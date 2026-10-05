package com.hrips.browser

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
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
fun StartPage(store: Store, wallpaper: ImageBitmap?, onOpen: (String) -> Unit, onSearch: () -> Unit, modifier: Modifier = Modifier) {
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    val markSize = if (wide) 96.sp else 72.sp
    // На обоях текст всегда белый, на обычном фоне цвет берётся из темы
    val textColor = if (wallpaper != null) Color.White else MaterialTheme.colorScheme.onSurface
    var showAdd by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<Entry?>(null) }

    Box(modifier.fillMaxSize()) {
    if (wallpaper != null) {
        Image(wallpaper, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 96.dp),
            modifier = Modifier.widthIn(max = 880.dp).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(
                    Modifier.fillMaxWidth().padding(top = 32.dp, bottom = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(R.drawable.logo_shrimp),
                            contentDescription = null,
                            modifier = Modifier.size(if (wide) 88.dp else 64.dp),
                            colorFilter = ColorFilter.tint(textColor),
                        )
                        Spacer(Modifier.width(14.dp))
                        Text(
                            "hrips",
                            style = WordmarkStyle.copy(fontSize = markSize, lineHeight = markSize * 0.95f),
                            color = textColor,
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                    Surface(
                        onClick = onSearch,
                        shape = MaterialTheme.shapes.extraLarge,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth().widthIn(max = 640.dp),
                    ) {
                        Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(HripsIcons.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(12.dp))
                            Text("Искать или задать вопрос", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }

            items(store.speedDial.toList()) { e ->
                Column(
                    Modifier
                        .combinedClickable(onClick = { onOpen(e.url) }, onLongClick = { toDelete = e })
                        .padding(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val (bg, fg) = tileColors(e.title)
                    Surface(shape = RoundedCornerShape(28.dp), color = bg, modifier = Modifier.size(72.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Favicon(e.url, 72.dp, fill = true) {
                                Text(
                                    e.title.firstOrNull()?.uppercase() ?: "?",
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = fg,
                                )
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
            }

            item {
                Column(
                    Modifier.clickable { showAdd = true }.padding(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Surface(
                        shape = RoundedCornerShape(28.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.size(72.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) { Icon(HripsIcons.Add, "Добавить") }
                    }
                }
            }
        }
    }
    }

    if (showAdd) {
        var name by remember { mutableStateOf("") }
        var address by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Новая плитка") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Название") }, singleLine = true)
                    OutlinedTextField(address, { address = it }, label = { Text("Адрес") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = address.isNotBlank(),
                    onClick = {
                        val url = toUrl(address)
                        val title = name.ifBlank { Uri.parse(url).host?.removePrefix("www.") ?: url }
                        store.addDial(url, title)
                        showAdd = false
                    },
                ) { Text("Добавить") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Отмена") } },
        )
    }

    toDelete?.let { e ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Удалить плитку?") },
            text = { Text(e.title) },
            confirmButton = { TextButton(onClick = { store.removeDial(e); toDelete = null }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Отмена") } },
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
