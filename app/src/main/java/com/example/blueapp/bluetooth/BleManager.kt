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
import java.util.Locale
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
    ANALYZING_CHARACTERISTICS,
    ENABLING_NOTIFY,
    RECONNECTING,
    READY
}

enum class BleLogDirection { TX, RX }

data class BleLogEntry(
    val timeMillis: Long,
    val direction: BleLogDirection,
    val hex: String,
)

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

    // 连接与重连策略
    private var lastDevice: BluetoothDevice? = null
    private var userInitiatedDisconnect: Boolean = true
    private var reconnectJob: Job? = null

    private val reconnectMaxRetry = 5
    private val reconnectDelayMs = 1200L
    
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

    // 调试日志（TX/RX notify）
    private val _bleLogs = MutableStateFlow<List<BleLogEntry>>(emptyList())
    val bleLogs: StateFlow<List<BleLogEntry>> = _bleLogs.asStateFlow()

    private val _lastNotify = MutableStateFlow<ByteArray?>(null)
    val lastNotify: StateFlow<ByteArray?> = _lastNotify.asStateFlow()

    private fun appendLog(direction: BleLogDirection, data: ByteArray) {
        val entry = BleLogEntry(
            timeMillis = System.currentTimeMillis(),
            direction = direction,
            hex = data.toHexString(),
        )
        val current = _bleLogs.value
        val next = (current + entry).takeLast(400)
        _bleLogs.value = next
    }

    fun clearBleLogs() {
        _bleLogs.value = emptyList()
        _lastNotify.value = null
    }

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
        // 用户选择连接新设备：关闭旧连接，但不触发自动重连
        disconnect(userInitiated = true)

        _connectionState.value = ConnectionState.CONNECTING
        _errorMessage.value = null
        userInitiatedDisconnect = false
        lastDevice = device

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
    fun disconnect(userInitiated: Boolean = true) {
        userInitiatedDisconnect = userInitiated
        reconnectJob?.cancel()
        reconnectJob = null
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

        if (userInitiated) {
            lastDevice = null
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
                    try {
                        gatt.close()
                    } catch (_: Exception) {}
                    _connectionState.value = ConnectionState.DISCONNECTED
                    _errorMessage.value = "设备已断开连接"
                    stopPolling()

                    // 意外断开：尝试重连
                    if (!userInitiatedDisconnect) {
                        startReconnect()
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "服务发现成功")
                _connectionState.value = ConnectionState.ANALYZING_CHARACTERISTICS
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
            _lastNotify.value = value
            appendLog(BleLogDirection.RX, value)
            handleReceivedData(value)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "描述符写入成功，通知已启用")
                // CCCD 写成功后，进入 READY 并开始业务通信（轮询）
                if (_connectionState.value == ConnectionState.ENABLING_NOTIFY) {
                    _connectionState.value = ConnectionState.READY
                    _errorMessage.value = null
                    startPolling()
                    Log.d(TAG, "设备已就绪，开始数据轮询")
                }
            } else {
                Log.e(TAG, "描述符写入失败: $status")
                _errorMessage.value = "订阅通知失败（descriptorWrite=$status）"
                // 订阅失败时仍保持连接，但不进入 READY
                if (_connectionState.value == ConnectionState.ENABLING_NOTIFY) {
                    _connectionState.value = ConnectionState.CONNECTED
                }
            }
        }
    }

    /**
     * 分析并配置特征值
     */
    private fun analyzeCharacteristics(gatt: BluetoothGatt) {
        writeCharacteristic = null
        notifyCharacteristic = null

        val preferredService = BleUuidConfig.PREFERRED_SERVICE_UUID?.normalizeUuid()
        val preferredWrite = BleUuidConfig.PREFERRED_WRITE_CHAR_UUID?.normalizeUuid()
        val preferredNotify = BleUuidConfig.PREFERRED_NOTIFY_CHAR_UUID?.normalizeUuid()

        val allServices = gatt.services ?: emptyList()
        val filteredAllServices = allServices.filter { !isSystemService(it.uuid?.toString()) }

        val targetService = preferredService?.let { ps ->
            filteredAllServices.firstOrNull { it.uuid?.toString().normalizeUuid() == ps }
        }
        val servicesToTry = (listOfNotNull(targetService) + filteredAllServices)
            .distinctBy { it.uuid?.toString().normalizeUuid() }

        fun isWritable(ch: BluetoothGattCharacteristic): Boolean {
            val p = ch.properties
            return (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) ||
                (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
        }

        fun isNotifiableOrIndicatable(ch: BluetoothGattCharacteristic): Boolean {
            val p = ch.properties
            return (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) ||
                (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0)
        }

        // Pass 1：优先在 targetService（如果配置）里找，否则按非系统服务顺序找
        for (service in servicesToTry) {
            Log.d(TAG, "服务: ${service.uuid}")
            val chars = service.characteristics ?: emptyList()
            for (characteristic in chars) {
                val uuidStr = characteristic.uuid?.toString()
                val properties = characteristic.properties
                Log.d(TAG, "  特征值: ${characteristic.uuid}, 属性: $properties")

                if (writeCharacteristic == null) {
                    val hit = preferredWrite?.let { pw -> uuidStr.normalizeUuid() == pw } == true
                    if (hit) {
                        writeCharacteristic = characteristic
                        Log.d(TAG, "  ✓ 写特征值（UUID精确匹配）: ${characteristic.uuid}")
                    }
                }

                if (notifyCharacteristic == null) {
                    val hit = preferredNotify?.let { pn -> uuidStr.normalizeUuid() == pn } == true
                    if (hit && !isSystemChar(uuidStr) && isNotifiableOrIndicatable(characteristic)) {
                        notifyCharacteristic = characteristic
                        Log.d(TAG, "  ✓ 通知特征值（UUID精确匹配）: ${characteristic.uuid}")
                    }
                }
            }

            // 如果 UUID 精确匹配没找到，则回退到能力匹配（仍旧排除系统特征）
            if (writeCharacteristic == null) {
                writeCharacteristic = chars.firstOrNull { isWritable(it) }
                if (writeCharacteristic != null) {
                    Log.d(TAG, "  ✓ 写特征值（能力回退）: ${writeCharacteristic?.uuid}")
                }
            }
            if (notifyCharacteristic == null) {
                notifyCharacteristic = chars.firstOrNull { ch ->
                    val u = ch.uuid?.toString()
                    !isSystemChar(u) && isNotifiableOrIndicatable(ch)
                }
                if (notifyCharacteristic != null) {
                    Log.d(TAG, "  ✓ 通知特征值（能力回退）: ${notifyCharacteristic?.uuid}")
                }
            }

            if (writeCharacteristic != null && notifyCharacteristic != null) break
        }

        // Pass 2：如果在 targetService / 主遍历中没找到 notify，但配置了 notify UUID，则在所有服务里再找一次
        if (notifyCharacteristic == null && preferredNotify != null) {
            for (service in filteredAllServices) {
                val chars = service.characteristics ?: emptyList()
                val hit = chars.firstOrNull { ch ->
                    val u = ch.uuid?.toString()
                    u.normalizeUuid() == preferredNotify && !isSystemChar(u) && isNotifiableOrIndicatable(ch)
                }
                if (hit != null) {
                    notifyCharacteristic = hit
                    Log.d(TAG, "✓ 通知特征值（二次全局匹配）service=${service.uuid} char=${hit.uuid}")
                    break
                }
            }
        }

        // Pass 3：仍然找不到 notify，则在所有服务里找第一个可通知/指示的非系统特征
        if (notifyCharacteristic == null) {
            outer@ for (service in filteredAllServices) {
                val chars = service.characteristics ?: emptyList()
                for (ch in chars) {
                    val u = ch.uuid?.toString()
                    if (!isSystemChar(u) && isNotifiableOrIndicatable(ch)) {
                        notifyCharacteristic = ch
                        Log.d(TAG, "✓ 通知特征值（全局能力回退）service=${service.uuid} char=${ch.uuid}")
                        break@outer
                    }
                }
            }
        }

        val foundWriteChar = writeCharacteristic != null
        val foundNotifyChar = notifyCharacteristic != null

        if (foundWriteChar && foundNotifyChar) {
            Log.d(TAG, "最终选择特征值: write=${writeCharacteristic?.uuid} notify=${notifyCharacteristic?.uuid}")
            _connectionState.value = ConnectionState.ENABLING_NOTIFY
            _errorMessage.value = null
            // 只在最终选定后启用通知（避免遍历过程中启用到错误特征）
            enableNotification(gatt, notifyCharacteristic!!)
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
        val ok = gatt.setCharacteristicNotification(characteristic, true)
        if (!ok) {
            Log.e(TAG, "setCharacteristicNotification 返回 false: ${characteristic.uuid}")
            _errorMessage.value = "启用通知失败（setCharacteristicNotification=false）"
        }
        
        val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
        if (descriptor != null) {
            val props = characteristic.properties
            val enableValue = when {
                (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                (props and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0 -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                else -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val r = gatt.writeDescriptor(descriptor, enableValue)
                if (r != BluetoothGatt.GATT_SUCCESS) {
                    Log.e(TAG, "writeDescriptor 失败: r=$r char=${characteristic.uuid} desc=${descriptor.uuid}")
                    _errorMessage.value = "订阅通知失败（writeDescriptor=$r）"
                }
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = enableValue
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }

            val valueHex = enableValue.joinToString(" ") { b -> "%02X".format(b) }
            Log.d(
                TAG,
                "已写入CCCD: char=${characteristic.uuid} value=$valueHex",
            )

            // 注意：READY 会在 onDescriptorWrite 成功后设置
        } else {
            // 有些设备没有 CCCD，但仍然可能通过 setCharacteristicNotification 工作；这里给出提示方便排查
            Log.w(TAG, "未找到CCCD(0x2902) descriptor: char=${characteristic.uuid}")
            _errorMessage.value = "未找到CCCD(0x2902)，可能无法收到Notify"

            // 没有 CCCD 时，尽力进入 READY（部分设备仍能收到回包）
            if (_connectionState.value == ConnectionState.ENABLING_NOTIFY) {
                _connectionState.value = ConnectionState.READY
                startPolling()
            }
        }
    }

    private fun startReconnect() {
        val device = lastDevice ?: return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            _connectionState.value = ConnectionState.RECONNECTING
            for (attempt in 1..reconnectMaxRetry) {
                if (userInitiatedDisconnect) return@launch
                _errorMessage.value = "连接断开，正在重连（$attempt/$reconnectMaxRetry）..."
                delay(reconnectDelayMs)
                try {
                    bluetoothGatt?.close()
                } catch (_: Exception) {}
                bluetoothGatt = null
                writeCharacteristic = null
                notifyCharacteristic = null
                receivedDataBuffer.clear()

                try {
                    _connectionState.value = ConnectionState.CONNECTING
                    bluetoothGatt = device.connectGatt(context, false, gattCallback)
                    Log.d(TAG, "重连发起: ${device.name} (${device.address})")
                    return@launch
                } catch (e: Exception) {
                    Log.e(TAG, "重连失败 attempt=$attempt", e)
                }
            }
            _connectionState.value = ConnectionState.DISCONNECTED
            _errorMessage.value = "重连失败，请手动重新连接"
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
            appendLog(BleLogDirection.TX, data)
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
     * 发送自定义十六进制指令（调试器使用）
     *
     * 支持输入：
     * - "7E 7F 50 FB FD"
     * - "7E7F50FBFD"
     */
    fun sendRawHexCommand(hex: String): Boolean {
        if (_connectionState.value != ConnectionState.READY) {
            _errorMessage.value = "设备未就绪"
            return false
        }
        val bytes = parseHexToBytes(hex)
        if (bytes == null || bytes.isEmpty()) {
            _errorMessage.value = "指令格式错误：请输入十六进制（例如 7E 7F 50 FB FD）"
            return false
        }
        return writeData(bytes)
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
        val startIndex = findHeaderIndex(receivedDataBuffer)

        if (startIndex >= 0) {
            // 找到数据包头，检查是否有足够的数据
            val packetData = receivedDataBuffer.drop(startIndex).take(256).toByteArray()
            
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

    private fun parseHexToBytes(input: String): ByteArray? {
        val cleaned = input
            .trim()
            .replace("\n", " ")
            .replace("\t", " ")
            .replace("0x", "", ignoreCase = true)
            .replace(" ", "")
            .uppercase(Locale.US)

        if (cleaned.isEmpty()) return byteArrayOf()
        if (!cleaned.matches(Regex("^[0-9A-F]+$"))) return null
        if (cleaned.length % 2 != 0) return null

        return try {
            val out = ByteArray(cleaned.length / 2)
            var i = 0
            while (i < cleaned.length) {
                val byteStr = cleaned.substring(i, i + 2)
                out[i / 2] = byteStr.toInt(16).toByte()
                i += 2
            }
            out
        } catch (_: Exception) {
            null
        }
    }

    private fun String?.normalizeUuid(): String =
        (this ?: "").trim().uppercase(Locale.US)

    private fun isSystemService(uuid: String?): Boolean {
        val u = uuid.normalizeUuid()
        return u.startsWith("00001800") || u.startsWith("00001801") // GAP / GATT
    }

    private fun isSystemChar(uuid: String?): Boolean {
        val u = uuid.normalizeUuid()
        return u.startsWith("00002A00") || u.startsWith("00002A01") // Device Name / Appearance
    }

    private fun findHeaderIndex(buf: List<Byte>): Int {
        if (buf.size < 3) return -1
        val b0 = 0x7E.toByte()
        val b1 = 0x7F.toByte()
        val b2 = 0x30.toByte()
        for (i in 0..(buf.size - 3)) {
            if (buf[i] == b0 && buf[i + 1] == b1 && buf[i + 2] == b2) return i
        }
        return -1
    }

    /**
     * 清理资源
     */
    fun cleanup() {
        disconnect(userInitiated = true)
        stopScan()
    }
}
