package com.hrips.browser

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object HripsLayout {
    const val PhoneBarWideFrom = 360

    val PhoneBarInset = 4.dp
    val PhoneBarGap = 8.dp

    // Нижняя панель (размеры сняты с образцов для экрана 360 dp, точность около ±1 dp): три капсулы по 42 dp.
    val BarHeight = 42.dp
    val BarSideMargin = 26.dp
    val BarTextSp = 14
    // Свёрнутая пилюля: только домен, ширина по тексту.
    val BarCompactHeight = 25.dp
    val BarCompactPadding = 15.dp
    val BarCompactTextSp = 10
    val BarGap = 8.dp
    val BarMargin = 12.dp

    // Ряд поиска: кружок «Готово» и капсулы одной высоты.
    val FindPill = 38.dp
    val FindCircle = FindPill
    val FindGap = 12.dp
    val FindTextSp = 14

    val PageMaxWidth = 720.dp
    val PageGutter = 16.dp

    fun phoneBarButton(screenWidthDp: Int): Dp = if (screenWidthDp >= PhoneBarWideFrom) 48.dp else 40.dp

    fun phoneBarHeight(button: Dp): Dp = button + PhoneBarInset * 2

    fun phoneIslandSpace(button: Dp): Dp = phoneBarHeight(button) + PhoneBarGap
}

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
            navigationIcon = {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.padding(start = 4.dp),
                    colors = IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                ) { Icon(HripsIcons.ChevronBack, "Назад") }
            },
            actions = actions,
            colors = TopAppBarDefaults.topAppBarColors(containerColor = surface, scrolledContainerColor = surface),
            scrollBehavior = scroll,
            modifier = Modifier.widthIn(max = HripsLayout.PageMaxWidth + HripsLayout.PageGutter * 2),
        )
    }
}

object HripsShapes {
    val IconFrame = RoundedCornerShape(6.dp)
    val S = RoundedCornerShape(16.dp)
    val M = RoundedCornerShape(20.dp)
    val ML = RoundedCornerShape(24.dp)
    val L = RoundedCornerShape(28.dp)
    val XL = RoundedCornerShape(32.dp)

    fun segment(index: Int, count: Int): RoundedCornerShape {
        val top = if (index == 0) 28.dp else 8.dp
        val bottom = if (index == count - 1) 28.dp else 8.dp
        return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
    }
}

object HripsSpace {
    val S = 8.dp
    val L = 16.dp
    val XL = 24.dp
}

/** Радиусы углов сегмента в dp (для морфинга при нажатии). Для не-скруглённых форм берётся большой радиус. */
@Composable
internal fun Shape.asCorners(): Corners {
    val d = LocalDensity.current
    val r = this as? CornerBasedShape ?: return Corners.all(28.dp)
    return with(d) {
        Corners(
            r.topStart.toPx(Size.Zero, d).toDp(),
            r.topEnd.toPx(Size.Zero, d).toDp(),
            r.bottomEnd.toPx(Size.Zero, d).toDp(),
            r.bottomStart.toPx(Size.Zero, d).toDp(),
        )
    }
}
