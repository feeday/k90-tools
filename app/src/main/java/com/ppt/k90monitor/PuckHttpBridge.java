package com.ppt.k90monitor;

import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import org.json.JSONObject;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import okhttp3.*;

/** JSON transport for the bundled page; credentials are never logged or persisted. */
public final class PuckHttpBridge {
    private final WebView webView;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean closed;
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(240, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(270, TimeUnit.SECONDS)
            .followRedirects(false)
            .build();

    public PuckHttpBridge(WebView view) { webView = view; }

    @JavascriptInterface public void request(String id, String url, String method,
                                              String headersJson, String body) {
        if (closed) return;
        try {
            HttpUrl endpoint = HttpUrl.parse(url);
            if (endpoint == null || !(endpoint.scheme().equals("http") || endpoint.scheme().equals("https")))
                throw new IllegalArgumentException("API 地址必须为 HTTP 或 HTTPS");
            if (!method.equals("POST") && !method.equals("GET"))
                throw new IllegalArgumentException("不支持的请求方法");
            JSONObject headers = new JSONObject(headersJson);
            Request.Builder builder = new Request.Builder().url(endpoint)
                    .header("Accept", "application/json");
            String key = headers.optString("Authorization", "");
            if (!key.isEmpty()) builder.header("Authorization", key);
            if (method.equals("POST")) builder.post(RequestBody.create(
                    body, MediaType.parse("application/json; charset=utf-8")));
            client.newCall(builder.build()).enqueue(new Callback() {
                @Override public void onFailure(Call call, IOException error) {
                    deliver(id, 0, "", "网络连接失败或超时，请检查 API 地址及网络");
                }
                @Override public void onResponse(Call call, Response response) {
                    try (Response result = response) {
                        deliver(id, result.code(), result.body() == null ? "" : result.body().string(), "");
                    } catch (IOException error) {
                        deliver(id, 0, "", "读取 API 响应失败");
                    }
                }
            });
        } catch (Exception error) {
            deliver(id, 0, "", "请求参数无效，请检查 API 地址和认证设置");
        }
    }

    private void deliver(String id, int status, String body, String error) {
        if (closed) return;
        try {
            JSONObject payload = new JSONObject().put("id", id).put("status", status)
                    .put("body", body).put("error", error);
            handler.post(() -> {
                if (!closed) webView.evaluateJavascript(
                        "window.onNativeHttpResult && window.onNativeHttpResult(" + payload + ");", null);
            });
        } catch (Exception ignored) { }
    }

    public void cancelAll() { client.dispatcher().cancelAll(); }
    public void close() {
        closed = true;
        cancelAll();
        handler.removeCallbacksAndMessages(null);
        client.connectionPool().evictAll();
    }
}
