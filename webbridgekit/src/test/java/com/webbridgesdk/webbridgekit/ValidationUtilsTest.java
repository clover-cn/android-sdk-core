package com.webbridgesdk.webbridgekit;

import com.webbridgesdk.webbridgekit.util.ValidationUtils;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * ValidationUtils 单元测试
 */
public class ValidationUtilsTest {

    @Test
    public void testValidMacAddress() {
        assertTrue(ValidationUtils.isValidMacAddress("AA:BB:CC:DD:EE:FF"));
        assertTrue(ValidationUtils.isValidMacAddress("aa:bb:cc:dd:ee:ff"));
        assertTrue(ValidationUtils.isValidMacAddress("12:34:56:78:9A:BC"));
        assertTrue(ValidationUtils.isValidMacAddress("12-34-56-78-9A-BC"));
    }

    @Test
    public void testInvalidMacAddress() {
        assertFalse(ValidationUtils.isValidMacAddress(""));
        assertFalse(ValidationUtils.isValidMacAddress(null));
        assertFalse(ValidationUtils.isValidMacAddress("AA:BB:CC:DD:EE"));
        assertFalse(ValidationUtils.isValidMacAddress("AA:BB:CC:DD:EE:GG"));
        assertFalse(ValidationUtils.isValidMacAddress("AA-BB-CC-DD-EE-FF-00"));
    }

    @Test
    public void testValidUUID() {
        assertTrue(ValidationUtils.isValidUUID("550e8400-e29b-41d4-a716-446655440000"));
        assertTrue(ValidationUtils.isValidUUID("6ba7b810-9dad-11d1-80b4-00c04fd430c8"));
        assertTrue(ValidationUtils.isValidUUID("0000FFF0-0000-1000-8000-00805F9B34FB"));
    }

    @Test
    public void testInvalidUUID() {
        assertFalse(ValidationUtils.isValidUUID(""));
        assertFalse(ValidationUtils.isValidUUID(null));
        assertFalse(ValidationUtils.isValidUUID("550e8400-e29b-41d4-a716"));
        assertFalse(ValidationUtils.isValidUUID("550e8400-e29b-41d4-a716-446655440000-extra"));
        assertFalse(ValidationUtils.isValidUUID("550e8400-e29b-41d4-g716-446655440000"));
    }

    @Test
    public void testValidHexString() {
        assertTrue(ValidationUtils.isValidHexString("AABBCCDD"));
        assertTrue(ValidationUtils.isValidHexString("aabbccdd"));
        assertTrue(ValidationUtils.isValidHexString("1234567890ABCDEF"));
        assertTrue(ValidationUtils.isValidHexString("00"));
    }

    @Test
    public void testInvalidHexString() {
        assertFalse(ValidationUtils.isValidHexString(""));
        assertFalse(ValidationUtils.isValidHexString(null));
        assertFalse(ValidationUtils.isValidHexString("AAB")); // 奇数长度
        assertFalse(ValidationUtils.isValidHexString("AABBCCGG")); // 包含非十六进制字符
        assertFalse(ValidationUtils.isValidHexString("AA BB CC DD")); // 包含空格
    }

    @Test
    public void testSafeString() {
        assertEquals("", ValidationUtils.safeString(null));
        assertEquals("test", ValidationUtils.safeString("test"));
        assertEquals("", ValidationUtils.safeString(""));
        
        assertEquals("default", ValidationUtils.safeString(null, "default"));
        assertEquals("test", ValidationUtils.safeString("test", "default"));
        assertEquals("default", ValidationUtils.safeString("", "default"));
    }
}
