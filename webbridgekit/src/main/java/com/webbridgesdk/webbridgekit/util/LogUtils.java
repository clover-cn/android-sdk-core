package com.webbridgesdk.webbridgekit.util;

import android.util.Log;

/**
 * 日志工具类
 * 提供统一的日志管理和控制
 */
public class LogUtils {
    private static final String TAG_PREFIX = "WebBridgeKit_";
    private static boolean isDebugEnabled = true; // 可以通过配置控制

    /**
     * 设置调试模式
     */
    public static void setDebugEnabled(boolean enabled) {
        isDebugEnabled = enabled;
    }

    /**
     * Debug 日志
     */
    public static void d(String tag, String message) {
        if (isDebugEnabled) {
            Log.d(TAG_PREFIX + tag, message);
        }
    }

    /**
     * Info 日志
     */
    public static void i(String tag, String message) {
        if (isDebugEnabled) {
            Log.i(TAG_PREFIX + tag, message);
        }
    }

    /**
     * Warning 日志
     */
    public static void w(String tag, String message) {
        Log.w(TAG_PREFIX + tag, message);
    }

    /**
     * Error 日志
     */
    public static void e(String tag, String message) {
        Log.e(TAG_PREFIX + tag, message);
    }

    /**
     * Error 日志带异常
     */
    public static void e(String tag, String message, Throwable throwable) {
        Log.e(TAG_PREFIX + tag, message, throwable);
    }

    /**
     * Verbose 日志
     */
    public static void v(String tag, String message) {
        if (isDebugEnabled) {
            Log.v(TAG_PREFIX + tag, message);
        }
    }
}
