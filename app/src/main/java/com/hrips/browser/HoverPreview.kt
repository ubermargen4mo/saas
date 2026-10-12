package com.hrips.browser

import android.os.Handler
import android.os.Looper

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController

class HoverPreview(private val runtime: GeckoRuntime) {
    private val main = Handler(Looper.getMainLooper())
    var extension by mutableStateOf<WebExtension?>(null)
        private set
    var enabled by mutableStateOf(true)
        private set

    fun install() {
        runtime.webExtensionController
            .ensureBuiltIn("resource://android/assets/hover/", "hover-preview@hrips.app")
            .accept(
                { ext ->
                    main.post {
                        extension = ext
                        enabled = ext?.metaData?.enabled ?: true
                    }
                    if (ext != null && !ext.metaData.allowedInPrivateBrowsing) {
                        runtime.webExtensionController.setAllowedInPrivateBrowsing(ext, true)
                            .accept({ updated -> main.post { if (updated != null) extension = updated } }, { })
                    }
                },
                { },
            )
    }

    fun setHoverEnabled(value: Boolean) {
        val ext = extension ?: return
        val c = runtime.webExtensionController
        val r = if (value) c.enable(ext, WebExtensionController.EnableSource.USER) else c.disable(ext, WebExtensionController.EnableSource.USER)
        r.accept({ updated -> main.post { if (updated != null) extension = updated; enabled = value } }, { })
    }
}
