@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.hrips.browser

import androidx.compose.ui.unit.IntOffset
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf

enum class SettingsPage(val title: String, val subtitle: String) {
    SEARCH("Поиск", "Поисковая система, подсказки при вводе"),
    HOME("Внешний вид", "Цвет темы, иконка приложения, обои"),
    PRIVACY("Конфиденциальность", "Реклама, трекеры, очистка данных, приватный режим"),
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

internal val LocalSettingsPane = compositionLocalOf { false }

@Composable
private fun SearchOrEmpty(query: String, onPick: (SettingsPage) -> Unit) {
    val q = query.trim()
    val found = remember(q) {
        index.filter { it.title.contains(q, true) || it.keywords.contains(q, true) || it.page.title.contains(q, true) }
    }
    if (found.isEmpty()) {
        Text(
            "Ничего не найдено",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    } else {
        SearchResults(found, onPick)
    }
}

private class SettingEntry(val title: String, val keywords: String, val page: SettingsPage)

private val index = listOf(
    SettingEntry("Поисковая система", "google yandex duckduckgo bing поиск движок", SettingsPage.SEARCH),
    SettingEntry("Подсказки при вводе", "варианты запросов автодополнение", SettingsPage.SEARCH),
    SettingEntry("История поиска", "очистить запросы", SettingsPage.SEARCH),
    SettingEntry("Обои главной страницы", "фон картинка фото", SettingsPage.HOME),
    SettingEntry("Иконка приложения", "значок лого креветка синяя оранжевая серая icon", SettingsPage.HOME),
    SettingEntry("Цвет темы", "тема цвет палитра rgb hex акцент оформление material you", SettingsPage.HOME),
    SettingEntry("Внешний вид", "оформление тема иконка значок обои", SettingsPage.HOME),
    SettingEntry("Вкладки на планшете", "панель вкладок сверху снизу слева скрыть показывать", SettingsPage.HOME),
    SettingEntry("Блокировка рекламы", "реклама ublock origin баннеры адблок", SettingsPage.PRIVACY),
    SettingEntry("Защита от трекеров", "трекеры слежка отслеживание", SettingsPage.PRIVACY),
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
            SettingsPage.PRIVACY -> PrivacyPage(browser, app, back)
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
                    SearchOrEmpty(query) { query = ""; page = it }
                } else {
                    QuickAndSections(browser, app, selected = page, onOpen = { page = it })
                }
            }
        }
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
    var query by remember { mutableStateOf("") }

    PageScaffold("Настройки", onBack) {
        PageSearchField("Поиск настроек", query) { query = it }
        Spacer(Modifier.height(16.dp))

        if (query.isNotBlank()) {
            SearchOrEmpty(query) { query = ""; onOpen(it) }
        } else {
            QuickAndSections(browser, app, selected = null, onOpen = onOpen)
        }
    }
}

@Composable
private fun QuickAndSections(browser: Browser, app: HripsApp, selected: SettingsPage?, onOpen: (SettingsPage) -> Unit) {
    Column {
        Column(Modifier.toolsIn(0)) { DefaultBrowserRow() }
        Spacer(Modifier.height(12.dp))

        Column(Modifier.toolsIn(1)) { GroupOf(SettingsPage.entries) { p, sh ->
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
