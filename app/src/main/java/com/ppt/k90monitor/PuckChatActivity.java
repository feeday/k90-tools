package com.ppt.k90monitor;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;

import java.util.ArrayList;
import java.util.List;

/** Locally bundled Puck Chat page. It does not load the puck.chat website. */
public class PuckChatActivity extends Activity {
    private static final String ASSET_URL = "https://appassets.androidplatform.net/assets/puck/index.html";
    private static final int REQUEST_FILE = 6102;

    private FrameLayout contentFrame;
    private WebView mainWebView;
    private WebView popupWebView;
    private LinearLayout popupLayer;
    private ValueCallback<Uri[]> fileChooserCallback;
    private WebViewAssetLoader assetLoader;
    private PuckSpeechBridge speechBridge;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();
        buildScreen();
        mainWebView.loadUrl(ASSET_URL);
    }

    private void buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(7, 8, 10));

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(12), dp(5), dp(12), dp(5));
        toolbar.setBackgroundColor(Color.rgb(15, 17, 20));

        Button back = toolbarButton("关闭");
        back.setOnClickListener(v -> finish());
        toolbar.addView(back, new LinearLayout.LayoutParams(dp(64), dp(42)));

        TextView title = new TextView(this);
        title.setText("K90-Tools · AI 工具箱");
        title.setTextColor(Color.WHITE);
        title.setTextSize(14);
        title.setPadding(dp(12), 0, 0, 0);
        title.setSingleLine(true);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));

        Button reload = toolbarButton("刷新");
        reload.setOnClickListener(v -> {
            if (mainWebView != null) mainWebView.reload();
        });
        toolbar.addView(reload, new LinearLayout.LayoutParams(dp(64), dp(42)));
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(50)));

        contentFrame = new FrameLayout(this);
        mainWebView = createWebView();
        speechBridge = new PuckSpeechBridge(mainWebView);
        mainWebView.addJavascriptInterface(speechBridge, "AndroidPuck");
        contentFrame.addView(mainWebView, new FrameLayout.LayoutParams(-1, -1));

        popupLayer = new LinearLayout(this);
        popupLayer.setOrientation(LinearLayout.VERTICAL);
        popupLayer.setBackgroundColor(Color.rgb(8, 10, 13));
        popupLayer.setVisibility(View.GONE);
        contentFrame.addView(popupLayer, new FrameLayout.LayoutParams(-1, -1));

        root.addView(contentFrame, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);

    }

    private WebView createWebView() {
        WebView view = new WebView(this);
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setDefaultTextEncodingName("UTF-8");

        view.setBackgroundColor(Color.rgb(7, 8, 10));
        view.setWebViewClient(new WebViewClient() {
            @Override public android.webkit.WebResourceResponse shouldInterceptRequest(
                    WebView webView, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }

            @Override public boolean shouldOverrideUrlLoading(WebView webView, WebResourceRequest request) {
                return routeNavigation(request.getUrl());
            }

            @Override public boolean shouldOverrideUrlLoading(WebView webView, String url) {
                return routeNavigation(Uri.parse(url));
            }
        });

        view.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView webView,
                    ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileChooserCallback != null) fileChooserCallback.onReceiveValue(null);
                fileChooserCallback = callback;
                try {
                    Intent intent = params.createIntent();
                    startActivityForResult(intent, REQUEST_FILE);
                    return true;
                } catch (ActivityNotFoundException e) {
                    fileChooserCallback = null;
                    Toast.makeText(PuckChatActivity.this, "没有可用的文件选择器", Toast.LENGTH_SHORT).show();
                    return false;
                }
            }

            @Override public boolean onCreateWindow(WebView webView, boolean isDialog,
                    boolean isUserGesture, android.os.Message resultMsg) {
                showPopupWindow(resultMsg);
                return true;
            }

            @Override public void onCloseWindow(WebView window) {
                closePopupWindow();
            }
        });
        return view;
    }

    private boolean routeNavigation(Uri uri) {
        if (uri == null) return true;
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if ("appassets.androidplatform.net".equalsIgnoreCase(host)
                || "about".equalsIgnoreCase(scheme)
                || "blob".equalsIgnoreCase(scheme)
                || "data".equalsIgnoreCase(scheme)) {
            return false;
        }
        // All external HTTP(S), mail and custom-scheme navigation is intentionally blocked.
        return true;
    }

    private void showPopupWindow(android.os.Message resultMsg) {
        closePopupWindow();
        popupWebView = createWebView();
        LinearLayout popupToolbar = new LinearLayout(this);
        popupToolbar.setGravity(Gravity.CENTER_VERTICAL);
        popupToolbar.setPadding(dp(10), dp(4), dp(10), dp(4));
        popupToolbar.setBackgroundColor(Color.rgb(18, 22, 27));
        TextView label = new TextView(this);
        label.setText("预览 / 新页面");
        label.setTextColor(Color.WHITE);
        label.setTextSize(13);
        popupToolbar.addView(label, new LinearLayout.LayoutParams(0, dp(42), 1f));
        Button close = toolbarButton("关闭");
        close.setOnClickListener(v -> closePopupWindow());
        popupToolbar.addView(close, new LinearLayout.LayoutParams(dp(64), dp(42)));
        popupLayer.removeAllViews();
        popupLayer.addView(popupToolbar, new LinearLayout.LayoutParams(-1, dp(50)));
        popupLayer.addView(popupWebView, new LinearLayout.LayoutParams(-1, 0, 1f));
        popupLayer.setVisibility(View.VISIBLE);

        android.webkit.WebView.WebViewTransport transport =
                (android.webkit.WebView.WebViewTransport) resultMsg.obj;
        transport.setWebView(popupWebView);
        resultMsg.sendToTarget();
    }

    private void closePopupWindow() {
        if (popupWebView != null) {
            try { popupWebView.stopLoading(); popupWebView.destroy(); } catch (Throwable ignored) { }
            popupWebView = null;
        }
        if (popupLayer != null) {
            popupLayer.removeAllViews();
            popupLayer.setVisibility(View.GONE);
        }
    }

    private Button toolbarButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(12);
        button.setAllCaps(false);
        button.setPadding(dp(4), 0, dp(4), 0);
        return button;
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_FILE && fileChooserCallback != null) {
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    List<Uri> uris = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) uris.add(data.getClipData().getItemAt(i).getUri());
                    results = uris.toArray(new Uri[0]);
                } else if (data.getData() != null) {
                    results = new Uri[]{data.getData()};
                }
            }
            fileChooserCallback.onReceiveValue(results);
            fileChooserCallback = null;
        }
    }

    @Override public void onBackPressed() {
        if (popupLayer != null && popupLayer.getVisibility() == View.VISIBLE) {
            closePopupWindow();
            return;
        }
        if (mainWebView != null && mainWebView.canGoBack()) {
            mainWebView.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override protected void onDestroy() {
        if (fileChooserCallback != null) {
            fileChooserCallback.onReceiveValue(null);
            fileChooserCallback = null;
        }
        closePopupWindow();
        if (speechBridge != null) { speechBridge.cancelTts(); speechBridge.cancelLocalTts(); }
        if (mainWebView != null) {
            try { mainWebView.stopLoading(); mainWebView.removeJavascriptInterface("AndroidPuck"); mainWebView.destroy(); }
            catch (Throwable ignored) { }
            mainWebView = null;
        }
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
