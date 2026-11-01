@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.hrips.browser

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun SearchPage(store: Store, onBack: () -> Unit) {
    PageScaffold("Поиск", onBack) {
        SectionTitle("Поисковая система")
        TwoColumnGrid(SearchEngines.all) { e, m ->
            SelectCard(SearchEngines.current == e, { store.setSearchEngine(e) }, m) {
                EngineLogo(e, 36.dp)
                Text(e.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        SectionTitle("Ввод")
        Group(
            { s ->
                PanelSwitch(
                    "Подсказки при вводе", "Вводимый текст отправляется выбранной поисковой системе",
                    store.suggestionsOn, { store.updateSuggestions(it) },
                    shape = s,
                )
            },
            { s ->
                PanelItem(
                    "Очистить историю поиска",
                    subtitle = if (store.searches.isEmpty()) "Пока пусто" else "Запросов: ${store.searches.size}",
                    enabled = store.searches.isNotEmpty(),
                    onClick = {
                        store.clearSearches()
                        Notices.show("История поиска очищена")
                    },
                    shape = s,
                )
            },
        )
    }
}

@Composable
internal fun HomePage(app: HripsApp, onBack: () -> Unit) {
    val wallpaper = app.wallpaper
    PageScaffold("Внешний вид", onBack) {
        ThemeColorSection(app.store)
        if (isWideWindow()) {
            val st = app.store
            val motion = MaterialTheme.motionScheme
            SectionTitle("Вкладки: вид")
            PreviewChoiceRow(
                options = listOf(TABS_STYLE_STRIP, TABS_STYLE_INLINE),
                isSelected = { st.tabsStyle == it },
                label = { if (it == TABS_STYLE_STRIP) "Отдельная полоса" else "В строке адреса" },
                supporting = { if (it == TABS_STYLE_STRIP) "Вкладки в своей строке или сбоку" else "Компактно: нажатая вкладка становится адресом" },
                onSelect = { st.updateTabsStyle(it) },
            ) { v -> TabsMock(style = v, pos = st.tabsPos) }

            AnimatedVisibility(
                visible = st.tabsStyle == TABS_STYLE_STRIP,
                enter = expandVertically(motion.defaultSpatialSpec()) + fadeIn(motion.defaultEffectsSpec()),
                exit = shrinkVertically(motion.defaultSpatialSpec()) + fadeOut(motion.fastEffectsSpec()),
            ) {
                Column {
                    SectionTitle("Вкладки: расположение")
                    PreviewChoiceRow(
                        options = listOf(TABS_BELOW, TABS_ABOVE, TABS_LEFT),
                        isSelected = { st.tabsPos == it },
                        label = { when (it) { TABS_BELOW -> "Под адресом"; TABS_ABOVE -> "Над адресом"; else -> "Слева" } },
                        onSelect = { st.updateTabsPos(it) },
                    ) { v -> TabsMock(style = TABS_STYLE_STRIP, pos = v) }
                }
            }

            SectionTitle("Вкладки: когда показывать")
            TileSwitcher(
                options = listOf(TABS_ALWAYS to "Всегда", TABS_MORE_THAN_TWO to "От 2 вкладок", TABS_NEVER to "Никогда"),
                selected = st.tabsShow,
                onSelect = { st.updateTabsShow(it) },
                description = {
                    when (it) {
                        TABS_ALWAYS -> "Панель вкладок видна всегда, даже когда открыта одна вкладка"
                        TABS_MORE_THAN_TWO -> "Панель появляется, когда открыто две вкладки и больше"
                        else -> "Панель скрыта. Её можно открыть кнопкой слева в шапке"
                    }
                },
            )
        }
        SectionTitle("Иконка приложения")
        AppIconPicker()

        SectionTitle("Обои главной страницы")
        val cs = MaterialTheme.colorScheme
        val img = wallpaper.image
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(HripsShapes.XL)
                .background(Brush.verticalGradient(listOf(cs.primaryContainer, cs.tertiaryContainer))),
            contentAlignment = Alignment.Center,
        ) {
            if (img != null) {
                Image(bitmap = img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                ShapeBadge(HripsIcons.Image, 72.dp, badge = Badge.FLOWER, container = cs.primary, content = cs.onPrimary)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                shapes = ButtonDefaults.shapes(),
                onClick = { wallpaper.pick?.invoke() },
                modifier = Modifier.weight(1f).height(52.dp),
            ) { Text(if (img == null) "Выбрать фото" else "Заменить", style = MaterialTheme.typography.titleMedium) }
            if (img != null) {
                HripsTextButton(onClick = { wallpaper.clear() }, modifier = Modifier.height(52.dp)) { Text("Убрать фон") }
            }
        }
    }
}

@Composable
private fun AppIconPicker() {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var selected by remember { mutableStateOf(AppIcons.current(context)) }
    val columns = 4
    val rows = AppIcon.entries.chunked(columns)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { icon ->
                    val isSelected = icon == selected
                    val bg by animateColorAsState(
                        if (isSelected) cs.surfaceContainerHighest else Color.Transparent,
                        MaterialTheme.motionScheme.fastEffectsSpec<Color>(), label = "iconBg",
                    )
                    val ring by animateColorAsState(
                        if (isSelected) cs.primary else Color.Transparent,
                        MaterialTheme.motionScheme.fastEffectsSpec<Color>(), label = "iconRing",
                    )
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(HripsShapes.L)
                            .background(bg)
                            .border(BorderStroke(2.dp, ring), HripsShapes.L)
                            .clickable {
                                if (!isSelected) {
                                    if (AppIcons.set(context, icon)) {
                                        selected = icon
                                        Notices.show("Иконка изменена. На рабочем столе она может обновиться через пару секунд")
                                    } else {
                                        Notices.show("Не удалось сменить иконку")
                                    }
                                }
                            }
                            .padding(vertical = 12.dp, horizontal = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(Modifier.size(64.dp)) {
                            Image(
                                painter = androidx.compose.ui.res.painterResource(icon.preview),
                                contentDescription = icon.title,
                                modifier = Modifier.fillMaxSize().clip(CircleShape),
                            )
                            if (isSelected) {
                                Box(
                                    Modifier.align(Alignment.BottomEnd).size(24.dp).clip(CircleShape).background(cs.primary),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(HripsIcons.Check, null, Modifier.size(16.dp), tint = cs.onPrimary)
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            icon.title,
                            style = MaterialTheme.typography.labelLarge,
                            color = cs.onSurface,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            maxLines = 2,
                        )
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
