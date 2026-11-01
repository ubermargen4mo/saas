package com.hrips.browser

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

object Updater {
    class Info(
        val versionName: String,
        val build: Int,
        val notes: String,
        val url: String,
        val size: Long,
        val sha256: String? = null,
    )

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        class Available(val info: Info) : State
        class Downloading(val info: Info, val percent: Int) : State
        class Ready(val info: Info, val file: File) : State
        class Failed(val message: String) : State
    }

    private class UpdateException(message: String) : IOException(message)

    var state: State by mutableStateOf(State.Idle)
        private set

    private val main = MainThread.handler
    private val busy = AtomicBoolean(false)
    private const val DAY = 24L * 3600 * 1000
    private const val APK_MIME = "application/vnd.android.package-archive"
    private const val APK_NAME = "hrips-update.apk"
    private const val PART_NAME = "hrips-update.apk.part"
    private const val TAG = "Updater"

    private const val SIGNING_FLAGS = PackageManager.GET_SIGNING_CERTIFICATES

    val repo: String get() = BuildConfig.UPDATE_REPO
    val available: Boolean get() = repo.contains('/')

    private fun set(s: State) = main.post { state = s }
    private fun dir(context: Context) = File(context.cacheDir, "updates")

    private fun currentCode(context: Context): Long =
        PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))

    private fun begin(started: State, rejected: State, task: () -> Unit) {
        if (!busy.compareAndSet(false, true)) return
        set(started)
        val queued = AppExecutors.tryExecute {
            try {
                task()
            } finally {
                busy.set(false)
            }
        }
        if (!queued) {
            busy.set(false)
            set(rejected)
        }
    }

    fun autoCheck(app: HripsApp) {
        if (!available || !app.store.autoUpdate) return
        if (System.currentTimeMillis() - app.store.lastUpdateCheck < DAY) return
        check(app, manual = false)
    }

    fun check(app: HripsApp, manual: Boolean) {
        if (!available) {
            if (manual) set(State.Failed("Эта сборка не знает, откуда обновляться"))
            return
        }
        begin(State.Checking, State.Idle) {
            val result = runCatching { fetchLatest(app) }
            app.store.markUpdateChecked()
            result.onSuccess { info ->
                if (info == null) {
                    set(State.UpToDate)
                } else {
                    set(State.Available(info))
                    if (!manual) main.post {
                        Notices.show("Доступна версия ${info.versionName}", "Обновить") { download(app, info) }
                    }
                }
            }.onFailure { e ->
                AppLog.w(TAG, "проверка обновлений не удалась", e)
                set(if (manual) State.Failed("Не удалось проверить обновления") else State.Idle)
            }
        }
    }

    private fun fetchLatest(app: HripsApp): Info? {
        val bytes = Favicons.download("https://api.github.com/repos/$repo/releases/latest", 512 * 1024)
            ?: throw IOException("нет ответа")
        val json = JSONObject(String(bytes, Charsets.UTF_8))
        val build = buildFromTag(json.optString("tag_name")) ?: return null
        if (versionCodeOfBuild(build) <= currentCode(app)) return null
        val assets = json.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val url = a.optString("browser_download_url")
            if (!a.optString("name").endsWith(".apk", true) || !url.startsWith("https://")) continue
            val name = json.optString("name").removePrefix("hrips").trim().ifBlank { "сборка $build" }
            val digest = a.optString("digest")
                .takeIf { it.startsWith("sha256:") }
                ?.removePrefix("sha256:")
                ?.takeIf { it.length == 64 }
            return Info(name, build, json.optString("body").trim().take(1500), url, a.optLong("size", 0), digest)
        }
        return null
    }

    fun download(app: HripsApp, info: Info) = begin(State.Downloading(info, 0), State.Available(info)) {
        val dir = dir(app).apply { mkdirs() }
        val part = File(dir, PART_NAME)
        val out = File(dir, APK_NAME)
        try {
            part.delete()
            val sha256 = fetchTo(info.url, part, info.size) { set(State.Downloading(info, it)) }
            verify(app, info, part, sha256)
            out.delete()
            if (!part.renameTo(out)) throw IOException("не удалось сохранить файл")
            set(State.Ready(info, out))
            main.post { Notices.show("Обновление загружено", "Установить") { install(app) } }
        } catch (e: Exception) {
            part.delete()
            AppLog.w(TAG, "загрузка обновления не удалась", e)
            set(State.Failed((e as? UpdateException)?.message ?: "Не удалось скачать обновление"))
        }
    }

    private fun fetchTo(url: String, dest: File, expected: Long, progress: (Int) -> Unit): String {
        val c = try {
            openFollowingRedirects(url) {
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("User-Agent", "hrips-updater")
            }
        } catch (e: InsecureRedirectException) {
            throw UpdateException("Обновление отдаётся не по https")
        }
        try {
            val total = c.contentLengthLong.takeIf { it > 0 } ?: expected
            val digest = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(64 * 1024)
            var done = 0L
            var lastPct = -1
            c.inputStream.use { input ->
                dest.outputStream().use { output ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt().coerceIn(0, 100)
                            if (pct != lastPct) {
                                lastPct = pct
                                progress(pct)
                            }
                        }
                    }
                }
            }
            if (total > 0 && done != total) throw UpdateException("Файл обновления скачался не полностью")
            return hex(digest.digest())
        } finally {
            c.disconnect()
        }
    }

    private fun verify(app: HripsApp, info: Info, file: File, sha256: String) {
        if (info.size > 0 && file.length() != info.size) throw UpdateException("Размер файла не совпал с ожидаемым")
        val expected = info.sha256
        if (expected != null && !expected.equals(sha256, ignoreCase = true)) throw UpdateException("Контрольная сумма не совпала")
        val pm = app.packageManager
        val offered = pm.getPackageArchiveInfo(file.path, SIGNING_FLAGS) ?: throw UpdateException("Файл не является APK")
        if (offered.packageName != app.packageName) throw UpdateException("Файл относится к другому приложению")
        if (PackageInfoCompat.getLongVersionCode(offered) <= currentCode(app)) throw UpdateException("Загруженная версия не новее установленной")
        val offeredKeys = signerKeys(offered)
        val installedKeys = signerKeys(pm.getPackageInfo(app.packageName, SIGNING_FLAGS))
        if (offeredKeys.isNotEmpty() && installedKeys.isNotEmpty() && offeredKeys.none { it in installedKeys }) {
            throw UpdateException("Обновление подписано другим ключом, Android его не установит")
        }
    }

    private fun signerKeys(pi: PackageInfo): Set<String> {
        val info = pi.signingInfo ?: return emptySet()
        val signatures = if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory
        return signatures.orEmpty().mapTo(HashSet()) { hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun install(context: Context) {
        val s = state as? State.Ready ?: return
        val app = context.applicationContext
        if (!app.packageManager.canRequestPackageInstalls()) {
            Notices.show("Разрешите установку из hrips и нажмите «Установить» ещё раз")
            runCatching {
                app.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            return
        }
        runCatching {
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", s.file)
            app.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, APK_MIME)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { Notices.show("Не удалось открыть установщик") }
    }

    fun cleanup(context: Context) {
        val f = File(dir(context), APK_NAME)
        if (!f.exists()) return
        val pi = context.packageManager.getPackageArchiveInfo(f.path, 0)
        if (pi == null || PackageInfoCompat.getLongVersionCode(pi) <= currentCode(context)) {
            dir(context).listFiles()?.forEach { it.delete() }
        }
    }
}
