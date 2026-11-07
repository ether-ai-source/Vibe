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
                        name = o.optString("name", "Added Source"),
                        url = o.optString("url"),
                        repoType = o.optString("repoType", "M3U"),
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

    fun addRepositoryUrl(rawInputUrl: String, customName: String = ""): RepoConfig {
        val normalized = NativeEngine.normalizeUrl(rawInputUrl)
        if (normalized.isEmpty()) {
            throw IllegalArgumentException("Please enter a valid link")
        }

        val current = getSavedRepos().toMutableList()
        val existingIdx = current.indexOfFirst { it.url.equals(normalized, ignoreCase = true) }

        val inspected = inspectRemoteRepository(normalized, customName)
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

    private fun inspectRemoteRepository(url: String, customName: String = ""): RepoConfig {
        return try {
            val body = LowLatencyHttp.fetchText(url).trim()
            when {
                body.startsWith("#EXTM3U", ignoreCase = true) || url.endsWith(".m3u", true) || url.endsWith(".m3u8", true) -> {
                    val items = ExtractorEngine.parseM3uPlaylist(body, customName.ifEmpty { "Live TV Source" })
                    RepoConfig(
                        name = customName.ifEmpty {
                            url.substringAfterLast('/').substringBefore('?').ifEmpty { "Live TV Playlist" }
                        },
                        url = url,
                        repoType = "Live TV / M3U",
                        pluginCount = items.size
                    )
                }
                body.startsWith("{") -> {
                    val obj = JSONObject(body)
                    if (obj.has("pluginLists")) {
                        val name = customName.ifEmpty { obj.optString("name", "CloudStream 3 Repo") }
                        val lists = obj.optJSONArray("pluginLists") ?: JSONArray()
                        var totalPlugins = 0
                        for (i in 0 until lists.length()) {
                            val pluginListUrl = lists.optString(i)
                            if (pluginListUrl.isNotEmpty()) {
                                totalPlugins += fetchCs3PluginsList(pluginListUrl, name).size
                            }
                        }
                        RepoConfig(name = name, url = url, repoType = "CloudStream 3", pluginCount = totalPlugins)
                    } else {
                        val repoTitle = customName.ifEmpty { obj.optString("name", "Stream Catalog") }
                        val items = ExtractorEngine.parseVibeJsonCatalog(body, repoTitle)
                        RepoConfig(
                            name = repoTitle,
                            url = url,
                            repoType = "Stream Catalog",
                            pluginCount = items.size
                        )
                    }
                }
                body.startsWith("[") -> {
                    val arr = JSONArray(body)
                    val first = arr.optJSONObject(0)
                    if (first != null && (first.has("internalName") || first.has("apiVersion"))) {
                        RepoConfig(
                            name = customName.ifEmpty { "CloudStream 3 Plugins" },
                            url = url,
                            repoType = "CloudStream 3",
                            pluginCount = arr.length()
                        )
                    } else {
                        RepoConfig(
                            name = customName.ifEmpty { "Stream Catalog" },
                            url = url,
                            repoType = "Stream Catalog",
                            pluginCount = arr.length()
                        )
                    }
                }
                else -> {
                    RepoConfig(
                        name = customName.ifEmpty { url.substringAfter("://").substringBefore('/') },
                        url = url,
                        repoType = "Direct Web Source",
                        pluginCount = 1
                    )
                }
            }
        } catch (_: Throwable) {
            RepoConfig(
                name = customName.ifEmpty { url.substringAfterLast('/').ifEmpty { "Added Source" } },
                url = url,
                repoType = "External Source",
                pluginCount = 0
            )
        }
    }

    fun fetchAllCatalogItems(): List<MediaCardItem> {
        val allItems = mutableListOf<MediaCardItem>()

        // 1. Built-in movies, shows, and live channels ready to watch immediately
        allItems.addAll(getBuiltInCuratedStreams())

        // 2. Fetch from added repositories
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
                                    title = "${repo.name} #${idx + 1}",
                                    subtitle = "Press OK to Watch",
                                    badge = "STREAM",
                                    category = "MOVIES",
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
            if (status == 0) continue

            val tvTypesArr = o.optJSONArray("tvTypes")
            val types = mutableListOf<String>()
            if (tvTypesArr != null) {
                for (j in 0 until tvTypesArr.length()) {
                    types.add(tvTypesArr.optString(j))
                }
            }

            list.add(
                Cs3PluginMeta(
                    name = o.optString("name", o.optString("internalName", "Source")),
                    internalName = o.optString("internalName", "Source"),
                    url = o.optString("url"),
                    tvTypes = types,
                    repositoryName = repoName,
                    description = o.optString("description", types.joinToString(", ").ifEmpty { "CloudStream 3 Source" })
                )
            )
        }
        return list
    }

    private fun Cs3PluginMeta.toMediaCardItem(): MediaCardItem {
        val badgeText = tvTypes.firstOrNull()?.uppercase()?.take(8) ?: "SOURCE"
        return MediaCardItem(
            id = NativeEngine.computeIdHash("$repositoryName:$internalName:$url"),
            title = name,
            subtitle = description.ifEmpty { "From $repositoryName" },
            badge = badgeText,
            category = "REPOS",
            providerName = repositoryName,
            streams = emptyList(),
            pageOrPluginUrl = url
        )
    }

    private fun getDefaultStarterRepos(): List<RepoConfig> {
        return listOf(
            RepoConfig(
                name = "Vibe Built-In Movies & Live TV",
                url = "builtin://vibe-core-streams",
                repoType = "Built-In",
                pluginCount = 9
            )
        )
    }

    private fun getBuiltInCuratedStreams(): List<MediaCardItem> {
        return listOf(
            MediaCardItem(
                id = 1001L,
                title = "Tears of Steel (Sci-Fi Movie)",
                subtitle = "Full HD Sci-Fi Short Film • Instant Start",
                badge = "MOVIE",
                category = "MOVIES",
                providerName = "Ready to Play",
                streams = listOf(
                    StreamSource(
                        name = "Adaptive HD",
                        url = "https://demo.unified-streaming.com/k8s/features/stable/video/tears-of-steel/tears-of-steel.ism/.m3u8",
                        quality = "1080p",
                        isM3u8 = true
                    ),
                    StreamSource(
                        name = "Backup Mirror",
                        url = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/TearsOfSteel.mp4",
                        quality = "1080p",
                        isM3u8 = false
                    )
                )
            ),
            MediaCardItem(
                id = 1002L,
                title = "Sintel (Fantasy Adventure Movie)",
                subtitle = "Award-Winning Animated Feature • Multi-Audio HD",
                badge = "MOVIE",
                category = "MOVIES",
                providerName = "Ready to Play",
                streams = listOf(
                    StreamSource(
                        name = "Primary HD",
                        url = "https://bitdash-a.akamaihd.net/content/sintel/hls/playlist.m3u8",
                        quality = "1080p",
                        isM3u8 = true
                    ),
                    StreamSource(
                        name = "Backup Mirror",
                        url = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/Sintel.mp4",
                        quality = "720p",
                        isM3u8 = false
                    )
                )
            ),
            MediaCardItem(
                id = 1003L,
                title = "Big Buck Bunny (Family Comedy)",
                subtitle = "60fps Full HD • Family & Kids",
                badge = "MOVIE",
                category = "MOVIES",
                providerName = "Ready to Play",
                streams = listOf(
                    StreamSource(
                        name = "Primary 60fps HD",
                        url = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
                        quality = "1080p",
                        isM3u8 = true
                    ),
                    StreamSource(
                        name = "Backup Mirror",
                        url = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4",
                        quality = "1080p",
                        isM3u8 = false
                    )
                )
            ),
            MediaCardItem(
                id = 1004L,
                title = "Red Bull TV (Live Sports & Adventure)",
                subtitle = "24/7 Live Action, Motorsports & Outdoor Events",
                badge = "LIVE TV",
                category = "LIVE",
                providerName = "Live Channel",
                streams = listOf(
                    StreamSource(
                        name = "Red Bull Live HD",
                        url = "https://rbmn-live.akamaized.net/hls/live/590964/BoRB-AT/master.m3u8",
                        quality = "1080p Live",
                        isM3u8 = true
                    )
                )
            ),
            MediaCardItem(
                id = 1005L,
                title = "NASA TV Public (Live Space Station)",
                subtitle = "24/7 Live Earth Views & Space Missions",
                badge = "LIVE TV",
                category = "LIVE",
                providerName = "Live Channel",
                streams = listOf(
                    StreamSource(
                        name = "NASA Official Live",
                        url = "https://ntv1.akamaized.net/hls/live/2014075/NASA-NTV1-HLS/master.m3u8",
                        quality = "HD Live",
                        isM3u8 = true
                    )
                )
            ),
            MediaCardItem(
                id = 1006L,
                title = "DW English (24/7 World News Live)",
                subtitle = "International Live News & Documentaries",
                badge = "LIVE TV",
                category = "LIVE",
                providerName = "Live Channel",
                streams = listOf(
                    StreamSource(
                        name = "DW Live HD",
                        url = "https://dwamdstream102.akamaized.net/hls/live/2015525/dwstream102/index.m3u8",
                        quality = "HD Live",
                        isM3u8 = true
                    )
                )
            ),
            MediaCardItem(
                id = 1007L,
                title = "Cosmos Laundromat (Comedy / Drama)",
                subtitle = "Blender Open Cinema • High Definition",
                badge = "MOVIE",
                category = "MOVIES",
                providerName = "Ready to Play",
                streams = listOf(
                    StreamSource(
                        name = "Direct HD",
                        url = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4",
                        quality = "1080p",
                        isM3u8 = false
                    )
                )
            ),
            MediaCardItem(
                id = 1008L,
                title = "Elephants Dream (Sci-Fi Classic)",
                subtitle = "Remastered Open Movie • Instant Playback",
                badge = "MOVIE",
                category = "MOVIES",
                providerName = "Ready to Play",
                streams = listOf(
                    StreamSource(
                        name = "Direct HD",
                        url = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ElephantsDream.mp4",
                        quality = "1080p",
                        isM3u8 = false
                    )
                )
            ),
            MediaCardItem(
                id = 1009L,
                title = "We Are Going On Bullrun (Documentary)",
                subtitle = "Automotive Rally Documentary Special",
                badge = "MOVIE",
                category = "MOVIES",
                providerName = "Ready to Play",
                streams = listOf(
                    StreamSource(
                        name = "Direct HD",
                        url = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/WeAreGoingOnBullrun.mp4",
                        quality = "720p",
                        isM3u8 = false
                    )
                )
            )
        )
    }

    companion object {
        private const val KEY_REPOS = "saved_repos_v2"
        const val PRESET_IPTV_ENG_URL = "https://iptv-org.github.io/iptv/languages/eng.m3u"
        const val PRESET_IPTV_MOVIES_URL = "https://iptv-org.github.io/iptv/categories/movies.m3u"
    }
}
