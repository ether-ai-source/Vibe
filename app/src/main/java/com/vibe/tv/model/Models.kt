package com.vibe.tv.model

data class StreamSource(
    val name: String,
    val url: String,
    val quality: String = "1080p HD",
    val isM3u8: Boolean = url.contains(".m3u8", ignoreCase = true),
    val referer: String = "",
    val headers: Map<String, String> = emptyMap()
)

data class MediaCardItem(
    val id: Long,
    val title: String,
    val subtitle: String,
    val synopsis: String = "",
    val badge: String = "1080p",
    val category: String = "MOVIES", // "INDIAN", "MOVIES", "LIVE", "REPOS"
    val providerName: String = "Vibe",
    val posterUrl: String = "",
    val backdropUrl: String = "",
    val imdbId: String = "",
    val streams: List<StreamSource> = emptyList(),
    val pageOrPluginUrl: String = ""
)

data class RepoConfig(
    val name: String,
    val url: String,
    val repoType: String, // "MEGARepo", "CS3", "M3U"
    val description: String = "",
    val pluginCount: Int = 0
)

data class Cs3PluginMeta(
    val name: String,
    val internalName: String,
    val url: String,
    val iconUrl: String = "",
    val language: String = "HI / EN",
    val tvTypes: List<String> = emptyList(),
    val repositoryName: String,
    val description: String = "",
    val isInstalled: Boolean = true
)
