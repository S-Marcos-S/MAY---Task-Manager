// SPDX-License-Identifier: GPL-3.0-or-later
package dev.may.taskmanager.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object RootInstaller {

    private val SU_PATHS = arrayOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/system/sd/xbin/su",
        "/system/bin/failsafe/su",
        "/data/local/xbin/su",
        "/data/local/bin/su",
        "/data/local/su",
        "/apex/com.android.runtime/bin/su",
    )

    fun isRootAvailable(): Boolean {
        try {
            for (p in SU_PATHS) {
                if (File(p).exists()) return true
            }
            val pathEnv = System.getenv("PATH") ?: ""
            for (dir in pathEnv.split(":")) {
                if (dir.isNotBlank() && File(dir.trim(), "su").exists()) {
                    return true
                }
            }
        } catch (_: Throwable) {}
        return false
    }

    suspend fun installApkViaRoot(
        context: Context,
        apkUri: Uri,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val cacheFile = File(context.cacheDir, "may_update_temp.apk")
        try {
            context.contentResolver.openInputStream(apkUri)?.use { input ->
                cacheFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext Result.failure(IllegalStateException("Failed to read update APK."))

            if (!cacheFile.exists() || cacheFile.length() <= 0) {
                return@withContext Result.failure(IllegalStateException("Update APK file is empty or invalid."))
            }

            val stagedApkPath = "/data/local/tmp/may_update_${System.currentTimeMillis()}.apk"
            val cachePath = cacheFile.absolutePath

            val shellScript = """
                cp "$cachePath" "$stagedApkPath" && \
                chmod 644 "$stagedApkPath" && \
                pm install -r -d -g -t "$stagedApkPath"
                EXIT_CODE=$?
                rm -f "$stagedApkPath"
                exit ${'$'}EXIT_CODE
            """.trimIndent()

            val process = ProcessBuilder("su", "-c", shellScript)
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()

            if (exitCode == 0 && output.contains("Success", ignoreCase = true)) {
                try {
                    val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                    if (launchIntent != null) {
                        context.startActivity(launchIntent)
                    }
                } catch (_: Throwable) {}

                Result.success(Unit)
            } else {
                val errorMsg = output.lines().filter { it.isNotBlank() }.joinToString("\n").ifBlank {
                    "Error executing pm install via su (exit code $exitCode)"
                }
                Result.failure(RuntimeException(errorMsg))
            }
        } catch (e: Throwable) {
            Result.failure(e)
        } finally {
            try {
                if (cacheFile.exists()) {
                    cacheFile.delete()
                }
            } catch (_: Throwable) {}
        }
    }
}
