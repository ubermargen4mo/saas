package com.hrips.browser

import android.Manifest
import android.app.PictureInPictureParams
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
    private val mainHandler = Handler(Looper.getMainLooper())

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

    // Результат SAF живёт в Prompts/Application, а не в Activity: после recreation prompt не зависнет.
    private fun deliverFiles(uris: List<Uri>) = hrips.prompts.deliverFileSelection(uris)

    /**
     * Файлы из проводника копируем в кэш приложения и отдаём движку обычными файлами: так не зависим от того,
     * какой провайдер документов (у каждой прошивки свой проводник) вернул ссылку. Копирование в фоне.
     */
    private fun deliverCopied(uris: List<Uri>) {
        if (uris.isEmpty()) { deliverFiles(emptyList()); return }
        if (!AppExecutors.tryExecute {
            val copied = UploadFiles.copyFiles(applicationContext, uris)
            mainHandler.post {
                if (copied.isEmpty()) {
                    Notices.show("Не удалось прочитать выбранные файлы")
                }
                deliverFiles(copied)
            }
        }) {
            deliverFiles(emptyList())
            Notices.show("Приложение занято. Повторите через пару секунд")
        }
    }

    private val pickOneFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> deliverCopied(listOfNotNull(uri)) }

    private val pickManyFiles = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> deliverCopied(uris) }

    // Папка целиком: копируется вместе с вложенными, структура сохраняется
    private val pickFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { tree ->
        if (tree == null) { deliverFiles(emptyList()); return@registerForActivityResult }
        Notices.show("Подготавливаю папку…")
        if (!AppExecutors.tryExecute {
            val dir = UploadFiles.copyTree(applicationContext, tree)
            mainHandler.post {
                if (dir == null) {
                    Notices.show("Не удалось прочитать папку")
                }
                deliverFiles(listOfNotNull(dir))
            }
        }) {
            deliverFiles(emptyList())
            Notices.show("Приложение занято. Повторите через пару секунд")
        }
    }

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
        hrips.uiOwner = this
        hrips.wallpaper.pick = {
            pickWallpaper.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        hrips.prompts.pickFolder = { onResult ->
            hrips.prompts.replaceFileSelection(onResult)
            try {
                pickFolderLauncher.launch(null)
            } catch (e: Exception) {
                deliverFiles(emptyList())
                Notices.show("Не удалось открыть выбор папки")
            }
        }
        browser.permissions.requestAndroid = { perms -> permissionLauncher.launch(perms) }
        browser.permissions.requestFirstRun = { perms -> firstRunLauncher.launch(perms) }
        hrips.downloads.requestNotifications = {
            if (Build.VERSION.SDK_INT >= 33) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        hrips.prompts.pickFiles = { multiple, mimeTypes, onResult ->
            hrips.prompts.replaceFileSelection(onResult)
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
                Notices.show("Не удалось открыть выбор файлов")
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

    override fun onDestroy() {
        // Эти колбэки живут в Application-синглтонах и замыкают launcher'ы данной Activity. Без сброса
        // уничтоженная Activity (со всем деревом View) оставалась бы достижимой, пока жив процесс.
        // Сбрасываем только если колбэки всё ещё наши: новая Activity могла уже выставить свои в onCreate.
        if (hrips.uiOwner === this) {
            hrips.uiOwner = null
            hrips.wallpaper.pick = null
            hrips.prompts.pickFolder = null
            hrips.prompts.pickFiles = null
            hrips.downloads.requestNotifications = null
            hrips.browserIfReady?.permissions?.let {
                it.requestAndroid = null
                it.requestFirstRun = null
            }
        }
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        // A killed Gecko content process makes its session closed/unusable. Recover before showing it.
        browser.tabs.forEach { if (!it.isSuspended && !it.session.isOpen) it.recover() }
        browser.onForeground()
        Updater.cleanup(this)
        Updater.autoCheck(hrips)
    }

    override fun onStop() {
        // Mark all sessions inactive before persisting: Gecko flushes session state when a session
        // becomes inactive, while inactive sessions use substantially less memory.
        browser.onBackground()
        // Browser debounces the write until Gecko has delivered the state flushed by setActive(false).
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
        // Повторная доставка того же intent'а при запуске из списка недавних: вкладку второй раз не открываем
        if (intent != null && intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        // Нажали на уведомление о загрузке: открываем страницу загрузок
        if (intent?.action == Downloads.ACTION_OPEN_PAGE) {
            hrips.downloads.pageRequested = true
            return
        }
        // Ссылка, локальный HTML/PDF, «Поделиться в hrips», «Искать в hrips»
        if (IncomingIntents.isLocalFile(intent)) {
            // Файл копируется в кэш: в фоне, чтобы большой PDF не вешал интерфейс
            val src = intent
            if (!AppExecutors.tryExecute {
                val url = IncomingIntents.resolve(applicationContext, src)
                mainHandler.post {
                    if (url != null) browser.newTab(url)
                    else Notices.show("Не удалось открыть файл")
                }
            }) {
                Notices.show("Не удалось открыть файл сейчас")
            }
        } else {
            IncomingIntents.resolve(this, intent)?.let { browser.newTab(it) }
        }
    }
}
