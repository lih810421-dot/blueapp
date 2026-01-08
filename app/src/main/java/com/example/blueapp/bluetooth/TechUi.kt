package com.example.blueapp.bluetooth

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// 轻色「科技」配色：用户偏好非深色模式
internal val TechPrimary = Color(0xFF00B7FF)
internal val TechSecondary = Color(0xFF7C3AED)
internal val TechBgStart = Color(0xFFF6FAFF)
internal val TechBgEnd = Color(0xFFF1F4FF)
internal val TechText = Color(0xFF0B1220)
internal val TechSubText = Color(0xFF5B6475)
// 卡片背景改为不透明，避免“没铺满/透底”的观感
internal val GlassFill = Color.White
internal val GlassBorder = Color(0xFF0B1220).copy(alpha = 0.08f)

@Composable
internal fun TechBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(TechBgStart, TechBgEnd))),
    ) {
        GridOverlay()
        // 只给内容留安全区，背景仍然全屏铺满（避免和顶部任务栏/底部手势条重叠）
        Box(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            content()
        }
    }
}

@Composable
private fun GridOverlay() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val step = 40.dp.toPx()
        val lineColor = TechText.copy(alpha = 0.03f)

        var x = 0f
        while (x <= size.width) {
            drawLine(
                color = lineColor,
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 1f,
            )
            x += step
        }

        var y = 0f
        while (y <= size.height) {
            drawLine(
                color = lineColor,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1f,
            )
            y += step
        }
    }
}

@Composable
internal fun GlassCard(
    modifier: Modifier = Modifier,
    corner: Dp = 16.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(corner)
    Surface(
        modifier = modifier,
        shape = shape,
        border = BorderStroke(1.dp, GlassBorder),
        color = GlassFill,
        contentColor = TechText,
        tonalElevation = 0.dp,
        shadowElevation = 10.dp,
    ) {
        Box(modifier = Modifier.padding(16.dp)) { content() }
    }
}

