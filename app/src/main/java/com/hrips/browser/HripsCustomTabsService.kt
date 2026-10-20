package com.hrips.browser

import mozilla.components.concept.engine.Engine
import mozilla.components.feature.customtabs.AbstractCustomTabsService
import mozilla.components.feature.customtabs.store.CustomTabsServiceStore

/**
 * Сервис Custom Tabs. Его наличие делает hrips кандидатом на «браузер для Custom Tabs»:
 * именно по нему приложения (YouTube, Telegram, почта) решают, куда направить ссылку.
 * Всю работу делает AbstractCustomTabsService из Mozilla.
 */
class HripsCustomTabsService : AbstractCustomTabsService() {
    private val components get() = (application as HripsApp).components

    override val engine: Engine by lazy { components.engine }
    override val customTabsServiceStore: CustomTabsServiceStore by lazy { components.customTabsServiceStore }
}
