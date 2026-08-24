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
    public static final BridgeError BLUETOOTH_ERROR = new BridgeError("BLUETOOTH_ERROR", "蓝牙操作失败");
    public static final BridgeError BLUETOOTH_NOT_SUPPORTED = new BridgeError("BLUETOOTH_NOT_SUPPORTED", "设备不支持蓝牙");
    public static final BridgeError BLUETOOTH_DISABLED = new BridgeError("BLUETOOTH_DISABLED", "蓝牙未启用");
    public static final BridgeError BLUETOOTH_BUSY = new BridgeError("BLUETOOTH_BUSY", "蓝牙操作正在进行中");
    public static final BridgeError BLUETOOTH_CONNECT_TIMEOUT = new BridgeError("BLUETOOTH_CONNECT_TIMEOUT", "蓝牙连接超时");
    public static final BridgeError BLUETOOTH_CONNECTION_FAILED = new BridgeError("BLUETOOTH_CONNECTION_FAILED", "蓝牙连接失败");
    public static final BridgeError BLUETOOTH_SERVICE_DISCOVERY_FAILED = new BridgeError("BLUETOOTH_SERVICE_DISCOVERY_FAILED", "蓝牙服务发现失败");
    public static final BridgeError BLUETOOTH_DISCONNECT_TIMEOUT = new BridgeError("BLUETOOTH_DISCONNECT_TIMEOUT", "蓝牙断开超时");
    public static final BridgeError BLUETOOTH_DISCONNECT_FAILED = new BridgeError("BLUETOOTH_DISCONNECT_FAILED", "蓝牙断开失败");
    public static final BridgeError BLUETOOTH_NOT_CONNECTED = new BridgeError("BLUETOOTH_NOT_CONNECTED", "蓝牙未连接");
    public static final BridgeError BLUETOOTH_WRITE_FAILED = new BridgeError("BLUETOOTH_WRITE_FAILED", "蓝牙写入失败");
    public static final BridgeError BLUETOOTH_WRITE_TIMEOUT = new BridgeError("BLUETOOTH_WRITE_TIMEOUT", "蓝牙写入超时");
    public static final BridgeError BLUETOOTH_DISCOVERY_FAILED = new BridgeError("BLUETOOTH_DISCOVERY_FAILED", "蓝牙搜索失败");
    public static final BridgeError BLUETOOTH_CANCELLED = new BridgeError("BLUETOOTH_CANCELLED", "蓝牙操作已取消");
    public static final BridgeError BLUETOOTH_RELEASED = new BridgeError("BLUETOOTH_RELEASED", "蓝牙管理器已释放");

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
