const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const html=fs.readFileSync('app/src/main/assets/puck/index.html','utf8');
function context(bridge){
 const c={URL,Map,Date,Promise,Error,JSON,setTimeout,clearTimeout,fetch:()=>{throw Error('Unexpected browser fetch')}};
 c.window=c;if(bridge)c.AndroidHttp=bridge;
 vm.createContext(c);
 vm.runInContext(html.slice(html.indexOf('function completionUrl('),html.indexOf('async function imageBlob')),c);
 return c;
}
test('all inline JavaScript parses',()=>{
 let count=0;
 for(const [,attrs,body] of html.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script>/gi)){
  if(/\bsrc\s*=/.test(attrs)||!body.trim())continue;
  new vm.Script(body);count++;
 }
 assert.ok(count>0);
});
test('normalizes local, versioned and complete endpoints',()=>{
 const c=context();
 for(const base of [' http://127.0.0.1:5001/ ','http://127.0.0.1:5001/v1/','http://127.0.0.1:5001/v1/chat/completions'])
  assert.equal(c.completionUrl(base),'http://127.0.0.1:5001/v1/chat/completions');
 assert.throws(()=>c.completionUrl(''),/HTTP/);
});
test('native request preserves JSON body and authorization, bypassing fetch',async()=>{
 let c; c=context({request(id,url,method,headers,body){
  assert.equal(url,'http://localhost:5001/v1/chat/completions');
  assert.equal(method,'POST');assert.equal(JSON.parse(headers).Authorization,'Bearer test');
  assert.equal(JSON.parse(body).stream,false);
  c.onNativeHttpResult({id,status:200,body:'{"choices":[{"message":{"content":"ok"}}]}'});
 }});
 const result=await c.apiJson('http://localhost:5001/v1/chat/completions',{method:'POST',headers:{Authorization:'Bearer test'},body:'{"stream":false}'});
 assert.equal(result.choices[0].message.content,'ok');
});
test('native HTTP and malformed-response errors are surfaced',async()=>{
 for(const [status,body,expected] of [[401,'{"error":{"message":"invalid key"}}',/HTTP 401: invalid key/],[502,'bad gateway',/非 JSON（HTTP 502）/]]){
  let c;c=context({request(id){c.onNativeHttpResult({id,status,body})}});
  await assert.rejects(c.apiJson('https://example.test'),expected);
 }
});
test('concurrent native callbacks resolve the correct request',async()=>{
 const ids=[];const c=context({request(id){ids.push(id)}});
 const first=c.apiJson('http://localhost/a'),second=c.apiJson('http://localhost/b');
 c.onNativeHttpResult({id:ids[1],status:200,body:'{"value":2}'});
 c.onNativeHttpResult({id:ids[0],status:200,body:'{"value":1}'});
 assert.equal((await first).value,1);assert.equal((await second).value,2);
});
test('overlay allows local model without key and refuses to overwrite busy input',()=>{
 const fields={unifiedModel:{value:'custom-chat'},btnCombinedAction:{disabled:false}};
 const c={window:{},String,$:id=>fields[id],TEXT_MODELS:[],saveField:id=>({customApiUrl:'http://localhost:5001',customModelName:'local-model'}[id]||''),txt:{value:''},updateStats(){},runAi(){c.sent=true},log(){throw Error('should send without key')}};
 vm.createContext(c);
 vm.runInContext(html.slice(html.indexOf('window.receiveOverlayPrompt=function'),html.indexOf('let currentUrl=')),c);
 assert.equal(c.window.receiveOverlayPrompt('hello'),true);assert.equal(c.sent,true);
 fields.btnCombinedAction.disabled=true;
 assert.equal(c.window.receiveOverlayPrompt('second'),false);assert.equal(c.txt.value,'hello');
});
