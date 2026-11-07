package com.ppt.k90monitor;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * Native Doubao and local TTS transport for the locally bundled K90-Tools page.
 * API keys are supplied by the page at request time and are never persisted here.
 */
public final class PuckSpeechBridge {
    private static final String TTS_URL = "wss://openspeech.bytedance.com/api/v3/tts/unidirectional/stream";
    private static final int MAX_TTS_TEXT = 10_000;
    private static final long MAX_SAVED_FILE_BYTES = 512L * 1024L * 1024L;
    private static final String SAVE_FOLDER = "K90-Tools";
    private static final long MAX_LOCAL_TTS_BYTES = 24L * 1024L * 1024L;
    private static final String LOCAL_TTS_LOOPBACK = "http://127.0.0.1:8080/api/tts";
    private static final String LOCAL_TTS_LAN = "http://192.168.31.11:8080/api/tts";

    private final WebView webView;
    private final Context appContext;
    private final Map<String, SaveSession> saveSessions = new ConcurrentHashMap<>();
    private final OkHttpClient client;
    private final OkHttpClient localTtsClient;
    private volatile Call localTtsCall;
    private volatile WebSocket ttsSocket;
    private volatile boolean ttsCancelled;
    private volatile boolean ttsFinished;
    private final ByteArrayOutputStream ttsAudio = new ByteArrayOutputStream();

    public PuckSpeechBridge(WebView webView) {
        this.webView = webView;
        this.appContext = webView.getContext().getApplicationContext();
        this.client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .build();
        this.localTtsClient = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .callTimeout(100, TimeUnit.SECONDS)
                .build();
    }

    private static final class SaveSession {
        final Uri uri;
        final File file;
        final OutputStream output;
        final String displayPath;
        long bytes;
        SaveSession(Uri uri, File file, OutputStream output, String displayPath) {
            this.uri = uri; this.file = file; this.output = output; this.displayPath = displayPath;
        }
    }

    @JavascriptInterface
    public String beginSaveFile(String fileName, String mimeType) {
        String safeName = sanitizeFileName(fileName);
        String type = mimeType == null || mimeType.trim().isEmpty() ? "application/octet-stream" : mimeType.trim();
        String id = UUID.randomUUID().toString();
        Uri uri = null;
        boolean created = false;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentResolver resolver = appContext.getContentResolver();
                Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                String relativePath = Environment.DIRECTORY_DOWNLOADS + "/" + SAVE_FOLDER + "/";
                uri = findExistingDownload(resolver, collection, safeName, relativePath);
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, safeName);
                values.put(MediaStore.MediaColumns.MIME_TYPE, type);
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath);
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                if (uri == null) {
                    uri = resolver.insert(collection, values);
                    created = true;
                } else {
                    resolver.update(uri, values, null, null);
                }
                if (uri == null) throw new IOException("无法在 Downloads 中创建文件");
                OutputStream output = resolver.openOutputStream(uri, "w");
                if (output == null) throw new IOException("无法打开保存文件");
                saveSessions.put(id, new SaveSession(uri, null, output, "Download/" + SAVE_FOLDER + "/" + safeName));
            } else {
                File base = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (base == null) base = appContext.getFilesDir();
                File dir = new File(base, SAVE_FOLDER);
                if (!dir.exists() && !dir.mkdirs()) throw new IOException("无法创建 K90-Tools 下载目录");
                File file = new File(dir, safeName);
                OutputStream output = new FileOutputStream(file, false);
                saveSessions.put(id, new SaveSession(null, file, output, file.getAbsolutePath()));
            }
            return id;
        } catch (Exception error) {
            if (uri != null) {
                try {
                    if (created) appContext.getContentResolver().delete(uri, null, null);
                    else {
                        ContentValues restore = new ContentValues();
                        restore.put(MediaStore.MediaColumns.IS_PENDING, 0);
                        appContext.getContentResolver().update(uri, restore, null, null);
                    }
                } catch (Throwable ignored) { }
            }
            return "ERROR:" + safeMessage(error);
        }
    }

    private Uri findExistingDownload(ContentResolver resolver, Uri collection, String name, String relativePath) {
        String[] columns = {"_id"};
        String selection = MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " + MediaStore.MediaColumns.RELATIVE_PATH + "=?";
        try (Cursor cursor = resolver.query(collection, columns, selection, new String[]{name, relativePath}, MediaStore.MediaColumns.DATE_MODIFIED + " DESC")) {
            if (cursor != null && cursor.moveToFirst()) {
                return ContentUris.withAppendedId(collection, cursor.getLong(0));
            }
        } catch (Exception ignored) { }
        return null;
    }

    @JavascriptInterface
    public boolean appendSaveFile(String id, String base64Chunk) {
        SaveSession session = saveSessions.get(id);
        if (session == null || base64Chunk == null) return false;
        try {
            byte[] data = Base64.decode(base64Chunk, Base64.DEFAULT);
            synchronized (session) {
                if (session.bytes + data.length > MAX_SAVED_FILE_BYTES) return false;
                session.output.write(data);
                session.bytes += data.length;
            }
            return true;
        } catch (Exception error) {
            return false;
        }
    }

    @JavascriptInterface
    public String finishSaveFile(String id) {
        SaveSession session = saveSessions.remove(id);
        if (session == null) return "ERROR:保存任务不存在";
        try {
            synchronized (session) {
                session.output.flush();
                session.output.close();
            }
            if (session.uri != null) {
                ContentValues ready = new ContentValues();
                ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
                appContext.getContentResolver().update(session.uri, ready, null, null);
            }
            return session.displayPath;
        } catch (Exception error) {
            deleteSaveSession(session);
            return "ERROR:" + safeMessage(error);
        }
    }

    @JavascriptInterface
    public void cancelSaveFile(String id) {
        SaveSession session = saveSessions.remove(id);
        if (session != null) deleteSaveSession(session);
    }

    private void deleteSaveSession(SaveSession session) {
        try { synchronized (session) { session.output.close(); } } catch (Throwable ignored) { }
        try {
            if (session.uri != null) appContext.getContentResolver().delete(session.uri, null, null);
            else if (session.file != null) session.file.delete();
        } catch (Throwable ignored) { }
    }

    @JavascriptInterface
    public String saveTextFile(String fileName, String mimeType, String content) {
        String id = beginSaveFile(fileName, mimeType);
        if (id.startsWith("ERROR:")) return id;
        String value = content == null ? "" : content;
        String encoded = Base64.encodeToString(value.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        if (!appendSaveFile(id, encoded)) {
            cancelSaveFile(id);
            return "ERROR:写入文本文件失败";
        }
        return finishSaveFile(id);
    }

    private static String sanitizeFileName(String value) {
        String raw = value == null ? "K90-Tools-file.bin" : value.trim();
        StringBuilder safe = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c < 32 || c == '/' || c == '\\' || c == ':' || c == '*' || c == '?' || c == '"' || c == '<' || c == '>' || c == '|') safe.append('_');
            else safe.append(c);
        }
        String name = safe.toString().replace("..", "_").trim();
        if (name.isEmpty() || ".".equals(name)) name = "K90-Tools-file.bin";
        if (name.length() > 120) name = name.substring(name.length() - 120);
        return name;
    }

    @JavascriptInterface
    public void synthesize(String text, String apiKey, String resourceId, String speaker) {
        cancelTts();
        cancelLocalTts();
        String value = text == null ? "" : text.trim();
        String key = apiKey == null ? "" : apiKey.trim();
        if (value.isEmpty()) {
            ttsEvent("error", "请输入需要朗读的文字");
            return;
        }
        if (value.length() > MAX_TTS_TEXT) {
            ttsEvent("error", "单次朗读最多支持 10000 个字符");
            return;
        }
        if (key.isEmpty()) {
            ttsEvent("error", "请先在豆包语音服务设置中填写 TTS API Key");
            return;
        }
        String resource = resourceId == null || resourceId.trim().isEmpty() ? "seed-tts-2.0" : resourceId.trim();
        String voice = speaker == null || speaker.trim().isEmpty()
                ? "zh_female_vv_uranus_bigtts" : speaker.trim();
        ttsCancelled = false;
        ttsFinished = false;
        synchronized (ttsAudio) { ttsAudio.reset(); }

        Request request = new Request.Builder()
                .url(TTS_URL)
                .header("X-Api-Key", key)
                .header("X-Api-Resource-Id", resource)
                .header("X-Api-Request-Id", UUID.randomUUID().toString())
                .header("X-Control-Require-Usage-Tokens-Return", "*")
                .build();

        ttsSocket = client.newWebSocket(request, new WebSocketListener() {
            @Override public void onOpen(WebSocket socket, Response response) {
                try {
                    JSONObject audioParams = new JSONObject()
                            .put("format", "mp3")
                            .put("sample_rate", 24000)
                            .put("speech_rate", 0)
                            .put("loudness_rate", 0);
                    JSONObject additions = new JSONObject()
                            .put("disable_markdown_filter", false)
                            .put("disable_emoji_filter", true);
                    JSONObject reqParams = new JSONObject()
                            .put("text", value)
                            .put("speaker", voice)
                            .put("audio_params", audioParams)
                            .put("additions", additions.toString());
                    JSONObject payload = new JSONObject()
                            .put("user", new JSONObject().put("uid", "k90-puck-chat"))
                            .put("req_params", reqParams);
                    socket.send(ByteString.of(fullClientFrame(payload.toString())));
                } catch (Exception e) {
                    failTts("初始化豆包 TTS 失败：" + safeMessage(e));
                }
            }

            @Override public void onMessage(WebSocket socket, ByteString bytes) {
                if (ttsCancelled || ttsFinished) return;
                try {
                    TtsPacket packet = parseTtsFrame(bytes.toByteArray());
                    if (packet.error != null) {
                        failTts(packet.error);
                        return;
                    }
                    if (packet.messageType == 0x0b && packet.event == 352 && packet.payload.length > 0) {
                        synchronized (ttsAudio) { ttsAudio.write(packet.payload); }
                    }
                    if (packet.messageType == 0x09 && packet.event == 152) {
                        JSONObject meta = new JSONObject(new String(packet.payload, StandardCharsets.UTF_8));
                        int status = meta.optInt("status_code", 20000000);
                        if (status != 20000000) {
                            failTts(meta.optString("message", "TTS 状态码 " + status));
                            return;
                        }
                        finishTts();
                    }
                } catch (Exception e) {
                    failTts("解析 TTS 返回数据失败：" + safeMessage(e));
                }
            }

            @Override public void onFailure(WebSocket socket, Throwable t, Response response) {
                failTts("连接豆包 TTS 失败：" + safeMessage(t));
            }

            @Override public void onClosed(WebSocket socket, int code, String reason) {
                if (!ttsCancelled && !ttsFinished) {
                    synchronized (ttsAudio) {
                        if (ttsAudio.size() > 0) finishTts();
                        else failTts("豆包 TTS 连接提前关闭：" + reason);
                    }
                }
            }
        });

        webView.postDelayed(() -> {
            if (!ttsFinished && !ttsCancelled) failTts("豆包 TTS 请求超时");
        }, 90_000);
    }

    @JavascriptInterface
    public void synthesizeLocal(String text, String endpoint, String voice) {
        cancelTts();
        cancelLocalTts();
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()) {
            localTtsEvent("error", "请输入需要朗读的文字");
            return;
        }
        if (value.length() > MAX_TTS_TEXT) {
            localTtsEvent("error", "单次朗读最多支持 10000 个字符");
            return;
        }
        String url = endpoint == null ? "" : endpoint.trim();
        if (!LOCAL_TTS_LOOPBACK.equals(url) && !LOCAL_TTS_LAN.equals(url)) {
            localTtsEvent("error", "本地 TTS 地址只允许 127.0.0.1 或 192.168.31.11 的 8080/api/tts 服务");
            return;
        }
        try {
            JSONObject payload = new JSONObject().put("text", value);
            String speaker = voice == null ? "" : voice.trim();
            if (!speaker.isEmpty()) payload.put("voice", speaker);
            MediaType jsonType = MediaType.parse("application/json; charset=utf-8");
            RequestBody body = RequestBody.create(jsonType, payload.toString());
            Request request = new Request.Builder().url(url).post(body).build();
            Call call = localTtsClient.newCall(request);
            localTtsCall = call;
            call.enqueue(new Callback() {
                @Override public void onFailure(Call call, IOException error) {
                    if (localTtsCall == call) localTtsCall = null;
                    if (!call.isCanceled()) localTtsEvent("error", "本地 TTS 连接失败：" + safeMessage(error));
                }

                @Override public void onResponse(Call call, Response response) {
                    try (Response closeable = response) {
                        if (!closeable.isSuccessful()) {
                            throw new IOException("本地 TTS HTTP " + closeable.code() + " " + closeable.message());
                        }
                        ResponseBody responseBody = closeable.body();
                        if (responseBody == null) throw new IOException("本地 TTS 没有返回音频数据");
                        MediaType type = responseBody.contentType();
                        String contentType = type == null ? "audio/mpeg" : type.toString();
                        long length = responseBody.contentLength();
                        if (length > MAX_LOCAL_TTS_BYTES) throw new IOException("本地 TTS 音频超过 24 MB 限制");
                        byte[] audio = responseBody.bytes();
                        if (audio.length == 0) throw new IOException("本地 TTS 返回了空音频");
                        if (audio.length > MAX_LOCAL_TTS_BYTES) throw new IOException("本地 TTS 音频超过 24 MB 限制");
                        if (localTtsCall == call) localTtsCall = null;
                        if (call.isCanceled()) return;
                        JSONObject event = new JSONObject()
                                .put("type", "done")
                                .put("contentType", contentType)
                                .put("audioBase64", Base64.encodeToString(audio, Base64.NO_WRAP));
                        sendJs("onNativeLocalTtsResult", event);
                    } catch (Exception error) {
                        if (localTtsCall == call) localTtsCall = null;
                        if (!call.isCanceled()) localTtsEvent("error", "本地 TTS 处理失败：" + safeMessage(error));
                    }
                }
            });
        } catch (Exception error) {
            localTtsEvent("error", "本地 TTS 请求失败：" + safeMessage(error));
        }
    }

    @JavascriptInterface
    public void cancelLocalTts() {
        Call call = localTtsCall;
        localTtsCall = null;
        if (call != null) call.cancel();
    }

    private void localTtsEvent(String type, String message) {
        JSONObject event = new JSONObject();
        try {
            event.put("type", type);
            if (message != null) event.put("message", message);
        } catch (Exception ignored) { }
        sendJs("onNativeLocalTtsResult", event);
    }

    @JavascriptInterface
    public void cancelTts() {
        ttsCancelled = true;
        ttsFinished = true;
        WebSocket socket = ttsSocket;
        ttsSocket = null;
        if (socket != null) socket.close(1000, "TTS cancelled by page");
    }

    private void finishTts() {
        if (ttsFinished || ttsCancelled) return;
        ttsFinished = true;
        byte[] audio;
        synchronized (ttsAudio) { audio = ttsAudio.toByteArray(); }
        WebSocket socket = ttsSocket;
        ttsSocket = null;
        if (socket != null) socket.close(1000, "TTS complete");
        if (audio.length == 0) {
            ttsEvent("error", "豆包 TTS 没有返回音频，请检查音色 ID 和模型权限");
            return;
        }
        JSONObject event = new JSONObject();
        try {
            event.put("type", "done");
            event.put("audioBase64", Base64.encodeToString(audio, Base64.NO_WRAP));
        } catch (Exception ignored) { }
        sendJs("onNativeTtsResult", event);
    }

    private void failTts(String message) {
        if (ttsFinished || ttsCancelled) return;
        ttsFinished = true;
        WebSocket socket = ttsSocket;
        ttsSocket = null;
        if (socket != null) socket.close(1011, "TTS failed");
        ttsEvent("error", message);
    }

    private void ttsEvent(String type, String message) {
        JSONObject event = new JSONObject();
        try {
            event.put("type", type);
            if (message != null) event.put("message", message);
        } catch (Exception ignored) { }
        sendJs("onNativeTtsResult", event);
    }

    private void sendJs(String callback, JSONObject payload) {
        if (webView == null || payload == null) return;
        String script = "if(window." + callback + ")window." + callback + "(" + payload.toString() + ");";
        webView.post(() -> {
            try { webView.evaluateJavascript(script, null); } catch (Throwable ignored) { }
        });
    }

    private static byte[] fullClientFrame(String json) {
        return frame(new byte[]{0x11, 0x10, 0x10, 0x00}, json.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] frame(byte[] header, byte[] payload) {
        ByteBuffer buffer = ByteBuffer.allocate(header.length + 4 + payload.length).order(ByteOrder.BIG_ENDIAN);
        buffer.put(header);
        buffer.putInt(payload.length);
        buffer.put(payload);
        return buffer.array();
    }

    private static final class TtsPacket {
        final int messageType;
        final int event;
        final byte[] payload;
        final String error;
        TtsPacket(int messageType, int event, byte[] payload, String error) {
            this.messageType = messageType; this.event = event; this.payload = payload; this.error = error;
        }
    }

    private static TtsPacket parseTtsFrame(byte[] data) throws Exception {
        if (data == null || data.length < 8) throw new IOException("豆包 TTS 返回了不完整的数据帧");
        int headerSize = (data[0] & 0x0f) * 4;
        int messageType = (data[1] >> 4) & 0x0f;
        int flags = data[1] & 0x0f;
        int compression = data[2] & 0x0f;
        int offset = headerSize;
        if (messageType == 0x0f) {
            if (data.length < offset + 8) throw new IOException("豆包 TTS 返回错误帧");
            int code = readInt(data, offset);
            int size = readInt(data, offset + 4);
            return new TtsPacket(messageType, -1, new byte[0],
                    "TTS 上游错误 " + code + ": " + safeUtf8(data, offset + 8, size));
        }
        int event = -1;
        if ((flags & 0x04) != 0) {
            if (data.length < offset + 8) throw new IOException("豆包 TTS 事件帧不完整");
            event = readInt(data, offset);
            int sessionSize = readInt(data, offset + 4);
            offset += 8 + sessionSize;
        } else if ((flags & 0x01) != 0 || (flags & 0x03) != 0) {
            offset += 4;
        }
        if (offset < 0 || data.length < offset + 4) throw new IOException("豆包 TTS 帧缺少 payload 长度");
        int payloadSize = readInt(data, offset);
        offset += 4;
        if (payloadSize < 0 || payloadSize > data.length - offset) throw new IOException("豆包 TTS payload 长度不匹配");
        byte[] payload = slice(data, offset, payloadSize);
        if (compression == 0x01) payload = gunzip(payload);
        return new TtsPacket(messageType, event, payload, null);
    }

    private static int readInt(byte[] data, int offset) {
        return ByteBuffer.wrap(data, offset, 4).order(ByteOrder.BIG_ENDIAN).getInt();
    }

    private static byte[] slice(byte[] data, int offset, int size) {
        byte[] out = new byte[size];
        System.arraycopy(data, offset, out, 0, size);
        return out;
    }

    private static String safeUtf8(byte[] data, int offset, int size) {
        int length = Math.max(0, Math.min(size, data.length - Math.max(0, offset)));
        if (offset < 0 || offset > data.length) return "";
        return new String(data, offset, length, StandardCharsets.UTF_8);
    }

    private static byte[] gunzip(byte[] compressed) throws IOException {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(compressed));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return out.toByteArray();
        }
    }

    private static String safeMessage(Throwable error) {
        String message = error == null ? "" : error.getMessage();
        return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message;
    }
}
