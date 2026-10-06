package com.hrips.browser

import android.Manifest
import android.app.PictureInPictureParams
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {
    private val hrips get() = application as HripsApp
    private val browser get() = hrips.browser

    private val pickWallpaper = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) hrips.wallpaper.set(uri) }

    // Системный запрос разрешений (камера, микрофон, местоположение) для сайтов
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result -> browser.permissions.onAndroidResult(result) }

    // Экран первого запуска: все системные разрешения одним заходом
    private val firstRunLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { browser.permissions.firstRunStage = 1 }

    // Разрешение на уведомления (Android 13+): без него не видно прогресс загрузок в шторке
    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    // Загрузка файлов на сайты (<input type="file">): системный выбор файлов
    private var filesCallback: ((List<Uri>) -> Unit)? = null

    private fun deliverFiles(uris: List<Uri>) {
        val cb = filesCallback
        filesCallback = null
        cb?.invoke(uris)
    }

    private val pickOneFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> deliverFiles(listOfNotNull(uri)) }

    private val pickManyFiles = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> deliverFiles(uris) }

    // Если сайт просит только фото/видео: системный подборщик медиа (Google Фото и аналоги)
    private val pickOneMedia = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> deliverFiles(listOfNotNull(uri)) }

    private val pickManyMedia = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris -> deliverFiles(uris) }

    /** Запрос к подборщику медиа или null, если нужны любые файлы (тогда открывается проводник). */
    private fun mediaRequest(types: Array<String>): PickVisualMediaRequest? {
        if (types.isEmpty() || types.any { it == "*/*" }) return null
        val images = types.all { it.startsWith("image/") }
        val videos = types.all { it.startsWith("video/") }
        val media = types.all { it.startsWith("image/") || it.startsWith("video/") }
        return PickVisualMediaRequest(
            when {
                // Один конкретный тип (например, только image/png): подборщик отфильтрует сам
                (images || videos) && types.size == 1 && !types[0].endsWith("/*") ->
                    ActivityResultContracts.PickVisualMedia.SingleMimeType(types[0])
                images -> ActivityResultContracts.PickVisualMedia.ImageOnly
                videos -> ActivityResultContracts.PickVisualMedia.VideoOnly
                media -> ActivityResultContracts.PickVisualMedia.ImageAndVideo
                else -> return null
            }
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        hrips.wallpaper.pick = {
            pickWallpaper.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        browser.permissions.requestAndroid = { perms -> permissionLauncher.launch(perms) }
        browser.permissions.requestFirstRun = { perms -> firstRunLauncher.launch(perms) }
        hrips.downloads.requestNotifications = {
            if (Build.VERSION.SDK_INT >= 33) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        hrips.prompts.pickFiles = { multiple, mimeTypes, onResult ->
            deliverFiles(emptyList()) // предыдущий незавершённый выбор считаем отменённым
            filesCallback = onResult
            try {
                val media = mediaRequest(mimeTypes)
                when {
                    media != null && multiple -> pickManyMedia.launch(media)
                    media != null -> pickOneMedia.launch(media)
                    multiple -> pickManyFiles.launch(mimeTypes)
                    else -> pickOneFile.launch(mimeTypes)
                }
            } catch (e: Exception) {
                // Нет приложения для выбора файлов: страница не должна зависнуть в ожидании
                deliverFiles(emptyList())
                android.widget.Toast.makeText(this, "Не удалось открыть выбор файлов", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        if (browser.tabs.isEmpty()) browser.restore()
        if (savedInstanceState == null) handleIntent(intent)
        if (browser.tabs.isEmpty()) browser.newTab()
        setContent {
            val incognito = browser.tabs.getOrNull(browser.currentIndex)?.isPrivate == true
            HripsTheme(incognito = incognito) {
                Surface(Modifier.fillMaxSize()) { BrowserScreen(browser) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        // Если система закрыла сессию, пока приложение было свёрнуто, поднимаем её заново
        browser.tabs.forEach { if (!it.session.isOpen) it.recover() }
    }

    override fun onStop() {
        browser.saveState()
        super.onStop()
    }

    /**
     * Видео на весь экран идёт и пользователь ушёл на главный экран: вместо паузы
     * показываем его в маленьком окне поверх всего ("картинка в картинке").
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < 26 || !hrips.store.pipEnabled) return
        val tab = browser.tabs.getOrNull(browser.currentIndex) ?: return
        if (tab.fullscreen && hrips.media.isPlaying) {
            try {
                enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
            } catch (e: Exception) {
                // устройство или режим не поддерживает PiP: остаётся фоновое воспроизведение
            }
        }
    }

    private fun handleIntent(intent: Intent?) {
        // Нажали на уведомление сайта
        val siteId = intent?.getIntExtra(SiteNotifications.EXTRA_ID, -1) ?: -1
        if (siteId >= 0) {
            hrips.siteNotifications.clicked(siteId, browser)
            return
        }
        intent?.data?.toString()?.let { browser.newTab(it) }
    }
}
