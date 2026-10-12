package com.hrips.browser

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController

/** Запрос разрешений у пользователя: установка, обновление с новыми правами или запрос во время работы. */
class ExtPrompt(
    val extension: WebExtension,
    val kind: Kind,
    val permissions: List<String>,
    val origins: List<String>,
    val dataCollection: List<String>,
    /** allow - согласие на запрошенные права, privateAllowed - можно ли работать в приватных вкладках. */
    val answer: (allow: Boolean, privateAllowed: Boolean) -> Unit,
) {
    enum class Kind { INSTALL, UPDATE, OPTIONAL }
}

/** Открытое всплывающее окно расширения (по нажатию на его кнопку). */
class ExtPopup(val extension: WebExtension, val session: GeckoSession)

/**
 * Расширения как в Firefox для ПК: установка из каталога addons.mozilla.org, включение, права,
 * кнопки на панели и всплывающие окна. Всю работу делает движок, мы отвечаем за интерфейс.
 */
class Extensions(private val runtime: GeckoRuntime) {
    /** Все установленные расширения, включая встроенный uBlock Origin. */
    val installed = mutableStateListOf<WebExtension>()

    /** Кнопка расширения на панели (действие по умолчанию): id расширения -> действие. */
    val actions = mutableStateMapOf<String, WebExtension.Action>()

    var prompt by mutableStateOf<ExtPrompt?>(null)
        private set
    var popup by mutableStateOf<ExtPopup?>(null)
        private set

    /** Идентификаторы (guid) расширений, которые сейчас устанавливаются. */
    val installing = mutableStateListOf<String>()
    var error by mutableStateOf<String?>(null)

    /** Открывает вкладку для расширения (его страница настроек, tabs.create). Подключается в HripsApp. */
    var openTab: (url: String, active: Boolean, engineWillLoad: Boolean) -> GeckoSession? = { _, _, _ -> null }

    private val controller get() = runtime.webExtensionController
    private val main = Handler(Looper.getMainLooper())
    private var activeSession: GeckoSession? = null

    /** Номер последнего запроса списка: ответ более раннего запроса, пришедший позже, не должен затереть свежий. */
    private var refreshGen = 0

    fun init() {
        controller.setPromptDelegate(object : WebExtensionController.PromptDelegate {
            override fun onInstallPromptRequest(
                extension: WebExtension,
                permissions: Array<String>,
                origins: Array<String>,
                dataCollectionPermissions: Array<String>,
            ): GeckoResult<WebExtension.PermissionPromptResponse>? {
                val result = GeckoResult<WebExtension.PermissionPromptResponse>()
                ask(ExtPrompt(extension, ExtPrompt.Kind.INSTALL, permissions.toList(), origins.toList(), dataCollectionPermissions.toList()) { allow, priv ->
                    result.complete(WebExtension.PermissionPromptResponse(allow, priv, false))
                })
                return result
            }

            override fun onUpdatePrompt(
                extension: WebExtension,
                newPermissions: Array<String>,
                newOrigins: Array<String>,
                newDataCollectionPermissions: Array<String>,
            ): GeckoResult<AllowOrDeny>? {
                val result = GeckoResult<AllowOrDeny>()
                ask(ExtPrompt(extension, ExtPrompt.Kind.UPDATE, newPermissions.toList(), newOrigins.toList(), newDataCollectionPermissions.toList()) { allow, _ ->
                    result.complete(if (allow) AllowOrDeny.ALLOW else AllowOrDeny.DENY)
                })
                return result
            }

            override fun onOptionalPrompt(
                extension: WebExtension,
                permissions: Array<String>,
                origins: Array<String>,
                dataCollectionPermissions: Array<String>,
            ): GeckoResult<AllowOrDeny>? {
                val result = GeckoResult<AllowOrDeny>()
                ask(ExtPrompt(extension, ExtPrompt.Kind.OPTIONAL, permissions.toList(), origins.toList(), dataCollectionPermissions.toList()) { allow, _ ->
                    result.complete(if (allow) AllowOrDeny.ALLOW else AllowOrDeny.DENY)
                })
                return result
            }
        })
        controller.setAddonManagerDelegate(object : WebExtensionController.AddonManagerDelegate {
            override fun onInstalled(extension: WebExtension) = refresh()
            override fun onUninstalled(extension: WebExtension) = refresh()
            override fun onEnabled(extension: WebExtension) = refresh()
            override fun onDisabled(extension: WebExtension) = refresh()
        })
        refresh()
    }

    /** Если предыдущий запрос ещё висит, отклоняем его: одновременно показываем только один. */
    private fun ask(p: ExtPrompt) {
        prompt?.answer?.invoke(false, false)
        prompt = p
    }

    fun answerPrompt(allow: Boolean, privateAllowed: Boolean) {
        val p = prompt ?: return
        prompt = null
        p.answer(allow, privateAllowed)
    }

    /** Перечитывает список установленных расширений у движка. */
    fun refresh() {
        val gen = ++refreshGen
        controller.list().accept({ list ->
            val sorted = (list ?: emptyList()).sortedBy { (it.metaData.name ?: it.id).lowercase() }
            main.post {
                if (gen == refreshGen) applyInstalled(sorted)
            }
        }, { e -> Log.w(TAG, "Не удалось получить список расширений", e) })
    }

    private fun applyInstalled(sorted: List<WebExtension>) {
        // События движка (включено, установлено...) часто приходят парами и вместе с ручным обновлением: если те же
        // самые объекты в том же порядке, список не трогаем (иначе лишняя перекомпоновка страницы и значков)
        val same = installed.size == sorted.size && installed.indices.all { installed[it] === sorted[it] }
        if (same) return
        installed.clear()
        installed.addAll(sorted)
        val ids = sorted.map { it.id }.toSet()
        actions.keys.filter { it !in ids }.forEach { actions.remove(it) }
        sorted.forEach { attach(it) }
    }

    /** Подписываемся на кнопку, всплывающие окна и открытие вкладок этого расширения. */
    private fun attach(ext: WebExtension) {
        ext.setActionDelegate(object : WebExtension.ActionDelegate {
            override fun onBrowserAction(extension: WebExtension, session: GeckoSession?, action: WebExtension.Action) {
                // Значения для конкретной вкладки пока не используем, берём общие
                if (session == null) actions[extension.id] = action
            }

            // Повторное нажатие на кнопку, пока окно открыто, закрывает его (как в Firefox)
            override fun onTogglePopup(extension: WebExtension, action: WebExtension.Action): GeckoResult<GeckoSession>? {
                if (popup?.extension?.id == extension.id) {
                    closePopup()
                    return null
                }
                return openPopup(extension)
            }

            override fun onOpenPopup(extension: WebExtension, action: WebExtension.Action): GeckoResult<GeckoSession>? =
                openPopup(extension)
        })
        ext.setTabDelegate(object : WebExtension.TabDelegate {
            override fun onNewTab(source: WebExtension, createDetails: WebExtension.CreateTabDetails): GeckoResult<GeckoSession>? {
                val s = openTab(createDetails.url ?: "about:blank", createDetails.active != false, true) ?: return null
                return GeckoResult.fromValue(s)
            }

            override fun onOpenOptionsPage(source: WebExtension) {
                source.metaData.optionsPageUrl?.let { openTab(it, true, false) }
            }
        })
    }

    private fun openPopup(ext: WebExtension): GeckoResult<GeckoSession> {
        closePopup()
        val s = GeckoSession()
        s.contentDelegate = object : GeckoSession.ContentDelegate {
            // window.close() из окна расширения (запоздавший запрос от уже закрытого окна не должен закрывать новое)
            override fun onCloseRequest(session: GeckoSession) {
                if (popup?.session === session) closePopup()
            }
        }
        s.navigationDelegate = object : GeckoSession.NavigationDelegate {
            // Ссылка в новом окне из всплывающего окна открывается обычной вкладкой
            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
                openTab(uri, true, false)
                if (popup?.session === session) closePopup()
                return null
            }
        }
        s.open(runtime)
        popup = ExtPopup(ext, s)
        return GeckoResult.fromValue(s)
    }

    fun closePopup() {
        val p = popup ?: return
        popup = null
        try {
            p.session.close()
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось закрыть окно расширения", e)
        }
    }

    /** Сообщает расширениям, какая вкладка сейчас активна (нужно для tabs.query и кнопок). */
    fun setActive(session: GeckoSession) {
        val prev = activeSession
        // Предыдущая вкладка могла уже закрыться: её ошибка не должна помешать сделать активной новую
        if (prev != null && prev !== session) markActive(prev, false)
        activeSession = session
        markActive(session, true)
    }

    private fun markActive(session: GeckoSession, active: Boolean) {
        try {
            controller.setTabActive(session, active)
        } catch (e: Exception) {
            Log.w(TAG, "setTabActive($active)", e)
        }
    }

    fun install(guid: String, xpiUrl: String) {
        if (guid in installing) return
        if (!isTrustedSource(xpiUrl)) {
            error = "Недопустимый источник расширения"
            return
        }
        installing.add(guid)
        try {
            controller.install(xpiUrl, WebExtensionController.INSTALLATION_METHOD_MANAGER).accept(
                { main.post { installing.remove(guid); refresh() } },
                { e -> main.post { installing.remove(guid); describe(e)?.let { error = it } } },
            )
        } catch (e: Exception) {
            // Движок отказал сразу: без этого значок «устанавливается» остался бы навсегда
            installing.remove(guid)
            error = describe(e)
        }
    }

    /**
     * Устанавливать можно только с addons.mozilla.org по https. Адрес приходит из ответа каталога, но проверяем сами:
     * имя пользователя в адресе и обратная косая черта (`https://evil.com\@addons.mozilla.org`) разбираются Android и движком
     * по-разному, и «хост» мог бы оказаться другим.
     */
    private fun isTrustedSource(xpiUrl: String): Boolean {
        if ('\\' in xpiUrl) return false
        val u = runCatching { Uri.parse(xpiUrl) }.getOrNull() ?: return false
        val host = u.host?.lowercase() ?: return false
        return u.scheme.equals("https", true) && u.userInfo == null && (host == AMO_HOST || host.endsWith(".$AMO_HOST"))
    }

    fun uninstall(ext: WebExtension) {
        controller.uninstall(ext).accept(
            { main.post { actions.remove(ext.id); refresh() } },
            { e -> main.post { fail("Не удалось удалить расширение", e) } },
        )
    }

    fun setEnabled(ext: WebExtension, on: Boolean) {
        val r = if (on) controller.enable(ext, WebExtensionController.EnableSource.USER)
        else controller.disable(ext, WebExtensionController.EnableSource.USER)
        r.accept(
            { main.post { refresh() } },
            { e -> main.post { fail(if (on) "Не удалось включить расширение" else "Не удалось выключить расширение", e) } },
        )
    }

    /** Приватные вкладки расширениям по умолчанию недоступны: включать это нужно отдельно. */
    fun setAllowedInPrivate(ext: WebExtension, on: Boolean) {
        controller.setAllowedInPrivateBrowsing(ext, on).accept(
            { main.post { refresh() } },
            { e -> main.post { fail("Не удалось изменить доступ в приватных вкладках", e) } },
        )
    }

    /** Человеку показываем понятную фразу (раньше мог попасть технический текст движка, а то и пустое сообщение), подробности пишем в лог. */
    private fun fail(message: String, e: Throwable?) {
        Log.w(TAG, message, e)
        error = message
    }

    private fun describe(e: Throwable?): String? {
        if (e != null) Log.w(TAG, "Ошибка установки расширения", e)
        val code = (e as? WebExtension.InstallException)?.code ?: return "Не удалось установить расширение"
        return when (code) {
            WebExtension.InstallException.ErrorCodes.ERROR_USER_CANCELED -> null
            WebExtension.InstallException.ErrorCodes.ERROR_NETWORK_FAILURE -> "Нет соединения с каталогом расширений"
            WebExtension.InstallException.ErrorCodes.ERROR_INCOMPATIBLE -> "Расширение несовместимо с этой версией браузера"
            WebExtension.InstallException.ErrorCodes.ERROR_SIGNEDSTATE_REQUIRED -> "Расширение не подписано Mozilla"
            WebExtension.InstallException.ErrorCodes.ERROR_BLOCKLISTED,
            WebExtension.InstallException.ErrorCodes.ERROR_SOFT_BLOCKED -> "Mozilla заблокировала это расширение"
            WebExtension.InstallException.ErrorCodes.ERROR_CORRUPT_FILE,
            WebExtension.InstallException.ErrorCodes.ERROR_INCORRECT_HASH -> "Файл расширения повреждён"
            else -> "Не удалось установить расширение (код $code)"
        }
    }

    private companion object {
        const val TAG = "Extensions"
        const val AMO_HOST = "addons.mozilla.org"
    }
}
