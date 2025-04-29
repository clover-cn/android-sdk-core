package com.example.myapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import android.widget.Button
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.webbridgesdk.webbridgekit.WebViewBridge
import org.json.JSONException
import org.json.JSONObject

class MainActivity : ComponentActivity(), WebViewBridge.MessageListener {
    private lateinit var webView: WebView
    private lateinit var webViewBridge: WebViewBridge
    private var floatView: View? = null
    
    private val TAG = "MainActivity"

    private val permissions: Array<String>
        get() {
            val basePermissions = arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN
            )

            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12 (API 31)及以上版本需要这些额外权限
                basePermissions + arrayOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN
                )
            } else {
                basePermissions
            }
        }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // 记录每个权限的授权状态
        permissions.forEach { (permission, isGranted) ->
            Log.d(TAG, "权限 $permission: ${if (isGranted) "已授权" else "未授权"}")
        }

        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            Log.d(TAG, "所有请求的权限都已获取")
            loadWebView()
        } else {
            // 找出哪些权限被拒绝
            val deniedPermissions = permissions.filter { !it.value }.keys.joinToString(", ")
            Toast.makeText(this, "以下权限被拒绝: $deniedPermissions", Toast.LENGTH_LONG).show()
            // 尝试加载WebView，即使某些权限被拒绝
            loadWebView()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        // 过滤出未授权的权限
        val permissionsToRequest = permissions.filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsToRequest.isEmpty()) {
            Log.d(TAG, "所有权限已经获取，直接加载WebView")
            loadWebView()
        } else {
            Log.d(TAG, "请求以下权限: ${permissionsToRequest.joinToString(", ")}")
            requestPermissionLauncher.launch(permissionsToRequest)
        }
    }

    private fun loadWebView() {
        try {
            webViewBridge = WebViewBridge(this, webView)
            // 添加消息监听器
            webViewBridge.addMessageListener(this)
            webViewBridge.loadUrl("file:///android_asset/index.html")
            
            // 直接添加应用内悬浮按钮，不需要检查权限
            addFloatingButton()
        } catch (e: Exception) {
            Log.e(TAG, "加载WebView时出错", e)
            Toast.makeText(this, "加载页面时出错: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // 删除checkOverlayPermission方法，因为应用内悬浮窗不需要特殊权限

    /**
     * 添加应用内悬浮按钮
     */
    private fun addFloatingButton() {
        // 获取WindowManager服务
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // 创建悬浮按钮
        val button = Button(this)
        button.text = "发送数据"
        button.alpha = 0.8f
        button.setBackgroundColor(0xFF5C6BC0.toInt()) // 设置背景色

        // 配置WindowManager布局参数 - 使用TYPE_APPLICATION或TYPE_APPLICATION_PANEL
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION, // 应用内悬浮窗类型
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        
        // 设置初始位置在右下角
        params.gravity = Gravity.END or Gravity.BOTTOM
        params.x = 0
        params.y = 100

        // 处理点击事件
        button.setOnClickListener {
            sendMessageToH5()
        }

        // 处理拖动事件
        var initialX: Int = 0
        var initialY: Int = 0
        var initialTouchX: Float = 0f
        var initialTouchY: Float = 0f
        
        button.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // 记录初始位置
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    // 计算移动后的位置
                    params.x = initialX + (initialTouchX - event.rawX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    // 更新悬浮窗位置
                    windowManager.updateViewLayout(v, params)
                    true
                }
                else -> false
            }
        }

        // 将按钮添加到窗口
        windowManager.addView(button, params)
        floatView = button
    }
    
    /**
     * 要发送的消息类型列表
     */
    private val messageTypes = arrayOf(
        "appMessage", "notification", "response", "update"
    )
    
    /**
     * 发送消息到H5
     */
    private fun sendMessageToH5() {
        try {
            // 随机选择一个消息类型
            // 使用安全的取模方法，避免整数溢出问题
            val index = (System.currentTimeMillis() % messageTypes.size).toInt()
            val type = messageTypes[index]
            
            // 创建消息数据
            val messageData = when (type) {
                "appMessage" -> createAppMessage()
                "notification" -> createNotification()
                "response" -> createResponseData()
                else -> createUpdateData()
            }
            
            // 发送消息到H5
            webViewBridge.sendMessageToH5(type, messageData)
            Toast.makeText(this, "发送了[$type]类型的数据", Toast.LENGTH_SHORT).show()
            Log.d(TAG, "发送消息到H5: $type, 数据: $messageData")
        } catch (e: JSONException) {
            Log.e(TAG, "发送消息时出错", e)
            Toast.makeText(this, "发送消息时出错: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 创建普通App消息
     */
    private fun createAppMessage(): JSONObject {
        return JSONObject().apply {
            put("text", "来自App的普通消息")
            put("number", System.currentTimeMillis() % 1000)
            put("boolean", true)
            put("timestamp", System.currentTimeMillis())
            
            // 添加一个嵌套对象
            put("nestedObject", JSONObject().apply {
                put("name", "Android App")
                put("version", Build.VERSION.RELEASE)
            })
        }
    }
    
    /**
     * 创建通知消息
     */
    private fun createNotification(): JSONObject {
        return JSONObject().apply {
            put("title", "系统通知")
            put("content", "这是一条来自Android App的重要通知")
            put("timestamp", System.currentTimeMillis())
            put("level", "info") // info, warning, error
            put("actions", JSONObject().apply {
                put("primary", "查看详情")
                put("dismiss", "忽略")
            })
        }
    }
    
    /**
     * 创建响应数据
     */
    private fun createResponseData(): JSONObject {
        return JSONObject().apply {
            put("status", "success")
            put("requestId", "req_${System.currentTimeMillis()}")
            put("result", JSONObject().apply {
                put("code", 200)
                put("message", "操作成功")
                put("timestamp", System.currentTimeMillis())
            })
        }
    }
    
    /**
     * 创建更新数据
     */
    private fun createUpdateData(): JSONObject {
        return JSONObject().apply {
            put("updateType", "system")
            put("details", JSONObject().apply {
                put("currentVersion", "1.2.5")
                put("newVersion", "1.3.0")
                put("releaseNotes", "修复了一些bug，提升了性能")
                put("mandatory", false)
                put("size", "15.2MB")
            })
        }
    }
    
    /**
     * 实现WebViewBridge.MessageListener接口
     */
    override fun onMessageReceived(type: String, data: JSONObject?) {
        runOnUiThread {
            val messageText = when (type) {
                "h5Message" -> "收到H5普通消息"
                "userInfo" -> "收到用户信息"
                "pageData" -> "收到页面数据"
                "action" -> "收到操作请求"
                else -> "收到未知类型消息: $type"
            }
            
            Toast.makeText(this, messageText, Toast.LENGTH_SHORT).show()
            Log.d(TAG, "收到H5消息: $type, 数据: $data")
            
            // 如果是操作请求，发送响应
            if (type == "action" && data != null) {
                try {
                    val actionType = data.optString("actionType")
                    val requestId = data.optString("requestId")
                    
                    if (actionType == "openNativeDialog" && requestId.isNotEmpty()) {
                        // 延迟1秒后发送响应
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            sendActionResponse(requestId, "确定")
                        }, 1000)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "处理操作请求时出错", e)
                }
            }
        }
    }
    
    /**
     * 发送操作响应到H5
     */
    private fun sendActionResponse(requestId: String, result: String) {
        try {
            val responseData = JSONObject().apply {
                put("status", "completed")
                put("requestId", requestId)
                put("result", result)
                put("timestamp", System.currentTimeMillis())
            }
            
            webViewBridge.sendMessageToH5("response", responseData)
            Log.d(TAG, "已发送操作响应: $responseData")
        } catch (e: Exception) {
            Log.e(TAG, "发送操作响应时出错", e)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        webViewBridge.handleActivityResult(requestCode, resultCode, data)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // 移除悬浮窗
        floatView?.let {
            val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            windowManager.removeView(it)
        }
        
        // 移除消息监听器
        if (::webViewBridge.isInitialized) {
            webViewBridge.removeMessageListener(this)
        }
    }
    
    companion object {
        // 移除OVERLAY_PERMISSION_REQUEST_CODE常量，不再需要
    }
}