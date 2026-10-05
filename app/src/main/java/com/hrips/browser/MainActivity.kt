package com.hrips.browser

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
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

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        hrips.wallpaper.pick = {
            pickWallpaper.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        browser.permissions.requestAndroid = { perms -> permissionLauncher.launch(perms) }
        hrips.downloads.requestNotifications = {
            if (Build.VERSION.SDK_INT >= 33) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        hrips.prompts.pickFiles = { multiple, mimeTypes, onResult ->
            deliverFiles(emptyList()) // предыдущий незавершённый выбор считаем отменённым
            filesCallback = onResult
            if (multiple) pickManyFiles.launch(mimeTypes) else pickOneFile.launch(mimeTypes)
        }
        if (browser.tabs.isEmpty()) browser.restore()
        if (savedInstanceState == null) handleIntent(intent)
        if (browser.tabs.isEmpty()) browser.newTab()
        setContent {
            HripsTheme {
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

    private fun handleIntent(intent: Intent?) {
        intent?.data?.toString()?.let { browser.newTab(it) }
    }
}
