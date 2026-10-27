@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.hrips.browser

import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.IntOffset
import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf

enum class SettingsPage(val title: String, val subtitle: String) {
    SEARCH("Поиск", "Поисковая система, подсказки"),
    HOME("Внешний вид", "Значок приложения, обои"),
    PRIVACY("Конфиденциальность", "Трекеры, очистка данных, приватный режим"),
    PASSWORDS("Пароли и автозаполнение", "Сохранённые пароли, автозаполнение, импорт"),
    PERMISSIONS("Разрешения", "Камера, микрофон, местоположение, уведомления"),
    DESKTOP("Версия для ПК", "Сайты, где она включена"),
    MEDIA("Медиа", "Управление из шторки, картинка в картинке"),
    DOWNLOADS("Загрузки", "Подтверждение, список файлов"),
    PERFORMANCE("Производительность", "Память, фоновые вкладки"),
    ABOUT("О программе", "Версия, движок"),
}

private fun SettingsPage.icon(): ImageVector = when (this) {
    SettingsPage.SEARCH -> HripsIcons.Search
    SettingsPage.HOME -> HripsIcons.PaletteLine
    SettingsPage.PRIVACY -> HripsIcons.EyeOff
    SettingsPage.PASSWORDS -> HripsIcons.PasswordKey
    SettingsPage.PERMISSIONS -> HripsIcons.ShieldCheck
    SettingsPage.DESKTOP -> HripsIcons.Desktop
    SettingsPage.MEDIA -> HripsIcons.PlayCircle
    SettingsPage.DOWNLOADS -> HripsIcons.Download
    SettingsPage.PERFORMANCE -> HripsIcons.Gauge
    SettingsPage.ABOUT -> HripsIcons.Info
}

@Composable
private fun SearchResults(found: List<SettingEntry>, onPick: (SettingsPage) -> Unit) {
    GroupOf(found) { e, sh ->
        SettingsRow(e.page.icon(), e.title, e.page.title, sh, onClick = { onPick(e.page) })
    }
}

/** В двухпанельном режиме (планшет) страница рисуется справа без большой сворачивающейся шапки. */
private val LocalSettingsPane = compositionLocalOf { false }

private class SettingEntry(val title: String, val keywords: String, val page: SettingsPage)

private val index = listOf(
    SettingEntry("Поисковая система", "google yandex duckduckgo bing поиск движок", SettingsPage.SEARCH),
    SettingEntry("История поиска", "очистить запросы", SettingsPage.SEARCH),
    SettingEntry("Обои главной страницы", "фон картинка фото", SettingsPage.HOME),
    SettingEntry("Значок приложения", "иконка лого креветка синяя оранжевая серая icon", SettingsPage.HOME),
    SettingEntry("Внешний вид", "оформление тема значок обои", SettingsPage.HOME),
    SettingEntry("Вкладки на планшете", "панель вкладок сверху снизу слева скрыть показывать", SettingsPage.HOME),
    SettingEntry("Скриншоты в приватных вкладках", "приватный режим снимок экрана", SettingsPage.PRIVACY),
    SettingEntry("Очистить данные", "история cookies куки кэш удалить", SettingsPage.PRIVACY),
    SettingEntry("Global Privacy Control", "gpc не продавать данные сигнал приватность", SettingsPage.PRIVACY),
    SettingEntry("Картинки страниц в карточках вкладок", "превью og:image карточки вкладок запросы", SettingsPage.PRIVACY),
    SettingEntry("Скрывать запросы cookie", "баннеры согласия куки отклонять", SettingsPage.PRIVACY),
    SettingEntry("Ссылки в приложениях", "открывать в приложении app link youtube telegram", SettingsPage.PRIVACY),
    SettingEntry("Безопасное соединение (HTTPS)", "https only http шифрование предупреждение", SettingsPage.PRIVACY),
    SettingEntry("Защищённый DNS (DoH)", "dns doh днс cloudflare quad9 adguard провайдер", SettingsPage.PRIVACY),
    SettingEntry("Пароли", "сохранённые логины вход менеджер паролей список", SettingsPage.PASSWORDS),
    SettingEntry("Автозаполнение", "пароли менеджер bitwarden google 1password системный", SettingsPage.PASSWORDS),
    SettingEntry("Импорт и экспорт паролей", "csv chrome firefox bitwarden файл выгрузить", SettingsPage.PASSWORDS),
    SettingEntry("Не сохранять пароли для сайтов", "никогда исключения", SettingsPage.PASSWORDS),
    SettingEntry("Блокировка экрана для паролей", "pin отпечаток биометрия подтверждение защита", SettingsPage.PASSWORDS),
    SettingEntry("Разрешения сайтов", "камера микрофон геолокация местоположение уведомления сброс", SettingsPage.PERMISSIONS),
    SettingEntry("Разрешения приложения", "android системные права", SettingsPage.PERMISSIONS),
    SettingEntry("Версия для ПК", "десктопный режим компьютер сайт", SettingsPage.DESKTOP),
    SettingEntry("Оживление миниатюр", "предпросмотр видео наведение удержание превью", SettingsPage.MEDIA),
    SettingEntry("Картинка в картинке", "pip видео окно", SettingsPage.MEDIA),
    SettingEntry("Управление в уведомлении", "фоновое воспроизведение музыка шторка", SettingsPage.MEDIA),
    SettingEntry("Спрашивать перед загрузкой", "подтверждение скачивание", SettingsPage.DOWNLOADS),
    SettingEntry("Список загрузок", "файлы скачанные", SettingsPage.DOWNLOADS),
    SettingEntry("Экономия памяти", "память фоновые вкладки выгрузка suspend performance", SettingsPage.PERFORMANCE),
    SettingEntry("Версия", "о программе движок gecko", SettingsPage.ABOUT),
)

@Composable
fun SettingsScreen(
    browser: Browser,
    app: HripsApp,
    onBack: () -> Unit,
    onSitePermissions: () -> Unit,
    onPasswords: () -> Unit,
    onOpenDownloads: () -> Unit,
) {
    if (isWideWindow()) SettingsTwoPane(browser, app, onBack, onSitePermissions, onPasswords, onOpenDownloads)
    else SettingsSinglePane(browser, app, onBack, onSitePermissions, onPasswords, onOpenDownloads)
}

@Composable
private fun SettingsPageContent(
    p: SettingsPage,
    browser: Browser,
    app: HripsApp,
    back: () -> Unit,
    onSitePermissions: () -> Unit,
    onPasswords: () -> Unit,
    onOpenDownloads: () -> Unit,
) {
    run {
        when (p) {
            SettingsPage.SEARCH -> SearchPage(browser.store, back)
            SettingsPage.HOME -> HomePage(app, back)
            SettingsPage.PRIVACY -> PrivacyPage(browser, back)
            SettingsPage.PASSWORDS -> PasswordsSettingsPage(app, onPasswords, back)
            SettingsPage.PERMISSIONS -> PermissionsPage(onSitePermissions, back)
            SettingsPage.DESKTOP -> DesktopPage(browser, back)
            SettingsPage.MEDIA -> MediaPage(browser.store, app.hoverPreview, back)
            SettingsPage.DOWNLOADS -> DownloadsPage(browser, back, onOpenDownloads)
            SettingsPage.PERFORMANCE -> PerformancePage(browser, back)
            SettingsPage.ABOUT -> AboutPage(app, back)
        }
    }
}

/**
 * Планшет: слева список разделов с поиском, справа выбранный раздел, как в системных настройках Android.
 * Слева всегда выбран какой-то раздел (по умолчанию первый), «назад» в шапке справа закрывает настройки.
 */
@Composable
private fun SettingsTwoPane(
    browser: Browser,
    app: HripsApp,
    onBack: () -> Unit,
    onSitePermissions: () -> Unit,
    onPasswords: () -> Unit,
    onOpenDownloads: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var page by remember { mutableStateOf(SettingsPage.SEARCH) }
    var query by remember { mutableStateOf("") }
    val paneIn = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val paneOut = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    BackHandler(onBack = onBack)
    Row(
        Modifier
            .fillMaxSize()
            .background(cs.surface)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(0.36f).widthIn(min = 320.dp, max = 440.dp)) {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(top = 12.dp, bottom = 32.dp),
            ) {
                PageSearchField("Поиск настроек", query) { query = it }
                Spacer(Modifier.height(16.dp))
                if (query.isNotBlank()) {
                    val q = query.trim()
                    val found = index.filter { it.title.contains(q, true) || it.keywords.contains(q, true) || it.page.title.contains(q, true) }
                    if (found.isEmpty()) {
                        Text(
                            "Ничего не найдено",
                            style = MaterialTheme.typography.bodyLarge,
                            color = cs.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    } else {
                        SearchResults(found) { query = ""; page = it }
                    }
                } else {
                    QuickAndSections(browser, app, selected = page, onOpen = { page = it })
                }
            }
        }
        // Правая панель: страница на фоне чуть темнее/светлее списка, как в образце.
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(cs.surfaceContainerLow),
        ) {
            CompositionLocalProvider(LocalSettingsPane provides true) {
                AnimatedContent(
                    targetState = page,
                    transitionSpec = { fadeIn(paneIn) togetherWith fadeOut(paneOut) },
                    label = "settings-pane",
                ) { p ->
                    SettingsPageContent(p, browser, app, onBack, onSitePermissions, onPasswords, onOpenDownloads)
                }
            }
        }
    }
}

@Composable
private fun SettingsSinglePane(
    browser: Browser,
    app: HripsApp,
    onBack: () -> Unit,
    onSitePermissions: () -> Unit,
    onPasswords: () -> Unit,
    onOpenDownloads: () -> Unit,
) {
    var page by remember { mutableStateOf<SettingsPage?>(null) }
    BackHandler(enabled = page != null) { page = null }

    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
    val fadeInSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val fadeOutSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    AnimatedContent(
        targetState = page,
        transitionSpec = {
            if (targetState != null) {
                (slideInHorizontally(spatial) { it / 6 } + fadeIn(fadeInSpec)) togetherWith
                    (slideOutHorizontally(spatial) { -it / 8 } + fadeOut(fadeOutSpec))
            } else {
                (slideInHorizontally(spatial) { -it / 8 } + fadeIn(fadeInSpec)) togetherWith
                    (slideOutHorizontally(spatial) { it / 6 } + fadeOut(fadeOutSpec))
            }
        },
        label = "settings-pages",
    ) { p ->
        if (p == null) {
            MainPage(browser, app, onBack = onBack, onOpen = { page = it })
        } else {
            SettingsPageContent(p, browser, app, { page = null }, onSitePermissions, onPasswords, onOpenDownloads)
        }
    }
}

@Composable
private fun MainPage(browser: Browser, app: HripsApp, onBack: () -> Unit, onOpen: (SettingsPage) -> Unit) {
    val store = browser.store
    val adBlock = app.adBlock
    var query by remember { mutableStateOf("") }

    PageScaffold("Настройки", onBack) {
        PageSearchField("Поиск настроек", query) { query = it }
        Spacer(Modifier.height(16.dp))

        if (query.isNotBlank()) {
            val q = query.trim()
            val found = index.filter { it.title.contains(q, true) || it.keywords.contains(q, true) || it.page.title.contains(q, true) }
            if (found.isEmpty()) {
                Text(
                    "Ничего не найдено",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                SearchResults(found) { query = ""; onOpen(it) }
            }
        } else {
            QuickAndSections(browser, app, selected = null, onOpen = onOpen)
        }
    }
}

@Composable
private fun QuickAndSections(browser: Browser, app: HripsApp, selected: SettingsPage?, onOpen: (SettingsPage) -> Unit) {
    val store = browser.store
    val adBlock = app.adBlock
    Column {
        Column(Modifier.toolsIn(0)) { DefaultBrowserRow() }
        Spacer(Modifier.height(12.dp))

        val adState = when {
            adBlock.error != null -> "Недоступна"
            adBlock.extension == null -> "Загрузка…"
            adBlock.enabled -> "Включена"
            else -> "Выключена"
        }
        Column(Modifier.toolsIn(1)) {
            QuickTilesRow {
                QuickTile(
                    HripsIcons.Block, "Блокировка рекламы", adState, adBlock.enabled, adBlock.extension != null,
                    { adBlock.setBlocking(it) }, Modifier.weight(1f),
                )
                QuickTile(
                    HripsIcons.Shield, "Защита от трекеров", if (store.trackingProtection) "Включена" else "Выключена",
                    store.trackingProtection, true, { browser.setTracking(it) }, Modifier.weight(1f),
                )
                QuickTile(
                    HripsIcons.KeyboardLine, "Подсказки при вводе", if (store.suggestionsOn) "Включены" else "Выключены",
                    store.suggestionsOn, true, { store.updateSuggestions(it) }, Modifier.weight(1f),
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Column(Modifier.toolsIn(2)) { GroupOf(SettingsPage.entries) { p, sh ->
            SettingsRow(
                p.icon(), p.title, p.subtitle, sh, onClick = { onOpen(p) }, selected = p == selected,
                value = if (p == SettingsPage.SEARCH) SearchEngines.current.name else null,
            )
        } }
    }
}

@Composable
private fun DefaultBrowserRow() {
    val context = LocalContext.current
    var isDefault by remember { mutableStateOf(DefaultBrowser.isDefault(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        isDefault = DefaultBrowser.isDefault(context)
    }
    val owner = context as? LifecycleOwner
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) isDefault = DefaultBrowser.isDefault(context) }
        owner?.lifecycle?.addObserver(obs)
        onDispose { owner?.lifecycle?.removeObserver(obs) }
    }
    val check: (@Composable () -> Unit)? = if (isDefault) ({ Icon(HripsIcons.Check, null, tint = MaterialTheme.colorScheme.onSecondaryContainer) }) else null
    HeroBanner(
        title = "Браузер по умолчанию",
        subtitle = if (isDefault) "hrips открывает ссылки из других приложений" else "Сделать hrips браузером по умолчанию",
        icon = HripsIcons.Compass,
        badge = Badge.COOKIE9,
        tone = if (isDefault) 1 else 0,
        badgeSize = 48.dp,
        onClick = {
            val intent = if (isDefault) DefaultBrowser.settingsIntent() else DefaultBrowser.requestIntent(context)
            try {
                launcher.launch(intent)
            } catch (e: Exception) {
                runCatching { context.startActivity(DefaultBrowser.settingsIntent()) }
            }
        },
        trailing = check,
    )
}

@Composable
private fun SearchPage(store: Store, onBack: () -> Unit) {
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
private fun HomePage(app: HripsApp, onBack: () -> Unit) {
    val wallpaper = app.wallpaper
    PageScaffold("Внешний вид", onBack) {
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
        SectionTitle("Значок приложения")
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
                                        Notices.show("Значок изменён. На рабочем столе он может обновиться через пару секунд")
                                    } else {
                                        Notices.show("Не удалось сменить значок")
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

@Composable
private fun PrivacyPage(browser: Browser, onBack: () -> Unit) {
    val store = browser.store
    val context = LocalContext.current
    var clearHistory by remember { mutableStateOf(true) }
    var clearCookies by remember { mutableStateOf(false) }
    var clearCache by remember { mutableStateOf(true) }

    PageScaffold("Конфиденциальность", onBack) {
        val on = store.trackingProtection
        HeroBanner(
            title = if (on) "Защита включена" else "Защита выключена",
            subtitle = if (on) "Трекеры и слежка за вами блокируются" else "Сайты могут следить за вами",
            icon = if (on) HripsIcons.ShieldCheck else HripsIcons.Shield,
            badge = Badge.COOKIE12,
            tone = if (on) 0 else 2,
        )

        SectionTitle("Защита")
        Group(
            { s ->
                PanelSwitch(
                    "Защита от трекеров", "Если сайт работает неправильно, выключите и перезагрузите страницу",
                    on, { browser.setTracking(it) },
                    shape = s,
                )
            },
            { s ->
                PanelSwitch(
                    "Скриншоты в приватных вкладках", "По умолчанию запрещены, а миниатюра в списке приложений скрыта",
                    store.allowPrivateShots, { store.updatePrivateShots(it) },
                    shape = s,
                )
            },
        )

        NetworkPrivacySection(browser)

        SectionTitle("Очистить данные")
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ToggleChip("История", clearHistory, { clearHistory = it }, Modifier.weight(1f))
            ToggleChip("Cookies", clearCookies, { clearCookies = it }, Modifier.weight(1f))
            ToggleChip("Кэш", clearCache, { clearCache = it }, Modifier.weight(1f))
        }
        Reveal(clearCookies) {
            Callout("Cookies и данные сайтов: вы выйдете из аккаунтов на сайтах", Modifier.padding(top = 8.dp), icon = HripsIcons.Alert)
        }
        Spacer(Modifier.height(12.dp))
        Button(shapes = ButtonDefaults.shapes(),
            enabled = clearHistory || clearCookies || clearCache,
            onClick = {
                if (clearHistory) { store.clearHistory(); store.clearSearches() }
                if (clearHistory || clearCache) {
                    TabThumbs.clear(context)
                    PageImages.clear(context)
                    browser.tabs.forEach { it.thumbnail = null }
                }
                browser.clearData(clearCookies, clearCache) {
                    Notices.show("Данные очищены")
                }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("Очистить", style = MaterialTheme.typography.titleMedium) }
    }
}

@Composable
private fun PermissionsPage(onSitePermissions: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    val openApp = {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:" + context.packageName))
            )
        }
    }
    val system = buildList {
        add(Triple("Камера", HripsIcons.Camera, granted(Manifest.permission.CAMERA)))
        add(Triple("Микрофон", HripsIcons.Mic, granted(Manifest.permission.RECORD_AUDIO)))
        add(Triple("Местоположение", HripsIcons.Globe, granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)))
        if (Build.VERSION.SDK_INT >= 33) add(Triple("Уведомления", HripsIcons.Bell, granted(Manifest.permission.POST_NOTIFICATIONS)))
    }
    val okCount = system.count { it.third }

    PageScaffold("Разрешения", onBack) {
        HeroBanner(
            title = "Права в Android",
            subtitle = "Выдано $okCount из ${system.size}",
            icon = HripsIcons.ShieldCheck,
            badge = Badge.PENTAGON,
            tone = if (okCount == system.size) 1 else 2,
        )

        SectionTitle("Сайты")
        Group(
            { s ->
                PanelItem(
                    "Разрешения сайтов", subtitle = "Что вы разрешили или запретили сайтам. Можно отозвать",
                    onClick = onSitePermissions,
                    trailing = { Icon(HripsIcons.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    shape = s,
                )
            },
        )

        SectionTitle("Права браузера в Android")
        TwoColumnGrid(system) { item, m -> StatusTile(item.second, item.first, item.third, { openApp() }, m) }
        Callout(
            "Без системного права сайт не сможет получить доступ, даже если вы разрешили его здесь. Нажмите на плитку, чтобы изменить.",
            Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun DesktopPage(browser: Browser, onBack: () -> Unit) {
    val sites = browser.store.desktopSites.toList()
    PageScaffold("Версия для ПК", onBack) {
        Callout(
            "По умолчанию на всех сайтах открывается мобильная версия. Включить версию для ПК можно в меню страницы (⋮): она запомнится только для этого сайта.",
            icon = HripsIcons.Desktop,
        )
        SectionTitle("Сайты")
        if (sites.isEmpty()) {
            HeroBanner(
                title = "Пока нет ни одного сайта",
                subtitle = "Сайты с включённой версией для ПК появятся здесь",
                icon = HripsIcons.Desktop,
                badge = Badge.BURST,
                tone = 2,
            )
        } else {
            GroupOf(sites) { host, s ->
                PanelItem(
                    host, shape = s, subtitle = "Версия для ПК включена", icon = HripsIcons.Desktop,
                    trailing = {
                        IconButton(onClick = { browser.setSiteDesktop(host, false) }) { Icon(HripsIcons.Trash, "Выключить") }
                    },
                )
            }
            Spacer(Modifier.height(12.dp))
            HripsTextButton(onClick = { browser.resetDesktopSites() }) { Text("Выключить на всех сайтах") }
        }
    }
}

@Composable
private fun MediaPage(store: Store, hover: HoverPreview, onBack: () -> Unit) {
    PageScaffold("Медиа", onBack) {
        Group(
            { s ->
                PanelSwitch(
                    "Управление в уведомлении",
                    "Пауза, перемотка и треки в шторке и на экране блокировки. Звук продолжает играть при выключенном экране",
                    store.mediaControls, { store.updateMediaControls(it) }, icon = HripsIcons.NotificationsLine,
                    shape = s,
                )
            },
            { s ->
                PanelSwitch(
                    "Картинка в картинке",
                    "Видео на весь экран уходит в маленькое окно, когда вы выходите на главный экран",
                    store.pipEnabled, { store.updatePip(it) }, icon = HripsIcons.Pip,
                    shape = s,
                )
            },
            { s ->
                PanelSwitch(
                    "Оживление миниатюр",
                    "Удерживайте палец на миниатюре видео: сайт запустит предпросмотр, как при наведении мыши. Работает там, где он есть у самого сайта",
                    hover.enabled, { hover.setHoverEnabled(it) }, enabled = hover.extension != null, icon = HripsIcons.MotionPlayLine,
                    shape = s,
                )
            },
        )
    }
}

@Composable
private fun PerformancePage(browser: Browser, onBack: () -> Unit) {
    val store = browser.store
    PageScaffold("Производительность", onBack) {
        Group(
            { s ->
                PanelSwitch(
                    "Экономия памяти",
                    "При сильной нехватке памяти фоновые вкладки временно выгружаются. При возврате страница восстанавливается.",
                    store.suspendTabsOnMemoryPressure, { store.updateSuspendTabsOnMemoryPressure(it) },
                    shape = s,
                )
            },
            { s ->
                PanelSwitch(
                    "Держать страницу при сворачивании",
                    "Текущая страница остаётся в памяти до 20 минут после сворачивания, чтобы не перезагружаться при возврате. Занимает больше памяти в фоне.",
                    store.keepPageInBackground, { store.updateKeepPageInBackground(it) },
                    shape = s,
                )
            },
        )
        Callout(
            "Включение может приводить к повторной загрузке тяжёлых страниц. Текущая вкладка и активное воспроизведение не выгружаются.",
            Modifier.padding(top = 12.dp), icon = HripsIcons.Gauge,
        )
    }
}

@Composable
private fun DownloadsPage(browser: Browser, onBack: () -> Unit, onOpenDownloads: () -> Unit) {
    val store = browser.store
    val downloads = browser.downloads
    val hasFinished = downloads.items.any { !downloads.isOngoing(it) }
    PageScaffold("Загрузки", onBack) {
        Group(
            { s ->
                PanelSwitch(
                    "Спрашивать перед загрузкой", "Показывать имя файла и размер, чтобы подтвердить или отменить",
                    store.askBeforeDownload, { store.updateAskBeforeDownload(it) },
                    shape = s,
                )
            },
        )
        SectionTitle("Файлы")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionTile(HripsIcons.File, "Список загрузок", "Скачанные файлы и текущие загрузки", onOpenDownloads, Modifier.weight(1f))
            ActionTile(
                HripsIcons.Trash, "Очистить список", "Сами файлы останутся на устройстве",
                { downloads.clearFinished() }, Modifier.weight(1f), enabled = hasFinished,
            )
        }
        Callout("Файлы сохраняются в «Загрузки» на устройстве", Modifier.padding(top = 8.dp), icon = HripsIcons.Folder)
    }
}

@Composable
private fun AboutPage(app: HripsApp, onBack: () -> Unit) {
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }
    val gecko = remember {
        runCatching {
            Class.forName("org.mozilla.geckoview.BuildConfig").getField("MOZ_APP_VERSION").get(null) as String
        }.getOrDefault("")
    }
    PageScaffold("О программе", onBack) {
        HeroBanner(
            title = "hrips",
            subtitle = if (version.isBlank()) "Версия неизвестна" else "Версия $version",
            icon = HripsIcons.Globe,
            badge = Badge.SUNNY,
            tone = 0,
        )
        SectionTitle("Сборка")
        Group(
            { s ->
                PanelItem("Движок", value = if (gecko.isBlank()) "GeckoView" else "GeckoView $gecko", shape = s)
            },
            { s ->
                PanelItem("Блокировка рекламы", value = "uBlock Origin" + if (app.adBlock.extension == null) " (не загружена)" else "", shape = s)
            },
            { s ->
                PanelItem("Android", value = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})", shape = s)
            },
        )
        if (Updater.available) {
            SectionTitle("Обновления")
            Group(
                { s ->
                    UpdateRow(app, shape = s)
                },
                { s ->
                    PanelSwitch(
                        "Проверять автоматически", "Раз в сутки при запуске. Запрос идёт на github.com",
                        app.store.autoUpdate, { app.store.updateAutoUpdate(it) },
                        shape = s,
                    )
                },
            )
            (Updater.state as? Updater.State.Available)?.info?.notes?.takeIf { it.isNotBlank() }?.let {
                Callout(it, Modifier.padding(top = 12.dp), icon = HripsIcons.Package)
            }
        }
        SectionTitle("Диагностика")
        DiagnosticsGroup(app)
    }
}

@Composable
private fun DiagnosticsGroup(app: HripsApp) {
    val context = LocalContext.current
    var size by remember { mutableStateOf(AppLog.sizeBytes(context)) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(app.store.logging) {
        while (true) {
            size = AppLog.sizeBytes(context)
            delay(2_000L)
        }
    }
    val path = remember {
        AppLog.file(context).absolutePath.let { it.substringAfter("/emulated/0/", it) }
    }
    val sizeText = when {
        size <= 0L -> "Журнал пуст"
        size < 1024L * 1024L -> "Размер ${(size + 1023L) / 1024L} КБ"
        else -> "Размер ${"%.1f".format(size / 1024.0 / 1024.0)} МБ"
    }
    Group(
        { s ->
            PanelSwitch(
                "Записывать журнал",
                "Для поиска ошибок: действия, сбои и зависания пишутся в файл. Адреса приватных вкладок не сохраняются",
                app.store.logging,
                {
                    app.store.updateLogging(it)
                    AppLog.setEnabled(it)
                    FrameJank.sync(context)
                },
                shape = s,
            )
        },
        { s ->
            PanelItem(
                "Отправить журнал", subtitle = sizeText, icon = HripsIcons.Share,
                enabled = !busy && size > 0L,
                onClick = {
                    busy = true
                    AppExecutors.startThread("hrips-log-share") {
                        val file = runCatching { AppLog.buildShareFile(context.applicationContext) }.getOrNull()
                        MainThread.handler.post {
                            busy = false
                            if (file == null) {
                                Notices.show("Журнал пуст")
                            } else {
                                try {
                                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                                    val send = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        putExtra(Intent.EXTRA_SUBJECT, "Журнал hrips")
                                        clipData = ClipData.newRawUri("", uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(send, "Отправить журнал"))
                                } catch (e: Exception) {
                                    AppLog.w("Settings", "не удалось отправить журнал", e)
                                    Notices.show("Не удалось отправить журнал")
                                }
                            }
                        }
                    }
                },
                shape = s,
            )
        },
        { s ->
            PanelItem("Файл журнала", subtitle = path, icon = HripsIcons.Folder, shape = s)
        },
        { s ->
            PanelItem(
                "Очистить журнал", icon = HripsIcons.Trash, enabled = size > 0L,
                onClick = {
                    AppLog.clear(context)
                    size = AppLog.sizeBytes(context)
                },
                shape = s,
            )
        },
    )
}

@Composable
private fun UpdateRow(app: HripsApp, shape: Shape) {
    val context = LocalContext.current
    when (val st = Updater.state) {
        is Updater.State.Checking -> PanelItem("Обновления", subtitle = "Проверяю…", icon = HripsIcons.BarRefresh, shape = shape)
        is Updater.State.Available -> PanelItem(
            "Доступна версия ${st.info.versionName}",
            subtitle = "Нажмите, чтобы скачать" + if (st.info.size > 0) " (${st.info.size / 1_000_000} МБ)" else "",
            icon = HripsIcons.Download,
            onClick = { Updater.download(app, st.info) },
            shape = shape,
        )
        is Updater.State.Downloading -> PanelItem("Скачиваю ${st.info.versionName}", value = "${st.percent}%", icon = HripsIcons.Download, shape = shape)
        is Updater.State.Ready -> PanelItem(
            "Установить ${st.info.versionName}", subtitle = "Файл загружен и проверен. Нажмите, чтобы установить",
            icon = HripsIcons.Package, onClick = { Updater.install(context) },
            shape = shape,
        )
        is Updater.State.UpToDate -> PanelItem(
            "Установлена последняя версия", subtitle = "Нажмите, чтобы проверить ещё раз",
            icon = HripsIcons.Check, onClick = { Updater.check(app, manual = true) },
            shape = shape,
        )
        is Updater.State.Failed -> PanelItem(
            st.message.ifBlank { "Не удалось проверить обновления" }, subtitle = "Нажмите, чтобы повторить",
            icon = HripsIcons.Alert, onClick = { Updater.check(app, manual = true) },
            shape = shape,
        )
        else -> PanelItem("Проверить обновления", icon = HripsIcons.BarRefresh, onClick = { Updater.check(app, manual = true) }, shape = shape)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PageScaffold(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    if (LocalSettingsPane.current) {
        // Правая панель планшета: стрелка и название в одной строке, дальше прокручиваемое содержимое (как в системных настройках).
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onBack,
                    colors = IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                ) { Icon(HripsIcons.BarChevronBack, "Закрыть настройки") }
                Spacer(Modifier.width(16.dp))
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    Modifier.widthIn(max = 840.dp).fillMaxWidth().padding(bottom = 32.dp),
                    content = content,
                )
            }
        }
        return
    }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            PageTopBar(title, onBack, scroll)
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = HripsLayout.PageGutter),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.widthIn(max = HripsLayout.PageMaxWidth).fillMaxWidth().padding(bottom = 32.dp),
                content = content,
            )
        }
    }
}

@Composable
internal fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
    )
}

private fun segment(i: Int, n: Int): Shape = HripsShapes.segment(i, n)

@Composable
internal fun <T> GroupOf(items: List<T>, row: @Composable (T, Shape) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items.forEachIndexed { i, item -> row(item, segment(i, items.size)) }
    }
}

@Composable
internal fun Group(vararg rows: @Composable (Shape) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        rows.forEachIndexed { i, row -> row(segment(i, rows.size)) }
    }
}

@Composable
private fun IconTile(icon: ImageVector) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.size(40.dp).clip(CircleShape).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(24.dp), tint = cs.onPrimaryContainer)
    }
}

@Composable
internal fun SettingsRow(
    icon: ImageVector?,
    title: String,
    subtitle: String?,
    shape: Shape,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    value: String? = null,
    // Свой значок вместо круга с иконкой (логотип поисковой системы).
    leading: (@Composable () -> Unit)? = null,
    // Строка списка разделов на планшете, открытая справа: полностью скруглённая и темнее остальных.
    selected: Boolean = false,
) {
    val content: @Composable () -> Unit = {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (leading != null) leading() else if (icon != null) IconTile(icon)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (value != null) {
                Text(value, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 140.dp))
            }
            trailing?.invoke()
        }
    }
    val modifier = Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.5f)
    val cs = MaterialTheme.colorScheme
    val rowShape: Shape = if (selected) HripsShapes.L else shape
    val rowColor by animateColorAsState(
        if (selected) cs.surfaceContainerLowest else cs.surfaceContainerHigh,
        MaterialTheme.motionScheme.fastEffectsSpec<Color>(), label = "rowColor",
    )
    if (onClick != null) {
        PressSurface(
            onClick = onClick, corners = rowShape.asCorners(), color = rowColor, contentColor = cs.onSurface,
            pressedCorner = 20.dp, pressScale = 0.985f, modifier = modifier, enabled = enabled, content = content,
        )
    } else {
        Surface(shape = rowShape, color = rowColor, modifier = modifier, content = content)
    }
}

@Composable
internal fun SwitchRow(
    icon: ImageVector?,
    title: String,
    subtitle: String?,
    checked: Boolean,
    enabled: Boolean,
    shape: Shape,
    onChange: (Boolean) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    SettingsRow(
        icon, title, subtitle, shape,
        enabled = enabled,
        onClick = { haptic.performHapticFeedback(if (checked) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn); onChange(!checked) },
        trailing = {
            HripsSwitch(checked = checked, onCheckedChange = null, enabled = enabled)
        },
    )
}

@Composable
private fun CheckRow(title: String, checked: Boolean, shape: Shape, subtitle: String? = null, onChange: (Boolean) -> Unit) {
    val haptic = LocalHapticFeedback.current
    SettingsRow(
        null, title, subtitle, shape,
        onClick = { haptic.performHapticFeedback(if (checked) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn); onChange(!checked) },
        trailing = { Checkbox(checked = checked, onCheckedChange = null) },
    )
}

