# WebBridgeKit Consumer ProGuard Rules
# 这些规则会自动应用到使用此库的应用中

# 保持 JavaScript 接口类不被混淆
-keepclassmembers class com.webbridgesdk.webbridgekit.** {
    @android.webkit.JavascriptInterface <methods>;
}

# 保持所有公共 API 类
-keep public class com.webbridgesdk.webbridgekit.WebViewBridge { *; }
-keep public class com.webbridgesdk.webbridgekit.WebViewBridgeConfig { *; }
-keep public class com.webbridgesdk.webbridgekit.WebViewBridgeConfig$* { *; }
-keep public class com.webbridgesdk.webbridgekit.BridgeError { *; }
-keep public class com.webbridgesdk.webbridgekit.PermissionHelper { *; }
-keep public class com.webbridgesdk.webbridgekit.DeviceCompatibilityChecker { *; }

# 保持接口和回调
-keep interface com.webbridgesdk.webbridgekit.WebViewBridge$MessageListener { *; }
-keep interface com.webbridgesdk.webbridgekit.CameraManager$WebViewCallback { *; }

# ZXing 相关
-keep class com.google.zxing.** { *; }
-keep class com.journeyapps.barcodescanner.** { *; }

# JSON 相关
-keep class org.json.** { *; }
