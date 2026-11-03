package com.hrips.browser

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal val Tab.soundShown: Boolean get() = (audible || muted) && !home

@Composable
internal fun AudioGlyph(tab: Tab, size: Dp, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    val strike by animateFloatAsState(if (tab.muted) 1f else 0f, tween(220), label = "audioStrike")
    val label = if (tab.muted) "Звук выключен. Нажмите, чтобы включить" else "Играет звук. Нажмите, чтобы выключить"
    Box(
        modifier
            .clip(CircleShape)
            .clickable(role = Role.Button) { tab.toggleMuted() }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(HripsIcons.Volume, null, Modifier.size(size), tint = tint)
        Canvas(Modifier.size(size)) {
            if (strike <= 0.01f) return@Canvas
            val from = Offset(this.size.width * 0.17f, this.size.height * 0.17f)
            val to = Offset(this.size.width * 0.83f, this.size.height * 0.83f)
            drawLine(
                color = tint,
                start = from,
                end = Offset(from.x + (to.x - from.x) * strike, from.y + (to.y - from.y) * strike),
                strokeWidth = this.size.width * 0.09f,
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
internal fun SoundSlot(tab: Tab, size: Dp, tint: Color = LocalContentColor.current, gap: Dp = 0.dp, touch: Dp = size) {
    AnimatedVisibility(
        visible = tab.soundShown,
        enter = fadeIn(tween(160)) + scaleIn(tween(200), initialScale = 0.5f) + expandHorizontally(tween(200), expandFrom = Alignment.Start),
        exit = fadeOut(tween(120)) + scaleOut(tween(160), targetScale = 0.5f) + shrinkHorizontally(tween(160), shrinkTowards = Alignment.Start),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AudioGlyph(tab, size, Modifier.size(touch), tint)
            if (gap > 0.dp) Spacer(Modifier.width(gap))
        }
    }
}
