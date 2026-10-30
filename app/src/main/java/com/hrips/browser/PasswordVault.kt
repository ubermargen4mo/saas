package com.hrips.browser

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.Autocomplete
import org.mozilla.geckoview.GeckoResult

/** Кто занимается паролями: сам Hrips, системное автозаполнение Android (Google, Bitwarden...) или никто. */
enum class PasswordMode(val id: String, val title: String) {
    BUILTIN("builtin", "Hrips"),
    SYSTEM("system", "Системный менеджер"),
    OFF("off", "Выключен");

    companion object {
        fun of(id: String?): PasswordMode = entries.firstOrNull { it.id == id } ?: BUILTIN
    }
}

/**
 * Собственный менеджер паролей Hrips.
 *
 * Записи лежат в `files/logins.bin`, зашифрованные AES-256-GCM. Ключ создаётся в Android Keystore (в аппаратном
 * хранилище, где оно есть) и не покидает его, поэтому файл бесполезен без этого телефона (`allowBackup=false`,
 * в резервные копии он не попадает). Запись идёт во временный файл с атомарной заменой, так что обрыв посреди записи
 * не портит прежний файл.
 *
 * Списки и настройки меняются только на главном потоке (как и вызовы GeckoView с `@UiThread`), на диск пишет
 * отдельный поток. Чтение с диска ленивое и безопасно из любого потока: [ensureLoaded].
 *
 * Движку пароли отдаёт [delegate] (`Autocomplete.StorageDelegate`): запросы записей для автозаполнения, сохранение
 * подтверждённого пользователем и учёт использования. Окна «Сохранить пароль?» и «Выбрать логин» показывает
 * [Prompts] (onLoginSave / onLoginSelect).
 */
class PasswordVault(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("hrips_passwords", Context.MODE_PRIVATE)
    private val file = File(app.filesDir, FILE_NAME)
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "hrips-vault").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
    }
    private val writeLock = Any()

    /** Все записи. Читать можно откуда угодно, менять только на главном потоке. */
    val logins = mutableStateListOf<SavedLogin>()

    /** Сайты (хосты без «www.»), для которых пользователь выбрал «Никогда не сохранять». */
    val neverSave = mutableStateListOf<String>().apply { addAll(prefs.getStringSet(PREF_NEVER, emptySet()).orEmpty().sorted()) }

    var mode by mutableStateOf(PasswordMode.of(prefs.getString(PREF_MODE, null)))
        private set

    /** Предлагать сохранить пароль после входа на сайте. */
    var offerSave by mutableStateOf(prefs.getBoolean(PREF_OFFER_SAVE, true))
        private set

    /** Подставлять пароль при открытии страницы и предлагать записи при фокусе на поле логина. */
    var autofill by mutableStateOf(prefs.getBoolean(PREF_AUTOFILL, true))
        private set

    /** Просить PIN, рисунок или отпечаток, чтобы показать, скопировать или выгрузить пароли. */
    var requireAuth by mutableStateOf(prefs.getBoolean(PREF_REQUIRE_AUTH, true))
        private set

    /** Вызывается после смены режима и автозаполнения: приложение применяет их к движку. */
    var onSettingsChanged: (() -> Unit)? = null

    @Volatile private var loaded = false
    @Volatile private var needsQuarantine = false

    val isBuiltin: Boolean get() = mode == PasswordMode.BUILTIN

    // ---- Настройки ---------------------------------------------------------------------------------------------------

    fun updateMode(value: PasswordMode) {
        mode = value
        prefs.edit().putString(PREF_MODE, value.id).apply()
        onSettingsChanged?.invoke()
    }

    fun updateOfferSave(on: Boolean) {
        offerSave = on
        prefs.edit().putBoolean(PREF_OFFER_SAVE, on).apply()
    }

    fun updateAutofill(on: Boolean) {
        autofill = on
        prefs.edit().putBoolean(PREF_AUTOFILL, on).apply()
        onSettingsChanged?.invoke()
    }

    fun updateRequireAuth(on: Boolean) {
        requireAuth = on
        prefs.edit().putBoolean(PREF_REQUIRE_AUTH, on).apply()
    }

    fun isNever(host: String): Boolean = host.lowercase().removePrefix("www.") in neverSave

    fun addNever(host: String) {
        val h = host.lowercase().removePrefix("www.").trim()
        if (h.isEmpty() || h in neverSave) return
        val sorted = (neverSave.toList() + h).sorted()
        neverSave.clear()
        neverSave.addAll(sorted)
        saveNever()
    }

    fun removeNever(host: String) {
        if (neverSave.remove(host)) saveNever()
    }

    private fun saveNever() {
        prefs.edit().putStringSet(PREF_NEVER, neverSave.toSet()).apply()
    }

    // ---- Записи ------------------------------------------------------------------------------------------------------

    /** Сохранение от движка или из формы: новая запись или обновление пароля существующей. */
    fun save(inc: IncomingLogin): SavedLogin {
        ensureLoaded()
        val r = upsertLogin(logins.toList(), inc, System.currentTimeMillis()) { UUID.randomUUID().toString() }
        replaceAll(r.list)
        persist()
        return r.saved
    }

    /** Правка из экрана паролей. [origin] уже приведён к виду `https://хост`. */
    fun edit(id: String, origin: String, username: String, password: String) {
        ensureLoaded()
        val i = logins.indexOfFirst { it.id == id }
        if (i < 0) return
        val old = logins[i]
        val changed = old.password != password || old.username != username || old.origin != origin
        logins[i] = old.copy(
            origin = origin,
            username = username,
            password = password,
            modified = if (changed) System.currentTimeMillis() else old.modified,
        )
        persist()
    }

    fun delete(id: String) {
        ensureLoaded()
        if (logins.removeAll { it.id == id }) persist()
    }

    fun deleteAll() {
        ensureLoaded()
        logins.clear()
        persist()
    }

    fun find(origin: String, username: String, httpRealm: String? = null): SavedLogin? {
        ensureLoaded()
        return logins.firstOrNull { sameAccount(it, origin, username, httpRealm) }
    }

    fun byId(id: String?): SavedLogin? = if (id == null) null else logins.firstOrNull { it.id == id }

    /** Запись использована для автозаполнения: запоминаем время и число раз. */
    fun markUsed(id: String?) {
        if (id == null) return
        ensureLoaded()
        val i = logins.indexOfFirst { it.id == id }
        if (i < 0) return
        logins[i] = logins[i].copy(lastUsed = System.currentTimeMillis(), timesUsed = logins[i].timesUsed + 1)
        persist()
    }

    fun importRows(rows: List<CsvLogin>): CsvImport {
        ensureLoaded()
        val r = PasswordCsv.merge(logins.toList(), rows, System.currentTimeMillis()) { UUID.randomUUID().toString() }
        if (r.added > 0) {
            replaceAll(r.list)
            persist()
        }
        return r
    }

    fun exportCsv(): String {
        ensureLoaded()
        return PasswordCsv.format(logins.toList())
    }

    private fun replaceAll(list: List<SavedLogin>) {
        logins.clear()
        logins.addAll(list)
    }

    // ---- Диск --------------------------------------------------------------------------------------------------------

    /** Читает и расшифровывает файл, если ещё не читали. Можно звать с любого потока. */
    fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val read = readFile()
            logins.clear()
            logins.addAll(read)
            loaded = true
        }
    }

    private fun persist() {
        val snapshot = logins.toList()
        io.execute {
            synchronized(writeLock) {
                try {
                    writeFile(snapshot)
                } catch (e: Exception) {
                    AppLog.e(TAG, "Не удалось записать хранилище паролей", e)
                    Notices.show("Не удалось сохранить пароли на устройстве")
                }
            }
        }
    }

    private fun readFile(): List<SavedLogin> {
        if (!file.exists()) return emptyList()
        var failure: Exception? = null
        // Один повтор: Keystore сразу после запуска иногда отвечает ошибкой, а со второго раза работает.
        repeat(2) { attempt ->
            try {
                val plain = decrypt(file.readBytes())
                return fromJson(String(plain, StandardCharsets.UTF_8))
            } catch (e: Exception) {
                failure = e
                if (attempt == 0) Thread.sleep(300)
            }
        }
        AppLog.e(TAG, "Не удалось расшифровать хранилище паролей", failure)
        // Старый файл не удаляем и не затираем: перед первой новой записью он будет переименован и останется на диске.
        needsQuarantine = true
        Notices.show("Не удалось расшифровать сохранённые пароли")
        return emptyList()
    }

    private fun writeFile(list: List<SavedLogin>) {
        if (needsQuarantine) {
            if (file.exists()) {
                val moved = File(file.parentFile, "$FILE_NAME.unreadable-${System.currentTimeMillis()}")
                if (file.renameTo(moved)) AppLog.w(TAG, "Нечитаемый файл паролей сохранён как ${moved.name}")
            }
            needsQuarantine = false
        }
        val data = encrypt(toJson(list).toByteArray(StandardCharsets.UTF_8))
        val tmp = File(file.parentFile, "$FILE_NAME.tmp")
        FileOutputStream(tmp).use {
            it.write(data)
            it.fd.sync()
        }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun toJson(list: List<SavedLogin>): String {
        val arr = JSONArray()
        for (l in list) {
            arr.put(
                JSONObject()
                    .put("id", l.id)
                    .put("origin", l.origin)
                    .put("action", l.formActionOrigin ?: JSONObject.NULL)
                    .put("realm", l.httpRealm ?: JSONObject.NULL)
                    .put("user", l.username)
                    .put("pass", l.password)
                    .put("created", l.created)
                    .put("modified", l.modified)
                    .put("used", l.lastUsed)
                    .put("times", l.timesUsed)
            )
        }
        return JSONObject().put("v", 1).put("logins", arr).toString()
    }

    private fun fromJson(text: String): List<SavedLogin> {
        val arr = JSONObject(text).optJSONArray("logins") ?: return emptyList()
        val out = ArrayList<SavedLogin>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id", "").ifEmpty { UUID.randomUUID().toString() }
            val origin = o.optString("origin", "")
            if (origin.isEmpty()) continue
            out.add(
                SavedLogin(
                    id = id,
                    origin = origin,
                    formActionOrigin = if (o.isNull("action")) null else o.getString("action"),
                    httpRealm = if (o.isNull("realm")) null else o.getString("realm"),
                    username = o.optString("user", ""),
                    password = o.optString("pass", ""),
                    created = o.optLong("created", 0L),
                    modified = o.optLong("modified", 0L),
                    lastUsed = o.optLong("used", 0L),
                    timesUsed = o.optInt("times", 0),
                )
            )
        }
        return out
    }

    // ---- Шифрование --------------------------------------------------------------------------------------------------

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    /** Формат файла: байт версии, 12 байт IV, шифртекст с тегом GCM. */
    private fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        check(iv.size == IV_BYTES) { "Неожиданная длина IV: ${iv.size}" }
        val body = cipher.doFinal(plain)
        return ByteArray(1 + IV_BYTES + body.size).also {
            it[0] = FORMAT_VERSION
            System.arraycopy(iv, 0, it, 1, IV_BYTES)
            System.arraycopy(body, 0, it, 1 + IV_BYTES, body.size)
        }
    }

    private fun decrypt(data: ByteArray): ByteArray {
        require(data.size > 1 + IV_BYTES + 16 && data[0] == FORMAT_VERSION) { "Неизвестный формат файла паролей" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, data, 1, IV_BYTES))
        return cipher.doFinal(data, 1 + IV_BYTES, data.size - 1 - IV_BYTES)
    }

    // ---- Движок ------------------------------------------------------------------------------------------------------

    private fun toEntry(l: SavedLogin): Autocomplete.LoginEntry {
        // Gecko требует ровно одно из двух: у формы formActionOrigin (пустая строка = «любая форма сайта», так
        // получают записи из CSV), у HTTP-авторизации httpRealm.
        val realm = l.httpRealm
        return Autocomplete.LoginEntry.Builder()
            .guid(l.id)
            .origin(l.origin)
            .formActionOrigin(if (realm != null) null else (l.formActionOrigin ?: ""))
            .httpRealm(realm)
            .username(l.username)
            .password(l.password)
            .build()
    }

    private fun fetch(domain: String?): Array<Autocomplete.LoginEntry> {
        if (mode != PasswordMode.BUILTIN) return emptyArray()
        ensureLoaded()
        val src = if (domain == null) logins.toList() else logins.filter { originMatchesDomain(it.origin, domain) }
        return Array(src.size) { toEntry(src[it]) }
    }

    /** Подтверждённое пользователем в окне «Сохранить пароль?» (или изменённое движком) попадает сюда. */
    private fun saveFromGecko(login: Autocomplete.LoginEntry) {
        if (mode != PasswordMode.BUILTIN) return
        val origin = normalizeOrigin(login.origin) ?: login.origin.takeIf { it.isNotBlank() } ?: return
        save(
            IncomingLogin(
                guid = login.guid,
                origin = origin,
                formActionOrigin = login.formActionOrigin,
                httpRealm = login.httpRealm,
                username = login.username,
                password = login.password,
            )
        )
    }

    val delegate = object : Autocomplete.StorageDelegate {
        override fun onLoginFetch(domain: String): GeckoResult<Array<Autocomplete.LoginEntry>>? =
            GeckoResult.fromValue(fetch(domain))

        override fun onLoginFetch(): GeckoResult<Array<Autocomplete.LoginEntry>>? =
            GeckoResult.fromValue(fetch(null))

        override fun onLoginSave(login: Autocomplete.LoginEntry) {
            saveFromGecko(login)
        }

        override fun onLoginUsed(login: Autocomplete.LoginEntry, usedFields: Int) {
            markUsed(login.guid)
        }
    }

    private companion object {
        const val TAG = "Passwords"
        const val FILE_NAME = "logins.bin"
        const val KEY_ALIAS = "hrips_logins_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val FORMAT_VERSION: Byte = 1
        const val PREF_MODE = "mode"
        const val PREF_OFFER_SAVE = "offer_save"
        const val PREF_AUTOFILL = "autofill"
        const val PREF_REQUIRE_AUTH = "require_auth"
        const val PREF_NEVER = "never"
    }
}
