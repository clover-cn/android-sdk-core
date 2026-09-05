# WebBridgeKit 跨端 SDK 统一开发文档

本文档是 Android、iOS、鸿蒙三端 WebBridgeKit SDK 的统一实现规范。后续实现 iOS 或鸿蒙 SDK 时，以本文档为准；`app/src/main/assets/index.html` 是三端共同的验收 demo，不应为了某个平台单独改 H5 调用方式。

## 1. 目标与原则

WebBridgeKit 的目标是让 H5 通过一套稳定协议调用原生能力：

- 设备信息
- App 与 H5 双向消息
- 二维码扫码
- BLE 蓝牙搜索、连接、断开、写入、通知开关

三端必须遵守以下原则：

- H5 只使用 `WebBridge.invoke(feature, action, payload)` 和 `WebBridge.on(event, handler)`。
- 原生 SDK 只暴露一个桥对象，不再暴露 `BluetoothInterface`、`CameraManager`、`MessageBridge`、`DeviceChecker` 等旧全局对象。
- 请求和响应都使用 JSON envelope。
- 业务操作失败统一通过 Promise reject 进入 H5 的 `catch`。
- `bluetooth.*` 事件只用于 UI、日志、状态广播，不承载业务成败判断。
- 正确性优先，不为旧接口做兼容层。

## 2. H5 与原生桥协议

### 2.1 H5 发送请求

H5 demo 会发送字符串 JSON：

```json
{
  "id": "req_1724390000000_abcd",
  "feature": "bluetooth",
  "action": "connect",
  "payload": {
    "macAddress": "5C:53:10:7A:1C:80"
  }
}
```

字段说明：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `id` | string | 是 | 本次请求 ID，原生响应必须原样带回 |
| `feature` | string | 是 | 能力模块，如 `bluetooth`、`camera` |
| `action` | string | 是 | 模块动作 |
| `payload` | object | 否 | 参数对象，缺省按 `{}` 处理 |

### 2.2 原生返回成功

```json
{
  "id": "req_1724390000000_abcd",
  "ok": true,
  "data": {}
}
```

### 2.3 原生返回失败

```json
{
  "id": "req_1724390000000_abcd",
  "ok": false,
  "error": {
    "code": "PERMISSION_DENIED",
    "message": "缺少必要权限"
  }
}
```

### 2.4 原生发送事件

```json
{
  "event": "bluetooth.connected",
  "data": {
    "address": "5C:53:10:7A:1C:80",
    "services": ["0000fff0-0000-1000-8000-00805f9b34fb"]
  }
}
```

事件没有 `id`，不会 resolve/reject 某个 Promise。

## 3. 三端桥对象要求

### 3.1 Android

Android 当前实现是协议基准：

- 新版 WebView：注入 `window.WebBridgeNative`，H5 调用 `window.WebBridgeNative.postMessage(message)`。
- fallback：注入 `window.WebBridgeNativeFallback.postMessage(message)`。
- 原生回调统一派发到 `window.WebBridge.__dispatch(...)` 或 `window.__WebBridgeFallbackDispatch(...)`。

### 3.2 iOS

iOS 使用 `WKWebView`，原生无法直接生成与 Android 完全一样的 `WebBridgeNative` 对象，因此 SDK 必须在 document start 注入 shim：

```javascript
window.WebBridgeNative = {
  onmessage: null,
  postMessage: function (message) {
    window.webkit.messageHandlers.WebBridgeNative.postMessage(message);
  }
};
```

iOS 原生侧要求：

- 使用 `WKUserContentController.add(_:name:)` 注册 `WebBridgeNative`。
- 使用 `WKScriptMessageHandler` 接收 H5 字符串消息。
- 所有原生响应都通过主线程执行 JavaScript：

```javascript
window.WebBridgeNative &&
window.WebBridgeNative.onmessage &&
window.WebBridgeNative.onmessage({ data: "<response-json-string>" });
```

注意：`<response-json-string>` 必须正确 JSON 转义，不能手工拼接未转义字符串。建议用 JSON 序列化后再作为 JS 字符串参数编码。

### 3.3 鸿蒙

鸿蒙 ArkTS SDK 推荐实现 fallback 桥，因为 demo 已支持该模式：

- 注入或注册 `window.WebBridgeNativeFallback.postMessage(message)`。
- 原生侧接收 H5 字符串消息。
- 原生响应执行：

```javascript
window.__WebBridgeFallbackDispatch &&
window.__WebBridgeFallbackDispatch(<response-object>);
```

鸿蒙侧要求：

- 使用 Web 组件 controller 注册 JavaScript proxy。
- proxy 对象名固定为 `WebBridgeNativeFallback`。
- proxy 方法名固定为 `postMessage`。
- `postMessage` 入参是 H5 传入的 JSON 字符串。
- 页面加载前完成 proxy 注册。

## 4. 能力清单

### 4.1 `device.getInfo`

请求：

```javascript
await WebBridge.invoke('device', 'getInfo');
```

返回至少包含：

```json
{
  "platform": "android",
  "manufacturer": "Xiaomi",
  "model": "2211133C",
  "osVersion": "16",
  "bluetoothSupported": true,
  "bleSupported": true,
  "cameraSupported": true
}
```

三端必须返回顶层 `platform`，取值固定为 `android`、`ios`、`harmonyos`。Android 可额外返回 `androidVersion`、`apiLevel`；iOS 和鸿蒙可额外返回各自平台字段。demo 只展示 JSON，不依赖额外字段。

### 4.2 `message.sendToApp`

请求：

```javascript
await WebBridge.invoke('message', 'sendToApp', {
  "type": "h5Log",
  "data": {
    "message": "日志内容",
    "timestamp": 1724390000000
  }
});
```

返回：

```json
{ "accepted": true }
```

原生宿主可监听该消息，用于业务通信或 demo 日志同步。

App 主动发送给 H5：

```json
{
  "event": "message.fromApp",
  "data": {
    "type": "notification",
    "data": {}
  }
}
```

### 4.3 `camera.scanQr`

请求：

```javascript
const result = await WebBridge.invoke('camera', 'scanQr');
```

成功返回：

```json
{ "text": "二维码内容" }
```

失败规则：

- 用户取消：`CAMERA_SCAN_CANCELLED`
- 无相机权限：`PERMISSION_DENIED`
- 系统扫码能力异常：`NATIVE_ERROR`

平台实现建议：

- Android：沿用当前扫码实现。
- iOS：使用 AVFoundation 相机采集和二维码 metadata 识别。
- 鸿蒙：使用 Scan Kit 或系统扫码能力；如果项目选择自定义相机识别，也必须返回同一 envelope。

## 5. 蓝牙能力规范

### 5.1 蓝牙 Promise 语义

蓝牙命令 Promise 表示本次命令的最终结果：

| action | resolve 时机 | reject 场景 |
| --- | --- | --- |
| `getStatus` | 读取当前状态完成 | 原生异常 |
| `getPairedDevices` | 读取已配对或已知设备列表完成 | 权限不足或原生异常 |
| `startDiscovery` | 搜索已启动 | 蓝牙不可用、权限不足、启动失败 |
| `stopDiscovery` | 搜索已停止 | 权限不足或停止失败 |
| `getDiscoveredDevices` | 返回缓存发现列表 | 原生异常 |
| `connect` | GATT 连接且服务发现完成 | 参数错误、权限不足、连接失败、服务发现失败、超时 |
| `disconnect` | 实际断开或已处于断开状态 | 断开失败或超时 |
| `writeHex` | 普通写入完成或全部分片写入完成 | 未连接、参数错误、写入失败、写入超时 |
| `setNotificationsEnabled` | 通知开关状态已保存 | 原生异常 |
| `isNotificationsEnabled` | 当前通知开关读取完成 | 原生异常 |

业务层只在 `try/catch` 写成功/失败逻辑：

```javascript
try {
  const result = await WebBridge.invoke('bluetooth', 'connect', { macAddress });
  await notifyServer({ success: true, address: result.address });
} catch (error) {
  await notifyServer({ success: false, code: error.code, message: error.message });
}
```

不要在 `bluetooth.error` 监听器里重复写业务失败逻辑。

### 5.2 蓝牙状态

请求：

```javascript
const status = await WebBridge.invoke('bluetooth', 'getStatus');
```

返回：

```json
{
  "supported": true,
  "enabled": true,
  "connected": false,
  "deviceAddress": ""
}
```

字段要求：

- `supported`：设备是否支持蓝牙/BLE。
- `enabled`：系统蓝牙是否打开。
- `connected`：SDK 当前是否已有连接。
- `deviceAddress`：当前连接设备标识，没有则为空字符串。

### 5.3 设备列表

`getPairedDevices` 返回：

```json
{
  "devices": [
    { "name": "Device", "address": "5C:53:10:7A:1C:80" }
  ]
}
```

`getDiscoveredDevices` 返回：

```json
{
  "devices": [
    { "name": "Device", "address": "5C:53:10:7A:1C:80", "rssi": -60 }
  ]
}
```

跨端地址规则：

- Android：`address` 使用 BLE MAC。
- iOS：系统不允许获取真实 MAC，`address` 使用 `CBPeripheral.identifier.uuidString`。字段名仍叫 `address`，以保证 demo 不变。
- 鸿蒙：如果系统能获取 MAC，则使用 MAC；如果只能获取设备 ID，则使用系统返回的稳定设备标识。字段名仍叫 `address`。

`connect` 参数仍固定为：

```json
{ "macAddress": "<address-from-device-list>" }
```

虽然字段名叫 `macAddress`，iOS/鸿蒙可以把它解释为设备 ID。不要为了 iOS 改 H5 字段名。

### 5.4 搜索

启动搜索：

```javascript
await WebBridge.invoke('bluetooth', 'startDiscovery');
```

成功返回：

```json
{ "discovering": true }
```

停止搜索：

```javascript
await WebBridge.invoke('bluetooth', 'stopDiscovery');
```

成功返回：

```json
{ "discovering": false }
```

搜索事件：

```json
{ "event": "bluetooth.discoveryStarted", "data": { "discovering": true } }
{ "event": "bluetooth.discoveryStopped", "data": { "discovering": false } }
{ "event": "bluetooth.deviceFound", "data": { "name": "Device", "address": "...", "rssi": -60 } }
```

约束：

- `connect` 不隐式触发搜索。
- 搜索启动后的扫描会话异常可以通过 `bluetooth.error` 广播。
- `getDiscoveredDevices` 返回 SDK 内存缓存列表。

### 5.5 连接

请求：

```javascript
const result = await WebBridge.invoke('bluetooth', 'connect', {
  "macAddress": "5C:53:10:7A:1C:80"
});
```

成功返回：

```json
{
  "address": "5C:53:10:7A:1C:80",
  "services": ["0000fff0-0000-1000-8000-00805f9b34fb"]
}
```

连接事件：

```json
{
  "event": "bluetooth.connected",
  "data": {
    "address": "5C:53:10:7A:1C:80",
    "services": ["0000fff0-0000-1000-8000-00805f9b34fb"]
  }
}
```

实现要求：

- 同一时间只允许一个连接、断开或写入关键操作；冲突返回 `BLUETOOTH_BUSY`。
- 连接前清理旧 GATT/peripheral 连接状态。
- 连接成功不等于 Promise 成功；必须完成服务发现后才 resolve。
- 连接超时建议 15 秒，覆盖连接、MTU 协商、服务发现整体流程。
- 连接失败必须 reject，同时可以发送 `bluetooth.error` 事件。

iOS 特别说明：

- `macAddress` 实际是 `CBPeripheral.identifier.uuidString`。
- SDK 需要维护 `identifier -> CBPeripheral` 映射。
- 如果用户直接输入一个未扫描缓存过的 identifier，SDK 可尝试从已知 peripheral 缓存中查找；找不到返回 `BLUETOOTH_CONNECTION_FAILED` 或 `INVALID_PARAMETER`。

### 5.6 断开

请求：

```javascript
const result = await WebBridge.invoke('bluetooth', 'disconnect');
```

成功返回：

```json
{ "disconnected": true }
```

断开事件：

```json
{
  "event": "bluetooth.disconnected",
  "data": { "address": "5C:53:10:7A:1C:80" }
}
```

要求：

- 如果当前没有连接，也 resolve `{ "disconnected": true }`。
- 如果正在连接，`disconnect` 应取消连接流程，并 resolve `{ "disconnected": true }`。
- 如果断开超时，reject `BLUETOOTH_DISCONNECT_TIMEOUT`，并清理本地连接状态。

### 5.7 写入

请求：

```javascript
const result = await WebBridge.invoke('bluetooth', 'writeHex', {
  "serviceUUID": "0000FFF0-0000-1000-8000-00805F9B34FB",
  "characteristicUUID": "0000FFF2-0000-1000-8000-00805F9B34FB",
  "hex": "7B864814071027923000280033BD7D"
});
```

成功返回：

```json
{
  "uuid": "0000fff2-0000-1000-8000-00805f9b34fb",
  "status": "success"
}
```

分片写入成功返回：

```json
{
  "uuid": "0000fff2-0000-1000-8000-00805f9b34fb",
  "status": "success",
  "chunked": true,
  "totalChunks": 3
}
```

写入完成事件：

```json
{
  "event": "bluetooth.writeCompleted",
  "data": {
    "uuid": "0000fff2-0000-1000-8000-00805f9b34fb",
    "status": "success"
  }
}
```

要求：

- `hex` 必须是偶数长度十六进制字符串；空字符串或非法字符返回 `INVALID_PARAMETER`。
- 未连接返回 `BLUETOOTH_NOT_CONNECTED`。
- 找不到 service/characteristic 返回 `BLUETOOTH_WRITE_FAILED`。
- 普通写入等待平台写入回调后 resolve。
- 大包写入必须分片，全部分片成功后才 resolve。
- 任一分片失败或超时必须 reject。

分片规则：

- Android：优先使用协商后的 MTU，payload = `max(20, mtu - 3)`。
- iOS：使用 `maximumWriteValueLength(for:)`。
- 鸿蒙：使用平台 GATT 可写入长度；无法获取时按 20 字节保守分片。

### 5.8 通知开关与设备通知

H5 控制 SDK 是否向页面转发设备通知：

```javascript
const result = await WebBridge.invoke('bluetooth', 'setNotificationsEnabled', {
  "enabled": true
});
```

返回：

```json
{ "enabled": true }
```

读取：

```javascript
const result = await WebBridge.invoke('bluetooth', 'isNotificationsEnabled');
```

设备数据事件：

```json
{
  "event": "bluetooth.characteristicChanged",
  "data": {
    "uuid": "0000fff2-0000-1000-8000-00805f9b34fb",
    "value": "7B8648",
    "hexValue": "7B8648"
  }
}
```

要求：

- `enabled=false` 时，SDK 可以继续保持底层 BLE notify，但不要向 H5 转发 `bluetooth.characteristicChanged`。
- `value` 和 `hexValue` 至少保证 `hexValue` 是大写十六进制字符串。
- 不要把设备主动上报误判为某个 `writeHex` 的 Promise 成功。

## 6. 错误码

三端错误码必须一致：

| code | 含义 |
| --- | --- |
| `INVALID_MESSAGE` | H5 消息不是合法 JSON 或字段缺失 |
| `FORBIDDEN_FRAME` | 非主 frame 调用被拒绝 |
| `UNSUPPORTED_ACTION` | feature/action 不存在 |
| `PERMISSION_DENIED` | 宿主拒绝或用户拒绝权限 |
| `INVALID_PARAMETER` | 参数格式错误 |
| `CAMERA_SCAN_CANCELLED` | 用户取消扫码 |
| `NATIVE_ERROR` | 原生能力执行失败 |
| `BLUETOOTH_ERROR` | 通用蓝牙错误 |
| `BLUETOOTH_NOT_SUPPORTED` | 设备不支持蓝牙 |
| `BLUETOOTH_DISABLED` | 蓝牙未启用 |
| `BLUETOOTH_BUSY` | 另一个蓝牙操作正在进行 |
| `BLUETOOTH_CONNECT_TIMEOUT` | 连接超时 |
| `BLUETOOTH_CONNECTION_FAILED` | GATT 连接失败 |
| `BLUETOOTH_SERVICE_DISCOVERY_FAILED` | 服务发现失败 |
| `BLUETOOTH_DISCONNECT_TIMEOUT` | 断开超时 |
| `BLUETOOTH_DISCONNECT_FAILED` | 断开失败 |
| `BLUETOOTH_NOT_CONNECTED` | 当前没有蓝牙连接 |
| `BLUETOOTH_WRITE_FAILED` | 写入失败 |
| `BLUETOOTH_WRITE_TIMEOUT` | 写入超时 |
| `BLUETOOTH_DISCOVERY_FAILED` | 搜索失败 |
| `BLUETOOTH_CANCELLED` | 操作被取消 |
| `BLUETOOTH_RELEASED` | SDK 已释放 |

错误响应格式必须稳定：

```json
{
  "id": "req_xxx",
  "ok": false,
  "error": {
    "code": "BLUETOOTH_CONNECT_TIMEOUT",
    "message": "连接超时，请确保设备在范围内且未被其他设备连接"
  }
}
```

平台原始错误码可以拼进 `message`，不要新增平台专属 `code`。

## 7. 权限策略

### 7.1 Android

- 蓝牙连接/读写：Android 12+ 使用 `BLUETOOTH_CONNECT`；Android 11 及以下使用 `BLUETOOTH`、`BLUETOOTH_ADMIN`。
- 蓝牙搜索：Android 12+ 使用 `BLUETOOTH_SCAN` 和 `ACCESS_FINE_LOCATION`；Android 6-11 使用 `BLUETOOTH`、`BLUETOOTH_ADMIN`、`ACCESS_FINE_LOCATION`。扫描附近 BLE 设备时要求系统定位服务处于开启状态。
- 扫码：`CAMERA`。

### 7.2 iOS

`Info.plist` 至少声明：

- `NSBluetoothAlwaysUsageDescription`
- `NSCameraUsageDescription`

如果项目兼容更老系统，可补充旧蓝牙 usage description。本文档默认 iOS 13+。

权限请求策略：

- 初始化 SDK 不主动弹权限。
- H5 调用蓝牙动作时初始化/检查 `CBCentralManager` 状态。
- H5 调用扫码时请求相机权限。
- 用户拒绝时 reject `PERMISSION_DENIED`。

### 7.3 鸿蒙

`module.json5` 中声明蓝牙、位置、相机等权限，具体权限名按目标 HarmonyOS SDK 版本配置。

权限请求策略：

- 初始化 SDK 不主动弹权限。
- `startDiscovery` 才请求扫描所需权限。
- `connect/write/disconnect` 请求蓝牙连接相关权限。
- `scanQr` 请求相机权限。
- 用户拒绝时 reject `PERMISSION_DENIED`。

## 8. iOS SDK 结构建议

建议模块：

- `WebBridgeKit`：SDK 入口，持有 `WKWebView`、配置、能力分发。
- `BridgeEnvelope`：请求、响应、事件 JSON 编解码。
- `BridgeError`：统一错误码。
- `DeviceManager`：设备信息。
- `MessageManager`：App/H5 双向消息。
- `CameraManager`：扫码。
- `BluetoothManager`：CoreBluetooth 状态机。

入口 API 建议：

```swift
final class WebBridgeKit {
    init(webView: WKWebView, config: WebBridgeConfig)
    func loadDemoHTML(from url: URL)
    func sendMessageToH5(type: String, data: [String: Any])
    func release()
}
```

`WebBridgeConfig` 至少包含：

- `debugEnabled`
- `allowedOrigins`，默认不限制；如配置则拦截非允许 URL
- `permissionDelegate`，可选；宿主需要自定义权限流程时使用
- `messageHandler`，用于宿主接收 `message.sendToApp`

CoreBluetooth 状态机要求：

- 维护 discovered peripherals：`[String: CBPeripheral]`。
- 维护 pending operation：连接、断开、写入分别只完成一次。
- 所有 Promise 响应回到主线程。
- BLE delegate 回调不得直接拼 JS 字符串，必须走统一 envelope 编码。

## 9. 鸿蒙 SDK 结构建议

建议模块：

- `WebBridgeKit`：SDK 入口，持有 Web controller、配置、能力分发。
- `BridgeEnvelope`：请求、响应、事件 JSON 编解码。
- `BridgeError`：统一错误码。
- `DeviceManager`：设备信息。
- `MessageManager`：App/H5 双向消息。
- `CameraManager`：扫码。
- `BluetoothManager`：BLE 状态机。

入口 API 建议：

```ts
export class WebBridgeKit {
  constructor(controller: webview.WebviewController, config: WebBridgeConfig)
  installBridge(): void
  loadDemoPage(url: string): void
  sendMessageToH5(type: string, data: Record<string, Object>): void
  release(): void
}
```

`WebBridgeConfig` 至少包含：

- `debugEnabled`
- `allowedOrigins`，默认不限制
- `permissionDelegate`
- `messageHandler`

ArkTS 实现要求：

- JS proxy 名称固定为 `WebBridgeNativeFallback`。
- proxy 方法固定为 `postMessage(message: string)`。
- 原生响应统一调用 `runJavaScript` 派发 `window.__WebBridgeFallbackDispatch(...)`。
- BLE 回调必须映射为统一事件和 Promise envelope。
- 页面销毁时停止扫描、断开连接、清理 pending callbacks。

## 10. Demo 复用验收

三端都必须使用同一份：

```text
app/src/main/assets/index.html
```

验收步骤：

1. 打开 demo，确认“设备信息已刷新”。
2. 点击“刷新蓝牙状态”，确认返回 JSON。
3. 点击“发送 App 消息”，确认原生日志或宿主回调收到 `message.sendToApp`。
4. 点击“开始扫码”，扫码成功后 H5 显示二维码内容；取消时进入 `catch`。
5. 点击“开始搜索蓝牙设备”，确认设备列表出现。
6. 点击设备旁“填入 MAC”，iOS/鸿蒙也必须填入 `address` 字段。
7. 点击“连接设备”，成功后 `connect` Promise resolve，并收到 `bluetooth.connected`。
8. 输入 service UUID、characteristic UUID、hex，点击写入，成功后 `writeHex` Promise resolve。
9. 切换通知开关，确认 `setNotificationsEnabled` Promise resolve。
10. 点击断开，确认 `disconnect` Promise resolve，并收到 `bluetooth.disconnected`。

失败验收：

- 关闭蓝牙后连接，应 reject `BLUETOOTH_DISABLED` 或平台等价蓝牙不可用错误。
- 拒绝权限，应 reject `PERMISSION_DENIED`。
- 输入非法 UUID 或 hex，应 reject `INVALID_PARAMETER`。
- 未连接时写入，应 reject `BLUETOOTH_NOT_CONNECTED`。
- 设备不在范围内时连接，应 reject `BLUETOOTH_CONNECT_TIMEOUT` 或 `BLUETOOTH_CONNECTION_FAILED`。

## 11. 开发注意事项

- 不要让 `bluetooth.error` 代替 Promise reject。
- 不要在事件监听器中做“通知服务器连接失败”这类业务逻辑。
- 不要因为 iOS 没有 MAC 就修改 H5 字段名；继续使用 `macAddress`，值为 peripheral identifier。
- 不要对 JSON 使用字符串拼接；统一序列化后派发。
- 不要在连接成功但服务未发现时 resolve `connect`。
- 不要在 `writeHex` 刚提交给系统 API 时 resolve；必须等平台写入回调。
- 不要在 SDK 初始化时一次性请求所有权限。
- 不要保留旧 H5 全局对象兼容层。

## 12. 官方资料参考

- Apple WebKit `WKScriptMessageHandler`：https://developer.apple.com/documentation/webkit/wkscriptmessagehandler
- Apple WebKit `WKUserContentController`：https://developer.apple.com/documentation/webkit/wkusercontentcontroller
- Apple CoreBluetooth：https://developer.apple.com/documentation/corebluetooth
- Apple AVFoundation metadata scanning：https://developer.apple.com/documentation/avfoundation/avcapturemetadataoutput
- 华为 ArkWeb 指南入口：https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/arkweb-overview
- 华为 Web 组件参考入口：https://developer.huawei.com/consumer/cn/doc/harmonyos-references/ts-basic-components-web
- 华为 Connectivity Kit 蓝牙文档入口：https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/connectivity-kit
- 华为 Scan Kit 文档入口：https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/scan-overview
