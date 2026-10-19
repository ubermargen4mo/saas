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

    private fun deliverCopied(uris: List<Uri>) {
        if (uris.isEmpty()) { deliverFiles(emptyList()); return }
        // Большой файл копируется заметное время: без подсказки страница выглядит зависшей.
        val slowNotice = Runnable { Notices.show("Подготавливаю файлы…") }
        mainHandler.postDelayed(slowNotice, 700L)
        if (!AppExecutors.tryExecute {
            val copied = UploadFiles.copyFiles(applicationContext, uris)
            mainHandler.post {
                mainHandler.removeCallbacks(slowNotice)
                if (copied.isEmpty()) {
                    Notices.show("Не удалось прочитать выбранные файлы")
                }
                deliverFiles(copied)
            }
        }) {
            mainHandler.removeCallbacks(slowNotice)
            deliverFiles(emptyList())
            Notices.show("Приложение занято. Повторите через пару секунд")
        }
    }

    private val pickFilesLauncher = registerForActivityResult(FilePickContract()) { uris -> deliverCopied(uris) }

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
        hrips.prompts.pickFiles = { request, onResult ->
            hrips.prompts.replaceFileSelection(onResult)
            try {
                pickFilesLauncher.launch(request)
            } catch (e: Exception) {
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
        browser.tabs.forEach { if (!it.isSuspended && !it.session.isOpen) it.recover() }
        browser.onForeground()
        // Разбор APK обновления не должен задерживать первый кадр после возврата.
        val appCtx = applicationContext
        AppExecutors.tryExecute { Updater.cleanup(appCtx) }
        Updater.autoCheck(hrips)
    }

    override fun onStop() {
        browser.onBackground()
        super.onStop()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < 26 || !hrips.store.pipEnabled) return
        val tab = browser.tabs.getOrNull(browser.currentIndex) ?: return
        if (tab.fullscreen && hrips.media.isPlaying) {
            try {
                enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
            } catch (e: Exception) {
                android.util.Log.w("MainActivity", "picture-in-picture unavailable", e)
            }
        }
    }

    private fun handleIntent(intent: Intent?) {
        val siteId = intent?.getIntExtra(SiteNotifications.EXTRA_ID, -1) ?: -1
        if (siteId >= 0) {
            hrips.siteNotifications.clicked(siteId, browser)
            return
        }
        if (intent != null && intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        if (intent?.action == Downloads.ACTION_OPEN_PAGE) {
            hrips.downloads.pageRequested = true
            return
        }
        if (IncomingIntents.isLocalFile(intent)) {
            val src = intent
            if (!AppExecutors.tryExecute {
                val url = IncomingIntents.resolve(applicationContext, src)
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
