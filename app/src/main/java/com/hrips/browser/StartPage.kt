package com.hrips.browser

import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StartPage(store: Store, wallpaper: ImageBitmap?, onOpen: (String) -> Unit, onSearch: () -> Unit, onScanQr: () -> Unit, modifier: Modifier = Modifier, engine: SearchEngine? = null, onPickEngine: ((SearchEngine) -> Unit)? = null) {
    val wide = isWideWindow()
    // На обоях текст всегда белый, на обычном фоне цвет берётся из темы
    val textColor = if (wallpaper != null) Color.White else MaterialTheme.colorScheme.onSurface
    var showAdd by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<Entry?>(null) }
    var toEdit by remember { mutableStateOf<Entry?>(null) }
    var menuKey by remember { mutableStateOf<String?>(null) }
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val gridState = rememberLazyGridState()
    val drag = remember { DialDrag() }
    // В Опере строка занимает 1120px из 2560px экрана, то есть 43.75% ширины окна. Берём ту же долю
    // (не меньше 360dp и не больше 640dp), чтобы длина совпадала на любой плотности экрана.
    val screenW = LocalConfiguration.current.screenWidthDp
    val barMax = if (wide) (screenW * 0.4375f).dp.coerceIn(360.dp, 640.dp) else 640.dp
    // Телефон: ярлыки чуть меньше, а внизу оставляем место под плавающую панель (она лежит поверх страницы)
    val tile = if (wide) 72.dp else 62.dp
    val tileR = if (wide) 28.dp else 24.dp
    val islandSpace = if (wide) 0.dp else HripsLayout.phoneIslandSpace(HripsLayout.phoneBarButton(screenW)) + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(modifier.fillMaxSize()) {
    if (wallpaper != null) {
        Image(wallpaper, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(minSize = if (wide) 96.dp else 76.dp),
            modifier = Modifier.widthIn(max = 880.dp).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 24.dp, end = 16.dp, bottom = 24.dp + islandSpace),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
                Column(
                    Modifier.fillMaxWidth().padding(top = 32.dp, bottom = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Логотип с надписью одной прозрачной картинкой; красится в цвет текста (белый на обоях)
                    Image(
                        painter = painterResource(R.drawable.logo_hrips),
                        contentDescription = "hrips",
                        modifier = Modifier.height(if (wide) 104.dp else 72.dp),
                        contentScale = ContentScale.Fit,
                        colorFilter = ColorFilter.tint(textColor),
                    )
                    Spacer(Modifier.height(24.dp))
                    // Вид строки снят со скриншота Оперы (плотность 2.0): высота 56dp, края скруглены полностью,
                    // значки по центру «ячеек» 56dp слева и справа, текст начинается на 56dp от левого края.
                    Surface(
                        onClick = onSearch,
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.widthIn(max = barMax).fillMaxWidth().heightIn(min = 56.dp).originAnchor("search"),
                    ) {
                        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                            if (engine != null && onPickEngine != null) {
                                // Телефон: верхней строки нет, выбор движка прямо в строке поиска
                                Box(Modifier.padding(start = 6.dp)) { EnginePicker(engine, onPickEngine) }
                            } else {
                                Box(Modifier.width(56.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                                    Icon(HripsIcons.Search, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Text(
                                "Искать или задать вопрос",
                                modifier = Modifier.weight(1f).padding(start = if (engine != null && onPickEngine != null) 10.dp else 0.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            // QR сразу открывает сканер, остальная строка - поиск
                            Box(
                                Modifier.width(56.dp).fillMaxHeight().clip(CircleShape).clickable(onClick = onScanQr),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(HripsIcons.Qr, "Сканировать QR-код", Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            items(store.speedDial.toList(), key = { tileKey(it) }) { e ->
                val k = tileKey(e)
                val lifted = drag.key == k
                val settling = drag.settling == k
                // Взятая плитка «приподнимается»: растёт и отбрасывает тень, после отпускания мягко садится на место
                val scale by animateFloatAsState(
                    if (lifted) 1.14f else 1f,
                    spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium),
                    label = "dialLift",
                )
                val elevation by animateDpAsState(if (lifted) 16.dp else 0.dp, label = "dialElevation")
                // Нажатие: плитка сжимается, как кнопки панели
                val tileSource = remember(k) { MutableInteractionSource() }
                val tilePressed by tileSource.collectIsPressedAsState()
                val press by animateFloatAsState(
                    if (tilePressed && !lifted) HripsMotion.PressScale else 1f,
                    MaterialTheme.motionScheme.fastSpatialSpec<Float>(),
                    label = "dialPress",
                )

                fun reorder() {
                    val infos = gridState.layoutInfo.visibleItemsInfo
                    val me = infos.firstOrNull { it.key == k } ?: return
                    val cx = drag.startOffset.x + drag.delta.x + me.size.width / 2f
                    val cy = drag.startOffset.y + drag.delta.y + me.size.height / 2f
                    val keys = store.speedDial.map { tileKey(it) }
                    val target = infos.firstOrNull {
                        it.key != k && it.key in keys &&
                            cx >= it.offset.x && cx < it.offset.x + it.size.width &&
                            cy >= it.offset.y && cy < it.offset.y + it.size.height
                    } ?: return
                    val from = keys.indexOf(k)
                    val to = keys.indexOf(target.key)
                    if (from >= 0 && to >= 0 && from != to) {
                        haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        store.moveDial(from, to)
                    }
                }

                fun finish() {
                    if (drag.key != k) return
                    val from = drag.translation(gridState, k)
                    val wasMoved = drag.moved
                    drag.key = null
                    drag.settling = k
                    store.saveDial()
                    scope.launch {
                        drag.settle.snapTo(from)
                        // Подержали и отпустили, не двигая: вместо перестановки показываем меню плитки
                        if (!wasMoved) menuKey = k
                        drag.settle.animateTo(Offset.Zero, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow))
                        if (drag.settling == k) drag.settling = null
                    }
                }

                Box(
                    Modifier
                        // Пока плитку несут, место она не анимирует (её двигает палец); остальные плавно расступаются
                        .then(if (lifted || settling) Modifier else Modifier.animateItem())
                        .zIndex(if (lifted || settling) 1f else 0f)
                        .graphicsLayer {
                            val t = when {
                                lifted -> drag.translation(gridState, k)
                                settling -> drag.settle.value
                                else -> Offset.Zero
                            }
                            translationX = t.x
                            translationY = t.y
                            scaleX = scale * press
                            scaleY = scale * press
                        },
                ) {
                    Column(
                        Modifier
                            .combinedClickable(interactionSource = tileSource, indication = LocalIndication.current, onClick = { onOpen(e.url) }, onLongClick = {})
                            .pointerInput(k) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        val info = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == k }
                                        if (info != null) {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            menuKey = null
                                            drag.begin(k, info.offset)
                                        }
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        drag.delta += amount
                                        if (drag.delta.getDistance() > viewConfiguration.touchSlop) drag.moved = true
                                        reorder()
                                    },
                                    onDragEnd = { finish() },
                                    onDragCancel = { finish() },
                                )
                            }
                            .padding(4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        val (bg, fg) = tileColors(e.title)
                        Surface(
                            shape = RoundedCornerShape(tileR),
                            color = bg,
                            shadowElevation = elevation,
                            modifier = Modifier.size(tile),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Favicon(e.url, tile, fill = true) {
                                    SiteTile(e.url, e.title.ifBlank { siteKey(e.url) ?: e.url }, tile, shape = RoundedCornerShape(tileR))
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            e.title,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodySmall,
                            color = textColor,
                        )
                    }
                    DropdownMenu(expanded = menuKey == k, onDismissRequest = { if (menuKey == k) menuKey = null }) {
                        DropdownMenuItem(
                            text = { Text("Изменить") },
                            leadingIcon = { Icon(HripsIcons.Pencil, null, Modifier.size(20.dp)) },
                            onClick = { menuKey = null; toEdit = e },
                        )
                        DropdownMenuItem(
                            text = { Text("Удалить") },
                            leadingIcon = { Icon(HripsIcons.Trash, null, Modifier.size(20.dp)) },
                            onClick = { menuKey = null; toDelete = e },
                        )
                    }
                }
            }

            item(key = "add") {
                Column(
                    Modifier.animateItem().clickable { showAdd = true }.padding(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Surface(
                        shape = RoundedCornerShape(tileR),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.size(tile),
                    ) {
                        Box(contentAlignment = Alignment.Center) { Icon(HripsIcons.Add, "Добавить") }
                    }
                }
            }
        }
    }
    }

    if (showAdd) {
        DialDialog(
            icon = HripsIcons.Add,
            title = "Новая плитка",
            confirm = "Добавить",
            initialName = "",
            initialAddress = "",
            onDismiss = { showAdd = false },
        ) { name, address ->
            val url = toUrl(address)
            store.addDial(url, name.ifBlank { Uri.parse(url).host?.removePrefix("www.") ?: url })
            showAdd = false
        }
    }

    toEdit?.let { e ->
        DialDialog(
            icon = HripsIcons.Pencil,
            title = "Изменить плитку",
            confirm = "Сохранить",
            initialName = e.title,
            initialAddress = e.url,
            onDismiss = { toEdit = null },
        ) { name, address ->
            val url = toUrl(address)
            store.updateDial(e, url, name.ifBlank { Uri.parse(url).host?.removePrefix("www.") ?: url })
            toEdit = null
        }
    }

    toDelete?.let { e ->
        HripsDialog(
            icon = HripsIcons.Trash,
            onDismissRequest = { toDelete = null },
            title = { Text("Удалить плитку?") },
            text = { Text(e.title) },
            confirmButton = { HripsTextButton(onClick = { store.removeDial(e); toDelete = null }) { Text("Удалить") } },
            dismissButton = { HripsTextButton(onClick = { toDelete = null }) { Text("Отмена") } },
        )
    }
}

/** Цвета плитки берутся из темы и выбираются по названию, чтобы были стабильными. */
@Composable
private fun tileColors(key: String): Pair<Color, Color> {
    val c = MaterialTheme.colorScheme
    val options = listOf(
        c.primaryContainer to c.onPrimaryContainer,
        c.secondaryContainer to c.onSecondaryContainer,
        c.tertiaryContainer to c.onTertiaryContainer,
    )
    return options[Math.floorMod(key.hashCode(), options.size)]
}

/** Ключ плитки в сетке: по нему сетка узнаёт плитку при перестановке и анимирует её переезд. */
private fun tileKey(e: Entry) = "${e.time}|${e.url}|${e.title}"

/** Состояние перетаскивания плитки. Положение плитки = старт + сдвиг пальца, независимо от того, куда её переставила сетка. */
private class DialDrag {
    var key by mutableStateOf<String?>(null)
    var settling by mutableStateOf<String?>(null)
    var startOffset by mutableStateOf(IntOffset.Zero)
    var delta by mutableStateOf(Offset.Zero)
    var moved = false
    /** Возврат отпущенной плитки в свою ячейку. */
    val settle = Animatable(Offset.Zero, Offset.VectorConverter)

    fun begin(k: String, offset: IntOffset) {
        key = k
        settling = null
        startOffset = offset
        delta = Offset.Zero
        moved = false
    }

    /** На сколько плитку надо сдвинуть от её текущей ячейки, чтобы она была под пальцем. */
    fun translation(state: LazyGridState, k: String): Offset {
        val cur = state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == k }?.offset ?: startOffset
        return Offset(startOffset.x + delta.x - cur.x, startOffset.y + delta.y - cur.y)
    }
}

/** Диалог плитки: название и адрес. Один для добавления и для изменения. */
@Composable
private fun DialDialog(
    icon: ImageVector,
    title: String,
    confirm: String,
    initialName: String,
    initialAddress: String,
    onDismiss: () -> Unit,
    onConfirm: (name: String, address: String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var address by remember { mutableStateOf(initialAddress) }
    HripsDialog(
        icon = icon,
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HripsField(name, { name = it }, label = { Text("Название") }, singleLine = true)
                HripsField(address, { address = it }, label = { Text("Адрес") }, singleLine = true)
            }
        },
        confirmButton = { HripsTextButton(enabled = address.isNotBlank(), onClick = { onConfirm(name, address) }) { Text(confirm) } },
        dismissButton = { HripsTextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
