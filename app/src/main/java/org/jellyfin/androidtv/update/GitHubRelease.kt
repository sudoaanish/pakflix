package org.jellyfin.androidtv.update

data class GitHubAsset(
    val name: String = "",
    val downloadUrl: String = ""
)

data class GitHubRelease(
    val tagName: String = "",
    val name: String? = null,
    val body: String? = null,
    val assets: List<GitHubAsset> = emptyList()
)
