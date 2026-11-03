package com.hrips.browser

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.coerceAtMost
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp

private class DeviceSpec(
    val frame: Int,
    val aspect: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val cols: Int,
    val rows: Int,
    val tablet: Boolean,
    val corner: Float,
)

private val PhoneSpec = DeviceSpec(R.drawable.frame_phone, 420f / 919f, 0.0459f, 0.0218f, 0.9505f, 0.9734f, cols = 4, rows = 3, tablet = false, corner = 0.092f)
private val TabletSpec = DeviceSpec(R.drawable.frame_tablet, 1100f / 719f, 0.0351f, 0.0581f, 0.9649f, 0.9451f, cols = 6, rows = 2, tablet = true, corner = 0.015f)

@Composable
internal fun AboutHero(app: HripsApp, version: String, gecko: String) {
    val cs = MaterialTheme.colorScheme
    val wide = isWideWindow()
    val spec = if (wide) TabletSpec else PhoneSpec
    val engine = if (gecko.isBlank()) "GeckoView" else "GeckoView $gecko"
    val adBlock = "uBlock Origin" + if (app.adBlock.extension == null) " (не загружена)" else ""
    val android = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        val deviceW = if (wide) (maxWidth * 0.56f).coerceAtMost(460.dp) else (maxWidth * 0.40f).coerceIn(112.dp, 168.dp)
        val deviceH = deviceW / spec.aspect
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(if (wide) 32.dp else 16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.width(deviceW), horizontalAlignment = Alignment.CenterHorizontally) {
                DeviceMock(app, spec, deviceW, deviceH)
                Spacer(Modifier.height(16.dp))
                Text(
                    if (version.isBlank()) "hrips" else "hrips $version",
                    style = MaterialTheme.typography.titleLarge,
                    color = cs.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            Column(
                Modifier.weight(1f).height(deviceH),
                verticalArrangement = Arrangement.spacedBy(if (wide) 28.dp else 20.dp, Alignment.CenterVertically),
            ) {
                AboutFact(HripsIcons.Layers, "Движок", engine)
                AboutFact(HripsIcons.Block, "Блокировка рекламы", adBlock)
                AboutFact(HripsIcons.Android, "Android", android)
            }
        }
    }
}

@Composable
private fun AboutFact(icon: ImageVector, label: String, value: String) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(cs.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(22.dp), tint = cs.onSurfaceVariant)
        }
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(value, style = MaterialTheme.typography.bodyLarge, color = cs.onSurface, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun DeviceMock(app: HripsApp, spec: DeviceSpec, width: Dp, height: Dp) {
    val bleed = 1.5.dp
    val screenW = width * (spec.right - spec.left)
    Box(Modifier.size(width, height).clearAndSetSemantics {}) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(
                    start = (width * spec.left - bleed).coerceAtLeast(0.dp),
                    top = (height * spec.top - bleed).coerceAtLeast(0.dp),
                    end = (width * (1f - spec.right) - bleed).coerceAtLeast(0.dp),
                    bottom = (height * (1f - spec.bottom) - bleed).coerceAtLeast(0.dp),
                )
                .clip(RoundedCornerShape(width * spec.corner + bleed)),
        ) { MiniStart(app, spec, screenW) }
        Image(painterResource(spec.frame), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
    }
}

@Composable
private fun MiniStart(app: HripsApp, spec: DeviceSpec, sw: Dp) {
    val vw = LocalConfiguration.current.screenWidthDp.dp
    val holeAspect = (spec.right - spec.left) * spec.aspect / (spec.bottom - spec.top)
    val vh = vw / holeAspect
    val scale = sw.value / vw.value
    val real = LocalViewConfiguration.current
    val inert = remember(real) {
        object : ViewConfiguration by real {
            override val longPressTimeoutMillis: Long = Long.MAX_VALUE / 4
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .layout { measurable, constraints ->
                val placeable = measurable.measure(Constraints.fixed(vw.roundToPx(), vh.roundToPx()))
                layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(0, 0) }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0f)
            },
    ) {
        CompositionLocalProvider(LocalViewConfiguration provides inert) {
            if (spec.tablet) MiniTabletScreen(app, vw) else MiniPhoneScreen(app, vw)
        }
    }
}

@Composable
private fun MiniStartContent(app: HripsApp, modifier: Modifier = Modifier) {
    StartPage(
        store = app.store,
        wallpaper = app.wallpaper.image,
        onOpen = {},
        onSearch = {},
        onScanQr = {},
        onSearchImage = {},
        modifier = modifier,
        showSearch = false,
    )
}

@Composable
private fun MiniPhoneScreen(app: HripsApp, width: Dp) {
    val cs = MaterialTheme.colorScheme
    val button = HripsLayout.phoneBarButton(width.value.toInt())
    Box(Modifier.fillMaxSize().background(cs.surface)) {
        MiniStartContent(app)
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = HripsLayout.BarSideMargin, end = HripsLayout.BarSideMargin, bottom = 36.dp),
            horizontalArrangement = Arrangement.spacedBy(HripsLayout.PhoneBarGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FlatCapsule(Modifier.weight(1f).height(button), color = cs.surfaceContainerHigh) {
                Row(Modifier.fillMaxSize().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    MiniEngineChip()
                    Text(
                        "Поиск",
                        Modifier.weight(1f).padding(start = 12.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
                    )
                    Icon(HripsIcons.Image, null, Modifier.size(40.dp).padding(8.dp), tint = cs.onSurfaceVariant)
                    Icon(HripsIcons.Qr, null, Modifier.size(40.dp).padding(8.dp), tint = cs.onSurfaceVariant)
                }
            }
            FlatCapsule(Modifier.size(button), color = cs.surfaceContainerHigh) {
                Box(contentAlignment = Alignment.Center) { Icon(HripsIcons.MoreVert, null, tint = cs.onSurface) }
            }
        }
    }
}

@Composable
private fun MiniTabletScreen(app: HripsApp, width: Dp) {
    val cs = MaterialTheme.colorScheme
    val status = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bs = 36.dp
    val toolbar = status + 40.dp + 12.dp
    val wallpaper = app.wallpaper.image
    Box(Modifier.fillMaxSize().background(cs.surface)) {
        if (wallpaper != null) {
            Image(wallpaper, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.22f), Color.Black.copy(alpha = 0.52f))),
                ),
            )
        }
        MiniStartContent(app, Modifier.padding(top = toolbar))
        Row(
            Modifier.fillMaxWidth().padding(top = status).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolbarGroup { BarButton(onClick = {}, modifier = Modifier.size(bs)) { Icon(HripsIcons.TbSidebar, null) } }
            Spacer(Modifier.width(8.dp))
            ToolbarGroup { BarButton(onClick = {}, modifier = Modifier.size(bs)) { Icon(HripsIcons.TbHome, null) } }
            Spacer(Modifier.width(8.dp))
            ToolbarGroup {
                BarButton(onClick = {}, modifier = Modifier.size(bs), enabled = false) { Icon(HripsIcons.ChevronBack, null) }
                BarButton(onClick = {}, modifier = Modifier.size(bs), enabled = false) { Icon(HripsIcons.ChevronForward, null) }
            }
            Box(Modifier.weight(1f).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                val barWidth = (width.value * 0.4375f).coerceIn(360f, 640f).dp
                FlatCapsule(Modifier.width(barWidth).height(40.dp)) {
                    Row(Modifier.fillMaxSize().padding(start = 6.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        MiniEngineChip()
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            Icon(HripsIcons.TbSearch, null, tint = cs.onSurfaceVariant)
                            Spacer(Modifier.width(2.dp))
                            Text(
                                "Искать или задать вопрос",
                                style = MaterialTheme.typography.bodyLarge,
                                color = cs.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Icon(HripsIcons.Image.variant(2f), null, Modifier.size(bs).padding(6.dp), tint = cs.onSurfaceVariant)
                        Icon(HripsIcons.TbQr, null, Modifier.size(bs).padding(6.dp), tint = cs.onSurfaceVariant)
                    }
                }
            }
            ToolbarGroup {
                BarButton(onClick = {}, modifier = Modifier.size(bs)) { Icon(HripsIcons.Add.variant(2f), null) }
                TabCounterButton(1, Modifier.size(bs), tablet = true) {}
            }
            Spacer(Modifier.width(8.dp))
            ToolbarGroup { BarButton(onClick = {}, modifier = Modifier.size(bs)) { Icon(HripsIcons.Puzzle.variant(2f), null) } }
            Spacer(Modifier.width(8.dp))
            ToolbarGroup { BarButton(onClick = {}, modifier = Modifier.size(bs)) { Icon(HripsIcons.MoreVert, null) } }
        }
    }
}

@Composable
private fun MiniEngineChip() {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.height(32.dp).clip(CircleShape).background(cs.onSurface.copy(alpha = 0.08f)).padding(start = 3.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EngineLogo(SearchEngines.current, 26.dp)
        Icon(HripsIcons.ChevronDown, null, Modifier.size(18.dp), tint = cs.onSurfaceVariant)
    }
}
