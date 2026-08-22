package com.webbridgesdk.webbridgekit;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.core.content.ContextCompat;
import java.util.ArrayList;
import java.util.List;

/**
 * 权限管理工具类
 * 提供统一的权限检查和管理功能
 */
public class PermissionHelper {
    
    /**
     * 获取蓝牙相关权限列表
     */
    public static String[] getBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return new String[] {
                Manifest.permission.BLUETOOTH_CONNECT
            };
        } else {
            return new String[] {
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN
            };
        }
    }

    /**
     * 获取蓝牙搜索相关权限列表
     */
    public static String[] getBluetoothScanPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return new String[] {
                Manifest.permission.BLUETOOTH_SCAN
            };
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return new String[] {
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            };
        } else {
            return new String[] {
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN
            };
        }
    }
    
    /**
     * 获取相机权限列表
     */
    public static String[] getCameraPermissions() {
        return new String[] {
            Manifest.permission.CAMERA
        };
    }

    public static String[] getRequiredPermissions(String feature, String action) {
        if ("bluetooth".equals(feature)) {
            if ("startDiscovery".equals(action) || "stopDiscovery".equals(action)) {
                return getBluetoothScanPermissions();
            }
            if ("getDiscoveredDevices".equals(action)) {
                return new String[0];
            }
            return getBluetoothPermissions();
        }
        if ("camera".equals(feature) && "scanQr".equals(action)) {
            return getCameraPermissions();
        }
        return new String[0];
    }
    
    /**
     * 获取所有需要的权限
     */
    public static String[] getAllRequiredPermissions() {
        List<String> permissions = new ArrayList<>();
        
        // 添加蓝牙权限
        for (String permission : getBluetoothPermissions()) {
            addPermissionIfAbsent(permissions, permission);
        }
        for (String permission : getBluetoothScanPermissions()) {
            addPermissionIfAbsent(permissions, permission);
        }
        
        // 添加相机权限
        for (String permission : getCameraPermissions()) {
            addPermissionIfAbsent(permissions, permission);
        }
        
        return permissions.toArray(new String[0]);
    }
    
    /**
     * 检查是否拥有所有蓝牙权限
     */
    public static boolean hasAllBluetoothPermissions(Context context) {
        for (String permission : getBluetoothPermissions()) {
            if (ContextCompat.checkSelfPermission(context, permission) 
                != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * 检查是否拥有所有相机权限
     */
    public static boolean hasAllCameraPermissions(Context context) {
        for (String permission : getCameraPermissions()) {
            if (ContextCompat.checkSelfPermission(context, permission) 
                != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * 获取缺失的蓝牙权限
     */
    public static List<String> getMissingBluetoothPermissions(Context context) {
        List<String> missing = new ArrayList<>();
        for (String permission : getBluetoothPermissions()) {
            if (ContextCompat.checkSelfPermission(context, permission) 
                != PackageManager.PERMISSION_GRANTED) {
                missing.add(permission);
            }
        }
        return missing;
    }

    /**
     * 获取缺失的蓝牙搜索权限
     */
    public static List<String> getMissingBluetoothScanPermissions(Context context) {
        List<String> missing = new ArrayList<>();
        for (String permission : getBluetoothScanPermissions()) {
            if (ContextCompat.checkSelfPermission(context, permission)
                != PackageManager.PERMISSION_GRANTED) {
                missing.add(permission);
            }
        }
        return missing;
    }
    
    /**
     * 获取缺失的相机权限
     */
    public static List<String> getMissingCameraPermissions(Context context) {
        List<String> missing = new ArrayList<>();
        for (String permission : getCameraPermissions()) {
            if (ContextCompat.checkSelfPermission(context, permission) 
                != PackageManager.PERMISSION_GRANTED) {
                missing.add(permission);
            }
        }
        return missing;
    }
    
    /**
     * 获取所有缺失的权限
     */
    public static List<String> getAllMissingPermissions(Context context) {
        List<String> missing = new ArrayList<>();
        missing.addAll(getMissingBluetoothPermissions(context));
        missing.addAll(getMissingBluetoothScanPermissions(context));
        missing.addAll(getMissingCameraPermissions(context));
        return missing;
    }

    private static void addPermissionIfAbsent(List<String> permissions, String permission) {
        if (!permissions.contains(permission)) {
            permissions.add(permission);
        }
    }
} 
