package com.hrips.browser

import android.content.Context
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.mozilla.geckoview.GeckoView

/**
 * GeckoView, который просит клавиатуру не обучаться на том, что вы печатаете на сайтах
 * в приватной вкладке (EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING, как "режим инкогнито" Gboard).
 */
class HripsGeckoView(context: Context) : GeckoView(context) {
    var incognito = false

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val connection = super.onCreateInputConnection(outAttrs)
        if (incognito) outAttrs.imeOptions = outAttrs.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        return connection
    }
}

/** Стартовая страница приватной вкладки: честно говорит, что скрывается, а что нет. */
@Composable
fun PrivateStartPage(
    privateCount: Int,
    screenshotsBlocked: Boolean,
    onSearch: () -> Unit,
    onCloseAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Box(modifier.fillMaxSize().background(cs.surface), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(HripsIcons.Mask, null, Modifier.size(72.dp), tint = cs.primary)
            Spacer(Modifier.height(16.dp))
            Text("Приватная вкладка", style = MaterialTheme.typography.headlineMedium, color = cs.onSurface)
            Spacer(Modifier.height(24.dp))
            Surface(
                onClick = onSearch,
                shape = CircleShape,
                color = cs.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(HripsIcons.Search, null, tint = cs.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp))
                    Text("Искать или задать вопрос", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                }
            }
            Spacer(Modifier.height(32.dp))

            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Note(
                    "Не сохраняется",
                    "История, поисковые запросы, cookies, кэш и данные сайтов. Всё это стирается, когда закрывается " +
                        "последняя приватная вкладка. После перезапуска приложения приватных вкладок не будет.",
                )
                Note(
                    "Остаётся",
                    "Закладки и скачанные файлы (они лежат в папке «Загрузки»). Имена файлов в шторке уведомлений скрыты.",
                )
                Note(
                    "Не скрывается",
                    "Ваш интернет-провайдер, работодатель или VPN-сервис и сами сайты видят, что вы на них заходите. " +
                        "Режим убирает следы на этом устройстве, но не ваш IP-адрес.",
                )
                if (screenshotsBlocked) {
                    Note("Экран", "Скриншоты и миниатюра в списке приложений отключены. Это можно изменить в настройках.")
                }
            }

            if (privateCount > 0) {
                Spacer(Modifier.height(28.dp))
                FilledTonalButton(onClick = onCloseAll) { Text("Закрыть приватные вкладки ($privateCount)") }
            }
        }
    }
}

@Composable
private fun Note(title: String, text: String) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(4.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
