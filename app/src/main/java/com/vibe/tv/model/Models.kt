package com.vibe.tv.model

data class StreamSource(
    val name: String,
    val url: String,
    val quality: String = "Auto",
    val isM3u8: Boolean = url.contains(".m3u8", ignoreCase = true),
    val referer: String = "",
    val headers: Map<String, String> = emptyMap()
)

data class MediaCardItem(
    val id: Long,
    val title: String,
    val subtitle: String,
    val badge: String,
    val providerName: String,
    val streams: List<StreamSource>,
    val pageOrPluginUrl: String = ""
)

data class RepoConfig(
    val name: String,
    val url: String,
    val repoType: String, // "CS3", "M3U", "VIBE_JSON"
    val pluginCount: Int = 0
)

data class Cs3PluginMeta(
    val name: String,
    val internalName: String,
    val url: String,
    val tvTypes: List<String>,
    val repositoryName: String,
    val description: String = ""
)
