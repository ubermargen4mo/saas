package com.hrips.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp

/** Стили нижней панели: обычный, мягкое стекло, жидкое стекло. */
const val BAR_STYLE_DEFAULT = 0
const val BAR_STYLE_SOFT = 1
const val BAR_STYLE_LIQUID = 2

internal val LocalBarStyle = compositionLocalOf { BAR_STYLE_DEFAULT }

/**
 * Тень вокруг пилюли. Рисуется только снаружи формы: у полупрозрачной панели
 * обычная тень Material просвечивала бы сквозь «стекло» и мутила его.
 */
internal fun Modifier.glassShadow(style: Int): Modifier = drawWithCache {
    val liquid = style == BAR_STYLE_LIQUID
    val r = size.height / 2f
    val blur = (if (liquid) 16.dp else 11.dp).toPx()
    val dy = (if (liquid) 6.dp else 3.dp).toPx()
    val shadow = Color.Black.copy(alpha = if (liquid) 0.24f else 0.18f).toArgb()
    val path = Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(r, r))) }
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.BLACK
        setShadowLayer(blur, 0f, dy, shadow)
    }
    onDrawBehind {
        clipPath(path, ClipOp.Difference) {
            drawIntoCanvas { it.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, r, r, paint) }
        }
    }
}

/**
 * Заливка «стекла»: полупрозрачный тон, мягкий блик сверху и световая кромка.
 * Мягкое стекло плотнее и спокойнее, жидкое прозрачнее, с яркой кромкой и бликом по верхней дуге.
 */
internal fun Modifier.glassFill(style: Int, tint: Color, dark: Boolean): Modifier = drawWithCache {
    val liquid = style == BAR_STYLE_LIQUID
    val w = size.width
    val h = size.height
    val r = h / 2f
    val fill = tint.copy(alpha = if (liquid) 0.36f else 0.64f)
    val sheen = Brush.verticalGradient(
        listOf(Color.White.copy(alpha = if (liquid) (if (dark) 0.20f else 0.38f) else (if (dark) 0.08f else 0.22f)), Color.White.copy(alpha = 0f)),
        startY = 0f, endY = h * 0.62f,
    )
    val pool = Brush.verticalGradient(
        listOf(Color.White.copy(alpha = 0f), Color.White.copy(alpha = if (dark) 0.07f else 0.16f)),
        startY = h * 0.45f, endY = h,
    )
    val hi = if (liquid) (if (dark) 0.62f else 0.98f) else (if (dark) 0.24f else 0.70f)
    val lo = if (liquid) (if (dark) 0.22f else 0.55f) else (if (dark) 0.08f else 0.30f)
    val rim = Brush.linearGradient(
        listOf(Color.White.copy(alpha = hi), Color.White.copy(alpha = 0f), Color.White.copy(alpha = lo)),
        start = Offset.Zero, end = Offset(w, h),
    )
    val sw = (if (liquid) 1.25.dp else 0.8.dp).toPx()
    val inset = 2.dp.toPx()
    onDrawBehind {
        drawRoundRect(fill, cornerRadius = CornerRadius(r, r))
        drawRoundRect(sheen, cornerRadius = CornerRadius(r, r))
        if (liquid) {
            drawRoundRect(pool, cornerRadius = CornerRadius(r, r))
            // Блик по верхней дуге, как линза
            drawRoundRect(
                Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = if (dark) 0.30f else 0.55f), Color.White.copy(alpha = 0f)),
                    startY = 0f, endY = h * 0.42f,
                ),
                topLeft = Offset(r * 0.55f, inset),
                size = Size((w - r * 1.1f).coerceAtLeast(0f), h * 0.42f),
                cornerRadius = CornerRadius(h * 0.21f, h * 0.21f),
            )
        }
        drawRoundRect(
            rim,
            topLeft = Offset(sw / 2f, sw / 2f),
            size = Size((w - sw).coerceAtLeast(0f), (h - sw).coerceAtLeast(0f)),
            cornerRadius = CornerRadius(r - sw / 2f, r - sw / 2f),
            style = Stroke(sw),
        )
    }
}

/** Превью стиля панели для настроек: цветные пятна под пилюлей, чтобы было видно прозрачность. */
@Composable
internal fun BoxScope.BarStylePreview(style: Int) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(cs.primaryContainer, cs.tertiaryContainer))))
    Box(Modifier.align(Alignment.BottomStart).offset(x = 10.dp, y = 6.dp).size(46.dp).clip(CircleShape).background(cs.primary.copy(alpha = 0.55f)))
    Box(Modifier.align(Alignment.BottomEnd).offset(x = (-8).dp, y = 2.dp).size(38.dp).clip(CircleShape).background(cs.tertiary.copy(alpha = 0.55f)))
    BarPill(
        rememberBarPalette(),
        Modifier.align(Alignment.BottomCenter).padding(horizontal = 8.dp, vertical = 12.dp).fillMaxWidth().height(28.dp),
        pressHost = false,
        style = style,
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(3) { Box(Modifier.size(6.dp).clip(CircleShape).background(cs.onSurface.copy(alpha = 0.55f))) }
        }
    }
}
