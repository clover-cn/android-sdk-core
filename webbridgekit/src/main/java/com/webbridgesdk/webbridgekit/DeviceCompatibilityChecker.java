package com.webbridgesdk.webbridgekit;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;
import android.webkit.JavascriptInterface;

/**
 * 设备兼容性检查工具类
 * 用于检查设备是否支持WebBridgeKit的各项功能
 */
public class DeviceCompatibilityChecker {
    private static final String TAG = "DeviceCompatibilityChecker";
    
    private Context context;
    
    public DeviceCompatibilityChecker(Context context) {
        this.context = context;
    }
    
    /**
     * 检查设备是否支持蓝牙功能
     */
    @JavascriptInterface
    public boolean isBluetoothSupported() {
        return BluetoothAdapter.getDefaultAdapter() != null;
    }
    
    /**
     * 检查设备是否支持BLE功能
     */
    @JavascriptInterface
    public boolean isBLESupported() {
        return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE);
    }
    
    /**
     * 检查设备是否支持相机功能
     */
    @JavascriptInterface
    public boolean isCameraSupported() {
        return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA);
    }
    
    /**
     * 检查Android版本兼容性
     */
    @JavascriptInterface
    public boolean isAndroidVersionSupported() {
        // 最低支持Android 5.0 (API 21)
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP;
    }
    
    /**
     * 获取设备信息
     */
    @JavascriptInterface
    public String getDeviceInfo() {
        return String.format(
            "{\"manufacturer\":\"%s\",\"model\":\"%s\",\"androidVersion\":\"%s\",\"apiLevel\":%d,\"bluetoothSupported\":%b,\"bleSupported\":%b,\"cameraSupported\":%b}",
            Build.MANUFACTURER,
            Build.MODEL,
            Build.VERSION.RELEASE,
            Build.VERSION.SDK_INT,
            isBluetoothSupported(),
            isBLESupported(),
            isCameraSupported()
        );
    }
    
    /**
     * 检查是否为已知有问题的设备
     */
    public boolean isProblematicDevice() {
        String manufacturer = Build.MANUFACTURER.toLowerCase();
        String model = Build.MODEL.toLowerCase();
        
        // 一些已知在蓝牙连接方面有问题的设备
        if (manufacturer.contains("samsung")) {
            // 某些三星设备在Android 6.0上有蓝牙连接问题
            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.M) {
                Log.w(TAG, "Samsung device on Android 6.0 may have Bluetooth issues");
                return true;
            }
        }
        
        // 可以根据实际测试结果添加更多设备
        return false;
    }
    
    /**
     * 获取推荐的连接参数
     */
    public ConnectionParameters getRecommendedConnectionParameters() {
        ConnectionParameters params = new ConnectionParameters();
        
        // 根据设备类型调整参数
        if (isProblematicDevice()) {
            params.connectionTimeout = 5000; // 增加超时时间
            params.retryDelay = 2000; // 增加重试延迟
            params.maxRetries = 1; // 减少重试次数
        } else {
            params.connectionTimeout = 2000;
            params.retryDelay = 1000;
            params.maxRetries = 2;
        }
        
        return params;
    }
    
    /**
     * 连接参数类
     */
    public static class ConnectionParameters {
        public long connectionTimeout = 2000;
        public int retryDelay = 1000;
        public int maxRetries = 2;
        public int preferredMtu = 247;
    }
} 