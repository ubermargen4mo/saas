@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.hrips.browser

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

@Composable
internal fun SwipeUpToClose(onClose: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var offset by remember { mutableFloatStateOf(0f) }
    var height by remember { mutableFloatStateOf(1f) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val closeNow by rememberUpdatedState(onClose)
    val dragState = rememberDraggableState { d -> offset = (offset + d).coerceAtMost(0f) }
    Box(
        modifier
            .onSizeChanged { height = it.height.toFloat().coerceAtLeast(1f) }
            .draggable(
                state = dragState,
                orientation = Orientation.Vertical,
                onDragStopped = { velocity ->
                    val from = offset
                    scope.launch {
                        if (from < -height * 0.22f || velocity < -1200f) {
                            animate(from, -height * 1.15f, 0f, tween(SWIPE_MS)) { v, _ -> offset = v }
                            runCatching { haptic.performHapticFeedback(HapticFeedbackType.Confirm) }
                            closeNow()
                            delay(300)
                            offset = 0f
                        } else {
                            animate(from, 0f, 0f, tween(MOVE_MS, easing = FastOutSlowInEasing)) { v, _ -> offset = v }
                        }
                    }
                },
            )
            .graphicsLayer {
                translationY = offset
                alpha = 1f - 0.85f * (-offset / height).coerceIn(0f, 1f)
                compositingStrategy = CompositingStrategy.ModulateAlpha
            },
    ) { content() }
}

@Composable
internal fun GridSwipeUpToClose(enabled: Boolean, onClose: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var offset by remember { mutableFloatStateOf(0f) }
    var height by remember { mutableFloatStateOf(1f) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val closeNow by rememberUpdatedState(onClose)
    val enabledNow by rememberUpdatedState(enabled)
    Box(
        modifier
            .onSizeChanged { height = it.height.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var claimed = false
                    val slop = awaitVerticalTouchSlopOrCancellation(down.id) { change, over ->
                        if (enabledNow && over < 0f) {
                            change.consume()
                            claimed = true
                        }
                    }
                    if (slop == null || !claimed) return@awaitEachGesture
                    val tracker = VelocityTracker()
                    tracker.addPointerInputChange(slop)
                    offset = (offset + slop.positionChange().y).coerceAtMost(0f)
                    val finished = verticalDrag(slop.id) { change ->
                        tracker.addPointerInputChange(change)
                        offset = (offset + change.positionChange().y).coerceAtMost(0f)
                        change.consume()
                    }
                    val velocity = if (finished) tracker.calculateVelocity().y else 0f
                    val from = offset
                    scope.launch {
                        if (from < -height * 0.22f || velocity < -1200f) {
                            animate(from, -height * 1.15f, 0f, tween(SWIPE_MS)) { v, _ -> offset = v }
                            runCatching { haptic.performHapticFeedback(HapticFeedbackType.Confirm) }
                            closeNow()
                            delay(300)
                            offset = 0f
                        } else {
                            animate(from, 0f, 0f, tween(MOVE_MS, easing = FastOutSlowInEasing)) { v, _ -> offset = v }
                        }
                    }
                }
            }
            .graphicsLayer {
                translationY = offset
                alpha = 1f - 0.85f * (-offset / height).coerceIn(0f, 1f)
                compositingStrategy = CompositingStrategy.ModulateAlpha
            },
    ) { content() }
}

@Composable
internal fun SwipeToClose(onClose: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var offset by remember { mutableFloatStateOf(0f) }
    var width by remember { mutableFloatStateOf(1f) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val closeNow by rememberUpdatedState(onClose)
    Box(
        modifier
            .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val from = offset
                        val far = abs(from) > width * 0.35f
                        scope.launch {
                            if (far) {
                                animate(from, sign(from) * width, 0f, tween(SWIPE_MS)) { v, _ -> offset = v }
                                runCatching { haptic.performHapticFeedback(HapticFeedbackType.Confirm) }
                                closeNow()
                                delay(300)
                                offset = 0f
                            } else {
                                animate(from, 0f, 0f, tween(MOVE_MS, easing = FastOutSlowInEasing)) { v, _ -> offset = v }
                            }
                        }
                    },
                    onDragCancel = { offset = 0f },
                    onHorizontalDrag = { change, dx ->
                        change.consume()
                        offset += dx
                    },
                )
            }
            .graphicsLayer {
                translationX = offset
                alpha = 1f - 0.7f * (abs(offset) / width).coerceIn(0f, 1f)
                compositingStrategy = CompositingStrategy.ModulateAlpha
            },
    ) { content() }
}

@Composable
internal fun CloseTabButton(tab: Tab, modifier: Modifier = Modifier, onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    Box(
        modifier
            .size(44.dp)
            .clickable {
                runCatching { haptic.performHapticFeedback(HapticFeedbackType.Confirm) }
                onClose()
            }
            .semantics { contentDescription = "Закрыть вкладку: ${tab.label()}" },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(30.dp).clip(CircleShape).background(cs.surface), contentAlignment = Alignment.Center) {
            Icon(HripsIcons.Close, null, Modifier.size(16.dp), tint = cs.onSurface)
        }
    }
}
