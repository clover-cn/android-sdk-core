package com.webbridgesdk.webbridgekit.error;

/**
 * WebBridge 自定义异常类
 * 用于统一处理库内部的异常情况
 */
public class WebBridgeException extends Exception {
    
    public enum ErrorCode {
        BLUETOOTH_NOT_SUPPORTED(1001, "设备不支持蓝牙"),
        BLUETOOTH_NOT_ENABLED(1002, "蓝牙未启用"),
        BLUETOOTH_PERMISSION_DENIED(1003, "缺少蓝牙权限"),
        BLUETOOTH_CONNECTION_FAILED(1004, "蓝牙连接失败"),
        BLUETOOTH_WRITE_FAILED(1005, "蓝牙数据写入失败"),
        
        CAMERA_NOT_SUPPORTED(2001, "设备不支持相机"),
        CAMERA_PERMISSION_DENIED(2002, "缺少相机权限"),
        CAMERA_SCAN_FAILED(2003, "二维码扫描失败"),
        
        INVALID_PARAMETER(3001, "无效参数"),
        INVALID_MAC_ADDRESS(3002, "无效的MAC地址"),
        INVALID_UUID(3003, "无效的UUID"),
        INVALID_HEX_DATA(3004, "无效的十六进制数据"),
        
        WEBVIEW_ERROR(4001, "WebView错误"),
        JAVASCRIPT_ERROR(4002, "JavaScript执行错误"),
        
        UNKNOWN_ERROR(9999, "未知错误");
        
        private final int code;
        private final String message;
        
        ErrorCode(int code, String message) {
            this.code = code;
            this.message = message;
        }
        
        public int getCode() {
            return code;
        }
        
        public String getMessage() {
            return message;
        }
    }
    
    private final ErrorCode errorCode;
    
    public WebBridgeException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }
    
    public WebBridgeException(ErrorCode errorCode, String detailMessage) {
        super(errorCode.getMessage() + ": " + detailMessage);
        this.errorCode = errorCode;
    }
    
    public WebBridgeException(ErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
    }
    
    public WebBridgeException(ErrorCode errorCode, String detailMessage, Throwable cause) {
        super(errorCode.getMessage() + ": " + detailMessage, cause);
        this.errorCode = errorCode;
    }
    
    public ErrorCode getErrorCode() {
        return errorCode;
    }
    
    public int getCode() {
        return errorCode.getCode();
    }
}
