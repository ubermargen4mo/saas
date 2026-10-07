package com.hrips.browser

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController

/**
 * «Оживление» миниатюр: встроенное расширение (assets/hover) имитирует наведение мыши при удержании пальцем,
 * сайт сам запускает свой предпросмотр. Включается и выключается в Настройки -> Медиа.
 */
class HoverPreview(private val runtime: GeckoRuntime) {
    var extension by mutableStateOf<WebExtension?>(null)
        private set
    var enabled by mutableStateOf(true)
        private set

    fun install() {
        runtime.webExtensionController
            .ensureBuiltIn("resource://android/assets/hover/", "hover-preview@hrips.app")
            .accept(
                { ext ->
                    extension = ext
                    enabled = ext?.metaData?.enabled ?: true
                    // Как и блокировщик: в приватных вкладках расширения по умолчанию не работают
                    if (ext != null && !ext.metaData.allowedInPrivateBrowsing) {
                        runtime.webExtensionController.setAllowedInPrivateBrowsing(ext, true)
                            .accept({ updated -> if (updated != null) extension = updated }, { })
                    }
                },
                { },
            )
    }

    fun setEnabled(value: Boolean) {
        val ext = extension ?: return
        val c = runtime.webExtensionController
        val r = if (value) c.enable(ext, WebExtensionController.EnableSource.USER) else c.disable(ext, WebExtensionController.EnableSource.USER)
        r.accept({ updated -> if (updated != null) extension = updated; enabled = value }, { })
    }
}
