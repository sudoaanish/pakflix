package org.jellyfin.androidtv.update

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import java.security.MessageDigest

object UpdateManager {
    private const val GITHUB_RELEASES_URL = "https://api.github.com/repos/sudoaanish/pakflix/releases/latest"
    private const val EXPECTED_RELEASE_SIGNER_SHA256 = "359dde3161d91c47a020d2ed40a2b2a223272ac5cfc23cba8d35636759830c51"
    private const val MAX_RELEASE_NOTES_LENGTH = 1000
    private const val MESSAGE_NO_UPDATE = "No update available."
    private const val MESSAGE_CHECK_FAILED = "GitHub latest release check failed."
    private const val MESSAGE_NO_VALID_ASSET = "No valid Pakflix APK asset found."
    private const val MESSAGE_DOWNLOAD_FAILED = "APK download failed."
    private const val MESSAGE_APK_INVALID = "Downloaded update APK could not be read."
    private const val MESSAGE_WRONG_PACKAGE = "Downloaded update is not a Pakflix APK."
    private const val MESSAGE_NOT_NEWER = "Downloaded update is not newer than the installed app."
    private const val MESSAGE_SIGNER_UNREADABLE = "Downloaded update signing certificate could not be read."
    private const val MESSAGE_WRONG_SIGNER = "Downloaded update is signed with the wrong certificate and was blocked."
    private const val MESSAGE_INSTALL_PERMISSION_MISSING = "Pakflix does not have permission to install updates."
    private const val MESSAGE_INSTALLER_FAILED = "Package installer could not be opened."
    private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    private val httpClient by lazy { OkHttpClient() }

    data class UpdateInstallResult(
        val installerLaunched: Boolean,
        val message: String? = null
    )

    suspend fun checkForUpdates(
        context: Context,
        onUpdateAvailable: (release: GitHubRelease) -> Unit,
        onNoUpdate: (message: String) -> Unit = {},
        onCheckFailed: (message: String) -> Unit = {}
    ) {
        withContext(Dispatchers.IO) {
            try {
                Timber.i("Pakflix update check started")
                val request = Request.Builder()
                    .url(GITHUB_RELEASES_URL)
                    .header("User-Agent", "PAKFLIX-AndroidTV")
                    .build()

                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) throw UpdateException(MESSAGE_CHECK_FAILED)

                val jsonString = response.body?.string() ?: throw UpdateException(MESSAGE_CHECK_FAILED)
                val jsonObject = JSONObject(jsonString)

                val tagName = jsonObject.optString("tag_name", "")
                val releaseName = jsonObject.optString("name").takeIf { it.isNotBlank() }
                val releaseBody = jsonObject.optString("body").takeIf { it.isNotBlank() }
                Timber.i("Pakflix latest release tag found: %s", tagName)

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
                    val selectedAsset = selectReleaseAsset(release)
                    val selectedRelease = release.copy(selectedAsset = selectedAsset)
                    withContext(Dispatchers.Main) {
                        onUpdateAvailable(selectedRelease)
                    }
                } else {
                    Timber.i("Pakflix update check finished: %s", MESSAGE_NO_UPDATE)
                    withContext(Dispatchers.Main) {
                        onNoUpdate(MESSAGE_NO_UPDATE)
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to check for PAKFLIX updates")
                withContext(Dispatchers.Main) {
                    onCheckFailed(e.message ?: MESSAGE_CHECK_FAILED)
                }
            }
        }
    }

    suspend fun downloadAndInstallUpdate(
        context: Context,
        asset: GitHubAsset,
        onProgress: (percent: Int) -> Unit,
        onResult: (result: UpdateInstallResult) -> Unit
    ) {
        withContext(Dispatchers.IO) {
            try {
                Timber.i("Pakflix update download started: %s", asset.name)
                val request = Request.Builder()
                    .url(asset.downloadUrl)
                    .header("User-Agent", "PAKFLIX-AndroidTV")
                    .build()
                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) throw UpdateException(MESSAGE_DOWNLOAD_FAILED)

                val body = response.body ?: throw UpdateException(MESSAGE_DOWNLOAD_FAILED)
                val contentLength = body.contentLength()

                val downloadDir = context.externalCacheDir ?: context.cacheDir
                val apkFile = File(downloadDir, "pakflix.apk")
                if (apkFile.exists()) apkFile.delete()

                body.byteStream().use { input ->
                    FileOutputStream(apkFile).use { output ->
                        val buffer = ByteArray(65536)
                        var downloaded = 0L
                        var read: Int
                        var lastReportedProgress = -1
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (contentLength > 0) {
                                val progress = ((downloaded * 100) / contentLength).toInt()
                                if (progress != lastReportedProgress) {
                                    lastReportedProgress = progress
                                    withContext(Dispatchers.Main) {
                                        onProgress(progress)
                                    }
                                }
                            }
                        }
                    }
                }

                Timber.i("Pakflix update download finished: %s (%d bytes)", apkFile.absolutePath, apkFile.length())
                validateDownloadedApk(context, apkFile)
                withContext(Dispatchers.Main) {
                    installApk(context, apkFile)
                    onResult(UpdateInstallResult(installerLaunched = true))
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to download and install PAKFLIX update")
                withContext(Dispatchers.Main) {
                    onResult(UpdateInstallResult(installerLaunched = false, message = e.message ?: MESSAGE_DOWNLOAD_FAILED))
                }
            }
        }
    }

    private fun installApk(context: Context, apkFile: File) {
        val packageManager = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canRequestPackageInstalls = packageManager.canRequestPackageInstalls()
            Timber.i("Pakflix installer permission: canRequestPackageInstalls=%s", canRequestPackageInstalls)
            if (!canRequestPackageInstalls) {
                Timber.e("Pakflix update blocked: %s", MESSAGE_INSTALL_PERMISSION_MISSING)
                openUnknownSourcesSettings(context)
                throw UpdateException(MESSAGE_INSTALL_PERMISSION_MISSING)
            }
        }

        val apkUri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            apkFile
        )

        val installIntent = buildInstallIntent(context, apkUri)
        val viewIntent = buildViewInstallIntent(context, apkUri)

        try {
            launchInstallerIntent(context, installIntent, "ACTION_INSTALL_PACKAGE", apkUri)
            return
        } catch (e: ActivityNotFoundException) {
            Timber.w(e, "Pakflix ACTION_INSTALL_PACKAGE handoff failed; falling back to ACTION_VIEW")
        } catch (e: SecurityException) {
            Timber.w(e, "Pakflix ACTION_INSTALL_PACKAGE handoff security failure; falling back to ACTION_VIEW")
        }

        try {
            launchInstallerIntent(context, viewIntent, "ACTION_VIEW", apkUri)
        } catch (e: ActivityNotFoundException) {
            throw UpdateException(MESSAGE_INSTALLER_FAILED, e)
        } catch (e: SecurityException) {
            throw UpdateException(MESSAGE_INSTALLER_FAILED, e)
        }
    }

    private fun buildInstallIntent(context: Context, apkUri: Uri): Intent {
        return Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(apkUri, APK_MIME_TYPE)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            clipData = ClipData.newUri(context.contentResolver, "Pakflix update", apkUri)
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
        }
    }

    private fun buildViewInstallIntent(context: Context, apkUri: Uri): Intent {
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, APK_MIME_TYPE)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            clipData = ClipData.newUri(context.contentResolver, "Pakflix update", apkUri)
        }
    }

    private fun launchInstallerIntent(context: Context, intent: Intent, strategy: String, apkUri: Uri) {
        val candidates = context.packageManager.queryInstallerCandidates(intent)
        Timber.i(
            "Pakflix installer handoff prepared: strategy=%s action=%s mime=%s uriScheme=%s flags=0x%s clipData=%s candidates=%s",
            strategy,
            intent.action,
            intent.type,
            apkUri.scheme,
            intent.flags.toString(16),
            intent.clipData != null,
            candidates.joinToString(",") { it.label }.ifBlank { "none" }
        )

        candidates.map { it.packageName }.distinct().forEach { packageName ->
            context.grantUriPermission(packageName, apkUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(intent)
        Timber.i("Pakflix package installer intent launched: strategy=%s", strategy)
    }

    @Suppress("DEPRECATION")
    private fun PackageManager.queryInstallerCandidates(intent: Intent): List<InstallerCandidate> {
        return queryIntentActivities(intent, 0)
            .mapNotNull { resolveInfo ->
                val activityInfo = resolveInfo.activityInfo ?: return@mapNotNull null
                InstallerCandidate(
                    packageName = activityInfo.packageName,
                    activityName = activityInfo.name
                )
            }
    }

    private fun openUnknownSourcesSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${context.packageName}")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        try {
            context.startActivity(settingsIntent)
            Timber.i("Pakflix opened unknown app sources settings")
        } catch (e: ActivityNotFoundException) {
            Timber.w(e, "Pakflix could not open unknown app sources settings")
        }
    }

    fun buildUpdatePromptMessage(release: GitHubRelease): String {
        val versionLabel = release.tagName.ifBlank { release.name ?: "new version" }
        val notes = release.body.orEmpty().toPlainReleaseNotes()
        return if (notes.isBlank()) {
            "A new version ($versionLabel) of PAKFLIX is available."
        } else {
            "A new version ($versionLabel) of PAKFLIX is available.\n\nRelease notes:\n\n$notes"
        }
    }

    private fun selectReleaseAsset(release: GitHubRelease): GitHubAsset {
        val releaseVersion = release.tagName.removePrefix("v").trim()
        val pakflixApks = release.assets.filter { asset ->
            val name = asset.name.lowercase()
            name.endsWith(".apk") &&
                name.contains("pakflix") &&
                !name.contains("debug") &&
                asset.downloadUrl.isNotBlank()
        }

        val versionedAsset = pakflixApks.firstOrNull { asset ->
            asset.name.lowercase().contains(releaseVersion.lowercase())
        }

        if (versionedAsset != null) {
            Timber.i("Pakflix update asset selected: %s", versionedAsset.name)
            return versionedAsset
        }

        if (pakflixApks.size == 1) {
            Timber.w("Pakflix update using single non-debug APK asset without exact version match: %s", pakflixApks.single().name)
            return pakflixApks.single()
        }

        Timber.e("Pakflix update asset selection failed for tag %s. Assets: %s", release.tagName, release.assets.map { it.name })
        throw UpdateException(MESSAGE_NO_VALID_ASSET)
    }

    private fun validateDownloadedApk(context: Context, apkFile: File) {
        val packageManager = context.packageManager
        val downloadedPackage = packageManager.getPackageArchiveInfoCompat(apkFile)
            ?: throw UpdateException(MESSAGE_APK_INVALID)

        val downloadedVersionCode = downloadedPackage.versionCodeCompat()
        val installedVersionCode = packageManager
            .getPackageInfo(context.packageName, 0)
            .versionCodeCompat()
        val signerResult = packageManager.readArchiveSignerSha256s(apkFile)
        val signerSha256s = signerResult.sha256s

        Timber.i(
            "Pakflix downloaded APK metadata: file=%s bytes=%d package=%s versionCode=%d signerCount=%d signerSource=%s signerSha256=%s",
            apkFile.name,
            apkFile.length(),
            downloadedPackage.packageName,
            downloadedVersionCode,
            signerSha256s.size,
            signerResult.source,
            signerSha256s.joinToString(",")
        )

        if (downloadedPackage.packageName != context.packageName) {
            Timber.e("Pakflix update validation failed: %s", MESSAGE_WRONG_PACKAGE)
            throw UpdateException(MESSAGE_WRONG_PACKAGE)
        }

        if (downloadedVersionCode <= installedVersionCode) {
            Timber.e(
                "Pakflix update validation failed: %s downloaded=%d installed=%d",
                MESSAGE_NOT_NEWER,
                downloadedVersionCode,
                installedVersionCode
            )
            throw UpdateException(MESSAGE_NOT_NEWER)
        }

        if (signerSha256s.isEmpty()) {
            Timber.e("Pakflix update validation failed: %s", MESSAGE_SIGNER_UNREADABLE)
            throw UpdateException(MESSAGE_SIGNER_UNREADABLE)
        }

        if (EXPECTED_RELEASE_SIGNER_SHA256 !in signerSha256s) {
            Timber.e(
                "Pakflix update validation failed: %s expected=%s found=%s",
                MESSAGE_WRONG_SIGNER,
                EXPECTED_RELEASE_SIGNER_SHA256,
                signerSha256s.joinToString(",")
            )
            throw UpdateException(MESSAGE_WRONG_SIGNER)
        }

        Timber.i("Pakflix downloaded APK validation passed")
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

    @Suppress("DEPRECATION")
    private fun PackageManager.getPackageArchiveInfoCompat(apkFile: File): PackageInfo? {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        return getPackageArchiveInfo(apkFile.absolutePath, flags)
    }

    @Suppress("DEPRECATION")
    private fun PackageManager.readArchiveSignerSha256s(apkFile: File): SignerReadResult {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val modernPackageInfo = getPackageArchiveInfo(
                apkFile.absolutePath,
                PackageManager.GET_SIGNING_CERTIFICATES
            )
            val modernSignatures = modernPackageInfo?.signingInfo?.let { signingInfo ->
                if (signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners
                } else {
                    signingInfo.signingCertificateHistory
                }
            }?.filterNotNull().orEmpty()

            Timber.i("Pakflix APK signer read: source=modern signerCount=%d", modernSignatures.size)

            if (modernSignatures.isNotEmpty()) {
                return SignerReadResult(
                    sha256s = modernSignatures.sha256s(),
                    source = "modern"
                )
            }

            Timber.w("Pakflix APK signer read returned no modern signers; retrying legacy archive signatures")
        }

        val legacyPackageInfo = getPackageArchiveInfo(
            apkFile.absolutePath,
            PackageManager.GET_SIGNATURES
        )
        val legacySignatures = legacyPackageInfo?.signatures?.filterNotNull().orEmpty()

        Timber.i("Pakflix APK signer read: source=legacy signerCount=%d", legacySignatures.size)

        return SignerReadResult(
            sha256s = legacySignatures.sha256s(),
            source = if (legacySignatures.isEmpty()) "none" else "legacy"
        )
    }

    @Suppress("DEPRECATION")
    private fun PackageInfo.versionCodeCompat(): Long {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            longVersionCode
        } else {
            versionCode.toLong()
        }
    }

    private fun Iterable<Signature>.sha256s(): List<String> {
        return map { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
    }

    private fun String.toPlainReleaseNotes(): String {
        return replace("\r\n", "\n")
            .lines()
            .map { line ->
                line.trim()
                    .removePrefix("#")
                    .removePrefix("#")
                    .removePrefix("#")
                    .removePrefix("-")
                    .removePrefix("*")
                    .removePrefix(">")
                    .trim()
                    .replace("`", "")
                    .replace("**", "")
            }
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .take(MAX_RELEASE_NOTES_LENGTH)
    }

    private data class SignerReadResult(
        val sha256s: List<String>,
        val source: String
    )

    private data class InstallerCandidate(
        val packageName: String,
        val activityName: String
    ) {
        val label: String = "$packageName/$activityName"
    }

    private class UpdateException(message: String, cause: Throwable? = null) : Exception(message, cause)
}
