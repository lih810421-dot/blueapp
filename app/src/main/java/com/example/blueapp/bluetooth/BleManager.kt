package com.example.blueapp.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * BLE 设备信息
 */
data class BleDeviceInfo(
    val name: String,
    val address: String,
    val rssi: Int,
    val device: BluetoothDevice
)

/**
 * 连接状态
 */
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCOVERING_SERVICES,
    READY
}

/**
 * BLE 管理器
 * 实现低功耗蓝牙设备的扫描、连接、通信等功能
 */
@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {
    
    companion object {
        private const val TAG = "BleManager"
        
        // 标准 BLE UUIDs
        private val CLIENT_CHARACTERISTIC_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val bluetoothManager: BluetoothManager = 
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val bluetoothLeScanner: BluetoothLeScanner? = bluetoothAdapter?.bluetoothLeScanner

    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    
    private val scope = CoroutineScope(Dispatchers.IO)
    private var pollingJob: Job? = null
    private val receivedDataBuffer = mutableListOf<Byte>()

    // 状态流
    private val _scannedDevices = MutableStateFlow<List<BleDeviceInfo>>(emptyList())
    val scannedDevices: StateFlow<List<BleDeviceInfo>> = _scannedDevices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _sensorData = MutableStateFlow<SensorData?>(null)
    val sensorData: StateFlow<SensorData?> = _sensorData.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /**
     * 检查蓝牙是否开启
     */
    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    /**
     * 检查是否有必要的权限
     */
    fun hasRequiredPermissions(): Boolean {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            listOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
        
        return permissions.all { 
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED 
        }
    }

    /**
     * 开始扫描 BLE 设备
     */
    fun startScan() {
        if (!hasRequiredPermissions()) {
            _errorMessage.value = "缺少蓝牙权限"
            return
        }

        if (!isBluetoothEnabled()) {
            _errorMessage.value = "请开启蓝牙"
            return
        }

        if (_isScanning.value) return

        _scannedDevices.value = emptyList()
        _isScanning.value = true
        _errorMessage.value = null

        try {
            bluetoothLeScanner?.startScan(scanCallback)
            Log.d(TAG, "开始扫描 BLE 设备")
        } catch (e: Exception) {
            Log.e(TAG, "启动扫描失败", e)
            _errorMessage.value = "启动扫描失败: ${e.message}"
            _isScanning.value = false
        }
    }

    /**
     * 停止扫描
     */
    fun stopScan() {
        if (!_isScanning.value) return

        try {
            bluetoothLeScanner?.stopScan(scanCallback)
            _isScanning.value = false
            Log.d(TAG, "停止扫描")
        } catch (e: Exception) {
            Log.e(TAG, "停止扫描失败", e)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val deviceName = device.name ?: "未知设备"
            val deviceAddress = device.address
            val rssi = result.rssi

            // 更新设备列表
            val currentList = _scannedDevices.value.toMutableList()
            val existingIndex = currentList.indexOfFirst { it.address == deviceAddress }
            
            val deviceInfo = BleDeviceInfo(deviceName, deviceAddress, rssi, device)
            
            if (existingIndex >= 0) {
                currentList[existingIndex] = deviceInfo
            } else {
                currentList.add(deviceInfo)
            }
            
            _scannedDevices.value = currentList
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "扫描失败，错误码: $errorCode")
            _errorMessage.value = "扫描失败，错误码: $errorCode"
            _isScanning.value = false
        }
    }

    /**
     * 连接到 BLE 设备
     */
    fun connect(device: BluetoothDevice) {
        if (!hasRequiredPermissions()) {
            _errorMessage.value = "缺少蓝牙权限"
            return
        }

        stopScan()
        disconnect()

        _connectionState.value = ConnectionState.CONNECTING
        _errorMessage.value = null

        try {
            bluetoothGatt = device.connectGatt(context, false, gattCallback)
            Log.d(TAG, "正在连接设备: ${device.name} (${device.address})")
        } catch (e: Exception) {
            Log.e(TAG, "连接失败", e)
            _errorMessage.value = "连接失败: ${e.message}"
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    /**
     * 断开连接
     */
    fun disconnect() {
        stopPolling()
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
            bluetoothGatt = null
            writeCharacteristic = null
            notifyCharacteristic = null
            _connectionState.value = ConnectionState.DISCONNECTED
            _sensorData.value = null
            receivedDataBuffer.clear()
            Log.d(TAG, "已断开连接")
        } catch (e: Exception) {
            Log.e(TAG, "断开连接失败", e)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "已连接到 GATT 服务器")
                    _connectionState.value = ConnectionState.CONNECTED
                    _connectionState.value = ConnectionState.DISCOVERING_SERVICES
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "从 GATT 服务器断开")
                    _connectionState.value = ConnectionState.DISCONNECTED
                    _errorMessage.value = "设备已断开连接"
                    stopPolling()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "服务发现成功")
                analyzeCharacteristics(gatt)
            } else {
                Log.e(TAG, "服务发现失败: $status")
                _errorMessage.value = "服务发现失败"
                _connectionState.value = ConnectionState.CONNECTED
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "读取特征值成功: ${value.toHexString()}")
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "写入特征值成功")
            } else {
                Log.e(TAG, "写入特征值失败: $status")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            Log.d(TAG, "【RX】${value.toHexString()}")
            handleReceivedData(value)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "描述符写入成功，通知已启用")
            } else {
                Log.e(TAG, "描述符写入失败: $status")
            }
        }
    }

    /**
     * 分析并配置特征值
     */
    private fun analyzeCharacteristics(gatt: BluetoothGatt) {
        var foundWriteChar = false
        var foundNotifyChar = false

        for (service in gatt.services) {
            Log.d(TAG, "服务: ${service.uuid}")
            
            for (characteristic in service.characteristics) {
                val properties = characteristic.properties
                Log.d(TAG, "  特征值: ${characteristic.uuid}, 属性: $properties")

                // 查找可写特征值
                if (!foundWriteChar && (properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 ||
                    properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)) {
                    writeCharacteristic = characteristic
                    foundWriteChar = true
                    Log.d(TAG, "  ✓ 可写特征值")
                }

                // 查找可通知特征值
                if (!foundNotifyChar && (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 ||
                    properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0)) {
                    notifyCharacteristic = characteristic
                    foundNotifyChar = true
                    enableNotification(gatt, characteristic)
                    Log.d(TAG, "  ✓ 可通知特征值")
                }
            }
        }

        if (foundWriteChar && foundNotifyChar) {
            _connectionState.value = ConnectionState.READY
            _errorMessage.value = null
            startPolling()
            Log.d(TAG, "设备已就绪，开始数据轮询")
        } else {
            _errorMessage.value = "未找到合适的通信特征值"
            _connectionState.value = ConnectionState.CONNECTED
            Log.e(TAG, "未找到合适的特征值 (写: $foundWriteChar, 通知: $foundNotifyChar)")
        }
    }

    /**
     * 启用通知
     */
    private fun enableNotification(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(characteristic, true)
        
        val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
        if (descriptor != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }
        }
    }

    /**
     * 写入数据
     */
    private fun writeData(data: ByteArray): Boolean {
        val char = writeCharacteristic
        val gatt = bluetoothGatt

        if (char == null || gatt == null) {
            Log.e(TAG, "写特征值或GATT为空")
            return false
        }

        return try {
            Log.d(TAG, "【TX】${data.toHexString()}")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(char, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                char.value = data
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(char)
            } == BluetoothGatt.GATT_SUCCESS
        } catch (e: Exception) {
            Log.e(TAG, "写入数据失败", e)
            false
        }
    }

    /**
     * 启动周期性数据轮询（300ms）
     */
    private fun startPolling() {
        stopPolling()
        pollingJob = scope.launch {
            while (isActive && _connectionState.value == ConnectionState.READY) {
                writeData(BleProtocol.CMD_GET_DATA)
                delay(BleProtocol.POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * 停止数据轮询
     */
    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    /**
     * 处理接收到的数据
     */
    private fun handleReceivedData(data: ByteArray) {
        // 将接收到的数据加入缓冲区
        receivedDataBuffer.addAll(data.toList())

        // 尝试解析完整的数据包
        // 根据协议，数据包以 7E 7F 30 开头
        val startIndex = receivedDataBuffer.indexOfFirst { 
            receivedDataBuffer.size >= 3 && 
            it == 0x7E.toByte() && 
            receivedDataBuffer.getOrNull(receivedDataBuffer.indexOf(it) + 1) == 0x7F.toByte() &&
            receivedDataBuffer.getOrNull(receivedDataBuffer.indexOf(it) + 2) == 0x30.toByte()
        }

        if (startIndex >= 0) {
            // 找到数据包头，检查是否有足够的数据
            val packetData = receivedDataBuffer.drop(startIndex).take(100).toByteArray()
            
            val sensorData = BleProtocol.parseSensorData(packetData)
            if (sensorData != null) {
                _sensorData.value = sensorData
                // 清除已解析的数据
                receivedDataBuffer.clear()
            } else if (receivedDataBuffer.size > 200) {
                // 缓冲区太大但无法解析，清空
                receivedDataBuffer.clear()
            }
        } else if (receivedDataBuffer.size > 200) {
            // 没有找到有效的包头，且缓冲区过大，清空
            receivedDataBuffer.clear()
        }
    }

    /**
     * 发送模式切换指令
     */
    fun sendModeCommand(mode: WorkMode): Boolean {
        if (_connectionState.value != ConnectionState.READY) {
            _errorMessage.value = "设备未就绪"
            return false
        }
        
        Log.d(TAG, "切换模式到: ${mode.displayName}")
        return writeData(mode.command)
    }

    /**
     * 发送转速校准指令
     */
    fun sendRpmCalibration(rpm: Int): Boolean {
        if (_connectionState.value != ConnectionState.READY) {
            _errorMessage.value = "设备未就绪"
            return false
        }
        
        val command = BleProtocol.createRpmCalibrationCommand(rpm)
        Log.d(TAG, "发送转速校准: $rpm RPM")
        return writeData(command)
    }

    /**
     * 清除错误消息
     */
    fun clearError() {
        _errorMessage.value = null
    }

    /**
     * 字节数组转十六进制字符串
     */
    private fun ByteArray.toHexString(): String = 
        BleProtocol.run { toHexString() }

    /**
     * 清理资源
     */
    fun cleanup() {
        disconnect()
        stopScan()
    }
}
