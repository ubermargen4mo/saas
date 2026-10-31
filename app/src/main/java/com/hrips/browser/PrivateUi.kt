@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.hrips.browser

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.foundation.background
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.draw.clip
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.mozilla.geckoview.BasicSelectionActionDelegate
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.PanZoomController
import kotlin.math.abs
import kotlin.math.max

class HripsGeckoView(context: Context) : GeckoView(context) {
    var incognito = false

    var onSearchText: ((String) -> Unit)? = null

    var pullEnabled = false
    var onPull: ((Float) -> Unit)? = null
    var onPullRelease: ((Float) -> Unit)? = null

    private val selection: BasicSelectionActionDelegate? = context.findActivity()?.let { activity ->
        HripsSelectionDelegate(activity) { text -> onSearchText?.invoke(text) }.also { it.enableExternalActions(true) }
    }

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var downId = 0
    private var pullAllowed = false
    private var pulling = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Каждое изменение размера заставляет главный поток синхронно ждать compositor (syncResumeResizeCompositor),
        // поэтому в журнале видно, сколько раз размер меняется, например при сворачивании панели.
        AppLog.i("Gecko") { "размер страницы ${oldw}x$oldh -> ${w}x$h" }
    }

    override fun setSession(session: GeckoSession) {
        super.setSession(session)
        selection?.let { session.selectionActionDelegate = it }
    }

    fun release() {
        val released = releaseSession() ?: return
        if (selection != null && released.selectionActionDelegate === selection) {
            released.selectionActionDelegate = null
        }
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val connection = super.onCreateInputConnection(outAttrs)
        if (incognito) outAttrs.imeOptions = outAttrs.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        return connection
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                pulling = false
                pullAllowed = false
                if (pullEnabled && session != null) {
                    val id = ++downId
                    onTouchEventForDetailResult(event).accept({ detail ->
                        if (detail != null && id == downId) {
                            val atTop = (detail.scrollableDirections() and PanZoomController.SCROLLABLE_FLAG_TOP) == 0
                            val result = detail.handledResult()
                            val free = result == PanZoomController.INPUT_RESULT_HANDLED ||
                                result == PanZoomController.INPUT_RESULT_UNHANDLED
                            pullAllowed = atTop && free
                        }
                    }, { })
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (pulling) {
                    onPull?.invoke(max(0f, event.y - downY))
                    return true
                }
                if (pullAllowed && event.pointerCount == 1) {
                    val dy = event.y - downY
                    val dx = abs(event.x - downX)
                    if (dy > slop * 2 && dy > dx * 1.5f) {
                        pulling = true
                        val cancel = MotionEvent.obtain(event)
                        cancel.action = MotionEvent.ACTION_CANCEL
                        super.onTouchEvent(cancel)
                        cancel.recycle()
                        onPull?.invoke(dy)
                        return true
                    }
                    if (dy < -slop) pullAllowed = false
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (pulling) {
                    val distance = if (event.actionMasked == MotionEvent.ACTION_UP) max(0f, event.y - downY) else 0f
                    pulling = false
                    pullAllowed = false
                    onPull?.invoke(0f)
                    onPullRelease?.invoke(distance)
                    return true
                }
                pullAllowed = false
            }
        }
        return super.onTouchEvent(event)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Главная приватных вкладок: значок, заголовок в одну строку и три коротких пункта в сегментной группе
 * (что стирается, что остаётся, что не скрывается). Без цветных заголовков и сплошного текста: один акцент (primary),
 * остальное нейтральное. Содержимое по центру экрана; на низких экранах и с крупным шрифтом страница прокручивается.
 */
@Composable
fun PrivateStartPage(
    privateCount: Int,
    screenshotsBlocked: Boolean,
    onSearch: () -> Unit,
    onCloseAll: () -> Unit,
    modifier: Modifier = Modifier,
    engine: SearchEngine? = null,
    onPickEngine: ((SearchEngine) -> Unit)? = null,
    showSearch: Boolean = true,
) {
    val cs = MaterialTheme.colorScheme
    // Телефон: снизу лежит плавающая панель, её место остаётся свободным, чтобы центр страницы не уезжал под неё.
    val bottomSpace = if (isWideWindow()) 0.dp else
        HripsLayout.phoneIslandSpace(HripsLayout.phoneBarButton(LocalConfiguration.current.screenWidthDp)) +
            WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    BoxWithConstraints(modifier.fillMaxSize().background(cs.surface)) {
        val viewport = maxHeight
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier
                    .widthIn(max = 440.dp)
                    .fillMaxWidth()
                    .heightIn(min = viewport)
                    .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 24.dp + bottomSpace),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                ShapeBadge(HripsIcons.Mask, 72.dp, badge = Badge.CLOVER, container = cs.primaryContainer, content = cs.onPrimaryContainer)
                Text(
                    "Приватная вкладка",
                    Modifier.padding(top = 20.dp),
                    style = MaterialTheme.typography.headlineSmall,
                    color = cs.onSurface,
                    textAlign = TextAlign.Center,
                )
                Text(
                    "История не сохраняется",
                    Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )

                if (showSearch) {
                    val searchHeight = 56.dp * androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceIn(1f, 1.6f)
                    Surface(
                        onClick = onSearch,
                        shape = CircleShape,
                        color = cs.surfaceContainerHigh,
                        modifier = Modifier.padding(top = 24.dp).fillMaxWidth().height(searchHeight).originAnchor("search"),
                    ) {
                        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                            val picker = engine != null && onPickEngine != null
                            if (engine != null && onPickEngine != null) {
                                Box(Modifier.padding(start = 6.dp)) { EnginePicker(engine, onPickEngine) }
                            } else {
                                Box(Modifier.width(56.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                                    Icon(HripsIcons.Search, null, Modifier.size(24.dp), tint = cs.onSurfaceVariant)
                                }
                            }
                            Text(
                                "Искать или задать вопрос",
                                modifier = Modifier.weight(1f).padding(start = if (picker) 10.dp else 0.dp, end = 16.dp),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }

                val facts = listOf(
                    Triple(
                        HripsIcons.Trash, "Не сохраняется",
                        "История, запросы, cookies, кэш и данные сайтов стираются, когда закрывается последняя приватная вкладка.",
                    ),
                    Triple(
                        HripsIcons.Star, "Остаётся",
                        "Закладки и скачанные файлы. Их имена в шторке уведомлений скрыты.",
                    ),
                    Triple(
                        HripsIcons.Globe, "Не скрывается",
                        "Провайдер, работодатель, VPN-сервис и сами сайты видят ваш IP-адрес. Режим убирает следы только на этом устройстве.",
                    ),
                )
                Column(Modifier.padding(top = 32.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    facts.forEachIndexed { i, (icon, title, text) -> Fact(icon, title, text, HripsShapes.segment(i, facts.size)) }
                }

                if (screenshotsBlocked) {
                    Text(
                        "Скриншоты и миниатюра в списке приложений отключены",
                        Modifier.padding(top = 20.dp, start = 8.dp, end = 8.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = cs.onSurfaceVariant.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center,
                    )
                }

                if (privateCount > 0) {
                    TextButton(
                        onClick = onCloseAll,
                        modifier = Modifier.padding(top = 12.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = cs.error),
                    ) { Text("Закрыть приватные вкладки ($privateCount)") }
                }
            }
        }
    }
}

/** Пункт группы: значок в мягком круге слева, справа короткий заголовок и пояснение. */
@Composable
private fun Fact(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, text: String, shape: androidx.compose.ui.graphics.Shape) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(shape).background(cs.surfaceContainer).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier.size(36.dp).background(cs.primary.copy(alpha = 0.14f), CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, Modifier.size(20.dp), tint = cs.primary) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
            Spacer(Modifier.height(2.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
    }
}
