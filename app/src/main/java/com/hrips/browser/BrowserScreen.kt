package com.hrips.browser

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import mozilla.components.browser.state.action.CrashAction
import mozilla.components.browser.state.action.EngineAction
import mozilla.components.browser.state.selector.findTab
import mozilla.components.browser.state.selector.normalTabs
import mozilla.components.browser.state.selector.privateTabs
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.concept.engine.EngineView
import mozilla.components.lib.state.ext.observeAsComposableState

/** Снимок выбранной вкладки для интерфейса. */
internal data class TabUi(
    val id: String,
    val url: String,
    val title: String,
    val loading: Boolean,
    val progress: Int,
    val canGoBack: Boolean,
    val canGoForward: Boolean,
    val isPrivate: Boolean,
    val fullScreen: Boolean,
    val desktop: Boolean,
    val secure: Boolean,
    val parentId: String?,
)

internal fun TabSessionState.toUi() = TabUi(
    id = id,
    url = content.url,
    title = content.title,
    loading = content.loading,
    progress = content.progress,
    canGoBack = content.canGoBack,
    canGoForward = content.canGoForward,
    isPrivate = content.private,
    fullScreen = content.fullScreen,
    desktop = content.desktopMode,
    secure = content.securityInfo.isSecure,
    parentId = parentId,
)

internal fun TabUi.host(): String {
    if (url == Components.BLANK) return "Новая вкладка"
    val host = runCatching { Uri.parse(url).host }.getOrNull()
    return host?.removePrefix("www.") ?: url
}

private data class Counts(val normal: Int, val priv: Int)

private enum class Screen { Browse, Search, Tabs }

@Composable
fun BrowserScreen(activity: MainActivity, c: Components) {
    val store = c.store
    val tab by store.observeAsComposableState { s -> s.selectedTab?.toUi() }
    val counts by store.observeAsComposableState { s -> Counts(s.normalTabs.size, s.privateTabs.size) }
    var privateMode by rememberSaveable { mutableStateOf(false) }
    var screen by remember { mutableStateOf(Screen.Browse) }

    val current = tab
    LaunchedEffect(current?.id) {
        if (current != null) privateMode = current.isPrivate
    }

    val blank = current == null || current.url == Components.BLANK
    val fullscreen = current?.fullScreen == true
    FullscreenEffect(activity, fullscreen)

    BackHandler {
        when {
            screen != Screen.Browse -> screen = Screen.Browse
            activity.handlePromptBack() -> Unit
            fullscreen -> c.sessionUseCases.exitFullscreen()
            current == null -> activity.moveTaskToBack(true)
            current.canGoBack -> c.sessionUseCases.goBack()
            current.id in c.externalTabs -> activity.closeExternalTab(current.id)
            current.parentId != null && store.state.findTab(current.parentId) != null -> {
                val parent = current.parentId
                c.tabsUseCases.removeTab(current.id)
                c.tabsUseCases.selectTab(parent)
            }
            else -> activity.moveTaskToBack(true)
        }
    }

    HripsTheme(incognito = privateMode) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize()) {
                val insets = if (fullscreen) {
                    Modifier
                } else {
                    Modifier.windowInsetsPadding(
                        WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                    )
                }
                Column(Modifier.fillMaxSize().then(insets)) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        if (blank) {
                            StartPage(
                                privateMode = privateMode,
                                engineTitle = SearchEngines.current(LocalContext.current).title,
                                onSearch = { screen = Screen.Search },
                            )
                        } else {
                            EngineContent(activity, c)
                        }
                    }
                    if (!fullscreen) {
                        BottomBar(
                            c = c,
                            tab = current,
                            tabCount = if (privateMode) counts.priv else counts.normal,
                            privateMode = privateMode,
                            onSearch = { screen = Screen.Search },
                            onTabs = { screen = Screen.Tabs },
                            onNewTab = {
                                c.newTab(private = privateMode)
                                screen = Screen.Search
                            },
                        )
                    }
                }

                when (screen) {
                    Screen.Search -> SearchOverlay(
                        initial = if (blank) "" else current?.url.orEmpty(),
                        onClose = { screen = Screen.Browse },
                        onSubmit = { url ->
                            c.open(url, private = privateMode)
                            screen = Screen.Browse
                        },
                    )
                    Screen.Tabs -> TabSwitcher(
                        c = c,
                        privateMode = privateMode,
                        onPrivateMode = { privateMode = it },
                        onClose = { screen = Screen.Browse },
                        onNewTab = {
                            c.newTab(private = privateMode)
                            screen = Screen.Search
                        },
                    )
                    Screen.Browse -> Unit
                }
            }
        }
    }
}

@Composable
private fun FullscreenEffect(activity: Activity, fullscreen: Boolean) {
    val view = LocalView.current
    LaunchedEffect(fullscreen) {
        val controller = WindowCompat.getInsetsController(activity.window, view)
        if (fullscreen) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

/**
 * Отображение страницы. Движковое View создаётся один раз и привязывается к сессии выбранной вкладки
 * штатным способом Mozilla (EngineView.render). Приостановкой, активностью и поверхностью сессий
 * занимается GeckoEngineView — собственных обходных путей здесь нет.
 */
@Composable
private fun EngineContent(activity: MainActivity, c: Components) {
    val tab by c.store.observeAsComposableState(
        observe = { s ->
            listOf(
                s.selectedTabId,
                s.selectedTab?.engineState?.engineSession,
                s.selectedTab?.engineState?.crashed,
            )
        },
        map = { s -> s.selectedTab },
    )

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val engineView = c.engine.createView(ctx)
                engineView.setActivityContext(activity)
                engineView.asView()
            },
            update = { view ->
                val engineView = view as EngineView
                val selected = tab
                if (selected == null) {
                    engineView.release()
                } else {
                    val session = selected.engineState.engineSession
                    if (session == null) {
                        c.store.dispatch(EngineAction.CreateEngineSessionAction(selected.id))
                    } else {
                        engineView.render(session)
                    }
                }
            },
            onRelease = { view ->
                val engineView = view as EngineView
                engineView.setActivityContext(null)
                engineView.release()
            },
        )

        val crashed = tab
        if (crashed != null && crashed.engineState.crashed) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Страница перестала отвечать", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                    Text(
                        "Другие вкладки не пострадали.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
                    )
                    Button(onClick = { c.store.dispatch(CrashAction.RestoreCrashedSessionAction(crashed.id)) }) {
                        Text("Перезагрузить")
                    }
                }
            }
        }
    }
}

@Composable
private fun BottomBar(
    c: Components,
    tab: TabUi?,
    tabCount: Int,
    privateMode: Boolean,
    onSearch: () -> Unit,
    onTabs: () -> Unit,
    onNewTab: () -> Unit,
) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val loading = tab?.loading == true

    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.navigationBarsPadding()) {
            if (loading) {
                LinearProgressIndicator(
                    progress = { (tab?.progress ?: 0) / 100f },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                )
            } else {
                Box(Modifier.height(2.dp))
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BarIcon(HripsIcons.Back, "Назад", enabled = tab?.canGoBack == true) { c.sessionUseCases.goBack() }
                BarIcon(HripsIcons.Forward, "Вперёд", enabled = tab?.canGoForward == true) { c.sessionUseCases.goForward() }

                Surface(
                    modifier = Modifier.weight(1f).height(44.dp).padding(horizontal = 4.dp).clickable(onClick = onSearch),
                    shape = HripsShapes.L,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                ) {
                    Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        val blank = tab == null || tab.url == Components.BLANK
                        Icon(
                            imageVector = when {
                                privateMode -> HripsIcons.Mask
                                blank -> HripsIcons.Search
                                tab?.secure == true -> HripsIcons.Lock
                                else -> HripsIcons.Globe
                            },
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = if (blank) "Поиск или адрес" else tab?.host().orEmpty(),
                            modifier = Modifier.padding(start = 10.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (blank) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                TabCounter(tabCount, onTabs)

                Box {
                    BarIcon(HripsIcons.MoreVert, "Меню") { menu = true }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        MenuItem("Новая вкладка", HripsIcons.Add) { menu = false; onNewTab() }
                        MenuItem(
                            if (privateMode) "Обычная вкладка" else "Приватная вкладка",
                            HripsIcons.Mask,
                        ) {
                            menu = false
                            c.newTab(private = !privateMode)
                            onSearch()
                        }
                        if (tab != null && tab.url != Components.BLANK) {
                            MenuItem(
                                if (loading) "Остановить" else "Обновить",
                                if (loading) HripsIcons.BarClose else HripsIcons.BarRefresh,
                            ) {
                                menu = false
                                if (loading) c.sessionUseCases.stopLoading() else c.sessionUseCases.reload()
                            }
                            MenuItem(
                                if (tab.desktop) "Мобильная версия сайта" else "Версия для компьютера",
                                HripsIcons.Desktop,
                            ) {
                                menu = false
                                c.sessionUseCases.requestDesktopSite(!tab.desktop)
                            }
                            MenuItem("Поделиться", HripsIcons.Share) {
                                menu = false
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, tab.url)
                                }
                                context.startActivity(Intent.createChooser(send, null))
                            }
                            MenuItem("Скопировать ссылку", HripsIcons.Copy) {
                                menu = false
                                val clipboard = context.getSystemService(ClipboardManager::class.java)
                                clipboard?.setPrimaryClip(ClipData.newPlainText("url", tab.url))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuItem(text: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp)) },
        onClick = onClick,
    )
}

@Composable
private fun BarIcon(icon: ImageVector, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun TabCounter(count: Int, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.size(26.dp),
            shape = HripsShapes.XS,
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = if (count > 99) ":)" else count.toString(),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun SearchOverlay(initial: String, onClose: () -> Unit, onSubmit: (String) -> Unit) {
    val context = LocalContext.current
    var field by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    var engine by remember { mutableStateOf(SearchEngines.current(context)) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
            Column(Modifier.weight(1f).fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.Bottom) {
                val text = field.text.trim()
                if (text.isNotEmpty()) {
                    if (SearchEngines.looksLikeUrl(text)) {
                        SuggestionRow(HripsIcons.Globe, "Открыть $text") { onSubmit(SearchEngines.resolve(context, text)) }
                    }
                    SuggestionRow(HripsIcons.Search, "Искать «$text» — ${engine.title}") {
                        onSubmit(SearchEngines.searchUrl(context, text))
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SearchEngines.all.forEach { entry ->
                        FilterChip(
                            selected = entry.id == engine.id,
                            onClick = {
                                SearchEngines.select(context, entry)
                                engine = entry
                            },
                            label = { Text(entry.title) },
                        )
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = field,
                    onValueChange = { field = it },
                    modifier = Modifier.weight(1f).focusRequester(focus),
                    singleLine = true,
                    shape = HripsShapes.L,
                    placeholder = { Text("Поиск или адрес") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { onSubmit(SearchEngines.resolve(context, field.text)) }),
                )
                IconButton(onClick = onClose) {
                    Icon(HripsIcons.Close, contentDescription = "Закрыть")
                }
            }
        }
    }
}

@Composable
private fun SuggestionRow(icon: ImageVector, text: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text,
            modifier = Modifier.padding(start = 14.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
