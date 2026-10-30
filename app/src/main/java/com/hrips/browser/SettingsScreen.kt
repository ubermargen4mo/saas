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
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
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
    HOME("Внешний вид", "Иконка приложения, обои"),
    PRIVACY("Конфиденциальность", "Трекеры, очистка данных, приватный режим"),
    PASSWORDS("Пароли и автозаполнение", "Сохранённые пароли, автозаполнение, импорт"),
    PERMISSIONS("Разрешения", "Камера, микрофон, местоположение, уведомления"),
    DESKTOP("Версия для ПК", "Сайты, где она включена"),
    MEDIA("Медиа", "Управление из шторки, картинка в картинке"),
    DOWNLOADS("Загрузки", "Подтверждение, папка, индикатор"),
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
    SettingEntry("Иконка приложения", "значок лого креветка синяя оранжевая серая icon", SettingsPage.HOME),
    SettingEntry("Внешний вид", "оформление тема иконка значок обои", SettingsPage.HOME),
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
    SettingEntry("Папка загрузок", "куда сохраняются файлы каталог", SettingsPage.DOWNLOADS),
    SettingEntry("Индикатор загрузки", "кольцо волна толщина", SettingsPage.DOWNLOADS),
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
            SettingsPage.MEDIA -> MediaPage(browser.store, back)
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

        Column(Modifier.toolsIn(1)) {
            Group(
                { sh ->
                    PanelSwitch(
                        "Блокировка рекламы",
                        when {
                            adBlock.error != null -> "Недоступна"
                            adBlock.extension == null -> "Загрузка…"
                            else -> "Скрывает рекламу на сайтах (uBlock Origin)"
                        },
                        adBlock.enabled, { adBlock.setBlocking(it) },
                        enabled = adBlock.extension != null, icon = HripsIcons.Block, shape = sh,
                    )
                },
                { sh ->
                    PanelSwitch(
                        "Защита от трекеров", "Блокирует трекеры и слежку за вами",
                        store.trackingProtection, { browser.setTracking(it) }, icon = HripsIcons.Shield, shape = sh,
                    )
                },
                { sh ->
                    PanelSwitch(
                        "Подсказки при вводе", "Варианты запросов от поисковой системы",
                        store.suggestionsOn, { store.updateSuggestions(it) }, icon = HripsIcons.KeyboardLine, shape = sh,
                    )
                },
            )
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
    val launch: (android.content.Intent) -> Unit = { intent ->
        try {
            launcher.launch(intent)
        } catch (e: Exception) {
            runCatching { context.startActivity(DefaultBrowser.settingsIntent()) }
        }
    }
    val openSettings = { launch(DefaultBrowser.settingsIntent()) }
    val makeDefault = { launch(DefaultBrowser.requestIntent(context)) }
    // Галочка: зелёная, на мягкой зелёной подложке.
    val check: (@Composable () -> Unit)? = if (isDefault) ({
        Box(Modifier.size(36.dp).clip(CircleShape).background(StatusOk.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
            Icon(HripsIcons.Check, null, Modifier.size(20.dp), tint = StatusOk)
        }
    }) else null
    HeroBanner(
        title = "Браузер по умолчанию",
        subtitle = null,
        icon = HripsIcons.Compass,
        badge = Badge.COOKIE9,
        tone = 3,
        badgeSize = 48.dp,
        onClick = if (isDefault) openSettings else null,
        trailing = check,
        action = if (isDefault) null else ({
            Button(
                shapes = ButtonDefaults.shapes(),
                onClick = makeDefault,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) { Text("Сделать по умолчанию", style = MaterialTheme.typography.titleSmall) }
        }),
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

private class StatusCheck(val title: String, val ok: Boolean, val okText: String, val badText: String, val fix: () -> Unit)

@Composable
private fun PrivacyPage(browser: Browser, onBack: () -> Unit) {
    val store = browser.store
    val context = LocalContext.current
    var showClear by remember { mutableStateOf(false) }

    PageScaffold("Конфиденциальность", onBack) {
        val on = store.trackingProtection
        val checks = listOf(
            StatusCheck("Защита от трекеров", on, "Блокирует трекеры и слежку", "Выключена. Нажмите, чтобы включить") { browser.setTracking(true) },
            StatusCheck("Global Privacy Control", store.gpc, "Сайты просят не продавать ваши данные", "Выключен. Нажмите, чтобы включить") {
                store.updateGpc(true); browser.applyPrivacy()
            },
            StatusCheck("Tracking-параметры в адресах", store.stripTrackingParams, "Удаляются до загрузки страницы", "Не удаляются. Нажмите, чтобы включить") {
                store.updateStripTrackingParams(true); browser.applyPrivacy()
            },
            StatusCheck("Соединение HTTPS", store.httpsMode != PrivacyEngine.HTTPS_OFF, "Предупреждение о сайтах без шифрования", "Без предупреждений. Нажмите, чтобы включить") {
                store.updateHttpsMode(PrivacyEngine.HTTPS_ALL); browser.applyPrivacy()
            },
            StatusCheck("Защищённый DNS", store.dohMode != PrivacyEngine.DOH_OFF, "Запросы адресов шифруются", "Выключен. Нажмите, чтобы включить") {
                store.updateDohMode(PrivacyEngine.DOH_AUTO); browser.applyPrivacy()
            },
            StatusCheck("Скриншоты приватных вкладок", !store.allowPrivateShots, "Запрещены", "Разрешены. Нажмите, чтобы запретить") {
                store.updatePrivateShots(false)
            },
        )
        val okCount = checks.count { it.ok }
        val allOk = okCount == checks.size
        // Зелёный: всё включено; красный: ничего или почти ничего; иначе жёлтый. Дуги того же цвета, что и круг.
        val tone = when {
            allOk -> StatusOk
            okCount * 3 <= checks.size -> StatusBad
            else -> StatusWarn
        }
        Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            StatusRing(
                filled = okCount, total = checks.size,
                center = tone,
                arc = tone,
                icon = if (allOk) HripsIcons.Check else HripsIcons.Alert,
                size = 120.dp,
            )
            Spacer(Modifier.height(20.dp))
            Text(
                if (allOk) "Защита включена" else if (tone == StatusBad) "Защита почти не включена" else "Защиту можно улучшить",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Text(
                if (allOk) "Все проверки пройдены" else "Проверьте настройки ($okCount из ${checks.size})",
                Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Column(Modifier.fillMaxWidth()) {
            checks.forEach { c -> StatusRow(c.ok, c.title, if (c.ok) c.okText else c.badText, onClick = if (c.ok) null else c.fix) }
        }

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
        Group(
            { s ->
                PanelItem(
                    "Очистить данные", subtitle = "История, cookies, кэш", icon = HripsIcons.Trash,
                    onClick = { showClear = true },
                    trailing = { Icon(HripsIcons.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    shape = s,
                )
            },
        )
        if (showClear) ClearDataSheet(browser) { showClear = false }
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
        // Сводка: слева список прав с отметками, справа крупное число выданных.
        val cs = MaterialTheme.colorScheme
        Row(
            Modifier.fillMaxWidth().clip(HripsShapes.XL).background(cs.surfaceContainerHigh).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                Modifier.weight(1.4f).clip(HripsShapes.ML).background(cs.surfaceContainerLow).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                system.forEach { (title, _, ok) ->
                    Row(Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                        Box(Modifier.size(20.dp).clip(CircleShape).background(if (ok) StatusOk else StatusWarn), contentAlignment = Alignment.Center) {
                            Icon(if (ok) HripsIcons.Check else HripsIcons.Alert, null, Modifier.size(12.dp), tint = Color(0xFF14231A))
                        }
                    }
                }
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "$okCount",
                    style = MaterialTheme.typography.displayMedium,
                    color = if (okCount == system.size) StatusOk else StatusWarn,
                )
                Text("из ${system.size} выдано", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }

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
private fun MediaPage(store: Store, onBack: () -> Unit) {
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

/** Подпись папки загрузок: путь внутри хранилища из адреса дерева SAF. */
private fun folderLabel(tree: String?): String {
    if (tree == null) return "Загрузки (системная папка)"
    return runCatching {
        val id = android.provider.DocumentsContract.getTreeDocumentId(Uri.parse(tree))
        id.substringAfter(':', "").ifBlank { "Внутренняя память" }
    }.getOrDefault("Выбранная папка")
}

@Composable
private fun DownloadsPage(browser: Browser, onBack: () -> Unit, onOpenDownloads: () -> Unit) {
    val store = browser.store
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            store.updateDlTree(uri.toString())
        }
    }
    PageScaffold("Загрузки", onBack) {
        Group(
            { s ->
                PanelSwitch(
                    "Спрашивать перед загрузкой", "Показывать имя файла и размер, чтобы подтвердить или отменить",
                    store.askBeforeDownload, { store.updateAskBeforeDownload(it) },
                    shape = s,
                )
            },
            { s ->
                PanelItem(
                    "Папка загрузок", subtitle = folderLabel(store.dlTree), icon = HripsIcons.Folder,
                    onClick = { picker.launch(null) },
                    trailing = { Icon(HripsIcons.ChevronRight, null, tint = cs.onSurfaceVariant) },
                    shape = s,
                )
            },
        )
        if (store.dlTree != null) {
            HripsTextButton(
                onClick = {
                    store.dlTree?.let { t ->
                        runCatching {
                            context.contentResolver.releasePersistableUriPermission(
                                Uri.parse(t), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                            )
                        }
                    }
                    store.updateDlTree(null)
                },
            ) { Text("Вернуть системную папку «Загрузки»") }
        }
        Callout(
            "Новые загрузки сохраняются в выбранную папку. Файлы, скачанные раньше, остаются на месте.",
            Modifier.padding(top = 8.dp), icon = HripsIcons.Folder,
        )

        SectionTitle("Индикатор загрузки")
        val demo by androidx.compose.animation.core.rememberInfiniteTransition(label = "ringDemo").animateFloat(
            0.08f, 1f,
            androidx.compose.animation.core.infiniteRepeatable(
                androidx.compose.animation.core.tween(3600, easing = androidx.compose.animation.core.LinearEasing),
                androidx.compose.animation.core.RepeatMode.Restart,
            ),
            label = "demo",
        )
        Box(
            Modifier.fillMaxWidth().height(150.dp).clip(HripsShapes.XL).background(cs.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            // Настоящий размер кольца (32 dp) с кнопкой загрузок внутри, увеличенный для просмотра.
            Box(Modifier.size(48.dp).graphicsLayer { scaleX = 2.6f; scaleY = 2.6f }, contentAlignment = Alignment.Center) {
                DownloadRing(demo, Modifier.size(32.dp))
                Icon(HripsIcons.Download, null, Modifier.size(24.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        val rows = buildList<@Composable (androidx.compose.ui.graphics.Shape) -> Unit> {
            add { s ->
                SliderRow(
                    "Толщина", "%.1f dp".format(store.dlRingThick), store.dlRingThick, 1.5f..6f, 8, s,
                ) { store.updateDlRingThick(it) }
            }
            if (store.dlRingWavy) {
                add { s ->
                    SliderRow(
                        "Высота волны", "%.1f dp".format(store.dlRingAmp), store.dlRingAmp, 0.5f..3f, 4, s,
                    ) { store.updateDlRingAmp(it) }
                }
            }
            add { s ->
                PanelSwitch(
                    "Волнистое кольцо", "Заполненная часть кольца идёт волной",
                    store.dlRingWavy, { store.updateDlRingWavy(it) }, shape = s,
                )
            }
        }
        Group(*rows.toTypedArray())
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
            UpdateButton(app)
            Spacer(Modifier.height(12.dp))
            Group(
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

