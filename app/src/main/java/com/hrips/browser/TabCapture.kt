package com.hrips.browser

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.PixelCopy
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.graphics.asImageBitmap
import org.mozilla.geckoview.GeckoView

private const val CAPTURE_WIDTH = 720
private const val MAX_HEIGHT_FACTOR = 3
private const val TAG = "TabCapture"

private val pendingCaptures = HashMap<String, MutableList<() -> Unit>>()

fun captureThumbnail(
    activity: Activity?,
    view: View?,
    tab: Tab,
    allowPixelCopy: Boolean = true,
    timeoutMs: Long = 1000,
    acceptAfterDeparture: Boolean = false,
    done: () -> Unit,
) {
    val gv = view as? GeckoView
    if (activity == null || gv == null || !isCapturable(activity, gv, tab)) {
        done()
        return
    }
    pendingCaptures[tab.id]?.let {
        it.add(done)
        return
    }
    ThumbnailCapture(activity, gv, tab, allowPixelCopy, acceptAfterDeparture, done).start(timeoutMs)
}

private fun isCapturable(activity: Activity, gv: GeckoView, tab: Tab): Boolean =
    !tab.home &&
        gv.width != 0 && gv.height != 0 && gv.isShown &&
        gv.session === tab.session &&
        (activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) == 0

private fun Bitmap.recycleQuietly() {
    if (!isRecycled) recycle()
}

private fun scaleForThumbnail(full: Bitmap): Bitmap {
    val w = CAPTURE_WIDTH
    if (full.width <= w) return full
    val fullH = (w * full.height.toFloat() / full.width).toInt().coerceAtLeast(1)
    val h = fullH.coerceAtMost(w * MAX_HEIGHT_FACTOR)
    val srcH = if (h == fullH) full.height else (h.toFloat() * full.width / w).toInt().coerceIn(1, full.height)
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    Canvas(out).drawBitmap(full, Rect(0, 0, full.width, srcH), Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
    return out
}

private class ThumbnailCapture(
    private val activity: Activity,
    private val gv: GeckoView,
    private val tab: Tab,
    private val allowPixelCopy: Boolean,
    private val acceptAfterDeparture: Boolean,
    firstWaiter: () -> Unit,
) {
    private val main = MainThread.handler
    private val startUrl = tab.url
    private val waiters = mutableListOf(firstWaiter)
    private val timeout = Runnable { finish() }
    private var finished = false

    fun start(timeoutMs: Long) {
        pendingCaptures[tab.id] = waiters
        main.postDelayed(timeout, timeoutMs)
        try {
            gv.capturePixels().accept({ full ->
                main.post { onPixels(full) }
            }, {
                main.post { if (!finished) viaPixelCopy() }
            })
        } catch (e: Exception) {
            AppLog.w(TAG, "capturePixels не удался", e)
            main.post { if (!finished) viaPixelCopy() }
        }
    }

    private fun finish() {
        if (finished) return
        finished = true
        main.removeCallbacks(timeout)
        if (pendingCaptures[tab.id] === waiters) pendingCaptures.remove(tab.id)
        waiters.toList().forEach { runCatching { it() } }
    }

    private fun stillValid(): Boolean =
        !finished && tab.url == startUrl && (acceptAfterDeparture || gv.session === tab.session)

    private fun store(bmp: Bitmap): Boolean {
        if (!stillValid() || TabThumbs.isLikelyFailed(bmp)) return false
        if (!tab.updateThumbnail(bmp.asImageBitmap())) return false
        if (!tab.isPrivate && tab.url.isNotBlank()) TabThumbs.saveAsync(activity.applicationContext, tab.id, bmp)
        return true
    }

    private fun storeAndFinish(bmp: Bitmap) {
        if (!store(bmp)) bmp.recycleQuietly()
        finish()
    }

    private fun onPixels(full: Bitmap?) {
        if (finished) {
            full?.recycleQuietly()
            return
        }
        if (full == null || full.width <= 0 || full.height <= 0) {
            full?.recycleQuietly()
            viaPixelCopy()
        } else {
            scaleAndStore(full)
        }
    }

    private fun scaleAndStore(full: Bitmap) {
        val queued = AppExecutors.tryExecute {
            val scaled = try {
                scaleForThumbnail(full)
            } catch (e: Exception) {
                AppLog.w(TAG, "не удалось уменьшить снимок", e)
                full.recycleQuietly()
                main.post { finish() }
                return@tryExecute
            }
            if (scaled !== full) full.recycleQuietly()
            main.post { storeAndFinish(scaled) }
        }
        if (!queued) {
            full.recycleQuietly()
            finish()
        }
    }

    private fun viaPixelCopy() {
        if (finished || !allowPixelCopy) {
            finish()
            return
        }
        var target: Bitmap? = null
        try {
            val loc = IntArray(2)
            gv.getLocationInWindow(loc)
            val w = CAPTURE_WIDTH
            val h = (w * gv.height.toFloat() / gv.width).toInt().coerceIn(1, w * MAX_HEIGHT_FACTOR)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            target = bmp
            val rect = Rect(loc[0], loc[1], loc[0] + gv.width, loc[1] + gv.height)
            PixelCopy.request(activity.window, rect, bmp, { result ->
                if (result == PixelCopy.SUCCESS) {
                    storeAndFinish(bmp)
                } else {
                    bmp.recycleQuietly()
                    finish()
                }
            }, main)
        } catch (e: Exception) {
            AppLog.w(TAG, "PixelCopy не удался", e)
            target?.recycleQuietly()
            finish()
        }
    }
}
