package com.hrips.browser

import android.Manifest
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.Rational
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime

class MainActivity : ComponentActivity() {
    private val hrips get() = application as HripsApp
    private val browser get() = hrips.browser
    private val mainHandler = MainThread.handler

    private val pickWallpaper = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) hrips.wallpaper.set(uri) }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result -> browser.permissions.onAndroidResult(result) }

    private val firstRunLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { browser.permissions.firstRunStage = 1 }

    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private fun deliverFiles(uris: List<Uri>) = hrips.prompts.deliverFileSelection(uris)

    private fun uploadThread(task: () -> Unit) =
        AppExecutors.startThread("hrips-upload", onError = { mainHandler.post { deliverFiles(emptyList()) } }, task = task)

    private fun deliverCopied(uris: List<Uri>) {
        AppLog.i("Upload", "Системный выбор вернул файлов: ${uris.size}")
        if (uris.isEmpty()) { deliverFiles(emptyList()); return }
        val hint = Runnable { Notices.show("Подготавливаю файлы…") }
        mainHandler.postDelayed(hint, 700L)
        val started = SystemClock.elapsedRealtime()
        uploadThread {
            val copied = UploadFiles.copyFiles(applicationContext, uris)
            mainHandler.post {
                mainHandler.removeCallbacks(hint)
                AppLog.i("Upload", "Готово ${copied.size} из ${uris.size} за ${SystemClock.elapsedRealtime() - started} мс")
                if (copied.isEmpty()) Notices.show("Не удалось прочитать выбранные файлы")
                deliverFiles(copied)
            }
        }
    }

    private val pickFilesLauncher = registerForActivityResult(FilePickContract()) { uris -> deliverCopied(uris) }

    private val pickFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { tree ->
        AppLog.i("Upload", "Системный выбор вернул папку: ${tree != null}")
        if (tree == null) { deliverFiles(emptyList()); return@registerForActivityResult }
        uploadThread {
            val ok = UploadFiles.treeWithinLimits(applicationContext, tree)
            mainHandler.post {
                AppLog.i("Upload", "Папка в пределах лимитов: $ok")
                if (ok) {
                    deliverFiles(listOf(tree))
                } else {
                    Notices.show("Папка слишком большая или не читается")
                    deliverFiles(emptyList())
                }
            }
        }
    }

    // Ключи доступа (WebAuthn): GeckoView просит приложение открыть системное окно ключей
    // и вернуть ему результат. Без этого делегата сайты (например, Discord) не могут войти по ключу.
    private var webAuthnResult: GeckoResult<Intent>? = null

    private val webAuthnLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { r ->
        val pending = webAuthnResult
        webAuthnResult = null
        val data = r.data
        AppLog.i("WebAuthn", "Системное окно ключей закрыто: код=${r.resultCode}, данные=${data != null}")
        if (data != null) pending?.complete(data) else pending?.completeExceptionally(RuntimeException("Ключ не выбран"))
    }

    private fun startWebAuthn(pi: PendingIntent): GeckoResult<Intent> {
        val result = GeckoResult<Intent>()
        webAuthnResult?.completeExceptionally(RuntimeException("Запущен новый запрос"))
        webAuthnResult = result
        AppLog.i("WebAuthn", "GeckoView запросил окно ключей доступа")
        try {
            webAuthnLauncher.launch(IntentSenderRequest.Builder(pi).build())
        } catch (e: Exception) {
            AppLog.w("WebAuthn", "Не удалось открыть окно ключей", e)
            webAuthnResult = null
            result.completeExceptionally(e)
        }
        return result
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        AppLog.i("Activity", "onCreate, пересоздание=${savedInstanceState != null}")
        hrips.uiOwner = this
        hrips.runtime.activityDelegate = GeckoRuntime.ActivityDelegate { pi -> startWebAuthn(pi) }
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
        hrips.prompts.pickFiles = { request, onResult ->
            hrips.prompts.replaceFileSelection(onResult)
            try {
                pickFilesLauncher.launch(request)
            } catch (e: Exception) {
                deliverFiles(emptyList())
                Notices.show("Не удалось открыть выбор файлов")
            }
        }
        if (browser.tabs.isEmpty()) browser.resumePendingExitClear(this)
        if (browser.tabs.isEmpty()) browser.restore()
        if (savedInstanceState == null) handleIntent(intent)
        if (browser.tabs.isEmpty()) browser.newTab()
        setContent {
            val incognito = browser.tabs.getOrNull(browser.currentIndex)?.isPrivate == true
            HripsTheme(incognito = incognito, seed = hrips.store.themeSeed, mode = hrips.store.themeMode) {
                Surface(Modifier.fillMaxSize()) { BrowserScreen(browser) }
            }
        }
    }

    override fun onDestroy() {
        AppLog.i("Activity", "onDestroy, смена конфигурации=$isChangingConfigurations, завершение=$isFinishing")
        if (hrips.uiOwner === this) {
            hrips.uiOwner = null
            hrips.runtime.activityDelegate = null
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
        AppLog.i("Activity", "onNewIntent action=${intent.action}")
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        AppLog.i("Activity", "onStart, вкладок=${browser.tabs.size}")
        FrameJank.sync(this)
        AppLog.setUiVisible(true)
        // Пересоздаётся сразу только текущая вкладка; остальные с потерянной сессией ждут показа, а не грузятся все разом.
        val shown = browser.tabs.getOrNull(browser.currentIndex)
        browser.tabs.forEach { it.restoreLostSession(foreground = it === shown) }
        browser.onForeground()
        val appContext = applicationContext
        AppExecutors.tryExecute { Updater.cleanup(appContext) }
        Updater.autoCheck(hrips)
    }

    override fun onStop() {
        AppLog.i("Activity", "onStop")
        FrameJank.stop()
        AppLog.setUiVisible(false)
        hrips.store.markStartupStable()
        hrips.store.flushPendingSaves()
        browser.onBackground()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        AppLog.i("Activity", "onResume")
    }

    override fun onPause() {
        AppLog.i("Activity", "onPause")
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        AppLog.i("Activity", "фокус окна=$hasFocus")
    }

    // Ложное срабатывание lint: переопределяется публичный Activity.dispatchKeyEvent, а не скрытый метод androidx.core.
    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        return KeyShortcuts.handler?.invoke(event) == true || super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (AppLog.enabled && ev.actionMasked == MotionEvent.ACTION_DOWN) {
            AppLog.i("Touch", "касание x=${ev.x.toInt()} y=${ev.y.toInt()}")
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!hrips.store.pipEnabled) return
        val tab = browser.tabs.getOrNull(browser.currentIndex) ?: return
        if (tab.fullscreen && hrips.media.isPlaying) {
            try {
                enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
            } catch (e: Exception) {
                Log.w("MainActivity", "picture-in-picture unavailable", e)
            }
        }
    }

    private fun handleIntent(intent: Intent?) {
        val siteId = intent?.getIntExtra(SiteNotifications.EXTRA_ID, -1) ?: -1
        if (siteId >= 0) {
            hrips.siteNotifications.clicked(siteId, browser, intent?.getStringExtra(SiteNotifications.EXTRA_SOURCE))
            return
        }
        if (intent != null && intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        if (intent?.action == Downloads.ACTION_OPEN_PAGE) {
            hrips.downloads.pageRequested = true
            return
        }
        if (IncomingIntents.isLocalFile(intent)) {
            if (!AppExecutors.tryExecute {
                val url = IncomingIntents.resolve(applicationContext, intent)
                mainHandler.post {
                    if (url != null) browser.newTab(url, returnToPrevious = false)
                    else Notices.show("Не удалось открыть файл")
                }
            }) {
                Notices.show("Не удалось открыть файл сейчас")
            }
        } else {
            IncomingIntents.resolve(this, intent)?.let {
                val tab = browser.newTab(it, returnToPrevious = false)
                if (IncomingIntents.isFromOtherApp(this, intent)) tab.fromApp = true
            }
        }
    }
}
