package com.example.myapplication

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.example.mylibrary.WebViewBridge

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private lateinit var webViewBridge: WebViewBridge
    
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
            webViewBridge.loadUrl("file:///android_asset/index.html")
        } catch (e: Exception) {
            Log.e(TAG, "加载WebView时出错", e)
            Toast.makeText(this, "加载页面时出错: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        webViewBridge.handleActivityResult(requestCode, resultCode, data)
    }
}