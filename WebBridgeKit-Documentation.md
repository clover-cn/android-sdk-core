# WebBridgeKit v2 开发文档

## 简介

WebBridgeKit 是一个 Android WebView 原生能力桥接库，支持 H5 调用设备蓝牙、二维码扫码、设备信息和 App 双向消息通信。

v2 是破坏性安全升级：不再向 H5 直接暴露 `BluetoothInterface`、`CameraManager`、`MessageBridge`、`DeviceChecker` 等全局对象，而是通过单一 `WebBridgeNative` 消息对象和 H5 端 `WebBridge.invoke/on` 访问原生能力。

核心变化：

- SDK 初始化不要求配置可信 origin；默认当前 WebView 页面都可通过统一桥发起调用。
- 原生回调统一使用 JSON envelope，不再拼接 `window.xxx('...')`。
- 权限按动作请求，不再启动时一次性请求所有权限。
- 新版 WebView 使用 `WebMessageListener`，旧版 WebView 自动降级到单一 JSON `JavascriptInterface`。
- 本地 assets 示例通过 `WebViewAssetLoader` 加载为 HTTPS origin。
- 蓝牙分片按实际协商 MTU 动态计算，失败时回退默认 20 字节。

## 安装

项目内模块依赖：

```kotlin
dependencies {
    implementation(project(":webbridgekit"))
}
```

AAR 方式：

```kotlin
dependencies {
    implementation(files("libs/webbridgekit-release.aar"))
    implementation("androidx.webkit:webkit:1.13.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
```

## Android 权限

SDK manifest 会合并最小权限：

```xml
<uses-permission
    android:name="android.permission.BLUETOOTH"
    android:maxSdkVersion="30" />
<uses-permission
    android:name="android.permission.BLUETOOTH_ADMIN"
    android:maxSdkVersion="30" />
<uses-permission
    android:name="android.permission.ACCESS_FINE_LOCATION"
    android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.VIBRATE" />
<uses-feature android:name="android.hardware.camera" android:required="false" />
```

扫描权限只在 H5 显式调用 `startDiscovery` 时请求；直接 `connect` 不会触发扫描权限，也不会隐式搜索设备。

## Android 接入

宿主必须配置权限代理；origin 规则可选，默认不限制页面来源：

```kotlin
private lateinit var webViewBridge: WebViewBridge
private var pendingPermissionCallback: WebViewBridgeConfig.PermissionCallback? = null

private val requestPermissionLauncher = registerForActivityResult(
    ActivityResultContracts.RequestMultiplePermissions()
) { permissions ->
    val granted = permissions.entries.all { it.value }
    pendingPermissionCallback?.onResult(granted)
    pendingPermissionCallback = null
}

private fun initBridge(webView: WebView) {
    val config = WebViewBridgeConfig.Builder()
        .setDebugEnabled(BuildConfig.DEBUG)
        .setPermissionDelegate { feature, action, callback ->
            val missing = PermissionHelper.getRequiredPermissions(feature, action)
                .filter {
                    ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
                }
            if (missing.isEmpty()) {
                callback.onResult(true)
            } else {
                pendingPermissionCallback = callback
                requestPermissionLauncher.launch(missing.toTypedArray())
            }
        }
        .build()

    webViewBridge = WebViewBridge(this, webView, config)
    webViewBridge.loadUrl(webViewBridge.getAssetUrl("index.html"))
}
```

如果宿主希望提前收窄页面来源，可以额外调用：

```kotlin
.addAllowedOriginRule("https://example.com")
```

Activity 生命周期：

```kotlin
override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    webViewBridge.handleActivityResult(requestCode, resultCode, data)
}

override fun onDestroy() {
    webViewBridge.release()
    super.onDestroy()
}
```

App 向 H5 发消息：

```kotlin
webViewBridge.sendMessageToH5("notification", JSONObject().apply {
    put("title", "系统通知")
    put("content", "来自 App 的消息")
})
```

接收 H5 消息：

```kotlin
class MainActivity : ComponentActivity(), WebViewBridge.MessageListener {
    override fun onMessageReceived(type: String, data: JSONObject?) {
        // 处理 H5 消息
    }
}
```

## H5 API

H5 通过单一入口调用：

```javascript
WebBridge.invoke(feature, action, payload)
WebBridge.on(event, handler)
```

请求 envelope：

```json
{
  "id": "req_1",
  "feature": "bluetooth",
  "action": "connect",
  "payload": {}
}
```

响应 envelope：

```json
{
  "id": "req_1",
  "ok": true,
  "data": {}
}
```

错误响应：

```json
{
  "id": "req_1",
  "ok": false,
  "error": {
    "code": "PERMISSION_DENIED",
    "message": "缺少必要权限"
  }
}
```

### 设备信息

```javascript
const info = await WebBridge.invoke('device', 'getInfo');
```

### App 通信

```javascript
await WebBridge.invoke('message', 'sendToApp', {
  type: 'h5Message',
  data: { text: 'Hello from H5' }
});

WebBridge.on('message.fromApp', message => {
  console.log(message.type, message.data);
});
```

### 二维码扫码

```javascript
try {
  const result = await WebBridge.invoke('camera', 'scanQr');
  console.log(result.text);
} catch (error) {
  console.log(error.code, error.message);
}
```

### 蓝牙

```javascript
const status = await WebBridge.invoke('bluetooth', 'getStatus');
const paired = await WebBridge.invoke('bluetooth', 'getPairedDevices');

// 可选：由 H5 自己决定是否先搜索附近 BLE 设备。
await WebBridge.invoke('bluetooth', 'startDiscovery');
const discovered = await WebBridge.invoke('bluetooth', 'getDiscoveredDevices');
await WebBridge.invoke('bluetooth', 'stopDiscovery');

await WebBridge.invoke('bluetooth', 'connect', {
  macAddress: '5C:53:10:7A:1C:80'
});

await WebBridge.invoke('bluetooth', 'writeHex', {
  serviceUUID: '0000FFF0-0000-1000-8000-00805F9B34FB',
  characteristicUUID: '0000FFF2-0000-1000-8000-00805F9B34FB',
  hex: '7B864814071027923000280033BD7D'
});

await WebBridge.invoke('bluetooth', 'disconnect');
```

蓝牙事件：

```javascript
WebBridge.on('bluetooth.connected', data => console.log(data.address));
WebBridge.on('bluetooth.disconnected', data => console.log(data.address));
WebBridge.on('bluetooth.discoveryStarted', data => console.log(data.discovering));
WebBridge.on('bluetooth.discoveryStopped', data => console.log(data.discovering));
WebBridge.on('bluetooth.deviceFound', data => console.log(data.name, data.address, data.rssi));
WebBridge.on('bluetooth.servicesDiscovered', data => console.log(data.services));
WebBridge.on('bluetooth.characteristicChanged', data => console.log(data.hexValue));
WebBridge.on('bluetooth.writeCompleted', data => console.log(data));
WebBridge.on('bluetooth.error', data => console.log(data.message));
```

`connect` 只按传入的 `macAddress` 直接连接，不会在 SDK 内部自动搜索。需要搜索时，H5 显式调用 `startDiscovery`，收到 `bluetooth.deviceFound` 后自行判断是否连接。

## 错误码

| code | 含义 |
| --- | --- |
| `INVALID_MESSAGE` | H5 消息不是合法 JSON 或字段缺失 |
| `FORBIDDEN_FRAME` | 非主 frame 调用被拒绝 |
| `UNSUPPORTED_ACTION` | feature/action 不存在 |
| `PERMISSION_DENIED` | 宿主拒绝或用户拒绝权限 |
| `INVALID_PARAMETER` | 参数格式错误 |
| `CAMERA_SCAN_CANCELLED` | 用户取消扫码 |
| `NATIVE_ERROR` | 原生能力执行失败 |

## 迁移说明

旧接口已移除：

```javascript
BluetoothInterface.getBluetoothStatus()
CameraManager.startQRCodeScan()
MessageBridge.sendMessageToApp(...)
DeviceChecker.getDeviceInfo()
window.onBluetoothConnected = ...
```

迁移到：

```javascript
await WebBridge.invoke('bluetooth', 'getStatus');
await WebBridge.invoke('camera', 'scanQr');
await WebBridge.invoke('message', 'sendToApp', payload);
await WebBridge.invoke('device', 'getInfo');
WebBridge.on('bluetooth.connected', handler);
```

## 混淆

SDK 已通过 consumer rules 保留公开 API、配置类和回调接口。宿主通常不需要额外配置。
