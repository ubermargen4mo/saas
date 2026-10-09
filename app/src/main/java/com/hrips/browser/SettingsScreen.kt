@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.hrips.browser

import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.IntOffset
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/** Разделы настроек. Порядок = порядок на главной странице настроек. */
enum class SettingsPage(val title: String, val subtitle: String) {
    SEARCH("Поиск", "Поисковая система, подсказки"),
    HOME("Внешний вид", "Значок приложения, обои"),
    PRIVACY("Конфиденциальность", "Трекеры, очистка данных, приватный режим"),
    PASSWORDS("Пароли и автозаполнение", "Системные менеджеры паролей"),
    PERMISSIONS("Разрешения", "Камера, микрофон, местоположение, уведомления"),
    DESKTOP("Версия для ПК", "Сайты, где она включена"),
    MEDIA("Медиа", "Управление из шторки, картинка в картинке"),
    DOWNLOADS("Загрузки", "Подтверждение, список файлов"),
    PERFORMANCE("Производительность", "Память, фоновые вкладки"),
    ABOUT("О программе", "Версия, движок"),
}

private fun SettingsPage.icon(): ImageVector = when (this) {
    SettingsPage.SEARCH -> HripsIcons.Search
    SettingsPage.HOME -> HripsIcons.Home
    SettingsPage.PRIVACY -> HripsIcons.Mask
    SettingsPage.PASSWORDS -> HripsIcons.Key
    SettingsPage.PERMISSIONS -> HripsIcons.Shield
    SettingsPage.DESKTOP -> HripsIcons.Desktop
    SettingsPage.MEDIA -> HripsIcons.Video
    SettingsPage.DOWNLOADS -> HripsIcons.Download
    SettingsPage.PERFORMANCE -> HripsIcons.Speed
    SettingsPage.ABOUT -> HripsIcons.Info
}

/** Для поиска по настройкам: название пункта, слова для поиска и раздел, где он находится. */
private class SettingEntry(val title: String, val keywords: String, val page: SettingsPage)

private val index = listOf(
    SettingEntry("Поисковая система", "google yandex duckduckgo bing поиск движок", SettingsPage.SEARCH),
    SettingEntry("История поиска", "очистить запросы", SettingsPage.SEARCH),
    SettingEntry("Обои главной страницы", "фон картинка фото", SettingsPage.HOME),
    SettingEntry("Значок приложения", "иконка лого креветка синяя оранжевая серая icon", SettingsPage.HOME),
    SettingEntry("Внешний вид", "оформление тема значок обои", SettingsPage.HOME),
    SettingEntry("Скриншоты в приватных вкладках", "приватный режим снимок экрана", SettingsPage.PRIVACY),
    SettingEntry("Очистить данные", "история cookies куки кэш удалить", SettingsPage.PRIVACY),
    SettingEntry("Global Privacy Control", "gpc не продавать данные сигнал приватность", SettingsPage.PRIVACY),
    SettingEntry("Картинки страниц в карточках вкладок", "превью og:image карточки вкладок запросы", SettingsPage.PRIVACY),
    SettingEntry("Скрывать запросы cookie", "баннеры согласия куки отклонять", SettingsPage.PRIVACY),
    SettingEntry("Безопасное соединение (HTTPS)", "https only http шифрование предупреждение", SettingsPage.PRIVACY),
    SettingEntry("Защищённый DNS (DoH)", "dns doh днс cloudflare quad9 adguard провайдер", SettingsPage.PRIVACY),
    SettingEntry("Автозаполнение", "пароли менеджер bitwarden google 1password", SettingsPage.PASSWORDS),
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

/**
 * Настройки на весь экран, как страница загрузок. Главная страница: поиск по настройкам, быстрые
 * переключатели и разделы, каждый раздел открывается отдельной страницей. Карточки в группах
 * сегментированы: у крайних большое скругление, у средних малое.
 */
@Composable
fun SettingsScreen(
    browser: Browser,
    app: HripsApp,
    onBack: () -> Unit,
    onSitePermissions: () -> Unit,
    onOpenDownloads: () -> Unit,
) {
    var page by remember { mutableStateOf<SettingsPage?>(null) }
    BackHandler { if (page != null) page = null else onBack() }

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
            val back = { page = null }
            when (p) {
                SettingsPage.SEARCH -> SearchPage(browser.store, back)
                SettingsPage.HOME -> HomePage(app, back)
                SettingsPage.PRIVACY -> PrivacyPage(browser, back)
                SettingsPage.PASSWORDS -> PasswordsPage(back)
                SettingsPage.PERMISSIONS -> PermissionsPage(onSitePermissions, back)
                SettingsPage.DESKTOP -> DesktopPage(browser, back)
                SettingsPage.MEDIA -> MediaPage(browser.store, app.hoverPreview, back)
                SettingsPage.DOWNLOADS -> DownloadsPage(browser, back, onOpenDownloads)
                SettingsPage.PERFORMANCE -> PerformancePage(browser, back)
                SettingsPage.ABOUT -> AboutPage(app, back)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Главная страница
// ---------------------------------------------------------------------------------------------

@Composable
private fun MainPage(browser: Browser, app: HripsApp, onBack: () -> Unit, onOpen: (SettingsPage) -> Unit) {
    val store = browser.store
    val adBlock = app.adBlock
    var query by remember { mutableStateOf("") }

    PageScaffold("Настройки", onBack) {
        SearchBox(query) { query = it }
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
                GroupOf(found) { e, s ->
                    SettingsRow(e.page.icon(), e.title, e.page.title, s, onClick = { query = ""; onOpen(e.page) })
                }
            }
        } else {
            QuickAndSections(browser, app, onOpen)
        }
    }
}

@Composable
private fun QuickAndSections(browser: Browser, app: HripsApp, onOpen: (SettingsPage) -> Unit) {
    val store = browser.store
    val adBlock = app.adBlock
    Column {
        DefaultBrowserRow()
        Spacer(Modifier.height(20.dp))

        // Быстрые переключатели
        val adSubtitle = when {
            adBlock.error != null -> "Недоступна: ${adBlock.error}"
            adBlock.extension == null -> "Загрузка..."
            adBlock.enabled -> "Включена"
            else -> "Выключена"
        }
        Group(
            { s ->
                SwitchRow(HripsIcons.Block, "Блокировка рекламы", adSubtitle, adBlock.enabled, adBlock.extension != null, s) {
                    adBlock.setBlocking(it)
                }
            },
            { s ->
                SwitchRow(
                    HripsIcons.Shield, "Защита от трекеров",
                    if (store.trackingProtection) "Включена" else "Выключена. Сайты могут следить за вами",
                    store.trackingProtection, true, s,
                ) { browser.setTracking(it) }
            },
            { s ->
                SwitchRow(
                    HripsIcons.Search, "Подсказки при вводе", "Вводимый текст отправляется поисковой системе",
                    store.suggestionsOn, true, s,
                ) { store.updateSuggestions(it) }
            },
        )

        Spacer(Modifier.height(20.dp))

        // Разделы
        GroupOf(SettingsPage.entries) { p, s ->
            SettingsRow(p.icon(), p.title, p.subtitle, s, onClick = { onOpen(p) })
        }
    }
}

/** Карточка «Браузер по умолчанию»: системное окно выбора, статус обновляется при возврате в приложение. */
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
    val check: (@Composable () -> Unit)? = if (isDefault) ({ Icon(HripsIcons.Check, null, tint = MaterialTheme.colorScheme.primary) }) else null
    Group(
        { s ->
            SettingsRow(
                HripsIcons.Link,
                "Браузер по умолчанию",
                if (isDefault) "hrips открывает ссылки из других приложений" else "Сделать hrips браузером по умолчанию",
                s,
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
        },
    )
}

// ---------------------------------------------------------------------------------------------
// Подстраницы
// ---------------------------------------------------------------------------------------------

@Composable
private fun SearchPage(store: Store, onBack: () -> Unit) {
    val context = LocalContext.current
    PageScaffold("Поиск", onBack) {
        SectionTitle("Поисковая система")
        GroupOf(SearchEngines.all) { e, s ->
            SettingsRow(
                null, e.name, null, s,
                onClick = { store.setSearchEngine(e) },
                trailing = { RadioButton(selected = SearchEngines.current == e, onClick = null) },
            )
        }

        SectionTitle("Ввод")
        Group(
            { s ->
                SwitchRow(
                    null, "Подсказки при вводе", "Вводимый текст отправляется выбранной поисковой системе",
                    store.suggestionsOn, true, s,
                ) { store.updateSuggestions(it) }
            },
            { s ->
                SettingsRow(
                    null, "Очистить историю поиска",
                    if (store.searches.isEmpty()) "Пока пусто" else "Запросов: ${store.searches.size}", s,
                    enabled = store.searches.isNotEmpty(),
                    onClick = {
                        store.clearSearches()
                        Notices.show("История поиска очищена")
                    },
                )
            },
        )
    }
}

@Composable
private fun HomePage(app: HripsApp, onBack: () -> Unit) {
    val wallpaper = app.wallpaper
    PageScaffold("Внешний вид", onBack) {
        SectionTitle("Значок приложения")
        AppIconPicker()

        SectionTitle("Обои главной страницы")
        wallpaper.image?.let { img ->
            Image(
                bitmap = img,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(24.dp)),
            )
            Spacer(Modifier.height(8.dp))
        }
        Group(
            { s -> SettingsRow(HripsIcons.Image, "Выбрать фото", "Фон главной страницы", s, onClick = { wallpaper.pick?.invoke() }) },
            { s ->
                SettingsRow(
                    HripsIcons.Trash, "Убрать фон", if (wallpaper.image == null) "Сейчас фон не задан" else null, s,
                    enabled = wallpaper.image != null,
                    onClick = { wallpaper.clear() },
                )
            },
        )
    }
}

/** Два варианта значка с превью: выбранный выделен рамкой. Переключается сразу. */
@Composable
private fun AppIconPicker() {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var selected by remember { mutableStateOf(AppIcons.current(context)) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AppIcon.entries.forEach { icon ->
            val isSelected = icon == selected
            Surface(
                onClick = {
                    if (!isSelected) {
                        AppIcons.set(context, icon)
                        selected = icon
                        Notices.show("Значок изменён. На рабочем столе он может обновиться через пару секунд")
                    }
                },
                shape = RoundedCornerShape(28.dp),
                color = if (isSelected) cs.primaryContainer else cs.surfaceContainerHigh,
                border = if (isSelected) androidx.compose.foundation.BorderStroke(2.dp, cs.primary) else null,
                modifier = Modifier.weight(1f),
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(
                        painter = androidx.compose.ui.res.painterResource(icon.preview),
                        contentDescription = icon.title,
                        modifier = Modifier.size(84.dp).clip(RoundedCornerShape(26.dp)),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        icon.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (isSelected) cs.onPrimaryContainer else cs.onSurface,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
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
        SectionTitle("Защита")
        Group(
            { s ->
                SwitchRow(
                    HripsIcons.Shield, "Защита от трекеров",
                    "Если сайт работает неправильно, выключите и перезагрузите страницу",
                    store.trackingProtection, true, s,
                ) { browser.setTracking(it) }
            },
            { s ->
                SwitchRow(
                    HripsIcons.Mask, "Скриншоты в приватных вкладках",
                    "По умолчанию запрещены, а миниатюра в списке приложений скрыта",
                    store.allowPrivateShots, true, s,
                ) { store.updatePrivateShots(it) }
            },
        )

        NetworkPrivacySection(browser)

        SectionTitle("Очистить данные")
        Group(
            { s -> CheckRow("История", clearHistory, s) { clearHistory = it } },
            { s -> CheckRow("Cookies и данные сайтов", clearCookies, s, "Вы выйдете из аккаунтов на сайтах") { clearCookies = it } },
            { s -> CheckRow("Кэш", clearCache, s, "Временные файлы сайтов") { clearCache = it } },
        )
        Spacer(Modifier.height(12.dp))
        Button(shapes = ButtonDefaults.shapes(),
            enabled = clearHistory || clearCookies || clearCache,
            onClick = {
                if (clearHistory) { store.clearHistory(); store.clearSearches() }
                if (clearHistory || clearCache) {
                    // Превью страниц в карточках вкладок: и на диске, и в памяти
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
private fun PasswordsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    PageScaffold("Пароли и автозаполнение", onBack) {
        Text(
            "Hrips использует системное автозаполнение Android: Google, Bitwarden, 1Password и другие менеджеры. " +
                "Свои пароли браузер не хранит. В приватных вкладках автозаполнение выключено.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
        )
        Spacer(Modifier.height(8.dp))
        Group(
            { s ->
                SettingsRow(
                    HripsIcons.Key, "Сервис автозаполнения", "Выбрать менеджер паролей в настройках Android", s,
                    onClick = { openAutofillSettings(context) },
                )
            },
        )
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
        add("Камера" to granted(Manifest.permission.CAMERA))
        add("Микрофон" to granted(Manifest.permission.RECORD_AUDIO))
        add("Местоположение" to (granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)))
        if (Build.VERSION.SDK_INT >= 33) add("Уведомления" to granted(Manifest.permission.POST_NOTIFICATIONS))
    }

    PageScaffold("Разрешения", onBack) {
        SectionTitle("Сайты")
        Group(
            { s ->
                SettingsRow(
                    HripsIcons.Shield, "Разрешения сайтов", "Что вы разрешили или запретили сайтам. Можно отозвать", s,
                    onClick = onSitePermissions,
                )
            },
        )

        SectionTitle("Права браузера в Android")
        GroupOf(system) { item, s ->
            val ok = item.second
            SettingsRow(
                null, item.first, null, s, onClick = { openApp() },
                trailing = {
                    Text(
                        if (ok) "Разрешено" else "Не разрешено",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                },
            )
        }
        Text(
            "Без системного права сайт не сможет получить доступ, даже если вы разрешили его здесь. Нажмите на пункт, чтобы изменить.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun DesktopPage(browser: Browser, onBack: () -> Unit) {
    val sites = browser.store.desktopSites.toList()
    PageScaffold("Версия для ПК", onBack) {
        Text(
            "По умолчанию на всех сайтах открывается мобильная версия. Включить версию для ПК можно в меню страницы (⋮): она запомнится только для этого сайта.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
        SectionTitle("Сайты")
        if (sites.isEmpty()) {
            Text(
                "Пока нет ни одного сайта",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            GroupOf(sites) { host, s ->
                SettingsRow(
                    HripsIcons.Desktop, host, "Версия для ПК включена", s,
                    trailing = {
                        IconButton(onClick = { browser.setSiteDesktop(host, false) }) { Icon(HripsIcons.Trash, "Выключить") }
                    },
                )
            }
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = { browser.resetDesktopSites() }) { Text("Выключить на всех сайтах") }
        }
    }
}

@Composable
private fun MediaPage(store: Store, hover: HoverPreview, onBack: () -> Unit) {
    PageScaffold("Медиа", onBack) {
        Group(
            { s ->
                SwitchRow(
                    HripsIcons.Audio, "Управление в уведомлении",
                    "Пауза, перемотка и треки в шторке и на экране блокировки. Звук продолжает играть при выключенном экране",
                    store.mediaControls, true, s,
                ) { store.updateMediaControls(it) }
            },
            { s ->
                SwitchRow(
                    HripsIcons.Video, "Картинка в картинке",
                    "Видео на весь экран уходит в маленькое окно, когда вы выходите на главный экран",
                    store.pipEnabled, true, s,
                ) { store.updatePip(it) }
            },
            { s ->
                SwitchRow(
                    HripsIcons.Video, "Оживление миниатюр",
                    "Удерживайте палец на миниатюре видео: сайт запустит предпросмотр, как при наведении мыши. Работает там, где он есть у самого сайта",
                    hover.enabled, hover.extension != null, s,
                ) { hover.setHoverEnabled(it) }
            },
        )
    }
}

@Composable
private fun PerformancePage(browser: Browser, onBack: () -> Unit) {
    val store = browser.store
    PageScaffold("Производительность", onBack) {
        Group(
            { shape ->
                SwitchRow(
                    HripsIcons.Speed,
                    "Экономия памяти",
                    "При сильной нехватке памяти фоновые вкладки временно выгружаются. При возврате страница восстанавливается.",
                    store.suspendTabsOnMemoryPressure,
                    true,
                    shape,
                ) { store.updateSuspendTabsOnMemoryPressure(it) }
            },
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Включение может приводить к повторной загрузке тяжёлых страниц. Текущая вкладка и активное воспроизведение не выгружаются.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

@Composable
private fun DownloadsPage(browser: Browser, onBack: () -> Unit, onOpenDownloads: () -> Unit) {
    val store = browser.store
    val downloads = browser.downloads
    val hasFinished = downloads.items.any { !downloads.isActive(it) }
    PageScaffold("Загрузки", onBack) {
        Group(
            { s ->
                SwitchRow(
                    HripsIcons.Download, "Спрашивать перед загрузкой",
                    "Показывать имя файла и размер, чтобы подтвердить или отменить",
                    store.askBeforeDownload, true, s,
                ) { store.updateAskBeforeDownload(it) }
            },
        )
        SectionTitle("Файлы")
        Group(
            { s -> SettingsRow(HripsIcons.File, "Список загрузок", "Скачанные файлы и текущие загрузки", s, onClick = onOpenDownloads) },
            { s ->
                SettingsRow(
                    HripsIcons.Trash, "Очистить список", "Сами файлы останутся на устройстве", s,
                    enabled = hasFinished, onClick = { downloads.clearFinished() },
                )
            },
            { s -> SettingsRow(HripsIcons.Folder, "Папка", "Файлы сохраняются в «Загрузки» на устройстве", s) },
        )
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
        Group(
            { s -> SettingsRow(HripsIcons.Info, "hrips", if (version.isBlank()) "Версия неизвестна" else "Версия $version", s) },
            { s -> SettingsRow(HripsIcons.Search, "Движок", if (gecko.isBlank()) "GeckoView" else "GeckoView $gecko", s) },
            { s ->
                SettingsRow(
                    HripsIcons.Block, "Блокировка рекламы", "uBlock Origin" + if (app.adBlock.extension == null) " (не загружена)" else "", s,
                )
            },
            { s -> SettingsRow(HripsIcons.Mask, "Android", "Версия ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT}", s) },
        )
        if (Updater.available) {
            Spacer(Modifier.height(16.dp))
            Group(
                { s -> UpdateRow(app, s) },
                { s ->
                    SwitchRow(
                        HripsIcons.Download, "Проверять автоматически", "Раз в сутки при запуске. Запрос идёт на github.com",
                        app.store.autoUpdate, true, s,
                    ) { app.store.updateAutoUpdate(it) }
                },
            )
            (Updater.state as? Updater.State.Available)?.info?.notes?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun UpdateRow(app: HripsApp, shape: Shape) {
    val context = LocalContext.current
    when (val st = Updater.state) {
        is Updater.State.Checking -> SettingsRow(HripsIcons.Download, "Обновления", "Проверяю…", shape)
        is Updater.State.Available -> SettingsRow(
            HripsIcons.Download, "Доступна версия ${st.info.versionName}",
            "Нажмите, чтобы скачать" + if (st.info.size > 0) " (${st.info.size / 1_000_000} МБ)" else "", shape,
            onClick = { Updater.download(app, st.info) },
        )
        is Updater.State.Downloading -> SettingsRow(HripsIcons.Download, "Скачиваю ${st.info.versionName}", "${st.percent}%", shape)
        is Updater.State.Ready -> SettingsRow(
            HripsIcons.Download, "Установить ${st.info.versionName}", "Файл загружен и проверен. Нажмите, чтобы установить", shape,
            onClick = { Updater.install(context) },
        )
        is Updater.State.UpToDate -> SettingsRow(
            HripsIcons.Download, "Установлена последняя версия", "Нажмите, чтобы проверить ещё раз", shape,
            onClick = { Updater.check(app, manual = true) },
        )
        is Updater.State.Failed -> SettingsRow(
            HripsIcons.Download, st.message.ifBlank { "Не удалось проверить обновления" }, "Нажмите, чтобы повторить", shape,
            onClick = { Updater.check(app, manual = true) },
        )
        else -> SettingsRow(
            HripsIcons.Download, "Проверить обновления", null, shape,
            onClick = { Updater.check(app, manual = true) },
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Общие элементы
// ---------------------------------------------------------------------------------------------

/** Страница с большим сворачивающимся заголовком и кнопкой "назад", как у загрузок. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PageScaffold(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            // Страница лежит поверх GeckoView: без этого нажатия по пустым местам уходили бы в сайт под ней
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            LargeTopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(HripsIcons.Back, "Назад") } },
                scrollBehavior = scroll,
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun SearchBox(query: String, onChange: (String) -> Unit) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().height(56.dp),
    ) {
        Row(
            Modifier.padding(start = 20.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(HripsIcons.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text("Поиск настроек", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                BasicTextField(
                    value = query,
                    onValueChange = onChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) {
                IconButton(onClick = { onChange("") }) { Icon(HripsIcons.Close, "Очистить") }
            }
        }
    }
}

@Composable
internal fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 20.dp, bottom = 8.dp),
    )
}

private fun segment(i: Int, n: Int): Shape {
    val big = 24.dp
    val small = 6.dp
    val top = if (i == 0) big else small
    val bottom = if (i == n - 1) big else small
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

/** То же для списка данных: карточка на каждый элемент. */
@Composable
internal fun <T> GroupOf(items: List<T>, row: @Composable (T, Shape) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items.forEachIndexed { i, item -> row(item, segment(i, items.size)) }
    }
}

/** Группа карточек: между ними маленький зазор, скругление зависит от положения в группе. */
@Composable
internal fun Group(vararg rows: @Composable (Shape) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        rows.forEachIndexed { i, row -> row(segment(i, rows.size)) }
    }
}

@Composable
private fun IconTile(icon: ImageVector) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(44.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
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
) {
    val content: @Composable () -> Unit = {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (icon != null) IconTile(icon)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            trailing?.invoke()
        }
    }
    val modifier = Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.5f)
    if (onClick != null) {
        Surface(
            onClick = onClick, enabled = enabled, shape = shape,
            color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier, content = content,
        )
    } else {
        Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier, content = content)
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
            Switch(
                checked = checked,
                onCheckedChange = null,
                enabled = enabled,
                thumbContent = if (checked) {
                    { Icon(HripsIcons.Check, null, Modifier.size(SwitchDefaults.IconSize)) }
                } else null,
            )
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
