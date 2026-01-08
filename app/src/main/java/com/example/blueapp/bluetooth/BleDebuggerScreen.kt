package com.example.blueapp.bluetooth

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BleDebuggerScreen(
    connected: Boolean,
    connectionStateText: String,
    logs: List<BleLogEntry>,
    onSendHex: (String) -> Unit,
    onClearLogs: () -> Unit,
    onBack: () -> Unit,
) {
    var hexInput by remember { mutableStateOf("7E 7F 50 FB FD") }
    var hint by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(connected) {
        if (!connected) {
            hint = "未连接设备：请先在扫描页连接设备，或等待状态变为「就绪」"
        }
    }

    TechBackground(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 14.dp),
        ) {
            DebugTopBar(
                connected = connected,
                connectionStateText = connectionStateText,
                onBack = onBack,
                onClearLogs = onClearLogs,
            )

            Spacer(modifier = Modifier.height(12.dp))

            GlassCard(modifier = Modifier.fillMaxWidth(), corner = 18.dp) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "自定义指令（HEX）",
                        color = TechText,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "支持：7E 7F 50 FB FD 或 7E7F50FBFD（会自动去空格/0x）",
                        color = TechSubText,
                        fontSize = 12.sp,
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = hexInput,
                        onValueChange = { raw ->
                            // 只允许 0-9 A-F a-f 空格 以及 x（为了 0x 前缀）
                            val filtered = raw.filter { ch ->
                                ch.isDigit() ||
                                    (ch in 'a'..'f') ||
                                    (ch in 'A'..'F') ||
                                    ch == ' ' || ch == '\n' || ch == '\t' ||
                                    ch == 'x' || ch == 'X' || ch == '0'
                            }
                            hexInput = filtered
                            hint = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false,
                        minLines = 2,
                        label = { Text("例如：7E 7F 50 FB FD", color = TechSubText) },
                        supportingText = {
                            val msg = hint
                            if (msg != null) {
                                Text(msg, color = Color(0xFFF59E0B), fontSize = 12.sp)
                            }
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Ascii,
                            capitalization = KeyboardCapitalization.Characters,
                        ),
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            QuickChip(
                                text = "GET_DATA",
                                onClick = { hexInput = "7E 7F 50 FB FD" },
                            )
                            QuickChip(
                                text = "E",
                                onClick = { hexInput = "7E 7F 00 FB FD" },
                            )
                            QuickChip(
                                text = "N",
                                onClick = { hexInput = "7E 7F 01 FB FD" },
                            )
                            QuickChip(
                                text = "S",
                                onClick = { hexInput = "7E 7F 02 FB FD" },
                            )
                            QuickChip(
                                text = "S+",
                                onClick = { hexInput = "7E 7F 03 FB FD" },
                            )
                            QuickChip(
                                text = "R",
                                onClick = { hexInput = "7E 7F 04 FB FD" },
                            )
                        }

                        val brush = if (connected) {
                            Brush.linearGradient(listOf(TechPrimary, TechSecondary))
                        } else {
                            Brush.linearGradient(listOf(TechSubText, TechSubText))
                        }

                        Box(
                            modifier = Modifier
                                .height(44.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(brush)
                                .clickable(enabled = connected) {
                                    if (!connected) {
                                        hint = "当前未就绪，无法发送"
                                        return@clickable
                                    }
                                    onSendHex(hexInput)
                                }
                                .padding(horizontal = 14.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Outlined.Send,
                                    contentDescription = "发送",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("发送", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            GlassCard(modifier = Modifier.fillMaxWidth().weight(1f), corner = 18.dp) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "实时收发日志（Notify）",
                            color = TechText,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "${logs.size} 条",
                            color = TechSubText,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    if (logs.isEmpty()) {
                        Text(
                            text = "暂无日志。连接设备后，设备 Notify / 发送指令都会显示在这里。",
                            color = TechSubText,
                            fontSize = 13.sp,
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            itemsIndexed(logs) { _, entry ->
                                LogRow(entry)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DebugTopBar(
    connected: Boolean,
    connectionStateText: String,
    onBack: () -> Unit,
    onClearLogs: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = "返回",
                tint = TechText,
                modifier = Modifier.size(26.dp),
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "蓝牙调试器",
                color = TechText,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = connectionStateText,
                color = TechSubText,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(
                        color = if (connected) Color(0xFF10B981) else Color(0xFF6B7280),
                        shape = CircleShape,
                    ),
            )
            Spacer(modifier = Modifier.width(10.dp))
            IconButton(onClick = onClearLogs) {
                Icon(
                    imageVector = Icons.Outlined.DeleteSweep,
                    contentDescription = "清空日志",
                    tint = TechText,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

@Composable
private fun QuickChip(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(34.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, TechPrimary.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .background(TechText.copy(alpha = 0.02f))
            .clickable { onClick() }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = TechPrimary.copy(alpha = 0.92f),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun LogRow(entry: BleLogEntry) {
    val sdf = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }
    val timeText = sdf.format(Date(entry.timeMillis))
    val isRx = entry.direction == BleLogDirection.RX
    val pillColor = if (isRx) Color(0xFF10B981) else Color(0xFF3B82F6)
    val pillText = if (isRx) "RX" else "TX"

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(pillColor.copy(alpha = 0.15f))
                .border(1.dp, pillColor.copy(alpha = 0.35f), RoundedCornerShape(999.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = pillText,
                color = pillColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = timeText,
                color = TechSubText,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = entry.hex,
                color = TechText,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

