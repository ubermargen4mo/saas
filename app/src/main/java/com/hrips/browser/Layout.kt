package com.hrips.browser

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.unit.dp

/*
 * Размеры раскладки, общие для всех страниц на весь экран (история, закладки, загрузки, настройки, расширения).
 * Раньше число 720 и боковые поля были записаны отдельно в каждом файле, и страницы расходились:
 * у настроек карточки были уже, чем у истории, на 32dp.
 */
object HripsLayout {
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
