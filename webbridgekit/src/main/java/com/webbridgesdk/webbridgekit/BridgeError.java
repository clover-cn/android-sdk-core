package com.webbridgesdk.webbridgekit;

/**
 * 统一的桥接错误对象，确保 H5 能稳定处理失败场景。
 */
public class BridgeError {
    public static final BridgeError INVALID_MESSAGE = new BridgeError("INVALID_MESSAGE", "消息格式错误");
    public static final BridgeError FORBIDDEN_FRAME = new BridgeError("FORBIDDEN_FRAME", "非主 frame 调用被拒绝");
    public static final BridgeError UNSUPPORTED_ACTION = new BridgeError("UNSUPPORTED_ACTION", "不支持的能力或动作");
    public static final BridgeError PERMISSION_DENIED = new BridgeError("PERMISSION_DENIED", "缺少必要权限");
    public static final BridgeError INVALID_PARAMETER = new BridgeError("INVALID_PARAMETER", "参数无效");
    public static final BridgeError NATIVE_ERROR = new BridgeError("NATIVE_ERROR", "原生能力调用失败");
    public static final BridgeError CAMERA_SCAN_CANCELLED = new BridgeError("CAMERA_SCAN_CANCELLED", "扫码取消");

    private final String code;
    private final String message;

    public BridgeError(String code, String message) {
        this.code = code;
        this.message = message;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
