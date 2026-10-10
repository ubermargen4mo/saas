package com.hrips.browser

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Размеры раскладки, общие для всех страниц на весь экран (история, закладки, загрузки, настройки, расширения).
 * Раньше число 720 и боковые поля были записаны отдельно в каждом файле, и страницы расходились:
 * у настроек карточки были уже, чем у истории, на 32dp.
 */
object HripsLayout {
    /** Нижняя панель на телефоне: размер кнопки зависит от ширины экрана (на узких 40dp, иначе 48dp - минимальная область нажатия). */
    fun phoneBarButton(screenWidthDp: Int): Dp = if (screenWidthDp >= PhoneBarWideFrom) 48.dp else 40.dp

    /** Ширина экрана, с которой кнопки панели 48dp. */
    const val PhoneBarWideFrom = 360

    /** Вертикальный отступ внутри панели (сверху и снизу) и зазор от неё до нижнего края. */
    val PhoneBarInset = 4.dp
    val PhoneBarGap = 8.dp

    /** Высота нижней панели на телефоне без системной полосы. */
    fun phoneBarHeight(button: Dp): Dp = button + PhoneBarInset * 2

    /**
     * Сколько внизу экрана занимает плавающая панель (вместе с зазором): столько надо оставить под ней страницам,
     * которые лежат на всю высоту. Системную полосу навигации добавляет вызывающий.
     */
    fun phoneIslandSpace(button: Dp): Dp = phoneBarHeight(button) + PhoneBarGap

    /** Ширина колонки с карточками на широких экранах. На телефоне колонка занимает всю ширину за вычетом полей. */
    val PageMaxWidth = 720.dp

    /** Боковое поле страницы. */
    val PageGutter = 16.dp

    /** Предел ширины для переключателя из двух сегментов: на планшете его не растягиваем на весь экран. */
    val SegmentedMaxWidth = 400.dp
}

/**
 * Большой сворачивающийся заголовок страницы. На широком экране он стоит в той же колонке, что и карточки:
 * раньше кнопка «назад» и название уезжали к краю экрана, а список оставался по центру.
 * Цвет не меняется при прокрутке: список лежит под панелью, а не за ней, и подсветка сужённой панели
 * выглядела бы как прямоугольник посреди экрана.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PageTopBar(
    title: String,
    onBack: () -> Unit,
    scroll: TopAppBarScrollBehavior,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val surface = MaterialTheme.colorScheme.surface
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        LargeTopAppBar(
            title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(HripsIcons.Back, "Назад") } },
            actions = actions,
            colors = TopAppBarDefaults.topAppBarColors(containerColor = surface, scrolledContainerColor = surface),
            scrollBehavior = scroll,
            modifier = Modifier.widthIn(max = HripsLayout.PageMaxWidth + HripsLayout.PageGutter * 2),
        )
    }
}

/**
 * Шкала скруглений: 12 / 16 / 20 / 28 / 32 / 36. Раньше в коде было 11 разных значений (12-36 с шагом 2),
 * из-за чего похожие по роли элементы отличались на пару dp. Полный круг - `CircleShape`.
 */
object HripsShapes {
    val XS = RoundedCornerShape(12.dp)
    val S = RoundedCornerShape(16.dp)
    val M = RoundedCornerShape(20.dp)
    val L = RoundedCornerShape(28.dp)
    val XL = RoundedCornerShape(32.dp)
    val XXL = RoundedCornerShape(36.dp)

    /**
     * Строка в группе: большое скругление только у крайних, между соседями малое (приём M3 Expressive для списков).
     * Раньше три копии этой функции жили в настройках, загрузках и поиске.
     */
    fun segment(index: Int, count: Int): RoundedCornerShape {
        val top = if (index == 0) 28.dp else 8.dp
        val bottom = if (index == count - 1) 28.dp else 8.dp
        return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
    }
}

/** Шкала отступов (шаг 4dp): новые элементы берут значения отсюда, а не новые числа. */
object HripsSpace {
    val XS = 4.dp
    val S = 8.dp
    val M = 12.dp
    val L = 16.dp
    val XL = 24.dp
    val XXL = 32.dp
}
