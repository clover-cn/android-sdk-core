package com.webbridgesdk.webbridgekit;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.Map;
import java.util.HashMap;

public class BluetoothManager {
    private static final String TAG = "BluetoothManager";
    private static final long CONNECTION_TIMEOUT = 15000; // 连接、MTU 和服务发现的最终结果超时
    private static final long CONNECTION_START_DELAY = 800;
    private static final long SERVICE_DISCOVERY_DELAY = 500;
    private static final long SERVICE_DISCOVERY_FALLBACK_DELAY = 1200;
    private static final int PREFERRED_MTU = 247; // 首选MTU大小
    private Context context;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bluetoothLeScanner;
    private BluetoothGatt bluetoothGatt;
    private BluetoothDevice currentDevice;
    private Handler mainHandler;
    private WebViewBridge webViewBridge;
    private Runnable timeoutRunnable;
    private ScanCallback discoveryCallback;
    private Map<String, JSONObject> discoveredDevices = new LinkedHashMap<>();
    private Map<String, Boolean> characteristicNotificationEnabled = new HashMap<>();
    private Map<String, Boolean> characteristicReading = new HashMap<>();
    private boolean mtuConfigured = false;
    private int negotiatedMtu = 23;
    private boolean notificationsEnabled = true; // 添加通知控制开关，默认开启
    private Map<String, ChunkedWriteData> chunkedWriteData = new HashMap<>();
    private Map<String, Runnable> writeTimeouts = new HashMap<>();

    /**
     * 蓝牙命令的最终结果回调。所有回调都在主线程执行，并且每个命令只完成一次。
     */
    public interface OperationCallback {
        void onSuccess(JSONObject result);

        void onFailure(BridgeError error);
    }

    private OperationCallback connectionCallback;
    private long connectionOperationSequence = 0;
    private long activeConnectionOperationId = -1;
    private BluetoothDevice connectionTargetDevice;
    private boolean connectionReady = false;
    private boolean serviceDiscoveryStarted = false;
    private Runnable connectionStartRunnable;

    private OperationCallback disconnectCallback;
    private BluetoothGatt disconnectGatt;
    private Runnable disconnectTimeoutRunnable;

    private OperationCallback pendingWriteCallback;
    private long writeOperationSequence = 0;
    private long pendingWriteOperationId = -1;
    private String pendingWriteUuid;
    private BluetoothGatt pendingWriteGatt;
    private boolean pendingWriteChunked = false;

    public BluetoothManager(Context context, WebViewBridge webViewBridge) {
        this.context = context;
        this.webViewBridge = webViewBridge;
        this.bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public boolean isBluetoothSupported() {
        if (isReleased()) {
            Log.w(TAG, "BluetoothManager has been released");
            return false;
        }
        return bluetoothAdapter != null;
    }

    public boolean isBluetoothEnabled() {
        if (isReleased()) {
            Log.w(TAG, "BluetoothManager has been released");
            return false;
        }
        try {
            return bluetoothAdapter != null && bluetoothAdapter.isEnabled();
        } catch (SecurityException e) {
            Log.e(TAG, "Security exception checking bluetooth state: " + e.getMessage());
            return false;
        }
    }

    public String getPairedDevices() {
        if (isReleased()) {
            Log.w(TAG, "BluetoothManager has been released");
            return "[]";
        }
        
        if (!isBluetoothEnabled()) {
            return "[]";
        }

        try {
            JSONArray deviceList = new JSONArray();
            Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();

            for (BluetoothDevice device : pairedDevices) {
                JSONObject deviceInfo = new JSONObject();
                deviceInfo.put("name", device.getName() != null ? device.getName() : "Unknown");
                deviceInfo.put("address", device.getAddress());
                deviceList.put(deviceInfo);
            }

            return deviceList.toString();
        } catch (JSONException e) {
            Log.e(TAG, "Error building paired devices JSON: " + e.getMessage());
            return "[]";
        } catch (SecurityException e) {
            Log.e(TAG, "Security exception getting paired devices: " + e.getMessage());
            return "[]";
        } catch (Exception e) {
            Log.e(TAG, "Error getting paired devices: " + e.getMessage());
            return "[]";
        }
    }

    public void startDiscovery(OperationCallback callback) {
        if (isReleased()) {
            failOperation(callback, BridgeError.BLUETOOTH_RELEASED);
            return;
        }

        if (!isBluetoothSupported()) {
            failOperation(callback, BridgeError.BLUETOOTH_NOT_SUPPORTED);
            return;
        }

        if (!isBluetoothEnabled()) {
            failOperation(callback, BridgeError.BLUETOOTH_DISABLED);
            return;
        }

        if (!hasBluetoothScanPermission()) {
            failOperation(callback, BridgeError.PERMISSION_DENIED);
            return;
        }

        if (!isLocationServicesEnabled()) {
            failOperation(callback, new BridgeError(
                    BridgeError.BLUETOOTH_DISCOVERY_FAILED.getCode(),
                    "定位服务未开启，Android BLE 扫描可能无法返回附近设备"));
            return;
        }

        if (discoveryCallback != null) {
            notifyWebView("onDiscoveryStarted", "{}");
            successOperation(callback, resultWith("discovering", true));
            return;
        }

        bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
        if (bluetoothLeScanner == null) {
            failOperation(callback, new BridgeError(
                    BridgeError.BLUETOOTH_DISCOVERY_FAILED.getCode(),
                    "设备不支持BLE扫描或蓝牙未就绪"));
            return;
        }

        discoveredDevices.clear();
        discoveryCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                handleScanResult(result);
            }

            @Override
            public void onBatchScanResults(List<ScanResult> results) {
                for (ScanResult result : results) {
                    handleScanResult(result);
                }
            }

            @Override
            public void onScanFailed(int errorCode) {
                Log.e(TAG, "BLE scan failed: " + errorCode);
                discoveryCallback = null;
                notifyBluetoothError(new BridgeError(
                        BridgeError.BLUETOOTH_DISCOVERY_FAILED.getCode(),
                        "蓝牙扫描失败，错误码: " + errorCode));
                notifyWebView("onDiscoveryStopped", "{}");
            }
        };

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        try {
            bluetoothLeScanner.startScan(null, settings, discoveryCallback);
            notifyWebView("onDiscoveryStarted", "{}");
            successOperation(callback, resultWith("discovering", true));
        } catch (SecurityException e) {
            discoveryCallback = null;
            failOperation(callback, BridgeError.PERMISSION_DENIED);
        } catch (Exception e) {
            discoveryCallback = null;
            failOperation(callback, new BridgeError(
                    BridgeError.BLUETOOTH_DISCOVERY_FAILED.getCode(),
                    "启动蓝牙搜索失败: " + safeMessage(e)));
        }
    }

    public void stopDiscovery(OperationCallback callback) {
        if (isReleased()) {
            failOperation(callback, BridgeError.BLUETOOTH_RELEASED);
            return;
        }

        if (discoveryCallback == null) {
            notifyWebView("onDiscoveryStopped", "{}");
            successOperation(callback, resultWith("discovering", false));
            return;
        }

        BridgeError failure = null;
        try {
            if (bluetoothLeScanner != null) {
                bluetoothLeScanner.stopScan(discoveryCallback);
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Missing Bluetooth scan permission on stopScan: " + e.getMessage());
            failure = BridgeError.PERMISSION_DENIED;
        } catch (Exception e) {
            Log.e(TAG, "Error stopping BLE scan: " + e.getMessage());
            failure = new BridgeError(
                    BridgeError.BLUETOOTH_DISCOVERY_FAILED.getCode(),
                    "停止蓝牙搜索失败: " + safeMessage(e));
        } finally {
            discoveryCallback = null;
            notifyWebView("onDiscoveryStopped", "{}");
        }

        if (failure != null) {
            failOperation(callback, failure);
        } else {
            successOperation(callback, resultWith("discovering", false));
        }
    }

    public String getDiscoveredDevices() {
        JSONArray devices = new JSONArray();
        for (JSONObject device : discoveredDevices.values()) {
            devices.put(device);
        }
        return devices.toString();
    }

    private void handleScanResult(ScanResult result) {
        if (result == null || result.getDevice() == null) {
            return;
        }

        try {
            BluetoothDevice device = result.getDevice();
            String address = device.getAddress();
            if (address == null || discoveredDevices.containsKey(address)) {
                return;
            }

            String name = null;
            if (result.getScanRecord() != null) {
                name = result.getScanRecord().getDeviceName();
            }
            if (name == null) {
                try {
                    name = device.getName();
                } catch (SecurityException ignored) {
                    name = null;
                }
            }

            JSONObject deviceInfo = new JSONObject();
            deviceInfo.put("name", name != null ? name : "Unknown");
            deviceInfo.put("address", address);
            deviceInfo.put("rssi", result.getRssi());
            discoveredDevices.put(address, deviceInfo);
            notifyWebView("onBluetoothDeviceFound", deviceInfo.toString());
        } catch (JSONException e) {
            Log.e(TAG, "Error building discovered device JSON: " + e.getMessage());
        } catch (SecurityException e) {
            notifyWebView("onBluetoothError", "缺少必要的蓝牙扫描权限");
        }
    }

    public void connectToDevice(String macAddress, OperationCallback callback) {
        if (connectionCallback != null) {
            failOperation(callback, BridgeError.BLUETOOTH_BUSY);
            return;
        }

        if (disconnectCallback != null) {
            failOperation(callback, BridgeError.BLUETOOTH_BUSY);
            return;
        }

        if (isReleased()) {
            failOperation(callback, BridgeError.BLUETOOTH_RELEASED);
            return;
        }

        if (!isBluetoothSupported()) {
            failOperation(callback, BridgeError.BLUETOOTH_NOT_SUPPORTED);
            return;
        }

        if (!isBluetoothEnabled()) {
            failOperation(callback, BridgeError.BLUETOOTH_DISABLED);
            return;
        }

        if (!hasBluetoothPermissions()) {
            failOperation(callback, BridgeError.PERMISSION_DENIED);
            return;
        }

        failPendingWrite(BridgeError.BLUETOOTH_NOT_CONNECTED);
        cleanupConnection();
        connectionReady = false;
        serviceDiscoveryStarted = false;
        connectionTargetDevice = null;

        final long operationId = ++connectionOperationSequence;
        activeConnectionOperationId = operationId;
        connectionCallback = callback;
        connectionStartRunnable = () -> beginConnection(macAddress, operationId);
        mainHandler.postDelayed(connectionStartRunnable, CONNECTION_START_DELAY);
    }

    private void beginConnection(String macAddress, long operationId) {
        if (!isPendingConnection(operationId)) {
            return;
        }

        try {
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(macAddress);
            connectionTargetDevice = device;

            int bondState = device.getBondState();
            if (bondState == BluetoothDevice.BOND_NONE) {
                Log.i(TAG, "设备未配对，尝试直接连接（可能是BLE设备）");
            }

            connectToGattServer(device, operationId);
        } catch (IllegalArgumentException e) {
            completeConnectionFailure(operationId,
                    new BridgeError(BridgeError.INVALID_PARAMETER.getCode(), "无效的MAC地址"));
        } catch (SecurityException e) {
            completeConnectionFailure(operationId, BridgeError.PERMISSION_DENIED);
        } catch (Exception e) {
            completeConnectionFailure(operationId, new BridgeError(
                    BridgeError.BLUETOOTH_CONNECTION_FAILED.getCode(),
                    "开始蓝牙连接失败: " + safeMessage(e)));
        }
    }

    public void disconnect(OperationCallback callback) {
        if (isReleased()) {
            failOperation(callback, BridgeError.BLUETOOTH_RELEASED);
            return;
        }

        if (disconnectCallback != null) {
            failOperation(callback, BridgeError.BLUETOOTH_BUSY);
            return;
        }

        if (connectionCallback != null) {
            String address = connectionTargetDevice == null
                    ? null
                    : connectionTargetDevice.getAddress();
            cancelConnectionAttempt(BridgeError.BLUETOOTH_CANCELLED);
            notifyBluetoothDisconnected(address);
            successOperation(callback, resultWith("disconnected", true));
            return;
        }

        if (bluetoothGatt == null) {
            notifyBluetoothDisconnected(currentDevice == null ? null : currentDevice.getAddress());
            successOperation(callback, resultWith("disconnected", true));
            return;
        }

        disconnectCallback = callback;
        disconnectGatt = bluetoothGatt;
        final BluetoothGatt gatt = bluetoothGatt;
        final String address = currentDevice == null ? null : currentDevice.getAddress();
        notifyWebView("onBluetoothStateChange", "正在断开连接...");

        try {
            gatt.disconnect();
        } catch (SecurityException e) {
            completeDisconnectFailure(BridgeError.PERMISSION_DENIED, address);
            return;
        } catch (Exception e) {
            completeDisconnectFailure(new BridgeError(
                    BridgeError.BLUETOOTH_DISCONNECT_FAILED.getCode(),
                    "断开蓝牙连接失败: " + safeMessage(e)), address);
            return;
        }

        disconnectTimeoutRunnable = () -> {
            if (disconnectGatt != gatt || bluetoothGatt != gatt) {
                return;
            }

            Log.w(TAG, "Disconnect timeout, forcing close");
            OperationCallback pendingCallback = disconnectCallback;
            disconnectCallback = null;
            disconnectGatt = null;
            disconnectTimeoutRunnable = null;
            failPendingWrite(BridgeError.BLUETOOTH_NOT_CONNECTED);
            cleanupConnection();
            connectionReady = false;
            notifyBluetoothDisconnected(address);
            failOperation(pendingCallback, BridgeError.BLUETOOTH_DISCONNECT_TIMEOUT);
        };
        mainHandler.postDelayed(disconnectTimeoutRunnable, 2000);

        if (timeoutRunnable != null) {
            mainHandler.removeCallbacks(timeoutRunnable);
            timeoutRunnable = null;
        }
    }

    public void writeData(String serviceUUID, String characteristicUUID, String data) {
        if (data == null) {
            notifyBluetoothError(BridgeError.INVALID_PARAMETER);
            return;
        }
        writeRawHexData(serviceUUID, characteristicUUID, bytesToHex(data.getBytes()), null);
    }

    /**
     * 将十六进制字符串转换为字节数组并发送到蓝牙设备
     *
     * @param serviceUUID        服务UUID
     * @param characteristicUUID 特征值UUID
     * @param hexString          十六进制字符串，如"7B864814071027923000280033BD7D"
     */
    public void writeRawHexData(String serviceUUID,
                                 String characteristicUUID,
                                 String hexString,
                                 OperationCallback callback) {
        if (pendingWriteOperationId != -1) {
            failOperation(callback, BridgeError.BLUETOOTH_BUSY);
            return;
        }

        if (isReleased()) {
            failOperation(callback, BridgeError.BLUETOOTH_RELEASED);
            return;
        }

        if (bluetoothGatt == null) {
            failOperation(callback, BridgeError.BLUETOOTH_NOT_CONNECTED);
            return;
        }

        BluetoothGatt gatt = bluetoothGatt;
        String writeTimeoutKey = characteristicUUID;
        try {
            BluetoothGattService service = gatt.getService(UUID.fromString(serviceUUID));
            if (service == null) {
                failOperation(callback, new BridgeError(
                        BridgeError.BLUETOOTH_WRITE_FAILED.getCode(), "未找到指定服务"));
                return;
            }

            BluetoothGattCharacteristic characteristic =
                    service.getCharacteristic(UUID.fromString(characteristicUUID));
            if (characteristic == null) {
                failOperation(callback, new BridgeError(
                        BridgeError.BLUETOOTH_WRITE_FAILED.getCode(), "未找到指定特征值"));
                return;
            }
            writeTimeoutKey = characteristic.getUuid().toString();

            int properties = characteristic.getProperties();
            if ((properties & BluetoothGattCharacteristic.PROPERTY_WRITE) == 0 &&
                    (properties & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) == 0) {
                failOperation(callback, new BridgeError(
                        BridgeError.BLUETOOTH_WRITE_FAILED.getCode(), "该特征值不支持写入操作"));
                return;
            }

            byte[] dataBytes = hexStringToByteArray(hexString);
            if (dataBytes.length == 0) {
                failOperation(callback, new BridgeError(
                        BridgeError.INVALID_PARAMETER.getCode(), "无效的十六进制字符串"));
                return;
            }

            long operationId = ++writeOperationSequence;
            pendingWriteCallback = callback;
            pendingWriteOperationId = operationId;
            pendingWriteUuid = writeTimeoutKey;
            pendingWriteGatt = gatt;
            pendingWriteChunked = dataBytes.length > getChunkPayloadSize();

            if (pendingWriteChunked) {
                writeRawHexDataChunked(service, characteristic, dataBytes, operationId);
                return;
            }

            scheduleWriteTimeout(writeTimeoutKey, operationId, "写入操作超时");
            notifyWebView("onBluetoothStateChange", "正在发送十六进制数据...");

            characteristic.setValue(dataBytes);
            boolean writeResult = gatt.writeCharacteristic(characteristic);
            if (!writeResult) {
                finishWriteFailure(operationId, new BridgeError(
                        BridgeError.BLUETOOTH_WRITE_FAILED.getCode(), "写入操作失败"),
                        writeTimeoutKey, "failed");
            }
        } catch (IllegalArgumentException e) {
            BridgeError error = new BridgeError(
                    BridgeError.INVALID_PARAMETER.getCode(), "无效的参数: " + safeMessage(e));
            boolean started = pendingWriteOperationId != -1
                    && writeTimeoutKey.equals(pendingWriteUuid);
            if (started) {
                failPendingWriteIfStarted(writeTimeoutKey, error);
            } else {
                failOperation(callback, error);
            }
        } catch (SecurityException e) {
            boolean started = pendingWriteOperationId != -1
                    && writeTimeoutKey.equals(pendingWriteUuid);
            if (started) {
                failPendingWriteIfStarted(writeTimeoutKey, BridgeError.PERMISSION_DENIED);
            } else {
                failOperation(callback, BridgeError.PERMISSION_DENIED);
            }
        } catch (Exception e) {
            BridgeError error = new BridgeError(
                    BridgeError.BLUETOOTH_WRITE_FAILED.getCode(),
                    "发送数据出错: " + safeMessage(e));
            boolean started = pendingWriteOperationId != -1
                    && writeTimeoutKey.equals(pendingWriteUuid);
            if (started) {
                failPendingWriteIfStarted(writeTimeoutKey, error);
            } else {
                failOperation(callback, error);
            }
        }
    }

    /**
     * 分片发送大数据包
     *
     * @param service        蓝牙服务
     * @param characteristic 特征值
     * @param data           要发送的完整数据
     */
    private void writeRawHexDataChunked(BluetoothGattService service,
                                        BluetoothGattCharacteristic characteristic,
                                        byte[] data,
                                        long operationId) {
        final int chunkSize = getChunkPayloadSize();
        final int totalChunks = (int) Math.ceil((double) data.length / chunkSize);

        Log.d(TAG, "数据大小: " + data.length + "字节，将分为" + totalChunks + "片发送");
        notifyWebView("onBluetoothStateChange",
                String.format("数据大小: %d字节，将分为%d片发送", data.length, totalChunks));

        // 创建队列来存储所有数据片段
        ArrayList<byte[]> chunks = new ArrayList<>();
        for (int i = 0; i < data.length; i += chunkSize) {
            int end = Math.min(i + chunkSize, data.length);
            byte[] chunk = new byte[end - i];
            System.arraycopy(data, i, chunk, 0, end - i);
            chunks.add(chunk);
        }

        // 使用递归函数发送每一片
        sendNextChunk(characteristic, chunks, 0, totalChunks,
                characteristic.getUuid().toString(), operationId);
    }

    /**
     * 递归发送下一个数据片段（带特征值UUID参数）
     *
     * @param characteristic     特征值
     * @param chunks             所有数据片段的列表
     * @param index              当前要发送的片段索引
     * @param totalChunks        总片段数
     * @param characteristicUUID 特征值UUID
     * @param operationId        写入操作标识
     */
    private void sendNextChunk(BluetoothGattCharacteristic characteristic,
                               ArrayList<byte[]> chunks, int index, int totalChunks,
                               String characteristicUUID,
                               long operationId) {
        if (!isPendingWrite(operationId, characteristicUUID)) {
            return;
        }

        if (index >= chunks.size() || bluetoothGatt == null) {
            Log.d(TAG, "分片发送完成或连接已断开");
            finishWriteFailure(operationId, BridgeError.BLUETOOTH_NOT_CONNECTED,
                    characteristicUUID, "failed");
            return;
        }

        byte[] chunk = chunks.get(index);

        // 设置状态更新
        final int currentChunk = index + 1;
        notifyWebView("onBluetoothStateChange",
                String.format("正在发送第%d/%d片数据...", currentChunk, totalChunks));

        // 保存分片信息到Map中
        if (!chunkedWriteData.containsKey(characteristicUUID)) {
            ChunkedWriteData writeData = new ChunkedWriteData();
            writeData.chunks = chunks;
            writeData.currentIndex = index;
            writeData.totalChunks = totalChunks;
            writeData.operationId = operationId;
            chunkedWriteData.put(characteristicUUID, writeData);
        }

        scheduleWriteTimeout(characteristicUUID, operationId, "数据片段" + currentChunk + "写入超时");

        // 设置数据并写入
        try {
            characteristic.setValue(chunk);
            // Android 12+ 写入特征值需要 BLUETOOTH_CONNECT 权限
            boolean writeResult = bluetoothGatt.writeCharacteristic(characteristic);
            if (!writeResult) {
                Log.e(TAG, "片段" + currentChunk + "写入失败");
                finishWriteFailure(operationId, new BridgeError(
                        BridgeError.BLUETOOTH_WRITE_FAILED.getCode(),
                        "数据片段" + currentChunk + "写入失败"),
                        characteristicUUID, "failed");
            }
        } catch (SecurityException se) {
            Log.e(TAG, "Missing BLUETOOTH_CONNECT permission on writeCharacteristic: " + se.getMessage());
            finishWriteFailure(operationId, BridgeError.PERMISSION_DENIED,
                    characteristicUUID, "failed");
        } catch (Exception e) {
            finishWriteFailure(operationId, new BridgeError(
                    BridgeError.BLUETOOTH_WRITE_FAILED.getCode(),
                    "发送数据出错: " + safeMessage(e)), characteristicUUID, "failed");
        }

        // 写入成功后会在onCharacteristicWrite回调中处理下一片段
    }

    /**
     * 存储分片写入过程中的数据
     */
    private class ChunkedWriteData {
        ArrayList<byte[]> chunks;  // 所有数据片段
        int currentIndex;          // 当前片段索引
        int totalChunks;           // 总片段数
        long operationId;           // 写入操作标识
    }

    private boolean isPendingWrite(long operationId, String characteristicUUID) {
        return pendingWriteOperationId == operationId
                && pendingWriteOperationId != -1
                && characteristicUUID != null
                && characteristicUUID.equals(pendingWriteUuid)
                && bluetoothGatt != null
                && pendingWriteGatt == bluetoothGatt;
    }

    private boolean isPendingWrite(BluetoothGatt gatt, String characteristicUUID) {
        return gatt != null
                && pendingWriteGatt == gatt
                && pendingWriteOperationId != -1
                && characteristicUUID != null
                && characteristicUUID.equals(pendingWriteUuid);
    }

    private void scheduleWriteTimeout(String characteristicUUID,
                                      long operationId,
                                      String timeoutMessage) {
        final Runnable writeTimeoutRunnable = () -> {
            if (!isPendingWrite(operationId, characteristicUUID)) {
                return;
            }
            finishWriteFailure(operationId, new BridgeError(
                    BridgeError.BLUETOOTH_WRITE_TIMEOUT.getCode(), timeoutMessage),
                    characteristicUUID, "timeout");
        };
        Runnable previousWriteTimeout = writeTimeouts.put(characteristicUUID, writeTimeoutRunnable);
        if (previousWriteTimeout != null) {
            mainHandler.removeCallbacks(previousWriteTimeout);
        }
        mainHandler.postDelayed(writeTimeoutRunnable, 5000);
    }

    private void removeWriteTimeout(String characteristicUUID) {
        if (characteristicUUID == null) {
            return;
        }
        Runnable writeTimeout = writeTimeouts.remove(characteristicUUID);
        if (writeTimeout != null) {
            mainHandler.removeCallbacks(writeTimeout);
        }
    }

    private void failPendingWriteIfStarted(String characteristicUUID, BridgeError error) {
        if (pendingWriteOperationId != -1
                && characteristicUUID != null
                && characteristicUUID.equals(pendingWriteUuid)) {
            finishWriteFailure(pendingWriteOperationId, error, characteristicUUID, "failed");
        }
    }

    private void failPendingWrite(BridgeError error) {
        if (pendingWriteOperationId != -1 && pendingWriteUuid != null) {
            finishWriteFailure(pendingWriteOperationId, error, pendingWriteUuid, "failed");
        }
    }

    private void finishWriteSuccess(long operationId, JSONObject result) {
        if (pendingWriteOperationId != operationId || pendingWriteOperationId == -1) {
            return;
        }

        String uuid = pendingWriteUuid;
        OperationCallback callback = pendingWriteCallback;
        clearPendingWrite();
        notifyWebView("onWriteCompleted", result.toString());
        successOperation(callback, result);
    }

    private void finishWriteFailure(long operationId,
                                    BridgeError error,
                                    String characteristicUUID,
                                    String status) {
        if (!isPendingWrite(operationId, characteristicUUID)
                && !(pendingWriteOperationId == operationId
                && characteristicUUID != null
                && characteristicUUID.equals(pendingWriteUuid))) {
            return;
        }

        OperationCallback callback = pendingWriteCallback;
        JSONObject result = writeResult(characteristicUUID, false, 0);
        try {
            result.put("status", status);
        } catch (JSONException ignored) {
        }
        clearPendingWrite();
        notifyWebView("onWriteCompleted", result.toString());
        failOperation(callback, error);
    }

    private void clearPendingWrite() {
        removeWriteTimeout(pendingWriteUuid);
        if (pendingWriteUuid != null) {
            chunkedWriteData.remove(pendingWriteUuid);
        }
        pendingWriteCallback = null;
        pendingWriteOperationId = -1;
        pendingWriteUuid = null;
        pendingWriteGatt = null;
        pendingWriteChunked = false;
    }

    private JSONObject writeResult(String uuid, boolean chunked, int totalChunks) {
        JSONObject result = new JSONObject();
        try {
            result.put("uuid", uuid);
            result.put("status", "success");
            if (chunked) {
                result.put("chunked", true);
                result.put("totalChunks", totalChunks);
            }
        } catch (JSONException ignored) {
        }
        return result;
    }

    private int getChunkPayloadSize() {
        return Math.max(20, negotiatedMtu - 3);
    }

    /**
     * 将十六进制字符串转换为字节数组
     */
    private byte[] hexStringToByteArray(String hexString) {
        if (hexString == null || hexString.isEmpty()) {
            return new byte[0];
        }

        // 转换为大写并去除空格
        hexString = hexString.toUpperCase().replace(" ", "");

        // 验证是否为有效的十六进制字符串
        if (!hexString.matches("[0-9A-F]+")) {
            return new byte[0];
        }

        // 如果长度为奇数，前面补0
        if (hexString.length() % 2 != 0) {
            hexString = "0" + hexString;
        }

        int len = hexString.length();
        byte[] bytes = new byte[len / 2];

        for (int i = 0; i < len; i += 2) {
            bytes[i / 2] = (byte) ((Character.digit(hexString.charAt(i), 16) << 4)
                    + Character.digit(hexString.charAt(i + 1), 16));
        }

        return bytes;
    }

    private void cleanupConnection() {
        // 清理GATT连接
        if (bluetoothGatt != null) {
            try {
                // Android 12+ 关闭 GATT 也需 BLUETOOTH_CONNECT 权限
                bluetoothGatt.close();
            } catch (SecurityException se) {
                Log.e(TAG, "Missing BLUETOOTH_CONNECT permission on close: " + se.getMessage());
            }
            bluetoothGatt = null;
        }

        // 清理设备引用
        currentDevice = null;

        // 清理定时器
        if (timeoutRunnable != null) {
            mainHandler.removeCallbacks(timeoutRunnable);
            timeoutRunnable = null;
        }

        // 清理特征值状态
        characteristicNotificationEnabled.clear();
        characteristicReading.clear();
        for (Runnable runnable : writeTimeouts.values()) {
            mainHandler.removeCallbacks(runnable);
        }
        writeTimeouts.clear();
        chunkedWriteData.clear();
        mtuConfigured = false;
        negotiatedMtu = 23;
        serviceDiscoveryStarted = false;
    }

    private boolean isPendingConnection(long operationId) {
        return connectionCallback != null && activeConnectionOperationId == operationId;
    }

    private boolean isCurrentGatt(BluetoothGatt gatt, long operationId) {
        return gatt != null && bluetoothGatt == gatt && activeConnectionOperationId == operationId;
    }

    private void discoverServices(long operationId, BluetoothGatt gatt) {
        if (!isCurrentGatt(gatt, operationId) || serviceDiscoveryStarted) {
            return;
        }

        serviceDiscoveryStarted = true;
        try {
            if (!gatt.discoverServices()) {
                completeConnectionFailure(operationId, BridgeError.BLUETOOTH_SERVICE_DISCOVERY_FAILED);
            }
        } catch (SecurityException e) {
            completeConnectionFailure(operationId, BridgeError.PERMISSION_DENIED);
        } catch (Exception e) {
            completeConnectionFailure(operationId, new BridgeError(
                    BridgeError.BLUETOOTH_SERVICE_DISCOVERY_FAILED.getCode(),
                    "启动服务发现失败: " + safeMessage(e)));
        }
    }

    private void completeConnectionSuccess(long operationId,
                                            String address,
                                            List<String> services) {
        if (!isPendingConnection(operationId)) {
            return;
        }

        removeConnectionCallbacks();
        OperationCallback callback = connectionCallback;
        connectionCallback = null;
        connectionTargetDevice = null;
        connectionReady = true;

        JSONObject result = new JSONObject();
        try {
            result.put("address", address);
            JSONArray serviceArray = new JSONArray();
            for (String service : services) {
                serviceArray.put(service);
            }
            result.put("services", serviceArray);
        } catch (JSONException e) {
            failOperation(callback, BridgeError.NATIVE_ERROR);
            return;
        }

        notifyBluetoothConnected(address, services);
        successOperation(callback, result);
    }

    private void completeConnectionFailure(long operationId, BridgeError error) {
        if (!isPendingConnection(operationId)) {
            return;
        }

        removeConnectionCallbacks();
        OperationCallback callback = connectionCallback;
        connectionCallback = null;
        connectionTargetDevice = null;
        connectionReady = false;
        failPendingWrite(BridgeError.BLUETOOTH_NOT_CONNECTED);
        cleanupConnection();
        failOperation(callback, error);
    }

    private void cancelConnectionAttempt(BridgeError error) {
        if (connectionCallback == null) {
            return;
        }

        removeConnectionCallbacks();
        OperationCallback callback = connectionCallback;
        connectionCallback = null;
        connectionTargetDevice = null;
        connectionReady = false;
        cleanupConnection();
        failOperation(callback, error);
    }

    private void removeConnectionCallbacks() {
        if (connectionStartRunnable != null) {
            mainHandler.removeCallbacks(connectionStartRunnable);
            connectionStartRunnable = null;
        }
        if (timeoutRunnable != null) {
            mainHandler.removeCallbacks(timeoutRunnable);
            timeoutRunnable = null;
        }
    }

    private void completeDisconnectSuccess(String address) {
        if (disconnectCallback == null) {
            return;
        }

        OperationCallback callback = disconnectCallback;
        clearDisconnectCallbacks();
        failPendingWrite(BridgeError.BLUETOOTH_NOT_CONNECTED);
        cleanupConnection();
        connectionReady = false;
        notifyBluetoothDisconnected(address);
        successOperation(callback, resultWith("disconnected", true));
    }

    private void completeDisconnectFailure(BridgeError error, String address) {
        if (disconnectCallback == null) {
            return;
        }

        OperationCallback callback = disconnectCallback;
        clearDisconnectCallbacks();
        notifyBluetoothError(error);
        if (callback != null) {
            callback.onFailure(error);
        }
    }

    private void completeDisconnectFailureAfterCleanup(BridgeError error, String address) {
        if (disconnectCallback == null) {
            return;
        }

        OperationCallback callback = disconnectCallback;
        clearDisconnectCallbacks();
        failPendingWrite(BridgeError.BLUETOOTH_NOT_CONNECTED);
        cleanupConnection();
        connectionReady = false;
        notifyBluetoothDisconnected(address);
        failOperation(callback, error);
    }

    private void clearDisconnectCallbacks() {
        if (disconnectTimeoutRunnable != null) {
            mainHandler.removeCallbacks(disconnectTimeoutRunnable);
            disconnectTimeoutRunnable = null;
        }
        disconnectGatt = null;
        disconnectCallback = null;
    }

    private void connectToGattServer(BluetoothDevice device, long operationId) {
        Log.d(TAG, "Starting GATT connection process");

        if (!isBluetoothEnabled()) {
            Log.e(TAG, "Bluetooth is not enabled when trying to connect");
            completeConnectionFailure(operationId, BridgeError.BLUETOOTH_DISABLED);
            return;
        }

        if (!hasBluetoothPermissions()) {
            Log.e(TAG, "Missing Bluetooth permissions");
            completeConnectionFailure(operationId, BridgeError.PERMISSION_DENIED);
            return;
        }

        currentDevice = device;

        Log.d(TAG, "Setting connection timeout to " + CONNECTION_TIMEOUT + "ms");

        timeoutRunnable = () -> {
            Log.e(TAG, "Connection timeout");
            completeConnectionFailure(operationId, new BridgeError(
                    BridgeError.BLUETOOTH_CONNECT_TIMEOUT.getCode(),
                    "连接超时，请确保设备在范围内且未被其他设备连接"));
        };
        mainHandler.postDelayed(timeoutRunnable, CONNECTION_TIMEOUT);

        Log.i(TAG, "Attempting to connect to device: " + device.getAddress());
        try {
            boolean useAutoConnect = true;
            bluetoothGatt = device.connectGatt(context, useAutoConnect, new BluetoothGattCallback() {
                @Override
                public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                    String deviceAddress = device == null ? null : device.getAddress();
                    Log.d(TAG, "Connection state changed: status=" + status + ", state=" + newState);

                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        if (status != BluetoothGatt.GATT_SUCCESS || !isCurrentGatt(gatt, operationId)) {
                            completeConnectionFailure(operationId, new BridgeError(
                                    BridgeError.BLUETOOTH_CONNECTION_FAILED.getCode(),
                                    "GATT连接失败，错误码: " + status));
                            return;
                        }

                        Log.i(TAG, "Connected to GATT server: " + deviceAddress);
                        boolean waitingForMtu = false;
                        try {
                            boolean priorityResult = gatt.requestConnectionPriority(
                                    BluetoothGatt.CONNECTION_PRIORITY_HIGH);
                            Log.d(TAG, "Set high priority result: " + priorityResult);

                            if (!mtuConfigured) {
                                Log.d(TAG, "Requesting MTU size: " + PREFERRED_MTU);
                                boolean mtuResult = gatt.requestMtu(PREFERRED_MTU);
                                if (!mtuResult) {
                                    Log.e(TAG, "Failed to request MTU");
                                } else {
                                    waitingForMtu = true;
                                }
                            }
                        } catch (SecurityException e) {
                            completeConnectionFailure(operationId, BridgeError.PERMISSION_DENIED);
                            return;
                        }

                        // 服务发现成功才算 connect 成功；MTU 回调优先，兜底定时器避免个别设备不回调。
                        long discoveryDelay = waitingForMtu
                                ? SERVICE_DISCOVERY_FALLBACK_DELAY
                                : SERVICE_DISCOVERY_DELAY;
                        mainHandler.postDelayed(() -> discoverServices(operationId, gatt), discoveryDelay);
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        Log.i(TAG, "Disconnected from GATT server. Status: " + status);
                        if (disconnectGatt == gatt && disconnectCallback != null) {
                            if (status == BluetoothGatt.GATT_SUCCESS) {
                                completeDisconnectSuccess(deviceAddress);
                            } else {
                                completeDisconnectFailureAfterCleanup(new BridgeError(
                                        BridgeError.BLUETOOTH_DISCONNECT_FAILED.getCode(),
                                        "断开连接失败，错误码: " + status), deviceAddress);
                            }
                        } else if (connectionCallback != null && activeConnectionOperationId == operationId) {
                            completeConnectionFailure(operationId, new BridgeError(
                                    BridgeError.BLUETOOTH_CONNECTION_FAILED.getCode(),
                                    "连接中断，错误码: " + status));
                        } else if (isCurrentGatt(gatt, operationId)) {
                            failPendingWrite(BridgeError.BLUETOOTH_NOT_CONNECTED);
                            cleanupConnection();
                            connectionReady = false;
                            notifyBluetoothDisconnected(deviceAddress);
                        }
                    } else if (status != BluetoothGatt.GATT_SUCCESS) {
                        completeConnectionFailure(operationId, new BridgeError(
                                BridgeError.BLUETOOTH_CONNECTION_FAILED.getCode(),
                                "GATT连接失败，错误码: " + status));
                    }
                }

                @Override
                public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
                    if (!isCurrentGatt(gatt, operationId)) {
                        return;
                    }

                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        Log.d(TAG, "MTU changed to: " + mtu);
                        mtuConfigured = true;
                        negotiatedMtu = mtu;
                    } else {
                        Log.e(TAG, "MTU change failed with status: " + status);
                    }
                    discoverServices(operationId, gatt);
                }

                @Override
                public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                    if (!isCurrentGatt(gatt, operationId)) {
                        return;
                    }

                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        List<String> services = new ArrayList<>();
                        for (BluetoothGattService service : gatt.getServices()) {
                            String serviceUuid = service.getUuid().toString();
                            services.add(serviceUuid);
                            Log.d(TAG, "发现服务: " + serviceUuid);

                            // 自动开启所有可通知的特征值
                            for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
                                String uuid = characteristic.getUuid().toString();
                                int properties = characteristic.getProperties();
                                Log.d(TAG, "发现特征值: " + uuid + ", 属性: " + properties);

                                // 启用通知
                                if ((properties & BluetoothGattCharacteristic.PROPERTY_NOTIFY) > 0) {
                                    Log.d(TAG, "特征值支持通知: " + uuid);
                                    if (!characteristicNotificationEnabled.containsKey(uuid) || !characteristicNotificationEnabled.get(uuid)) {
                                        // 先设置通知
                                        boolean success = gatt.setCharacteristicNotification(characteristic, true);
                                        if (success) {
                                            Log.d(TAG, "开启通知成功: " + uuid);
                                            characteristicNotificationEnabled.put(uuid, true);

                                            // 写入客户端特征值配置描述符
                                            BluetoothGattDescriptor descriptor = characteristic.getDescriptor(
                                                    UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));
                                            if (descriptor != null) {
                                                Log.d(TAG, "找到CCCD描述符: " + uuid);
                                                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                                                boolean writeResult = gatt.writeDescriptor(descriptor);
                                                if (!writeResult) {
                                                    Log.e(TAG, "写入CCCD描述符失败: " + uuid);
                                                } else {
                                                    Log.d(TAG, "写入CCCD描述符成功: " + uuid);
                                                }
                                            } else {
                                                Log.e(TAG, "未找到CCCD描述符: " + uuid);
                                            }
                                        } else {
                                            Log.e(TAG, "开启通知失败: " + uuid);
                                            characteristicNotificationEnabled.put(uuid, false);
                                        }
                                    } else {
                                        Log.d(TAG, "通知已启用: " + uuid);
                                    }
                                }
                            }
                        }
                        notifyWebView("onServicesDiscovered", String.join(",", services));
                        completeConnectionSuccess(operationId, device.getAddress(), services);
                    } else {
                        Log.e(TAG, "Service discovery failed with status: " + status);
                        completeConnectionFailure(operationId, new BridgeError(
                                BridgeError.BLUETOOTH_SERVICE_DISCOVERY_FAILED.getCode(),
                                "服务发现失败，错误码: " + status));
                    }
                }

                @Override
                public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
                    String uuid = descriptor.getUuid().toString();
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        Log.d(TAG, "描述符写入成功: " + uuid);
                        // 描述符写入成功后，尝试读取特征值
                        BluetoothGattCharacteristic characteristic = descriptor.getCharacteristic();
                        if (characteristic != null) {
                            String charUuid = characteristic.getUuid().toString();
                            Log.d(TAG, "尝试读取特征值: " + charUuid);
                            boolean readResult = gatt.readCharacteristic(characteristic);
                            if (!readResult) {
                                Log.e(TAG, "读取特征值失败: " + charUuid);
                            }
                        }
                    } else {
                        Log.e(TAG, "描述符写入失败，状态码: " + status + ", UUID: " + uuid);
                    }
                }

                @Override
                public void onCharacteristicChanged(BluetoothGatt gatt,
                                                    BluetoothGattCharacteristic characteristic) {
                    String uuid = characteristic.getUuid().toString();
                    byte[] data = characteristic.getValue();

                    // 将字节数组转换为十六进制字符串以便于显示
                    StringBuilder hexStringBuilder = new StringBuilder();
                    for (byte b : data) {
                        hexStringBuilder.append(String.format("%02X", b));
                    }
                    String hexValue = hexStringBuilder.toString();

                    Log.d(TAG, "收到特征值变化: UUID=" + uuid + ", 值=" + hexValue);

                    // 只有在通知开启的情况下才传递数据给WebView
                    if (notificationsEnabled) {
                        // 直接使用十六进制值，不尝试解析为文本
                        notifyWebView("onCharacteristicChanged",
                                String.format("{\"uuid\":\"%s\",\"value\":\"%s\",\"hexValue\":\"%s\"}",
                                        uuid, hexValue, hexValue));
                    } else {
                        Log.d(TAG, "通知已关闭，不处理收到的数据");
                    }
                }

                @Override
                public void onCharacteristicWrite(BluetoothGatt gatt,
                                                  BluetoothGattCharacteristic characteristic,
                                                  int status) {
                    String uuid = characteristic.getUuid().toString();

                    if (isPendingWrite(gatt, uuid)) {
                        long operationId = pendingWriteOperationId;
                        removeWriteTimeout(uuid);

                        if (pendingWriteChunked) {
                            ChunkedWriteData writeData = chunkedWriteData.get(uuid);
                            if (status != BluetoothGatt.GATT_SUCCESS || writeData == null
                                    || writeData.operationId != operationId) {
                                chunkedWriteData.remove(uuid);
                                finishWriteFailure(operationId, new BridgeError(
                                        BridgeError.BLUETOOTH_WRITE_FAILED.getCode(),
                                        "写入失败，错误码: " + status), uuid, "failed");
                                return;
                            }

                            int nextIndex = writeData.currentIndex + 1;
                            if (nextIndex < writeData.chunks.size()) {
                                writeData.currentIndex = nextIndex;
                                mainHandler.postDelayed(() -> sendNextChunk(
                                        characteristic,
                                        writeData.chunks,
                                        nextIndex,
                                        writeData.totalChunks,
                                        uuid,
                                        operationId), 50);
                                return;
                            }

                            Log.d(TAG, "所有片段已发送完成: UUID=" + uuid);
                            chunkedWriteData.remove(uuid);
                            handleWriteCompletion(gatt, characteristic);
                            finishWriteSuccess(operationId, writeResult(uuid, true,
                                    writeData.totalChunks));
                            return;
                        }

                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            handleWriteCompletion(gatt, characteristic);
                            finishWriteSuccess(operationId, writeResult(uuid, false, 0));
                        } else {
                            finishWriteFailure(operationId, new BridgeError(
                                    BridgeError.BLUETOOTH_WRITE_FAILED.getCode(),
                                    "写入失败，错误码: " + status), uuid, "failed");
                        }
                        return;
                    }

                    Log.d(TAG, "忽略没有对应进行中操作的写入回调: UUID=" + uuid);
                }

                // 抽取写入完成后的通用处理逻辑
                private void handleWriteCompletion(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
                    int properties = characteristic.getProperties();
                    String uuid = characteristic.getUuid().toString();

                    // 写入成功后，确保通知已启用
                    if ((properties & BluetoothGattCharacteristic.PROPERTY_NOTIFY) > 0) {
                        if (!characteristicNotificationEnabled.containsKey(uuid) || !characteristicNotificationEnabled.get(uuid)) {
                            Log.d(TAG, "重新启用通知: " + uuid);
                            boolean success = gatt.setCharacteristicNotification(characteristic, true);
                            if (success) {
                                Log.d(TAG, "重新启用通知成功: " + uuid);
                                characteristicNotificationEnabled.put(uuid, true);

                                // 写入CCCD描述符
                                BluetoothGattDescriptor descriptor = characteristic.getDescriptor(
                                        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));
                                if (descriptor != null) {
                                    descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                                    boolean writeResult = gatt.writeDescriptor(descriptor);
                                    if (!writeResult) {
                                        Log.e(TAG, "写入CCCD描述符失败: " + uuid);
                                    }
                                }
                            } else {
                                Log.e(TAG, "重新启用通知失败: " + uuid);
                                characteristicNotificationEnabled.put(uuid, false);
                            }
                        }
                    }
                }

                @Override
                public void onCharacteristicRead(BluetoothGatt gatt,
                                                 BluetoothGattCharacteristic characteristic,
                                                 int status) {
                    String uuid = characteristic.getUuid().toString();
                    characteristicReading.put(uuid, false);  // 重置读取状态

                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        byte[] data = characteristic.getValue();

                        // 将字节数组转换为十六进制字符串以便于显示
                        StringBuilder hexStringBuilder = new StringBuilder();
                        for (byte b : data) {
                            hexStringBuilder.append(String.format("%02X", b));
                        }
                        String hexValue = hexStringBuilder.toString();

                        // 尝试解析为UTF-8文本，如果失败则使用十六进制表示
                        String textValue;
                        try {
                            textValue = new String(data, "UTF-8");
                            // 检查是否为可打印字符，否则使用十六进制
                            if (!isPrintableText(textValue)) {
                                textValue = hexValue;
                            }
                        } catch (Exception e) {
                            textValue = hexValue;
                        }

                        Log.d(TAG, "读取特征值成功: UUID=" + uuid + ", 值=" + hexValue);

                        // 只有在通知开启的情况下才传递数据给WebView
                        if (notificationsEnabled) {
                            notifyWebView("onCharacteristicChanged",
                                    String.format("{\"uuid\":\"%s\",\"value\":\"%s\",\"hexValue\":\"%s\"}",
                                            uuid, textValue, hexValue));
                        } else {
                            Log.d(TAG, "通知已关闭，不处理读取的数据");
                        }
                    } else {
                        Log.e(TAG, "读取特征值失败，状态码: " + status);
                    }
                }
            });
        } catch (SecurityException e) {
            Log.e(TAG, "Security exception when connecting: " + e.getMessage());
            completeConnectionFailure(operationId, BridgeError.PERMISSION_DENIED);
        } catch (Exception e) {
            Log.e(TAG, "Exception when connecting: " + e.getMessage());
            completeConnectionFailure(operationId, new BridgeError(
                    BridgeError.BLUETOOTH_CONNECTION_FAILED.getCode(),
                    "连接蓝牙设备失败: " + safeMessage(e)));
        }
    }

    /**
     * 检查应用是否拥有必要的蓝牙权限
     *
     * @return 是否拥有权限
     */
    private boolean hasBluetoothPermissions() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            // Android 12及以上需要BLUETOOTH_CONNECT权限
            boolean hasConnect = context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
            return hasConnect;
        } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            // Android 6.0-11版本检查传统蓝牙权限
            boolean hasBluetooth = context.checkSelfPermission(android.Manifest.permission.BLUETOOTH)
                    == PackageManager.PERMISSION_GRANTED;
            boolean hasBluetoothAdmin = context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_ADMIN)
                    == PackageManager.PERMISSION_GRANTED;
            return hasBluetooth && hasBluetoothAdmin;
        } else {
            // Android 6.0以下版本，权限在安装时授予
            return true;
        }
    }

    private boolean hasBluetoothScanPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            boolean hasScan = context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED;
            boolean hasLocation = context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
            return hasScan && hasLocation;
        } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            boolean hasBluetooth = context.checkSelfPermission(android.Manifest.permission.BLUETOOTH)
                    == PackageManager.PERMISSION_GRANTED;
            boolean hasBluetoothAdmin = context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_ADMIN)
                    == PackageManager.PERMISSION_GRANTED;
            boolean hasLocation = context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
            return hasBluetooth && hasBluetoothAdmin && hasLocation;
        }
        return true;
    }

    private boolean isLocationServicesEnabled() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) {
            return true;
        }

        try {
            LocationManager locationManager =
                    (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            if (locationManager == null) {
                return false;
            }
            return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Exception e) {
            Log.e(TAG, "Error checking location services: " + e.getMessage());
            return false;
        }
    }

    /**
     * 获取缺失的权限列表
     * @return 缺失的权限数组
     */
    public String getMissingPermissions() {
        List<String> missingPermissions = new ArrayList<>();
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            if (context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                missingPermissions.add("BLUETOOTH_CONNECT");
            }
        } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            if (context.checkSelfPermission(android.Manifest.permission.BLUETOOTH)
                    != PackageManager.PERMISSION_GRANTED) {
                missingPermissions.add("BLUETOOTH");
            }
            if (context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_ADMIN)
                    != PackageManager.PERMISSION_GRANTED) {
                missingPermissions.add("BLUETOOTH_ADMIN");
            }
        }
        
        return "[" + String.join(",", missingPermissions.stream()
                .map(p -> "\"" + p + "\"")
                .toArray(String[]::new)) + "]";
    }

    private boolean isPrintableText(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }

        // 检查字符串是否只包含可打印字符
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 32 || c > 126) {
                // 非ASCII可打印字符
                return false;
            }
        }
        return true;
    }

    private void successOperation(OperationCallback callback, JSONObject result) {
        if (callback != null) {
            JSONObject safeResult = result == null ? new JSONObject() : result;
            mainHandler.post(() -> callback.onSuccess(safeResult));
        }
    }

    private void failOperation(OperationCallback callback, BridgeError error) {
        notifyBluetoothError(error);
        if (callback != null) {
            BridgeError safeError = error == null ? BridgeError.BLUETOOTH_ERROR : error;
            mainHandler.post(() -> callback.onFailure(safeError));
        }
    }

    private JSONObject resultWith(String key, Object value) {
        JSONObject result = new JSONObject();
        try {
            result.put(key, value);
        } catch (JSONException ignored) {
        }
        return result;
    }

    private String safeMessage(Exception exception) {
        if (exception == null || exception.getMessage() == null
                || exception.getMessage().isEmpty()) {
            return exception == null ? "未知错误" : exception.getClass().getSimpleName();
        }
        return exception.getMessage();
    }

    private String bytesToHex(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format("%02X", value));
        }
        return builder.toString();
    }

    private void notifyBluetoothError(BridgeError error) {
        if (error == null) {
            error = BridgeError.BLUETOOTH_ERROR;
        }
        final BridgeError finalError = error;
        mainHandler.post(() -> {
            try {
                JSONObject payload = new JSONObject()
                        .put("code", finalError.getCode())
                        .put("message", finalError.getMessage());
                if (webViewBridge != null) {
                    webViewBridge.emitEvent("bluetooth.error", payload);
                }
            } catch (JSONException e) {
                Log.e(TAG, "Failed to emit bluetooth error event: " + e.getMessage());
            }
        });
    }

    private void notifyBluetoothConnected(String address, List<String> services) {
        mainHandler.post(() -> {
            try {
                JSONObject payload = new JSONObject().put("address", address);
                JSONArray serviceArray = new JSONArray();
                if (services != null) {
                    for (String service : services) {
                        serviceArray.put(service);
                    }
                }
                payload.put("services", serviceArray);
                if (webViewBridge != null) {
                    webViewBridge.emitEvent("bluetooth.connected", payload);
                }
            } catch (JSONException e) {
                Log.e(TAG, "Failed to emit bluetooth connected event: " + e.getMessage());
            }
        });
    }

    private void notifyBluetoothDisconnected(String address) {
        mainHandler.post(() -> {
            try {
                JSONObject payload = new JSONObject();
                if (address != null && !address.isEmpty()) {
                    payload.put("address", address);
                }
                if (webViewBridge != null) {
                    webViewBridge.emitEvent("bluetooth.disconnected", payload);
                }
            } catch (JSONException e) {
                Log.e(TAG, "Failed to emit bluetooth disconnected event: " + e.getMessage());
            }
        });
    }

    private void notifyWebView(String method, String data) {
        mainHandler.post(() -> {
            try {
                JSONObject payload = new JSONObject();
                if ("onBluetoothConnected".equals(method) || "onBluetoothDisconnected".equals(method)) {
                    payload.put("address", data);
                } else if ("onBluetoothError".equals(method)) {
                    payload.put("code", BridgeError.BLUETOOTH_ERROR.getCode());
                    payload.put("message", data);
                } else if ("onBluetoothStateChange".equals(method)) {
                    payload.put("message", data);
                } else if ("onDiscoveryStarted".equals(method) || "onDiscoveryStopped".equals(method)) {
                    payload.put("discovering", "onDiscoveryStarted".equals(method));
                } else if ("onServicesDiscovered".equals(method)) {
                    JSONArray services = new JSONArray();
                    if (data != null && !data.isEmpty()) {
                        for (String service : data.split(",")) {
                            services.put(service);
                        }
                    }
                    payload.put("services", services);
                } else if (data != null && data.trim().startsWith("{")) {
                    payload = new JSONObject(data);
                } else {
                    payload.put("value", data);
                }
                if (webViewBridge != null) {
                    webViewBridge.emitEvent(toEventName(method), payload);
                }
            } catch (JSONException e) {
                Log.e(TAG, "Failed to emit bluetooth event: " + e.getMessage());
            }
        });
    }

    private String toEventName(String method) {
        switch (method) {
            case "onBluetoothConnected":
                return "bluetooth.connected";
            case "onBluetoothDisconnected":
                return "bluetooth.disconnected";
            case "onBluetoothError":
                return "bluetooth.error";
            case "onServicesDiscovered":
                return "bluetooth.servicesDiscovered";
            case "onCharacteristicChanged":
                return "bluetooth.characteristicChanged";
            case "onWriteCompleted":
                return "bluetooth.writeCompleted";
            case "onBluetoothStateChange":
                return "bluetooth.stateChanged";
            case "onBluetoothDeviceFound":
                return "bluetooth.deviceFound";
            case "onDiscoveryStarted":
                return "bluetooth.discoveryStarted";
            case "onDiscoveryStopped":
                return "bluetooth.discoveryStopped";
            default:
                return "bluetooth.event";
        }
    }

    public String getBluetoothStatus() {
        if (!isBluetoothSupported()) {
            return "{\"supported\":false,\"enabled\":false,\"connected\":false}";
        }
        boolean connected = bluetoothGatt != null;
        return String.format(
                "{\"supported\":true,\"enabled\":%b,\"connected\":%b,\"discovering\":%b}",
                isBluetoothEnabled(),
                connected,
                discoveryCallback != null
        );
    }

    public void setNotificationsEnabled(boolean enabled, OperationCallback callback) {
        if (isReleased()) {
            failOperation(callback, BridgeError.BLUETOOTH_RELEASED);
            return;
        }
        this.notificationsEnabled = enabled;
        Log.d(TAG, "蓝牙通知状态已设置为: " + (enabled ? "开启" : "关闭"));
        successOperation(callback, resultWith("enabled", enabled));
    }

    public boolean isNotificationsEnabled() {
        return notificationsEnabled;
    }

    /**
     * 释放所有资源，防止内存泄漏
     * 应在Activity销毁时调用
     */
    public void release() {
        Log.d(TAG, "Releasing BluetoothManager resources");

        if (connectionCallback != null) {
            cancelConnectionAttempt(BridgeError.BLUETOOTH_RELEASED);
        }
        if (disconnectCallback != null) {
            completeDisconnectFailure(BridgeError.BLUETOOTH_RELEASED,
                    currentDevice == null ? null : currentDevice.getAddress());
        }
        failPendingWrite(BridgeError.BLUETOOTH_RELEASED);
        stopDiscovery(null);
        
        // 断开连接
        if (bluetoothGatt != null) {
            try {
                // 断开与关闭在 Android 12+ 需要 BLUETOOTH_CONNECT 权限
                bluetoothGatt.disconnect();
                bluetoothGatt.close();
            } catch (SecurityException se) {
                Log.e(TAG, "Missing BLUETOOTH_CONNECT permission when releasing: " + se.getMessage());
            } catch (Exception e) {
                Log.e(TAG, "Error closing GATT connection: " + e.getMessage());
            }
            bluetoothGatt = null;
        }
        
        // 清理所有回调
        if (mainHandler != null) {
            if (timeoutRunnable != null) {
                mainHandler.removeCallbacks(timeoutRunnable);
                timeoutRunnable = null;
            }
            if (connectionStartRunnable != null) {
                mainHandler.removeCallbacks(connectionStartRunnable);
                connectionStartRunnable = null;
            }
            if (disconnectTimeoutRunnable != null) {
                mainHandler.removeCallbacks(disconnectTimeoutRunnable);
                disconnectTimeoutRunnable = null;
            }
            for (Runnable runnable : writeTimeouts.values()) {
                mainHandler.removeCallbacks(runnable);
            }
        }
        
        // 清理状态
        currentDevice = null;
        connectionTargetDevice = null;
        connectionCallback = null;
        disconnectCallback = null;
        disconnectGatt = null;
        pendingWriteCallback = null;
        pendingWriteOperationId = -1;
        pendingWriteUuid = null;
        pendingWriteGatt = null;
        characteristicNotificationEnabled.clear();
        characteristicReading.clear();
        chunkedWriteData.clear();
        
        // 清理引用
        context = null;
        webViewBridge = null;
        bluetoothAdapter = null;
    }

    /**
     * 检查资源是否已释放
     */
    public boolean isReleased() {
        return context == null || webViewBridge == null;
    }
}
