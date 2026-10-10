@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.hrips.browser

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.util.Size
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.Binarizer
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Сканер QR-кодов: CameraX (превью + анализ кадров) и ZXing, который в проекте уже есть для показа QR.
 * Всё считается на устройстве, без Google Play Services и без сетевых запросов.
 *
 * Найденный код сам ничего не открывает: показывается панель с адресом (хост крупно), и только по кнопке
 * «Открыть» ссылка идёт в новую вкладку. Ссылкой считаются только http/https и «голые» адреса вида
 * example.com/путь; tel:, WIFI:, intent:, javascript: и прочее открывается лишь как поиск текста.
 */
@Composable
fun QrScannerScreen(
    /** Адрес для новой вкладки: сайт из QR-кода либо поиск по его тексту. */
    onOpen: (String) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current

    var granted by remember { mutableStateOf(hasCameraPermission(context)) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.CAMERA) }

    var result by remember { mutableStateOf<String?>(null) }
    var torch by remember { mutableStateOf(false) }
    var hasFlash by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Касания не должны проваливаться на страницу под сканером
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        if (granted && !failed) {
            CameraLayer(
                scanning = result == null,
                torch = torch,
                onFlash = { hasFlash = it },
                onFailed = { failed = true },
                onCode = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    result = it
                },
            )
            if (result == null) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(250.dp).border(3.dp, Color.White.copy(alpha = 0.9f), RoundedCornerShape(28.dp)))
                    Spacer(Modifier.height(16.dp))
                    Surface(shape = RoundedCornerShape(50), color = Color.Black.copy(alpha = 0.5f)) {
                        Text(
                            "Наведите камеру на QR-код",
                            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        } else {
            Column(
                Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    if (failed) "Не удалось запустить камеру" else "Для сканирования нужен доступ к камере",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                if (!failed) {
                    Button(shapes = ButtonDefaults.shapes(), onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text("Разрешить") }
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }) { Text("Настройки приложения") }
                }
            }
        }

        // Верхняя полоса: закрыть и фонарик
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp).align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalIconButton(onClick = onClose) { Icon(HripsIcons.Close, "Закрыть") }
            if (hasFlash && result == null) {
                if (torch) Button(shapes = ButtonDefaults.shapes(), onClick = { torch = false }) { Text("Фонарик выкл.") }
                else FilledTonalButton(shapes = ButtonDefaults.shapes(), onClick = { torch = true }) { Text("Фонарик") }
            }
        }

        result?.let { text ->
            ResultPanel(
                text = text,
                onOpen = onOpen,
                onAgain = { result = null },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun ResultPanel(text: String, onOpen: (String) -> Unit, onAgain: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val url = remember(text) { qrAsUrl(text) }
    val host = remember(url) { url?.let { runCatching { Uri.parse(it).host }.getOrNull() } }

    Surface(
        modifier = modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),
        shape = RoundedCornerShape(28.dp),
        color = cs.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (url != null) "Ссылка из QR-кода" else "Текст из QR-кода",
                style = MaterialTheme.typography.labelLarge,
                color = cs.onSurfaceVariant,
            )
            if (host != null) {
                // Хост крупно: по нему проще заметить подделку
                Text(host, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(
                url ?: text,
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Button(shapes = ButtonDefaults.shapes(),
                onClick = { onOpen(url ?: searchUrl(text)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (url != null) "Открыть" else "Искать") }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(shapes = ButtonDefaults.shapes(),
                    onClick = {
                        context.getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(ClipData.newPlainText("qr", text))
                        Notices.show("Текст из QR-кода скопирован")
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Копировать") }
                TextButton(onClick = onAgain, modifier = Modifier.weight(1f)) { Text("Ещё раз") }
            }
        }
    }
}

/** Превью камеры + анализ кадров. Камера привязана к жизненному циклу активности и отпускается при выходе. */
@Composable
private fun CameraLayer(
    scanning: Boolean,
    torch: Boolean,
    onFlash: (Boolean) -> Unit,
    onFailed: () -> Unit,
    onCode: (String) -> Unit,
) {
    val context = LocalContext.current
    val owner = remember(context) { context.findLifecycleOwner() }
    val scanFlag = remember { AtomicBoolean(true) }
    SideEffect { scanFlag.set(scanning) }
    val latestOnCode by rememberUpdatedState(onCode)
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            keepScreenOn = true
        }
    }
    var camera by remember { mutableStateOf<Camera?>(null) }

    DisposableEffect(owner) {
        if (owner == null) {
            onFailed()
            return@DisposableEffect onDispose { }
        }
        val executor = Executors.newSingleThreadExecutor()
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            try {
                val p = future.get()
                provider = p
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    // 720p хватает для QR и не нагружает процессор; если такого размера нет, берётся ближайший
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                            )
                            .build(),
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor, QrAnalyzer { text ->
                    // Один код = одно срабатывание, пока пользователь не нажмёт «Ещё раз»
                    if (scanFlag.compareAndSet(true, false)) previewView.post { latestOnCode(text) }
                }.also { it.enabled = { scanFlag.get() } })
                p.unbindAll()
                val cam = p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                camera = cam
                onFlash(cam.cameraInfo.hasFlashUnit())
            } catch (e: Exception) {
                onFailed()
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            provider?.unbindAll()
            camera = null
            executor.shutdown()
        }
    }

    LaunchedEffect(camera, torch) { camera?.cameraControl?.enableTorch(torch) }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}

/**
 * Берёт яркостную плоскость кадра (Y) и ищет в ней QR. Если не нашёл, пробует инвертированное изображение
 * (светлый код на тёмном фоне). Кадры чаще ~12 в секунду не разбираются.
 */
private class QrAnalyzer(private val onFound: (String) -> Unit) : ImageAnalysis.Analyzer {
    var enabled: () -> Boolean = { true }
    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true,
            ),
        )
    }
    private var last = 0L

    override fun analyze(image: ImageProxy) {
        try {
            val now = SystemClock.elapsedRealtime()
            if (!enabled() || now - last < 80) return
            last = now
            val plane = image.planes[0]
            val buffer = plane.buffer
            val data = ByteArray(buffer.remaining())
            buffer.get(data)
            // Ширина строки в буфере (rowStride) может быть больше ширины кадра
            val source = PlanarYUVLuminanceSource(data, plane.rowStride, image.height, 0, 0, image.width, image.height, false)
            val text = decode(HybridBinarizer(source)) ?: decode(HybridBinarizer(source.invert()))
            if (text != null) onFound(text)
        } catch (e: Exception) {
            // Битый кадр пропускаем: следующий придёт через долю секунды
        } finally {
            image.close()
        }
    }

    private fun decode(binarizer: Binarizer): String? = try {
        reader.decodeWithState(BinaryBitmap(binarizer)).text
    } catch (e: ReaderException) {
        null
    } finally {
        reader.reset()
    }
}

private val bareUrl = Regex("^[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+(:\\d+)?([/?#]\\S*)?$")

/**
 * Адрес сайта из текста QR-кода, либо null, если это не ссылка. Принимаются только http/https и «голые»
 * адреса (example.com/путь). Всё остальное (tel:, mailto:, WIFI:, intent:, javascript:, file:) ссылкой не считается.
 */
fun qrAsUrl(text: String): String? {
    val t = text.trim()
    if (t.isEmpty() || t.any { it.isWhitespace() }) return null
    return when {
        t.startsWith("http://", ignoreCase = true) || t.startsWith("https://", ignoreCase = true) ->
            t.takeIf { runCatching { Uri.parse(it).host }.getOrNull().orEmpty().isNotEmpty() }
        bareUrl.matches(t) -> "https://$t"
        else -> null
    }
}

private fun searchUrl(text: String) = SearchEngines.current.template + Uri.encode(text.trim())

private fun hasCameraPermission(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private tailrec fun Context.findLifecycleOwner(): LifecycleOwner? = when (this) {
    is LifecycleOwner -> this
    is ContextWrapper -> baseContext.findLifecycleOwner()
    else -> null
}
