package com.hrips.browser

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.Executors
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.mozilla.geckoview.Autocomplete
import org.mozilla.geckoview.GeckoResult

enum class PasswordMode(val id: String, val title: String) {
    BUILTIN("builtin", "Hrips"),
    SYSTEM("system", "Системный менеджер"),
    OFF("off", "Выключен");

    companion object {
        fun of(id: String?): PasswordMode = entries.firstOrNull { it.id == id } ?: BUILTIN
    }
}

class PasswordVault(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("hrips_passwords", Context.MODE_PRIVATE)
    private val file = File(app.filesDir, FILE_NAME)
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "hrips-vault").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
    }
    private val writeLock = Any()

    val logins = mutableStateListOf<SavedLogin>()

    val neverSave = mutableStateListOf<String>().apply { addAll(prefs.getStringSet(PREF_NEVER, emptySet()).orEmpty().sorted()) }

    var mode by mutableStateOf(PasswordMode.of(prefs.getString(PREF_MODE, null)))
        private set

    var offerSave by mutableStateOf(prefs.getBoolean(PREF_OFFER_SAVE, true))
        private set

    var autofill by mutableStateOf(prefs.getBoolean(PREF_AUTOFILL, true))
        private set

    var requireAuth by mutableStateOf(prefs.getBoolean(PREF_REQUIRE_AUTH, true))
        private set

    var onSettingsChanged: (() -> Unit)? = null

    @Volatile private var loaded = false
    @Volatile private var needsQuarantine = false
    @Volatile private var retryAfter = 0L

    val isBuiltin: Boolean get() = mode == PasswordMode.BUILTIN

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

    fun save(inc: IncomingLogin): SavedLogin {
        ensureLoaded()
        val r = upsertLogin(logins.toList(), inc, System.currentTimeMillis()) { UUID.randomUUID().toString() }
        replaceAll(r.list)
        persist()
        return r.saved
    }

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
        Snapshot.withMutableSnapshot {
            logins.clear()
            logins.addAll(list)
        }
    }

    fun ensureLoaded() {
        if (loaded) return
        if (SystemClock.elapsedRealtime() < retryAfter) return
        synchronized(this) {
            if (loaded) return
            val list = readFile()
            if (list == null) {
                retryAfter = SystemClock.elapsedRealtime() + RETRY_COOLDOWN_MS
                return
            }
            replaceAll(list)
            loaded = true
        }
    }

    private fun persist() {
        if (!loaded) {
            Notices.show("Пароли пока недоступны, изменения не сохранены")
            return
        }
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

    private fun isPermanent(e: Throwable?): Boolean =
        e is AEADBadTagException || e is KeyPermanentlyInvalidatedException ||
            e is IllegalArgumentException || e is JSONException

    private fun readFile(): List<SavedLogin>? {
        if (!file.exists()) return emptyList()
        val attempts = if (Looper.myLooper() == Looper.getMainLooper()) 1 else MAX_READ_ATTEMPTS
        var failure: Exception? = null
        for (attempt in 0 until attempts) {
            try {
                return fromJson(String(decrypt(file.readBytes()), StandardCharsets.UTF_8))
            } catch (e: Exception) {
                failure = e
                if (isPermanent(e)) break
                if (attempt < attempts - 1) Thread.sleep(RETRY_DELAY_MS * (attempt + 1))
            }
        }
        if (isPermanent(failure)) {
            AppLog.e(TAG, "Хранилище паролей повреждено или ключ не подходит", failure)
            needsQuarantine = true
            Notices.show("Не удалось расшифровать сохранённые пароли")
            return emptyList()
        }
        AppLog.e(TAG, "Хранилище паролей временно недоступно", failure)
        Notices.show("Хранилище паролей временно недоступно, повторю позже")
        return null
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

    private fun toEntry(l: SavedLogin): Autocomplete.LoginEntry {
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
        const val MAX_READ_ATTEMPTS = 4
        const val RETRY_DELAY_MS = 250L
        const val RETRY_COOLDOWN_MS = 30_000L
        const val FORMAT_VERSION: Byte = 1
        const val PREF_MODE = "mode"
        const val PREF_OFFER_SAVE = "offer_save"
        const val PREF_AUTOFILL = "autofill"
        const val PREF_REQUIRE_AUTH = "require_auth"
        const val PREF_NEVER = "never"
    }
}
