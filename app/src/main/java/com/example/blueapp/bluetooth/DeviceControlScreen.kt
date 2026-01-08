package com.example.blueapp.bluetooth

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun DeviceControlScreen(
    deviceName: String,
    connected: Boolean,
    onBack: () -> Unit,
    rpm: Int,
    pressure: Int,
    currentMode: Int,
    onModeChange: (WorkMode) -> Unit,
    onSendTargetRpm: (Int) -> Unit,
    connectionStateText: String,
    errorMessage: String?,
    onClearError: () -> Unit,
) {
    var targetRpmText by remember { mutableStateOf(rpm.toString()) }
    var inlineStatus by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }

    // 当 rpm 变化时，如果输入框为空或无效，更新为当前值
    LaunchedEffect(rpm) {
        if (targetRpmText.toIntOrNull() == null) {
            targetRpmText = rpm.toString()
        }
    }

    // 显示错误消息
    LaunchedEffect(errorMessage) {
        if (errorMessage != null) {
            inlineStatus = "错误: $errorMessage"
            delay(3000)
            onClearError()
            inlineStatus = null
        }
    }

    // 0..360kPa 是模拟数据范围（后续接真实协议时可替换）
    val pressureRatio = (pressure / 360f).coerceIn(0f, 1f)
    val maxRpm = 15000
    val minRpm = 1

    // 压力历史，用于小型动态图（sparkline）
    val pressureHistory = remember { mutableStateListOf<Int>() }
    LaunchedEffect(pressure) {
        pressureHistory.add(pressure)
        if (pressureHistory.size > 36) pressureHistory.removeAt(0)
    }

    TechBackground(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 14.dp),
        ) {
            TopBar(
                deviceName = deviceName,
                connected = connected,
                connectionStateText = connectionStateText,
                onBack = onBack
            )

            Spacer(modifier = Modifier.height(8.dp))

            RpmGauge(rpm = rpm, min = minRpm, max = maxRpm, modifier = Modifier.fillMaxWidth())

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                PressureCard(
                    modifier = Modifier.weight(1.45f),
                    value = pressure,
                    ratio = pressureRatio,
                    history = pressureHistory,
                )
                CurrentModeCard(
                    modifier = Modifier.weight(1f),
                    mode = currentMode,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            ModePicker(
                selected = currentMode,
                onSelect = { modeNum ->
                    val workMode = WorkMode.entries.find { it.modeNum == modeNum }
                    workMode?.let { onModeChange(it) }
                },
                connected = connected,
            )

            Spacer(modifier = Modifier.height(18.dp))

            TargetRpmInput(
                currentRpm = rpm,
                maxRpm = maxRpm,
                minRpm = minRpm,
                pressure = pressure,
                connected = connected,
                sending = sending,
                targetRpmText = targetRpmText,
                onTargetRpmTextChange = {
                    targetRpmText = it
                    inlineStatus = null
                },
                inlineStatus = inlineStatus,
                onSend = { target ->
                    sending = true
                    onSendTargetRpm(target)
                    inlineStatus = "已发送：$target RPM"
                    // 模拟发送延迟
                    kotlinx.coroutines.GlobalScope.launch {
                        delay(500)
                        sending = false
                    }
                },
                onNudge = { delta ->
                    val current = targetRpmText.toIntOrNull() ?: rpm
                    targetRpmText = (current + delta).coerceIn(minRpm, maxRpm).toString()
                    inlineStatus = null
                },
                onResetToCurrent = {
                    targetRpmText = rpm.toString()
                    inlineStatus = null
                },
            )
        }
    }
}

@Composable
private fun TopBar(
    deviceName: String,
    connected: Boolean,
    connectionStateText: String,
    onBack: () -> Unit,
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

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
        Text(
            text = deviceName,
            color = TechText,
                fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.2.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
            if (connectionStateText.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = connectionStateText,
                    color = TechSubText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(
                    color = if (connected) Color(0xFF10B981) else Color(0xFF6B7280),
                    shape = CircleShape,
                ),
        )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (connected) "已连接" else "未连接",
                color = if (connected) Color(0xFF10B981) else Color(0xFF6B7280),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun RpmGauge(
    rpm: Int,
    min: Int,
    max: Int,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(280.dp),
        contentAlignment = Alignment.Center,
    ) {
        val gaugeSize = 210.dp
        val bg = Brush.radialGradient(listOf(Color.White, Color(0xFFE8F4FF)))

        Box(
            modifier = Modifier
                .size(gaugeSize)
                .background(bg, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val stroke = 3.dp.toPx()
                val outer = this.size.minDimension / 2f
                val center = Offset(this.size.width / 2f, this.size.height / 2f)

                // 外圈光晕
                drawCircle(
                    color = TechPrimary.copy(alpha = 0.35f),
                    radius = outer,
                    style = Stroke(width = stroke),
                )
                drawCircle(
                    color = TechPrimary.copy(alpha = 0.18f),
                    radius = outer * 1.08f,
                    style = Stroke(width = 1.dp.toPx()),
                )

                // tick（约 270° 角度范围）
                val startDeg = 225f
                val sweepDeg = 270f
                val tickCount = 60
                for (i in 0..tickCount) {
                    val t = i / tickCount.toFloat()
                    val angle = Math.toRadians((startDeg + sweepDeg * t).toDouble())
                    val longTick = i % 5 == 0
                    val r1 = outer * 0.78f
                    val r2 = if (longTick) outer * 0.90f else outer * 0.86f
                    val p1 = Offset(
                        x = center.x + r1 * cos(angle).toFloat(),
                        y = center.y + r1 * sin(angle).toFloat(),
                    )
                    val p2 = Offset(
                        x = center.x + r2 * cos(angle).toFloat(),
                        y = center.y + r2 * sin(angle).toFloat(),
                    )
                    drawLine(
                        color = TechText.copy(alpha = if (longTick) 0.22f else 0.12f),
                        start = p1,
                        end = p2,
                        strokeWidth = if (longTick) 2.dp.toPx() else 1.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }

                // 指针（三角形）
                val ratio = ((rpm - min).toFloat() / (max - min).toFloat()).coerceIn(0f, 1f)
                val pointerDeg = startDeg + sweepDeg * ratio
                val a = Math.toRadians(pointerDeg.toDouble())
                val tipR = outer * 0.92f
                val tip = Offset(
                    x = center.x + tipR * cos(a).toFloat(),
                    y = center.y + tipR * sin(a).toFloat(),
                )
                val baseR = outer * 0.70f
                val base = Offset(
                    x = center.x + baseR * cos(a).toFloat(),
                    y = center.y + baseR * sin(a).toFloat(),
                )
                val normal = a + Math.PI / 2.0
                val w = 10.dp.toPx()
                val left = Offset(
                    x = base.x + w * cos(normal).toFloat(),
                    y = base.y + w * sin(normal).toFloat(),
                )
                val right = Offset(
                    x = base.x - w * cos(normal).toFloat(),
                    y = base.y - w * sin(normal).toFloat(),
                )
                val path = Path().apply {
                    moveTo(tip.x, tip.y)
                    lineTo(left.x, left.y)
                    lineTo(right.x, right.y)
                    close()
                }
                drawPath(path, TechPrimary.copy(alpha = 0.95f))
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = rpm.toString(),
                    color = TechText,
                    fontSize = 56.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.SansSerif,
                    letterSpacing = (-1).sp,
                )
                Text(
                    text = "RPM",
                    color = TechPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 3.sp,
                )
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 26.dp)
                .width(200.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = min.toString(), color = TechSubText, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            Text(text = max.toString(), color = TechSubText, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun PressureCard(
    modifier: Modifier,
    value: Int,
    ratio: Float,
    history: List<Int>,
) {
    GlassCard(
        modifier = modifier.height(126.dp),
        corner = 16.dp,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // value 当前单位：kPa
            val bar = value / 100f
            val psi = bar * 14.5037738f // 1 BAR = 14.5037738 PSI

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(TechPrimary.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = "⇅", color = TechPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(text = "当前压力", color = TechSubText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "${"%.2f".format(bar)} bar  ·  ${"%.1f".format(psi)} psi",
                            color = TechSubText,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "${"%.2f".format(bar)}",
                        color = TechText,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = FontFamily.SansSerif,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${value} kPa",
                        color = TechSubText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 小型动态图表（压力历史）
            PressureSparkline(
                history = history,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(26.dp),
            )

            Spacer(modifier = Modifier.height(10.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(TechText.copy(alpha = 0.08f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(ratio.coerceIn(0f, 1f))
                        .height(8.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Brush.horizontalGradient(listOf(Color(0xFF10B981), Color(0xFF6EE7B7)))),
                )
            }
        }
    }
}

@Composable
private fun CurrentModeCard(
    modifier: Modifier,
    mode: Int,
) {
    GlassCard(modifier = modifier.height(126.dp), corner = 16.dp) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = "当前模式", color = TechSubText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "模式$mode",
                color = TechText,
                fontSize = 44.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = (-1).sp,
            )
        }
    }
}

@Composable
private fun ModePicker(
    selected: Int,
    onSelect: (Int) -> Unit,
    connected: Boolean,
) {
    val modes = listOf(
        WorkMode.E to "E",
        WorkMode.N to "N",
        WorkMode.S to "S",
        WorkMode.S_PLUS to "S+",
        WorkMode.R to "R"
    )

    GlassCard(modifier = Modifier.fillMaxWidth(), corner = 18.dp) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(text = "工作模式", color = TechText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                val selectedMode = WorkMode.fromModeNum(selected)
                Text(
                    text = selectedMode?.displayName ?: "模式 $selected",
                    color = TechSubText,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                modes.forEach { (mode, text) ->
                    ModeButton(
                        text = text,
                        active = mode.modeNum == selected,
                        onClick = { if (connected) onSelect(mode.modeNum) },
                        modifier = Modifier.weight(1f),
                        enabled = connected,
                    )
                }
            }

            if (!connected) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "设备未连接，无法切换模式",
                    color = Color(0xFFF59E0B),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun PressureSparkline(
    history: List<Int>,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        if (history.size < 2) return@Canvas

        val paddingX = 4.dp.toPx()
        val paddingY = 3.dp.toPx()
        val w = size.width - paddingX * 2
        val h = size.height - paddingY * 2
        if (w <= 0f || h <= 0f) return@Canvas

        val minV = history.minOrNull()?.toFloat() ?: 0f
        val maxV = history.maxOrNull()?.toFloat() ?: (minV + 1f)
        val range = (maxV - minV).coerceAtLeast(10f) // 至少 10 的范围避免过于敏感

        fun yFor(v: Float): Float {
            val t = ((v - minV) / range).coerceIn(0f, 1f)
            return paddingY + (1f - t) * h
        }

        val step = w / (history.size - 1).coerceAtLeast(1).toFloat()

        // 绘制渐变填充区域
        val fillPath = Path()
        history.forEachIndexed { idx, v ->
            val x = paddingX + step * idx
            val y = yFor(v.toFloat())
            if (idx == 0) {
                fillPath.moveTo(x, paddingY + h) // 从底部开始
                fillPath.lineTo(x, y)
            } else {
                fillPath.lineTo(x, y)
            }
        }
        fillPath.lineTo(paddingX + step * (history.size - 1), paddingY + h) // 回到底部
        fillPath.close()

        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(
                    TechPrimary.copy(alpha = 0.20f),
                    TechPrimary.copy(alpha = 0.02f),
                ),
            ),
        )

        // 绘制平滑折线
        val linePath = Path()
        history.forEachIndexed { idx, v ->
            val x = paddingX + step * idx
            val y = yFor(v.toFloat())
            if (idx == 0) {
                linePath.moveTo(x, y)
            } else {
                linePath.lineTo(x, y)
            }
        }

        drawPath(
            path = linePath,
            color = TechPrimary.copy(alpha = 0.90f),
            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
        )

        // 在最后一个点绘制圆点高亮
        if (history.isNotEmpty()) {
            val lastX = paddingX + step * (history.size - 1)
            val lastY = yFor(history.last().toFloat())
            drawCircle(
                color = TechPrimary,
                radius = 3.5.dp.toPx(),
                center = Offset(lastX, lastY),
            )
            drawCircle(
                color = Color.White,
                radius = 1.5.dp.toPx(),
                center = Offset(lastX, lastY),
            )
        }
    }
}

@Composable
private fun SpeedCorrection() {
    // 已废弃：原先的“修正量 Slider”不符合实际需求（应发送目标转速到设备）。
    // 保留空实现是为了避免旧引用时编译报错；后续可以彻底删除该函数。
}

@Composable
private fun TargetRpmInput(
    currentRpm: Int,
    maxRpm: Int,
    minRpm: Int,
    pressure: Int,
    connected: Boolean,
    sending: Boolean,
    targetRpmText: String,
    onTargetRpmTextChange: (String) -> Unit,
    inlineStatus: String?,
    onSend: (Int) -> Unit,
    onNudge: (Int) -> Unit,
    onResetToCurrent: () -> Unit,
) {
    val parsed = targetRpmText.toIntOrNull()
    // 动态校验：压力越高，可允许发送的最大目标转速越低（示例规则，后续可替换成设备协议）
    val bar = pressure / 100f
    val dynamicMax = when {
        bar >= 3.30f -> 6000
        bar >= 2.80f -> 9000
        else -> maxRpm
    }
    val valid = parsed != null && parsed in minRpm..dynamicMax

    GlassCard(modifier = Modifier.fillMaxWidth(), corner = 18.dp) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(text = "目标转速", color = TechText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "当前：$currentRpm RPM（允许：$minRpm - $dynamicMax）",
                        color = TechSubText,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }

                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(TechText.copy(alpha = 0.04f))
                        .clickable { onResetToCurrent() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.RestartAlt,
                        contentDescription = "设为当前",
                        tint = TechText.copy(alpha = 0.85f),
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = targetRpmText,
                onValueChange = { raw ->
                    // 只允许数字输入
                    val filtered = raw.filter { it.isDigit() }.take(5)
                    onTargetRpmTextChange(filtered)
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("输入转速（RPM）", color = TechSubText) },
                supportingText = {
                    val msg = when {
                        inlineStatus != null -> inlineStatus
                        !connected -> "设备未连接，无法发送"
                        sending -> "发送中…"
                        !valid && targetRpmText.isNotBlank() -> "请输入 $minRpm - $dynamicMax 之间的整数（动态限制）"
                        else -> "输入目标转速并发送到设备"
                    }
                    if (msg != null) {
                        Text(
                            text = msg,
                            color = when {
                                !connected -> Color(0xFFF59E0B) // warning
                                sending -> TechSubText
                                !valid && targetRpmText.isNotBlank() -> Color(0xFFEF4444) // error
                                else -> TechPrimary.copy(alpha = 0.9f)
                            },
                            fontSize = 12.sp,
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StepChip(text = "-500", onClick = { onNudge(-500) })
                StepChip(text = "-100", onClick = { onNudge(-100) })
                StepChip(text = "+100", onClick = { onNudge(100) })
                StepChip(text = "+500", onClick = { onNudge(500) })

                Spacer(modifier = Modifier.weight(1f))

                val brush = Brush.linearGradient(listOf(TechPrimary, TechSecondary))
                Button(
                    onClick = { parsed?.let { onSend(it) } },
                    enabled = connected && !sending && valid && targetRpmText.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                    contentPadding = ButtonDefaults.ContentPadding,
                    modifier = Modifier
                        .height(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(brush),
                ) {
                    Text(if (sending) "发送中" else "发送", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun ModeButton(
    text: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(14.dp)
    val alpha = if (enabled) 1f else 0.4f
    Box(
        modifier = modifier
            .height(44.dp)
            .clip(shape)
            .background(
                brush = if (active) {
                    Brush.linearGradient(
                        listOf(
                            TechPrimary.copy(alpha = alpha),
                            TechSecondary.copy(alpha = alpha)
                        )
                    )
                } else {
                    Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))
                },
                shape = shape,
            )
            .then(
                if (!active) Modifier.border(
                    1.dp,
                    TechPrimary.copy(alpha = 0.35f * alpha),
                    shape
                ) else Modifier
            )
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (active) Color.White.copy(alpha = alpha) else TechPrimary.copy(alpha = 0.95f * alpha),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun StepChip(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, TechPrimary.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .background(TechText.copy(alpha = 0.02f))
            .clickable { onClick() }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = TechPrimary.copy(alpha = 0.9f),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
        )
    }
}

