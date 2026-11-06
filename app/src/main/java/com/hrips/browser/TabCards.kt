@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.hrips.browser

import androidx.compose.material3.toShape
import androidx.compose.material3.MaterialShapes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape

@Composable
internal fun TabCard(
    tab: Tab,
    selected: Boolean,
    env: SwitcherEnv,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    big: Boolean = false,
    clean: Boolean = big,
    corner: Dp = 28.dp,
    onMoved: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val holder = remember { CoordsHolder() }
    if (selected) SideEffect { env.currentHolder = holder }
    if (clean) {
        val screen = RoundedCornerShape(corner)
        Box(
            modifier
                .onPlaced { holder.c = it }
                .clip(screen)
                .background(cs.surfaceContainerLowest)
                .border(if (selected) 3.dp else 0.5.dp, if (selected) cs.primary else cs.onSurface.copy(alpha = 0.14f), screen)
                .semantics { this.selected = selected }
                .tabHold(env, tab, holder, onMoved, onClose)
                .clickable(onClick = onOpen),
        ) {
            TabPreview(tab, mini = false, small = !big, modifier = Modifier.fillMaxSize())
            if (env.selecting) {
                SelectBadge(tab.id in env.picked, Modifier.align(Alignment.TopEnd).padding(4.dp))
            } else {
                CloseTabButton(tab, Modifier.align(Alignment.TopEnd).padding(4.dp), onClose)
            }
        }
        return
    }
    Box(
        modifier
            .onPlaced { holder.c = it }
            .clip(HripsShapes.L)
            .background(if (selected) cs.primary else cs.surfaceContainerHigh)
            .semantics { this.selected = selected }
            .tabHold(env, tab, holder, onMoved, onClose)
            .clickable(onClick = onOpen),
    ) {
        Box(Modifier.fillMaxSize().padding(4.dp).clip(HripsShapes.ML).background(cs.surfaceContainerLowest)) {
            TabPreview(tab, mini = false, small = !big, modifier = Modifier.fillMaxSize())
            if (env.selecting) {
                SelectBadge(tab.id in env.picked, Modifier.align(Alignment.TopEnd))
            } else {
                CloseTabButton(tab, Modifier.align(Alignment.TopEnd), onClose)
            }
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(6.dp)
                    .clip(HripsShapes.M)
                    .background(cs.surfaceContainerHigh)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TabIcon(tab, 24.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    tab.label(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge, color = cs.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
internal fun TabRow(
    tab: Tab,
    selected: Boolean,
    env: SwitcherEnv,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    onMoved: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val holder = remember { CoordsHolder() }
    val rowHolder = remember { CoordsHolder() }
    if (selected) SideEffect { env.currentHolder = holder }
    val pinned = env.browser.store.isPinned(tab.id)
    val titleColor = if (selected) cs.onPrimaryContainer else cs.onSurface
    val subColor = if (selected) cs.onPrimaryContainer.copy(alpha = 0.72f) else cs.onSurfaceVariant
    Row(
        Modifier
            .fillMaxWidth()
            .height(76.dp)
            .clip(HripsShapes.ML)
            .background(if (selected) cs.primaryContainer else cs.surfaceContainerHigh)
            .semantics { this.selected = selected }
            .onPlaced { rowHolder.c = it }
            .tabHold(env, tab, rowHolder, onMoved, onClose)
            .clickable(onClick = onOpen)
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TabPreview(
            tab, mini = true, small = true,
            modifier = Modifier.size(64.dp).onPlaced { holder.c = it }.clip(HripsShapes.S),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tab.label(), Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall, color = titleColor)
                if (pinned) {
                    Spacer(Modifier.width(6.dp))
                    Icon(HripsIcons.TabPin, "Закреплена", Modifier.size(14.dp), tint = subColor)
                }
            }
            Text(tabSubtitle(tab), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = subColor)
        }
        if (env.selecting) {
            SelectBadge(tab.id in env.picked)
        } else {
            IconButton(onClick = onClose) {
                Icon(HripsIcons.Close, "Закрыть вкладку ${tab.label()}", tint = titleColor)
            }
        }
    }
}

@Composable
internal fun TabPreview(tab: Tab, mini: Boolean, small: Boolean, modifier: Modifier = Modifier) {
    val thumbState = rememberTabThumb(tab, small)
    val thumb = thumbState.image
    val context = LocalContext.current.applicationContext
    val pageImageState = produceState<ImageBitmap?>(null, tab.url, thumbState.resolved, thumb == null, PageImages.enabled) {
        value = if (thumbState.resolved && thumb == null && !tab.isPrivate && !tab.home && tab.url.isNotBlank()) {
            PageImages.load(context, tab.url)
        } else null
    }
    val pageImage = pageImageState.value
    Box(modifier) {
        if (thumb != null && !tab.home) {
            Image(
                bitmap = thumb,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (pageImage != null && !tab.home) {
            Image(
                bitmap = pageImage,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.Center,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (tab.home && !tab.isPrivate) {
            HomeMini(mini)
        } else {
            TabPlaceholder(tab, mini)
        }
    }
}

@Composable
private fun HomeMini(mini: Boolean) {
    val cs = MaterialTheme.colorScheme
    val wallpaper = (LocalContext.current.applicationContext as HripsApp).wallpaper.image
    // В мини-превью фон контейнерный: иначе плитка сливается с фоном переключателя.
    Box(Modifier.fillMaxSize().background(if (mini && wallpaper == null) cs.surfaceContainerHigh else cs.background)) {
        if (wallpaper != null) {
            Image(bitmap = wallpaper, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Column(
            Modifier.fillMaxSize().padding(horizontal = if (mini) 6.dp else 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(if (mini) 1f else 0.18f))
            Image(
                painter = androidx.compose.ui.res.painterResource(R.drawable.logo_hrips),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(cs.onBackground),
                modifier = Modifier.fillMaxWidth(if (mini) 0.8f else 0.62f).height(if (mini) 14.dp else 34.dp),
            )
            if (!mini) {
                Spacer(Modifier.height(14.dp))
                Box(Modifier.fillMaxWidth().height(22.dp).clip(CircleShape).background(cs.surfaceContainerHigh))
                Spacer(Modifier.height(18.dp))
                repeat(2) {
                    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        repeat(4) { Box(Modifier.size(22.dp).clip(CircleShape).background(cs.surfaceContainerHigh)) }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
internal fun TabIcon(tab: Tab, size: Dp) {
    val cs = MaterialTheme.colorScheme
    when {
        tab.isPrivate -> Icon(HripsIcons.Mask, null, Modifier.size(size * 0.8f), tint = cs.primary)
        tab.home -> Icon(HripsIcons.Home, null, Modifier.size(size * 0.8f), tint = cs.onSurfaceVariant)
        else -> TabSiteIcon(tab, size, shape = CircleShape)
    }
}

@Composable
private fun TabPlaceholder(tab: Tab, mini: Boolean) {
    val cs = MaterialTheme.colorScheme
    val dark = cs.background.luminance() < 0.5f
    val (bg, fg) = when {
        tab.isPrivate -> cs.tertiaryContainer to cs.onTertiaryContainer
        tab.home -> cs.primaryContainer to cs.onPrimaryContainer
        else -> siteColors(tab.url, dark)
    }
    val icon = if (mini) 28.dp else 48.dp
    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(bg, bg.copy(alpha = 0.78f).compositeOver(cs.surfaceContainerLowest)))),
        contentAlignment = Alignment.Center,
    ) {
        if (mini) {
            TabGlyph(tab, icon, fg)
        } else {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(96.dp).background(fg.copy(alpha = 0.16f), MaterialShapes.Cookie9Sided.toShape()), contentAlignment = Alignment.Center) {
                    TabGlyph(tab, icon, fg)
                }
                if (!tab.isPrivate) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        tab.label(), color = fg, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(bottom = 36.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TabGlyph(tab: Tab, size: Dp, tint: Color) {
    when {
        tab.isPrivate -> Icon(HripsIcons.Mask, null, Modifier.size(size), tint = tint)
        tab.home -> Icon(HripsIcons.Home, null, Modifier.size(size), tint = tint)
        else -> TabSiteIcon(tab, size)
    }
}

private data class TabThumbResult(val image: ImageBitmap?, val resolved: Boolean)

@Composable
private fun rememberTabThumb(tab: Tab, small: Boolean): TabThumbResult {
    val context = LocalContext.current.applicationContext
    val activeImage = tab.thumbnail
    val cachedImage = TabThumbs.peek(tab.id, small)
    val initial = TabThumbResult(
        image = activeImage ?: cachedImage,
        resolved = activeImage != null || cachedImage != null || tab.isPrivate || tab.home,
    )
    val loaded by produceState(initial, tab.id, activeImage, small, tab.isPrivate, tab.home) {
        value = when {
            activeImage != null -> TabThumbResult(activeImage, resolved = true)
            tab.isPrivate || tab.home -> TabThumbResult(null, resolved = true)
            else -> TabThumbResult(TabThumbs.load(context, tab.id, small), resolved = true)
        }
    }
    val result = if (activeImage != null) TabThumbResult(activeImage, resolved = true) else loaded
    LaunchedEffect(result.image) {
        val bitmap = result.image ?: return@LaunchedEffect
        withContext(Dispatchers.Default) {
            runCatching { bitmap.asAndroidBitmap().prepareToDraw() }
        }
    }
    return result
}
