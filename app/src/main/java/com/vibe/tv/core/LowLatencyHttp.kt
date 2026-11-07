package com.vibe.tv.core

import okhttp3.CacheControl
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object LowLatencyHttp {

    private val connectionPool = ConnectionPool(4, 30, TimeUnit.SECONDS)

    val client: OkHttpClient = OkHttpClient.Builder()
        .cache(null) // Never cache responses to disk
        .connectionPool(connectionPool)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header("Cache-Control", "no-store, no-cache, max-age=0")
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 11; AFTKA) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                )
                .build()
            chain.proceed(req)
        }
        .build()

    fun fetchText(url: String, headers: Map<String, String> = emptyMap()): String {
        val builder = Request.Builder()
            .url(url)
            .cacheControl(CacheControl.FORCE_NETWORK)
        for ((k, v) in headers) {
            builder.header(k, v)
        }
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}")
            }
            return response.body?.string() ?: ""
        }
    }

    fun fetchBytesInRam(url: String, maxBytes: Int = 1024 * 1024): ByteArray {
        val req = Request.Builder()
            .url(url)
            .cacheControl(CacheControl.FORCE_NETWORK)
            .build()
        client.newCall(req).execute().use { response ->
            if (!response.isSuccessful) return ByteArray(0)
            val stream = response.body?.byteStream() ?: return ByteArray(0)
            return stream.readBytes().let { bytes ->
                if (bytes.size > maxBytes) bytes.copyOf(maxBytes) else bytes
            }
        }
    }

    fun evictAllConnections() {
        try {
            connectionPool.evictAll()
        } catch (_: Throwable) {
        }
    }
}
