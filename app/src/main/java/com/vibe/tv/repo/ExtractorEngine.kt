package com.vibe.tv.repo

import com.vibe.tv.core.LowLatencyHttp
import com.vibe.tv.core.NativeEngine
import com.vibe.tv.model.MediaCardItem
import com.vibe.tv.model.StreamSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import java.util.zip.ZipInputStream

object ExtractorEngine {

    /**
     * Resolves playable high-bitrate streams for a selected MediaCardItem.
     * Sorts highest quality (4K / 1080p Adaptive HLS) to index 0.
     */
    fun resolveStreamsForItem(item: MediaCardItem): List<StreamSource> {
        if (item.streams.isNotEmpty()) {
            return prioritizeHighQuality(item.streams)
        }

        val discovered = mutableListOf<StreamSource>()

        val target = item.pageOrPluginUrl.trim()
        if (target.isNotEmpty()) {
            if (isDirectStreamUrl(target)) {
                discovered.add(
                    StreamSource(
                        name = item.providerName,
                        url = target,
                        quality = "1080p Full HD",
                        isM3u8 = target.contains(".m3u8", ignoreCase = true)
                    )
                )
            } else if (target.endsWith(".cs3", ignoreCase = true)) {
                discovered.addAll(inspectCs3ArchiveInRam(target, item.title))
            } else {
                try {
                    val raw = LowLatencyHttp.fetchText(target)
                    val urls = NativeEngine.extractStreams(raw, target)
                    urls.forEachIndexed { idx, streamUrl ->
                        discovered.add(
                            StreamSource(
                                name = "${item.providerName} #${idx + 1}",
                                url = streamUrl,
                                quality = guessQualityLabel(streamUrl),
                                isM3u8 = streamUrl.contains(".m3u8", true),
                                referer = target
                            )
                        )
                    }
                } catch (_: Throwable) {
                }
            }
        }

        // If item has an IMDb ID or is a searched Movie/Series, also probe public multi-stream indexers
        if (discovered.isEmpty() && item.imdbId.startsWith("tt")) {
            discovered.addAll(probePublicStreamsByImdbId(item.imdbId, item.title))
        }

        return prioritizeHighQuality(discovered)
    }

    /**
     * Intelligent Live Search via IMDb Suggestion API (supports all Indian & Global releases)
     * with official high-res posters, release years, and cast metadata.
     */
    fun searchGlobalAndIndianCatalog(query: String): List<MediaCardItem> {
        val clean = query.trim()
        if (clean.isEmpty()) return emptyList()

        val encoded = URLEncoder.encode(clean.lowercase(), "UTF-8")
        val firstChar = encoded.firstOrNull { it.isLetterOrDigit() } ?: 'a'
        val apiUrl = "https://v3.sg.media-imdb.com/suggestion/$firstChar/$encoded.json"

        val results = mutableListOf<MediaCardItem>()
        try {
            val raw = LowLatencyHttp.fetchText(apiUrl)
            val root = JSONObject(raw)
            val arr = root.optJSONArray("d") ?: return emptyList()

            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (!id.startsWith("tt")) continue

                val title = o.optString("l", "Untitled")
                val year = o.optInt("y", 0)
                val stars = o.optString("s", "")
                val kind = o.optString("q", "Movie")
                val imgObj = o.optJSONObject("i")
                val rawPoster = imgObj?.optString("imageUrl") ?: ""
                // Request crisp 600px poster from IMDb CDN
                val poster = if (rawPoster.contains("._V1_")) {
                    rawPoster.replace("._V1_.", "._V1_UX600.")
                } else {
                    rawPoster
                }

                val yearLabel = if (year > 0) "$year" else "HD"
                results.add(
                    MediaCardItem(
                        id = NativeEngine.computeIdHash(id),
                        title = title,
                        subtitle = "$yearLabel • $kind",
                        synopsis = if (stars.isNotEmpty()) "Starring: $stars • Instant Multi-Repo Lookup" else "Instant Multi-Repo Lookup",
                        badge = "4K / 1080p",
                        category = "INDIAN",
                        providerName = "Multi-Repo Fetcher",
                        posterUrl = poster,
                        backdropUrl = poster,
                        imdbId = id
                    )
                )
            }
        } catch (_: Throwable) {
        }
        return results
    }

    private fun probePublicStreamsByImdbId(imdbId: String, title: String): List<StreamSource> {
        val found = mutableListOf<StreamSource>()
        // Probe Archive.org / Public Domain / Open HLS mirrors matching title or ID
        try {
            val encodedTitle = URLEncoder.encode(title, "UTF-8")
            val archiveUrl =
                "https://archive.org/advancedsearch.php?q=title:($encodedTitle)+AND+mediatype:(movies)&fl[]=identifier,title&rows=3&output=json"
            val json = LowLatencyHttp.fetchText(archiveUrl)
            val docs = JSONObject(json).optJSONObject("response")?.optJSONArray("docs") ?: JSONArray()
            for (i in 0 until docs.length()) {
                val doc = docs.optJSONObject(i) ?: continue
                val identifier = doc.optString("identifier")
                if (identifier.isNotEmpty()) {
                    val metaRaw = LowLatencyHttp.fetchText("https://archive.org/metadata/$identifier")
                    val files = JSONObject(metaRaw).optJSONArray("files") ?: JSONArray()
                    for (j in 0 until files.length()) {
                        val f = files.optJSONObject(j) ?: continue
                        val name = f.optString("name")
                        if (name.endsWith(".mp4", true) || name.endsWith(".m3u8", true)) {
                            val directUrl = "https://archive.org/download/$identifier/${URLEncoder.encode(name, "UTF-8").replace("+", "%20")}"
                            found.add(
                                StreamSource(
                                    name = "Direct Archive HD",
                                    url = directUrl,
                                    quality = "1080p HD",
                                    isM3u8 = name.endsWith(".m3u8", true)
                                )
                            )
                            break
                        }
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return found
    }

    fun parseM3uPlaylist(rawM3u: String, sourceRepoName: String, maxItems: Int = 220): List<MediaCardItem> {
        val items = mutableListOf<MediaCardItem>()
        val lines = rawM3u.lines()
        var pendingTitle = ""
        var pendingGroup = "Live TV HD"
        var pendingLogo = ""

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
                val logoMatch = Regex("""tvg-logo="([^"]+)"""").find(line)
                if (logoMatch != null && logoMatch.groupValues[1].isNotBlank()) {
                    pendingLogo = logoMatch.groupValues[1]
                }
            } else if (!line.startsWith("#") && (line.startsWith("http://") || line.startsWith("https://"))) {
                val cleanTitle = if (pendingTitle.isNotEmpty()) pendingTitle else line.substringAfterLast('/')
                val isHls = line.contains(".m3u8", ignoreCase = true)
                val isIndian = sourceRepoName.contains("India", true) ||
                    pendingGroup.contains("India", true) ||
                    pendingGroup.contains("Hindi", true) ||
                    pendingGroup.contains("Tamil", true) ||
                    pendingGroup.contains("Telugu", true) ||
                    pendingGroup.contains("Malayalam", true)

                items.add(
                    MediaCardItem(
                        id = NativeEngine.computeIdHash(line),
                        title = cleanTitle,
                        subtitle = pendingGroup,
                        synopsis = "Live High-Bitrate Stream from $sourceRepoName • Zero Disk Cache",
                        badge = guessQualityLabel(line),
                        category = if (isIndian) "INDIAN" else "LIVE",
                        providerName = sourceRepoName,
                        posterUrl = pendingLogo,
                        backdropUrl = pendingLogo,
                        streams = listOf(
                            StreamSource(
                                name = sourceRepoName,
                                url = line,
                                quality = guessQualityLabel(line),
                                isM3u8 = isHls
                            )
                        )
                    )
                )
                pendingTitle = ""
                pendingLogo = ""
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
                    itemObj.optString("description", "1080p Instant Stream")
                }
                val poster = itemObj.optString("poster").ifEmpty { itemObj.optString("iconUrl") }
                val directUrl = itemObj.optString("url").ifEmpty { itemObj.optString("streamUrl") }
                if (directUrl.isNotEmpty()) {
                    val isHls = directUrl.contains(".m3u8", ignoreCase = true)
                    results.add(
                        MediaCardItem(
                            id = NativeEngine.computeIdHash(directUrl),
                            title = title,
                            subtitle = subtitle,
                            synopsis = subtitle,
                            badge = itemObj.optString("quality", "1080p"),
                            category = "MOVIES",
                            providerName = repoName,
                            posterUrl = poster,
                            backdropUrl = poster,
                            streams = listOf(
                                StreamSource(
                                    name = repoName,
                                    url = directUrl,
                                    quality = itemObj.optString("quality", "1080p HD"),
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
                for (domainUrl in candidateDomains.distinct().take(4)) {
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
                    quality = guessQualityLabel(url),
                    isM3u8 = url.contains(".m3u8", true)
                )
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun prioritizeHighQuality(streams: List<StreamSource>): List<StreamSource> {
        return streams.sortedBy { s ->
            val q = (s.quality + " " + s.url).lowercase()
            when {
                q.contains("4k") || q.contains("2160") || q.contains("master.m3u8") -> 0
                q.contains("1080") || s.isM3u8 -> 1
                q.contains("720") -> 2
                else -> 3
            }
        }
    }

    private fun guessQualityLabel(url: String): String {
        val l = url.lowercase()
        return when {
            l.contains("2160") || l.contains("4k") -> "4K UHD"
            l.contains("1080") || l.contains("fhd") -> "1080p FHD"
            l.contains(".m3u8") -> "1080p Auto"
            l.contains("720") -> "720p HD"
            else -> "1080p HD"
        }
    }

    fun isDirectStreamUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".m3u8") || lower.contains(".mpd") || lower.contains(".mp4")
    }
}
