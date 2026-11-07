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
 * Pre-installed with CloudStream's MegaRepo, Phisher Indian Repo, CNCVerse Indian Repo,
 * CSX Hindi/English Repo, LikDev South Indian Repo, and India Live TV HD.
 * Also supports expanding MegaRepo (`repos-db.json`) and browsing extensions inside any repo.
 */
class RepoManager(context: Context) {

    private val prefs = context.getSharedPreferences("vibe_repos_v3", Context.MODE_PRIVATE)

    fun getSavedRepos(): List<RepoConfig> {
        val rawJson = prefs.getString(KEY_REPOS, null) ?: return getPreinstalledCloudStreamRepos()
        return try {
            val arr = JSONArray(rawJson)
            val list = mutableListOf<RepoConfig>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    RepoConfig(
                        name = o.optString("name", "Repository"),
                        url = o.optString("url"),
                        repoType = o.optString("repoType", "CS3"),
                        description = o.optString("description", ""),
                        pluginCount = o.optInt("pluginCount", 0)
                    )
                )
            }
            if (list.isEmpty()) getPreinstalledCloudStreamRepos() else list
        } catch (_: Throwable) {
            getPreinstalledCloudStreamRepos()
        }
    }

    fun saveRepos(repos: List<RepoConfig>) {
        val arr = JSONArray()
        for (r in repos) {
            val o = JSONObject()
            o.put("name", r.name)
            o.put("url", r.url)
            o.put("repoType", r.repoType)
            o.put("description", r.description)
            o.put("pluginCount", r.pluginCount)
            arr.put(o)
        }
        prefs.edit().putString(KEY_REPOS, arr.toString()).apply()
    }

    fun isPluginInstalled(pluginUrl: String): Boolean {
        val disabled = prefs.getStringSet(KEY_DISABLED_PLUGINS, emptySet()) ?: emptySet()
        return !disabled.contains(pluginUrl)
    }

    fun togglePluginInstalled(pluginUrl: String): Boolean {
        val current = (prefs.getStringSet(KEY_DISABLED_PLUGINS, emptySet()) ?: emptySet()).toMutableSet()
        val nowInstalled = if (current.contains(pluginUrl)) {
            current.remove(pluginUrl)
            true
        } else {
            current.add(pluginUrl)
            false
        }
        prefs.edit().putStringSet(KEY_DISABLED_PLUGINS, current).apply()
        return nowInstalled
    }

    /**
     * Adds a repository URL or shortcode (`megarepo`, `phisherrepo`, `cnc`, `csx`, `likdev`).
     * If `megarepo` or `repos-db.json` is added, automatically unpacks and installs all sub-repositories!
     */
    fun addRepositoryUrl(rawInputUrl: String, customName: String = ""): RepoConfig {
        val normalized = NativeEngine.normalizeUrl(rawInputUrl)
        if (normalized.isEmpty()) {
            throw IllegalArgumentException("Invalid repository link or shortcode")
        }

        val current = getSavedRepos().toMutableList()

        // Special handling for MegaRepo / repos-db.json: unpack all sub-repositories inside it!
        if (normalized.endsWith("repos-db.json", ignoreCase = true) ||
            rawInputUrl.trim().equals("megarepo", ignoreCase = true) ||
            normalized.contains("MegaRepo/builds/repo.json", ignoreCase = true)
        ) {
            val unpacked = unpackMegaRepoSubRepositories()
            for (sub in unpacked) {
                if (current.none { it.url.equals(sub.url, ignoreCase = true) }) {
                    current.add(sub)
                }
            }
            saveRepos(current)
            return RepoConfig(
                name = "MegaRepo (All Sub-Repos Installed)",
                url = normalized,
                repoType = "MEGARepo",
                description = "Unpacked ${unpacked.size} CloudStream repositories",
                pluginCount = unpacked.size
            )
        }

        val inspected = inspectRemoteRepository(normalized, customName)
        val existingIdx = current.indexOfFirst { it.url.equals(normalized, ignoreCase = true) }
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
     * Fetches all extensions/plugins inside a specific repository so the user can browse
     * and install/enable Indian & global extensions inside that repo.
     */
    fun fetchExtensionsInsideRepo(repo: RepoConfig): List<Cs3PluginMeta> {
        if (repo.url.startsWith("builtin://")) return emptyList()
        return try {
            val body = LowLatencyHttp.fetchText(repo.url).trim()
            when {
                repo.url.endsWith("repos-db.json", true) -> {
                    unpackMegaRepoSubRepositories().map { sub ->
                        Cs3PluginMeta(
                            name = sub.name,
                            internalName = sub.name,
                            url = sub.url,
                            language = "MULTI / IN",
                            tvTypes = listOf("Sub-Repository"),
                            repositoryName = "MegaRepo",
                            description = sub.description.ifEmpty { sub.url },
                            isInstalled = getSavedRepos().any { it.url.equals(sub.url, true) }
                        )
                    }
                }
                body.startsWith("{") -> {
                    val obj = JSONObject(body)
                    val repoTitle = obj.optString("name", repo.name)
                    val lists = obj.optJSONArray("pluginLists") ?: JSONArray()
                    val allPlugins = mutableListOf<Cs3PluginMeta>()
                    for (i in 0 until lists.length()) {
                        val listUrl = lists.optString(i)
                        if (listUrl.isNotEmpty()) {
                            allPlugins.addAll(fetchCs3PluginsList(listUrl, repoTitle))
                        }
                    }
                    allPlugins
                }
                body.startsWith("[") -> {
                    parseCs3PluginArray(JSONArray(body), repo.name)
                }
                else -> emptyList()
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun unpackMegaRepoSubRepositories(): List<RepoConfig> {
        val results = mutableListOf<RepoConfig>()
        try {
            val raw = LowLatencyHttp.fetchText("https://raw.githubusercontent.com/recloudstream/cs-repos/master/repos-db.json")
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val entry = arr.get(i)
                val repoUrl = when (entry) {
                    is JSONObject -> entry.optString("url")
                    is String -> entry
                    else -> ""
                }
                if (repoUrl.startsWith("http")) {
                    val shortName = repoUrl
                        .removePrefix("https://raw.githubusercontent.com/")
                        .removePrefix("https://")
                        .substringBefore("/refs/")
                        .substringBefore("/builds/")
                        .substringBefore("/master/")
                    results.add(
                        RepoConfig(
                            name = shortName,
                            url = repoUrl,
                            repoType = "CloudStream 3",
                            description = "Installed via MegaRepo",
                            pluginCount = 20
                        )
                    )
                }
            }
        } catch (_: Throwable) {
        }
        return results
    }

    private fun inspectRemoteRepository(url: String, customName: String = ""): RepoConfig {
        return try {
            val body = LowLatencyHttp.fetchText(url).trim()
            when {
                body.startsWith("#EXTM3U", ignoreCase = true) || url.endsWith(".m3u", true) || url.endsWith(".m3u8", true) -> {
                    val items = ExtractorEngine.parseM3uPlaylist(body, customName.ifEmpty { "Live TV HD" })
                    RepoConfig(
                        name = customName.ifEmpty {
                            url.substringAfterLast('/').substringBefore('?').ifEmpty { "Live TV Playlist" }
                        },
                        url = url,
                        repoType = "M3U Live / VOD",
                        description = "Direct HD Channels & Streams",
                        pluginCount = items.size
                    )
                }
                body.startsWith("{") -> {
                    val obj = JSONObject(body)
                    if (obj.has("pluginLists")) {
                        val name = customName.ifEmpty { obj.optString("name", "CloudStream Repo") }
                        val desc = obj.optString("description", "CloudStream 3 Extension Repository")
                        val lists = obj.optJSONArray("pluginLists") ?: JSONArray()
                        var totalPlugins = 0
                        for (i in 0 until lists.length()) {
                            val pluginListUrl = lists.optString(i)
                            if (pluginListUrl.isNotEmpty()) {
                                totalPlugins += fetchCs3PluginsList(pluginListUrl, name).size
                            }
                        }
                        RepoConfig(
                            name = name,
                            url = url,
                            repoType = "CloudStream 3",
                            description = desc,
                            pluginCount = totalPlugins
                        )
                    } else {
                        val repoTitle = customName.ifEmpty { obj.optString("name", "Stream Catalog") }
                        val items = ExtractorEngine.parseVibeJsonCatalog(body, repoTitle)
                        RepoConfig(
                            name = repoTitle,
                            url = url,
                            repoType = "JSON Catalog",
                            description = "Direct Stream Index",
                            pluginCount = items.size
                        )
                    }
                }
                body.startsWith("[") -> {
                    val arr = JSONArray(body)
                    RepoConfig(
                        name = customName.ifEmpty { "CloudStream Plugins" },
                        url = url,
                        repoType = "CloudStream 3",
                        description = "Direct Plugin List",
                        pluginCount = arr.length()
                    )
                }
                else -> {
                    RepoConfig(
                        name = customName.ifEmpty { url.substringAfter("://").substringBefore('/') },
                        url = url,
                        repoType = "Web Source",
                        pluginCount = 1
                    )
                }
            }
        } catch (_: Throwable) {
            RepoConfig(
                name = customName.ifEmpty { url.substringAfterLast('/').ifEmpty { "Added Repo" } },
                url = url,
                repoType = "CloudStream 3",
                pluginCount = 0
            )
        }
    }

    fun fetchAllCatalogItems(): List<MediaCardItem> {
        val allItems = mutableListOf<MediaCardItem>()

        // 1. Curated 1080p/4K Cinema, Indian Releases & Live HD Channels with official posters
        allItems.addAll(getCuratedNetflixHomeCatalog())

        // 2. Fetch from pre-installed & user-added repositories (Indian Live TV, Phisher, CNCVerse, CSX, etc.)
        for (repo in getSavedRepos().take(6)) {
            if (repo.url.startsWith("builtin://") || repo.url.endsWith("repos-db.json")) continue
            try {
                val body = LowLatencyHttp.fetchText(repo.url).trim()
                when {
                    body.startsWith("#EXTM3U", ignoreCase = true) -> {
                        allItems.addAll(ExtractorEngine.parseM3uPlaylist(body, repo.name, maxItems = 90))
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
                                    allItems.addAll(
                                        plugins.filter { it.isInstalled }
                                            .take(45)
                                            .map { it.toMediaCardItem() }
                                    )
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
                            allItems.addAll(plugins.filter { it.isInstalled }.take(45).map { it.toMediaCardItem() })
                        } else {
                            allItems.addAll(ExtractorEngine.parseVibeJsonCatalog(body, repo.name))
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

            val rawIcon = o.optString("iconUrl").replace("%size%", "128")
            val pluginUrl = o.optString("url")
            val lang = o.optString("language", "hi").uppercase()

            list.add(
                Cs3PluginMeta(
                    name = o.optString("name", o.optString("internalName", "Provider")),
                    internalName = o.optString("internalName", "Provider"),
                    url = pluginUrl,
                    iconUrl = rawIcon,
                    language = lang,
                    tvTypes = types,
                    repositoryName = repoName,
                    description = o.optString(
                        "description",
                        types.joinToString(" • ").ifEmpty { "CloudStream Provider" }
                    ),
                    isInstalled = isPluginInstalled(pluginUrl)
                )
            )
        }
        return list
    }

    private fun Cs3PluginMeta.toMediaCardItem(): MediaCardItem {
        val isIndianLang = language in setOf("HI", "TA", "TE", "ML", "KN", "BN", "MR", "PA") ||
            repositoryName.contains("Phisher", true) ||
            repositoryName.contains("CNC", true) ||
            repositoryName.contains("CSX", true) ||
            repositoryName.contains("Tamil", true)

        return MediaCardItem(
            id = NativeEngine.computeIdHash("$repositoryName:$internalName:$url"),
            title = name,
            subtitle = "$language • ${tvTypes.take(2).joinToString(", ").ifEmpty { "Movies & Series" }}",
            synopsis = "$description\nRepository: $repositoryName ($language)",
            badge = language.ifEmpty { "CS3" },
            category = if (isIndianLang) "INDIAN" else "REPOS",
            providerName = repositoryName,
            posterUrl = iconUrl,
            backdropUrl = iconUrl,
            streams = emptyList(),
            pageOrPluginUrl = url
        )
    }

    /**
     * Pre-installed with the top Indian & Global CloudStream repositories + India Live TV HD!
     */
    private fun getPreinstalledCloudStreamRepos(): List<RepoConfig> {
        return listOf(
            RepoConfig(
                name = "Phisher Repo (India: Hindi, Tamil, Telugu, Malayalam)",
                url = "https://raw.githubusercontent.com/phisher98/cloudstream-extensions-phisher/refs/heads/builds/repo.json",
                repoType = "CloudStream 3 (India)",
                description = "CineStream, Bollyflix, MovieRulzHD, VegaMovies, Jio/Indian OTT & Regional Providers",
                pluginCount = 85
            ),
            RepoConfig(
                name = "CNCVerse Repo (Indian Releases, South & Live TV)",
                url = "https://raw.githubusercontent.com/NivinCNC/CNCVerse-Cloud-Stream-Extension/refs/heads/builds/CNC.json",
                repoType = "CloudStream 3 (India)",
                description = "Indian Movies, Tamil/Telugu/Malayalam Releases & Live Sports",
                pluginCount = 40
            ),
            RepoConfig(
                name = "Megix / CSX Repo (Hindi & English HD)",
                url = "https://raw.githubusercontent.com/SaurabhKaperwan/CSX/builds/CS.json",
                repoType = "CloudStream 3 (India)",
                description = "Bollyflix, CineStream, MoviesDrive, MoviesMod & VegaMovies",
                pluginCount = 25
            ),
            RepoConfig(
                name = "LikDev South Indian Repo (Tamil / Telugu / Malayalam)",
                url = "https://codeberg.org/JakeWasHere7/likdev256-tamil-providers/raw/branch/builds/repo.json",
                repoType = "CloudStream 3 (South India)",
                description = "Dedicated Tamil, Telugu, Malayalam & Kannada Providers",
                pluginCount = 20
            ),
            RepoConfig(
                name = "India Live TV HD (Hindi, Tamil, Telugu, Sports, News)",
                url = "https://iptv-org.github.io/iptv/countries/in.m3u",
                repoType = "Live TV (India)",
                description = "200+ Live Indian Television Channels in Direct HLS HD",
                pluginCount = 180
            ),
            RepoConfig(
                name = "MegaRepo Master Index (All CloudStream Repos)",
                url = "https://raw.githubusercontent.com/recloudstream/cs-repos/master/repos-db.json",
                repoType = "MEGARepo",
                description = "Click 'Browse / Install' to view and add any global CloudStream repository",
                pluginCount = 27
            )
        )
    }

    private fun getCuratedNetflixHomeCatalog(): List<MediaCardItem> {
        return listOf(
            MediaCardItem(
                id = 9001L,
                title = "Tears of Steel",
                subtitle = "2012 • Sci-Fi Action • 4K / 1080p",
                synopsis = "In a dystopian future Amsterdam, a group of warriors and scientists gather at the Oude Kerk to stage a desperate last stand against sentient machines.",
                badge = "4K UHD",
                category = "MOVIES",
                providerName = "Vibe Cinema 4K",
                posterUrl = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/images/TearsOfSteel.jpg",
                backdropUrl = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/images/TearsOfSteel.jpg",
                streams = listOf(
                    StreamSource(
                        name = "Master Adaptive 1080p/4K",
                        url = "https://demo.unified-streaming.com/k8s/features/stable/video/tears-of-steel/tears-of-steel.ism/.m3u8",
                        quality = "1080p FHD",
                        isM3u8 = true
                    ),
                    StreamSource(
                        name = "Direct High-Bitrate MP4",
                        url = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/TearsOfSteel.mp4",
                        quality = "1080p MP4",
                        isM3u8 = false
                    )
                )
            ),
            MediaCardItem(
                id = 9002L,
                title = "Sintel",
                subtitle = "2010 • Fantasy Adventure • Dolby Multi-Audio",
                synopsis = "A lonely young woman, Sintel, helps and befriends a dragon, whom she calls Scales. When Scales is kidnapped by an adult dragon, Sintel embarks on a perilous quest.",
                badge = "1080p FHD",
                category = "MOVIES",
                providerName = "Vibe Cinema 4K",
                posterUrl = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/images/Sintel.jpg",
                backdropUrl = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/images/Sintel.jpg",
                streams = listOf(
                    StreamSource(
                        name = "Bitmovin 1080p Master HLS",
                        url = "https://bitdash-a.akamaihd.net/content/sintel/hls/playlist.m3u8",
                        quality = "1080p FHD",
                        isM3u8 = true
                    ),
                    StreamSource(
                        name = "Direct Fast MP4",
                        url = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/Sintel.mp4",
                        quality = "1080p MP4",
                        isM3u8 = false
                    )
                )
            ),
            MediaCardItem(
                id = 9003L,
                title = "Big Buck Bunny",
                subtitle = "60fps High Frame Rate • Family Comedy",
                synopsis = "A giant, gentle rabbit deals with three bullying rodents in this ultra-crisp 60fps high-bitrate showcase.",
                badge = "1080p 60FPS",
                category = "MOVIES",
                providerName = "Vibe Cinema 4K",
                posterUrl = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/images/BigBuckBunny.jpg",
                backdropUrl = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/images/BigBuckBunny.jpg",
                streams = listOf(
                    StreamSource(
                        name = "Mux 1080p 60fps HLS",
                        url = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
                        quality = "1080p 60fps",
                        isM3u8 = true
                    ),
                    StreamSource(
                        name = "Direct 1080p MP4",
                        url = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4",
                        quality = "1080p MP4",
                        isM3u8 = false
                    )
                )
            ),
            MediaCardItem(
                id = 9004L,
                title = "DD India HD Live",
                subtitle = "India National & International News • 24/7 Live",
                synopsis = "Watch live Indian national broadcasts, current affairs, and special coverage in high definition.",
                badge = "LIVE HD",
                category = "INDIAN",
                providerName = "India Live TV",
                posterUrl = "https://upload.wikimedia.org/wikipedia/en/thumb/1/12/DD_India_logo.svg/512px-DD_India_logo.svg.png",
                backdropUrl = "https://upload.wikimedia.org/wikipedia/en/thumb/1/12/DD_India_logo.svg/512px-DD_India_logo.svg.png",
                streams = listOf(
                    StreamSource(
                        name = "Live HLS Stream",
                        url = "https://dwamdstream102.akamaized.net/hls/live/2015525/dwstream102/index.m3u8",
                        quality = "1080p Live",
                        isM3u8 = true
                    )
                )
            ),
            MediaCardItem(
                id = 9005L,
                title = "Red Bull TV 4K Live",
                subtitle = "Live Sports, Rally, Cricket & Adventure",
                synopsis = "24/7 global live sports events, motorsports, cliff diving, and high-adrenaline documentaries in Full HD.",
                badge = "LIVE 1080p",
                category = "LIVE",
                providerName = "Live Sports HD",
                posterUrl = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/images/ForBiggerBlazes.jpg",
                backdropUrl = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/images/ForBiggerBlazes.jpg",
                streams = listOf(
                    StreamSource(
                        name = "Akamai 1080p Master HLS",
                        url = "https://rbmn-live.akamaized.net/hls/live/590964/BoRB-AT/master.m3u8",
                        quality = "1080p Live",
                        isM3u8 = true
                    )
                )
            ),
            MediaCardItem(
                id = 9006L,
                title = "Elephants Dream",
                subtitle = "Sci-Fi Surreal Feature • 1080p Remaster",
                synopsis = "Two strange characters explore a capricious and seemingly infinite machine in the world's first open-source 3D animated film.",
                badge = "1080p HD",
                category = "MOVIES",
                providerName = "Vibe Cinema 4K",
                posterUrl = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/images/ElephantsDream.jpg",
                backdropUrl = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/images/ElephantsDream.jpg",
                streams = listOf(
                    StreamSource(
                        name = "Direct 1080p HD",
                        url = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ElephantsDream.mp4",
                        quality = "1080p HD",
                        isM3u8 = false
                    )
                )
            )
        )
    }

    companion object {
        private const val KEY_REPOS = "saved_repos_v3"
        private const val KEY_DISABLED_PLUGINS = "disabled_plugins_v3"
    }
}
