package com.vibe.tv.core

/**
 * Bridge to the Rust Native Engine (libvibe_core.so).
 * Automatically falls back to lightweight pure-Kotlin implementations if JNI is unavailable.
 */
object NativeEngine {

    var isRustLoaded: Boolean = false
        private set

    init {
        isRustLoaded = try {
            System.loadLibrary("vibe_core")
            true
        } catch (_: Throwable) {
            false
        }
    }

    @JvmStatic
    private external fun normalizeRepoUrl(inputUrl: String): String

    @JvmStatic
    private external fun extractStreamsFromPayload(rawPayload: String, baseUrl: String): String

    @JvmStatic
    private external fun fastHash(input: String): Long

    fun normalizeUrl(rawUrl: String): String {
        if (isRustLoaded) {
            return try {
                normalizeRepoUrl(rawUrl)
            } catch (_: Throwable) {
                fallbackNormalizeUrl(rawUrl)
            }
        }
        return fallbackNormalizeUrl(rawUrl)
    }

    fun extractStreams(rawPayload: String, baseUrl: String = ""): List<String> {
        if (isRustLoaded) {
            return try {
                val res = extractStreamsFromPayload(rawPayload, baseUrl)
                res.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            } catch (_: Throwable) {
                fallbackExtractStreams(rawPayload)
            }
        }
        return fallbackExtractStreams(rawPayload)
    }

    fun computeIdHash(input: String): Long {
        if (isRustLoaded) {
            return try {
                fastHash(input)
            } catch (_: Throwable) {
                input.hashCode().toLong()
            }
        }
        return input.hashCode().toLong()
    }

    private fun fallbackNormalizeUrl(raw: String): String {
        val trimmed = raw.trim().trim('"', '\'')
        if (trimmed.isEmpty()) return ""
        if (trimmed.startsWith("cloudstreamrepo://")) {
            val rest = trimmed.removePrefix("cloudstreamrepo://")
            return if (rest.startsWith("http")) rest else "https://$rest"
        }
        if (trimmed.contains("github.com/") && trimmed.contains("/blob/")) {
            return trimmed
                .replace("github.com/", "raw.githubusercontent.com/")
                .replace("/blob/", "/")
        }
        if (!trimmed.contains("://") && trimmed.count { it == '/' } == 1 && !trimmed.contains(' ')) {
            return "https://raw.githubusercontent.com/$trimmed/main/repo.json"
        }
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }
    }

    private fun fallbackExtractStreams(payload: String): List<String> {
        val unescaped = payload
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
        val regex = Regex("""https?://[^\s"'<>`\\]+?\.(?:m3u8|mpd|mp4)(?:\?[^\s"'<>`\\]*)?""", RegexOption.IGNORE_CASE)
        return regex.findAll(unescaped).map { it.value }.distinct().take(25).toList()
    }
}
