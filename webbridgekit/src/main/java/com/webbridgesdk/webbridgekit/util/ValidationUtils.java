package com.webbridgesdk.webbridgekit.util;

import java.util.regex.Pattern;

/**
 * 验证工具类
 * 提供常用的数据验证功能
 */
public class ValidationUtils {
    
    // MAC地址正则表达式
    private static final Pattern MAC_ADDRESS_PATTERN = 
        Pattern.compile("^([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})$");
    
    // UUID正则表达式
    private static final Pattern UUID_PATTERN = 
        Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    
    // 十六进制字符串正则表达式
    private static final Pattern HEX_PATTERN = 
        Pattern.compile("^[0-9A-Fa-f]+$");

    /**
     * 验证MAC地址格式
     */
    public static boolean isValidMacAddress(String macAddress) {
        if (isEmpty(macAddress)) {
            return false;
        }
        return MAC_ADDRESS_PATTERN.matcher(macAddress).matches();
    }

    /**
     * 验证UUID格式
     */
    public static boolean isValidUUID(String uuid) {
        if (isEmpty(uuid)) {
            return false;
        }
        return UUID_PATTERN.matcher(uuid).matches();
    }

    /**
     * 验证十六进制字符串
     */
    public static boolean isValidHexString(String hexString) {
        if (isEmpty(hexString)) {
            return false;
        }
        // 长度必须是偶数
        if (hexString.length() % 2 != 0) {
            return false;
        }
        return HEX_PATTERN.matcher(hexString).matches();
    }

    /**
     * 验证字符串是否为空或null
     */
    public static boolean isEmpty(String str) {
        return str == null || str.isEmpty();
    }

    /**
     * 验证字符串是否非空
     */
    public static boolean isNotEmpty(String str) {
        return !isEmpty(str);
    }

    /**
     * 安全地获取字符串，避免null
     */
    public static String safeString(String str) {
        return str == null ? "" : str;
    }

    /**
     * 安全地获取字符串，提供默认值
     */
    public static String safeString(String str, String defaultValue) {
        return isEmpty(str) ? defaultValue : str;
    }
}
