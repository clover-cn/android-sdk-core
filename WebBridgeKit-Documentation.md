# WebBridgeKit 开发文档

## 简介

WebBridgeKit 是一个 Android 库，旨在为嵌入式 WebView 提供原生功能的桥接能力。它允许 H5 页面调用设备的蓝牙和相机功能，实现原生与 Web 的无缝通信。主要功能包括：

- 蓝牙设备扫描、连接和数据交换
- 二维码扫描
- 原生与 H5 之间的双向消息通信

## 安装与配置

### Gradle 配置

APP引用一个 Gradle 项目模块（即项目内的另一个子模块）

在应用模块的 build.gradle.kts 文件中添加依赖：

```kotlin
dependencies {
    implementation("com.example:webbridgekit:1.0.0")
}
```

或者通过项目依赖引入：

```kotlin
dependencies {
    implementation(project(":webbridgekit"))
}
```

### 引入AAR文件

```kotlin
// 导入libs下所有的aar
implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))
// 导入指定aar
implementation(files("libs/webbridgekit-debug.aar"))
```

根据文件方式二选一

### 权限配置

WebBridgeKit 需要以下权限，它们将自动合并到应用的 AndroidManifest.xml 中：

```xml
<!-- 蓝牙权限 -->
<uses-permission android:name="android.permission.BLUETOOTH" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />

<!-- 相机权限 -->
<uses-permission android:name="android.permission.CAMERA" />
<uses-feature android:name="android.hardware.camera" android:required="true" />
<uses-feature android:name="android.hardware.camera.autofocus" android:required="false" />

<!-- 振动权限，用于扫码成功提示 -->
<uses-permission android:name="android.permission.VIBRATE" />

<!-- 位置信息 -->
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"/>
```

**注意**：应用需要在运行时请求这些权限（Android 6.0+）。

### 活动注册(注意事项)

WebBridgeKit 在清单文件中注册了 QRScanActivity:

```xml
<activity
    android:name=".QRScanActivity"
    android:screenOrientation="portrait"
    android:exported="false"
    android:theme="@style/zxing_CaptureTheme" />
```

**注意**：固定屏幕方向可能与宿主应用的屏幕适配策略冲突，确保 @style/zxing_CaptureTheme 样式可用，可能需要在宿主应用中重新定义

## 快速开始

### 初始化

在 Activity 中初始化 WebViewBridge：

```kotlin
private lateinit var webView: WebView
private lateinit var webViewBridge: WebViewBridge

override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    
    // 初始化 WebView
    webView = WebView(this)
    setContentView(webView)
    
    // 请求必要权限...
    
    // 初始化 WebViewBridge
    webViewBridge = WebViewBridge(this, webView)
    
    // 添加消息监听器
    webViewBridge.addMessageListener(this)
    
    // 加载 HTML 页面
    webViewBridge.loadUrl("file:///android_asset/index.html")
}
```

### 处理权限请求

为确保正常使用，需请求相关权限：

```kotlin
private val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.BLUETOOTH,
        Manifest.permission.BLUETOOTH_ADMIN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN
    )
} else {
    arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.BLUETOOTH,
        Manifest.permission.BLUETOOTH_ADMIN
    )
}

private val requestPermissionLauncher = registerForActivityResult(
    ActivityResultContracts.RequestMultiplePermissions()
) { permissions ->
    val allGranted = permissions.entries.all { it.value }
    if (allGranted) {
        initializeWebViewBridge()
    } else {
        // 处理权限被拒绝的情况
    }
}

private fun checkAndRequestPermissions() {
    val permissionsToRequest = permissions.filter { permission ->
        ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
    }.toTypedArray()

    if (permissionsToRequest.isEmpty()) {
        initializeWebViewBridge()
    } else {
        requestPermissionLauncher.launch(permissionsToRequest)
    }
}
```

### 处理 Activity 结果

在 Activity 中添加以下代码以处理扫码结果：

```kotlin
override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    webViewBridge.handleActivityResult(requestCode, resultCode, data)
}
```

### 注册消息监听器

如需接收从 H5 发送的消息，实现 MessageListener 接口：

```kotlin
import com.example.webbridgekit.WebViewBridge
class MainActivity : ComponentActivity(), WebViewBridge.MessageListener {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        // ...
        webViewBridge.addMessageListener(this)
    }
    
    override fun onMessageReceived(type: String, data: JSONObject?) {
        when (type) {
            "userInfo" -> {
                // 处理用户信息
            }
            "action" -> {
                // 处理行动请求
            }
            // 其他消息类型...
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        webViewBridge.removeMessageListener(this)
    }
}
```

## H5 端接口

WebBridgeKit 向 WebView 注入了三个 JavaScript 接口：

### 1. BluetoothInterface - 蓝牙操作接口

```javascript
// 检查蓝牙状态
const status = JSON.parse(BluetoothInterface.getBluetoothStatus());
// { supported: true, enabled: true, connected: false }

// 获取已配对设备
const devices = JSON.parse(BluetoothInterface.getPairedDevices());
// [{"name":"Device1","address":"XX:XX:XX:XX:XX:XX"},...]

// 连接设备
BluetoothInterface.connectToDevice("XX:XX:XX:XX:XX:XX");

// 断开连接
BluetoothInterface.disconnect();

// 发送数据（十六进制字符串）
BluetoothInterface.writeRawHexData(
    "0000FFF0-0000-1000-8000-00805F9B34FB",  // 服务UUID
    "0000FFF2-0000-1000-8000-00805F9B34FB",  // 特征值UUID
    "7B864814071027923000280033BD7D"         // 十六进制数据
);

// 启用/禁用通知
BluetoothInterface.setNotificationsEnabled(true);
```

### 2. CameraManager - 相机操作接口

```javascript
// 开始扫描二维码
CameraManager.startQRCodeScan();
```

### 3. MessageBridge - 消息通信接口

```javascript
// 向 Android 原生应用发送消息
const messageData = {
    type: "action",
    data: {
        actionType: "openNativeDialog",
        title: "测试",
        message: "这是一个测试消息"
    }
};
MessageBridge.sendMessageToApp(JSON.stringify(messageData));

// 获取设备信息
const deviceInfo = JSON.parse(MessageBridge.getDeviceInfo());
// { "model": "Pixel 6", "manufacturer": "Google", "os": "Android", "osVersion": "12" }
```

### 回调函数

WebBridgeKit 定义了以下全局回调函数，H5 页面需要实现这些函数来接收事件：

#### 蓝牙回调

```javascript
// 蓝牙连接成功
window.onBluetoothConnected = function(address) {
    console.log("连接成功：" + address);
};

// 蓝牙断开连接
window.onBluetoothDisconnected = function(address) {
    console.log("连接断开：" + address);
};

// 蓝牙错误
window.onBluetoothError = function(error) {
    console.log("错误：" + error);
};

// 发现服务
window.onServicesDiscovered = function(services) {
    console.log("发现服务：" + services);
};

// 特征值变化（接收数据）
window.onCharacteristicChanged = function(data) {
    const json = JSON.parse(data);
    console.log(`收到数据：UUID=${json.uuid}, 值=${json.value}, 十六进制=${json.hexValue}`);
};

// 蓝牙状态变化
window.onBluetoothStateChange = function(msg) {
    console.log("蓝牙状态：" + msg);
};

// 蓝牙已准备就绪
window.onBluetoothReady = function() {
    console.log("蓝牙已准备就绪");
};
```

#### 相机回调

```javascript
// 扫描结果
window.onQRCodeResult = function(result) {
    console.log("扫描结果：" + result);
};

// 错误回调
window.onError = function(error) {
    console.log("错误：" + error);
};
```

#### 消息回调

```javascript
// 接收来自原生应用的消息
window.onAppMessage = function(message) {
    console.log(`收到消息类型：${message.type}`);
    console.log(`消息数据：${JSON.stringify(message.data)}`);
};
```

## API 参考

### WebViewBridge 类

#### 构造函数

```kotlin
WebViewBridge(activity: Activity, webView: WebView)
```

#### 方法

| 方法 | 描述 |
|------|------|
| `loadUrl(url: String)` | 加载指定 URL |
| `evaluateJavascript(script: String)` | 执行 JavaScript 代码 |
| `sendMessageToH5(type: String, data: Object)` | 向 H5 发送消息 |
| `addMessageListener(listener: MessageListener)` | 添加消息监听器 |
| `removeMessageListener(listener: MessageListener)` | 移除消息监听器 |
| `handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?)` | 处理 Activity 结果 |

### MessageListener 接口

```kotlin
// 接收来自H5消息
interface MessageListener {
    void onMessageReceived(String type, JSONObject data);
}
```

## 集成注意事项

### 1. 包名冲突

当前库使用 `com.example.webbridgekit` 包名，若您需要修改，确保同步更改：
- AndroidManifest.xml 中的 package 属性
- 所有 Java/Kotlin 文件的包声明
- 库模块的 build.gradle.kts 中的 namespace

### 2. 权限处理

- Android 6.0+ 需要运行时请求权限
- Android 12+ 需要额外的蓝牙权限 (BLUETOOTH_CONNECT, BLUETOOTH_SCAN)
- 确保在初始化 WebViewBridge 前获取必要权限

### 3. 生命周期管理

- 在 Activity 的 onDestroy() 中移除消息监听器
- 确保蓝牙连接在适当时机断开，防止资源泄漏

### 4. WebView 设置

- 确保启用 JavaScript：`webView.settings.javaScriptEnabled = true`
- 启用 DOM 存储：`webView.settings.domStorageEnabled = true`

### 5. 混淆规则

如果启用了代码混淆，添加以下规则到 proguard-rules.pro：

```proguard
# WebBridgeKit
-keep class com.example.webbridgekit.** { *; }

# ZXing
-keep class com.google.zxing.** { *; }

# JSON
-keep class org.json.** { *; }
```

### 6. JavaScript 接口名冲突

如果宿主应用已经向 WebView 中注入了相同名称的接口，可能会发生冲突。请确保使用唯一的接口名称。

### 7. 测试和兼容性

- 在不同 Android 版本上测试功能
- 确保 H5 页面在不同 WebView 版本中兼容
- 测试蓝牙连接、断开和重连逻辑

## 常见问题

### Q: 蓝牙连接失败怎么办？

A: 检查以下几点：
- 确保已获取蓝牙相关权限
- 设备是否已开启蓝牙
- MAC 地址格式是否正确
- 目标设备是否在范围内且未被其他设备连接

### Q: 扫码功能无法使用？

A: 确认以下几点：
- 已获取相机权限
- 设备有摄像头且工作正常
- QRScanActivity 已正确注册在 AndroidManifest.xml 中

### Q: H5 中的回调函数没有触发？

A: 检查以下几点：
- JavaScript 是否已启用
- 回调函数名是否正确（区分大小写）
- 回调函数是否定义在全局作用域
