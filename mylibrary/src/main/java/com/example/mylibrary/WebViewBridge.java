package com.example.mylibrary;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import androidx.webkit.WebSettingsCompat;

public class WebViewBridge implements CameraManager.WebViewCallback {
    private Activity activity;
    private WebView webView;
    private BluetoothManager bluetoothManager;
    private CameraManager cameraManager;

    public WebViewBridge(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
        setupWebView();
        initManagers();
    }

    private void setupWebView() {
        // 启用WebView调试功能
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());
        
        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            WebSettingsCompat.setForceDark(webView.getSettings(), WebSettingsCompat.FORCE_DARK_OFF);
        }
    }

    private void initManagers() {
        // 确保蓝牙初始化在主线程完成，并具有一定的延迟
        activity.runOnUiThread(() -> {
            bluetoothManager = new BluetoothManager(activity, this);
            // 为蓝牙适配器准备时间
            android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
            handler.postDelayed(() -> {
                // 初始化操作完成后的回调
                evaluateJavascript("javascript:if(window.onBluetoothReady) window.onBluetoothReady()");
            }, 1000); // 给蓝牙适配器1秒的初始化时间
        });
        
        // 相机管理器可以立即初始化
        cameraManager = new CameraManager(activity, this);
        
        // 添加JavaScript接口
        webView.addJavascriptInterface(bluetoothManager, "BluetoothInterface");
        webView.addJavascriptInterface(cameraManager, "CameraManager");
    }

    public void handleActivityResult(int requestCode, int resultCode, Intent data) {
        cameraManager.handleActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onQRCodeScanned(String result) {
        final String js = String.format("javascript:window.onQRCodeResult('%s')", result);
        activity.runOnUiThread(() -> webView.evaluateJavascript(js, null));
    }

    @Override
    public void onError(String error) {
        final String js = String.format("javascript:window.onError('%s')", error);
        activity.runOnUiThread(() -> webView.evaluateJavascript(js, null));
    }

    public void loadUrl(String url) {
        webView.loadUrl(url);
    }

    // 新增：用于执行JavaScript代码
    public void evaluateJavascript(String script) {
        activity.runOnUiThread(() -> webView.evaluateJavascript(script, null));
    }
}