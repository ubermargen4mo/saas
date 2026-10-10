package com.hrips.browser

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Обновление из GitHub Releases того же репозитория, где идёт сборка (адрес подставляет CI: BuildConfig.UPDATE_REPO).
 * Каждый push в main публикует релиз с тегом `build-<номер запуска>` и подписанным APK.
 * Проверка: последний релиз -> номер из тега + 100 сравнивается с versionCode установленной версии.
 * Скачанный файл проверяется (пакет, версия) и отдаётся системному установщику; подпись сверяет сам Android,
 * поэтому APK, подписанный другим ключом, не установится поверх.
 * Репозиторий должен быть публичным: без токена приватные релизы недоступны.
 */
object Updater {
    class Info(val versionName: String, val build: Int, val notes: String, val url: String, val size: Long)

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        class Available(val info: Info) : State
        class Downloading(val info: Info, val percent: Int) : State
        class Ready(val info: Info, val file: File) : State
        class Failed(val message: String) : State
    }

    var state: State by mutableStateOf(State.Idle)
        private set

    private val main = Handler(Looper.getMainLooper())
    private const val DAY = 24L * 3600 * 1000
    private const val APK_MIME = "application/vnd.android.package-archive"

    val repo: String get() = BuildConfig.UPDATE_REPO
    /** Без адреса репозитория (локальная сборка) обновления отключены. */
    val available: Boolean get() = repo.contains('/')

    private fun set(s: State) = main.post { state = s }
    private fun dir(context: Context) = File(context.cacheDir, "updates")

    private fun currentCode(context: Context): Long =
        PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))

    /** Раз в сутки при запуске, если включено в настройках. Если есть новая версия, показывает уведомление с кнопкой. */
    fun autoCheck(app: HripsApp) {
        if (!available || !app.store.autoUpdate) return
        if (System.currentTimeMillis() - app.store.lastUpdateCheck < DAY) return
        check(app, manual = false)
    }

    fun check(app: HripsApp, manual: Boolean) {
        if (!available) { if (manual) set(State.Failed("Эта сборка не знает, откуда обновляться")); return }
        val cur = state
        if (cur is State.Checking || cur is State.Downloading) return
        set(State.Checking)
        val ok = AppExecutors.tryExecute {
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
            }.onFailure { set(State.Failed(if (manual) "Не удалось проверить обновления" else "")) }
        }
        if (!ok) set(State.Idle)
    }

    /** null - обновлений нет. Бросает исключение при сетевой ошибке. */
    private fun fetchLatest(app: HripsApp): Info? {
        val bytes = Favicons.download("https://api.github.com/repos/$repo/releases/latest", 512 * 1024)
            ?: error("нет ответа")
        val json = JSONObject(String(bytes, Charsets.UTF_8))
        val build = buildFromTag(json.optString("tag_name")) ?: return null
        if (versionCodeOfBuild(build) <= currentCode(app)) return null
        val assets = json.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val url = a.optString("browser_download_url")
            if (a.optString("name").endsWith(".apk", true) && url.startsWith("https://")) {
                val name = json.optString("name").removePrefix("hrips").trim().ifBlank { "сборка $build" }
                return Info(name, build, json.optString("body").trim().take(1500), url, a.optLong("size", 0))
            }
        }
        return null
    }

    fun download(app: HripsApp, info: Info) {
        if (state is State.Downloading) return
        set(State.Downloading(info, 0))
        val ok = AppExecutors.tryExecute {
            try {
                val out = File(dir(app), "hrips-update.apk")
                val part = File(dir(app), "hrips-update.apk.part")
                dir(app).mkdirs()
                part.delete()
                fetchTo(info.url, part, info.size) { pct -> set(State.Downloading(info, pct)) }
                if (info.size > 0 && part.length() != info.size) error("размер не совпал")
                // Это точно наш пакет и версия новее? Подпись потом сверит сам Android при установке.
                val pi = app.packageManager.getPackageArchiveInfo(part.path, 0) ?: error("файл не APK")
                if (pi.packageName != app.packageName) error("чужой пакет")
                if (PackageInfoCompat.getLongVersionCode(pi) <= currentCode(app)) error("версия не новее")
                out.delete()
                if (!part.renameTo(out)) error("не удалось сохранить")
                set(State.Ready(info, out))
                main.post { Notices.show("Обновление загружено", "Установить") { install(app) } }
            } catch (e: Exception) {
                File(dir(app), "hrips-update.apk.part").delete()
                set(State.Failed("Не удалось скачать обновление"))
            }
        }
        if (!ok) set(State.Available(info))
    }

    private fun fetchTo(url: String, dest: File, expected: Long, progress: (Int) -> Unit) {
        var current = url
        repeat(6) {
            val c = URL(current).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 10_000
                c.readTimeout = 15_000
                c.instanceFollowRedirects = false
                c.setRequestProperty("User-Agent", "hrips-updater")
                val code = c.responseCode
                if (code in 301..308 && code != 304) {
                    val next = URL(URL(current), c.getHeaderField("Location") ?: error("redirect"))
                    if (!next.protocol.equals("https", true)) error("не https")
                    current = next.toString()
                    return@repeat
                }
                if (code !in 200..299) error("HTTP $code")
                val total = if (c.contentLengthLong > 0) c.contentLengthLong else expected
                var done = 0L
                var lastPct = -1
                c.inputStream.use { input ->
                    dest.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt().coerceIn(0, 100)
                                if (pct != lastPct) { lastPct = pct; progress(pct) }
                            }
                        }
                    }
                }
                return
            } finally {
                c.disconnect()
            }
        }
        error("слишком много перенаправлений")
    }

    /** Открывает системный установщик. Если установка из этого приложения не разрешена, ведёт на нужную страницу настроек. */
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

    /** Старый скачанный файл после успешного обновления больше не нужен. */
    fun cleanup(context: Context) {
        val f = File(dir(context), "hrips-update.apk")
        if (!f.exists()) return
        val pi = context.packageManager.getPackageArchiveInfo(f.path, 0)
        if (pi == null || PackageInfoCompat.getLongVersionCode(pi) <= currentCode(context)) {
            dir(context).listFiles()?.forEach { it.delete() }
        }
    }
}
