package com.hrips.browser

import android.util.Log
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.mozilla.geckoview.GeckoPreferenceController
import java.util.Locale

object GeckoColors {
    private const val TAG = "GeckoColors"

    private var wanted: Map<String, String> = emptyMap()
    private var applied: Map<String, String> = emptyMap()
    private var attached = false

    private fun css(c: Color, alpha: Float = 1f): String {
        val a = (alpha.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        return String.format(Locale.ROOT, "#%06X%02X", c.toArgb() and 0xFFFFFF, a)
    }

    @Synchronized
    fun update(cs: ColorScheme) {
        wanted = mapOf(
            "ui.highlight" to css(cs.primary, 0.38f),
            "ui.textHighlightBackground" to css(cs.tertiaryContainer),
            "ui.textHighlightForeground" to css(cs.onTertiaryContainer),
            "ui.textSelectAttentionBackground" to css(cs.primary),
            "ui.textSelectAttentionForeground" to css(cs.onPrimary),
        )
        if (attached) push()
    }

    @Synchronized
    fun attach() {
        attached = true
        push()
    }

    private fun push() {
        val next = HashMap(applied)
        for ((pref, value) in wanted) {
            if (applied[pref] == value) continue
            next[pref] = value
            GeckoPreferenceController.setGeckoPref(pref, value, GeckoPreferenceController.PREF_BRANCH_DEFAULT)
                .accept({ }, { e -> Log.w(TAG, "Не удалось выставить $pref", e) })
        }
        applied = next
    }
}
