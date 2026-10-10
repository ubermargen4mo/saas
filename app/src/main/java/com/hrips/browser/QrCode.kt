package com.hrips.browser

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** QR-код для текста; один пиксель картинки = один модуль, масштабировать без сглаживания. null, если текст слишком длинный. */
fun qrBitmap(text: String): Bitmap? = try {
    val hints = mapOf<EncodeHintType, Any>(
        EncodeHintType.MARGIN to 1,
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.CHARACTER_SET to "UTF-8",
    )
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
    val px = IntArray(m.width * m.height) { i -> if (m.get(i % m.width, i / m.width)) Color.BLACK else Color.WHITE }
    Bitmap.createBitmap(px, m.width, m.height, Bitmap.Config.ARGB_8888)
} catch (e: Exception) {
    null
}

@Composable
fun QrDialog(url: String, onDismiss: () -> Unit) {
    val bmp = remember(url) { qrBitmap(url) }
    HripsDialog(
        icon = HripsIcons.Qr,
        onDismissRequest = onDismiss,
        title = { Text("QR-код страницы") },
        text = {
            Column(Modifier.padding(top = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (bmp != null) {
                    // QR всегда чёрным по белому, независимо от темы: иначе сканеры его не читают
                    Image(
                        bmp.asImageBitmap(), "QR-код",
                        Modifier.size(248.dp).clip(HripsShapes.L).background(androidx.compose.ui.graphics.Color.White).padding(14.dp),
                        filterQuality = FilterQuality.None,
                    )
                } else {
                    Text("Адрес слишком длинный для QR-кода", style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    url, Modifier.padding(top = 12.dp), maxLines = 2, overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { HripsTextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}
