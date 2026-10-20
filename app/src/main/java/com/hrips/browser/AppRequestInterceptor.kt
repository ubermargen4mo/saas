package com.hrips.browser

import android.content.Context
import mozilla.components.browser.errorpages.ErrorPages
import mozilla.components.browser.errorpages.ErrorType
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.request.RequestInterceptor
import mozilla.components.feature.app.links.AppLinksInterceptor

/**
 * Перехват запросов страницы. Логику «открыть в приложении» целиком выполняет Mozilla
 * [AppLinksInterceptor] (включая вход через приложения и защиту от возврата в источник),
 * ошибки загрузки показываются штатными страницами ошибок Mozilla.
 */
class AppRequestInterceptor(
    private val context: Context,
    private val appLinks: () -> AppLinksInterceptor,
) : RequestInterceptor {

    override fun onLoadRequest(
        engineSession: EngineSession,
        uri: String,
        lastUri: String?,
        hasUserGesture: Boolean,
        isSameDomain: Boolean,
        isRedirect: Boolean,
        isDirectNavigation: Boolean,
        isSubframeRequest: Boolean,
    ): RequestInterceptor.InterceptionResponse? = appLinks().onLoadRequest(
        engineSession,
        uri,
        lastUri,
        hasUserGesture,
        isSameDomain,
        isRedirect,
        isDirectNavigation,
        isSubframeRequest,
    )

    override fun onErrorRequest(
        session: EngineSession,
        errorType: ErrorType,
        uri: String?,
    ): RequestInterceptor.ErrorResponse? {
        val page = ErrorPages.createUrlEncodedErrorPage(context = context, errorType = errorType, uri = uri)
        return RequestInterceptor.ErrorResponse(page)
    }
}
