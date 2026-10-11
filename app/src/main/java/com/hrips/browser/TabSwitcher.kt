@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.hrips.browser

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Rect as UiRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * Снимок страницы для карточки вкладки. Делается через PixelCopy, пока страница ещё на экране
 * (GeckoView рисует в SurfaceView, обычный drawToBitmap дал бы чёрный квадрат).
 * [done] вызывается всегда, с картинкой или без: после него можно открывать переключатель.
 */
fun captureThumbnail(
    activity: Activity?,
    view: View?,
    tab: Tab,
    allowPixelCopy: Boolean = true,
    timeoutMs: Long = 1500,
    done: () -> Unit,
) {
    val gv = view as? org.mozilla.geckoview.GeckoView
    if (activity == null || gv == null || tab.home ||
        gv.width == 0 || gv.height == 0 || !gv.isShown ||
        gv.session !== tab.session
    ) {
        done(); return
    }
    // Окно закрыто от скриншотов (приватная вкладка): снимок был бы чёрным
    if ((activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0) {
        done(); return
    }
    val main = Handler(Looper.getMainLooper())
    var finished = false
    // done() вызываем ровно один раз и не позже чем через 1.5 с, чтобы переключатель вкладок всегда открывался
    val finish: () -> Unit = {
        if (!finished) { finished = true; done() }
    }
    main.postDelayed({ finish() }, timeoutMs)

    fun store(bmp: Bitmap) {
        tab.thumbnail = bmp.asImageBitmap()
        // На диск (в фоне): после перезапуска карточка сразу с картинкой. Приватные вкладки не сохраняем
        val url = tab.url
        if (!tab.isPrivate && url.isNotBlank()) {
            val app = activity.applicationContext
            AppExecutors.tryExecute { TabThumbs.save(app, url, bmp) }
        }
    }

    // Снимок движка в полный размер экрана (на планшете это ~2400x1500): уменьшаем его не в главном потоке,
    // иначе кадр открытия переключателя вкладок, на который приходится снимок, подвисал
    fun scaleAndStore(full: Bitmap) {
        val w = 720
        val h = (w * full.height.toFloat() / full.width).toInt().coerceAtLeast(1)
        val ran = AppExecutors.tryExecute {
            val scaled = if (full.width > w) Bitmap.createScaledBitmap(full, w, h, true) else full
            main.post {
                store(scaled)
                finish()
            }
        }
        if (!ran) finish()
    }

    // Запасной способ: PixelCopy окна (работает, пока страница на экране)
    fun viaPixelCopy() {
        if (finished || !allowPixelCopy || Build.VERSION.SDK_INT < 26) { finish(); return }
        try {
            val loc = IntArray(2)
            gv.getLocationInWindow(loc)
            val w = 720
            val h = (w * gv.height.toFloat() / gv.width).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val rect = Rect(loc[0], loc[1], loc[0] + gv.width, loc[1] + gv.height)
            PixelCopy.request(activity.window, rect, bmp, { result ->
                if (result == PixelCopy.SUCCESS) store(bmp)
                finish()
            }, main)
        } catch (e: Throwable) {
            finish()
        }
    }

    // Основной способ: снимок средствами самого движка (так делает Firefox), он не зависит от SurfaceView
    try {
        gv.capturePixels().accept({ full ->
            if (full == null || full.width <= 0 || full.height <= 0) {
                viaPixelCopy()
            } else {
                scaleAndStore(full)
            }
        }, { viaPixelCopy() })
    } catch (e: Throwable) {
        viaPixelCopy()
    }
}

// =====================================================================================================================
// Переключатель вкладок. Устройство (переписано с нуля ради плавности):
//
// 1. Карусель - собственный Layout, а не HorizontalPager. Все карточки одного размера и стоят в одной точке; положение,
//    наклон, масштаб и прозрачность каждой задаёт блок слоя (placeWithLayer), который читает состояние только на этапе
//    рисования. Кадр прокрутки или смахивания не вызывает ни перекомпоновку, ни измерение, ни даже перезапись карточки:
//    двигается готовая текстура.
// 2. Положение камеры - одно число [CarouselState.pos] в «номерах карточек». Пальцем его двигает один обработчик на всю
//    карусель, доводку делает одна пружина.
// 3. Закрытие: карточка улетает вверх, а соседи плавно сдвигаются в освободившееся место (раньше после удаления вкладки
//    пейджер перескакивал). Вкладка из списка убирается, только когда карточка уже невидима и место схлопнулось.
// 4. Начальная страница рисуется один раз в общий слой и после этого убирается из композиции: стоимость в покое - ноль.
// =====================================================================================================================

private fun tabKey(t: Tab) = t.id

/** Элемент карусели: вкладка или карточка-заглушка пустого раздела. */
private data class Slide(val tab: Tab?, val index: Int, val priv: Boolean) {
    val key: Any get() = tab?.let { tabKey(it) } ?: if (priv) "empty-private" else "empty-normal"
}

/** Нажатая карточка: где она на экране и какая на ней картинка. */
private class Expand(val rect: UiRect, val thumb: ImageBitmap?, val index: Int)

/** Координаты карточки. Обычная ссылка, а не состояние: запись в состояние на каждом кадре перерисовывала бы лишнее. */
private class CoordsHolder {
    var c: LayoutCoordinates? = null
    fun rect(): UiRect = c?.takeIf { it.isAttached }?.boundsInRoot() ?: UiRect.Zero
}

/** Всё, что нужно карточкам от переключателя, в одном стабильном объекте. */
@Stable
private class CardEnv {
    lateinit var browser: Browser
    lateinit var homeLayer: GraphicsLayer

    /** Начальная страница записана в [homeLayer]: до этого карточки показывают заглушку и плавно меняют её на превью */
    var homeReady by mutableStateOf(false)
    var currentHolder: CoordsHolder? = null
    var onOpen: (Int, UiRect, ImageBitmap?) -> Unit = { _, _, _ -> }
    var onClose: (Tab) -> Unit = {}

    /** Сколько карточек сейчас тянут пальцем или они улетают. Отложенное закрытие сессий ждёт нуля */
    var busy by mutableIntStateOf(0)
}

/** Во сколько порогов закрытия карточка оттянута, когда её прозрачность дошла до нуля: дальше она не видна */
private const val SWIPE_GONE = 2.2f
private const val SIDE_SCALE = 0.12f
private const val SIDE_ALPHA = 0.28f
private const val SIDE_TURN = 18f

// Пустой запрос: ничего не читаем. Иначе список зависел бы от заголовка и адреса каждой вкладки
private fun Tab.matches(q: String) = q.isEmpty() || matchesQuery(label(), url, q)

/** Всё, что меняется у одной карточки на каждом кадре. Читается только в блоках слоя, поэтому перекомпоновок не вызывает. */
@Stable
private class CardMotion(enterFrom: Float) {
    /** Смещение вверх при смахивании (px, <= 0) */
    var y by mutableFloatStateOf(0f)

    /** Сколько места карточка занимает в карусели: 1 - всё, 0 - схлопнулась, соседи сомкнулись */
    var slot by mutableFloatStateOf(1f)

    /** Сетка: прогресс закрытия кнопкой */
    var leave by mutableFloatStateOf(0f)
    val press = Animatable(1f)

    /** Появление новой карточки */
    val enter = Animatable(enterFrom)
    var job: Job? = null
    var leaving = false
    var hapticFired = false
}

/**
 * Состояние карусели.
 *
 * [pos] - положение камеры в номерах карточек (2.0 - вторая карточка точно по центру, 2.5 - между второй и третьей).
 * Пока карточка схлопывается ([collapsing]), места за ней становится меньше: положение карточки на экране считает [xAt],
 * линейно «забирая» у камеры освободившееся место, поэтому камера остаётся на той же карточке, а не уезжает на соседнюю.
 */
@Stable
private class CarouselState(
    private val scope: CoroutineScope,
    private val haptic: HapticFeedback,
    initialPos: Float,
) {
    lateinit var slidesState: State<List<Slide>>
    lateinit var env: CardEnv
    val slides: List<Slide> get() = slidesState.value

    var pos by mutableFloatStateOf(initialPos)

    /** Идёт жест или доводка: пока true, отложенное закрытие сессий движка ждёт */
    var moving by mutableStateOf(false)

    /** Карточки, у которых место схлопывается. Читается в блоках слоя, поэтому состояние */
    val collapsing = mutableStateMapOf<Any, CardMotion>()
    var fadeMode by mutableIntStateOf(-1)
    val fade = Animatable(1f)

    /** После первого кадра: карточки, созданные позже (вошли в окно, появились после закрытия), плавно проявляются */
    var ready = false
    var onSettled: () -> Unit = {}

    // Размеры, ставит Layout при измерении
    var step = 1f
    var cardW = 1f
    var cardH = 1f
    var viewW = 1f
    var viewH = 1f
    var dismissPx = 1f
    var flickPx = 1f
    var flickMinPx = 1f
    var cardShape: Shape = HripsShapes.XXL

    private val motions = HashMap<Any, CardMotion>()
    private var camJob: Job? = null
    private var camTarget = 0f
    private var camVel = 0f

    // ---- карточки ----

    fun motion(key: Any): CardMotion = motions.getOrPut(key) { CardMotion(if (ready) 0f else 1f) }

    fun prune() {
        val keys = HashSet<Any>()
        slides.forEach { keys.add(it.key) }
        motions.keys.retainAll { it in keys || collapsing.containsKey(it) }
    }

    private fun maxPos() = (slides.size - 1).coerceAtLeast(0).toFloat()

    // ---- геометрия ----

    /** Сколько места «потеряно» левее точки [t]: схлопывающаяся карточка j отдаёт его по мере того, как камера её проходит */
    private fun lostAt(t: Float, list: List<Slide>): Float {
        if (collapsing.isEmpty()) return 0f
        var lost = 0f
        for ((k, m) in collapsing) {
            val j = list.indexOfFirst { it.key == k }
            if (j >= 0) lost += (1f - m.slot) * (t - j).coerceIn(0f, 1f)
        }
        return lost
    }

    private fun xAt(t: Float, list: List<Slide>) = t - lostAt(t, list)

    /** Блок слоя карточки: всё, что видно глазу. Выполняется при рисовании, не при композиции */
    fun applyLayer(s: GraphicsLayerScope, key: Any) {
        val list = slides
        val i = list.indexOfFirst { it.key == key }
        val m = motions[key]
        if (i < 0 || m == null) {
            s.alpha = 0f
            return
        }
        val e = pos.coerceIn(0f, (list.size - 1).coerceAtLeast(0).toFloat())
        val d = xAt(i.toFloat(), list) - xAt(e, list)
        val ad = abs(d).coerceIn(0f, 1f)
        val y = m.y
        // Чем выше утащили, тем сильнее карточка сжимается и наклоняется, как отрываемый листок
        val pull = (-y / dismissPx).coerceIn(0f, 2f)
        val en = m.enter.value
        val sc = (1f - SIDE_SCALE * ad) * (1f - 0.07f * pull) * m.press.value * (0.92f + 0.08f * en)
        s.translationX = d * step
        s.translationY = y
        s.scaleX = sc
        s.scaleY = sc
        s.rotationZ = -pull * 3f
        // Боковые поворачиваются лицом к центру, как в coverflow
        s.rotationY = -d.coerceIn(-1f, 1f) * SIDE_TURN
        s.cameraDistance = 14f * s.density
        var a = (1f - SIDE_ALPHA * ad) * (1f - abs(y) / (dismissPx * SWIPE_GONE)).coerceIn(0f, 1f) * en
        if (fadeMode >= 0 && list[i].priv == (fadeMode == 1)) a *= fade.value
        s.alpha = a
        s.shape = cardShape
        s.clip = true
        // Прозрачность на каждую команду рисования, без отдельного буфера размером с карточку
        s.compositingStrategy = CompositingStrategy.ModulateAlpha
    }

    /** Карточка под точкой [p] (в координатах карусели) или null */
    fun slideAt(p: Offset): Slide? {
        val list = slides
        if (list.isEmpty() || abs(p.y - viewH / 2f) > cardH / 2f) return null
        val e = pos.coerceIn(0f, maxPos())
        val xe = xAt(e, list)
        val c = e.roundToInt()
        var best: Slide? = null
        var bestDist = Float.MAX_VALUE
        for (i in (c - 2).coerceAtLeast(0)..(c + 2).coerceAtMost(list.lastIndex)) {
            val d = xAt(i.toFloat(), list) - xe
            val sc = 1f - SIDE_SCALE * abs(d).coerceIn(0f, 1f)
            val dist = abs(p.x - (viewW / 2f + d * step))
            if (dist <= cardW * sc / 2f && dist < bestDist) {
                best = list[i]
                bestDist = dist
            }
        }
        return best
    }

    // ---- камера ----

    fun beginDrag() {
        camJob?.cancel()
        camJob = null
        moving = true
    }

    fun dragBy(dxPx: Float) {
        pos = (pos - dxPx / step).coerceIn(0f, maxPos())
    }

    /** Палец отпущен: доводим до ближайшей карточки, бросок переносит максимум на одну. [vu] - скорость в карточках/с */
    fun settle(vu: Float) {
        val n = slides.size
        if (n == 0) {
            moving = false
            return
        }
        val base = pos.roundToInt().coerceIn(0, n - 1)
        val t = (pos + vu * 0.12f).roundToInt().coerceIn(base - 1, base + 1).coerceIn(0, n - 1)
        animateCamera(t.toFloat(), vu)
    }

    fun endDrag(vxPx: Float) = settle(-vxPx / step)

    /** Доводка камеры к [target] пружиной без перелёта; подхватывает скорость жеста */
    fun animateCamera(target: Float, velocity: Float = 0f) {
        camJob?.cancel()
        camTarget = target
        moving = true
        camJob = scope.launch {
            if (abs(pos - target) > 0.001f || abs(velocity) > 0.01f) {
                animate(pos, target, velocity, spring(dampingRatio = 0.9f, stiffness = 500f, visibilityThreshold = 0.002f)) { v, vel ->
                    camVel = vel
                    pos = v.coerceIn(0f, maxPos())
                }
            }
            pos = target.coerceIn(0f, maxPos())
            camVel = 0f
            moving = false
            onSettled()
        }
    }

    /** К карточке номер [idx] (переключатель «Вкладки / Приватные»). Дальний переход начинаем рядом, чтобы не мелькало полсписка */
    fun scrollTo(idx: Int) {
        val t = idx.toFloat()
        if (abs(t - pos) > 2.5f) pos = t - sign(t - pos) * 1.2f
        animateCamera(t)
    }

    // ---- смахивание вверх ----

    fun busyOn() {
        env.busy += 1
    }

    fun busyOff() {
        env.busy = (env.busy - 1).coerceAtLeast(0)
    }

    /** Вибро-отклик вне главного потока: на ряде устройств он занимает заметное время и обрывал плавность жеста */
    fun tick(type: HapticFeedbackType) {
        scope.launch(Dispatchers.Default) { runCatching { haptic.performHapticFeedback(type) } }
    }

    fun verticalStart(m: CardMotion) {
        m.job?.cancel()
        m.job = null
        m.hapticFired = false
        busyOn()
    }

    fun verticalBy(m: CardMotion, dy: Float) {
        // Вверх карточка идёт чуть быстрее пальца (1.25x): жест ощущается лёгким. Вниз возвращается 1 к 1
        m.y = (m.y + if (dy < 0f) dy * 1.25f else dy).coerceAtMost(0f)
        // Короткий отклик один раз за жест, когда карточку оттянули достаточно для закрытия
        if (m.y < -dismissPx && !m.hapticFired) {
            m.hapticFired = true
            tick(HapticFeedbackType.GestureThresholdActivate)
        }
    }

    fun verticalEnd(slide: Slide, m: CardMotion, vy: Float, finished: Boolean) {
        val flick = finished && vy < -flickPx && m.y < -flickMinPx
        if (finished && (m.y < -dismissPx || flick)) {
            dismiss(slide, vy, busyHeld = true)
        } else {
            m.job = scope.launch {
                try {
                    animate(m.y, 0f, vy, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow)) { v, _ -> m.y = v.coerceAtMost(0f) }
                } finally {
                    busyOff()
                }
            }
        }
    }

    /**
     * Улёт вверх и закрытие. Пока карточка летит, её место схлопывается, и соседи плавно сдвигаются. Вкладка убирается из
     * списка, когда карточка уже невидима и место сомкнулось: список меняется без скачка.
     * Единственная карточка раздела не схлопывается: на её месте появится заглушка.
     */
    fun dismiss(slide: Slide, velocity: Float, busyHeld: Boolean) {
        val tab = slide.tab
        val m = tab?.let { motion(slide.key) }
        if (tab == null || m == null || m.leaving) {
            if (busyHeld) busyOff()
            return
        }
        m.leaving = true
        if (!busyHeld) busyOn()
        val list = slides
        val j = list.indexOfFirst { it.key == slide.key }
        val canCollapse = list.any { it.tab != null && it.priv == slide.priv && it.key != slide.key && !collapsing.containsKey(it.key) }
        // Закрыли центральную карточку, последнюю в своём разделе: камера идёт к предыдущей, а не в соседний раздел
        val next = list.getOrNull(j + 1)
        val hasNextSame = next != null && next.tab != null && next.priv == slide.priv
        val goBack = canCollapse && j > 0 && abs(pos - j) < 0.5f && !hasNextSame
        val goneAt = dismissPx * 1.1f
        m.job?.cancel()
        m.job = scope.launch {
            var removed = false
            try {
                val fly = launch {
                    animate(m.y, -dismissPx * (SWIPE_GONE + 0.4f), velocity.coerceAtMost(0f), HripsMotion.fling<Float>()) { v, _ -> m.y = v }
                }
                if (canCollapse) {
                    snapshotFlow { m.y }.first { it <= -goneAt }
                    collapsing[slide.key] = m
                    if (goBack) animateCamera((j - 1).toFloat())
                    animate(1f, 0f, 0f, tween(300, easing = FastOutSlowInEasing)) { v, _ -> m.slot = v }
                } else {
                    snapshotFlow { m.y }.first { it <= -dismissPx * SWIPE_GONE }
                }
                fly.cancel()
                removeNow(slide.key, tab)
                removed = true
            } finally {
                busyOff()
                // Переключатель закрыли посреди улёта: карточка уже невидима, вкладку всё равно закрываем
                if (!removed && m.y <= -dismissPx * SWIPE_GONE) removeNow(slide.key, tab)
            }
        }
    }

    /** Убирает вкладку из списка и подстраивает камеру так, чтобы на экране ничего не сдвинулось */
    private fun removeNow(key: Any, tab: Tab) {
        val list = slides
        val j = list.indexOfFirst { it.key == key }
        val running = camJob?.isActive == true
        if (j >= 0) {
            pos = (pos - (pos - j).coerceIn(0f, 1f)).coerceAtLeast(0f)
            camTarget -= (camTarget - j).coerceIn(0f, 1f)
        }
        collapsing.remove(key)
        motions.remove(key)
        env.onClose(tab)
        // Идёт доводка: она целилась в старый номер. Продолжаем с той же скоростью, но к сдвинутой цели
        if (running) animateCamera(camTarget.coerceIn(0f, maxPos()), camVel)
    }

    // ---- «Закрыть все» ----

    suspend fun fadeOutSection(priv: Boolean) {
        fadeMode = if (priv) 1 else 0
        fade.snapTo(1f)
        fade.animateTo(0f, tween(180))
    }

    suspend fun resetFade() {
        fadeMode = -1
        fade.snapTo(1f)
    }
}

/**
 * Один обработчик касаний на всю карусель. По первому смещению за порог выбираем ось: по горизонтали листаем,
 * по вертикали (если палец на карточке) смахиваем эту карточку. Нажатия без смещения достаются самим карточкам.
 */
private fun Modifier.carouselGestures(st: CarouselState): Modifier = pointerInput(st) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val vt = VelocityTracker()
        vt.addPosition(down.uptimeMillis, down.position)
        var axis = 0
        var hit: Slide? = null
        val first = awaitTouchSlopOrCancellation(down.id) { change, over ->
            if (abs(over.x) > abs(over.y)) {
                axis = 1
            } else {
                hit = st.slideAt(down.position)?.takeIf { it.tab != null }
                if (hit != null) axis = 2
            }
            if (axis != 0) change.consume()
        } ?: return@awaitEachGesture
        if (axis == 1) {
            st.beginDrag()
            vt.addPosition(first.uptimeMillis, first.position)
            val ok = drag(first.id) { c ->
                vt.addPosition(c.uptimeMillis, c.position)
                st.dragBy(c.positionChange().x)
                c.consume()
            }
            st.endDrag(if (ok) vt.calculateVelocity().x else 0f)
        } else if (axis == 2) {
            val slide = hit ?: return@awaitEachGesture
            val m = st.motion(slide.key)
            if (m.leaving) return@awaitEachGesture
            st.verticalStart(m)
            vt.addPosition(first.uptimeMillis, first.position)
            val ok = drag(first.id) { c ->
                vt.addPosition(c.uptimeMillis, c.position)
                st.verticalBy(m, c.positionChange().y)
                c.consume()
            }
            st.verticalEnd(slide, m, if (ok) vt.calculateVelocity().y else 0f, ok)
        }
    }
}

/**
 * Карусель: окно из пяти карточек вокруг камеры. Остальные не компонуются. Все карточки одного размера и стоят в центре;
 * на место их ставит блок слоя ([CarouselState.applyLayer]).
 */
@Composable
private fun TabCarousel(
    st: CarouselState,
    env: CardEnv,
    current: Int,
    searching: Boolean,
    onNew: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val slides = st.slides
    val range by remember(st) {
        derivedStateOf {
            val list = st.slides
            val c = st.pos.roundToInt().coerceIn(0, (list.size - 1).coerceAtLeast(0))
            (c - 2).coerceAtLeast(0)..(c + 2).coerceAtMost(list.lastIndex)
        }
    }
    Layout(
        content = {
            for (i in range) {
                val s = slides.getOrNull(i) ?: continue
                key(s.key) {
                    CarouselCard(s, selected = s.tab != null && s.index == current, st = st, env = env, searching = searching, onNew = onNew)
                }
            }
        },
        modifier = modifier.carouselGestures(st),
    ) { measurables, constraints ->
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val cw = minOf(w * 0.78f, 400.dp.toPx()).roundToInt().coerceAtLeast(1)
        val ratio = if (w > 600.dp.toPx()) 1.0f else 1.4f
        val ch = minOf((cw * ratio).roundToInt(), h - 16.dp.roundToPx()).coerceAtLeast(1)
        st.cardW = cw.toFloat()
        st.cardH = ch.toFloat()
        st.viewW = w.toFloat()
        st.viewH = h.toFloat()
        st.step = cw + 14.dp.toPx()
        st.dismissPx = 64.dp.toPx()
        st.flickPx = 380.dp.toPx()
        st.flickMinPx = 10.dp.toPx()
        val fixed = Constraints.fixed(cw, ch)
        val placed = measurables.map { it.layoutId to it.measure(fixed) }
        layout(w, h) {
            val x0 = (w - cw) / 2
            val y0 = (h - ch) / 2
            for ((key, p) in placed) {
                if (key != null) p.placeWithLayer(x0, y0) { st.applyLayer(this, key) }
            }
        }
    }
}

@Composable
private fun CarouselCard(
    slide: Slide,
    selected: Boolean,
    st: CarouselState,
    env: CardEnv,
    searching: Boolean,
    onNew: (Boolean) -> Unit,
) {
    val m = remember { st.motion(slide.key) }
    LaunchedEffect(m) {
        if (m.enter.value < 1f) m.enter.animateTo(1f, tween(240, easing = FastOutSlowInEasing))
    }
    val tab = slide.tab
    Box(Modifier.layoutId(slide.key)) {
        if (tab == null) {
            EmptySlide(slide.priv, searching = searching, onNew = { onNew(slide.priv) }, modifier = Modifier.fillMaxSize())
        } else {
            val holder = remember { CoordsHolder() }
            if (selected) SideEffect { env.currentHolder = holder }
            TabCardBody(
                tab = tab,
                index = slide.index,
                selected = selected,
                compact = false,
                env = env,
                m = m,
                holder = holder,
                onClose = { st.dismiss(slide, 0f, busyHeld = false) },
                modifier = Modifier.fillMaxSize().onPlaced { holder.c = it },
            )
        }
    }
}

/**
 * Карточка вкладки: значок, название и адрес, кнопка закрытия и крупное превью страницы (снимок экрана).
 * Выбранная выделена цветом и рамкой. Нажатие открывает. Положение, наклон и прозрачность карточке не принадлежат:
 * их задаёт слой снаружи (карусель или сетка), поэтому содержимое во время жестов не перерисовывается.
 */
@Composable
private fun TabCardBody(
    tab: Tab,
    index: Int,
    selected: Boolean,
    compact: Boolean,
    env: CardEnv,
    m: CardMotion,
    holder: CoordsHolder,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val dismissPx = with(LocalDensity.current) { 64.dp.toPx() }
    val thumb = rememberTabThumb(tab, compact)
    val subtitle = when {
        // Название таких вкладок уже «Приватная вкладка» / «Начальная страница»: подпись говорит другое, а не повторяет его
        tab.isPrivate && tab.home -> "История не сохраняется"
        tab.home -> "Новая вкладка"
        else -> runCatching { Uri.parse(tab.url).host }.getOrNull()?.removePrefix("www.") ?: tab.url
    }
    val inner = RoundedCornerShape(if (compact) 23.dp else 30.dp)

    // Рамка: выбранная вкладка в цвете акцента, остальные в тоне поверхности. Цвет читается при рисовании
    val frame = animateColorAsState(
        if (selected) cs.primary else cs.surfaceContainerHigh,
        MaterialTheme.motionScheme.defaultEffectsSpec<Color>(),
        label = "tabFrame",
    )
    // Нажатая карточка чуть проседает: число лежит в m.press и читается слоем снаружи
    val source = remember { MutableInteractionSource() }
    val pressSpec = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    LaunchedEffect(source) {
        source.interactions.collect { i ->
            when (i) {
                is PressInteraction.Press -> launch { m.press.animateTo(0.97f, pressSpec) }
                is PressInteraction.Release, is PressInteraction.Cancel -> launch { m.press.animateTo(1f, pressSpec) }
                else -> Unit
            }
        }
    }

    Box(
        modifier
            .drawBehind { drawRect(frame.value) }
            // Только нажатие: карточку закрывают смахиванием вверх или крестиком
            .clickable(interactionSource = source, indication = null, onClick = { env.onOpen(index, holder.rect(), thumb) }),
    ) {
        Box(Modifier.fillMaxSize().padding(if (compact) 5.dp else 6.dp).clip(inner).background(cs.surfaceContainerLowest)) {
            // Нет снимка: пробуем картинку самой страницы (og:image). Для приватных вкладок не запрашивается ничего
            val context = LocalContext.current.applicationContext
            val pageImageState = produceState<ImageBitmap?>(null, tab.url, thumb == null, PageImages.enabled) {
                value = if (thumb == null && !tab.isPrivate && !tab.home && tab.url.isNotBlank()) PageImages.load(context, tab.url) else null
            }
            val pageImage = pageImageState.value
            if (thumb != null && !tab.home) {
                Image(
                    bitmap = thumb,
                    contentDescription = "Превью страницы",
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (pageImage != null && !tab.home) {
                Image(
                    bitmap = pageImage,
                    contentDescription = "Картинка страницы",
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (tab.home && !tab.isPrivate) {
                HomeCardContent(tab, compact, env)
            } else {
                TabPlaceholder(tab, compact)
            }

            // Кнопка закрытия справа сверху
            Surface(
                onClick = {
                    scope.launch(Dispatchers.Default) { runCatching { haptic.performHapticFeedback(HapticFeedbackType.Confirm) } }
                    onClose()
                },
                shape = CircleShape,
                color = cs.surface.copy(alpha = 0.88f),
                contentColor = cs.onSurface,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(if (compact) 6.dp else 10.dp)
                    .size(if (compact) 30.dp else 38.dp)
                    .semantics { contentDescription = "Закрыть вкладку ${tab.label()}" },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(HripsIcons.Close, null, Modifier.size(if (compact) 16.dp else 20.dp))
                }
            }

            // Подпись плашкой снизу: значок, название, адрес. Лежит поверх снимка, поэтому читается на любой странице
            CompositionLocalProvider(LocalContentColor provides cs.onSurface) {
                Row(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(if (compact) 6.dp else 8.dp)
                        .background(cs.surfaceContainerHigh.copy(alpha = 0.94f), RoundedCornerShape(if (compact) 18.dp else 24.dp))
                        .padding(horizontal = if (compact) 8.dp else 12.dp, vertical = if (compact) 6.dp else 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TabIcon(tab, if (compact) 20.dp else 30.dp)
                    Spacer(Modifier.width(if (compact) 8.dp else 10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            tab.label(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = if (compact) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleSmall,
                        )
                        if (!compact) {
                            Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        // Красный значок сверху: появляется, когда карточку оттянули достаточно, чтобы она закрылась. У него свой слой,
        // поэтому его проявление на каждом кадре жеста не перезаписывает остальную карточку
        if (!compact) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 14.dp)
                    .size(44.dp)
                    .graphicsLayer {
                        val k = (-m.y / dismissPx).coerceIn(0f, 1f)
                        alpha = k
                        scaleX = 0.6f + 0.4f * k
                        scaleY = 0.6f + 0.4f * k
                    }
                    .background(cs.error, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(HripsIcons.Close, null, Modifier.size(22.dp), tint = cs.onError) }
        }
    }
}

/** Начальная страница в карточке: сначала заглушка, когда настоящая записана в общий слой, превью проявляется поверх неё */
@Composable
private fun HomeCardContent(tab: Tab, compact: Boolean, env: CardEnv) {
    val a = animateFloatAsState(if (env.homeReady) 1f else 0f, tween(220), label = "homeFade")
    Box(Modifier.fillMaxSize()) {
        TabPlaceholder(tab, compact)
        HomeTabPreview(env.homeLayer, Modifier.fillMaxSize().graphicsLayer { alpha = a.value })
    }
}

/** Карточка сетки. Закрытие кнопкой: карточка уменьшается и гаснет, потом вкладка уходит, соседей сдвигает сетка */
@Composable
private fun GridCard(tab: Tab, index: Int, selected: Boolean, env: CardEnv, modifier: Modifier = Modifier) {
    val m = remember { CardMotion(1f) }
    val holder = remember { CoordsHolder() }
    if (selected) SideEffect { env.currentHolder = holder }
    val scope = rememberCoroutineScope()
    TabCardBody(
        tab = tab,
        index = index,
        selected = selected,
        compact = true,
        env = env,
        m = m,
        holder = holder,
        onClose = {
            if (!m.leaving) {
                m.leaving = true
                scope.launch {
                    animate(0f, 1f, 0f, tween(170, easing = FastOutSlowInEasing)) { v, _ -> m.leave = v }
                    env.onClose(tab)
                }
            }
        },
        modifier = modifier
            .onPlaced { holder.c = it }
            .graphicsLayer {
                val s = m.press.value * (1f - 0.1f * m.leave)
                scaleX = s
                scaleY = s
                alpha = 1f - m.leave
                shape = HripsShapes.L
                clip = true
                compositingStrategy = CompositingStrategy.ModulateAlpha
            },
    )
}

/** Смена раздела: старый уезжает и гаснет, новый приезжает с той стороны, куда ушёл ползунок таблетки */
private fun modeTransition(
    slide: FiniteAnimationSpec<IntOffset>,
    fin: FiniteAnimationSpec<Float>,
    fout: FiniteAnimationSpec<Float>,
): AnimatedContentTransitionScope<Int>.() -> ContentTransform = {
    val dir = if (targetState > initialState) 1 else -1
    (slideInHorizontally(slide) { it / 6 * dir } + fadeIn(fin)) togetherWith
        (slideOutHorizontally(slide) { -it / 6 * dir } + fadeOut(fout))
}

/** Сетка: один раздел за раз, между «Вкладки» и «Приватные» переключает таблетка в шапке. */
@Composable
private fun TabGrid(
    mode: Int,
    normal: List<Pair<Int, Tab>>,
    priv: List<Pair<Int, Tab>>,
    current: Int,
    searching: Boolean,
    env: CardEnv,
    onNew: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val slideSpec = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
    val fadeInSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val fadeOutSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    AnimatedContent(
        targetState = mode,
        modifier = modifier,
        transitionSpec = modeTransition(slideSpec, fadeInSpec, fadeOutSpec),
        label = "tabGrid",
    ) { m ->
        val isPriv = m == 1
        val list = if (isPriv) priv else normal
        if (list.isEmpty()) {
            EmptyTabs(isPriv, searching = searching, onNew = { onNew(isPriv) })
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 170.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 120.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                gridItems(list, key = { tabKey(it.second) }, contentType = { "tab" }) { (i, t) ->
                    GridCard(t, i, selected = i == current, env = env, modifier = Modifier.fillMaxWidth().aspectRatio(0.78f).animateItem())
                }
            }
        }
    }
}


/**
 * Переключатель вкладок на весь экран в стиле Material 3 Expressive: переключатель «Вкладки / Приватные»,
 * карусель крупных карточек с живым превью страницы (центральная крупнее, соседние уменьшаются, бледнеют и поворачиваются)
 * или сетка, плавающая панель с большой кнопкой «+». Карточку можно смахнуть вверх, чтобы закрыть.
 * Устройство описано в комментарии выше ([CarouselState]).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TabSwitcher(browser: Browser, onClose: () -> Unit, onHistory: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    // Выбранный вид (карусель или сетка) запоминается между запусками
    var grid by remember { mutableStateOf(browser.store.tabsGrid) }
    // Разворот видов (карусель <-> сетка) проявляется, а первый показ нет: его уже показывает открытие экрана
    val toggled = remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(searching) { if (searching) focus.requestFocus() }

    var showClosed by remember { mutableStateOf(false) }
    // Растёт при каждом закрытии: по нему отложенно отпускаются сессии закрытых вкладок (см. LaunchedEffect ниже)
    var closeTick by remember { mutableIntStateOf(0) }
    // Закрытие карточки с возможностью сразу вернуть вкладку. Движок при этом не трогаем: убираем вкладку только из
    // списка, сессию закроем, когда всё затихнет
    val closeWithUndo: (Int) -> Unit = { i ->
        val before = browser.closedTabs.firstOrNull()
        browser.closeTab(i, deferEngine = true)
        closeTick++
        if (browser.closedTabs.firstOrNull() !== before) {
            Notices.show("Вкладка закрыта", "Вернуть") { browser.reopenLast() }
        }
    }

    // Карточка, раскрывающаяся в страницу (после нажатия на карточку)
    var expanding by remember { mutableStateOf<Expand?>(null) }
    // 0..1: насколько карточка раскрылась в страницу. По нему гаснут шапка, список и нижняя панель
    val expandP = remember { Animatable(0f) }
    val chromeAlpha: () -> Float = { 1f - (expandP.value / 0.6f).coerceIn(0f, 1f) }
    val origins = LocalOrigins.current

    val current = browser.currentIndex
    val q = query.trim()
    val qState = rememberUpdatedState(q)

    val normalCount by remember(browser) { derivedStateOf { browser.tabs.count { !it.isPrivate } } }
    val privateCount by remember(browser) { derivedStateOf { browser.tabs.size - normalCount } }
    val hasHome by remember(browser) { derivedStateOf { browser.tabs.any { it.home && !it.isPrivate } } }

    // Списки вкладок с учётом поиска (пары «номер в общем списке, вкладка»). Запрос читаем через состояние, а не через
    // значение: иначе список, созданный один раз, навсегда запомнил бы первый запрос
    val normalList by remember(browser) {
        derivedStateOf {
            val qq = qState.value
            browser.tabs.mapIndexedNotNull { i, t -> if (!t.isPrivate && t.matches(qq)) i to t else null }
        }
    }
    val privList by remember(browser) {
        derivedStateOf {
            val qq = qState.value
            browser.tabs.mapIndexedNotNull { i, t -> if (t.isPrivate && t.matches(qq)) i to t else null }
        }
    }

    // Обычные и приватные вкладки - две отдельные карусели: пока листаете одни, других не видно вовсе. Переключаются
    // таблеткой в шапке. У каждой своё положение, оно помнится при переключении. Пустой раздел - одна карточка-заглушка
    val normalSlides = remember(browser) {
        derivedStateOf {
            if (normalList.isEmpty()) listOf(Slide(null, -1, false)) else normalList.map { Slide(it.second, it.first, false) }
        }
    }
    val privSlides = remember(browser) {
        derivedStateOf {
            if (privList.isEmpty()) listOf(Slide(null, -1, true)) else privList.map { Slide(it.second, it.first, true) }
        }
    }
    // Номер карточки текущей вкладки в списке (или первая)
    val startIn = { list: List<Slide> -> list.indexOfFirst { it.tab != null && it.index == current }.coerceAtLeast(0) }

    // 0 = обычные, 1 = приватные. Один режим на карусель и на сетку
    var mode by remember { mutableIntStateOf(if (browser.current.isPrivate) 1 else 0) }

    val env = remember { CardEnv() }
    env.browser = browser
    // Общий слой с превью начальной страницы: рисуется один раз, а не по экземпляру страницы на каждую карточку
    val homeLayer = rememberGraphicsLayer()
    env.homeLayer = homeLayer

    val stN = remember { CarouselState(scope, haptic, startIn(normalSlides.value).toFloat()) }
    val stP = remember { CarouselState(scope, haptic, startIn(privSlides.value).toFloat()) }
    stN.slidesState = normalSlides
    stN.env = env
    stP.slidesState = privSlides
    stP.env = env
    val st = if (mode == 1) stP else stN
    LaunchedEffect(stN, stP) {
        repeat(2) { withFrameNanos { } }
        stN.ready = true
        stP.ready = true
    }
    LaunchedEffect(normalSlides.value) { stN.prune() }
    LaunchedEffect(privSlides.value) { stP.prune() }
    // Список изменился (поиск, закрыли все): камера могла остаться между карточками или за краем
    LaunchedEffect(normalSlides.value.size) {
        if (!stN.moving && stN.collapsing.isEmpty()) stN.settle(0f)
    }
    LaunchedEffect(privSlides.value.size) {
        if (!stP.moving && stP.collapsing.isEmpty()) stP.settle(0f)
    }

    // Положение между «обычными» (0) и «приватными» (1). Читается только при рисовании
    val modeP = animateFloatAsState(mode.toFloat(), MaterialTheme.motionScheme.defaultSpatialSpec<Float>(), label = "mode")
    val progress: () -> Float = { modeP.value.coerceIn(0f, 1f) }
    val bgNormal = cs.surface
    val bgPrivate = lerp(cs.surface, cs.tertiaryContainer, 0.55f)

    val select: (Int) -> Unit = { m -> mode = m }

    env.onOpen = { i, rect, thumb -> if (expanding == null) expanding = Expand(rect, thumb, i) }
    env.onClose = { tab ->
        val i = browser.tabs.indexOf(tab)
        if (i >= 0) closeWithUndo(i)
    }

    // Начальная страница: настоящая главная записывается в слой один раз и после этого убирается из композиции. Начинаем
    // после того как переключатель открылся (пока он растёт, кадры нужны анимации), а до этого карточки показывают заглушку
    var homePhase by remember { mutableIntStateOf(0) } // 0 - ждём, 1 - записываем, 2 - готово
    LaunchedEffect(hasHome) {
        if (!hasHome) return@LaunchedEffect
        delay(480)
        homePhase = 1
        delay(450)
        env.homeReady = true
        withFrameNanos { }
        homePhase = 2
    }

    // Сессии закрытых карточек отпускаем, когда прошло время без новых закрытий, листание стоит и карточки не тянут.
    // При выходе из переключателя отпускаем всё, что осталось. Ожидание ограничено, чтобы не зависнуть на жесте
    LaunchedEffect(closeTick) {
        if (closeTick == 0) return@LaunchedEffect
        delay(1200)
        withTimeoutOrNull(3000) {
            snapshotFlow { stN.moving || stP.moving || env.busy > 0 }.first { !it }
        }
        browser.flushPendingClose()
    }
    DisposableEffect(browser) { onDispose { browser.flushPendingClose() } }

    // Положение текущей карточки нужно анимации «страница сжимается в карточку» (PageSwap). Сообщаем его не на каждом
    // кадре, а когда карточка встала на место: после открытия, смены вкладки, переключения вида и после доводки камеры
    val reportRect = {
        if (expanding == null) {
            env.currentHolder?.c?.takeIf { it.isAttached }?.let { origins.currentCardRect = it.boundsInRoot() }
        }
    }
    stN.onSettled = reportRect
    stP.onSettled = reportRect
    LaunchedEffect(current, grid, mode) {
        repeat(2) { withFrameNanos { } }
        reportRect()
    }

    val onNew: (Boolean) -> Unit = { priv ->
        browser.newTab(incognito = priv, returnToPrevious = false)
        onClose()
    }
    val viewEnterSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val modeSlide = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
    val modeFadeIn = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val modeFadeOut = MaterialTheme.motionScheme.fastEffectsSpec<Float>()

    Box(
        Modifier
            .fillMaxSize()
            // Экран лежит поверх GeckoView: нажатия мимо карточек не должны попадать в сайт под ним
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        // Фон плавно меняет тон по мере смахивания к приватным вкладкам. Свой слой: его перерисовка на каждом кадре
        // листания не должна задевать остальное
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { }
                .drawBehind { drawRect(lerp(bgNormal, bgPrivate, progress()).copy(alpha = 1f - expandP.value.coerceIn(0f, 1f))) },
        )
        if (hasHome && homePhase == 1) HomePreviewSource(homeLayer)

        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            SwitcherHeader(
                searching = searching,
                onSearching = { searching = it },
                query = query,
                onQuery = { query = it },
                focus = focus,
                progress = progress,
                normal = normalCount,
                priv = privateCount,
                onSelect = select,
            )

            key(grid) {
                val enter = remember { Animatable(if (toggled.value) 0f else 1f) }
                LaunchedEffect(Unit) { if (enter.value < 1f) enter.animateTo(1f, viewEnterSpec) }
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .graphicsLayer {
                            alpha = enter.value
                            val s = 0.96f + 0.04f * enter.value
                            scaleX = s
                            scaleY = s
                        },
                ) {
                    if (grid) {
                        TabGrid(
                            mode = mode,
                            normal = normalList,
                            priv = privList,
                            current = current,
                            searching = q.isNotEmpty(),
                            env = env,
                            onNew = onNew,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        // Смена раздела: одна карусель уезжает, другая приезжает. Приватные не видны, пока листаете обычные
                        AnimatedContent(
                            targetState = mode,
                            modifier = Modifier.fillMaxSize(),
                            transitionSpec = modeTransition(modeSlide, modeFadeIn, modeFadeOut),
                            label = "carouselMode",
                        ) { m ->
                            TabCarousel(
                                st = if (m == 1) stP else stN,
                                env = env,
                                current = current,
                                searching = q.isNotEmpty(),
                                onNew = onNew,
                                modifier = Modifier.fillMaxSize().padding(bottom = 104.dp),
                            )
                        }
                    }
                }
            }
        }

        SwitcherBar(
            grid = grid,
            count = browser.tabs.size,
            onToggleGrid = {
                toggled.value = true
                if (grid) {
                    // Карусель встаёт на текущую вкладку до первого кадра, а не прыгает после него
                    val i = st.slides.indexOfFirst { it.tab != null && it.index == current }
                    if (i >= 0) st.pos = i.toFloat()
                }
                grid = !grid
                browser.store.updateTabsGrid(grid)
            },
            onHistory = onHistory,
            onNew = { onNew(mode == 1) },
            onDone = onClose,
            privatePage = mode == 1,
            onCloseAll = {
                scope.launch {
                    val priv = mode == 1
                    // Карточки раздела гаснут разом, потом вкладки закрываются одним шагом
                    if (!grid) st.fadeOutSection(priv)
                    closeTick++
                    if (priv) {
                        browser.closePrivateTabs(deferEngine = true)
                    } else {
                        val toClose = browser.tabs.count { !it.isPrivate }
                        browser.tabs.indices.reversed().filter { !browser.tabs[it].isPrivate }.forEach { browser.closeTab(it, deferEngine = true) }
                        // История закрытых хранит 15 вкладок: вернуть можно не больше
                        val undoable = minOf(toClose, browser.closedTabs.size)
                        if (undoable > 0) {
                            Notices.show("Закрыто вкладок: $toClose", "Вернуть") { repeat(undoable) { browser.reopenLast() } }
                        }
                    }
                    if (!grid) {
                        st.pos = 0f
                        st.resetFade()
                    }
                }
            },
            closedCount = browser.closedTabs.size,
            onClosed = { showClosed = true },
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        // Когда карточка раскрывается в страницу, всё остальное растворяется в цвет фона. Закраска дешевле, чем прозрачность
        // слоя: не нужен отдельный буфер на весь экран
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    val a = 1f - chromeAlpha()
                    if (a > 0f) drawRect(lerp(bgNormal, bgPrivate, progress()), alpha = a)
                },
        )

        expanding?.let { e ->
            ExpandOverlay(e, expandP) {
                browser.currentIndex = e.index
                origins.instantClose = true
                origins.expandStamp++
                onClose()
            }
        }

        if (showClosed) {
            ClosedTabsSheet(browser, onDismiss = { showClosed = false }, onRestored = { showClosed = false; onClose() })
        }
    }
}

/**
 * Шапка: переключатель «Вкладки / Приватные» и поиск по вкладкам. Вынесена отдельно, чтобы ввод букв в поиске
 * и смена вида не перерисовывали карточки.
 */
@Composable
private fun SwitcherHeader(
    searching: Boolean,
    onSearching: (Boolean) -> Unit,
    query: String,
    onQuery: (String) -> Unit,
    focus: FocusRequester,
    progress: () -> Float,
    normal: Int,
    priv: Int,
    onSelect: (Int) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    // Спеки берём до AnimatedContent: transitionSpec не @Composable
    val headerFadeIn = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val headerFadeOut = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val headerScale = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    AnimatedContent(
        targetState = searching,
        transitionSpec = {
            (fadeIn(headerFadeIn) + scaleIn(headerScale, initialScale = 0.96f)) togetherWith
                fadeOut(headerFadeOut) using SizeTransform(clip = false) { _, _ -> snap() }
        },
        label = "switcherHeader",
    ) { isSearching ->
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isSearching) {
                Surface(shape = CircleShape, color = cs.surfaceContainerHigh, modifier = Modifier.weight(1f)) {
                    Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(HripsIcons.Search, null, tint = cs.onSurfaceVariant)
                        Box(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 16.dp)) {
                            if (query.isEmpty()) {
                                Text(
                                    "Поиск по вкладкам", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            BasicTextField(
                                value = query,
                                onValueChange = onQuery,
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface),
                                cursorBrush = SolidColor(cs.primary),
                                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                            )
                        }
                        IconButton(onClick = { onSearching(false); onQuery("") }) { Icon(HripsIcons.Close, "Закрыть поиск") }
                    }
                }
            } else {
                // weight задаёт ширине жёсткое значение, и widthIn(max) после него не действует (на планшете
                // переключатель растягивался на весь экран). Поэтому ширину ограничиваем уже внутри контейнера
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    ModeToggle(
                        progress = progress,
                        normal = normal,
                        priv = priv,
                        onSelect = onSelect,
                        modifier = Modifier.widthIn(max = HripsLayout.SegmentedMaxWidth).fillMaxWidth(),
                    )
                }
                Spacer(Modifier.width(12.dp))
                FilledTonalIconButton(onClick = { onSearching(true) }, modifier = Modifier.size(56.dp)) {
                    Icon(HripsIcons.Search, "Поиск по вкладкам")
                }
            }
        }
    }
}

/**
 * Карточка вырастает до размера экрана: рамка скругления уменьшается до нуля, картинка растягивается вместе с ней.
 * В конце вызывается [onDone]: переключатель закрывается мгновенно (его собственная анимация выхода пропускается).
 * Рисуется одним проходом на холсте: раньше на каждом кадре заново размечался и измерялся Image.
 */
@Composable
private fun ExpandOverlay(e: Expand, progress: Animatable<Float, AnimationVector1D>, onDone: () -> Unit) {
    val spec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val bg = MaterialTheme.colorScheme.surfaceContainerLowest
    val startCorner = with(LocalDensity.current) { 36.dp.toPx() }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, spec)
        onDone()
    }
    // Свой слой: иначе рисование на каждом кадре перезаписывало бы весь общий слой с скрытой начальной страницей
    Canvas(Modifier.fillMaxSize().graphicsLayer { }) {
        // Пружина может перелететь единицу: карточка не должна вылезать за экран
        val t = progress.value.coerceIn(0f, 1f)
        val full = UiRect(0f, 0f, size.width, size.height)
        val r = androidx.compose.ui.geometry.lerp(e.rect, full, t)
        if (r.width < 1f || r.height < 1f) return@Canvas
        val clip = Path().apply { addRoundRect(RoundRect(r, CornerRadius(startCorner * (1f - t)))) }
        clipPath(clip) {
            drawRect(bg, topLeft = r.topLeft, size = r.size)
            val img = e.thumb
            if (img != null) {
                // Как ContentScale.Crop с выравниванием по верху: масштаб по большей стороне, лишнее срезается по бокам
                val iw = img.width
                val ih = img.height
                val s = maxOf(r.width / iw, r.height / ih)
                val sw = (r.width / s).roundToInt().coerceIn(1, iw)
                val sh = (r.height / s).roundToInt().coerceIn(1, ih)
                drawImage(
                    img,
                    srcOffset = IntOffset(((iw - sw) / 2f).roundToInt(), 0),
                    srcSize = IntSize(sw, sh),
                    dstOffset = IntOffset(r.left.roundToInt(), r.top.roundToInt()),
                    dstSize = IntSize(r.width.roundToInt().coerceAtLeast(1), r.height.roundToInt().coerceAtLeast(1)),
                    filterQuality = FilterQuality.Low,
                )
            }
        }
    }
}

/**
 * Переключатель «Вкладки / Приватные»: одна большая таблетка, внутри цветной «ползунок», который едет
 * за пальцем, пока смахиваете карточки. При переходе к приватным цвет меняется на акцентный третичный.
 */
@Composable
private fun ModeToggle(progress: () -> Float, normal: Int, priv: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    // Ползунок и его цвет читают progress только при рисовании. Цвет текста обновляется шагами по 1/16
    val progressState by rememberUpdatedState(progress)
    val p by remember { derivedStateOf { (progressState().coerceIn(0f, 1f) * 16f).roundToInt() / 16f } }
    Box(modifier.height(56.dp).clip(CircleShape).background(cs.surfaceContainerHigh).padding(4.dp)) {
        // Ползунок на половину ширины, едет на свою ширину. Без BoxWithConstraints: он требует отдельной подкомпозиции
        Box(
            Modifier
                .fillMaxWidth(0.5f)
                .fillMaxHeight()
                .graphicsLayer {
                    translationX = progress().coerceIn(0f, 1f) * size.width
                    shape = CircleShape
                    clip = true
                }
                .drawBehind { drawRect(lerp(cs.primary, cs.tertiary, progress().coerceIn(0f, 1f))) },
        )
        Row(Modifier.fillMaxSize()) {
            ModeSegment(HripsIcons.Grid, "Вкладки", normal, lerp(cs.onPrimary, cs.onSurfaceVariant, p), Modifier.weight(1f)) { onSelect(0) }
            ModeSegment(HripsIcons.Mask, "Приватные", priv, lerp(cs.onSurfaceVariant, cs.onTertiary, p), Modifier.weight(1f)) { onSelect(1) }
        }
    }
}

@Composable
private fun ModeSegment(
    icon: ImageVector,
    label: String,
    count: Int,
    content: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(onClick = onClick, shape = CircleShape, color = Color.Transparent, contentColor = content, modifier = modifier.fillMaxHeight()) {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Icon(icon, null, Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(6.dp))
            Surface(shape = CircleShape, color = content.copy(alpha = 0.2f), contentColor = content) {
                Text(count.toString(), Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** Значок вкладки в шапке карточки: приватная, стартовая или иконка сайта. */
@Composable
private fun TabIcon(tab: Tab, size: Dp) {
    val cs = MaterialTheme.colorScheme
    when {
        tab.isPrivate -> Icon(HripsIcons.Mask, null, Modifier.size(size * 0.8f), tint = cs.primary)
        tab.home -> Icon(HripsIcons.Home, null, Modifier.size(size * 0.8f), tint = cs.onSurfaceVariant)
        else -> TabSiteIcon(tab, size, shape = CircleShape)
    }
}

/**
 * Если превью ещё нет: вся площадь залита цветом сайта (тем же, что у плитки), по центру печенье со значком.
 * У каждого сайта свой цвет, поэтому вкладки без снимка не сливаются в серое поле. Приватная и стартовая - цвета темы.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TabPlaceholder(tab: Tab, compact: Boolean) {
    val cs = MaterialTheme.colorScheme
    val dark = cs.background.luminance() < 0.5f
    val (bg, fg) = when {
        tab.isPrivate -> cs.tertiaryContainer to cs.onTertiaryContainer
        tab.home -> cs.primaryContainer to cs.onPrimaryContainer
        else -> siteColors(tab.url, dark)
    }
    val cookie = if (compact) 64.dp else 104.dp
    val icon = if (compact) 30.dp else 48.dp
    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(bg, bg.copy(alpha = 0.78f).compositeOver(cs.surfaceContainerLowest)))),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(cookie).background(fg.copy(alpha = 0.16f), MaterialShapes.Cookie9Sided.toShape()), contentAlignment = Alignment.Center) {
            when {
                tab.isPrivate -> Icon(HripsIcons.Mask, null, Modifier.size(icon), tint = fg)
                tab.home -> Icon(HripsIcons.Home, null, Modifier.size(icon), tint = fg)
                else -> TabSiteIcon(tab, icon)
            }
        }
    }
}

/**
 * Источник превью начальной страницы: настоящая главная, разложенная в размер окна. Живёт в одном экземпляре
 * и никуда не рисуется сам, а только записывает своё изображение в [layer]. Раньше такая страница (сетка, поиск,
 * обои, значки сайтов) целиком собиралась заново в каждой карточке с начальной страницей, и это было главным тормозом.
 * Размер у элемента нулевой: касания до него не доходят, места он не занимает.
 */
@Composable
private fun HomePreviewSource(layer: GraphicsLayer) {
    val app = LocalContext.current.applicationContext as HripsApp
    val cfg = LocalConfiguration.current
    val wide = isWideWindow()
    val density = LocalDensity.current
    val fullW = cfg.screenWidthDp.dp
    val fullH = cfg.screenHeightDp.dp
    val wPx = with(density) { fullW.roundToPx() }
    val hPx = with(density) { fullH.roundToPx() }
    val noPick: (SearchEngine) -> Unit = remember { { } }
    Layout(
        content = {
            Box(Modifier.size(fullW, fullH).drawWithContent { layer.record { this@drawWithContent.drawContent() } }) {
                CompositionLocalProvider(LocalOrigins provides remember { OriginTracker() }) {
                    Box(Modifier.fillMaxSize().statusBarsPadding()) {
                        StartPage(
                            store = app.store,
                            wallpaper = app.wallpaper.image,
                            onOpen = {},
                            onSearch = {},
                            onScanQr = {},
                            engine = if (wide) null else SearchEngines.current,
                            onPickEngine = if (wide) null else noPick,
                        )
                    }
                }
            }
        },
    ) { measurables, _ ->
        val placeable = measurables.first().measure(Constraints.fixed(wPx, hPx))
        layout(0, 0) { placeable.place(0, 0) }
    }
}

/**
 * Миниатюра начальной страницы в карточке: копия общего слоя [layer], уменьшенная до карточки. Касания до неё не доходят
 * (их получает карточка). Масштаб как у снимков страниц (Crop): по большей стороне, чтобы карточка заполнялась без
 * пустой полосы (на планшете страница альбомная, а карточка почти квадратная). Лишнее по ширине срезается поровну с краёв.
 */
@Composable
private fun HomeTabPreview(layer: GraphicsLayer, modifier: Modifier = Modifier) {
    val cfg = LocalConfiguration.current
    val density = LocalDensity.current
    val fullW = with(density) { cfg.screenWidthDp.dp.toPx() }
    val fullH = with(density) { cfg.screenHeightDp.dp.toPx() }
    val bg = MaterialTheme.colorScheme.background
    Box(
        modifier.clipToBounds().drawBehind {
            drawRect(bg)
            val scale = maxOf(size.width / fullW, size.height / fullH)
            withTransform({
                translate(left = -(fullW * scale - size.width) / 2f, top = 0f)
                scale(scale, scale, pivot = Offset.Zero)
            }) { drawLayer(layer) }
        },
    )
}

/**
 * Снимок для карточки: свежий из памяти, иначе сохранённый на диске (после перезапуска). В сетке хватает маленькой
 * копии: она декодируется быстрее и занимает меньше памяти.
 */
@Composable
private fun rememberTabThumb(tab: Tab, compact: Boolean): ImageBitmap? {
    val context = LocalContext.current.applicationContext
    val fromDisk by produceState<ImageBitmap?>(TabThumbs.peek(tab.url, compact), tab.url, tab.thumbnail, compact) {
        value = if (tab.thumbnail == null && !tab.isPrivate && !tab.home && tab.url.isNotBlank()) TabThumbs.load(context, tab.url, small = compact) else null
    }
    val thumb = tab.thumbnail ?: fromDisk
    // Заранее отдаём снимок на загрузку в GPU (иначе он грузится в момент первого показа и кадр рвётся). Не в главном потоке
    LaunchedEffect(thumb) {
        if (thumb != null) withContext(Dispatchers.Default) { runCatching { thumb.asAndroidBitmap().prepareToDraw() } }
    }
    return thumb
}

@Composable
private fun EmptySlide(priv: Boolean, searching: Boolean, onNew: () -> Unit, modifier: Modifier = Modifier) {
    Surface(shape = HripsShapes.XXL, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier) {
        EmptyTabs(priv, searching, onNew, Modifier.fillMaxSize())
    }
}

@Composable
private fun EmptyTabs(priv: Boolean, searching: Boolean, onNew: () -> Unit, modifier: Modifier = Modifier.fillMaxSize().padding(bottom = 104.dp)) {
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        EmptyState(
            icon = if (priv) HripsIcons.Mask else HripsIcons.Search,
            title = when {
                searching -> "Ничего не найдено"
                priv -> "Нет приватных вкладок"
                else -> "Нет открытых вкладок"
            },
            text = when {
                searching -> "Попробуйте другой запрос"
                priv -> "История и cookies приватных вкладок не сохраняются"
                else -> "Откройте новую вкладку, чтобы начать"
            },
            badge = if (priv) Badge.CLOVER else Badge.COOKIE9,
        )
        if (!searching) {
            FilledTonalButton(shapes = ButtonDefaults.shapes(), onClick = onNew) { Text(if (priv) "Приватная вкладка" else "Новая вкладка") }
        }
    }
}

/** Нижняя плавающая панель Expressive: вид, история, счётчик (вернуться к странице), ещё; рядом большая кнопка «+» в форме печенья. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SwitcherBar(
    grid: Boolean,
    count: Int,
    privatePage: Boolean,
    onToggleGrid: () -> Unit,
    onHistory: () -> Unit,
    onNew: () -> Unit,
    onDone: () -> Unit,
    onCloseAll: () -> Unit,
    closedCount: Int,
    onClosed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    val fabBg by animateColorAsState(if (privatePage) cs.tertiary else cs.primary, label = "fabBg")
    val fabFg by animateColorAsState(if (privatePage) cs.onTertiary else cs.onPrimary, label = "fabFg")
    Box(modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HripsIsland {
                BarButton(onClick = onToggleGrid) {
                    Icon(if (grid) HripsIcons.Rows else HripsIcons.Grid, if (grid) "Показать каруселью" else "Показать сеткой")
                }
                BarButton(onClick = onHistory) { Icon(HripsIcons.History, "История") }
                TabCounterButton(count, onClick = onDone)
                Box {
                    BarButton(onClick = { menu = true }) { Icon(HripsIcons.MoreVert, "Ещё") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, shape = HripsShapes.L) {
                        if (!privatePage) {
                            DropdownMenuItem(
                                text = { Text(if (closedCount > 0) "Недавно закрытые ($closedCount)" else "Недавно закрытые") },
                                leadingIcon = { Icon(HripsIcons.History, null) },
                                enabled = closedCount > 0,
                                onClick = { menu = false; onClosed() },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(if (privatePage) "Закрыть приватные вкладки" else "Закрыть все вкладки") },
                            leadingIcon = { Icon(HripsIcons.Trash, null) },
                            onClick = { menu = false; onCloseAll() },
                        )
                    }
                }
            }
            // Печенье живое: при нажатии сжимается, после нажатия делает четверть оборота (плюс при этом остаётся плюсом)
            val fabSource = remember { MutableInteractionSource() }
            val fabPress = remember { Animatable(1f) }
            val fabPressSpec = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
            LaunchedEffect(fabSource) {
                fabSource.interactions.collect { i ->
                    when (i) {
                        is PressInteraction.Press -> launch { fabPress.animateTo(0.88f, fabPressSpec) }
                        is PressInteraction.Release, is PressInteraction.Cancel -> launch { fabPress.animateTo(1f, fabPressSpec) }
                        else -> Unit
                    }
                }
            }
            val fabTurn = remember { Animatable(0f) }
            val fabTurnSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
            val fabHaptic = LocalHapticFeedback.current
            val fabScope = rememberCoroutineScope()
            FloatingActionButton(
                onClick = {
                    fabHaptic.performHapticFeedback(HapticFeedbackType.Confirm)
                    fabScope.launch { fabTurn.animateTo(fabTurn.targetValue + 90f, fabTurnSpec) }
                    onNew()
                },
                shape = MaterialShapes.Cookie9Sided.toShape(),
                containerColor = fabBg,
                contentColor = fabFg,
                interactionSource = fabSource,
                modifier = Modifier.size(68.dp).graphicsLayer {
                    rotationZ = fabTurn.value
                    scaleX = fabPress.value
                    scaleY = fabPress.value
                },
            ) {
                Icon(HripsIcons.Add, if (privatePage) "Новая приватная вкладка" else "Новая вкладка", Modifier.size(30.dp))
            }
        }
    }
}
