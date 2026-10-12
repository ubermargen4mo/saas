package com.hrips.browser

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension

object SiteColors {
    private const val TAG = "SiteColors"
    private const val NATIVE_APP = "hripsSiteColor"
    private const val EXTENSION_ID = "site-color@hrips.app"

    private val main = Handler(Looper.getMainLooper())
    private var runtime: GeckoRuntime? = null
    private var extension: WebExtension? = null
    private val waiting = ArrayList<Pair<GeckoSession, (Int?) -> Unit>>()

    fun install(rt: GeckoRuntime) {
        if (runtime != null) return
        runtime = rt
        rt.webExtensionController
            .ensureBuiltIn("resource://android/assets/sitecolor/", EXTENSION_ID)
            .accept(
                { ext ->
                    if (ext != null) {
                        main.post {
                            extension = ext
                            val pending = waiting.toList()
                            waiting.clear()
                            pending.forEach { (session, callback) -> attach(ext, session, callback) }
                        }
                        if (!ext.metaData.allowedInPrivateBrowsing) {
                            rt.webExtensionController.setAllowedInPrivateBrowsing(ext, true).accept({ }, { })
                        }
                    }
                },
                { e -> Log.w(TAG, "Не удалось установить расширение цвета сайта", e) },
            )
    }

    fun bind(session: GeckoSession, onColor: (Int?) -> Unit) {
        val ext = extension
        if (ext != null) attach(ext, session, onColor) else waiting.add(session to onColor)
    }

    private fun attach(ext: WebExtension, session: GeckoSession, onColor: (Int?) -> Unit) {
        session.webExtensionController.setMessageDelegate(
            ext,
            object : WebExtension.MessageDelegate {
                override fun onMessage(
                    nativeApp: String,
                    message: Any,
                    sender: WebExtension.MessageSender,
                ): GeckoResult<Any>? {
                    val raw = (message as? JSONObject)?.optString("color").orEmpty()
                    val color = if (raw.length == 7 && raw[0] == '#') {
                        runCatching { android.graphics.Color.parseColor(raw) }.getOrNull()
                    } else null
                    main.post { onColor(color) }
                    return null
                }
            },
            NATIVE_APP,
        )
    }
}
