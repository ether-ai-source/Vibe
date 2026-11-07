package com.vibe.tv.repo

import android.content.Context
import com.vibe.tv.core.LowLatencyHttp
import com.vibe.tv.core.NativeEngine
import com.vibe.tv.model.Cs3PluginMeta
import com.vibe.tv.model.MediaCardItem
import com.vibe.tv.model.RepoConfig
import com.vibe.tv.model.StreamSource
import org.json.JSONArray
import org.json.JSONObject

/**
 * Manages external repositories (CloudStream 3 repo.json, plugins.json, M3U playlists, Vibe JSON).
 * Stores only tiny URL strings in SharedPreferences (< 4 KB) so app storage never grows.
 */
class RepoManager(context: Context) {

    private val prefs = context.getSharedPreferences("vibe_repos_prefs", Context.MODE_PRIVATE)

    fun getSavedRepos(): List<RepoConfig> {
        val rawJson = prefs.getString(KEY_REPOS, null) ?: return getDefaultStarterRepos()
        return try {
            val arr = JSONArray(rawJson)
            val list = mutableListOf<RepoConfig>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    RepoConfig(
                        name = o.optString("name", "Custom Repo"),
                        url = o.optString("url"),
                        repoType = o.optString("repoType", "CS3"),
                        pluginCount = o.optInt("pluginCount", 0)
                    )
                )
            }
            if (list.isEmpty()) getDefaultStarterRepos() else list
        } catch (_: Throwable) {
            getDefaultStarterRepos()
        }
    }

    fun saveRepos(repos: List<RepoConfig>) {
        val arr = JSONArray()
        for (r in repos) {
            val o = JSONObject()
            o.put("name", r.name)
            o.put("url", r.url)
            o.put("repoType", r.repoType)
            o.put("pluginCount", r.pluginCount)
            arr.put(o)
        }
        prefs.edit().putString(KEY_REPOS, arr.toString()).apply()
    }

    /**
     * Adds and verifies a new repository URL (CloudStream 3 repo.json, plugins.json, M3U, or JSON catalog).
     */
    fun addRepositoryUrl(rawInputUrl: String): RepoConfig {
        val normalized = NativeEngine.normalizeUrl(rawInputUrl)
        if (normalized.isEmpty()) {
            throw IllegalArgumentException("Invalid repository URL")
        }

        val current = getSavedRepos().toMutableList()
        val existingIdx = current.indexOfFirst { it.url.equals(normalized, ignoreCase = true) }

        val inspected = inspectRemoteRepository(normalized)
        if (existingIdx != -1) {
            current[existingIdx] = inspected
        } else {
            current.add(0, inspected)
        }
        saveRepos(current)
        return inspected
    }

    fun removeRepository(url: String) {
        val updated = getSavedRepos().filterNot { it.url.equals(url, ignoreCase = true) }
        saveRepos(updated)
    }

    /**
     * Inspects a remote URL to determine whether it is a CloudStream 3 repo.json,
     * a direct CloudStream plugins.json list, an M3U playlist, or a Vibe JSON catalog.
     */
    private fun inspectRemoteRepository(url: String): RepoConfig {
        return try {
            val body = LowLatencyHttp.fetchText(url).trim()
            when {
                body.startsWith("#EXTM3U", ignoreCase = true) || url.endsWith(".m3u", true) || url.endsWith(".m3u8", true) -> {
                    val items = ExtractorEngine.parseM3uPlaylist(body, "M3U Playlist")
                    RepoConfig(
                        name = url.substringAfterLast('/').substringBefore('?').ifEmpty { "M3U Playlist" },
                        url = url,
                        repoType = "M3U",
                        pluginCount = items.size
                    )
                }
                body.startsWith("{") -> {
                    val obj = JSONObject(body)
                    if (obj.has("pluginLists")) {
                        // Standard CloudStream 3 repo.json
                        val name = obj.optString("name", "CloudStream 3 Repo")
                        val lists = obj.optJSONArray("pluginLists") ?: JSONArray()
                        var totalPlugins = 0
                        for (i in 0 until lists.length()) {
                            val pluginListUrl = lists.optString(i)
                            if (pluginListUrl.isNotEmpty()) {
                                totalPlugins += fetchCs3PluginsList(pluginListUrl, name).size
                            }
                        }
                        RepoConfig(name = name, url = url, repoType = "CS3", pluginCount = totalPlugins)
                    } else {
                        val items = ExtractorEngine.parseVibeJsonCatalog(body, obj.optString("name", "Vibe JSON Repo"))
                        RepoConfig(
                            name = obj.optString("name", "JSON Stream Repo"),
                            url = url,
                            repoType = "VIBE_JSON",
                            pluginCount = items.size
                        )
                    }
                }
                body.startsWith("[") -> {
                    val arr = JSONArray(body)
                    val first = arr.optJSONObject(0)
                    if (first != null && (first.has("internalName") || first.has("apiVersion"))) {
                        RepoConfig(
                            name = "CS3 Plugins Index",
                            url = url,
                            repoType = "CS3_PLUGINS",
                            pluginCount = arr.length()
                        )
                    } else {
                        RepoConfig(
                            name = "JSON Stream Feed",
                            url = url,
                            repoType = "VIBE_JSON",
                            pluginCount = arr.length()
                        )
                    }
                }
                else -> {
                    RepoConfig(
                        name = url.substringAfter("://").substringBefore('/'),
                        url = url,
                        repoType = "WEB_EXTRACTOR",
                        pluginCount = 1
                    )
                }
            }
        } catch (_: Throwable) {
            RepoConfig(
                name = url.substringAfterLast('/').ifEmpty { "External Repo" },
                url = url,
                repoType = "CS3",
                pluginCount = 0
            )
        }
    }

    /**
     * Fetches all MediaCardItems across all configured repositories + built-in low-latency showcase streams.
     */
    fun fetchAllCatalogItems(): List<MediaCardItem> {
        val allItems = mutableListOf<MediaCardItem>()

        // 1. Built-in verified low-latency HLS & MP4 streams for instant Fire TV testing
        allItems.addAll(getBuiltInLowLatencyStreams())

        // 2. Fetch from user-configured repositories
        for (repo in getSavedRepos()) {
            if (repo.url.startsWith("builtin://")) continue
            try {
                val body = LowLatencyHttp.fetchText(repo.url).trim()
                when {
                    body.startsWith("#EXTM3U", ignoreCase = true) -> {
                        allItems.addAll(ExtractorEngine.parseM3uPlaylist(body, repo.name))
                    }
                    body.startsWith("{") -> {
                        val obj = JSONObject(body)
                        if (obj.has("pluginLists")) {
                            val repoTitle = obj.optString("name", repo.name)
                            val lists = obj.optJSONArray("pluginLists") ?: JSONArray()
                            for (i in 0 until lists.length()) {
                                val listUrl = lists.optString(i)
                                if (listUrl.isNotEmpty()) {
                                    val plugins = fetchCs3PluginsList(listUrl, repoTitle)
                                    allItems.addAll(plugins.map { it.toMediaCardItem() })
                                }
                            }
                        } else {
                            allItems.addAll(ExtractorEngine.parseVibeJsonCatalog(body, repo.name))
                        }
                    }
                    body.startsWith("[") -> {
                        val arr = JSONArray(body)
                        val first = arr.optJSONObject(0)
                        if (first != null && (first.has("internalName") || first.has("apiVersion"))) {
                            val plugins = parseCs3PluginArray(arr, repo.name)
                            allItems.addAll(plugins.map { it.toMediaCardItem() })
                        } else {
                            allItems.addAll(ExtractorEngine.parseVibeJsonCatalog(body, repo.name))
                        }
                    }
                    else -> {
                        val extracted = NativeEngine.extractStreams(body, repo.url)
                        extracted.forEachIndexed { idx, streamUrl ->
                            allItems.add(
                                MediaCardItem(
                                    id = NativeEngine.computeIdHash(streamUrl),
                                    title = "${repo.name} Stream #${idx + 1}",
                                    subtitle = streamUrl,
                                    badge = if (streamUrl.contains(".m3u8", true)) "HLS" else "DIRECT",
                                    providerName = repo.name,
                                    streams = listOf(StreamSource(name = repo.name, url = streamUrl))
                                )
                            )
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }

        return allItems.distinctBy { it.id }
    }

    private fun fetchCs3PluginsList(pluginsJsonUrl: String, repoName: String): List<Cs3PluginMeta> {
        return try {
            val raw = LowLatencyHttp.fetchText(pluginsJsonUrl).trim()
            val arr = JSONArray(raw)
            parseCs3PluginArray(arr, repoName)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun parseCs3PluginArray(arr: JSONArray, repoName: String): List<Cs3PluginMeta> {
        val list = mutableListOf<Cs3PluginMeta>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val status = o.optInt("status", 1)
            if (status == 0) continue // Skip disabled plugins

            val tvTypesArr = o.optJSONArray("tvTypes")
            val types = mutableListOf<String>()
            if (tvTypesArr != null) {
                for (j in 0 until tvTypesArr.length()) {
                    types.add(tvTypesArr.optString(j))
                }
            }

            list.add(
                Cs3PluginMeta(
                    name = o.optString("name", o.optString("internalName", "CS3 Provider")),
                    internalName = o.optString("internalName", "Provider"),
                    url = o.optString("url"),
                    tvTypes = types,
                    repositoryName = repoName,
                    description = o.optString("description", types.joinToString(", ").ifEmpty { "CloudStream 3 Provider" })
                )
            )
        }
        return list
    }

    private fun Cs3PluginMeta.toMediaCardItem(): MediaCardItem {
        val badgeText = tvTypes.firstOrNull()?.uppercase()?.take(6) ?: "CS3"
        return MediaCardItem(
            id = NativeEngine.computeIdHash("$repositoryName:$internalName:$url"),
            title = name,
            subtitle = description.ifEmpty { "CloudStream 3 Provider • $repositoryName" },
            badge = badgeText,
            providerName = repositoryName,
            streams = emptyList(),
            pageOrPluginUrl = url
        )
    }

    private fun getDefaultStarterRepos(): List<RepoConfig> {
        return listOf(
            RepoConfig(
                name = "Vibe Open 4K & HLS Core",
                url = "builtin://vibe-core-streams",
                repoType = "BUILTIN",
                pluginCount = 6
            )
        )
    }

    private fun getBuiltInLowLatencyStreams(): List<MediaCardItem> {
        return listOf(
            MediaCardItem(
                id = 1001L,
                title = "Tears of Steel (Adaptive HLS)",
                subtitle = "1080p Adaptive Bitrate • Low-Latency Test Stream",
                badge = "HLS",
                providerName = "Vibe Core",
                streams = listOf(
                    StreamSource(
                        name = "Unified Streaming HLS",
                        url = "https://demo.unified-streaming.com/k8s/features/stable/video/tears-of-steel/tears-of-steel.ism/.m3u8",
                        quality = "1080p HLS",
                        isM3u8 = true
                    ),
                    StreamSource(
                        name = "Direct MP4 Mirror",
                        url = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/TearsOfSteel.mp4",
                        quality = "1080p MP4",
                        isM3u8 = false
                    )
                )
            ),
            MediaCardItem(
                id = 1002L,
                title = "Sintel (4K / 1080p Multi-Audio HLS)",
                subtitle = "Apple Advanced BIPBOP / Sintel HLS Manifest",
                badge = "HLS",
                providerName = "Vibe Core",
                streams = listOf(
                    StreamSource(
                        name = "Bitmovin HLS",
                        url = "https://bitdash-a.akamaihd.net/content/sintel/hls/playlist.m3u8",
                        quality = "1080p HLS",
                        isM3u8 = true
                    ),
                    StreamSource(
                        name = "Direct MP4 Fallback",
                        url = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/Sintel.mp4",
                        quality = "720p MP4",
                        isM3u8 = false
                    )
                )
            ),
            MediaCardItem(
                id = 1003L,
                title = "Big Buck Bunny (60fps Low-Latency HLS)",
                subtitle = "Mux Global CDN • Instant 0.6s Startup Test",
                badge = "HLS 60FPS",
                providerName = "Vibe Core",
                streams = listOf(
                    StreamSource(
                        name = "Mux Low-Latency CDN",
                        url = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
                        quality = "1080p HLS",
                        isM3u8 = true
                    ),
                    StreamSource(
                        name = "Direct MP4 Mirror",
                        url = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4",
                        quality = "1080p MP4",
                        isM3u8 = false
                    )
                )
            ),
            MediaCardItem(
                id = 1004L,
                title = "Cosmos Laundromat (Blender Open Movie)",
                subtitle = "High-Bitrate Direct Stream • Zero Disk Cache",
                badge = "HLS / MP4",
                providerName = "Vibe Core",
                streams = listOf(
                    StreamSource(
                        name = "Direct Fast Start",
                        url = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4",
                        quality = "1080p",
                        isM3u8 = false
                    )
                )
            ),
            MediaCardItem(
                id = 1005L,
                title = "Elephants Dream (Open Cinema HD)",
                subtitle = "Hardware Decoder Stress & Seek Test",
                badge = "DIRECT",
                providerName = "Vibe Core",
                streams = listOf(
                    StreamSource(
                        name = "Google Cloud CDN",
                        url = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ElephantsDream.mp4",
                        quality = "1080p",
                        isM3u8 = false
                    )
                )
            ),
            MediaCardItem(
                id = 1006L,
                title = "fMP4 Multi-Bitrate Live Simulation",
                subtitle = "Apple Authoring Spec fMP4 HLS Stream",
                badge = "LIVE HLS",
                providerName = "Vibe Core",
                streams = listOf(
                    StreamSource(
                        name = "Apple fMP4 HLS",
                        url = "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8",
                        quality = "Auto HLS",
                        isM3u8 = true
                    )
                )
            )
        )
    }

    companion object {
        private const val KEY_REPOS = "saved_repos_v1"
    }
}
