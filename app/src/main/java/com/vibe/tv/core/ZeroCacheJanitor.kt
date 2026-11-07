package com.vibe.tv.core

import android.content.Context
import java.io.File

/**
 * Automatically and silently wipes all temporary cache directories
 * so the app never accumulates storage on Fire TV.
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
}
