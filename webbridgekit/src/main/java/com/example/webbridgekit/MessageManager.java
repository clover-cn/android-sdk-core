package com.example.webbridgekit;

import android.app.Activity;
import android.webkit.JavascriptInterface;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * MessageManager类 - 用于处理App与H5之间的双向通信
 */
public class MessageManager {
    private Activity activity;
    private WebViewBridge webViewBridge;

    public MessageManager(Activity activity, WebViewBridge webViewBridge) {
        this.activity = activity;
        this.webViewBridge = webViewBridge;
    }

    /**
     * 发送消息到H5
     * @param msgType 消息类型
     * @param data 消息数据
     */
    public void sendMessageToH5(String msgType, Object data) {
        try {
            JSONObject message = new JSONObject();
            message.put("type", msgType);
            message.put("data", data);
            
            final String script = String.format(
                "javascript:window.onAppMessage(%s)",
                message.toString()
            );
            
            activity.runOnUiThread(() -> webViewBridge.evaluateJavascript(script));
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    /**
     * JavaScript接口方法，允许H5向App发送消息
     * @param messageJson 消息JSON字符串
     */
    @JavascriptInterface
    public void sendMessageToApp(String messageJson) {
        try {
            JSONObject message = new JSONObject(messageJson);
            String type = message.getString("type");
            JSONObject data = message.optJSONObject("data");
            
            // 将消息分发给注册的监听器
            webViewBridge.onMessageReceived(type, data);
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }
    
    /**
     * 获取当前设备信息
     * @return 设备信息JSON字符串
     */
    @JavascriptInterface
    public String getDeviceInfo() {
        try {
            JSONObject info = new JSONObject();
            info.put("model", android.os.Build.MODEL);
            info.put("manufacturer", android.os.Build.MANUFACTURER);
            info.put("os", "Android");
            info.put("osVersion", android.os.Build.VERSION.RELEASE);
            return info.toString();
        } catch (JSONException e) {
            e.printStackTrace();
            return "{}";
        }
    }
}