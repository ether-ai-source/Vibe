package com.vibe.tv.core

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
        when (trimmed.lowercase()) {
            "megarepo", "3737", "mega" ->
                return "https://raw.githubusercontent.com/recloudstream/cs-repos/master/repos-db.json"
            "phisherrepo", "864", "phisher" ->
                return "https://raw.githubusercontent.com/phisher98/cloudstream-extensions-phisher/refs/heads/builds/repo.json"
            "cnc", "cncverse" ->
                return "https://raw.githubusercontent.com/NivinCNC/CNCVerse-Cloud-Stream-Extension/refs/heads/builds/CNC.json"
            "csx", "3670", "megix" ->
                return "https://raw.githubusercontent.com/SaurabhKaperwan/CSX/builds/CS.json"
            "likdev", "z7iwuxt" ->
                return "https://codeberg.org/JakeWasHere7/likdev256-tamil-providers/raw/branch/builds/repo.json"
            "cspr", "0094", "official" ->
                return "https://raw.githubusercontent.com/recloudstream/extensions/master/repo.json"
            "storm", "l7iwex2" ->
                return "https://raw.githubusercontent.com/redblacker8/storm-ext/refs/heads/builds/repo.json"
        }

        if (trimmed.startsWith("cloudstreamrepo://")) {
            val rest = trimmed.removePrefix("cloudstreamrepo://")
            return if (rest.startsWith("http")) rest else "https://$rest"
        }
        if (trimmed.contains("github.com/") && trimmed.contains("/blob/")) {
            return trimmed
                .replace("github.com/", "raw.githubusercontent.com/")
                .replace("/blob/", "/")
        }
        if (!trimmed.contains("://") &&
            trimmed.count { it == '/' } == 1 &&
            !trimmed.contains(' ') &&
            !trimmed.endsWith(".json") &&
            !trimmed.endsWith(".m3u") &&
            !trimmed.endsWith(".m3u8")
        ) {
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
        val regex = Regex(
            """https?://[^\s"'<>`\\]+?\.(?:m3u8|mpd|mp4)(?:\?[^\s"'<>`\\]*)?""",
            RegexOption.IGNORE_CASE
        )
        return regex.findAll(unescaped)
            .map { it.value }
            .distinct()
            .sortedBy { url ->
                val l = url.lowercase()
                when {
                    l.contains("2160") || l.contains("4k") || l.contains("master.m3u8") -> 0
                    l.contains("1080") || l.contains(".m3u8") -> 1
                    l.contains("720") -> 2
                    else -> 3
                }
            }
            .take(35)
            .toList()
    }
}
