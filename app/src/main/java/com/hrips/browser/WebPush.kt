package com.hrips.browser

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.WebPushDelegate
import org.mozilla.geckoview.WebPushSubscription
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.UnifiedPush
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

private const val TAG = "WebPush"
private const val SUBSCRIBE_TIMEOUT_MS = 30_000L

/** Чистая логика scope из GeckoView: "https://site/path/^origin-атрибуты". Без Android, покрыта тестами. */
internal object WebPushScope {
    /** Имя регистрации UnifiedPush для scope: стабильное, без спецсимволов. */
    fun instanceOf(scope: String): String = "wp-" + sha1Hex(scope).take(32)

    /** Приватные вкладки push не получают: подписка пережила бы закрытие приватной вкладки. */
    fun isPrivate(scope: String): Boolean = scope.substringAfter('^', "").split('&').any { it == "privateBrowsingId=1" }

    fun host(scope: String): String =
        runCatching { java.net.URI(scope.substringBefore('^')).host }.getOrNull()?.removePrefix("www.").orEmpty().ifBlank { "сайт" }

    fun isWebScope(scope: String): Boolean = scope.startsWith("https://") || scope.startsWith("http://localhost") || scope.startsWith("http://127.0.0.1")
}

/**
 * Веб-push (Push API). Доставка двумя способами:
 *  - по умолчанию через сервисы Google Play (FCM): встроенный дистрибьютор UnifiedPush общается с Google Play напрямую,
 *    Firebase-проект и сторонние приложения не нужны;
 *  - через приложение-дистрибьютор UnifiedPush (ntfy, Sunup и др.), если пользователь выбрал его сам.
 * Сервер сайта шлёт зашифрованное сообщение (RFC 8291), библиотека connector его расшифровывает, а движок будит
 * service worker сайта, который и показывает уведомление через [SiteNotifications]. Google видит только шифротекст.
 *
 * Подписки хранятся в отдельном файле настроек: instance → scope, ключ сервера сайта, адрес и ключи шифрования.
 */
class WebPush(private val app: HripsApp) {
    private val prefs = app.getSharedPreferences("webpush", Context.MODE_PRIVATE)
    private val main = MainThread.handler
    private val pending = HashMap<String, GeckoResult<WebPushSubscription>>()

    private data class Record(
        val scope: String,
        val appServerKey: String?,
        val endpoint: String?,
        val pubKey: String?,
        val auth: String?,
    ) {
        fun toJson(): String = JSONObject()
            .put("scope", scope)
            .put("key", appServerKey ?: "")
            .put("endpoint", endpoint ?: "")
            .put("p256dh", pubKey ?: "")
            .put("auth", auth ?: "")
            .toString()

        val ready: Boolean get() = !endpoint.isNullOrBlank() && !pubKey.isNullOrBlank() && !auth.isNullOrBlank()

        fun toSubscription(): WebPushSubscription? = if (!ready) null else runCatching {
            WebPushSubscription(scope, endpoint!!, appServerKey?.let(::decode), decode(pubKey!!), decode(auth!!))
        }.getOrNull()

        companion object {
            fun parse(raw: String?): Record? = raw?.let {
                runCatching {
                    val o = JSONObject(it)
                    Record(
                        o.getString("scope"),
                        o.optString("key").ifBlank { null },
                        o.optString("endpoint").ifBlank { null },
                        o.optString("p256dh").ifBlank { null },
                        o.optString("auth").ifBlank { null },
                    )
                }.getOrNull()
            }
        }
    }

    val enabled: Boolean get() = app.store.webPush

    /** Пакеты дистрибьюторов UnifiedPush, включая встроенный FCM (наш собственный пакет). */
    fun distributors(): List<String> = runCatching { UnifiedPush.getDistributors(app) }.getOrDefault(emptyList())

    /** Сторонние приложения-дистрибьюторы (ntfy, Sunup и др.). */
    fun externalDistributors(): List<String> = distributors().filter { it != app.packageName }

    /** Есть ли на устройстве сервисы Google Play, через которые работает встроенный FCM. */
    fun hasGoogleServices(): Boolean = runCatching {
        app.packageManager.getApplicationInfo(GMS, 0).enabled
    }.getOrDefault(false)

    /** Выбранный дистрибьютор (пакет) или null. */
    fun distributor(): String? = runCatching { UnifiedPush.getAckDistributor(app) ?: UnifiedPush.getSavedDistributor(app) }.getOrNull()

    fun usesGoogle(): Boolean = distributor() == app.packageName

    fun labelOf(pkg: String): String =
        if (pkg == app.packageName) "Google (FCM)"
        else runCatching {
            val pm = app.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)

    fun distributorLabel(): String? = distributor()?.let(::labelOf)

    fun subscriptionCount(): Int = prefs.all.size

    /** Можно ли сейчас доставлять push хоть как-то. */
    fun available(): Boolean = hasGoogleServices() || externalDistributors().isNotEmpty()

    /**
     * Выбирает дистрибьютора, если он не выбран или пропал: сначала то, что предпочёл пользователь
     * ([Store.pushVia]), затем Google (FCM), затем любое приложение UnifiedPush.
     */
    private fun ensureDistributor(): Boolean {
        val current = distributor()
        if (current != null && (current != app.packageName || hasGoogleServices()) && current in distributors()) return true
        val external = externalDistributors()
        val pick = when {
            app.store.pushVia == PUSH_VIA_APP && external.isNotEmpty() -> external.first()
            hasGoogleServices() -> app.packageName
            external.isNotEmpty() -> external.first()
            else -> null
        } ?: return false
        runCatching { UnifiedPush.saveDistributor(app, pick) }.onFailure { return false }
        AppLog.i(TAG) { "дистрибьютор: ${labelOf(pick)}" }
        return true
    }

    /** Включение push. Возвращает false, если доставлять нечем (нет Google Play и приложений UnifiedPush). */
    fun enable(): Boolean {
        app.store.updateWebPush(true)
        return ensureDistributor()
    }

    /** Смена способа доставки: старые адреса подписок перестают работать, поэтому подписки снимаются (сайты узнают). */
    fun select(pkg: String) {
        if (pkg == distributor()) return
        clearSubscriptions()
        runCatching { UnifiedPush.saveDistributor(app, pkg) }.onFailure { AppLog.w(TAG, "не удалось выбрать дистрибьютора", it) }
        app.store.updatePushVia(if (pkg == app.packageName) PUSH_VIA_GOOGLE else PUSH_VIA_APP)
    }

    /** Выключение: все подписки сайтов снимаются, сайты получают pushsubscriptionchange. */
    fun disable() {
        app.store.updateWebPush(false)
        clearSubscriptions()
        runCatching { UnifiedPush.removeDistributor(app) }
    }

    /** Снимает все подписки (при выключении push и при удалении данных сайтов). */
    fun clearSubscriptions() {
        val all = prefs.all.keys.toList()
        if (all.isEmpty() && pending.isEmpty()) return
        all.forEach { instance ->
            val r = load(instance)
            runCatching { UnifiedPush.unregister(app, instance) }
            prefs.edit().remove(instance).apply()
            if (r?.ready == true) notifyChanged(r.scope)
        }
        pending.keys.toList().forEach { fail(it, "подписки удалены") }
        AppLog.i(TAG) { "подписки сняты: ${all.size}" }
    }

    /**
     * При каждом запуске заново регистрирует подписки, как советует UnifiedPush: так приходят новые адреса,
     * если дистрибьютор их сменил. Если дистрибьютор удалили, подписки снимаются (сайты узнают об этом).
     */
    fun refresh() {
        if (!enabled) return
        val all = prefs.all.keys.toList()
        if (all.isEmpty()) return
        val current = distributor()
        if (current == null || current !in distributors() || (current == app.packageName && !hasGoogleServices())) {
            AppLog.w(TAG, "дистрибьютор push пропал, подписки сняты")
            clearSubscriptions()
            runCatching { UnifiedPush.removeDistributor(app) }
            return
        }
        all.forEach { instance ->
            val r = load(instance) ?: return@forEach
            if (!r.ready) {
                prefs.edit().remove(instance).apply()
                return@forEach
            }
            runCatching { UnifiedPush.register(app, instance, "Уведомления ${WebPushScope.host(r.scope)}", r.appServerKey) }
                .onFailure { AppLog.w(TAG, "повторная регистрация не удалась", it) }
        }
    }

    val delegate = object : WebPushDelegate {
        override fun onSubscribe(scope: String, appServerKey: ByteArray?): GeckoResult<WebPushSubscription>? =
            subscribe(scope, appServerKey)

        override fun onGetSubscription(scope: String): GeckoResult<WebPushSubscription>? =
            GeckoResult.fromValue(if (enabled) load(WebPushScope.instanceOf(scope))?.toSubscription() else null)

        override fun onUnsubscribe(scope: String): GeckoResult<Void>? {
            unsubscribe(scope)
            return GeckoResult.fromValue(null)
        }
    }

    private fun subscribe(scope: String, key: ByteArray?): GeckoResult<WebPushSubscription> {
        if (!enabled || !app.store.siteNotifications || WebPushScope.isPrivate(scope) || !WebPushScope.isWebScope(scope) || !ensureDistributor()) {
            AppLog.i(TAG) { "подписка отклонена: включено=$enabled, уведомления=${app.store.siteNotifications}, дистрибьютор=${distributor() != null}" }
            return GeckoResult.fromValue(null)
        }
        val instance = WebPushScope.instanceOf(scope)
        val keyText = key?.let(::encode)
        load(instance)?.let { r ->
            if (r.ready && r.appServerKey == keyText) return GeckoResult.fromValue(r.toSubscription())
        }
        pending[instance]?.let { return it }
        val result = GeckoResult<WebPushSubscription>()
        pending[instance] = result
        save(instance, Record(scope, keyText, null, null, null))
        try {
            UnifiedPush.register(app, instance, "Уведомления ${WebPushScope.host(scope)}", keyText)
        } catch (e: Exception) {
            AppLog.w(TAG, "регистрация не началась", e)
            fail(instance, e.message ?: "ошибка регистрации")
            return result
        }
        main.postDelayed({ if (pending[instance] === result) fail(instance, "дистрибьютор не ответил") }, SUBSCRIBE_TIMEOUT_MS)
        return result
    }

    private fun unsubscribe(scope: String) {
        val instance = WebPushScope.instanceOf(scope)
        prefs.edit().remove(instance).apply()
        fail(instance, "подписка отменена")
        runCatching { UnifiedPush.unregister(app, instance) }
    }

    internal fun onNewEndpoint(instance: String, url: String, pubKey: String?, auth: String?) {
        val r = load(instance)
        if (r == null) {
            runCatching { UnifiedPush.unregister(app, instance) }
            return
        }
        if (pubKey.isNullOrBlank() || auth.isNullOrBlank()) {
            AppLog.w(TAG, "дистрибьютор не выдал ключи шифрования, сообщения нельзя будет расшифровать")
            prefs.edit().remove(instance).apply()
            fail(instance, "нет ключей")
            return
        }
        val updated = r.copy(endpoint = url, pubKey = pubKey, auth = auth)
        save(instance, updated)
        val waiting = pending.remove(instance)
        if (waiting != null) {
            AppLog.i(TAG) { "подписка готова: ${WebPushScope.host(r.scope)}" }
            waiting.complete(updated.toSubscription())
        } else if (r.ready && (r.endpoint != url || r.pubKey != pubKey)) {
            AppLog.i(TAG) { "адрес подписки сменился: ${WebPushScope.host(r.scope)}" }
            notifyChanged(r.scope)
        }
    }

    internal fun onMessage(instance: String, content: ByteArray, decrypted: Boolean) {
        val r = load(instance) ?: return
        if (!enabled || !app.store.siteNotifications) return
        if (!decrypted) {
            AppLog.w(TAG, "сообщение для ${WebPushScope.host(r.scope)} не расшифровано, пропущено")
            return
        }
        AppLog.i(TAG) { "push для ${WebPushScope.host(r.scope)}, ${content.size} байт" }
        runCatching {
            val controller = app.runtime.webPushController
            if (content.isEmpty()) controller.onPushEvent(r.scope) else controller.onPushEvent(r.scope, content)
        }.onFailure { AppLog.w(TAG, "движок не принял push", it) }
    }

    internal fun onRegistrationFailed(instance: String, reason: String) {
        AppLog.w(TAG, "регистрация не удалась: $reason")
        val r = load(instance)
        if (r != null && !r.ready) prefs.edit().remove(instance).apply()
        fail(instance, reason)
    }

    internal fun onUnregistered(instance: String) {
        val r = load(instance)
        prefs.edit().remove(instance).apply()
        fail(instance, "дистрибьютор снял подписку")
        if (r?.ready == true) notifyChanged(r.scope)
    }

    private fun notifyChanged(scope: String) {
        runCatching { app.runtime.webPushController.onSubscriptionChanged(scope) }
    }

    private fun fail(instance: String, reason: String) {
        pending.remove(instance)?.completeExceptionally(IllegalStateException("Web Push: $reason"))
    }

    private fun load(instance: String): Record? = Record.parse(prefs.getString(instance, null))

    private fun save(instance: String, r: Record) = prefs.edit().putString(instance, r.toJson()).apply()

    private companion object {
        private const val GMS = "com.google.android.gms"
        private const val FLAGS = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, FLAGS)
        fun decode(text: String): ByteArray = Base64.decode(text, Base64.URL_SAFE or Base64.NO_WRAP)
    }
}

/** Сюда библиотека UnifiedPush доставляет адреса подписок и сообщения (в процессе приложения). */
class HripsPushService : PushService() {
    private val webPush get() = (application as HripsApp).webPush

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        val url = endpoint.url
        val keys = endpoint.pubKeySet
        MainThread.run { webPush.onNewEndpoint(instance, url, keys?.pubKey, keys?.auth) }
    }

    override fun onMessage(message: PushMessage, instance: String) {
        val content = message.content
        val decrypted = message.decrypted
        MainThread.run { webPush.onMessage(instance, content, decrypted) }
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        MainThread.run { webPush.onRegistrationFailed(instance, reason.name) }
    }

    override fun onUnregistered(instance: String) {
        MainThread.run { webPush.onUnregistered(instance) }
    }
}
