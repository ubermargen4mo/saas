package com.hrips.browser

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

// ───────────────────────── Типы файлов ─────────────────────────

enum class FileKind(val label: String) {
    IMAGE("Изображения"), VIDEO("Видео"), AUDIO("Аудио"), PDF("PDF"),
    APK("Приложения"), ARCHIVE("Архивы"), DOC("Документы"), OTHER("Другое"),
}

private val archiveExt = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "tgz", "zst")
private val docExt = setOf("doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "rtf", "odt", "ods", "odp", "csv", "md", "json", "xml", "html", "epub", "fb2")

fun fileKind(name: String, mime: String): FileKind {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when {
        ext in setOf("apk", "apks", "xapk", "aab") || mime == "application/vnd.android.package-archive" -> FileKind.APK
        mime.startsWith("image/") -> FileKind.IMAGE
        mime.startsWith("video/") -> FileKind.VIDEO
        mime.startsWith("audio/") -> FileKind.AUDIO
        ext == "pdf" || mime == "application/pdf" -> FileKind.PDF
        ext in archiveExt || mime == "application/zip" || mime.contains("compressed") -> FileKind.ARCHIVE
        ext in docExt || mime.startsWith("text/") -> FileKind.DOC
        else -> FileKind.OTHER
    }
}

fun fileKind(d: DownloadItem) = fileKind(d.name, d.mime)

fun kindIcon(kind: FileKind): ImageVector = when (kind) {
    FileKind.IMAGE -> HripsIcons.Image
    FileKind.VIDEO -> HripsIcons.Video
    FileKind.AUDIO -> HripsIcons.Audio
    FileKind.PDF -> HripsIcons.FilePdf
    FileKind.APK -> HripsIcons.Package
    FileKind.ARCHIVE -> HripsIcons.Archive
    FileKind.DOC -> HripsIcons.FileDoc
    FileKind.OTHER -> HripsIcons.File
}

@Composable
private fun kindColors(kind: FileKind): Pair<Color, Color> {
    val c = MaterialTheme.colorScheme
    return when (kind) {
        FileKind.IMAGE -> c.tertiaryContainer to c.onTertiaryContainer
        FileKind.VIDEO -> c.secondaryContainer to c.onSecondaryContainer
        FileKind.AUDIO -> c.primaryContainer to c.onPrimaryContainer
        FileKind.PDF -> c.errorContainer to c.onErrorContainer
        FileKind.APK -> c.primary to c.onPrimary
        FileKind.ARCHIVE -> c.secondary to c.onSecondary
        FileKind.DOC -> c.tertiary to c.onTertiary
        FileKind.OTHER -> c.surfaceContainerHighest to c.onSurfaceVariant
    }
}

/** Иконка типа файла в фигуре Material 3 Expressive (у каждого типа своя форма). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun KindBadge(kind: FileKind, size: Dp, modifier: Modifier = Modifier) {
    val (bg, fg) = kindColors(kind)
    val polygon = remember(kind) {
        when (kind) {
            FileKind.IMAGE -> MaterialShapes.Cookie9Sided
            FileKind.VIDEO -> MaterialShapes.Clover4Leaf
            FileKind.AUDIO -> MaterialShapes.Sunny
            FileKind.PDF -> MaterialShapes.Pentagon
            FileKind.APK -> MaterialShapes.Cookie6Sided
            FileKind.ARCHIVE -> MaterialShapes.SoftBurst
            FileKind.DOC -> MaterialShapes.Cookie12Sided
            FileKind.OTHER -> MaterialShapes.Flower
        }
    }
    val shape = polygon.toShape()
    Box(modifier.size(size).background(bg, shape), contentAlignment = Alignment.Center) {
        Icon(kindIcon(kind), null, Modifier.size(size * 0.46f), tint = fg)
    }
}

// ───────────────────────── Состояние анимации ─────────────────────────

class DownloadFlight(val kind: FileKind, val from: Offset, val to: Offset)

/**
 * Связывает плашку подтверждения и кнопку загрузок: кнопка сообщает, где она находится ([target]),
 * плашка при нажатии "Загрузить" запускает полёт ([launch]), по прилёту ([land]) кнопка "подпрыгивает",
 * а вокруг неё появляется кольцо прогресса. Координаты в корне Compose, поэтому у всех общие.
 */
class DownloadFx {
    var target by mutableStateOf<Offset?>(null)
    var flight by mutableStateOf<DownloadFlight?>(null)
    var pulse by mutableIntStateOf(0)

    fun launch(kind: FileKind, from: Offset?) {
        val to = target
        if (from == null || to == null) { pulse++; return } // некуда лететь: сразу показываем кольцо
        flight = DownloadFlight(kind, from, to)
    }

    fun land() {
        flight = null
        pulse++
    }
}

// ───────────────────────── Кнопка загрузок с кольцом ─────────────────────────

/**
 * Кнопка с кольцом прогресса вокруг иконки. Кольцо появляется, когда иконка долетела,
 * показывает общий прогресс (или крутится, если размер неизвестен) и после завершения на мгновение замыкается.
 */
@Composable
fun DownloadsButton(
    downloads: Downloads,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector = HripsIcons.Download,
    description: String = "Загрузки",
    size: Dp = 48.dp,
) {
    val fx = downloads.fx
    val busy = downloads.items.any { downloads.isActive(it) }
    var linger by remember { mutableStateOf(false) }
    var wasBusy by remember { mutableStateOf(false) }
    LaunchedEffect(busy) {
        if (busy) wasBusy = true
        else if (wasBusy) {
            wasBusy = false
            linger = true
            delay(700)
            linger = false
        }
    }
    val ringOn = (busy || linger) && fx.flight == null
    val ringScale by animateFloatAsState(
        if (ringOn) 1f else 0.6f,
        spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMediumLow),
        label = "ringScale",
    )
    val ringAlpha by animateFloatAsState(if (ringOn) 1f else 0f, tween(220), label = "ringAlpha")

    val pulse = remember { Animatable(1f) }
    LaunchedEffect(fx.pulse) {
        if (fx.pulse > 0) {
            pulse.snapTo(1f)
            pulse.animateTo(1.28f, tween(110))
            pulse.animateTo(1f, spring(dampingRatio = 0.3f, stiffness = Spring.StiffnessLow))
        }
    }

    // Округляем до 1%, чтобы кнопка не перерисовывалась на каждый принятый блок данных
    val progress by remember(downloads) { derivedStateOf { (downloads.aggregateProgress() * 100).toInt() / 100f } }

    DisposableEffect(fx) { onDispose { fx.target = null } }

    Box(
        modifier
            .size(size)
            .onGloballyPositioned {
                val c = it.boundsInRoot().center
                if (fx.target != c) fx.target = c
            }
            .graphicsLayer { scaleX = pulse.value; scaleY = pulse.value },
        contentAlignment = Alignment.Center,
    ) {
        if (ringAlpha > 0.01f) {
            DownloadRing(
                progress = progress,
                modifier = Modifier
                    .size(size - 6.dp)
                    .graphicsLayer { scaleX = ringScale; scaleY = ringScale; alpha = ringAlpha },
            )
        }
        IconButton(onClick = onClick, modifier = Modifier.size(size)) { Icon(icon, description) }
    }
}

@Composable
private fun DownloadRing(progress: Float, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    val shown by animateFloatAsState(progress.coerceAtLeast(0f), tween(350), label = "ringProgress")
    val spin = rememberInfiniteTransition(label = "ringSpin")
    val rotation by spin.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart),
        label = "rot",
    )
    Canvas(modifier) {
        val w = 3.dp.toPx()
        val topLeft = Offset(w / 2, w / 2)
        val arc = Size(size.width - w, size.height - w)
        drawArc(track, 0f, 360f, false, topLeft, arc, style = Stroke(w))
        if (progress < 0f) {
            drawArc(color, rotation, 110f, false, topLeft, arc, style = Stroke(w, cap = StrokeCap.Round))
        } else {
            drawArc(color, -90f, 360f * shown.coerceAtLeast(0.03f), false, topLeft, arc, style = Stroke(w, cap = StrokeCap.Round))
        }
    }
}

// ───────────────────────── Полёт иконки ─────────────────────────

/** Рисуется поверх всего экрана: иконка файла по дуге летит к кнопке загрузок, уменьшаясь. */
@Composable
fun DownloadFlightOverlay(fx: DownloadFx) {
    val flight = fx.flight ?: return
    val progress = remember(flight) { Animatable(0f) }
    LaunchedEffect(flight) {
        progress.animateTo(1f, tween(720, easing = CubicBezierEasing(0.3f, 0f, 0f, 1f)))
        fx.land()
    }
    val density = LocalDensity.current
    val sizePx = with(density) { 56.dp.toPx() }
    // Опорная точка дуги: середина пути, смещённая вбок и вверх
    val d = flight.to - flight.from
    val len = d.getDistance().coerceAtLeast(1f)
    var n = Offset(-d.y / len, d.x / len)
    if (n.y > 0f) n = -n
    val control = (flight.from + flight.to) / 2f + n * (len * 0.28f)

    Box(
        Modifier
            .size(56.dp)
            .graphicsLayer {
                val t = progress.value
                val u = 1f - t
                val x = u * u * flight.from.x + 2 * u * t * control.x + t * t * flight.to.x
                val y = u * u * flight.from.y + 2 * u * t * control.y + t * t * flight.to.y
                translationX = x - sizePx / 2
                translationY = y - sizePx / 2
                val s = 1f - 0.62f * t
                scaleX = s
                scaleY = s
                rotationZ = 14f * t
                alpha = if (t < 0.82f) 1f else (1f - (t - 0.82f) / 0.18f).coerceIn(0f, 1f)
            },
    ) {
        KindBadge(flight.kind, 56.dp)
    }
}

// ───────────────────────── Плашка "Скачать файл?" ─────────────────────────

/** Нижняя плашка подтверждения загрузки: иконка типа, имя (можно изменить), размер, "Отмена" и "Загрузить". */
@Composable
fun DownloadPrompt(downloads: Downloads) {
    val current = downloads.pending.firstOrNull()
    // Держим последнюю плашку, чтобы она красиво уехала вниз, когда очередь опустела
    val held = remember { mutableStateOf<PendingDownload?>(null) }
    LaunchedEffect(current) { if (current != null) held.value = current }
    BackHandler(enabled = current != null) { current?.let { downloads.decline(it) } }

    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible = current != null, enter = fadeIn(tween(200)), exit = fadeOut(tween(250))) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        current?.let { downloads.decline(it) }
                    },
            )
        }
        AnimatedVisibility(
            visible = current != null,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow)) { it } + fadeIn(tween(150)),
            exit = slideOutVertically(tween(260)) { it } + fadeOut(tween(200)),
        ) {
            held.value?.let { PromptPanel(it, downloads) }
        }
    }
}

@Composable
private fun PromptPanel(p: PendingDownload, downloads: Downloads) {
    val fx = downloads.fx
    val context = LocalContext.current
    var name by remember(p) { mutableStateOf(p.name) }
    var iconCenter by remember { mutableStateOf<Offset?>(null) }
    val kind = fileKind(name, p.mime)

    val accept: () -> Unit = {
        val clean = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { p.name }
        fx.launch(fileKind(clean, p.mime), iconCenter)
        downloads.accept(p, clean)
    }

    Surface(
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 8.dp,
        modifier = Modifier
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .padding(12.dp)
            .imePadding()
            .navigationBarsPadding(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KindBadge(
                    kind, 56.dp,
                    Modifier
                        .onGloballyPositioned { iconCenter = it.boundsInRoot().center }
                        // Когда иконка полетела, на плашке её уже нет
                        .graphicsLayer { alpha = if (fx.flight != null) 0f else 1f },
                )
                TextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    shape = RoundedCornerShape(20.dp),
                    textStyle = MaterialTheme.typography.bodyLarge,
                    trailingIcon = { Icon(HripsIcons.Pencil, null, Modifier.size(20.dp)) },
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { accept() }),
                    modifier = Modifier.weight(1f).padding(start = 16.dp),
                )
            }
            val size = if (p.total > 0) Formatter.formatShortFileSize(context, p.total) else "размер неизвестен"
            Text(
                listOfNotNull(p.host, size, "папка «Загрузки»").joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 12.dp),
            )
            Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { downloads.decline(p) }, modifier = Modifier.weight(1f).height(56.dp)) {
                    Text("Отмена")
                }
                Button(onClick = accept, modifier = Modifier.weight(1.4f).height(56.dp)) {
                    Icon(HripsIcons.Download, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Загрузить")
                }
            }
        }
    }
}
