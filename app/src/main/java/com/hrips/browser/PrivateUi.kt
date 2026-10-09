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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.mozilla.geckoview.BasicSelectionActionDelegate
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.PanZoomController
import kotlin.math.abs
import kotlin.math.max

/**
 * GeckoView приложения:
 *  - в приватной вкладке просит клавиатуру не обучаться на том, что вы печатаете (IME_FLAG_NO_PERSONALIZED_LEARNING);
 *  - показывает меню над выделенным текстом (копировать, вставить, искать);
 *  - понимает жест «потяните вниз, чтобы обновить», когда страница прокручена к самому верху.
 */
class HripsGeckoView(context: Context) : GeckoView(context) {
    var incognito = false

    /** Нажали «Искать» в меню выделенного текста */
    var onSearchText: ((String) -> Unit)? = null

    var pullEnabled = false
    /** Сколько пикселей оттянули (0 - жест закончился) */
    var onPull: ((Float) -> Unit)? = null
    /** Пальцы отпустили: расстояние в пикселях (0, если жест отменён) */
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

    override fun setSession(session: GeckoSession) {
        super.setSession(session)
        selection?.let { session.selectionActionDelegate = it }
    }

    /**
     * Отвязывает сессию от вида и снимает наш делегат выделения. Делегат держит Activity, а сессия вкладки
     * живёт дольше Activity, поэтому без этого фоновые вкладки удерживали бы уничтоженную Activity.
     */
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
                    // Движок сам решает, прокручивается ли страница вверх под пальцем. Если нет, мы наверху.
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
                        // Страница больше не получает этот жест: говорим движку, что касание отменено
                        val cancel = MotionEvent.obtain(event)
                        cancel.action = MotionEvent.ACTION_CANCEL
                        super.onTouchEvent(cancel)
                        cancel.recycle()
                        onPull?.invoke(dy)
                        return true
                    }
                    if (dy < -slop) pullAllowed = false // пользователь листает страницу вниз
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

/** Стартовая страница приватной вкладки: честно говорит, что скрывается, а что нет. */
@Composable
fun PrivateStartPage(
    privateCount: Int,
    screenshotsBlocked: Boolean,
    onSearch: () -> Unit,
    onCloseAll: () -> Unit,
    modifier: Modifier = Modifier,
    engine: SearchEngine? = null,
    onPickEngine: ((SearchEngine) -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    Box(modifier.fillMaxSize().background(cs.surface), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(
                start = 24.dp,
                top = 40.dp,
                end = 24.dp,
                // Телефон: внизу плавающая панель лежит поверх страницы
                bottom = 40.dp + if (isWideWindow()) 0.dp else 64.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
            ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(HripsIcons.Mask, null, Modifier.size(72.dp), tint = cs.primary)
            Spacer(Modifier.height(16.dp))
            Text("Приватная вкладка", style = MaterialTheme.typography.headlineMedium, color = cs.onSurface)
            Spacer(Modifier.height(24.dp))
            Surface(
                onClick = onSearch,
                shape = CircleShape,
                color = cs.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().originAnchor("search"),
            ) {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (engine != null && onPickEngine != null) {
                        EnginePicker(engine, onPickEngine)
                    } else {
                        Icon(HripsIcons.Search, null, tint = cs.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text("Искать или задать вопрос", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                }
            }
            Spacer(Modifier.height(32.dp))

            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Note(
                    "Не сохраняется",
                    "История, поисковые запросы, cookies, кэш и данные сайтов. Всё это стирается, когда закрывается " +
                        "последняя приватная вкладка. После перезапуска приложения приватных вкладок не будет.",
                )
                Note(
                    "Остаётся",
                    "Закладки и скачанные файлы (они лежат в папке «Загрузки»). Имена файлов в шторке уведомлений скрыты.",
                )
                Note(
                    "Не скрывается",
                    "Ваш интернет-провайдер, работодатель или VPN-сервис и сами сайты видят, что вы на них заходите. " +
                        "Режим убирает следы на этом устройстве, но не ваш IP-адрес.",
                )
                if (screenshotsBlocked) {
                    Note("Экран", "Скриншоты и миниатюра в списке приложений отключены. Это можно изменить в настройках.")
                }
            }

            if (privateCount > 0) {
                Spacer(Modifier.height(28.dp))
                FilledTonalButton(shapes = ButtonDefaults.shapes(), onClick = onCloseAll) { Text("Закрыть приватные вкладки ($privateCount)") }
            }
        }
    }
}

@Composable
private fun Note(title: String, text: String) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(4.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
