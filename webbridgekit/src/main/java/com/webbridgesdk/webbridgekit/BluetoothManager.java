package com.webbridgesdk.webbridgekit;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.Map;
import java.util.HashMap;

public class BluetoothManager {
    private static final String TAG = "BluetoothManager";
    private static final long CONNECTION_TIMEOUT = 2000; // 2秒超时
    private static final int RETRY_DELAY = 1000; // 重试延迟1秒
    private static final int MAX_RETRIES = 2; // 最大重试次数
    private static final int PREFERRED_MTU = 247; // 首选MTU大小
    private Context context;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothGatt bluetoothGatt;
    private BluetoothDevice currentDevice;
    private Handler mainHandler;
    private WebViewBridge webViewBridge;
    private Runnable timeoutRunnable;
    private int retryCount = 0;
    private String lastMacAddress = null;
    private Map<String, Boolean> characteristicNotificationEnabled = new HashMap<>();
    private Map<String, Boolean> characteristicReading = new HashMap<>();
    private boolean mtuConfigured = false;
    private boolean notificationsEnabled = true; // 添加通知控制开关，默认开启
    private Map<String, ChunkedWriteData> chunkedWriteData = new HashMap<>();

    public BluetoothManager(Context context, WebViewBridge webViewBridge) {
        this.context = context;
        this.webViewBridge = webViewBridge;
        this.bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    @JavascriptInterface
    public boolean isBluetoothSupported() {
        if (isReleased()) {
            Log.w(TAG, "BluetoothManager has been released");
            return false;
        }
        return bluetoothAdapter != null;
    }

    @JavascriptInterface
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

    @JavascriptInterface
    public String getPairedDevices() {
        if (isReleased()) {
            Log.w(TAG, "BluetoothManager has been released");
            return "[]";
        }
        
        if (!isBluetoothEnabled()) {
            return "[]";
        }

        try {
            List<String> deviceList = new ArrayList<>();
            Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();

            for (BluetoothDevice device : pairedDevices) {
                String deviceInfo = String.format(
                        "{\"name\":\"%s\",\"address\":\"%s\"}",
                        device.getName() != null ? device.getName() : "Unknown",
                        device.getAddress()
                );
                deviceList.add(deviceInfo);
            }

            return "[" + String.join(",", deviceList) + "]";
        } catch (SecurityException e) {
            Log.e(TAG, "Security exception getting paired devices: " + e.getMessage());
            return "[]";
        } catch (Exception e) {
            Log.e(TAG, "Error getting paired devices: " + e.getMessage());
            return "[]";
        }
    }

    @JavascriptInterface
    public void connectToDevice(String macAddress) {
        if (!isBluetoothEnabled()) {
            notifyWebView("onBluetoothError", "蓝牙未启用");
            return;
        }

        // 确保蓝牙适配器处于活动状态
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            Log.e(TAG, "Bluetooth adapter not ready");
            // 延迟500ms后重试
            mainHandler.postDelayed(() -> connectToDevice(macAddress), 500);
            return;
        }

        // 添加: 首次启动时额外检查和延迟以确保蓝牙初始化完成
        if (retryCount == 0) {
            Log.i(TAG, "首次连接尝试，添加额外初始化延迟");
            mainHandler.postDelayed(() -> {
                try {
                    // 如果是全新的连接请求，重置重试计数
                    if (!macAddress.equals(lastMacAddress)) {
                        retryCount = 0;
                        lastMacAddress = macAddress;
                    }

                    // 清理之前的连接
                    cleanupConnection();

                    // 开始新的连接
                    BluetoothDevice device = bluetoothAdapter.getRemoteDevice(macAddress);

                    // 对于经典蓝牙设备，建议先配对
                    int bondState = device.getBondState();
                    if (bondState == BluetoothDevice.BOND_NONE) {
                        Log.i(TAG, "设备未配对，尝试直接连接（可能是BLE设备）");
                    }

                    // 直接进行连接尝试
                    connectToGattServer(device);
                } catch (IllegalArgumentException e) {
                    notifyWebView("onBluetoothError", "无效的MAC地址");
                } catch (SecurityException e) {
                    notifyWebView("onBluetoothError", "缺少必要的蓝牙权限");
                }
            }, 800); // 首次连接增加额外延迟
            return;
        }

        try {
            // 如果是全新的连接请求，重置重试计数
            if (!macAddress.equals(lastMacAddress)) {
                retryCount = 0;
                lastMacAddress = macAddress;
            }

            // 清理之前的连接
            cleanupConnection();

            // 开始新的连接
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(macAddress);

            // 对于经典蓝牙设备，建议先配对
            int bondState = device.getBondState();
            if (bondState == BluetoothDevice.BOND_NONE) {
                Log.i(TAG, "设备未配对，尝试直接连接（可能是BLE设备）");
            }

            // 直接进行连接尝试
            connectToGattServer(device);
        } catch (IllegalArgumentException e) {
            notifyWebView("onBluetoothError", "无效的MAC地址");
        } catch (SecurityException e) {
            notifyWebView("onBluetoothError", "缺少必要的蓝牙权限");
        }
    }

    @JavascriptInterface
    public void disconnect() {
        if (bluetoothGatt != null) {
            notifyWebView("onBluetoothStateChange", "正在断开连接...");

            // 直接执行断开，状态变化会通过onConnectionStateChange回调通知
            bluetoothGatt.disconnect();

            // 设置5秒超时，如果没收到断开回调就强制断开
            mainHandler.postDelayed(() -> {
                if (bluetoothGatt != null) {
                    Log.w(TAG, "Disconnect timeout, forcing close");
                    bluetoothGatt.close();
                    bluetoothGatt = null;
                    currentDevice = null;
                    notifyWebView("onBluetoothDisconnected", "已断开连接");
                }
            }, 2000);
        } else {
            notifyWebView("onBluetoothDisconnected", "已断开连接");
        }

        // 移除任何潜在的超时回调
        if (timeoutRunnable != null) {
            mainHandler.removeCallbacks(timeoutRunnable);
            timeoutRunnable = null;
        }
    }

    @JavascriptInterface
    public void writeData(String serviceUUID, String characteristicUUID, String data) {
        if (bluetoothGatt == null) {
            notifyWebView("onBluetoothError", "未连接到设备");
            return;
        }

        try {
            BluetoothGattService service = bluetoothGatt.getService(UUID.fromString(serviceUUID));
            if (service == null) {
                notifyWebView("onBluetoothError", "未找到指定服务");
                return;
            }

            BluetoothGattCharacteristic characteristic =
                    service.getCharacteristic(UUID.fromString(characteristicUUID));
            if (characteristic == null) {
                notifyWebView("onBluetoothError", "未找到指定特征值");
                return;
            }

            // 检查特征值是否支持写入
            int properties = characteristic.getProperties();
            if ((properties & BluetoothGattCharacteristic.PROPERTY_WRITE) == 0 &&
                    (properties & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) == 0) {
                notifyWebView("onBluetoothError", "该特征值不支持写入操作");
                return;
            }

            // 检查数据大小是否超过MTU限制
            byte[] dataBytes = data.getBytes();
            if (dataBytes.length > 20) { // 默认MTU通常是20字节
                // 改为调用分片方法，而不是返回错误
                writeRawDataChunked(service, characteristic, dataBytes);
                return;
            }

            // 设置写入超时
            final Runnable writeTimeoutRunnable = () -> {
                notifyWebView("onBluetoothError", "写入操作超时");
            };
            mainHandler.postDelayed(writeTimeoutRunnable, 5000); // 5秒超时

            // 通知开始写入
            notifyWebView("onBluetoothStateChange", "正在发送数据...");

            // 设置数据并写入
            characteristic.setValue(dataBytes);
            boolean writeResult = bluetoothGatt.writeCharacteristic(characteristic);

            if (!writeResult) {
                mainHandler.removeCallbacks(writeTimeoutRunnable);
                notifyWebView("onBluetoothError", "写入操作失败");
                return;
            }

            // 写入成功，等待onCharacteristicWrite回调
            // 超时处理在onCharacteristicWrite中移除
        } catch (IllegalArgumentException e) {
            notifyWebView("onBluetoothError", "无效的UUID格式");
        } catch (SecurityException e) {
            notifyWebView("onBluetoothError", "缺少必要的蓝牙权限");
        }
    }

    /**
     * 分片发送原始字节数据
     *
     * @param service        蓝牙服务
     * @param characteristic 特征值
     * @param data           要发送的完整数据
     */
    private void writeRawDataChunked(BluetoothGattService service,
                                     BluetoothGattCharacteristic characteristic,
                                     byte[] data) {
        writeRawHexDataChunked(service, characteristic, data);
    }

    /**
     * 将十六进制字符串转换为字节数组并发送到蓝牙设备
     *
     * @param serviceUUID        服务UUID
     * @param characteristicUUID 特征值UUID
     * @param hexString          十六进制字符串，如"7B864814071027923000280033BD7D"
     */
    @JavascriptInterface
    public void writeRawHexData(String serviceUUID, String characteristicUUID, String hexString) {
        if (bluetoothGatt == null) {
            notifyWebView("onBluetoothError", "未连接到设备");
            return;
        }

        try {
            BluetoothGattService service = bluetoothGatt.getService(UUID.fromString(serviceUUID));
            if (service == null) {
                notifyWebView("onBluetoothError", "未找到指定服务");
                return;
            }

            BluetoothGattCharacteristic characteristic =
                    service.getCharacteristic(UUID.fromString(characteristicUUID));
            if (characteristic == null) {
                notifyWebView("onBluetoothError", "未找到指定特征值");
                return;
            }

            // 检查特征值是否支持写入
            int properties = characteristic.getProperties();
            if ((properties & BluetoothGattCharacteristic.PROPERTY_WRITE) == 0 &&
                    (properties & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) == 0) {
                notifyWebView("onBluetoothError", "该特征值不支持写入操作");
                return;
            }

            // 将十六进制字符串转换为字节数组
            byte[] dataBytes = hexStringToByteArray(hexString);
            if (dataBytes.length == 0) {
                notifyWebView("onBluetoothError", "无效的十六进制字符串");
                return;
            }

            // 检查数据大小是否超过MTU限制
            if (dataBytes.length > 20) { // 默认MTU通常是20字节
                // 改为调用分片方法，而不是返回错误
                writeRawHexDataChunked(service, characteristic, dataBytes);
                return;
            }

            // 设置写入超时
            final Runnable writeTimeoutRunnable = () -> {
                notifyWebView("onBluetoothError", "写入操作超时");
            };
            mainHandler.postDelayed(writeTimeoutRunnable, 5000); // 5秒超时

            // 通知开始写入
            notifyWebView("onBluetoothStateChange", "正在发送十六进制数据...");

            // 设置数据并写入
            characteristic.setValue(dataBytes);
            boolean writeResult = bluetoothGatt.writeCharacteristic(characteristic);

            if (!writeResult) {
                mainHandler.removeCallbacks(writeTimeoutRunnable);
                notifyWebView("onBluetoothError", "写入操作失败");
                return;
            }

            // 写入成功，等待onCharacteristicWrite回调
        } catch (IllegalArgumentException e) {
            notifyWebView("onBluetoothError", "无效的参数: " + e.getMessage());
        } catch (SecurityException e) {
            notifyWebView("onBluetoothError", "缺少必要的蓝牙权限");
        } catch (Exception e) {
            notifyWebView("onBluetoothError", "发送数据出错: " + e.getMessage());
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
                                        byte[] data) {
        final int CHUNK_SIZE = 20; // 每片的最大字节数
        final int totalChunks = (int) Math.ceil((double) data.length / CHUNK_SIZE);

        Log.d(TAG, "数据大小: " + data.length + "字节，将分为" + totalChunks + "片发送");
        notifyWebView("onBluetoothStateChange",
                String.format("数据大小: %d字节，将分为%d片发送", data.length, totalChunks));

        // 创建队列来存储所有数据片段
        ArrayList<byte[]> chunks = new ArrayList<>();
        for (int i = 0; i < data.length; i += CHUNK_SIZE) {
            int end = Math.min(i + CHUNK_SIZE, data.length);
            byte[] chunk = new byte[end - i];
            System.arraycopy(data, i, chunk, 0, end - i);
            chunks.add(chunk);
        }

        // 使用递归函数发送每一片
        sendNextChunk(characteristic, chunks, 0, totalChunks);
    }

    /**
     * 递归发送下一个数据片段
     *
     * @param characteristic 特征值
     * @param chunks         所有数据片段的列表
     * @param index          当前要发送的片段索引
     * @param totalChunks    总片段数
     */
    private void sendNextChunk(BluetoothGattCharacteristic characteristic,
                               ArrayList<byte[]> chunks, int index, int totalChunks) {
        sendNextChunk(characteristic, chunks, index, totalChunks, characteristic.getUuid().toString());
    }

    /**
     * 递归发送下一个数据片段（带特征值UUID参数）
     *
     * @param characteristic     特征值
     * @param chunks             所有数据片段的列表
     * @param index              当前要发送的片段索引
     * @param totalChunks        总片段数
     * @param characteristicUUID 特征值UUID
     */
    private void sendNextChunk(BluetoothGattCharacteristic characteristic,
                               ArrayList<byte[]> chunks, int index, int totalChunks,
                               String characteristicUUID) {
        if (index >= chunks.size() || bluetoothGatt == null) {
            Log.d(TAG, "分片发送完成或连接已断开");
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
            chunkedWriteData.put(characteristicUUID, writeData);
        }

        // 设置写入超时处理
        final Runnable writeTimeoutRunnable = new Runnable() {
            @Override
            public void run() {
                Log.e(TAG, "片段" + currentChunk + "写入超时");
                notifyWebView("onBluetoothError", "数据片段" + currentChunk + "写入超时");
                chunkedWriteData.remove(characteristicUUID);
            }
        };

        // 设置5秒超时
        mainHandler.postDelayed(writeTimeoutRunnable, 5000);

        // 设置数据并写入
        characteristic.setValue(chunk);
        boolean writeResult = bluetoothGatt.writeCharacteristic(characteristic);

        if (!writeResult) {
            mainHandler.removeCallbacks(writeTimeoutRunnable);
            Log.e(TAG, "片段" + currentChunk + "写入失败");
            notifyWebView("onBluetoothError", "数据片段" + currentChunk + "写入失败");
            chunkedWriteData.remove(characteristicUUID);
            return;
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
            bluetoothGatt.close();
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
    }

    private void connectToGattServer(BluetoothDevice device) {
        Log.d(TAG, "Starting GATT connection process");

        // 确保开始新连接前所有状态都是清理的
        cleanupConnection();

        // 检查蓝牙状态
        if (!isBluetoothEnabled()) {
            Log.e(TAG, "Bluetooth is not enabled when trying to connect");
            notifyWebView("onBluetoothError", "蓝牙未启用，请先启用蓝牙");
            return;
        }

        // 添加权限检查
        if (!hasBluetoothPermissions()) {
            Log.e(TAG, "Missing Bluetooth permissions");
            notifyWebView("onBluetoothError", "缺少必要的蓝牙权限");
            return;
        }

        currentDevice = device;

        // 设置连接超时 - 首次连接时使用更长的超时时间
        long timeoutTime = retryCount == 0 ? CONNECTION_TIMEOUT * 2 : CONNECTION_TIMEOUT;
        Log.d(TAG, "Setting connection timeout to " + timeoutTime + "ms");

        timeoutRunnable = () -> {
            Log.e(TAG, "Connection timeout");
            notifyWebView("onBluetoothError", "连接超时，请确保设备在范围内且未被其他设备连接");
            disconnect();
        };
        mainHandler.postDelayed(timeoutRunnable, timeoutTime);

        Log.i(TAG, "Attempting to connect to device: " + device.getAddress());
        try {
            // 使用autoConnect=true对首次连接可能有所帮助
            boolean useAutoConnect = retryCount == 0;
            bluetoothGatt = device.connectGatt(context, useAutoConnect, new BluetoothGattCallback() {
                @Override
                public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                    // 移除重试逻辑，即使状态码不是GATT_SUCCESS也继续处理
                    // 这样可以避免设备突然断电或关闭蓝牙时导致的崩溃
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        Log.e(TAG, "Connection state change error: " + status);
                        // 记录状态码但不再尝试重连
                    }

                    // 无论之前的状态如何，都重置重试计数并清除超时
                    retryCount = 0;

                    // 清除连接超时定时器
                    if (timeoutRunnable != null) {
                        mainHandler.removeCallbacks(timeoutRunnable);
                        timeoutRunnable = null;
                    }

                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        Log.i(TAG, "Connected to GATT server: " + gatt.getDevice().getAddress());

                        // 设置更高的连接优先级以提高传输速度和稳定性
                        boolean priorityResult = gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
                        Log.d(TAG, "Set high priority result: " + priorityResult);

                        // 配置MTU大小
                        if (!mtuConfigured) {
                            Log.d(TAG, "Requesting MTU size: " + PREFERRED_MTU);
                            boolean mtuResult = gatt.requestMtu(PREFERRED_MTU);
                            if (!mtuResult) {
                                Log.e(TAG, "Failed to request MTU");
                            }
                        }

                        notifyWebView("onBluetoothConnected", device.getAddress());

                        // 延迟发现服务，给设备一些时间稳定连接
                        mainHandler.postDelayed(() -> {
                            if (bluetoothGatt != null) {
                                bluetoothGatt.discoverServices();
                            }
                        }, 500); // 增加延迟到500ms
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        Log.i(TAG, "Disconnected from GATT server. Status: " + status);
                        String deviceAddress = device != null ? device.getAddress() : "未知设备";
                        Log.d(TAG, "Device " + deviceAddress + " disconnected, retry count: " + retryCount);
                        cleanupConnection();
                        notifyWebView("onBluetoothDisconnected", deviceAddress);
                    } else {
                        Log.d(TAG, "Connection state changed to: " + newState);
                    }
                }

                @Override
                public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        Log.d(TAG, "MTU changed to: " + mtu);
                        mtuConfigured = true;
                        // MTU配置成功后，开始发现服务
                        if (bluetoothGatt != null) {
                            bluetoothGatt.discoverServices();
                        }
                    } else {
                        Log.e(TAG, "MTU change failed with status: " + status);
                    }
                }

                @Override
                public void onServicesDiscovered(BluetoothGatt gatt, int status) {
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
                    } else {
                        Log.e(TAG, "Service discovery failed with status: " + status);
                        notifyWebView("onBluetoothError", "服务发现失败");
                        disconnect();
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
                    // 移除写入超时
                    mainHandler.removeCallbacksAndMessages(null);

                    String uuid = characteristic.getUuid().toString();
                    String result = status == BluetoothGatt.GATT_SUCCESS ? "success" : "failed";
                    Log.d(TAG, "写入特征值完成: UUID=" + uuid + ", 状态=" + result);

                    // 处理分片发送回调，检查是否有待发送的数据片段
                    if (status == BluetoothGatt.GATT_SUCCESS && chunkedWriteData.containsKey(uuid)) {
                        ChunkedWriteData writeData = chunkedWriteData.get(uuid);
                        int nextIndex = writeData.currentIndex + 1;

                        // 如果还有下一片段，延迟一小段时间后发送
                        if (nextIndex < writeData.chunks.size()) {
                            writeData.currentIndex = nextIndex;

                            // 延迟一定时间后发送下一片，避免设备处理不过来
                            mainHandler.postDelayed(() -> {
                                sendNextChunk(characteristic, writeData.chunks,
                                        nextIndex, writeData.totalChunks, uuid);
                            }, 50); // 50ms延迟

                            return; // 不通知完成，因为还有更多片段
                        } else {
                            // 所有片段已发送完成
                            Log.d(TAG, "所有数据片段发送完成: UUID=" + uuid);
                            notifyWebView("onWriteCompleted",
                                    String.format("{\"uuid\":\"%s\",\"status\":\"success\",\"chunked\":true,\"totalChunks\":%d}",
                                            uuid, writeData.totalChunks));

                            // 清理分片数据
                            chunkedWriteData.remove(uuid);

                            // 写入完成后处理通知启用等操作
                            handleWriteCompletion(gatt, characteristic);
                            return;
                        }
                    }

                    // 非分片写入的常规处理
                    notifyWebView("onWriteCompleted",
                            String.format("{\"uuid\":\"%s\",\"status\":\"%s\"}",
                                    uuid, result));

                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        notifyWebView("onBluetoothError", "写入失败，错误码: " + status);
                        return;
                    }

                    // 写入成功后处理通知启用等操作
                    handleWriteCompletion(gatt, characteristic);
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
            notifyWebView("onBluetoothError", "缺少必要的蓝牙权限");
            mainHandler.removeCallbacks(timeoutRunnable);
            timeoutRunnable = null;
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
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
            boolean hasScan = context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
            
            // 对于连接操作，主要需要CONNECT权限
            return hasConnect;
        } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            // Android 6.0-11版本检查传统蓝牙权限
            boolean hasBluetooth = context.checkSelfPermission(android.Manifest.permission.BLUETOOTH)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
            boolean hasBluetoothAdmin = context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_ADMIN)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
            boolean hasLocation = context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
            
            return hasBluetooth && hasBluetoothAdmin && hasLocation;
        } else {
            // Android 6.0以下版本，权限在安装时授予
            return true;
        }
    }

    /**
     * 获取缺失的权限列表
     * @return 缺失的权限数组
     */
    @JavascriptInterface
    public String getMissingPermissions() {
        List<String> missingPermissions = new ArrayList<>();
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            if (context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                missingPermissions.add("BLUETOOTH_CONNECT");
            }
            if (context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                missingPermissions.add("BLUETOOTH_SCAN");
            }
        } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            if (context.checkSelfPermission(android.Manifest.permission.BLUETOOTH)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                missingPermissions.add("BLUETOOTH");
            }
            if (context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_ADMIN)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                missingPermissions.add("BLUETOOTH_ADMIN");
            }
            if (context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                missingPermissions.add("ACCESS_FINE_LOCATION");
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

    private void notifyWebView(String method, String data) {
        mainHandler.post(() -> {
            String js = String.format("javascript:window.%s('%s')", method, data);
            webViewBridge.evaluateJavascript(js);
        });
    }

    @JavascriptInterface
    public String getBluetoothStatus() {
        if (!isBluetoothSupported()) {
            return "{\"supported\":false,\"enabled\":false,\"connected\":false}";
        }
        boolean connected = bluetoothGatt != null;
        return String.format(
                "{\"supported\":true,\"enabled\":%b,\"connected\":%b}",
                isBluetoothEnabled(),
                connected
        );
    }

    @JavascriptInterface
    public void setNotificationsEnabled(boolean enabled) {
        this.notificationsEnabled = enabled;
        Log.d(TAG, "蓝牙通知状态已设置为: " + (enabled ? "开启" : "关闭"));
    }

    @JavascriptInterface
    public boolean isNotificationsEnabled() {
        return notificationsEnabled;
    }

    /**
     * 释放所有资源，防止内存泄漏
     * 应在Activity销毁时调用
     */
    public void release() {
        Log.d(TAG, "Releasing BluetoothManager resources");
        
        // 断开连接
        if (bluetoothGatt != null) {
            try {
                bluetoothGatt.disconnect();
                bluetoothGatt.close();
            } catch (Exception e) {
                Log.e(TAG, "Error closing GATT connection: " + e.getMessage());
            }
            bluetoothGatt = null;
        }
        
        // 清理所有回调
        if (mainHandler != null) {
            mainHandler.removeCallbacksAndMessages(null);
        }
        
        // 清理状态
        currentDevice = null;
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