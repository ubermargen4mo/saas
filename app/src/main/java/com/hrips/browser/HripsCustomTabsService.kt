package com.hrips.browser

import android.net.Uri
import android.os.Bundle
import androidx.browser.customtabs.CustomTabsService
import androidx.browser.customtabs.CustomTabsSessionToken

/**
 * Минимальный провайдер Custom Tabs: без него YouTube, Telegram, Gmail и другие приложения не считают браузер
 * «встроенным браузером» и открывают ссылки через Chrome или собственный WebView. Приложение находит провайдера
 * по действию `android.support.customtabs.action.CustomTabsService` (берётся браузер по умолчанию, если он его объявил).
 * Сами ссылки приходят в [MainActivity] обычным VIEW с `EXTRA_SESSION`; «назад» возвращает в приложение-источник.
 * Прогрев, предзагрузка, обмен сообщениями и проверка связей не поддерживаются и честно отвечают отказом.
 */
class HripsCustomTabsService : CustomTabsService() {
    override fun warmup(flags: Long): Boolean = true

    override fun newSession(sessionToken: CustomTabsSessionToken): Boolean = true

    override fun mayLaunchUrl(
        sessionToken: CustomTabsSessionToken,
        url: Uri?,
        extras: Bundle?,
        otherLikelyBundles: MutableList<Bundle>?,
    ): Boolean = false

    override fun extraCommand(commandName: String, args: Bundle?): Bundle? = null

    override fun updateVisuals(sessionToken: CustomTabsSessionToken, bundle: Bundle?): Boolean = false

    override fun requestPostMessageChannel(sessionToken: CustomTabsSessionToken, postMessageOrigin: Uri): Boolean = false

    override fun postMessage(sessionToken: CustomTabsSessionToken, message: String, extras: Bundle?): Int =
        RESULT_FAILURE_DISALLOWED

    override fun validateRelationship(
        sessionToken: CustomTabsSessionToken,
        relation: Int,
        origin: Uri,
        extras: Bundle?,
    ): Boolean = false

    override fun receiveFile(sessionToken: CustomTabsSessionToken, uri: Uri, purpose: Int, extras: Bundle?): Boolean = false
}
