# WebBridgeKit v2 集成与迁移说明

## 升级目标

v2 以安全稳定为第一优先级，采用破坏性替换：

- H5 不再直接访问多个原生对象。
- 原生能力只通过单一 `WebBridgeNative` 消息通道进入。
- 旧版 WebView 不支持 `WebMessageListener` 时，SDK 自动降级到单一 JSON fallback 通道。
- SDK 初始化不要求提供可信 origin；默认当前 WebView 页面都可通过统一桥发起调用。
- 权限由宿主按 H5 动作动态确认。
- 蓝牙写入按协商 MTU 分片，避免固定 20 字节导致吞吐浪费。

## Android 宿主接入清单

1. 创建 `WebView`。
2. 配置 `WebViewBridgeConfig`。
3. 实现 `PermissionDelegate`。
4. 创建 `WebViewBridge`。
5. 使用 `webViewBridge.getAssetUrl("index.html")` 或业务 HTTPS 地址加载页面。
6. 在 `onActivityResult` 转发扫码结果。
7. 在 `onDestroy` 调用 `release()`。

示例：

```kotlin
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
```

## H5 迁移清单

删除旧全局对象调用：

```javascript
BluetoothInterface.connectToDevice(mac)
BluetoothInterface.writeRawHexData(service, characteristic, hex)
CameraManager.startQRCodeScan()
MessageBridge.sendMessageToApp(json)
DeviceChecker.getDeviceInfo()
```

替换为：

```javascript
const connected = await WebBridge.invoke('bluetooth', 'connect', { macAddress: mac });
const writeResult = await WebBridge.invoke('bluetooth', 'writeHex', { serviceUUID, characteristicUUID, hex });
await WebBridge.invoke('bluetooth', 'disconnect');
await WebBridge.invoke('bluetooth', 'setNotificationsEnabled', { enabled: true });
const qr = await WebBridge.invoke('camera', 'scanQr');
await WebBridge.invoke('message', 'sendToApp', { type, data });
const info = await WebBridge.invoke('device', 'getInfo');
```

删除旧回调：

```javascript
window.onBluetoothConnected = ...
window.onBluetoothError = ...
window.onCharacteristicChanged = ...
window.onQRCodeResult = ...
window.onAppMessage = ...
```

替换为事件：

```javascript
WebBridge.on('bluetooth.connected', handler);
WebBridge.on('bluetooth.error', handler);
WebBridge.on('bluetooth.characteristicChanged', handler);
WebBridge.on('camera.qrResult', handler);
WebBridge.on('message.fromApp', handler);
```

蓝牙命令的 Promise 统一表示命令的最终结果。连接会等待服务发现完成，断开会等待实际断开，写入会等待普通写入或全部分片写入完成；失败统一通过 `catch` 返回 `{ code, message }`。`bluetooth.connected`、`bluetooth.error`、`bluetooth.disconnected`、`bluetooth.writeCompleted` 等事件只用于 UI、日志和状态广播，不能与 Promise 的 `catch` 重复执行业务通知。

## 权限策略

当前 SDK 能力只需要：

- 蓝牙连接/读写：Android 12+ 使用 `BLUETOOTH_CONNECT`；Android 11 及以下使用 `BLUETOOTH` 和 `BLUETOOTH_ADMIN`。
- 蓝牙搜索：只有 H5 显式调用 `startDiscovery` 时才请求。Android 12+ 使用 `BLUETOOTH_SCAN`；Android 6-11 使用 `BLUETOOTH`、`BLUETOOTH_ADMIN` 和 `ACCESS_FINE_LOCATION`。
- 二维码扫码：`CAMERA`。

`connect` 不会隐式触发蓝牙搜索。H5 可以直接传 MAC 连接，也可以先调用 `startDiscovery`，监听 `bluetooth.deviceFound` 后再自行决定是否调用 `connect`。

## Origin 策略

v2 默认不限制 origin，初始化时无需调用 `addAllowedOriginRule`。推荐本地示例：

```text
https://appassets.androidplatform.net/assets/index.html
```

如果宿主希望在 SDK 层提前收窄页面来源，可以选择配置：

```kotlin
.addAllowedOriginRule("https://example.com")
```

不配置时，SDK 层不做来源白名单控制；后续可以在统一 bridge 入口接入服务端鉴权。

## 蓝牙稳定性说明

BLE 默认 ATT MTU 是 23，扣除 3 字节协议开销后，默认 payload 是 20 字节。v2 不再固定 20，而是：

```text
chunkSize = max(20, negotiatedMtu - 3)
```

流程：

1. 连接成功后请求 `PREFERRED_MTU = 247`。
2. `onMtuChanged` 成功后记录实际 MTU。
3. 分片写入按实际 MTU 计算。
4. 协商失败或未回调时回退 20 字节。

同时，每次写入只移除自己的 timeout runnable，不再调用 `removeCallbacksAndMessages(null)` 清掉所有主线程回调。

## 安全边界

v2 默认保留的本地安全边界：

- 非主 frame 的桥接调用。
- 未授权 feature/action。
- 非法 MAC、UUID、hex 参数。

生产环境默认关闭 WebView 调试，并禁用 mixed content。SDK 不再阻断宿主传入的页面 URL；后续服务端鉴权可在统一 bridge 入口补上。

## 迁移影响

这是破坏性升级，无旧接口兼容层。迁移时必须同步修改 Android 初始化代码和 H5 调用代码。旧 H5 页面如果继续访问 `BluetoothInterface`、`CameraManager`、`MessageBridge` 或 `DeviceChecker`，会直接失败。
