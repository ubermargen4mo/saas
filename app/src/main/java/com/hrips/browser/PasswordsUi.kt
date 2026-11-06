@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.hrips.browser

import android.app.Activity
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PersistableBundle
import android.os.SystemClock
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Surface
import kotlinx.coroutines.flow.first
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.mozilla.geckoview.GeckoSession

private const val AUTH_GRACE_MS = 90_000L

private class DeviceAuthGate(private val context: Context, private val enabled: () -> Boolean) {
    var launch: ((Intent) -> Unit)? = null
    private var unlockedUntil = 0L
    private var pending: (() -> Unit)? = null

    fun require(title: String, onGranted: () -> Unit) {
        val km = context.getSystemService(KeyguardManager::class.java)
        if (!enabled() || km == null || !km.isDeviceSecure || SystemClock.elapsedRealtime() < unlockedUntil) {
            onGranted()
            return
        }
        val intent = km.createConfirmDeviceCredentialIntent(title, "Подтвердите, что это вы")
        if (intent == null) {
            onGranted()
            return
        }
        val start = launch
        if (start == null) {
            Notices.show("Не удалось запросить блокировку устройства")
            return
        }
        pending = onGranted
        try {
            start(intent)
        } catch (e: Exception) {
            pending = null
            AppLog.w("Passwords", "окно блокировки устройства не открылось", e)
            Notices.show("Не удалось запросить блокировку устройства")
        }
    }

    fun onResult(ok: Boolean) {
        val action = pending
        pending = null
        if (ok) {
            unlockedUntil = SystemClock.elapsedRealtime() + AUTH_GRACE_MS
            action?.invoke()
        } else {
            Notices.show("Доступ не подтверждён")
        }
    }

    fun lock() {
        unlockedUntil = 0L
    }
}

@Composable
private fun rememberAuthGate(vault: PasswordVault): DeviceAuthGate {
    val context = LocalContext.current
    val gate = remember { DeviceAuthGate(context) { vault.requireAuth } }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        gate.onResult(result.resultCode == Activity.RESULT_OK)
    }
    gate.launch = { intent -> launcher.launch(intent) }
    DisposableEffect(gate) { onDispose { gate.lock() } }
    return gate
}

private const val CLIPBOARD_CLEAR_MS = 60_000L

private fun copySecret(context: Context, text: String, what: String, sensitive: Boolean = true) {
    val cm = context.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText("", text)
    if (sensitive) {
        clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    }
    try {
        cm.setPrimaryClip(clip)
    } catch (e: Exception) {
        Notices.show("Не удалось скопировать")
        return
    }
    Notices.show("$what скопирован")
    if (sensitive) {
        MainThread.handler.postDelayed({
            runCatching {
                val current = cm.primaryClip?.getItemAt(0)?.text?.toString()
                if (current == text) {
                    cm.clearPrimaryClip()
                }
            }
        }, CLIPBOARD_CLEAR_MS)
    }
}

@Composable
private fun SiteAvatar(origin: String, size: Dp) {
    val cs = MaterialTheme.colorScheme
    Favicon(url = origin, size = size, shape = CircleShape) {
        Box(Modifier.size(size).clip(CircleShape).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
            Text(
                initial(displayHost(origin)),
                style = MaterialTheme.typography.titleMedium,
                color = cs.onPrimaryContainer,
            )
        }
    }
}

private fun initial(s: String) = s.firstOrNull()?.uppercaseChar()?.toString() ?: "?"

private fun passwordsCount(n: Int) = ruPlural(n, "пароль", "пароля", "паролей")

@Composable
private fun NeverSaveDialog(vault: PasswordVault, onDismiss: () -> Unit) {
    HripsDialog(
        onDismissRequest = onDismiss,
        icon = HripsIcons.Block,
        title = { Text("Не сохранять для сайтов") },
        text = {
            if (vault.neverSave.isEmpty()) {
                Text("Список пуст. Сайт попадает сюда, если в окне «Сохранить пароль?» выбрать «Никогда для этого сайта».")
            } else {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    vault.neverSave.toList().forEach { host ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(host, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            IconButton(onClick = { vault.removeNever(host) }) { Icon(HripsIcons.Close, "Убрать из списка") }
                        }
                    }
                }
            }
        },
        confirmButton = { HripsTextButton(onClick = onDismiss) { Text("Готово") } },
    )
}

@Composable
private fun HeroButton(label: String, onClick: () -> Unit) {
    Button(shapes = ButtonDefaults.shapes(), onClick = onClick, modifier = Modifier.fillMaxWidth().height(48.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
internal fun PasswordsSettingsPage(app: HripsApp, onOpenPasswords: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val vault = app.passwords
    val mode = vault.mode
    val secure = remember { context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true }
    var showNever by remember { mutableStateOf(false) }

    PageScaffold("Пароли и автозаполнение", onBack) {
        when (mode) {
            PasswordMode.BUILTIN -> HeroBanner(
                title = "Пароли Hrips",
                subtitle = if (vault.logins.isEmpty()) "Пока ничего не сохранено" else "Сохранено: " + passwordsCount(vault.logins.size),
                icon = HripsIcons.PasswordKey,
                badge = Badge.CLOVER,
                tone = 3,
                action = { HeroButton("Открыть сохранённые пароли", onOpenPasswords) },
            )
            PasswordMode.SYSTEM -> HeroBanner(
                title = "Системное автозаполнение",
                subtitle = "Hrips использует автозаполнение Android: Google, Bitwarden, 1Password и другие менеджеры.",
                icon = HripsIcons.PasswordKey,
                badge = Badge.CLOVER,
                tone = 3,
                action = { HeroButton("Выбрать менеджер паролей") { openAutofillSettings(context) } },
            )
            PasswordMode.OFF -> HeroBanner(
                title = "Менеджер паролей выключен",
                subtitle = "Hrips не сохраняет и не подставляет пароли.",
                icon = HripsIcons.Lock,
                badge = Badge.CLOVER,
                tone = 3,
            )
        }

        SectionTitle("Менеджер")
        Group(
            { s ->
                DropdownRow(
                    title = "Менеджер паролей",
                    subtitle = "Кто сохраняет и подставляет пароли",
                    value = mode.title,
                    options = PasswordMode.entries.map { it.id to it.title },
                    selectedId = mode.id,
                    onSelect = { vault.updateMode(PasswordMode.of(it)) },
                    shape = s,
                )
            },
        )

        if (mode == PasswordMode.BUILTIN) {
            SectionTitle("Сохранение и вход")
            Group(
                { s ->
                    PanelSwitch(
                        "Предлагать сохранять пароли", "После входа на сайте спросим, сохранить ли пароль. В приватных вкладках не предлагаем",
                        vault.offerSave, { vault.updateOfferSave(it) }, shape = s,
                    )
                },
                { s ->
                    PanelSwitch(
                        "Автозаполнение", "Подставлять логин и пароль на сайтах, где они сохранены",
                        vault.autofill, { vault.updateAutofill(it) }, shape = s,
                    )
                },
                { s ->
                    PanelItem(
                        "Не сохранять для сайтов", shape = s,
                        subtitle = "Сайты, для которых выбрано «Никогда»",
                        value = vault.neverSave.size.toString(),
                        onClick = { showNever = true },
                    )
                },
            )

            SectionTitle("Защита")
            Group(
                { s ->
                    PanelSwitch(
                        "Подтверждать блокировкой экрана", "PIN, рисунок или отпечаток нужны, чтобы показать, скопировать, изменить или выгрузить пароли",
                        vault.requireAuth, { vault.updateRequireAuth(it) }, shape = s, enabled = secure,
                    )
                },
            )
            if (!secure) {
                Callout("На устройстве не настроена блокировка экрана, поэтому подтверждать нечем. Включите её в настройках Android.", Modifier.padding(top = 8.dp), icon = HripsIcons.Alert)
            }
            Callout(
                "Пароли хранятся только на этом устройстве в зашифрованном виде (ключ в Android Keystore) и никуда не отправляются.",
                Modifier.padding(top = 12.dp),
                icon = HripsIcons.Lock,
            )
        } else if (mode == PasswordMode.SYSTEM) {
            Callout("В приватных вкладках автозаполнение выключено.", Modifier.padding(top = 12.dp), icon = HripsIcons.Lock)
        }
    }

    if (showNever) NeverSaveDialog(vault) { showNever = false }
}

private sealed interface PwView {
    data object Home : PwView
    class Detail(val id: String) : PwView
    class Form(val id: String?) : PwView
}

private sealed interface PwDialog {
    class Delete(val id: String) : PwDialog
    data object DeleteAll : PwDialog
    data object ExportWarning : PwDialog
    data object ImportInfo : PwDialog
    data object Never : PwDialog
}

private const val MAX_IMPORT_BYTES = 16 * 1024 * 1024

internal fun readImportText(context: Context, uri: Uri): String {
    val input = context.contentResolver.openInputStream(uri) ?: error("файл не открывается")
    input.use { s ->
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = s.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > MAX_IMPORT_BYTES) error("файл слишком большой")
        }
        return out.toString("UTF-8")
    }
}

private fun exportTo(context: Context, uri: Uri, text: String, count: Int) {
    AppExecutors.startThread("hrips-pw-export", onError = { Notices.show("Не удалось записать файл") }) {
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: error("файл не открывается")
        out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        Notices.show("Выгружено паролей: $count", NoticeKind.SUCCESS)
    }
}

private fun importFrom(context: Context, uri: Uri, vault: PasswordVault) {
    AppExecutors.startThread("hrips-pw-import", onError = { Notices.show("Не удалось прочитать файл") }) {
        val parsed = PasswordCsv.parse(readImportText(context, uri))
        MainThread.handler.post {
            when {
                parsed.error != null -> Notices.show("Не удалось импортировать: ${parsed.error}")
                parsed.rows.isEmpty() -> Notices.show("Не удалось найти пароли в файле")
                else -> {
                    val r = vault.importRows(parsed.rows)
                    val skipped = r.duplicates + parsed.skipped
                    val tail = if (skipped > 0) ". Пропущено: $skipped (уже есть или нечитаемые строки)" else ""
                    Notices.show("Добавлено паролей: ${r.added}$tail", NoticeKind.SUCCESS)
                }
            }
        }
    }
}

@Composable
fun PasswordsScreen(vault: PasswordVault, onOpenSite: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val gate = rememberAuthGate(vault)
    var view by remember { mutableStateOf<PwView>(PwView.Home) }
    var dialog by remember { mutableStateOf<PwDialog?>(null) }

    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { vault.ensureLoaded() } }

    BackHandler {
        when (val v = view) {
            is PwView.Home -> onBack()
            is PwView.Form -> view = if (v.id != null && vault.byId(v.id) != null) PwView.Detail(v.id) else PwView.Home
            is PwView.Detail -> view = PwView.Home
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) exportTo(context, uri, vault.exportCsv(), vault.logins.size)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importFrom(context, uri, vault)
    }

    when (val v = view) {
        is PwView.Home -> PasswordsHome(
            vault = vault,
            onBack = onBack,
            onOpen = { view = PwView.Detail(it) },
            onAdd = { view = PwView.Form(null) },
            onImport = { dialog = PwDialog.ImportInfo },
            onExport = { dialog = PwDialog.ExportWarning },
            onNever = { dialog = PwDialog.Never },
            onDeleteAll = { dialog = PwDialog.DeleteAll },
        )
        is PwView.Detail -> PasswordDetail(
            vault = vault,
            id = v.id,
            gate = gate,
            onBack = { view = PwView.Home },
            onEdit = { view = PwView.Form(v.id) },
            onDelete = { dialog = PwDialog.Delete(v.id) },
            onOpenSite = onOpenSite,
        )
        is PwView.Form -> PasswordForm(
            vault = vault,
            existing = vault.byId(v.id),
            onClose = { savedId -> view = if (savedId != null) PwView.Detail(savedId) else if (v.id != null && vault.byId(v.id) != null) PwView.Detail(v.id) else PwView.Home },
        )
    }

    when (val d = dialog) {
        null -> {}
        is PwDialog.Delete -> {
            val l = vault.byId(d.id)
            PwConfirmDialog(
                icon = HripsIcons.Trash,
                title = "Удалить пароль?",
                text = if (l != null) "Запись для ${displayHost(l.origin)}${if (l.username.isNotEmpty()) " (${l.username})" else ""} будет удалена с этого устройства." else "Запись будет удалена.",
                confirmLabel = "Удалить",
                onDismiss = { dialog = null },
            ) {
                dialog = null
                vault.delete(d.id)
                view = PwView.Home
                Notices.show("Пароль удалён")
            }
        }
        PwDialog.DeleteAll -> PwConfirmDialog(
            icon = HripsIcons.Trash,
            title = "Удалить все пароли?",
            text = "Будет удалено: ${passwordsCount(vault.logins.size)}. Это нельзя отменить. Если нужна копия, сначала выгрузите их в файл.",
            confirmLabel = "Удалить всё",
            onDismiss = { dialog = null },
        ) {
            dialog = null
            gate.require("Удаление паролей") {
                vault.deleteAll()
                Notices.show("Все пароли удалены")
            }
        }
        PwDialog.ExportWarning -> PwConfirmDialog(
            icon = HripsIcons.Share,
            title = "Выгрузить пароли в файл?",
            text = "Пароли запишутся в обычный CSV-файл без шифрования: любой, кто получит файл, увидит их все. Храните его в надёжном месте и удалите после использования.",
            confirmLabel = "Продолжить",
            onDismiss = { dialog = null },
        ) {
            dialog = null
            gate.require("Выгрузка паролей") { exportLauncher.launch("hrips-passwords.csv") }
        }
        PwDialog.ImportInfo -> PwConfirmDialog(
            icon = HripsIcons.Download,
            title = "Импорт из CSV",
            text = "Выберите CSV-файл, выгруженный из Chrome, Firefox, Edge, Bitwarden и других менеджеров. Уже сохранённые записи не перезаписываются. После импорта удалите файл: пароли в нём не защищены.",
            confirmLabel = "Выбрать файл",
            onDismiss = { dialog = null },
        ) {
            dialog = null
            try {
                importLauncher.launch(arrayOf("*/*"))
            } catch (e: Exception) {
                Notices.show("Не удалось открыть выбор файла")
            }
        }
        PwDialog.Never -> NeverSaveDialog(vault) { dialog = null }
    }
}

@Composable
private fun PwConfirmDialog(
    icon: ImageVector,
    title: String,
    text: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    HripsDialog(
        icon = icon,
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { HripsTextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { HripsTextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun PasswordsHome(
    vault: PasswordVault,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onAdd: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onNever: () -> Unit,
    onDeleteAll: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    val q = query.trim()
    val all = vault.logins
    val rows = all
        .filter { q.isEmpty() || displayHost(it.origin).contains(q, true) || it.username.contains(q, true) }
        .sortedWith(compareBy({ displayHost(it.origin) }, { it.username.lowercase() }))

    ListPage(
        title = "Пароли",
        onBack = onBack,
        actions = {
            IconButton(onClick = onAdd) { Icon(HripsIcons.Add, "Добавить пароль") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(HripsIcons.MoreVert, "Ещё") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, shape = HripsShapes.ML) {
                    PwMenuItem("Импорт из CSV", HripsIcons.Download) { menu = false; onImport() }
                    PwMenuItem("Экспорт в CSV", HripsIcons.Share, all.isNotEmpty()) { menu = false; onExport() }
                    PwMenuItem("Не сохранять для сайтов", HripsIcons.Block) { menu = false; onNever() }
                    PwMenuItem("Удалить все", HripsIcons.Trash, all.isNotEmpty()) { menu = false; onDeleteAll() }
                }
            }
        },
        header = if (all.isEmpty()) null else ({
            Box(Modifier.widthIn(max = HripsLayout.PageMaxWidth).padding(bottom = 8.dp)) {
                PageSearchField("Поиск паролей", query) { query = it }
            }
        }),
    ) {
        if (all.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    HripsIcons.PasswordKey,
                    "Нет сохранённых паролей",
                    "Когда вы войдёте на сайте, Hrips предложит сохранить пароль. Можно добавить его вручную или импортировать из файла другого браузера.",
                    Modifier.padding(top = 40.dp),
                )
            }
        } else if (rows.isEmpty()) {
            item(key = "none") {
                Text(
                    "Ничего не найдено",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
                )
            }
        }
        itemsIndexed(rows, key = { _, l -> l.id }) { i, l ->
            PasswordRow(l, segShape(i, rows.size)) { onOpen(l.id) }
        }
    }
}

@Composable
private fun PwMenuItem(text: String, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text) },
        leadingIcon = { Icon(icon, null) },
        enabled = enabled,
        onClick = onClick,
    )
}

@Composable
private fun PasswordRow(l: SavedLogin, shape: Shape, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    PressSurface(
        onClick = onClick,
        corners = shape.asCorners(),
        color = cs.surfaceContainerHigh,
        contentColor = cs.onSurface,
        pressedCorner = 20.dp,
        pressScale = 0.985f,
        modifier = Modifier.widthIn(max = HripsLayout.PageMaxWidth).fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SiteAvatar(l.origin, 40.dp)
            Column(Modifier.weight(1f)) {
                Text(displayHost(l.origin), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    l.username.ifEmpty { "Без логина" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun PasswordDetail(
    vault: PasswordVault,
    id: String,
    gate: DeviceAuthGate,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onOpenSite: (String) -> Unit,
) {
    val context = LocalContext.current
    val login = vault.byId(id)
    var revealed by remember(id) { mutableStateOf(false) }
    LaunchedEffect(revealed) {
        if (revealed) {
            delay(30_000)
            revealed = false
        }
    }
    if (login == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val cs = MaterialTheme.colorScheme

    ListPage(
        title = displayHost(login.origin),
        onBack = onBack,
        actions = {
            IconButton(onClick = { gate.require("Изменение пароля") { onEdit() } }) { Icon(HripsIcons.Pencil, "Изменить") }
            IconButton(onClick = onDelete) { Icon(HripsIcons.Trash, "Удалить") }
        },
    ) {
        item(key = "head") {
            Column(
                Modifier.widthIn(max = HripsLayout.PageMaxWidth).fillMaxWidth().padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                SiteAvatar(login.origin, 72.dp)
                Spacer(Modifier.height(12.dp))
                Text(displayHost(login.origin), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        item(key = "fields") {
            Column(Modifier.widthIn(max = HripsLayout.PageMaxWidth).fillMaxWidth()) {
                Group(
                    { s ->
                        PanelItem(
                            "Сайт", shape = s, subtitle = login.origin,
                            onClick = { onOpenSite(login.origin) },
                            trailing = { Icon(HripsIcons.Open, "Открыть сайт", tint = cs.onSurfaceVariant) },
                        )
                    },
                    { s ->
                        PanelItem(
                            "Логин", shape = s, subtitle = login.username.ifEmpty { "Не указан" },
                            trailing = {
                                if (login.username.isNotEmpty()) {
                                    IconButton(onClick = { copySecret(context, login.username, "Логин", sensitive = false) }) {
                                        Icon(HripsIcons.Copy, "Скопировать логин")
                                    }
                                }
                            },
                        )
                    },
                    { s ->
                        PanelItem(
                            "Пароль", shape = s,
                            subtitle = if (revealed) login.password else "••••••••••",
                            trailing = {
                                Row {
                                    ShowToggle(revealed) {
                                        if (revealed) revealed = false else gate.require("Показать пароль") { revealed = true }
                                    }
                                    IconButton(onClick = {
                                        gate.require("Копирование пароля") { copySecret(context, login.password, "Пароль") }
                                    }) { Icon(HripsIcons.Copy, "Скопировать пароль") }
                                }
                            },
                        )
                    },
                )
            }
        }
        item(key = "meta") {
            val changed = DateUtils.formatDateTime(
                context, login.modified,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_SHOW_TIME,
            )
            val used = if (login.timesUsed > 0) " · подставлен: " + ruPlural(login.timesUsed, "раз", "раза", "раз") else ""
            Text(
                "Изменён: $changed$used",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                modifier = Modifier.widthIn(max = HripsLayout.PageMaxWidth).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun ShowToggle(shown: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(if (shown) HripsIcons.EyeOff else HripsIcons.Eye, if (shown) "Скрыть пароль" else "Показать пароль")
    }
}

@Composable
private fun PasswordField(value: String, onValueChange: (String) -> Unit, show: Boolean, onShowChange: (Boolean) -> Unit, extraAction: @Composable () -> Unit = {}) {
    HripsField(
        value, onValueChange,
        label = { Text("Пароль") },
        singleLine = true,
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            Row {
                extraAction()
                IconButton(onClick = { onShowChange(!show) }) {
                    Icon(if (show) HripsIcons.EyeOff else HripsIcons.Eye, if (show) "Скрыть пароль" else "Показать пароль")
                }
            }
        },
    )
}

@Composable
private fun PasswordForm(vault: PasswordVault, existing: SavedLogin?, onClose: (savedId: String?) -> Unit) {
    var site by remember(existing?.id) { mutableStateOf(existing?.origin ?: "") }
    var user by remember(existing?.id) { mutableStateOf(existing?.username ?: "") }
    var pass by remember(existing?.id) { mutableStateOf(existing?.password ?: "") }
    var show by remember(existing?.id) { mutableStateOf(false) }
    val origin = normalizeOrigin(site)
    val canSave = origin != null && pass.isNotEmpty()

    ListPage(
        title = if (existing == null) "Новый пароль" else "Изменить пароль",
        onBack = { onClose(null) },
        listModifier = Modifier.imePadding(),
    ) {
        item(key = "form") {
            Column(
                Modifier.widthIn(max = HripsLayout.PageMaxWidth).fillMaxWidth().padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                HripsField(
                    site, { site = it },
                    label = { Text("Сайт") },
                    placeholder = { Text("example.com") },
                    singleLine = true,
                    isError = site.isNotBlank() && origin == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                HripsField(user, { user = it }, label = { Text("Логин") }, singleLine = true)
                PasswordField(pass, { pass = it }, show, { show = it }) {
                    IconButton(onClick = { pass = generatePassword(); show = true }) { Icon(HripsIcons.Sparkles, "Придумать пароль") }
                }
                Button(
                    shapes = ButtonDefaults.shapes(),
                    enabled = canSave,
                    onClick = {
                        val o = origin
                        if (o != null) {
                            if (existing == null) {
                                val saved = vault.save(IncomingLogin(null, o, null, null, user.trim(), pass))
                                Notices.show("Пароль сохранён", NoticeKind.SUCCESS)
                                onClose(saved.id)
                            } else {
                                vault.edit(existing.id, o, user.trim(), pass)
                                Notices.show("Пароль изменён", NoticeKind.SUCCESS)
                                onClose(existing.id)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text("Сохранить", style = MaterialTheme.typography.titleMedium) }
            }
        }
    }
}

@Composable
internal fun LoginSaveDialog(req: PromptRequest.LoginSave, prompts: Prompts) {
    var user by remember(req) { mutableStateOf(req.username) }
    var pass by remember(req) { mutableStateOf(req.password) }
    var show by remember(req) { mutableStateOf(false) }
    HripsDialog(
        icon = HripsIcons.PasswordKey,
        onDismissRequest = { prompts.resolve(req) { req.onAnswer(LoginSaveAnswer.Cancel) } },
        title = { Text(if (req.update) "Обновить пароль?" else "Сохранить пароль?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(req.host, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                if (req.generated) {
                    Text("Этот пароль придуман автоматически. Сохраните его, иначе позже не войдёте.", style = MaterialTheme.typography.bodyMedium)
                }
                HripsField(user, { user = it }, label = { Text("Логин") }, singleLine = true)
                PasswordField(pass, { pass = it }, show, { show = it })
                HripsTextButton(onClick = { prompts.resolve(req) { req.onAnswer(LoginSaveAnswer.Never) } }) {
                    Text("Никогда для этого сайта")
                }
            }
        },
        confirmButton = {
            HripsTextButton(
                enabled = pass.isNotEmpty(),
                onClick = { prompts.resolve(req) { req.onAnswer(LoginSaveAnswer.Save(user.trim(), pass)) } },
            ) { Text(if (req.update) "Обновить" else "Сохранить") }
        },
        dismissButton = { HripsTextButton(onClick = { prompts.resolve(req) { req.onAnswer(LoginSaveAnswer.Cancel) } }) { Text("Не сейчас") } },
    )
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun LoginSuggestBar(prompts: Prompts, current: GeckoSession?, modifier: Modifier = Modifier) {
    val req = prompts.loginSuggestFor(current) ?: return
    val cs = MaterialTheme.colorScheme
    val imeVisible = WindowInsets.isImeVisible
    val imeState = rememberUpdatedState(imeVisible)
    LaunchedEffect(req) {
        snapshotFlow { imeState.value }
            .let { flow ->
                withTimeoutOrNull(2000) { flow.first { it } }
                flow.first { !it }
            }
        prompts.resolveLogin(req) { req.onAnswer(null) }
    }
    if (!imeVisible) return
    Surface(
        modifier = modifier.fillMaxWidth().imePadding().padding(horizontal = 8.dp, vertical = 6.dp),
        shape = HripsShapes.L,
        color = cs.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
    ) {
        Row(Modifier.padding(start = 8.dp, end = 2.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(HripsIcons.PasswordKey, null, Modifier.padding(horizontal = 4.dp).size(20.dp), tint = cs.primary)
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                req.choices.forEachIndexed { i, c ->
                    Surface(
                        onClick = { prompts.resolveLogin(req) { req.onAnswer(i) } },
                        shape = CircleShape,
                        color = cs.primaryContainer,
                    ) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (c.generated) {
                                Icon(HripsIcons.Sparkles, null, Modifier.size(16.dp).padding(end = 0.dp), tint = cs.onPrimaryContainer)
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(
                                if (c.generated) "Надёжный пароль" else c.username.ifEmpty { "Без логина" },
                                style = MaterialTheme.typography.labelLarge,
                                color = cs.onPrimaryContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            IconButton(onClick = { prompts.resolveLogin(req) { req.onAnswer(null) } }) {
                Icon(HripsIcons.Close, "Скрыть подсказки", Modifier.size(20.dp), tint = cs.onSurfaceVariant)
            }
        }
    }
}
