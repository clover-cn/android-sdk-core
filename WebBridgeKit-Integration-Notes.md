# WebBridgeKit 集成注意事项

本文档详细介绍了将 WebBridgeKit 库作为 AAR 集成到其他应用时需要注意的事项，以及避免常见问题的最佳实践。

## 目录
1. [AAR 发布前的准备](#1-aar-发布前的准备)
2. [包名和命名空间](#2-包名和命名空间)
3. [权限管理](#3-权限管理)
4. [依赖冲突处理](#4-依赖冲突处理)
5. [JavaScript 接口冲突](#5-javascript-接口冲突)
6. [资源冲突](#6-资源冲突)
7. [清单文件合并](#7-清单文件合并)
8. [多应用场景](#8-多应用场景)
9. [混淆与发布配置](#9-混淆与发布配置)
10. [WebView 兼容性](#10-webview-兼容性)
11. [总结与最佳实践](#11-总结与最佳实践)

## 兼容性和稳定性最佳实践

引入必要方法：

```kotlin
import com.webbridgesdk.webbridgekit.WebViewBridge
import com.webbridgesdk.webbridgekit.PermissionHelper
import com.webbridgesdk.webbridgekit.DeviceCompatibilityChecker
```



### 1. 设备兼容性检查

在初始化WebBridgeKit之前，建议先检查设备兼容性：

```kotlin

// 检查设备兼容性
val compatibilityChecker = DeviceCompatibilityChecker(this)
if (!compatibilityChecker.isAndroidVersionSupported()) {
    // 处理不支持的Android版本
    showUnsupportedVersionDialog()
    return
}

// 获取设备信息
val deviceInfo = compatibilityChecker.getDeviceInfo()
Log.d("DeviceInfo", deviceInfo)
```

### 2. 权限管理最佳实践

使用PermissionHelper统一管理权限：

```kotlin

// 检查所有必需权限
val missingPermissions = PermissionHelper.getAllMissingPermissions(this)
if (missingPermissions.isNotEmpty()) {
    // 请求缺失的权限
    requestPermissions(missingPermissions.toTypedArray(), PERMISSION_REQUEST_CODE)
}

// 分别检查蓝牙和相机权限
if (!PermissionHelper.hasAllBluetoothPermissions(this)) {
    // 请求蓝牙权限
}

if (!PermissionHelper.hasAllCameraPermissions(this)) {
    // 请求相机权限
}
```

### 3. 资源管理

确保在Activity生命周期中正确管理资源：

```kotlin

class MainActivity : ComponentActivity() {
    private lateinit var webViewBridge: WebViewBridge
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 初始化WebViewBridge
        webViewBridge = WebViewBridge(this, webView)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // 释放资源，防止内存泄漏
        webViewBridge.release()
    }
}
```

### 4. 错误处理和重试机制

```kotlin
// 处理连接失败
webViewBridge.addMessageListener { type, data ->
    when (type) {
        "bluetoothError" -> {
            val error = data.getString("message")
            if (error.contains("连接失败次数过多")) {
                // 建议用户检查设备状态
                showRetryDialog()
            }
        }
    }
}
```

### 5. JavaScript端最佳实践

```javascript
// 检查设备兼容性
const deviceInfo = JSON.parse(DeviceChecker.getDeviceInfo());
console.log('Device info:', deviceInfo);

if (!deviceInfo.bluetoothSupported) {
    alert('此设备不支持蓝牙功能');
    return;
}

// 检查权限
const missingPermissions = JSON.parse(BluetoothInterface.getMissingPermissions());
if (missingPermissions.length > 0) {
    alert('缺少必要权限: ' + missingPermissions.join(', '));
    return;
}

// 连接前检查蓝牙状态
const status = JSON.parse(BluetoothInterface.getBluetoothStatus());
if (!status.enabled) {
    alert('请先开启蓝牙');
    return;
}
```

## 1. AAR 发布前的准备

在将 WebBridgeKit 打包为 AAR 文件前，请确保完成以下准备工作：

### 1.1 库版本和配置

在 webbridgekit 模块的 build.gradle.kts 中添加版本信息：

```kotlin
android {
    namespace = "com.webbridgesdk.webbridgekit"  // 根据实际情况修改
    
    // 其他配置...
    
    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}
```

### 1.2 构建 AAR 文件

在项目根目录执行以下命令构建 AAR 文件：

```bash
./gradlew :webbridgekit:assembleRelease
```

生成的 AAR 文件位于 `webbridgekit/build/outputs/aar/` 目录。

### 1.3 发布到本地或远程仓库

将 AAR 发布到本地 Maven 仓库：

```kotlin
// 在 webbridgekit/build.gradle.kts 中添加
plugins {
    id("maven-publish")
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.example"
                artifactId = "webbridgekit"
                version = "1.0.0"
            }
        }
    }
}
```

执行发布命令：

```bash
./gradlew :webbridgekit:publishToMavenLocal
```

## 2. 包名和命名空间

### 2.1 包名冲突问题

当前库使用 `com.webbridgesdk.webbridgekit` 作为包名，这可能与其他库冲突。在正式项目中，请使用公司专有域名，如：

```kotlin
namespace = "com.yourcompany.webbridgekit"
```

### 2.2 重命名包名的注意事项

如果需要修改包名，请确保同步修改：

1. build.gradle.kts 中的 namespace
2. AndroidManifest.xml 中的 package 属性
3. 所有 Java/Kotlin 文件的包声明
4. 任何使用完全限定名引用类的地方

## 3. 权限管理

### 3.1 库申请的权限列表

WebBridgeKit 在 AndroidManifest.xml 中申请了以下权限：

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

### 3.2 控制权限合并

如果宿主应用不需要某些功能，可以在清单文件中使用 tools:node 控制权限合并：

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
          xmlns:tools="http://schemas.android.com/tools">
    
    <!-- 移除不需要的权限 -->
    <uses-permission android:name="android.permission.BLUETOOTH" tools:node="remove" />
    <uses-permission android:name="android.permission.CAMERA" tools:node="remove" />
    
    <!-- 其他配置... -->
</manifest>
```

### 3.3 运行时权限请求

提供一个工具类帮助集成者请求所需权限：

```kotlin
object WebBridgeKitPermissions {
    @JvmStatic
    fun getRequiredPermissions(context: Context, features: Set<String>): Array<String> {
        val permissions = mutableListOf<String>()
        
        if (features.contains("bluetooth")) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
                permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            } else {
                permissions.add(Manifest.permission.BLUETOOTH)
                permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
            }
        }
        
        if (features.contains("camera")) {
            permissions.add(Manifest.permission.CAMERA)
        }
        
        // 根据需要返回权限列表
        return permissions.toTypedArray()
    }
}
```

## 4. 依赖冲突处理

### 4.1 库使用的主要依赖

WebBridgeKit 依赖以下库：
- androidx.webkit:webkit:1.7.0
- com.journeyapps:zxing-android-embedded:4.3.0
- androidx.camera:camera-* (多个 CameraX 相关库)

### 4.2 宿主应用中解决依赖冲突

在宿主应用的 build.gradle.kts 中使用 resolutionStrategy 控制版本冲突：

```kotlin
configurations.all {
    resolutionStrategy {
        // 强制使用特定版本的依赖
        force("androidx.webkit:webkit:1.7.0")
        force("com.journeyapps:zxing-android-embedded:4.3.0")
    }
}
```

### 4.3 排除特定依赖

如果宿主应用已经包含某些依赖，可以在引入 WebBridgeKit 时排除这些依赖：

```kotlin
dependencies {
    implementation("com.example:webbridgekit:1.0.0") {
        exclude(group = "com.journeyapps", module = "zxing-android-embedded")
    }
    
    // 使用宿主应用自己的版本
    implementation("com.journeyapps:zxing-android-embedded:4.2.0")
}
```

## 5. JavaScript 接口冲突

### 5.1 接口名冲突问题

WebBridgeKit 向 WebView 注入了三个 JavaScript 接口：
- BluetoothInterface
- CameraManager
- MessageBridge

这些名称可能与宿主应用已有的 JavaScript 接口冲突。

### 5.2 使用可配置的接口名

修改 WebViewBridge 类，使接口名可配置：

```kotlin
class WebViewBridge(
    private val activity: Activity,
    private val webView: WebView,
    private val config: WebViewBridgeConfig = WebViewBridgeConfig()
) {
    data class WebViewBridgeConfig(
        val bluetoothInterfaceName: String = "BluetoothInterface",
        val cameraManagerName: String = "CameraManager",
        val messageBridgeName: String = "MessageBridge"
    )
    
    // 在初始化时使用配置的接口名
    private fun initManagers() {
        bluetoothManager = BluetoothManager(activity, this)
        cameraManager = CameraManager(activity, this)
        messageManager = MessageManager(activity, this)
        
        webView.addJavascriptInterface(bluetoothManager, config.bluetoothInterfaceName)
        webView.addJavascriptInterface(cameraManager, config.cameraManagerName)
        webView.addJavascriptInterface(messageManager, config.messageBridgeName)
    }
}
```

### 5.3 使用示例

```kotlin
// 使用自定义接口名
val config = WebViewBridge.WebViewBridgeConfig(
    bluetoothInterfaceName = "CustomBT",
    cameraManagerName = "CustomCamera",
    messageBridgeName = "CustomMessage"
)
val webViewBridge = WebViewBridge(this, webView, config)

// 在 HTML 中使用自定义名称
// CustomBT.connectToDevice("XX:XX:XX:XX:XX:XX");
```

## 6. 资源冲突

### 6.1 资源命名冲突

WebBridgeKit 使用 zxing_CaptureTheme 等资源，可能与宿主应用资源冲突。

### 6.2 使用资源前缀

在库的 build.gradle.kts 中添加资源前缀：

```kotlin
android {
    resourcePrefix = "webbridgekit_"
}
```

重命名现有资源以使用前缀，例如将 `zxing_CaptureTheme` 改为 `webbridgekit_zxing_CaptureTheme`。

### 6.3 适应宿主应用主题

提供方法让宿主应用自定义扫码活动的主题：

```kotlin
class WebViewBridge {
    // ...
    companion object {
        var scanActivityTheme: Int = R.style.webbridgekit_zxing_CaptureTheme
    }
}

// 宿主应用中设置自定义主题
WebViewBridge.scanActivityTheme = R.style.MyCustomScanTheme
```

## 7. 清单文件合并

### 7.1 活动注册冲突

WebBridgeKit 在清单文件中注册了 QRScanActivity：

```xml
<activity
    android:name=".QRScanActivity"
    android:screenOrientation="portrait"
    android:exported="false"
    android:theme="@style/zxing_CaptureTheme" />
```

**注意**：固定屏幕方向可能与宿主应用的屏幕适配策略冲突，确保 @style/zxing_CaptureTheme 样式可用，可能需要在宿主应用中重新定义

### 7.2 使用清单占位符

使用清单占位符提供灵活性：

```xml
<activity
    android:name=".QRScanActivity"
    android:screenOrientation="${scanActivityOrientation}"
    android:exported="false"
    android:theme="@style/webbridgekit_zxing_CaptureTheme" />
```

在 build.gradle.kts 中设置默认值：

```kotlin
android {
    defaultConfig {
        manifestPlaceholders["scanActivityOrientation"] = "portrait"
    }
}
```

宿主应用可以覆盖这些值：

```kotlin
android {
    defaultConfig {
        manifestPlaceholders["scanActivityOrientation"] = "fullSensor"
    }
}
```

## 8. 多应用场景

### 8.1 多应用并存问题

当多个集成 WebBridgeKit 的应用安装在同一设备上时，可能会出现冲突。

### 8.2 唯一请求码

为避免请求码冲突，使用动态生成的请求码或与宿主应用包名相关的值：

```kotlin
class CameraManager(context: Context, callback: WebViewCallback) {
    private val REQUEST_QR_SCAN = context.packageName.hashCode() and 0xFFFF
    // 或者提供方法让宿主应用配置
    // ...
}
```

### 8.3 进程隔离

确保库的功能在宿主应用的进程内运行，避免跨进程通信问题：

```xml
<application
    android:name=".YourApplication"
    android:process=":main">
    <!-- 活动配置... -->
</application>
```

## 9. 混淆与发布配置

### 9.1 添加混淆规则

为确保库在混淆后正常工作，提供 consumer-rules.pro 文件：

```proguard
# 保留 JavaScript 接口
-keepclassmembers class com.webbridgesdk.webbridgekit.BluetoothManager {
    @android.webkit.JavascriptInterface <methods>;
}
-keepclassmembers class com.webbridgesdk.webbridgekit.CameraManager {
    @android.webkit.JavascriptInterface <methods>;
}
-keepclassmembers class com.webbridgesdk.webbridgekit.MessageManager {
    @android.webkit.JavascriptInterface <methods>;
}

# 保留 ZXing 相关类
-keep class com.google.zxing.** { *; }

# 保留 WebViewCallback 接口
-keep interface com.webbridgesdk.webbridgekit.CameraManager$WebViewCallback { *; }
```

### 9.2 配置 R8

在 proguard-rules.pro 中添加规则以优化库大小：

```proguard
# 优化但保留公共 API
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*
-optimizationpasses 5
-allowaccessmodification
```

### 9.3 发布配置

配置发布信息以便使用远程仓库：

```kotlin
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.example"
                artifactId = "webbridgekit"
                version = "1.0.0"
                
                pom {
                    name.set("WebBridgeKit")
                    description.set("Android WebView bridge for Bluetooth and Camera functionality")
                    url.set("https://github.com/yourusername/webbridgekit")
                    
                    licenses {
                        license {
                            name.set("The Apache License, Version 2.0")
                            url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                        }
                    }
                    
                    developers {
                        developer {
                            id.set("yourusername")
                            name.set("Your Name")
                            email.set("your.email@example.com")
                        }
                    }
                }
            }
        }
    }
}
```

## 10. WebView 兼容性

### 10.1 WebView 版本差异

Android 设备上的 WebView 版本可能差异很大，影响 JavaScript 接口的兼容性。

### 10.2 检测和适配

在初始化时检测 WebView 版本并进行适配：

```kotlin
class WebViewBridge(activity: Activity, webView: WebView) {
    private fun setupWebView() {
        // 获取 WebView 版本信息
        val webViewPackageInfo = WebViewCompat.getCurrentWebViewPackage(activity)
        val webViewVersion = webViewPackageInfo?.versionName ?: "unknown"
        
        Log.d(TAG, "当前 WebView 版本: $webViewVersion")
        
        // 根据版本应用不同配置
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        
        // 对于较新的 WebView，启用额外功能
        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            WebSettingsCompat.setForceDark(webView.settings, WebSettingsCompat.FORCE_DARK_OFF)
        }
        
        // 对于较旧的 WebView，提供兼容性处理
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            // Android 8.0 以下的特殊处理
        }
    }
}
```

### 10.3 降级处理

为不支持某些功能的 WebView 提供降级方案：

```javascript
// H5 端检测功能可用性
function checkFeatureAvailability() {
    const features = {
        bluetooth: typeof BluetoothInterface !== 'undefined',
        camera: typeof CameraManager !== 'undefined',
        messaging: typeof MessageBridge !== 'undefined'
    };
    
    // 根据可用功能调整 UI
    if (!features.bluetooth) {
        document.getElementById('bluetoothSection').style.display = 'none';
    }
    
    return features;
}
```

## 11. 总结与最佳实践

### 11.1 模块化设计

考虑将库拆分为多个模块，让集成者按需引入：
- webbridgekit-core：核心 WebView 桥接功能
- webbridgekit-bluetooth：蓝牙功能
- webbridgekit-camera：相机和扫码功能

### 11.2 配置优先于约定

提供充分的配置选项，避免硬编码值：

```kotlin
data class WebBridgeKitConfig(
    // JavaScript 接口名称
    val bluetoothInterfaceName: String = "BluetoothInterface",
    val cameraManagerName: String = "CameraManager",
    val messageBridgeName: String = "MessageBridge",
    
    // 功能开关
    val bluetoothEnabled: Boolean = true,
    val cameraEnabled: Boolean = true,
    val messageEnabled: Boolean = true,
    
    // 主题和样式
    @StyleRes val scanActivityTheme: Int = R.style.webbridgekit_zxing_CaptureTheme,
    val scanActivityOrientation: Int = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
)
```

### 11.3 详细文档和示例

提供全面的文档和示例项目：
- API 参考文档
- 集成指南
- 常见问题解答
- 示例项目涵盖不同场景

### 11.4 版本兼容性策略

建立清晰的版本控制和兼容性策略：
- 语义化版本号（Semantic Versioning）
- 明确支持的 Android API 级别范围
- 明确标记废弃的 API 并提供迁移路径
- 提供版本迁移指南

### 11.5 持续集成和测试

建立自动化测试和持续集成流程：
- 单元测试覆盖核心逻辑
- 仪器测试验证 WebView 交互
- 在不同 Android 版本上进行兼容性测试
- 发布前的回归测试

最终，通过合理的设计和充分的准备，WebBridgeKit 库可以作为 AAR 顺利集成到各种 Android 应用中，提供稳定可靠的 WebView 桥接功能。