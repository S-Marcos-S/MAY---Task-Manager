// SPDX-License-Identifier: GPL-3.0-or-later
package dev.may.taskmanager.installer

import android.content.Context
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

data class ApkInfoParsed(
    val uri: Uri,
    val appName: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val icon: Drawable?,
    val sizeBytes: Long,
    val tempFile: File,
)

object PackageInstallerManager {

    suspend fun parseApk(context: Context, uri: Uri): Result<ApkInfoParsed> = withContext(Dispatchers.IO) {
        try {
            val tempFile = File(context.cacheDir, "installer_temp_${System.currentTimeMillis()}.apk")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext Result.failure(IllegalStateException("Could not open APK stream"))

            if (!tempFile.exists() || tempFile.length() <= 0L) {
                return@withContext Result.failure(IllegalStateException("APK file is empty or missing"))
            }

            val pm = context.packageManager
            val packageInfo = pm.getPackageArchiveInfo(tempFile.absolutePath, 0)
                ?: return@withContext Result.failure(IllegalStateException("Invalid APK package"))

            packageInfo.applicationInfo?.sourceDir = tempFile.absolutePath
            packageInfo.applicationInfo?.publicSourceDir = tempFile.absolutePath

            val appName = packageInfo.applicationInfo?.loadLabel(pm)?.toString() ?: packageInfo.packageName
            val icon = packageInfo.applicationInfo?.loadIcon(pm)
            val versionName = packageInfo.versionName ?: "1.0"
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }

            Result.success(
                ApkInfoParsed(
                    uri = uri,
                    appName = appName,
                    packageName = packageInfo.packageName,
                    versionName = versionName,
                    versionCode = versionCode,
                    icon = icon,
                    sizeBytes = tempFile.length(),
                    tempFile = tempFile,
                )
            )
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    suspend fun installApkViaRoot(tempFile: File): Result<Unit> = withContext(Dispatchers.IO) {
        val stagedPath = "/data/local/tmp/may_pkg_${System.currentTimeMillis()}.apk"
        val cachePath = tempFile.absolutePath

        val shellScript = """
            cp "$cachePath" "$stagedPath" && \
            chmod 644 "$stagedPath" && \
            pm install -r -d -g -t "$stagedPath"
            EXIT_CODE=$?
            rm -f "$stagedPath"
            exit ${'$'}EXIT_CODE
        """.trimIndent()

        try {
            val process = ProcessBuilder("su", "-c", shellScript)
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()

            if (exitCode == 0 && output.contains("Success", ignoreCase = true)) {
                Result.success(Unit)
            } else {
                val errorMsg = output.lines().filter { it.isNotBlank() }.joinToString("\n").ifBlank {
                    "Installation failed (exit code $exitCode)"
                }
                Result.failure(RuntimeException(errorMsg))
            }
        } catch (e: Throwable) {
            Result.failure(e)
        } finally {
            try {
                if (tempFile.exists()) tempFile.delete()
            } catch (_: Throwable) {}
        }
    }
}
