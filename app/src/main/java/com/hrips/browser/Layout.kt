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

object HripsLayout {
    fun phoneBarButton(screenWidthDp: Int): Dp = if (screenWidthDp >= PhoneBarWideFrom) 48.dp else 40.dp

    const val PhoneBarWideFrom = 360

    val PhoneBarInset = 4.dp
    val PhoneBarGap = 8.dp

    val BarHeight = 52.dp
    val BarCompactHeight = 38.dp
    val BarGap = 8.dp
    val BarMargin = 12.dp

    fun phoneBarHeight(button: Dp): Dp = button + PhoneBarInset * 2

    fun phoneIslandSpace(button: Dp): Dp = phoneBarHeight(button) + PhoneBarGap

    val PageMaxWidth = 720.dp

    val PageGutter = 16.dp

    val SegmentedMaxWidth = 400.dp
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
            navigationIcon = { IconButton(onClick = onBack) { Icon(HripsIcons.Back, "Назад") } },
            actions = actions,
            colors = TopAppBarDefaults.topAppBarColors(containerColor = surface, scrolledContainerColor = surface),
            scrollBehavior = scroll,
            modifier = Modifier.widthIn(max = HripsLayout.PageMaxWidth + HripsLayout.PageGutter * 2),
        )
    }
}

object HripsShapes {
    val XS = RoundedCornerShape(12.dp)
    val S = RoundedCornerShape(16.dp)
    val M = RoundedCornerShape(20.dp)
    val L = RoundedCornerShape(28.dp)
    val XL = RoundedCornerShape(32.dp)
    val XXL = RoundedCornerShape(36.dp)

    fun segment(index: Int, count: Int): RoundedCornerShape {
        val top = if (index == 0) 28.dp else 8.dp
        val bottom = if (index == count - 1) 28.dp else 8.dp
        return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
    }
}

object HripsSpace {
    val XS = 4.dp
    val S = 8.dp
    val M = 12.dp
    val L = 16.dp
    val XL = 24.dp
    val XXL = 32.dp
}
