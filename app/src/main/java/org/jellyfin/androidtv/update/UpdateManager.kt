package org.jellyfin.androidtv.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jellyfin.androidtv.BuildConfig
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream

object UpdateManager {
    private const val GITHUB_RELEASES_URL = "https://api.github.com/repos/sudoaanish/pakflix/releases/latest"
    private val httpClient by lazy { OkHttpClient() }

    suspend fun checkForUpdates(context: Context, onUpdateAvailable: (release: GitHubRelease) -> Unit) {
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(GITHUB_RELEASES_URL)
                    .header("User-Agent", "PAKFLIX-AndroidTV")
                    .build()

                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) return@withContext

                val jsonString = response.body?.string() ?: return@withContext
                val jsonObject = JSONObject(jsonString)

                val tagName = jsonObject.optString("tag_name", "")
                val releaseName = jsonObject.optString("name", null)
                val releaseBody = jsonObject.optString("body", null)

                val assetsList = mutableListOf<GitHubAsset>()
                val assetsArray = jsonObject.optJSONArray("assets")
                if (assetsArray != null) {
                    for (i in 0 until assetsArray.length()) {
                        val assetObj = assetsArray.getJSONObject(i)
                        val assetName = assetObj.optString("name", "")
                        val downloadUrl = assetObj.optString("browser_download_url", "")
                        assetsList.add(GitHubAsset(name = assetName, downloadUrl = downloadUrl))
                    }
                }

                val release = GitHubRelease(
                    tagName = tagName,
                    name = releaseName,
                    body = releaseBody,
                    assets = assetsList
                )

                val latestVersion = release.tagName.removePrefix("v").trim()
                val currentVersion = BuildConfig.VERSION_NAME.removePrefix("v").trim()

                Timber.d("PAKFLIX Update Check - Current: $currentVersion, Latest: $latestVersion")

                if (isNewerVersion(currentVersion, latestVersion)) {
                    withContext(Dispatchers.Main) {
                        onUpdateAvailable(release)
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to check for PAKFLIX updates")
            }
        }
    }

    suspend fun downloadAndInstallUpdate(context: Context, downloadUrl: String, onProgress: (percent: Int) -> Unit) {
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(downloadUrl).build()
                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) return@withContext

                val body = response.body ?: return@withContext
                val contentLength = body.contentLength()

                val apkFile = File(context.cacheDir, "pakflix.apk")
                if (apkFile.exists()) apkFile.delete()

                body.byteStream().use { input ->
                    FileOutputStream(apkFile).use { output ->
                        val buffer = ByteArray(65536)
                        var downloaded = 0L
                        var lastReportedPercent = -1
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (contentLength > 0) {
                                val progress = ((downloaded * 100) / contentLength).toInt()
                                if (progress != lastReportedPercent) {
                                    lastReportedPercent = progress
                                    withContext(Dispatchers.Main) {
                                        onProgress(progress)
                                    }
                                }
                            }
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    installApk(context, apkFile)
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to download and install PAKFLIX update")
            }
        }
    }

    private fun installApk(context: Context, apkFile: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            apkFile
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }

        context.startActivity(intent)
    }

    private fun isNewerVersion(current: String, latest: String): Boolean {
        return try {
            val currentParts = current.split(".").map { it.toIntOrNull() ?: 0 }
            val latestParts = latest.split(".").map { it.toIntOrNull() ?: 0 }

            for (i in 0 until maxOf(currentParts.size, latestParts.size)) {
                val cur = currentParts.getOrElse(i) { 0 }
                val lat = latestParts.getOrElse(i) { 0 }
                if (lat > cur) return true
                if (lat < cur) return false
            }
            false
        } catch (e: Exception) {
            false
        }
    }
}
