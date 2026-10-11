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
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Rect as UiRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
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
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

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

private fun tabKey(t: Tab) = t.id

/** Элемент карусели: вкладка или карточка-заглушка пустого раздела. Сравнивается по содержимому, чтобы список не считался новым без причины. */
private data class Slide(val tab: Tab?, val index: Int, val priv: Boolean) {
    val key: Any get() = tab?.let { tabKey(it) } ?: if (priv) "empty-private" else "empty-normal"
}

/** Нажатая карточка: где она на экране и какая на ней картинка. */
private class Expand(val rect: UiRect, val thumb: ImageBitmap?, val index: Int)

/** Координаты карточки. Обычная ссылка, а не состояние: запись в состояние на каждом кадре прокрутки перерисовывала бы лишнее. */
private class CoordsHolder {
    var c: LayoutCoordinates? = null
    fun rect(): UiRect = c?.takeIf { it.isAttached }?.boundsInRoot() ?: UiRect.Zero
}

/**
 * Всё, что нужно карточкам от переключателя, в одном стабильном объекте. Раньше каждой карточке передавалось по семь
 * лямбд, которые пересоздавались при любой перерисовке переключателя, и все видимые карточки перерисовывались вместе с ним.
 * Теперь поля меняются на месте, а карточка получает только свои данные и ссылку на этот объект.
 */
@Stable
private class CardEnv {
    lateinit var browser: Browser
    lateinit var homeLayer: GraphicsLayer
    var currentHolder: CoordsHolder? = null
    var onOpen: (Int, UiRect, ImageBitmap?) -> Unit = { _, _, _ -> }
    var onClose: (Int) -> Unit = {}

    /** Сколько карточек сейчас тянут пальцем или они улетают. Читает только отложенное закрытие сессий: ждёт нуля */
    var busy by mutableIntStateOf(0)
}

/** Во сколько порогов закрытия карточка оттянута, когда её прозрачность дошла до нуля: дальше она не видна */
private const val SWIPE_GONE = 2.2f

// Пустой запрос: ничего не читаем. Иначе список зависел бы от заголовка и адреса каждой вкладки и пересчитывался
// (с компиляцией Regex на каждую вкладку) всякий раз, когда любая вкладка в фоне меняла заголовок
private fun Tab.matches(q: String) = q.isEmpty() || matchesQuery(label(), url, q)

/**
 * Переключатель вкладок на весь экран в стиле Material 3 Expressive: переключатель «Вкладки / Приватные»
 * со сменой формы кнопок, карусель крупных карточек с живым превью страницы (центральная крупнее, соседние
 * уменьшаются и бледнеют) или сетка, плавающая панель с большой кнопкой «+». Карточку можно смахнуть вверх, чтобы закрыть.
 *
 * Что сделано ради плавности:
 * - списки и счётчики считаются через derivedStateOf: переключатель перерисовывается, только когда результат изменился,
 *   а не при каждой смене заголовка или адреса любой вкладки;
 * - всё, что меняется на каждом кадре (смахивание, нажатие, прозрачность соседних карточек, ползунок), читается
 *   только на этапе рисования и не вызывает перерисовку;
 * - превью начальной страницы рисуется один раз в общий слой и копируется во все такие карточки;
 * - положение карточки для анимации открытия запрашивается по требованию, а не на каждом кадре прокрутки.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TabSwitcher(browser: Browser, onClose: () -> Unit, onHistory: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var grid by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(searching) { if (searching) focus.requestFocus() }

    var showClosed by remember { mutableStateOf(false) }
    // Растёт при каждом закрытии: по нему отложенно отпускаются сессии закрытых вкладок (см. LaunchedEffect ниже)
    var closeTick by remember { mutableIntStateOf(0) }
    // Закрытие карточки с возможностью сразу вернуть вкладку. Движок при этом не трогаем: убираем вкладку только из
    // списка, сессию закроем, когда всё затихнет (иначе закрытие сессии и поднятие соседней вкладки рвали анимацию)
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
    // 0..1: насколько карточка раскрылась в страницу. По нему гаснут шапка, список и нижняя панель, чтобы не исчезать щелчком в конце
    val expandP = remember { Animatable(0f) }
    val chromeAlpha: () -> Float = { 1f - (expandP.value / 0.6f).coerceIn(0f, 1f) }
    val origins = LocalOrigins.current

    val current = browser.currentIndex
    val q = query.trim()

    val normalCount by remember(browser) { derivedStateOf { browser.tabs.count { !it.isPrivate } } }
    val privateCount by remember(browser) { derivedStateOf { browser.tabs.size - normalCount } }
    val hasHome by remember(browser) { derivedStateOf { browser.tabs.any { it.home && !it.isPrivate } } }

    // Списки вкладок с учётом поиска (пары «номер в общем списке, вкладка»)
    val normalList by remember(browser, q) {
        derivedStateOf {
            browser.tabs.mapIndexedNotNull { i, t ->
                if (!t.isPrivate && t.matches(q)) i to t else null
            }
        }
    }
    val privList by remember(browser, q) {
        derivedStateOf { browser.tabs.mapIndexedNotNull { i, t -> if (t.isPrivate && t.matches(q)) i to t else null } }
    }

    // Карусель одна на все вкладки: сначала обычные, потом приватные. Поэтому к приватным
    // можно перейти одним обычным смахиванием, без отдельного «перелистывания страницы».
    // Пустой раздел представлен одной карточкой-заглушкой.
    val slides by remember {
        derivedStateOf {
            buildList {
                normalList.forEach { add(Slide(it.second, it.first, false)) }
                if (normalList.isEmpty()) add(Slide(null, -1, false))
                privList.forEach { add(Slide(it.second, it.first, true)) }
                if (privList.isEmpty()) add(Slide(null, -1, true))
            }
        }
    }

    // 0 = обычные, 1 = приватные. Источник правды для шапки, фона и нижней панели
    var mode by remember { mutableIntStateOf(if (browser.current.isPrivate) 1 else 0) }
    val firstSlide = { m: Int ->
        slides.indexOfFirst { it.index == current && it.priv == (m == 1) }
            .takeIf { it >= 0 } ?: slides.indexOfFirst { it.priv == (m == 1) }.coerceAtLeast(0)
    }
    val cp = rememberPagerState(initialPage = firstSlide(mode)) { slides.size }
    val gp = rememberPagerState(initialPage = mode) { 2 }

    LaunchedEffect(grid) {
        if (grid) gp.scrollToPage(mode) else cp.scrollToPage(firstSlide(mode))
    }
    LaunchedEffect(cp) {
        snapshotFlow { cp.currentPage }.collect { p ->
            if (!grid) slides.getOrNull(p)?.let { mode = if (it.priv) 1 else 0 }
        }
    }
    LaunchedEffect(gp) {
        snapshotFlow { gp.currentPage }.collect { if (grid) mode = it }
    }

    // Положение между «обычными» (0) и «приватными» (1), плавно следует за пальцем. Читается только при рисовании
    val progress: () -> Float = {
        if (grid) {
            (gp.currentPage + gp.currentPageOffsetFraction).coerceIn(0f, 1f)
        } else {
            val list = slides
            val pos = (cp.currentPage + cp.currentPageOffsetFraction).coerceIn(0f, list.lastIndex.toFloat())
            val lo = pos.toInt()
            val hi = (lo + 1).coerceAtMost(list.lastIndex)
            val f = pos - lo
            val a = if (list[lo].priv) 1f else 0f
            val b = if (list[hi].priv) 1f else 0f
            a + (b - a) * f
        }
    }
    // Смена режима «карусель / сетка» проявляется плавно, а не подменой за один кадр
    val modeFadeIn = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val modeFadeOut = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val modeScale = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    // Закрытие карточки в карусели: пейджер сам ничего не анимирует, и соседние карточки встали бы на новые места
    // за один кадр. Запоминаем, что куда сдвинулось, и рисуем сдвиг обратно, плавно сводя его к нулю
    val shiftInfo = remember { arrayOfNulls<PagerShift>(1) }
    val shift = remember { Animatable(0f) }
    val bgNormal = cs.surface
    val bgPrivate = lerp(cs.surface, cs.tertiaryContainer, 0.55f)

    val select: (Int) -> Unit = { m ->
        scope.launch { if (grid) gp.animateScrollToPage(m) else cp.animateScrollToPage(firstSlide(m)) }
    }

    // Общий слой с превью начальной страницы: рисуется один раз, а не по экземпляру страницы на каждую карточку
    val homeLayer = rememberGraphicsLayer()

    val env = remember { CardEnv() }
    env.browser = browser
    env.homeLayer = homeLayer
    env.onOpen = { i, rect, thumb -> if (expanding == null) expanding = Expand(rect, thumb, i) }
    env.onClose = { i ->
        // Закрыли центральную карточку, которая последняя в своём разделе: карусель общая, и пейджер остался бы на том же
        // номере, то есть уехал бы в соседний раздел (из обычных в приватные). Идём к предыдущей карточке этого раздела
        val si = slides.indexOfFirst { it.index == i }
        val s0 = slides.getOrNull(si)
        val back = if (!grid && si > 0 && si == cp.currentPage && s0 != null &&
            slides.getOrNull(si - 1)?.let { it.priv == s0.priv && it.tab != null } == true &&
            slides.getOrNull(si + 1)?.priv != s0.priv
        ) si - 1 else null
        if (!grid && s0 != null && si >= 0) {
            val n = slides.size
            val c = cp.currentPage
            // Пейджер держит на месте первую видимую карточку по ключу: центр остаётся тем же, если убрали не его
            val c2 = when {
                si < c -> c - 1
                si > c -> c
                else -> minOf(c, n - 2).coerceAtLeast(0)
            }
            shiftInfo[0] = PagerShift(n, si, c, c2)
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                shift.snapTo(1f)
                shift.animateTo(0f, spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow))
            }
        }
        closeWithUndo(i)
        if (back != null) cp.requestScrollToPage(back)
    }

    // Сессии закрытых карточек отпускаем, когда прошло время без новых закрытий, листание стоит и карточки не тянут.
    // При выходе из переключателя отпускаем всё, что осталось. Ожидание ограничено, чтобы не зависнуть на жесте
    LaunchedEffect(closeTick) {
        if (closeTick == 0) return@LaunchedEffect
        delay(1200)
        withTimeoutOrNull(3000) {
            snapshotFlow { cp.isScrollInProgress || gp.isScrollInProgress || env.busy > 0 }.first { !it }
        }
        browser.flushPendingClose()
    }
    DisposableEffect(browser) { onDispose { browser.flushPendingClose() } }

    // Положение текущей карточки нужно анимации «страница сжимается в карточку» (PageSwap). Сообщаем его не на каждом
    // кадре, а когда карточка встала на место: после открытия, смены вкладки, переключения вида и когда прокрутка остановилась
    val reportRect = {
        if (expanding == null) {
            env.currentHolder?.c?.takeIf { it.isAttached }?.let { origins.currentCardRect = it.boundsInRoot() }
        }
    }
    LaunchedEffect(current, grid, mode) {
        repeat(2) { withFrameNanos { } }
        reportRect()
    }
    LaunchedEffect(cp, gp) {
        snapshotFlow { cp.isScrollInProgress || gp.isScrollInProgress }.filter { !it }.collect { reportRect() }
    }

    Box(
        Modifier
            .fillMaxSize()
            // Экран лежит поверх GeckoView: нажатия мимо карточек не должны попадать в сайт под ним
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        // Фон плавно меняет тон по мере смахивания к приватным вкладкам. Свой слой: иначе его перерисовка на каждом кадре
        // листания заставляла бы заново записывать и скрытую начальную страницу, лежащую в том же слое
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { }
                .drawBehind { drawRect(lerp(bgNormal, bgPrivate, progress()).copy(alpha = 1f - expandP.value.coerceIn(0f, 1f))) },
        )
        // Должен быть раньше карточек: тогда слой записан до того, как его рисуют
        if (hasHome) HomePreviewSource(homeLayer)

        Column(Modifier.fillMaxSize().statusBarsPadding().graphicsLayer { alpha = chromeAlpha() }) {
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

            AnimatedContent(
                targetState = grid,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                transitionSpec = {
                    (fadeIn(modeFadeIn) + scaleIn(modeScale, initialScale = 0.94f)) togetherWith
                        fadeOut(modeFadeOut) using SizeTransform(clip = false) { _, _ -> snap() }
                },
                label = "switcherMode",
            ) { isGrid ->
            if (isGrid) {
                // Сетка: два раздела, между ними переключаемся обычным смахиванием (вертикальная прокрутка не мешает)
                HorizontalPager(state = gp, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 1) { page ->
                    val priv = page == 1
                    val list = if (priv) privList else normalList
                    if (list.isEmpty()) {
                        EmptyTabs(priv, searching = q.isNotEmpty(), onNew = { browser.newTab(incognito = priv, returnToPrevious = false); onClose() })
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 170.dp),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 120.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            gridItems(list, key = { tabKey(it.second) }, contentType = { "tab" }) { (i, t) ->
                                TabCard(t, i, selected = i == current, compact = true, env = env, modifier = Modifier.fillMaxWidth().aspectRatio(0.78f).animateItem())
                            }
                        }
                    }
                }
            } else {
                BoxWithConstraints(Modifier.fillMaxSize().padding(bottom = 104.dp)) {
                    val cardW = minOf(maxWidth * 0.78f, 400.dp)
                    val cardH = minOf(cardW * (if (maxWidth > 600.dp) 1.0f else 1.4f), maxHeight - 16.dp)
                    HorizontalPager(
                        state = cp,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = (maxWidth - cardW) / 2),
                        pageSize = PageSize.Fixed(cardW),
                        pageSpacing = 14.dp,
                        beyondViewportPageCount = 1,
                        key = { slides.getOrNull(it)?.key ?: it },
                    ) { page ->
                        val slide = slides.getOrNull(page)
                        Box(
                            Modifier
                                .fillMaxSize()
                                // Центральная карточка крупнее и ярче, соседние уменьшаются и бледнеют.
                                // Всё читается при рисовании: кадр смахивания не вызывает перерисовку страниц
                                .graphicsLayer {
                                    // signed > 0: карточка правее центра. Боковые поворачиваются лицом к центру, как в coverflow
                                    val sh = shiftInfo[0]
                                    val extra = if (sh != null && slides.size == sh.n - 1) {
                                        // Где карточка стояла до закрытия соседней, минус где стоит теперь (в карточках)
                                        val old = if (page < sh.si) page else page + 1
                                        ((old - sh.c) - (page - sh.c2)).toFloat() * shift.value
                                    } else 0f
                                    translationX = extra * (cardW.toPx() + 14.dp.toPx())
                                    val signed = ((page - cp.currentPage) - cp.currentPageOffsetFraction + extra).coerceIn(-1.5f, 1.5f)
                                    val d = signed.absoluteValue.coerceIn(0f, 1f)
                                    val sc = 1f - 0.12f * d
                                    scaleX = sc
                                    scaleY = sc
                                    alpha = 1f - 0.3f * d
                                    // Прозрачность применяется прямо при рисовании, без отдельного буфера на каждую карточку
                                    compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
                                    rotationY = -signed.coerceIn(-1f, 1f) * 22f
                                    cameraDistance = 14f * density
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            val t = slide?.tab
                            if (slide == null) {
                                // Пустое место карусели: рисовать нечего
                            } else if (t == null) {
                                EmptySlide(
                                    slide.priv, searching = q.isNotEmpty(),
                                    onNew = { browser.newTab(incognito = slide.priv, returnToPrevious = false); onClose() },
                                    modifier = Modifier.width(cardW).height(cardH),
                                )
                            } else {
                                TabCard(t, slide.index, selected = slide.index == current, compact = false, env = env, modifier = Modifier.width(cardW).height(cardH))
                            }
                        }
                    }
                }
            }
            }
        }

        SwitcherBar(
            grid = grid,
            count = browser.tabs.size,
            onToggleGrid = { grid = !grid },
            onHistory = onHistory,
            onNew = { browser.newTab(incognito = mode == 1, returnToPrevious = false); onClose() },
            onDone = onClose,
            privatePage = mode == 1,
            onCloseAll = {
                closeTick++
                if (mode == 1) {
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
            },
            closedCount = browser.closedTabs.size,
            onClosed = { showClosed = true },
            modifier = Modifier.align(Alignment.BottomCenter).graphicsLayer { alpha = chromeAlpha() },
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

/**
 * Состояние жеста «смахнуть карточку вверх». Положение под пальцем и анимированное положение читаются только при
 * рисовании ([y]), поэтому кадр жеста не вызывает перерисовку.
 */
/** Закрытие карточки в карусели: было карточек [n], убрана [si], центр был [c], станет [c2]. */
private class PagerShift(val n: Int, val si: Int, val c: Int, val c2: Int)

@Stable
private class SwipeState(
    val scope: CoroutineScope,
    val haptic: HapticFeedback,
    /** Порог закрытия. Хватает короткого движения: раньше карточку приходилось тащить почти до верха */
    val dismissPx: Float,
    /** Быстрый бросок вверх закрывает карточку, даже если палец прошёл меньше порога (px/с) */
    val flickPx: Float,
    val flickMinPx: Float,
) {
    /** Анимируемое смещение (доводка, улёт) */
    val offsetY = Animatable(0f)

    /** «Живое» смещение под пальцем: палец пишет обычное число без корутины на каждое событие */
    var liveY by mutableFloatStateOf(0f)
    var dragging by mutableStateOf(false)
    var pastThreshold = false
    var hapticFired = false
    var onDismiss: () -> Unit = {}

    /** Общий счётчик активных жестов: отложенное закрытие сессий не должно попасть на движение карточки */
    var env: CardEnv? = null
    private var busy = false
    fun setBusy(b: Boolean) {
        if (b == busy) return
        busy = b
        env?.let { it.busy += if (b) 1 else -1 }
    }

    /**
     * Вибро-отклик вне главного потока. performHapticFeedback идёт в системный сервис и на ряде устройств занимает
     * заметное время; вызванный посреди жеста в главном потоке, он обрывал плавность. Если отклик не удался, это не страшно.
     */
    fun tick(type: HapticFeedbackType) {
        scope.launch(Dispatchers.Default) { runCatching { haptic.performHapticFeedback(type) } }
    }

    val y: Float get() = if (dragging) liveY else offsetY.value

    /**
     * Улёт вверх и закрытие. Вкладка закрывается в тот миг, когда карточка стала невидимой, а не когда пружина досчитает
     * хвост: раньше соседние карточки занимали освободившееся место спустя полсекунды после исчезновения этой, это
     * выглядело как подвисание, особенно после быстрого броска. Цель улёта чуть дальше точки, где карточка пропала.
     */
    suspend fun flyAway(velocity: Float) {
        var done = false
        offsetY.animateTo(-dismissPx * (SWIPE_GONE + 0.4f), HripsMotion.fling(), initialVelocity = velocity.coerceAtMost(0f)) {
            if (!done && value <= -dismissPx * SWIPE_GONE) {
                done = true
                onDismiss()
            }
        }
        if (!done) onDismiss()
    }

    /** Карточка улетает вверх, потом вкладка закрывается. Так же закрывает и кнопка, и меню */
    fun fly(velocity: Float = 0f) {
        scope.launch {
            setBusy(true)
            try {
                flyAway(velocity)
            } finally {
                setBusy(false)
            }
        }
    }
}

private fun Modifier.swipeToClose(s: SwipeState, enabled: Boolean): Modifier {
    // В сетке карточки лежат в прокручиваемом списке: смахивание вверх отнимало бы у него прокрутку.
    // Там карточку закрывают кнопкой
    if (!enabled) return this
    return pointerInput(s) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val velocity = VelocityTracker()
            velocity.addPosition(down.uptimeMillis, down.position)
            fun moveBy(dy: Float) {
                // Вверх карточка идёт чуть быстрее пальца (1.25x): жест ощущается лёгким. Вниз возвращается 1 к 1
                s.liveY = (s.liveY + if (dy < 0f) dy * 1.25f else dy).coerceAtMost(0f)
                // Короткий отклик один раз за жест, когда карточку оттянули достаточно для закрытия. Раньше он срабатывал
                // при каждом переходе через порог, и дрожание пальца на границе давало серию вызовов прямо посреди движения
                val past = s.liveY < -s.dismissPx
                if (past != s.pastThreshold) {
                    s.pastThreshold = past
                    if (past && !s.hapticFired) {
                        s.hapticFired = true
                        s.tick(HapticFeedbackType.GestureThresholdActivate)
                    }
                }
            }
            val first = awaitVerticalTouchSlopOrCancellation(down.id) { change, over ->
                change.consume()
                s.liveY = s.offsetY.value
                s.dragging = true
                s.setBusy(true)
                s.hapticFired = false
                moveBy(over)
            } ?: return@awaitEachGesture
            velocity.addPosition(first.uptimeMillis, first.position)
            val finished = verticalDrag(first.id) { change ->
                velocity.addPosition(change.uptimeMillis, change.position)
                change.consume()
                moveBy(change.positionChange().y)
            }
            val vy = if (finished) velocity.calculateVelocity().y else 0f
            s.pastThreshold = false
            val start = s.liveY
            s.scope.launch {
                try {
                    s.offsetY.snapTo(start)
                    s.dragging = false
                    val flick = finished && vy < -s.flickPx && start < -s.flickMinPx
                    if (finished && (start < -s.dismissPx || flick)) {
                        // Улетает с той скоростью, с какой бросили
                        s.flyAway(vy)
                    } else {
                        s.offsetY.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow), initialVelocity = vy)
                    }
                } finally {
                    s.setBusy(false)
                }
            }
        }
    }
}

/**
 * Карточка вкладки: значок, название и адрес, кнопка закрытия и крупное превью страницы (снимок экрана).
 * Выбранная выделена цветом и рамкой.
 * Нажатие открывает, смахивание вверх закрывает (удержания нет).
 *
 * Слоёв на карточку стало меньше: смещение, наклон, сжатие при нажатии, скругление и прозрачность - один слой
 * (раньше четыре вложенных: Box, Surface, clip, graphicsLayer). Рамка рисуется прямо в этом слое.
 */
@Composable
private fun TabCard(
    tab: Tab,
    index: Int,
    selected: Boolean,
    compact: Boolean,
    env: CardEnv,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val swipe = remember(density) {
        SwipeState(scope, haptic, with(density) { 64.dp.toPx() }, with(density) { 380.dp.toPx() }, with(density) { 10.dp.toPx() })
    }
    swipe.onDismiss = { env.onClose(index) }
    swipe.env = env
    DisposableEffect(swipe) { onDispose { swipe.setBusy(false) } }
    val holder = remember { CoordsHolder() }
    if (selected) SideEffect { env.currentHolder = holder }

    val thumb = rememberTabThumb(tab, compact)
    val subtitle = when {
        // Название таких вкладок уже «Приватная вкладка» / «Начальная страница»: подпись говорит другое, а не повторяет его
        tab.isPrivate && tab.home -> "История не сохраняется"
        tab.home -> "Новая вкладка"
        else -> runCatching { Uri.parse(tab.url).host }.getOrNull()?.removePrefix("www.") ?: tab.url
    }
    val cardShape = if (compact) HripsShapes.L else HripsShapes.XXL
    val inner = RoundedCornerShape(if (compact) 23.dp else 30.dp)

    // Рамка: выбранная вкладка в цвете акцента, остальные в тоне поверхности. Цвет читается при рисовании
    val frame = animateColorAsState(
        if (selected) cs.primary else cs.surfaceContainerHigh,
        MaterialTheme.motionScheme.defaultEffectsSpec<Color>(),
        label = "tabFrame",
    )
    // Нажатая карточка чуть проседает. Число читается при рисовании, перерисовки нет
    val source = remember { MutableInteractionSource() }
    val press = remember { Animatable(1f) }
    val pressSpec = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    LaunchedEffect(source) {
        source.interactions.collect { i ->
            when (i) {
                is PressInteraction.Press -> launch { press.animateTo(0.97f, pressSpec) }
                is PressInteraction.Release, is PressInteraction.Cancel -> launch { press.animateTo(1f, pressSpec) }
                else -> Unit
            }
        }
    }

    // Снаружи слоя нет: здесь только размер и координаты. Дальше два соседних слоя: сама карточка и значок «закрыть».
    Box(modifier.onPlaced { holder.c = it }) {
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val y = swipe.y
                translationY = y
                // Чем выше утащили, тем сильнее карточка сжимается и наклоняется, как отрываемый листок
                val pull = (-y / swipe.dismissPx).coerceIn(0f, 2f)
                val sc = (1f - 0.07f * pull) * press.value
                scaleX = sc
                scaleY = sc
                rotationZ = -pull * 3f
                // До нуля: улетевшая карточка исчезает совсем, а не пропадает щелчком при удалении вкладки
                alpha = 1f - (abs(y) / (swipe.dismissPx * SWIPE_GONE)).coerceIn(0f, 1f)
                shape = cardShape
                clip = true
                // Содержимое карточки во время жеста не меняется (значок «закрыть» вынесен в отдельный слой), поэтому оно
                // один раз рисуется в текстуру, а на каждом кадре двигается и наклоняется только готовая картинка
                compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
            }
            .drawBehind { drawRect(frame.value) }
            .swipeToClose(swipe, enabled = !compact)
            // Только нажатие: удержания нет, карточку закрывают смахиванием вверх или крестиком
            .clickable(
                interactionSource = source,
                indication = null,
                onClick = { env.onOpen(index, holder.rect(), thumb) },
            ),
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
                    // Начальная страница: настоящая главная, как на экране (копия общего слоя)
                    HomeTabPreview(env.homeLayer, Modifier.fillMaxSize())
                } else {
                    TabPlaceholder(tab, compact)
                }

                // Кнопка закрытия справа сверху
                Surface(
                    onClick = {
                        swipe.tick(HapticFeedbackType.Confirm)
                        swipe.fly()
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
    }
        // Значок появляется сверху, когда карточку оттянули достаточно, чтобы она закрылась. Он лежит рядом с карточкой,
        // а не внутри неё: меняется на каждом кадре смахивания, и внутри слоя карточки заставлял бы заново рисовать
        // всю карточку (картинку, скругления, текст) на каждом кадре
        if (!compact) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        // Те же смещение, наклон и прозрачность, что у карточки: значок летит вместе с ней
                        val y = swipe.y
                        translationY = y
                        val pull = (-y / swipe.dismissPx).coerceIn(0f, 2f)
                        val sc = 1f - 0.07f * pull
                        scaleX = sc
                        scaleY = sc
                        rotationZ = -pull * 3f
                        alpha = 1f - (abs(y) / (swipe.dismissPx * SWIPE_GONE)).coerceIn(0f, 1f)
                        // Значок маленький: без отдельного буфера размером с карточку
                        compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
                    },
            ) {
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 14.dp)
                        .size(44.dp)
                        .graphicsLayer {
                            val k = (-swipe.y / swipe.dismissPx).coerceIn(0f, 1f)
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
