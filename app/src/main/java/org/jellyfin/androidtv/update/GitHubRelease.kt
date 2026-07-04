package org.jellyfin.androidtv.update

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class GitHubAsset(
    @Json(name = "name") val name: String = "",
    @Json(name = "browser_download_url") val downloadUrl: String = ""
)

@JsonClass(generateAdapter = true)
data class GitHubRelease(
    @Json(name = "tag_name") val tagName: String = "",
    @Json(name = "name") val name: String? = null,
    @Json(name = "body") val body: String? = null,
    @Json(name = "assets") val assets: List<GitHubAsset> = emptyList()
)
