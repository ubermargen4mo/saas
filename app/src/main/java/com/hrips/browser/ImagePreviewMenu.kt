package com.hrips.browser

import android.os.Build
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Меню удержания картинки в стиле iOS: большое превью «вылетает» из точки нажатия, под ним список действий.
 *
 * Анимация целиком в graphicsLayer (translation/scale/alpha читаются в фазе рисования), поэтому за кадр не тратится
 * ни композиция, ни измерение. Закрытие идёт той же кривой в обратную сторону: превью улетает обратно к пальцу.
 * Фон размывается средствами окна (Android 12+), на старых версиях и при выключенном блюре остаётся затемнение.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ImagePreviewMenu(
    url: String,
    referrer: String?,
    linkActs: List<MenuAct>,
    mediaActs: List<MenuAct>,
    origin: Offset,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    // Точка нажатия в экранных координатах, запоминаем один раз: и «вылет», и «возврат» идут к одной и той же точке.
    val start = remember { origin }
    val progress = remember { Animatable(0f) }
    var closing by remember { mutableStateOf(false) }
    val close: () -> Unit = {
        if (!closing) {
            closing = true
            scope.launch {
                progress.animateTo(0f, tween(180, easing = FastOutSlowInEasing))
                onDismiss()
            }
        }
    }
    LaunchedEffect(Unit) { progress.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 460f)) }

    // Большее превью, чем миниатюра в обычной шторке: на весь экран 512 пикселей были бы мыльными.
    var failed by remember(url) { mutableStateOf(false) }
    val bitmap by produceState<ImageBitmap?>(null, url) {
        value = withContext(Dispatchers.IO) { loadThumb(url, referrer, maxDim = 1600, byteCap = 8_000_000) }
        if (value == null) failed = true
    }
    val targetAspect = bitmap?.let { it.width.toFloat() / it.height.toFloat() }?.coerceIn(0.4f, 2.6f) ?: 1.25f
    val aspect by animateFloatAsState(targetAspect, spring(dampingRatio = 1f, stiffness = 380f), label = "previewAspect")
    val imageAlpha by animateFloatAsState(if (bitmap != null) 1f else 0f, tween(200), label = "previewImage")

    Dialog(
        onDismissRequest = close,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        WindowBlur(progress)
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = progress.value.coerceIn(0f, 1f) }
                    .background(Color.Black.copy(alpha = 0.38f))
                    .pointerInput(Unit) { detectTapGestures { close() } },
            )

            BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding()) {
                val maxW = maxWidth
                val maxH = maxHeight
                val side = 24.dp
                val gap = 14.dp
                val groupGap = if (linkActs.isNotEmpty() && mediaActs.isNotEmpty()) 8.dp else 0.dp
                val menuW = minOf(maxW - side * 2, 300.dp)
                val menuMaxH = maxH * 0.52f
                val menuH = minOf(56.dp * (linkActs.size + mediaActs.size) + groupGap, menuMaxH)
                val previewMaxH = (maxH - menuH - gap - 32.dp).coerceAtLeast(120.dp)
                val fitW = minOf(maxW - side * 2, previewMaxH * aspect)
                val fitH = fitW / aspect
                val previewStart = (maxW - fitW) / 2
                // Меню выровнено по левому краю превью, но не вылезает за экран.
                val menuStart = previewStart.coerceAtLeast(side).coerceAtMost((maxW - menuW - side).coerceAtLeast(side))

                var finalCenter by remember { mutableStateOf(Offset.Unspecified) }
                // Где корень окна диалога лежит на экране: экранную точку нажатия переводим в его координаты.
                var rootOnScreen by remember { mutableStateOf(Offset.Zero) }

                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                    Box(
                        Modifier
                            .padding(start = previewStart)
                            .size(fitW, fitH)
                            // Центр читаем до graphicsLayer: так в него не попадает собственная анимация.
                            .onGloballyPositioned {
                                val c = it.boundsInRoot().center
                                if (c != finalCenter) finalCenter = c
                                val r = it.positionOnScreen() - it.positionInRoot()
                                if (r != rootOnScreen) rootOnScreen = r
                            }
                            .graphicsLayer {
                                val p = progress.value
                                val s0 = 0.28f
                                val sc = s0 + (1f - s0) * p
                                scaleX = sc
                                scaleY = sc
                                if (start.isSpecified && finalCenter.isSpecified) {
                                    translationX = (start.x - rootOnScreen.x - finalCenter.x) * (1f - p)
                                    translationY = (start.y - rootOnScreen.y - finalCenter.y) * (1f - p)
                                }
                                alpha = (p * 3f).coerceIn(0f, 1f)
                                shadowElevation = 28.dp.toPx() * p.coerceIn(0f, 1f)
                                shape = RoundedCornerShape(24.dp)
                                clip = true
                            }
                            .background(cs.surfaceContainerHigh),
                        contentAlignment = Alignment.Center,
                    ) {
                        val b = bitmap
                        if (b != null) {
                            Image(
                                b, null,
                                Modifier.fillMaxSize().graphicsLayer { alpha = imageAlpha },
                                contentScale = ContentScale.Crop,
                            )
                        } else if (failed) {
                            Icon(HripsIcons.Image, null, Modifier.size(40.dp), tint = cs.onSurfaceVariant)
                        } else {
                            LoadingIndicator(Modifier.size(40.dp))
                        }
                    }

                    Spacer(Modifier.height(gap))

                    Box(
                        Modifier
                            .padding(start = menuStart)
                            .width(menuW)
                            .graphicsLayer {
                                // Меню включается чуть позже превью, когда оно уже почти доехало.
                                val m = ((progress.value - 0.25f) / 0.75f)
                                val mm = m.coerceIn(0f, 1f)
                                transformOrigin = TransformOrigin(0.1f, 0f)
                                val sc = 0.86f + 0.14f * m.coerceIn(0f, 1.1f)
                                scaleX = sc
                                scaleY = sc
                                alpha = mm
                                translationY = -(1f - mm) * 28.dp.toPx()
                                shadowElevation = 14.dp.toPx() * mm
                                shape = RoundedCornerShape(22.dp)
                                clip = true
                            }
                            .background(cs.surfaceContainerHigh.copy(alpha = 0.96f)),
                    ) {
                        Column(Modifier.heightIn(max = menuMaxH).verticalScroll(rememberScrollState())) {
                            linkActs.forEachIndexed { i, a ->
                                MenuRow(a, divider = i < linkActs.lastIndex) { a.run(); close() }
                            }
                            if (groupGap > 0.dp) {
                                Box(Modifier.fillMaxWidth().height(groupGap).background(cs.onSurface.copy(alpha = 0.08f)))
                            }
                            mediaActs.forEachIndexed { i, a ->
                                MenuRow(a, divider = i < mediaActs.lastIndex) { a.run(); close() }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuRow(act: MenuAct, divider: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 20.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                act.label,
                Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Normal),
            )
            Spacer(Modifier.width(16.dp))
            Icon(act.icon, null, Modifier.size(22.dp), tint = cs.onSurface)
        }
        if (divider) Box(Modifier.fillMaxWidth().height(0.5.dp).background(cs.onSurface.copy(alpha = 0.12f)))
    }
}

/**
 * Размытие всего, что за диалогом. Радиус растёт ступенями вместе с анимацией открытия (шесть обновлений
 * окна вместо покадровых). Без поддержки блюра остаётся только затемнение из самого меню.
 */
@Composable
private fun WindowBlur(progress: Animatable<Float, AnimationVector1D>) {
    val view = LocalView.current
    LaunchedEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@LaunchedEffect
        window.setDimAmount(0f)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return@LaunchedEffect
        val wm = view.context.getSystemService(WindowManager::class.java)
        if (!runCatching { wm.isCrossWindowBlurEnabled }.getOrDefault(false)) return@LaunchedEffect
        window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        snapshotFlow { (progress.value.coerceIn(0f, 1f) * 6f).toInt() }
            .distinctUntilChanged()
            .collect { step ->
                runCatching {
                    val attrs = window.attributes
                    attrs.blurBehindRadius = step * 6
                    window.attributes = attrs
                }
            }
    }
}
