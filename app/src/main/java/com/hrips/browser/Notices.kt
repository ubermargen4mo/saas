@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.hrips.browser

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/*
 * Единые уведомления приложения. Вместо системных Toast (их нельзя оформить, они ставятся в очередь и не умеют
 * действий) один снекбар: тип (успех / сведение / ошибка), значок на фигуре, кнопка действия («Открыть»,
 * «Вернуть»), свайп для скрытия, хаптика. Новое уведомление заменяет предыдущее, а не встаёт в очередь.
 *
 * Вызывать можно откуда угодно и с любого потока: Notices.show("Скопировано").
 * Если окно с хостом [NoticeHost] сейчас не на экране (например, приложение в фоне), покажется обычный Toast.
 */

enum class NoticeKind { INFO, SUCCESS, ERROR }

class Notice(
    val id: Long,
    val text: String,
    val kind: NoticeKind,
    val actionLabel: String?,
    val onAction: (() -> Unit)?,
    val durationMs: Long,
)

object Notices {
    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var hosts = 0
    private var seq = 0L

    /** Что показано сейчас. Меняется только в главном потоке. */
    var current by mutableStateOf<Notice?>(null)
        private set

    fun init(context: Context) {
        app = context.applicationContext
    }

    internal fun attach() { hosts++ }

    internal fun detach() { hosts = max(0, hosts - 1) }

    fun show(
        text: String,
        kind: NoticeKind = kindOf(text),
        actionLabel: String? = null,
        durationMs: Long = defaultDuration(text, actionLabel != null),
        onAction: (() -> Unit)? = null,
    ) {
        main.post {
            if (hosts == 0) {
                app?.let { Toast.makeText(it, text, if (durationMs > 4500) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show() }
                return@post
            }
            current = Notice(++seq, text, kind, actionLabel, onAction, durationMs)
        }
    }

    /** Короткая запись с действием: `Notices.show("Вкладка закрыта", "Вернуть") { ... }`. */
    fun show(text: String, actionLabel: String, onAction: () -> Unit) =
        show(text, kindOf(text), actionLabel, defaultDuration(text, true), onAction)

    fun dismiss(id: Long) {
        if (current?.id == id) current = null
    }

    /** Время показа по длине текста: длинное успеть прочитать, с действием не короче 5 с. */
    fun defaultDuration(text: String, hasAction: Boolean): Long {
        val base = min(8000L, 2200L + text.length * 55L)
        return if (hasAction) max(5000L, base) else max(3200L, base)
    }

    /** Тип по тексту, чтобы старые вызовы с одной строкой получали правильный вид без правок. */
    fun kindOf(text: String): NoticeKind {
        val t = text.lowercase()
        return when {
            t.startsWith("не удалось") || t.contains("не началась") || t.contains("слишком много") ||
                t.contains("занято") || t.startsWith("нет приложения") || t.contains("не найден") ||
                t.contains("нечего") || t.contains("не поддерживает") || t.startsWith("для этого сайта нет") -> NoticeKind.ERROR
            t.contains("скопирован") || t.startsWith("добавлено") || t.contains("очищен") ||
                t.startsWith("загружено") || t.contains("завершена") || t.contains("сохранён") ||
                t.contains("изменён") || t.startsWith("удалено") || t.contains("закрыта") || t.startsWith("закрыто") -> NoticeKind.SUCCESS
            else -> NoticeKind.INFO
        }
    }
}

/** Добавляет или убирает закладку и сообщает об этом с возможностью отменить. */
fun toggleBookmarkWithNotice(store: Store, url: String, title: String) {
    val was = store.isBookmarked(url)
    store.toggleBookmark(url, title)
    if (store.isBookmarked(url) == was) return // служебная страница, ничего не изменилось
    Notices.show(
        if (was) "Закладка удалена" else "Добавлено в закладки",
        NoticeKind.SUCCESS,
        actionLabel = "Отменить",
        onAction = { store.toggleBookmark(url, title) },
    )
}

/**
 * Место показа. Ставится один раз в корне экрана браузера выше всех оверлеев; снизу он сам отступает от
 * системных панелей и клавиатуры, нижний отступ над панелью браузера задаёт вызывающий через [modifier].
 */
@Composable
fun NoticeHost(modifier: Modifier = Modifier) {
    DisposableEffect(Unit) {
        Notices.attach()
        onDispose { Notices.detach() }
    }
    val notice = Notices.current
    val haptic = LocalHapticFeedback.current
    val motion = MaterialTheme.motionScheme
    val slideIn = motion.defaultSpatialSpec<IntOffset>()
    val slideOut = motion.fastSpatialSpec<IntOffset>()
    val scaleSpec = motion.defaultSpatialSpec<Float>()
    val fadeInSpec = motion.defaultEffectsSpec<Float>()
    val fadeOutSpec = motion.fastEffectsSpec<Float>()

    LaunchedEffect(notice?.id) {
        val n = notice ?: return@LaunchedEffect
        when (n.kind) {
            NoticeKind.ERROR -> haptic.performHapticFeedback(HapticFeedbackType.Reject)
            NoticeKind.SUCCESS -> haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            NoticeKind.INFO -> {}
        }
        delay(n.durationMs)
        Notices.dismiss(n.id)
    }

    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        AnimatedContent(
            targetState = notice,
            contentKey = { it?.id },
            transitionSpec = {
                (fadeIn(fadeInSpec) + slideInVertically(slideIn) { it / 2 } + scaleIn(scaleSpec, initialScale = 0.92f)) togetherWith
                    (fadeOut(fadeOutSpec) + slideOutVertically(slideOut) { it / 2 }) using SizeTransform(clip = false)
            },
            contentAlignment = Alignment.BottomCenter,
            label = "notice",
        ) { n ->
            if (n != null) NoticeCard(n) else Spacer(Modifier.height(0.dp))
        }
    }
}

@Composable
private fun NoticeCard(n: Notice) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val offsetX = remember(n.id) { Animatable(0f) }
    val dismissPx = with(LocalDensity.current) { 96.dp.toPx() }
    val settle = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val fly = MaterialTheme.motionScheme.fastSpatialSpec<Float>()

    val (icon, badge) = when (n.kind) {
        NoticeKind.SUCCESS -> HripsIcons.Check to Badge.COOKIE6
        NoticeKind.ERROR -> HripsIcons.Alert to Badge.BURST
        NoticeKind.INFO -> HripsIcons.Info to Badge.COOKIE9
    }
    val badgeContainer = when (n.kind) {
        NoticeKind.SUCCESS -> cs.inversePrimary
        NoticeKind.ERROR -> cs.error
        NoticeKind.INFO -> cs.inverseOnSurface.copy(alpha = 0.16f)
    }
    val badgeContent = when (n.kind) {
        NoticeKind.SUCCESS -> cs.inverseSurface
        NoticeKind.ERROR -> cs.onError
        NoticeKind.INFO -> cs.inverseOnSurface
    }

    Surface(
        shape = RoundedCornerShape(28.dp),
        color = cs.inverseSurface,
        contentColor = cs.inverseOnSurface,
        shadowElevation = 6.dp,
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite }
            .graphicsLayer {
                translationX = offsetX.value
                alpha = 1f - (abs(offsetX.value) / (dismissPx * 2.5f)).coerceIn(0f, 0.8f)
            }
            .pointerInput(n.id) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        scope.launch {
                            if (abs(offsetX.value) > dismissPx) {
                                offsetX.animateTo(sign(offsetX.value) * dismissPx * 4, fly)
                                Notices.dismiss(n.id)
                            } else {
                                offsetX.animateTo(0f, settle)
                            }
                        }
                    },
                    onDragCancel = { scope.launch { offsetX.animateTo(0f, settle) } },
                ) { change, dx ->
                    change.consume()
                    scope.launch { offsetX.snapTo(offsetX.value + dx) }
                }
            },
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = if (n.actionLabel != null) 6.dp else 18.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ShapeBadge(icon, 36.dp, badge = badge, container = badgeContainer, content = badgeContent)
            Text(
                n.text,
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (n.actionLabel != null) {
                TextButton(
                    onClick = {
                        // Сначала убираем снекбар, потом выполняем действие: оно может показать своё уведомление
                        Notices.dismiss(n.id)
                        n.onAction?.invoke()
                    },
                    shapes = ButtonDefaults.shapes(),
                    colors = ButtonDefaults.textButtonColors(contentColor = cs.inversePrimary),
                ) { Text(n.actionLabel) }
            }
        }
    }
}
