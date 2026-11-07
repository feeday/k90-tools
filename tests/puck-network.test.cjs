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
test('speech removes nested, encoded and unfinished thinking, retaining only Chinese and English',()=>{
 const c={};vm.createContext(c);
 vm.runInContext(html.slice(html.indexOf('function speechOnlyText('),html.indexOf('function speakText(')),c);
 assert.equal(c.speechOnlyText('<think>秘密 secret</think>你好 Hello 123 + 🌟'),'你好 Hello');
 assert.equal(c.speechOnlyText('&lt;think&gt;不要读&lt;/think&gt;答案 answer'),'答案 answer');
 assert.equal(c.speechOnlyText('<think>first<think>nested</think>last</think>正文'),'正文');
 assert.equal(c.speechOnlyText('<think>未结束的思考'),'');
 assert.equal(c.speechOnlyText('[THINK]secret[/THINK]Hello **中文** https://example.com 12'),'Hello 中文');
 assert.equal(c.speechOnlyText('```python\nprint("secret")\n``` [答案](https://example.com)'),'答案');
});
test('all speech engines receive filtered text',()=>{
 for(const engine of ['local','doubao','system']){
  let spoken;
  const c={window:{},String,ttsBusy:false,nativeTtsPending:false,$:()=>({}),saveField:id=>({ttsProvider:engine==='local'?'local':'doubao',ttsApiKey:engine==='system'?'':'test'}[id]||''),updateMemoryStatus(){},AndroidPuck:{synthesizeLocal:t=>spoken=t,synthesize:t=>spoken=t,speakSystem:t=>spoken=t}};
  c.window.AndroidPuck=c.AndroidPuck;vm.createContext(c);
  vm.runInContext(html.slice(html.indexOf('function speechOnlyText('),html.indexOf("bind('btnTts',")),c);
  assert.equal(c.speakText('<think>secret</think>你好 English 123',true),true);
  assert.equal(spoken,'你好 English');
 }
});
test('cloud failure is visible, resets send button and clears progress timer',async()=>{
 const fields={unifiedModel:{value:'deepseek-v4-flash'},btnCombinedAction:{disabled:false},responseWrap:{scrollIntoView(){}}};
 let message,cleared=false;
 const c={window:{},Date,performance:{now:()=>1},$:(id)=>fields[id],txt:{value:'hello'},modifiedImgSrc:'',originalImgSrc:'',overlayAutoSpeakNext:false,IMAGE_MODELS:[],setInterval:()=>123,clearInterval:id=>{cleared=id===123},log(){},alert:m=>message=m,saveField:()=> 'key',getBase:()=> 'https://api.poixe.com',completionUrl:x=>x+'/v1/chat/completions',chatContext:[],apiJson:async()=>{throw Error('HTTP 401: invalid key')},onModelChange(){}};
 vm.createContext(c);
 vm.runInContext(html.slice(html.indexOf('async function runAi('),html.indexOf("bind('btnApiCheck',")),c);
 await c.runAi();
 assert.match(message,/HTTP 401/);assert.equal(fields.btnCombinedAction.disabled,false);assert.equal(cleared,true);
});
