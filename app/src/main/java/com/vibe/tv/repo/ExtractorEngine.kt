package com.vibe.tv.repo

import com.vibe.tv.core.LowLatencyHttp
import com.vibe.tv.core.NativeEngine
import com.vibe.tv.model.MediaCardItem
import com.vibe.tv.model.StreamSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

object ExtractorEngine {

    fun resolveStreamsForItem(item: MediaCardItem): List<StreamSource> {
        if (item.streams.isNotEmpty()) {
            return item.streams
        }

        val target = item.pageOrPluginUrl.trim()
        if (target.isEmpty()) return emptyList()

        if (isDirectStreamUrl(target)) {
            return listOf(
                StreamSource(
                    name = item.providerName,
                    url = target,
                    quality = "HD",
                    isM3u8 = target.contains(".m3u8", ignoreCase = true)
                )
            )
        }

        if (target.endsWith(".cs3", ignoreCase = true)) {
            val cs3Streams = inspectCs3ArchiveInRam(target, item.title)
            if (cs3Streams.isNotEmpty()) return cs3Streams
        }

        return try {
            val raw = LowLatencyHttp.fetchText(target)
            val urls = NativeEngine.extractStreams(raw, target)
            urls.mapIndexed { idx, streamUrl ->
                StreamSource(
                    name = "${item.providerName} #${idx + 1}",
                    url = streamUrl,
                    quality = if (streamUrl.contains(".m3u8", true)) "Auto HD" else "Direct",
                    isM3u8 = streamUrl.contains(".m3u8", true),
                    referer = target
                )
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun parseM3uPlaylist(rawM3u: String, sourceRepoName: String, maxItems: Int = 180): List<MediaCardItem> {
        val items = mutableListOf<MediaCardItem>()
        val lines = rawM3u.lines()
        var pendingTitle = ""
        var pendingGroup = "Live TV"

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            if (line.startsWith("#EXTINF:", ignoreCase = true)) {
                val commaIdx = line.lastIndexOf(',')
                if (commaIdx != -1 && commaIdx + 1 < line.length) {
                    pendingTitle = line.substring(commaIdx + 1).trim()
                }
                val groupMatch = Regex("""group-title="([^"]+)"""").find(line)
                if (groupMatch != null && groupMatch.groupValues[1].isNotBlank()) {
                    pendingGroup = groupMatch.groupValues[1]
                }
            } else if (!line.startsWith("#") && (line.startsWith("http://") || line.startsWith("https://"))) {
                val cleanTitle = if (pendingTitle.isNotEmpty()) pendingTitle else line.substringAfterLast('/')
                val isHls = line.contains(".m3u8", ignoreCase = true)
                val isMovie = pendingGroup.contains("movie", true) ||
                    pendingGroup.contains("film", true) ||
                    pendingGroup.contains("cinema", true) ||
                    line.endsWith(".mp4", true)

                items.add(
                    MediaCardItem(
                        id = NativeEngine.computeIdHash(line),
                        title = cleanTitle,
                        subtitle = "$pendingGroup • Press OK to Watch",
                        badge = if (isMovie) "MOVIE" else "LIVE TV",
                        category = if (isMovie) "MOVIES" else "LIVE",
                        providerName = sourceRepoName,
                        streams = listOf(
                            StreamSource(
                                name = sourceRepoName,
                                url = line,
                                quality = if (isHls) "Live HD" else "Direct HD",
                                isM3u8 = isHls
                            )
                        )
                    )
                )
                pendingTitle = ""
                if (items.size >= maxItems) break
            }
        }
        return items
    }

    fun parseVibeJsonCatalog(rawJson: String, repoName: String): List<MediaCardItem> {
        val results = mutableListOf<MediaCardItem>()
        try {
            val rootArray = if (rawJson.trimStart().startsWith("[")) {
                JSONArray(rawJson)
            } else {
                val obj = JSONObject(rawJson)
                obj.optJSONArray("streams")
                    ?: obj.optJSONArray("items")
                    ?: obj.optJSONArray("movies")
                    ?: obj.optJSONArray("channels")
                    ?: JSONArray()
            }

            for (i in 0 until rootArray.length()) {
                val itemObj = rootArray.optJSONObject(i) ?: continue
                val title = itemObj.optString("title").ifEmpty { itemObj.optString("name", "Stream") }
                val subtitle = itemObj.optString("subtitle").ifEmpty {
                    itemObj.optString("description", "Press OK to watch")
                }
                val directUrl = itemObj.optString("url").ifEmpty { itemObj.optString("streamUrl") }
                if (directUrl.isNotEmpty()) {
                    val isHls = directUrl.contains(".m3u8", ignoreCase = true)
                    results.add(
                        MediaCardItem(
                            id = NativeEngine.computeIdHash(directUrl),
                            title = title,
                            subtitle = subtitle,
                            badge = if (isHls) "HD STREAM" else "MOVIE",
                            category = "MOVIES",
                            providerName = repoName,
                            streams = listOf(
                                StreamSource(
                                    name = repoName,
                                    url = directUrl,
                                    quality = itemObj.optString("quality", "HD"),
                                    isM3u8 = isHls
                                )
                            )
                        )
                    )
                }
            }
        } catch (_: Throwable) {
        }
        return results
    }

    private fun inspectCs3ArchiveInRam(cs3Url: String, pluginTitle: String): List<StreamSource> {
        return try {
            val bytes = LowLatencyHttp.fetchBytesInRam(cs3Url, maxBytes = 1024 * 1024)
            if (bytes.isEmpty()) return emptyList()

            val discoveredStreams = mutableListOf<String>()
            val candidateDomains = mutableListOf<String>()
            val domainRegex = Regex("""https?://[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}(?:/[^\s"'<>`\\]*)?""")

            ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val entryBytes = zis.readBytes()
                    val ascii = buildString {
                        for (b in entryBytes) {
                            val c = b.toInt() and 0xFF
                            if (c in 32..126) append(c.toChar()) else append(' ')
                        }
                    }
                    discoveredStreams.addAll(NativeEngine.extractStreams(ascii))
                    for (m in domainRegex.findAll(ascii)) {
                        val url = m.value
                        if (!url.contains("schemas.android.com") &&
                            !url.contains("github.com") &&
                            !url.contains("google.com") &&
                            !url.contains("w3.org")
                        ) {
                            candidateDomains.add(url)
                        }
                    }
                    entry = zis.nextEntry
                }
            }

            if (discoveredStreams.isEmpty()) {
                for (domainUrl in candidateDomains.distinct().take(3)) {
                    try {
                        val html = LowLatencyHttp.fetchText(domainUrl)
                        discoveredStreams.addAll(NativeEngine.extractStreams(html, domainUrl))
                        if (discoveredStreams.isNotEmpty()) break
                    } catch (_: Throwable) {
                    }
                }
            }

            discoveredStreams.distinct().mapIndexed { index, url ->
                StreamSource(
                    name = "$pluginTitle #${index + 1}",
                    url = url,
                    quality = "Auto HD",
                    isM3u8 = url.contains(".m3u8", true)
                )
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun isDirectStreamUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".m3u8") || lower.contains(".mpd") || lower.contains(".mp4")
    }
}
