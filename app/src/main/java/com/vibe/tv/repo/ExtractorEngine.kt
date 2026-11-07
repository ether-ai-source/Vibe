package com.vibe.tv.repo

import com.vibe.tv.core.LowLatencyHttp
import com.vibe.tv.core.NativeEngine
import com.vibe.tv.model.MediaCardItem
import com.vibe.tv.model.StreamSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Universal in-memory extractor for:
 * 1. Direct HLS (.m3u8), DASH (.mpd), and MP4 URLs
 * 2. M3U / M3U8 Live & VOD playlists
 * 3. CloudStream 3 (.cs3) plugin archives (inspected in RAM via ZipInputStream without writing to disk)
 * 4. Webpage / Embed HTML & JSON responses (via Rust NativeEngine)
 */
object ExtractorEngine {

    /**
     * Resolves playable StreamSources for a selected MediaCardItem.
     * If the item already has direct streams, returns them immediately.
     * If it points to a dynamic webpage, JSON endpoint, or .cs3 package, resolves streams in RAM.
     */
    fun resolveStreamsForItem(item: MediaCardItem): List<StreamSource> {
        if (item.streams.isNotEmpty()) {
            return item.streams
        }

        val target = item.pageOrPluginUrl.trim()
        if (target.isEmpty()) return emptyList()

        // If target is already a direct media link
        if (isDirectStreamUrl(target)) {
            return listOf(
                StreamSource(
                    name = item.providerName,
                    url = target,
                    quality = "Direct",
                    isM3u8 = target.contains(".m3u8", ignoreCase = true)
                )
            )
        }

        // If target is a CloudStream .cs3 package, inspect in RAM and extract embedded endpoints
        if (target.endsWith(".cs3", ignoreCase = true)) {
            val cs3Streams = inspectCs3ArchiveInRam(target, item.title)
            if (cs3Streams.isNotEmpty()) return cs3Streams
        }

        // Otherwise fetch page/JSON and run Rust zero-allocation stream extraction
        return try {
            val raw = LowLatencyHttp.fetchText(target)
            val urls = NativeEngine.extractStreams(raw, target)
            urls.mapIndexed { idx, streamUrl ->
                StreamSource(
                    name = "${item.providerName} #${idx + 1}",
                    url = streamUrl,
                    quality = if (streamUrl.contains(".m3u8", true)) "HLS Adaptive" else "Direct",
                    isM3u8 = streamUrl.contains(".m3u8", true),
                    referer = target
                )
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /**
     * Parses an M3U / M3U8 playlist into MediaCardItems.
     */
    fun parseM3uPlaylist(rawM3u: String, sourceRepoName: String): List<MediaCardItem> {
        val items = mutableListOf<MediaCardItem>()
        val lines = rawM3u.lines()
        var pendingTitle = ""
        var pendingGroup = "Live / VOD"

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            if (line.startsWith("#EXTINF:", ignoreCase = true)) {
                val commaIdx = line.lastIndexOf(',')
                if (commaIdx != -1 && commaIdx + 1 < line.length) {
                    pendingTitle = line.substring(commaIdx + 1).trim()
                }
                val groupMatch = Regex("""group-title="([^"]+)"""").find(line)
                if (groupMatch != null) {
                    pendingGroup = groupMatch.groupValues[1]
                }
            } else if (!line.startsWith("#") && (line.startsWith("http://") || line.startsWith("https://"))) {
                val title = if (pendingTitle.isNotEmpty()) pendingTitle else line.substringAfterLast('/')
                val isHls = line.contains(".m3u8", ignoreCase = true)
                items.add(
                    MediaCardItem(
                        id = NativeEngine.computeIdHash(line),
                        title = title,
                        subtitle = pendingGroup,
                        badge = if (isHls) "HLS" else "STREAM",
                        providerName = sourceRepoName,
                        streams = listOf(
                            StreamSource(
                                name = sourceRepoName,
                                url = line,
                                quality = "Auto",
                                isM3u8 = isHls
                            )
                        )
                    )
                )
                pendingTitle = ""
                if (items.size >= 200) break
            }
        }
        return items
    }

    /**
     * Parses a Vibe JSON catalog or generic JSON stream array.
     */
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
                val title = itemObj.optString("title").ifEmpty { itemObj.optString("name", "Untitled Stream") }
                val subtitle = itemObj.optString("subtitle").ifEmpty {
                    itemObj.optString("description", "Direct Low-Latency Stream")
                }
                val directUrl = itemObj.optString("url").ifEmpty { itemObj.optString("streamUrl") }
                if (directUrl.isNotEmpty()) {
                    val isHls = directUrl.contains(".m3u8", ignoreCase = true)
                    results.add(
                        MediaCardItem(
                            id = NativeEngine.computeIdHash(directUrl),
                            title = title,
                            subtitle = subtitle,
                            badge = if (isHls) "HLS" else "MP4",
                            providerName = repoName,
                            streams = listOf(
                                StreamSource(
                                    name = repoName,
                                    url = directUrl,
                                    quality = itemObj.optString("quality", "1080p"),
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

    /**
     * Downloads a CloudStream 3 `.cs3` archive strictly into a small RAM buffer,
     * scans its manifest/DEX string table for target provider URLs or embedded streams,
     * and never touches disk storage.
     */
    private fun inspectCs3ArchiveInRam(cs3Url: String, pluginTitle: String): List<StreamSource> {
        return try {
            val bytes = LowLatencyHttp.fetchBytesInRam(cs3Url, maxBytes = 1024 * 1024)
            if (bytes.isEmpty()) return emptyList()

            val discoveredUrls = mutableListOf<String>()
            ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val entryBytes = zis.readBytes()
                    // Extract printable ASCII strings from manifest.json or classes.dex
                    val ascii = buildString {
                        for (b in entryBytes) {
                            val c = b.toInt() and 0xFF
                            if (c in 32..126) append(c.toChar()) else append(' ')
                        }
                    }
                    discoveredUrls.addAll(NativeEngine.extractStreams(ascii))
                    entry = zis.nextEntry
                }
            }

            discoveredUrls.distinct().mapIndexed { index, url ->
                StreamSource(
                    name = "$pluginTitle #${index + 1}",
                    url = url,
                    quality = "Auto",
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
