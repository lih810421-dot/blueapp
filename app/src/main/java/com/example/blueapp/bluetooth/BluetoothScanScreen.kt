package com.example.blueapp.bluetooth

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.BluetoothSearching
import androidx.compose.material.icons.outlined.BluetoothConnected
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.Watch
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun BluetoothScanScreen(
    devices: List<BleDeviceInfo>,
    scanning: Boolean,
    onToggleScan: () -> Unit,
    onDeviceClick: (BleDeviceInfo) -> Unit,
    onSettingsClick: () -> Unit,
    bluetoothEnabled: Boolean,
    hasPermissions: Boolean,
    onRequestPermissions: () -> Unit,
    onEnableBluetooth: () -> Unit,
) {
    val infinite = rememberInfiniteTransition(label = "scan")
    val pulse by infinite.animateFloat(
        initialValue = 0.65f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )

    TechBackground(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 18.dp),
        ) {
            Header(onSettingsClick = onSettingsClick)

            Spacer(modifier = Modifier.height(18.dp))

            // 显示权限和蓝牙状态提示
            if (!hasPermissions) {
                WarningCard(
                    message = "需要蓝牙权限才能扫描设备",
                    actionText = "授予权限",
                    onAction = onRequestPermissions
                )
                Spacer(modifier = Modifier.height(12.dp))
            } else if (!bluetoothEnabled) {
                WarningCard(
                    message = "请开启蓝牙以连接设备",
                    actionText = "开启蓝牙",
                    onAction = onEnableBluetooth
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            ScanAction(
                scanning = scanning,
                pulse = pulse,
                onToggleScan = onToggleScan,
                enabled = hasPermissions && bluetoothEnabled,
            )

            Spacer(modifier = Modifier.height(18.dp))

            DeviceList(
                devices = devices,
                scanning = scanning,
                onDeviceClick = onDeviceClick,
            )
        }
    }
}

@Composable
private fun Header(onSettingsClick: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth()) {
        IconButton(
            onClick = { /* TODO: menu */ },
            modifier = Modifier.align(Alignment.CenterStart),
        ) {
            Icon(
                imageVector = Icons.Outlined.Menu,
                contentDescription = "菜单",
                tint = TechSubText,
                modifier = Modifier.size(28.dp),
            )
        }

        Row(
            modifier = Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.BluetoothConnected,
                contentDescription = null,
                tint = TechPrimary,
                modifier = Modifier.size(30.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "蓝牙助手",
                color = TechText,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.2.sp,
            )
        }

        IconButton(
            onClick = onSettingsClick,
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            Icon(
                imageVector = Icons.Outlined.Settings,
                contentDescription = "设置",
                tint = TechSubText,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

@Composable
private fun WarningCard(
    message: String,
    actionText: String,
    onAction: () -> Unit,
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        corner = 14.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = message,
                color = Color(0xFFF59E0B),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFFF59E0B).copy(alpha = 0.2f))
                    .clickable(onClick = onAction)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    text = actionText,
                    color = Color(0xFFF59E0B),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun ScanAction(
    scanning: Boolean,
    pulse: Float,
    onToggleScan: () -> Unit,
    enabled: Boolean = true,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val ringAlpha = if (scanning) 1f else 0.55f
        val buttonScale = if (scanning) (0.98f + 0.03f * pulse) else 1f

        Box(
            modifier = Modifier
                .size(280.dp)
                .clip(RectangleShape),
            contentAlignment = Alignment.Center,
        ) {
            // 三层静态“波纹环”
            Box(
                modifier = Modifier
                    .size(280.dp)
                    .clip(CircleShape)
                    .background(TechPrimary.copy(alpha = 0.05f * ringAlpha))
                    .alpha(0.9f),
            )
            Box(
                modifier = Modifier
                    .size(220.dp)
                    .clip(CircleShape)
                    .background(Color.Transparent)
                    .border(1.dp, TechPrimary.copy(alpha = 0.20f * ringAlpha), CircleShape),
            )
            Box(
                modifier = Modifier
                    .size(160.dp)
                    .clip(CircleShape)
                    .background(Color.Transparent)
                    .border(1.dp, TechPrimary.copy(alpha = 0.30f * ringAlpha), CircleShape),
            )

            // 主按钮
            val buttonBrush = if (enabled) {
                Brush.linearGradient(listOf(TechPrimary, TechSecondary))
            } else {
                Brush.linearGradient(listOf(TechSubText, TechSubText))
            }
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .scale(buttonScale)
                    .clip(CircleShape)
                    .background(buttonBrush)
                    .clickable(
                        enabled = enabled,
                        indication = null,
                        interactionSource = MutableInteractionSource(),
                        onClick = onToggleScan,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.BluetoothSearching,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(38.dp),
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (scanning) "扫描中" else "开始扫描",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "点击按钮搜索附近的 BLE 设备",
            color = TechSubText,
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun DeviceList(
    devices: List<BleDeviceInfo>,
    scanning: Boolean,
    onDeviceClick: (BleDeviceInfo) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "附近设备",
            color = TechText,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.2.sp,
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(TechPrimary.copy(alpha = if (scanning) 1f else 0.35f)),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (scanning) "SCANNING" else "PAUSED",
                color = TechPrimary.copy(alpha = if (scanning) 1f else 0.6f),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
            )
        }
    }

    Spacer(modifier = Modifier.height(14.dp))

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(devices) { device ->
            DeviceCard(device = device, onClick = { onDeviceClick(device) })
        }
        item { Spacer(modifier = Modifier.height(10.dp)) }
    }
}

@Composable
private fun DeviceCard(
    device: BleDeviceInfo,
    onClick: () -> Unit,
) {
    // 根据设备名称推测类型
    val (icon, iconBg, iconFg) = when {
        device.name.contains("温", ignoreCase = true) || device.name.contains("湿", ignoreCase = true) -> 
            Triple(Icons.Outlined.Thermostat, TechPrimary.copy(alpha = 0.20f), TechPrimary)
        device.name.contains("audio", ignoreCase = true) || device.name.contains("耳机", ignoreCase = true) -> 
            Triple(Icons.Outlined.Headphones, TechSecondary.copy(alpha = 0.12f), TechSecondary)
        device.name.contains("手环", ignoreCase = true) || device.name.contains("watch", ignoreCase = true) -> 
            Triple(Icons.Outlined.Watch, Color(0xFF3B82F6).copy(alpha = 0.10f), Color(0xFF2563EB))
        else -> 
            Triple(Icons.AutoMirrored.Outlined.BluetoothSearching, TechText.copy(alpha = 0.06f), TechSubText)
    }

    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(iconBg)
                        .border(1.dp, iconFg.copy(alpha = 0.30f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(imageVector = icon, contentDescription = null, tint = iconFg)
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column {
                    Text(
                        text = device.name,
                        color = TechText,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "MAC: ${device.address}",
                        color = TechSubText,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

                Column(horizontalAlignment = Alignment.End) {
                    SignalBars(rssi = device.rssi)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "${device.rssi}dBm",
                        color = if (device.rssi > -60) TechPrimary else TechSubText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun SignalBars(rssi: Int) {
    // 简单映射：越接近 0 信号越强
    val level = when {
        rssi >= -55 -> 3
        rssi >= -70 -> 2
        rssi >= -85 -> 1
        else -> 0
    }
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier.height(18.dp),
    ) {
        val barHeights = listOf(8.dp, 12.dp, 16.dp, 16.dp)
        for (i in 0..3) {
            val active = i <= level
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(barHeights[i])
                    .clip(RoundedCornerShape(2.dp))
                    .background(TechPrimary.copy(alpha = if (active) 1f else 0.25f)),
            )
        }
    }
}

