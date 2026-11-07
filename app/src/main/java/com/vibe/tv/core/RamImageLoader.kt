package com.vibe.tv.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Strictly RAM-only image loader for Netflix-style posters and hero backdrops.
 * Never writes a single byte to disk cache so app size never grows on Fire TV.
 */
object RamImageLoader {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val maxCacheKb = (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt().coerceAtLeast(4096)

    private val memoryCache = object : LruCache<String, Bitmap>(maxCacheKb) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.byteCount / 1024
        }
    }

    fun load(imageView: ImageView, url: String?, targetWidthPx: Int = 420) {
        if (url.isNullOrBlank()) {
            imageView.setImageDrawable(null)
            imageView.tag = null
            return
        }

        imageView.tag = url
        val cached = memoryCache.get(url)
        if (cached != null && !cached.isRecycled) {
            imageView.setImageBitmap(cached)
            imageView.alpha = 1f
            return
        }

        imageView.setImageDrawable(null)
        scope.launch {
            val bmp = withContext(Dispatchers.IO) {
                fetchAndDecodeInRam(url, targetWidthPx)
            }
            if (bmp != null && imageView.tag == url) {
                memoryCache.put(url, bmp)
                imageView.alpha = 0f
                imageView.setImageBitmap(bmp)
                imageView.animate().alpha(1f).setDuration(180).start()
            }
        }
    }

    fun clearRamCache() {
        try {
            memoryCache.evictAll()
        } catch (_: Throwable) {
        }
    }

    private fun fetchAndDecodeInRam(url: String, reqWidth: Int): Bitmap? {
        return try {
            val bytes = LowLatencyHttp.fetchBytesInRam(url, maxBytes = 1536 * 1024)
            if (bytes.isEmpty()) return null

            val boundsOpts = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOpts)

            var sampleSize = 1
            if (boundsOpts.outWidth > reqWidth && reqWidth > 0) {
                val halfWidth = boundsOpts.outWidth / 2
                while ((halfWidth / sampleSize) >= reqWidth) {
                    sampleSize *= 2
                }
            }

            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565 // Uses 50% less RAM on Fire TV
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
        } catch (_: Throwable) {
            null
        }
    }
}
