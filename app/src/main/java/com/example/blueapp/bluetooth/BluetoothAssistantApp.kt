package com.example.blueapp.bluetooth

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

@Composable
fun BluetoothAssistantApp() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 创建 BLE 管理器
    val bleManager = remember { BleManager(context) }

    // 收集状态
    val scannedDevices by bleManager.scannedDevices.collectAsState()
    val isScanning by bleManager.isScanning.collectAsState()
    val connectionState by bleManager.connectionState.collectAsState()
    val sensorData by bleManager.sensorData.collectAsState()
    val errorMessage by bleManager.errorMessage.collectAsState()
    val bleLogs by bleManager.bleLogs.collectAsState()

    // 界面状态
    var screen by rememberSaveable { mutableStateOf("scan") }
    var selectedDeviceAddress by rememberSaveable { mutableStateOf<String?>(null) }
    var debugReturnScreen by rememberSaveable { mutableStateOf("scan") }

    // 蓝牙和权限状态
    var bluetoothEnabled by remember { mutableStateOf(bleManager.isBluetoothEnabled()) }
    var hasPermissions by remember { mutableStateOf(bleManager.hasRequiredPermissions()) }

    // 权限请求
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasPermissions = permissions.all { it.value }
        if (hasPermissions) {
            bluetoothEnabled = bleManager.isBluetoothEnabled()
        }
    }

    // 蓝牙开启请求
    val bluetoothEnableLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        bluetoothEnabled = result.resultCode == Activity.RESULT_OK
    }

    // 检查并请求权限
    val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    } else {
        arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    }

    // 生命周期监听
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    bluetoothEnabled = bleManager.isBluetoothEnabled()
                    hasPermissions = bleManager.hasRequiredPermissions()
                }
                Lifecycle.Event.ON_PAUSE -> {
                    if (screen == "scan") {
                        bleManager.stopScan()
                    }
                }
                Lifecycle.Event.ON_DESTROY -> {
                    bleManager.cleanup()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            bleManager.cleanup()
        }
    }

    when (screen) {
        "scan" -> {
            BluetoothScanScreen(
                devices = scannedDevices,
                scanning = isScanning,
                onToggleScan = {
                    if (isScanning) {
                        bleManager.stopScan()
                    } else {
                        if (!hasPermissions) {
                            permissionLauncher.launch(requiredPermissions)
                        } else if (!bluetoothEnabled) {
                            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                            bluetoothEnableLauncher.launch(enableBtIntent)
                        } else {
                            bleManager.startScan()
                        }
                    }
                },
                onDeviceClick = { device ->
                    selectedDeviceAddress = device.address
                    bleManager.connect(device.device)
                    screen = "control"
                },
                onSettingsClick = {
                    debugReturnScreen = "scan"
                    screen = "debug"
                },
                bluetoothEnabled = bluetoothEnabled,
                hasPermissions = hasPermissions,
                onRequestPermissions = {
                    permissionLauncher.launch(requiredPermissions)
                },
                onEnableBluetooth = {
                    val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                    bluetoothEnableLauncher.launch(enableBtIntent)
                }
            )
        }

        "control" -> {
            val selectedDevice = scannedDevices.find { it.address == selectedDeviceAddress }
            val deviceName = selectedDevice?.name ?: "未知设备"

            // 从传感器数据中提取显示值
            val rpm = sensorData?.rpm ?: 0
            val pressure = sensorData?.freOut?.toInt() ?: 0  // 压力值在 freOut 字段
            val currentMode = sensorData?.modeNum ?: 0

            // 连接状态文本
            val connectionStateText = when (connectionState) {
                ConnectionState.DISCONNECTED -> "已断开"
                ConnectionState.CONNECTING -> "连接中..."
                ConnectionState.CONNECTED -> "已连接"
                ConnectionState.DISCOVERING_SERVICES -> "发现服务中..."
                ConnectionState.READY -> "就绪"
            }

            // 自动返回到扫描页面（如果断开连接）
            LaunchedEffect(connectionState) {
                if (connectionState == ConnectionState.DISCONNECTED && screen == "control") {
                    // 延迟返回，给用户看到断开消息的时间
                    kotlinx.coroutines.delay(2000)
                    if (connectionState == ConnectionState.DISCONNECTED) {
                        screen = "scan"
                    }
                }
            }

            DeviceControlScreen(
                deviceName = deviceName,
                connected = connectionState == ConnectionState.READY,
                onBack = {
                    screen = "scan"
                },
                rpm = rpm,
                pressure = pressure,
                currentMode = currentMode,
                onModeChange = { mode ->
                    bleManager.sendModeCommand(mode)
                },
                onSendTargetRpm = { targetRpm ->
                    bleManager.sendRpmCalibration(targetRpm)
                },
                connectionStateText = connectionStateText,
                errorMessage = errorMessage,
                onClearError = {
                    bleManager.clearError()
                },
                onOpenDebugger = {
                    debugReturnScreen = "control"
                    screen = "debug"
                },
                onDisconnect = { bleManager.disconnect() },
            )
        }

        "debug" -> {
            val connectionStateText = when (connectionState) {
                ConnectionState.DISCONNECTED -> "已断开"
                ConnectionState.CONNECTING -> "连接中..."
                ConnectionState.CONNECTED -> "已连接"
                ConnectionState.DISCOVERING_SERVICES -> "发现服务中..."
                ConnectionState.READY -> "就绪"
            }

            BleDebuggerScreen(
                connected = connectionState == ConnectionState.READY,
                connectionStateText = connectionStateText,
                logs = bleLogs,
                onSendHex = { hex ->
                    // 如果发送失败，错误会通过 errorMessage 流提示；调试器页也能从日志看到 TX
                    bleManager.sendRawHexCommand(hex)
                },
                onClearLogs = { bleManager.clearBleLogs() },
                onBack = { screen = debugReturnScreen },
            )
        }
    }
}
