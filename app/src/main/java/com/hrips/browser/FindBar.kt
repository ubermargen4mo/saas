@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.hrips.browser

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Состояние поиска по странице: запрос, номер совпадения и их число. Общее для строки телефона и планшета. */
@Stable
internal class FindState(private val tab: Tab) {
    var query by mutableStateOf("")
    var current by mutableIntStateOf(0)
    var total by mutableIntStateOf(0)
    var found by mutableStateOf(true)

    fun run(text: String, backwards: Boolean) {
        if (text.isEmpty()) {
            tab.finderClear()
            current = 0
            total = 0
            found = true
            return
        }
        val flags = tab.finderFlags(backwards)
        tab.finderFind(text, flags).accept({ r ->
            if (r != null) {
                current = r.current
                total = r.total
                found = r.found
            }
        }, { })
    }

    fun edit(text: String) {
        query = text
        run(text, false)
    }
}

/** Строка поиска для планшета: сверху под шапкой. На телефоне вместо неё [PhoneFindBar]. */
@Composable
fun FindBar(tab: Tab, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val state = remember(tab.id) { FindState(tab) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(tab.id, tab.session) {
        tab.finderSetHighlights()
        focus.requestFocus()
    }
    DisposableEffect(tab.session) { onDispose { tab.finderClear() } }
    BackHandler { onClose() }

    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = state.query,
                onValueChange = { state.edit(it) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = if (state.found) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { state.run(state.query, false) }),
                modifier = Modifier.weight(1f).padding(start = 20.dp, top = 12.dp, bottom = 12.dp).focusRequester(focus),
                decorationBox = { inner ->
                    Box {
                        if (state.query.isEmpty()) {
                            Text(
                                "Найти на странице",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        inner()
                    }
                },
            )
            if (state.query.isNotEmpty()) {
                Text(
                    when {
                        !state.found -> "0"
                        state.total > 0 -> "${state.current}/${state.total}"
                        else -> "…"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            IconButton(onClick = { state.run(state.query, true) }, enabled = state.query.isNotEmpty()) {
                Icon(HripsIcons.ChevronDown, "Предыдущее", Modifier.rotate(180f))
            }
            IconButton(onClick = { state.run(state.query, false) }, enabled = state.query.isNotEmpty()) {
                Icon(HripsIcons.ChevronDown, "Следующее")
            }
            IconButton(onClick = onClose) { Icon(HripsIcons.Close, "Закрыть поиск") }
        }
    }
}

/**
 * Появление части строки: у каждой своя небольшая задержка, поэтому строка «собирается» слева направо, а не выезжает куском.
 * Всё читается внутри graphicsLayer (без композиции за кадр); пружина даёт лёгкий перелёт на входе.
 */
private fun Modifier.findPart(
    p: Animatable<Float, AnimationVector1D>,
    delayFrac: Float,
    rise: Float,
    fromScale: Float,
    press: PillPress? = null,
): Modifier = graphicsLayer {
    val v = ((p.value - delayFrac) / (1f - delayFrac)).coerceAtLeast(0f)
    alpha = (v * 1.5f).coerceIn(0f, 1f)
    translationY = (1f - v) * rise
    val k = fromScale + (1f - fromScale) * v
    val s = k * (press?.scale?.value ?: 1f)
    scaleX = s
    scaleY = s
}

/**
 * Поиск по странице на телефоне (как в Safari): над клавиатурой в том же месте, где стоит нижняя панель.
 * Слева круглая кнопка «готово», в середине поле с обычной лупой и счётчиком «1 из 2», справа пара стрелок.
 * Нижняя панель при этом остаётся над ней в свёрнутом виде (домен), клавиатура накрывает страницу сверху.
 *
 * Окно живёт на время анимации закрытия: видимость и монтирование разведены.
 */
@Composable
internal fun PhoneFindBar(tab: Tab, visible: Boolean, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(visible) {
        progress.animateTo(if (visible) 1f else 0f, if (visible) HripsMotion.findIn() else HripsMotion.findOut())
    }
    val visibleNow by rememberUpdatedState(visible)
    val mounted by remember { derivedStateOf { visibleNow || progress.value > 0.001f } }
    if (!mounted) return

    val cs = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val keyboard = LocalSoftwareKeyboardController.current
    val state = remember(tab.id) { FindState(tab) }
    val focus = remember { FocusRequester() }
    val palette = rememberBarPalette()
    val rise = with(density) { 28.dp.toPx() }
    val leftPress = remember { PillPress() }
    val rightPress = remember { PillPress() }

    LaunchedEffect(tab.id, tab.session) {
        tab.finderSetHighlights()
        focus.requestFocus()
        keyboard?.show()
    }
    // Подсветка совпадений снимается сразу при закрытии, не дожидаясь конца анимации.
    LaunchedEffect(visible) { if (!visible) tab.finderClear() }
    DisposableEffect(tab.session) { onDispose { tab.finderClear() } }
    val close: () -> Unit = {
        keyboard?.hide()
        onClose()
    }
    BackHandler(enabled = visible) { close() }

    val active = state.query.isNotEmpty() && state.found

    Row(
        modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
            .padding(start = HripsLayout.BarMargin, end = HripsLayout.BarMargin, bottom = HripsLayout.BarGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Готово: яркая круглая кнопка, выскакивает первой.
        Surface(
            shape = CircleShape,
            color = cs.primary,
            contentColor = cs.onPrimary,
            shadowElevation = 6.dp,
            modifier = Modifier.size(HripsLayout.BarHeight).findPart(progress, 0f, rise, 0.5f, leftPress),
        ) {
            CompositionLocalProvider(LocalPillPress provides leftPress) {
                BarButton(onClick = close) {
                    Icon(HripsIcons.BarCheck, "Готово", Modifier.size(24.dp))
                }
            }
        }
        Spacer(Modifier.width(8.dp))

        BarPill(
            palette,
            pressHost = false,
            modifier = Modifier
                .weight(1f)
                .height(HripsLayout.BarHeight)
                .findPart(progress, 0.06f, rise, 0.9f),
        ) {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Icon(HripsIcons.Search, null, Modifier.padding(start = 14.dp).size(22.dp), tint = cs.onSurfaceVariant)
                BasicTextField(
                    value = state.query,
                    onValueChange = { state.edit(it) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = if (state.found) cs.onSurface else cs.error,
                        fontWeight = FontWeight.Medium,
                    ),
                    cursorBrush = SolidColor(cs.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { state.run(state.query, false) }),
                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp).focusRequester(focus),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (state.query.isEmpty()) {
                                Text(
                                    "Найти на странице",
                                    color = cs.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                    maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            inner()
                        }
                    },
                )
                val counter = when {
                    state.query.isEmpty() -> ""
                    !state.found -> "Нет"
                    state.total > 0 -> "${state.current} из ${state.total}"
                    else -> "…"
                }
                AnimatedVisibility(
                    visible = counter.isNotEmpty(),
                    enter = fadeIn(tween(120)),
                    exit = fadeOut(tween(90)),
                ) {
                    AnimatedContent(
                        targetState = counter,
                        transitionSpec = {
                            (fadeIn(tween(130, delayMillis = 30)) + slideInVertically(tween(170)) { it / 3 }) togetherWith
                                (fadeOut(tween(70)) + slideOutVertically(tween(130)) { -it / 3 }) using
                                SizeTransform(clip = false) { _, _ -> snap() }
                        },
                        contentAlignment = Alignment.Center,
                        label = "findCount",
                    ) { text ->
                        Text(
                            text,
                            maxLines = 1,
                            softWrap = false,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (state.found) cs.onSurfaceVariant else cs.error,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
                AnimatedVisibility(
                    visible = state.query.isNotEmpty(),
                    enter = fadeIn(tween(120)) + scaleIn(spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium), initialScale = 0.4f),
                    exit = fadeOut(tween(90)) + scaleOut(tween(100), targetScale = 0.4f),
                ) {
                    BarButton(onClick = { state.edit("") }, modifier = Modifier.size(40.dp)) {
                        Box(
                            Modifier.size(22.dp).clip(CircleShape).background(cs.onSurface.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center,
                        ) { Icon(HripsIcons.BarClose, "Очистить", Modifier.size(12.dp)) }
                    }
                }
                Spacer(Modifier.width(4.dp))
            }
        }
        Spacer(Modifier.width(8.dp))

        BarPill(
            palette,
            modifier = Modifier
                .height(HripsLayout.BarHeight)
                .findPart(progress, 0.12f, rise, 0.7f),
        ) {
            Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                BarButton(
                    onClick = { state.run(state.query, true) },
                    enabled = active,
                    modifier = Modifier.size(width = 38.dp, height = 44.dp),
                ) { Icon(HripsIcons.BarChevronUp, "Предыдущее", Modifier.size(24.dp)) }
                BarButton(
                    onClick = { state.run(state.query, false) },
                    enabled = active,
                    modifier = Modifier.size(width = 38.dp, height = 44.dp),
                ) { Icon(HripsIcons.BarChevronDown, "Следующее", Modifier.size(24.dp)) }
            }
        }
    }
}
