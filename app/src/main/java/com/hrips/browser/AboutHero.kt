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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.coerceAtMost
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
)

private val PhoneSpec = DeviceSpec(R.drawable.frame_phone, 420f / 919f, 0.0459f, 0.0218f, 0.9505f, 0.9734f, cols = 4, rows = 3, tablet = false)
private val TabletSpec = DeviceSpec(R.drawable.frame_tablet, 1100f / 719f, 0.0351f, 0.0581f, 0.9649f, 0.9451f, cols = 6, rows = 2, tablet = true)

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
                Text("hrips", style = MaterialTheme.typography.titleLarge, color = cs.onSurface, textAlign = TextAlign.Center)
                Text(
                    if (version.isBlank()) "Версия неизвестна" else "Версия $version",
                    Modifier.padding(top = 2.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                    textAlign = TextAlign.Center,
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
                ),
        ) { MiniStart(app, spec, screenW) }
        Image(painterResource(spec.frame), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
    }
}

@Composable
private fun MiniStart(app: HripsApp, spec: DeviceSpec, sw: Dp) {
    val cs = MaterialTheme.colorScheme
    val wallpaper = app.wallpaper.image
    val textColor = if (wallpaper != null) Color.White else cs.onSurface
    val pad = if (spec.tablet) 0.1f else 0.07f
    val gap = sw * (if (spec.tablet) 0.03f else 0.04f)
    val tile = (sw * (1f - 2f * pad) - gap * (spec.cols - 1)) / spec.cols

    Box(Modifier.fillMaxSize().background(cs.surface)) {
        if (wallpaper != null) {
            Image(wallpaper, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.22f), Color.Black.copy(alpha = 0.52f))),
                ),
            )
        }
        Column(Modifier.fillMaxSize().padding(horizontal = sw * pad), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(sw * (if (spec.tablet) 0.07f else 0.17f)))
            Image(
                painter = painterResource(R.drawable.logo_hrips),
                contentDescription = null,
                modifier = Modifier.height(sw * (if (spec.tablet) 0.08f else 0.15f)),
                contentScale = ContentScale.Fit,
                colorFilter = ColorFilter.tint(textColor),
            )
            if (spec.tablet) {
                Spacer(Modifier.height(sw * 0.03f))
                MiniSearch(sw, Modifier.fillMaxWidth(0.55f))
            }
            Spacer(Modifier.height(sw * (if (spec.tablet) 0.06f else 0.09f)))

            val entries: List<Entry?> = app.store.speedDial.toList().take(spec.cols * spec.rows - 1) + null
            entries.chunked(spec.cols).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally)) {
                    row.forEach { e -> MiniTile(e, tile, textColor) }
                }
                Spacer(Modifier.height(gap))
            }
            if (!spec.tablet) {
                Spacer(Modifier.weight(1f))
                MiniSearch(sw, Modifier.fillMaxWidth())
                Spacer(Modifier.height(sw * 0.07f))
            }
        }
    }
}

@Composable
private fun MiniSearch(sw: Dp, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier
            .height(sw * 0.13f)
            .clip(CircleShape)
            .background(cs.surfaceContainerHigh)
            .padding(horizontal = sw * 0.04f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(sw * 0.025f),
    ) {
        Icon(HripsIcons.Search, null, Modifier.size(sw * 0.06f), tint = cs.onSurfaceVariant)
        Text(
            "Искать или задать вопрос",
            fontSize = (sw.value * 0.045f).sp,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            softWrap = false,
            color = cs.onSurfaceVariant,
        )
    }
}

@Composable
private fun MiniTile(e: Entry?, tile: Dp, textColor: Color) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.width(tile), horizontalAlignment = Alignment.CenterHorizontally) {
        if (e == null) {
            ShapeBadge(
                HripsIcons.Add, tile, badge = Badge.COOKIE9,
                container = cs.surfaceContainerHighest, content = cs.onSurfaceVariant,
            )
        } else {
            val shape = RoundedCornerShape(tile * 0.38f)
            Surface(shape = shape, color = cs.surfaceContainerHigh, modifier = Modifier.size(tile)) {
                Box(contentAlignment = Alignment.Center) {
                    Favicon(e.url, tile, fill = true) {
                        SiteTile(e.url, e.title.ifBlank { siteKey(e.url) ?: e.url }, tile, shape = shape)
                    }
                }
            }
        }
        Spacer(Modifier.height(tile * 0.1f))
        Text(
            if (e == null) "Добавить" else e.title,
            fontSize = (tile.value * 0.24f).sp,
            lineHeight = (tile.value * 0.28f).sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            color = textColor,
        )
    }
}
