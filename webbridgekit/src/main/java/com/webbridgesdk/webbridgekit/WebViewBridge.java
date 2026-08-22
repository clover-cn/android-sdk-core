package com.webbridgesdk.webbridgekit;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.JavascriptInterface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import com.webbridgesdk.webbridgekit.util.ValidationUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class WebViewBridge implements CameraManager.WebViewCallback {
    public static final String JS_BRIDGE_NAME = "WebBridgeNative";
    private static final String FALLBACK_BRIDGE_NAME = "WebBridgeNativeFallback";

    private final Activity activity;
    private final WebView webView;
    private final WebViewBridgeConfig config;
    private final List<MessageListener> messageListeners = new CopyOnWriteArrayList<>();
    private final Map<String, JavaScriptReplyProxy> pendingCameraReplies = new ConcurrentHashMap<>();
    private final Map<String, FallbackReplyProxy> pendingCameraFallbackReplies = new ConcurrentHashMap<>();

    private BluetoothManager bluetoothManager;
    private CameraManager cameraManager;
    private MessageManager messageManager;
    private DeviceCompatibilityChecker compatibilityChecker;
    private WebViewAssetLoader assetLoader;

    public interface MessageListener {
        void onMessageReceived(String type, JSONObject data);
    }

    public WebViewBridge(Activity activity, WebView webView, WebViewBridgeConfig config) {
        this.activity = activity;
        this.webView = webView;
        this.config = config;

        compatibilityChecker = new DeviceCompatibilityChecker(activity);
        if (!compatibilityChecker.isAndroidVersionSupported()) {
            throw new UnsupportedOperationException("Android version not supported. Minimum required: Android 5.0 (API 21)");
        }

        setupWebView();
        initManagers();
        installBridge();
    }

    private void setupWebView() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(config.isDebugEnabled());
        }

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(true);
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            WebSettingsCompat.setForceDark(settings, WebSettingsCompat.FORCE_DARK_OFF);
        }

        assetLoader = new WebViewAssetLoader.Builder()
                .setDomain(config.getAssetLoaderDomain())
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(activity))
                .build();

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !config.isUrlAllowed(request.getUrl().toString());
            }

            @Nullable
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }
        });
    }

    private void initManagers() {
        bluetoothManager = new BluetoothManager(activity, this);
        cameraManager = new CameraManager(activity, this);
        messageManager = new MessageManager(activity, this);
    }

    private void installBridge() {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(
                    webView,
                    JS_BRIDGE_NAME,
                    config.getAllowedOriginRules(),
                    this::onPostMessage
            );
            return;
        }

        webView.addJavascriptInterface(new FallbackBridge(), FALLBACK_BRIDGE_NAME);
    }

    private void onPostMessage(@NonNull WebView view,
                               @NonNull WebMessageCompat message,
                               @NonNull Uri sourceOrigin,
                               boolean isMainFrame,
                               @NonNull JavaScriptReplyProxy replyProxy) {
        if (!isMainFrame) {
            replyError(replyProxy, null, BridgeError.FORBIDDEN_FRAME);
            return;
        }

        String rawMessage = message.getData();
        if (rawMessage == null) {
            replyError(replyProxy, null, BridgeError.INVALID_MESSAGE);
            return;
        }

        try {
            JSONObject request = new JSONObject(rawMessage);
            String id = request.optString("id", UUID.randomUUID().toString());
            String feature = request.optString("feature", "");
            String action = request.optString("action", "");
            JSONObject payload = request.optJSONObject("payload");
            if (payload == null) {
                payload = new JSONObject();
            }

            if (requiresPermission(feature, action)) {
                JSONObject finalPayload = payload;
                config.getPermissionDelegate().ensurePermission(feature, action, granted -> {
                    if (!granted) {
                        replyError(replyProxy, id, BridgeError.PERMISSION_DENIED);
                        return;
                    }
                    dispatch(replyProxy, id, feature, action, finalPayload);
                });
                return;
            }

            dispatch(replyProxy, id, feature, action, payload);
        } catch (JSONException e) {
            replyError(replyProxy, null, BridgeError.INVALID_MESSAGE);
        } catch (Exception e) {
            replyError(replyProxy, null, new BridgeError(BridgeError.NATIVE_ERROR.getCode(), e.getMessage()));
        }
    }

    private void handleFallbackMessage(String rawMessage) {
        if (rawMessage == null) {
            sendFallbackError(null, BridgeError.INVALID_MESSAGE);
            return;
        }

        try {
            JSONObject request = new JSONObject(rawMessage);
            String id = request.optString("id", UUID.randomUUID().toString());
            String feature = request.optString("feature", "");
            String action = request.optString("action", "");
            JSONObject payload = request.optJSONObject("payload");
            if (payload == null) {
                payload = new JSONObject();
            }

            if (requiresPermission(feature, action)) {
                JSONObject finalPayload = payload;
                config.getPermissionDelegate().ensurePermission(feature, action, granted -> {
                    if (!granted) {
                        sendFallbackError(id, BridgeError.PERMISSION_DENIED);
                        return;
                    }
                    dispatchFallback(id, feature, action, finalPayload);
                });
                return;
            }

            dispatchFallback(id, feature, action, payload);
        } catch (JSONException e) {
            sendFallbackError(null, BridgeError.INVALID_MESSAGE);
        } catch (Exception e) {
            sendFallbackError(null, new BridgeError(BridgeError.NATIVE_ERROR.getCode(), e.getMessage()));
        }
    }

    private boolean requiresPermission(String feature, String action) {
        return "bluetooth".equals(feature) || ("camera".equals(feature) && "scanQr".equals(action));
    }

    private void dispatch(JavaScriptReplyProxy replyProxy,
                          String id,
                          String feature,
                          String action,
                          JSONObject payload) {
        try {
            if ("device".equals(feature) && "getInfo".equals(action)) {
                replySuccess(replyProxy, id, new JSONObject(compatibilityChecker.getDeviceInfo()));
                return;
            }

            if ("message".equals(feature) && "sendToApp".equals(action)) {
                String type = payload.optString("type", "message");
                JSONObject data = payload.optJSONObject("data");
                onMessageReceived(type, data == null ? new JSONObject() : data);
                replySuccess(replyProxy, id, new JSONObject().put("accepted", true));
                return;
            }

            if ("bluetooth".equals(feature)) {
                dispatchBluetooth(replyProxy, id, action, payload);
                return;
            }

            if ("camera".equals(feature) && "scanQr".equals(action)) {
                pendingCameraReplies.put(id, replyProxy);
                cameraManager.startQRCodeScan(id);
                return;
            }

            replyError(replyProxy, id, BridgeError.UNSUPPORTED_ACTION);
        } catch (JSONException e) {
            replyError(replyProxy, id, BridgeError.INVALID_MESSAGE);
        } catch (IllegalArgumentException e) {
            replyError(replyProxy, id, new BridgeError(BridgeError.INVALID_PARAMETER.getCode(), e.getMessage()));
        } catch (Exception e) {
            replyError(replyProxy, id, new BridgeError(BridgeError.NATIVE_ERROR.getCode(), e.getMessage()));
        }
    }

    private void dispatchBluetooth(JavaScriptReplyProxy replyProxy,
                                   String id,
                                   String action,
                                   JSONObject payload) throws JSONException {
        switch (action) {
            case "getStatus":
                replySuccess(replyProxy, id, new JSONObject(bluetoothManager.getBluetoothStatus()));
                break;
            case "getPairedDevices":
                replySuccess(replyProxy, id, new JSONObject().put("devices", new JSONArray(bluetoothManager.getPairedDevices())));
                break;
            case "connect": {
                String macAddress = payload.optString("macAddress", "");
                if (!ValidationUtils.isValidMacAddress(macAddress)) {
                    throw new IllegalArgumentException("macAddress is invalid");
                }
                bluetoothManager.connectToDevice(macAddress);
                replySuccess(replyProxy, id, new JSONObject().put("accepted", true));
                break;
            }
            case "disconnect":
                bluetoothManager.disconnect();
                replySuccess(replyProxy, id, new JSONObject().put("accepted", true));
                break;
            case "writeHex": {
                String serviceUUID = payload.optString("serviceUUID", "");
                String characteristicUUID = payload.optString("characteristicUUID", "");
                String hex = payload.optString("hex", "");
                if (!ValidationUtils.isValidUUID(serviceUUID) || !ValidationUtils.isValidUUID(characteristicUUID)) {
                    throw new IllegalArgumentException("serviceUUID or characteristicUUID is invalid");
                }
                if (!ValidationUtils.isValidHexString(hex)) {
                    throw new IllegalArgumentException("hex is invalid");
                }
                bluetoothManager.writeRawHexData(serviceUUID, characteristicUUID, hex);
                replySuccess(replyProxy, id, new JSONObject().put("accepted", true));
                break;
            }
            case "setNotificationsEnabled":
                bluetoothManager.setNotificationsEnabled(payload.optBoolean("enabled", true));
                replySuccess(replyProxy, id, new JSONObject().put("enabled", bluetoothManager.isNotificationsEnabled()));
                break;
            case "isNotificationsEnabled":
                replySuccess(replyProxy, id, new JSONObject().put("enabled", bluetoothManager.isNotificationsEnabled()));
                break;
            default:
                replyError(replyProxy, id, BridgeError.UNSUPPORTED_ACTION);
                break;
        }
    }

    private void dispatchFallback(String id, String feature, String action, JSONObject payload) {
        try {
            if ("device".equals(feature) && "getInfo".equals(action)) {
                sendFallbackSuccess(id, new JSONObject(compatibilityChecker.getDeviceInfo()));
                return;
            }

            if ("message".equals(feature) && "sendToApp".equals(action)) {
                String type = payload.optString("type", "message");
                JSONObject data = payload.optJSONObject("data");
                onMessageReceived(type, data == null ? new JSONObject() : data);
                sendFallbackSuccess(id, new JSONObject().put("accepted", true));
                return;
            }

            if ("bluetooth".equals(feature)) {
                dispatchBluetoothFallback(id, action, payload);
                return;
            }

            if ("camera".equals(feature) && "scanQr".equals(action)) {
                pendingCameraFallbackReplies.put(id, new FallbackReplyProxy(id));
                cameraManager.startQRCodeScan(id);
                return;
            }

            sendFallbackError(id, BridgeError.UNSUPPORTED_ACTION);
        } catch (JSONException e) {
            sendFallbackError(id, BridgeError.INVALID_MESSAGE);
        } catch (IllegalArgumentException e) {
            sendFallbackError(id, new BridgeError(BridgeError.INVALID_PARAMETER.getCode(), e.getMessage()));
        } catch (Exception e) {
            sendFallbackError(id, new BridgeError(BridgeError.NATIVE_ERROR.getCode(), e.getMessage()));
        }
    }

    private void dispatchBluetoothFallback(String id, String action, JSONObject payload) throws JSONException {
        switch (action) {
            case "getStatus":
                sendFallbackSuccess(id, new JSONObject(bluetoothManager.getBluetoothStatus()));
                break;
            case "getPairedDevices":
                sendFallbackSuccess(id, new JSONObject().put("devices", new JSONArray(bluetoothManager.getPairedDevices())));
                break;
            case "connect": {
                String macAddress = payload.optString("macAddress", "");
                if (!ValidationUtils.isValidMacAddress(macAddress)) {
                    throw new IllegalArgumentException("macAddress is invalid");
                }
                bluetoothManager.connectToDevice(macAddress);
                sendFallbackSuccess(id, new JSONObject().put("accepted", true));
                break;
            }
            case "disconnect":
                bluetoothManager.disconnect();
                sendFallbackSuccess(id, new JSONObject().put("accepted", true));
                break;
            case "writeHex": {
                String serviceUUID = payload.optString("serviceUUID", "");
                String characteristicUUID = payload.optString("characteristicUUID", "");
                String hex = payload.optString("hex", "");
                if (!ValidationUtils.isValidUUID(serviceUUID) || !ValidationUtils.isValidUUID(characteristicUUID)) {
                    throw new IllegalArgumentException("serviceUUID or characteristicUUID is invalid");
                }
                if (!ValidationUtils.isValidHexString(hex)) {
                    throw new IllegalArgumentException("hex is invalid");
                }
                bluetoothManager.writeRawHexData(serviceUUID, characteristicUUID, hex);
                sendFallbackSuccess(id, new JSONObject().put("accepted", true));
                break;
            }
            case "setNotificationsEnabled":
                bluetoothManager.setNotificationsEnabled(payload.optBoolean("enabled", true));
                sendFallbackSuccess(id, new JSONObject().put("enabled", bluetoothManager.isNotificationsEnabled()));
                break;
            case "isNotificationsEnabled":
                sendFallbackSuccess(id, new JSONObject().put("enabled", bluetoothManager.isNotificationsEnabled()));
                break;
            default:
                sendFallbackError(id, BridgeError.UNSUPPORTED_ACTION);
                break;
        }
    }

    private void replySuccess(JavaScriptReplyProxy replyProxy, String id, Object data) {
        try {
            JSONObject response = new JSONObject();
            response.put("id", id);
            response.put("ok", true);
            response.put("data", data);
            replyProxy.postMessage(response.toString());
        } catch (JSONException e) {
            replyError(replyProxy, id, BridgeError.INVALID_MESSAGE);
        }
    }

    private void replyError(JavaScriptReplyProxy replyProxy, String id, BridgeError error) {
        try {
            JSONObject response = new JSONObject();
            response.put("id", id == null ? JSONObject.NULL : id);
            response.put("ok", false);
            response.put("error", new JSONObject()
                    .put("code", error.getCode())
                    .put("message", error.getMessage()));
            replyProxy.postMessage(response.toString());
        } catch (JSONException ignored) {
            replyProxy.postMessage("{\"ok\":false,\"error\":{\"code\":\"INVALID_MESSAGE\",\"message\":\"消息格式错误\"}}");
        }
    }

    private void sendFallbackSuccess(String id, Object data) {
        try {
            sendFallbackEnvelope(new JSONObject()
                    .put("id", id)
                    .put("ok", true)
                    .put("data", data));
        } catch (JSONException e) {
            sendFallbackError(id, BridgeError.INVALID_MESSAGE);
        }
    }

    private void sendFallbackError(String id, BridgeError error) {
        try {
            sendFallbackEnvelope(new JSONObject()
                    .put("id", id == null ? JSONObject.NULL : id)
                    .put("ok", false)
                    .put("error", new JSONObject()
                            .put("code", error.getCode())
                            .put("message", error.getMessage())));
        } catch (JSONException ignored) {
            evaluateJavascript("window.__WebBridgeFallbackDispatch&&window.__WebBridgeFallbackDispatch({\"ok\":false,\"error\":{\"code\":\"INVALID_MESSAGE\",\"message\":\"消息格式错误\"}});");
        }
    }

    private void sendFallbackEnvelope(JSONObject envelope) {
        String script = "window.__WebBridgeFallbackDispatch&&window.__WebBridgeFallbackDispatch(" + envelope.toString() + ");";
        evaluateJavascript(script);
    }

    public void emitEvent(String event, JSONObject payload) {
        try {
            JSONObject envelope = new JSONObject()
                    .put("event", event)
                    .put("data", payload == null ? new JSONObject() : payload);
            String script = "window.WebBridge&&window.WebBridge.__dispatch(" + envelope.toString() + ");";
            evaluateJavascript(script);
        } catch (JSONException ignored) {
            // 事件序列化失败时不向 H5 发送半截消息。
        }
    }

    public void handleActivityResult(int requestCode, int resultCode, Intent data) {
        cameraManager.handleActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onQRCodeScanned(String requestId, String result) {
        JavaScriptReplyProxy replyProxy = pendingCameraReplies.remove(requestId);
        if (replyProxy != null) {
            try {
                replySuccess(replyProxy, requestId, new JSONObject().put("text", result));
            } catch (JSONException e) {
                replyError(replyProxy, requestId, BridgeError.NATIVE_ERROR);
            }
        }
        FallbackReplyProxy fallbackReplyProxy = pendingCameraFallbackReplies.remove(requestId);
        if (fallbackReplyProxy != null) {
            try {
                fallbackReplyProxy.replySuccess(new JSONObject().put("text", result));
            } catch (JSONException e) {
                fallbackReplyProxy.replyError(BridgeError.NATIVE_ERROR);
            }
        }
        try {
            emitEvent("camera.qrResult", new JSONObject().put("text", result));
        } catch (JSONException ignored) {
        }
    }

    @Override
    public void onError(String requestId, String error) {
        JavaScriptReplyProxy replyProxy = pendingCameraReplies.remove(requestId);
        BridgeError bridgeError = "扫码取消".equals(error)
                ? BridgeError.CAMERA_SCAN_CANCELLED
                : new BridgeError(BridgeError.NATIVE_ERROR.getCode(), error);
        if (replyProxy != null) {
            replyError(replyProxy, requestId, bridgeError);
        }
        FallbackReplyProxy fallbackReplyProxy = pendingCameraFallbackReplies.remove(requestId);
        if (fallbackReplyProxy != null) {
            fallbackReplyProxy.replyError(bridgeError);
        }
        try {
            emitEvent("bridge.error", new JSONObject()
                    .put("code", bridgeError.getCode())
                    .put("message", bridgeError.getMessage()));
        } catch (JSONException ignored) {
        }
    }

    public void loadUrl(String url) {
        webView.loadUrl(url);
    }

    public void evaluateJavascript(String script) {
        activity.runOnUiThread(() -> webView.evaluateJavascript(script, null));
    }

    public String getAssetUrl(String assetPath) {
        String normalized = assetPath.startsWith("/") ? assetPath.substring(1) : assetPath;
        return "https://" + config.getAssetLoaderDomain() + "/assets/" + normalized;
    }

    public void sendMessageToH5(String type, Object data) {
        if (messageManager != null) {
            messageManager.sendMessageToH5(type, data);
        }
    }

    public void addMessageListener(MessageListener listener) {
        if (!messageListeners.contains(listener)) {
            messageListeners.add(listener);
        }
    }

    public void removeMessageListener(MessageListener listener) {
        messageListeners.remove(listener);
    }

    public void onMessageReceived(String type, JSONObject data) {
        for (MessageListener listener : messageListeners) {
            listener.onMessageReceived(type, data);
        }
    }

    public String getDeviceCompatibilityInfo() {
        return compatibilityChecker.getDeviceInfo();
    }

    public void release() {
        if (bluetoothManager != null) {
            bluetoothManager.release();
        }
        pendingCameraReplies.clear();
        pendingCameraFallbackReplies.clear();
        messageListeners.clear();
        webView.removeJavascriptInterface(FALLBACK_BRIDGE_NAME);
    }

    private class FallbackBridge {
        @JavascriptInterface
        public void postMessage(String message) {
            activity.runOnUiThread(() -> handleFallbackMessage(message));
        }
    }

    private class FallbackReplyProxy {
        private final String id;

        FallbackReplyProxy(String id) {
            this.id = id;
        }

        void replySuccess(Object data) {
            sendFallbackSuccess(id, data);
        }

        void replyError(BridgeError error) {
            sendFallbackError(id, error);
        }
    }
}
