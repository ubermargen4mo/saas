package com.hrips.browser

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Читает не больше [maxBytes] байт: всё, что дальше, отбрасывается. Защита от огромных ответов сервера. */
internal fun InputStream.readBounded(maxBytes: Int): ByteArray {
    val out = ByteArrayOutputStream(minOf(maxBytes, 16 * 1024))
    val buf = ByteArray(8 * 1024)
    while (out.size() < maxBytes) {
        val n = read(buf, 0, minOf(buf.size, maxBytes - out.size()))
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}
