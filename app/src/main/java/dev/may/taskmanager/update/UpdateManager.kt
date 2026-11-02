// SPDX-License-Identifier: GPL-3.0-or-later
package dev.may.taskmanager.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import dev.may.taskmanager.BuildConfig
import dev.may.taskmanager.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

sealed class DownloadStatus {
    object Idle : DownloadStatus()
    data class Downloading(val progressPercent: Int) : DownloadStatus()
    data class Finished(val fileUri: Uri?) : DownloadStatus()
    object InstallingRoot : DownloadStatus()
    data class Failed(val error: String) : DownloadStatus()
}

data class UpdateInfo(
    val hasUpdate: Boolean,
    val tagName: String,
    val releaseName: String,
    val commitSha: String?,
    val versionName: String? = null,
    val changelog: String,
    val apkDownloadUrl: String,
    val apkSize: Long,
    val publishedAtMs: Long,
) {
    val displayVersion: String
        get() {
            val v = versionName?.trim()
            return when {
                !v.isNullOrBlank() -> if (v.startsWith("v", ignoreCase = true)) v else "v$v"
                tagName.isNotBlank() && !tagName.equals("latest", ignoreCase = true) -> {
                    if (tagName.startsWith("v", ignoreCase = true)) tagName else "v$tagName"
                }
                else -> "v${BuildConfig.VERSION_NAME}"
            }
        }
}

object UpdateManager {
    private const val UPDATE_NOTIFICATION_CHANNEL_ID = "may_task_manager_updates"
    private const val UPDATE_NOTIFICATION_ID = 5252
    private const val PREFS_NAME = "may_update_prefs"
    private const val KEY_DOWNLOADED_APK_URI = "downloaded_apk_uri"
    private const val KEY_DOWNLOADED_VERSION_CODE = "downloaded_version_code"
    private const val KEY_DOWNLOADED_VERSION_NAME = "downloaded_version_name"

    private const val GITHUB_REPO = "S-Marcos-S/MAY---Task-Manager"
    private const val RELEASES_API_URL = "https://api.github.com/repos/$GITHUB_REPO/releases?per_page=5"
    private const val FALLBACK_RELEASES_API_URL = "https://api.github.com/repos/$GITHUB_REPO/releases/tags/latest"

    private var appContext: Context? = null

    private val _updateAvailable = MutableStateFlow<UpdateInfo?>(null)
    val updateAvailable: StateFlow<UpdateInfo?> = _updateAvailable.asStateFlow()

    private val _isCheckingUpdates = MutableStateFlow(false)
    val isCheckingUpdates: StateFlow<Boolean> = _isCheckingUpdates.asStateFlow()

    private val _downloadStatus = MutableStateFlow<DownloadStatus>(DownloadStatus.Idle)
    val downloadStatus: StateFlow<DownloadStatus> = _downloadStatus.asStateFlow()

    private val updateScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var lastCheckTimeMs = 0L
    private const val CHECK_INTERVAL_MS = 10 * 60 * 1000L

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun checkForUpdates(context: Context, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastCheckTimeMs < CHECK_INTERVAL_MS) {
            return
        }
        if (_isCheckingUpdates.value) return

        lastCheckTimeMs = now
        appContext = context.applicationContext

        updateScope.launch {
            _isCheckingUpdates.value = true
            try {
                val update = queryGitHubReleases(context)
                _updateAvailable.value = update
            } catch (_: Throwable) {
                // Ignore network errors during background check
            } finally {
                _isCheckingUpdates.value = false
            }
        }
    }

    private suspend fun queryGitHubReleases(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        var jsonText = fetchUrl(RELEASES_API_URL)
        if (jsonText.isNullOrBlank()) {
            jsonText = fetchUrl(FALLBACK_RELEASES_API_URL)?.let { "[$it]" }
        }
        if (jsonText.isNullOrBlank()) return@withContext null

        val releasesArray = runCatching { org.json.JSONArray(jsonText) }.getOrNull() ?: return@withContext null
        if (releasesArray.length() == 0) return@withContext null

        val currentVersion = BuildConfig.VERSION_NAME

        for (i in 0 until releasesArray.length()) {
            val releaseObj = releasesArray.optJSONObject(i) ?: continue
            val tagName = releaseObj.optString("tag_name", "").trim()
            val releaseName = releaseObj.optString("name", "").trim()
            val body = releaseObj.optString("body", "").trim()

            val rawVersion = tagName.removePrefix("v").removePrefix("V").trim()
            val assets = releaseObj.optJSONArray("assets") ?: continue
            var apkUrl: String? = null
            var apkSize = 0L

            for (j in 0 until assets.length()) {
                val asset = assets.optJSONObject(j) ?: continue
                val name = asset.optString("name", "")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    apkUrl = asset.optString("browser_download_url", "")
                    apkSize = asset.optLong("size", 0L)
                    break
                }
            }

            if (apkUrl.isNullOrBlank()) continue

            val isNewer = isVersionGreater(rawVersion, currentVersion)
            if (isNewer) {
                return@withContext UpdateInfo(
                    hasUpdate = true,
                    tagName = tagName,
                    releaseName = releaseName.ifBlank { tagName },
                    commitSha = releaseObj.optString("target_commitish", null),
                    versionName = rawVersion,
                    changelog = body,
                    apkDownloadUrl = apkUrl,
                    apkSize = apkSize,
                    publishedAtMs = System.currentTimeMillis(),
                )
            }
        }
        null
    }

    private fun isVersionGreater(remote: String, local: String): Boolean {
        val rParts = remote.split(".").mapNotNull { it.toIntOrNull() }
        val lParts = local.split(".").mapNotNull { it.toIntOrNull() }
        val maxLen = maxOf(rParts.size, lParts.size)
        for (i in 0 until maxLen) {
            val r = rParts.getOrElse(i) { 0 }
            val l = lParts.getOrElse(i) { 0 }
            if (r > l) return true
            if (r < l) return false
        }
        return false
    }

    private fun fetchUrl(urlStr: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlStr)
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.setRequestProperty("User-Agent", "MAY-Task-Manager/${BuildConfig.VERSION_NAME}")
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else null
        } catch (_: Throwable) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    fun downloadUpdate(context: Context, updateInfo: UpdateInfo) {
        if (_downloadStatus.value is DownloadStatus.Downloading) return
        val appCtx = context.applicationContext

        updateScope.launch {
            _downloadStatus.value = DownloadStatus.Downloading(0)
            var input: InputStream? = null
            var output: OutputStream? = null
            var connection: HttpURLConnection? = null

            try {
                val url = URL(updateInfo.apkDownloadUrl)
                connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.instanceFollowRedirects = true
                connection.connect()

                if (connection.responseCode !in 200..299) {
                    throw RuntimeException("HTTP ${connection.responseCode}: ${connection.responseMessage}")
                }

                val fileLength = connection.contentLength.toLong()
                val cacheFile = File(appCtx.cacheDir, "may-task-manager-update.apk")
                if (cacheFile.exists()) cacheFile.delete()

                input = connection.inputStream
                output = cacheFile.outputStream()

                val data = ByteArray(8192)
                var total: Long = 0
                var count: Int
                var lastProgress = 0

                while (input.read(data).also { count = it } != -1) {
                    total += count.toLong()
                    output.write(data, 0, count)
                    if (fileLength > 0) {
                        val progress = (total * 100 / fileLength).toInt()
                        if (progress > lastProgress) {
                            lastProgress = progress
                            _downloadStatus.value = DownloadStatus.Downloading(progress)
                        }
                    }
                }
                output.flush()

                val uri = FileProvider.getUriForFile(
                    appCtx,
                    "${appCtx.packageName}.fileprovider",
                    cacheFile,
                )

                _downloadStatus.value = DownloadStatus.Finished(uri)

                val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit()
                    .putString(KEY_DOWNLOADED_APK_URI, uri.toString())
                    .putInt(KEY_DOWNLOADED_VERSION_CODE, BuildConfig.VERSION_CODE)
                    .putString(KEY_DOWNLOADED_VERSION_NAME, updateInfo.versionName)
                    .apply()

            } catch (e: Throwable) {
                _downloadStatus.value = DownloadStatus.Failed(e.message ?: "Download failed")
            } finally {
                runCatching { output?.close() }
                runCatching { input?.close() }
                connection?.disconnect()
            }
        }
    }

    fun installUpdate(context: Context, uri: Uri, useRoot: Boolean = false) {
        if (useRoot && RootInstaller.isRootAvailable()) {
            installViaRoot(context, uri)
        } else {
            promptInstall(context, uri)
        }
    }

    fun installViaRoot(context: Context, uri: Uri) {
        val appCtx = context.applicationContext
        _downloadStatus.value = DownloadStatus.InstallingRoot
        updateScope.launch {
            val result = RootInstaller.installApkViaRoot(appCtx, uri)
            withContext(Dispatchers.Main) {
                if (result.isSuccess) {
                    Toast.makeText(
                        appCtx,
                        appCtx.getString(R.string.update_toast_root_success),
                        Toast.LENGTH_LONG,
                    ).show()
                    cleanupDownloadedApk(appCtx, force = true)
                    _downloadStatus.value = DownloadStatus.Idle
                    _updateAvailable.value = null
                } else {
                    val error = result.exceptionOrNull()?.message ?: "Error"
                    Toast.makeText(
                        appCtx,
                        appCtx.getString(R.string.update_toast_root_failed, error),
                        Toast.LENGTH_LONG,
                    ).show()
                    _downloadStatus.value = DownloadStatus.Finished(uri)
                    promptInstall(appCtx, uri)
                }
            }
        }
    }

    fun promptInstall(context: Context, uri: Uri) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {}
    }

    fun cleanupDownloadedApk(context: Context, force: Boolean = false) {
        updateScope.launch(Dispatchers.IO) {
            try {
                val appCtx = context.applicationContext
                val cacheFile = File(appCtx.cacheDir, "may-task-manager-update.apk")
                if (cacheFile.exists()) cacheFile.delete()
                val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().clear().apply()
            } catch (_: Throwable) {}
        }
    }
}
