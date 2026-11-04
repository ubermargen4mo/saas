package com.hrips.browser

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal class InsecureRedirectException(message: String) : IOException(message)

internal fun openFollowingRedirects(
    start: String,
    maxRedirects: Int = 6,
    configure: HttpURLConnection.() -> Unit,
): HttpURLConnection {
    var url = URL(start)
    repeat(maxRedirects) {
        val c = url.openConnection() as? HttpURLConnection ?: throw IOException("не http(s): $url")
        c.instanceFollowRedirects = false
        c.configure()
        val code = try {
            c.responseCode
        } catch (e: IOException) {
            c.disconnect()
            throw e
        }
        when (code) {
            in 200..299 -> return c
            301, 302, 303, 307, 308 -> {
                val location = c.getHeaderField("Location")
                c.disconnect()
                url = URL(url, location ?: throw IOException("перенаправление без адреса"))
                if (!url.protocol.equals("https", ignoreCase = true)) {
                    throw InsecureRedirectException("перенаправление не на https: $url")
                }
            }
            else -> {
                c.disconnect()
                throw IOException("HTTP $code")
            }
        }
    }
    throw IOException("слишком много перенаправлений")
}
