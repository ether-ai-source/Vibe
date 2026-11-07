package com.vibe.tv.core

import android.content.Context
import java.io.File

/**
 * Enforces Vibe's strictly ephemeral "Zero-Cache" storage guarantee.
 * Purges internal cache, code cache, external cache, and any stray temp files
 * on app launch, after every video playback, and on app exit.
 */
object ZeroCacheJanitor {

    fun purgeAll(context: Context): Long {
        var bytesFreed = 0L
        try {
            bytesFreed += deleteDirContents(context.cacheDir)
            bytesFreed += deleteDirContents(context.codeCacheDir)
            context.externalCacheDir?.let { ext ->
                bytesFreed += deleteDirContents(ext)
            }
            // Also remove any WebView or ExoPlayer temp directories if created by OS
            val dataDir = context.filesDir.parentFile
            if (dataDir != null) {
                val appWebView = File(dataDir, "app_webview")
                if (appWebView.exists()) {
                    bytesFreed += deleteDirContents(appWebView)
                }
            }
        } catch (_: Throwable) {
        }
        LowLatencyHttp.evictAllConnections()
        return bytesFreed
    }

    fun getCurrentCacheBytes(context: Context): Long {
        var total = 0L
        try {
            total += computeDirSize(context.cacheDir)
            context.externalCacheDir?.let { total += computeDirSize(it) }
        } catch (_: Throwable) {
        }
        return total
    }

    private fun deleteDirContents(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        var freed = 0L
        val children = dir.listFiles() ?: return 0L
        for (child in children) {
            if (child.isDirectory) {
                freed += deleteDirContents(child)
            }
            val len = child.length()
            if (child.delete()) {
                freed += len
            }
        }
        return freed
    }

    private fun computeDirSize(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        var size = 0L
        val children = dir.listFiles() ?: return 0L
        for (child in children) {
            size += if (child.isDirectory) computeDirSize(child) else child.length()
        }
        return size
    }
}
