package com.webbridgesdk.webbridgekit;

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
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public class WebViewBridge implements CameraManager.WebViewCallback {
    private Activity activity;
    private WebView webView;
    private BluetoothManager bluetoothManager;
    private CameraManager cameraManager;
    private MessageManager messageManager;
    private DeviceCompatibilityChecker compatibilityChecker;
    
    // 消息监听器接口
    public interface MessageListener {
        void onMessageReceived(String type, JSONObject data);
    }
    
    private List<MessageListener> messageListeners = new ArrayList<>();

    public WebViewBridge(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
        
        // 首先检查设备兼容性
        compatibilityChecker = new DeviceCompatibilityChecker(activity);
        if (!compatibilityChecker.isAndroidVersionSupported()) {
            throw new UnsupportedOperationException("Android version not supported. Minimum required: Android 5.0 (API 21)");
        }
        
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
        
        // 初始化消息管理器
        messageManager = new MessageManager(activity, this);
        
        // 添加JavaScript接口
        webView.addJavascriptInterface(bluetoothManager, "BluetoothInterface");
        webView.addJavascriptInterface(cameraManager, "CameraManager");
        webView.addJavascriptInterface(messageManager, "MessageBridge");
        webView.addJavascriptInterface(compatibilityChecker, "DeviceChecker");
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
    
    /**
     * 向H5发送消息
     * @param type 消息类型
     * @param data 消息数据
     */
    public void sendMessageToH5(String type, Object data) {
        if (messageManager != null) {
            messageManager.sendMessageToH5(type, data);
        }
    }
    
    /**
     * 注册消息监听器
     * @param listener 监听器
     */
    public void addMessageListener(MessageListener listener) {
        if (!messageListeners.contains(listener)) {
            messageListeners.add(listener);
        }
    }
    
    /**
     * 移除消息监听器
     * @param listener 监听器
     */
    public void removeMessageListener(MessageListener listener) {
        messageListeners.remove(listener);
    }
    
    /**
     * 当收到来自H5的消息时通知所有监听器
     * @param type 消息类型
     * @param data 消息数据
     */
    public void onMessageReceived(String type, JSONObject data) {
        for (MessageListener listener : messageListeners) {
            listener.onMessageReceived(type, data);
        }
    }

    /**
     * 获取设备兼容性信息
     */
    public String getDeviceCompatibilityInfo() {
        return compatibilityChecker.getDeviceInfo();
    }

    /**
     * 释放所有资源
     */
    public void release() {
        if (bluetoothManager != null) {
            bluetoothManager.release();
        }
        
        messageListeners.clear();
        
        // 清理WebView
        if (webView != null) {
            webView.removeJavascriptInterface("BluetoothInterface");
            webView.removeJavascriptInterface("CameraManager");
            webView.removeJavascriptInterface("MessageBridge");
            webView.removeJavascriptInterface("DeviceChecker");
        }
    }
}