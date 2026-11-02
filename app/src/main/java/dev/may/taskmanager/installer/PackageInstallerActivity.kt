// SPDX-License-Identifier: GPL-3.0-or-later
package dev.may.taskmanager.installer

import android.content.Context
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import dev.may.taskmanager.ui.theme.MayTheme
import dev.may.taskmanager.ui.theme.glass.WallpaperGlassProvider

class PackageInstallerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val uri: Uri? = intent.data ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }

        if (uri == null) {
            finish()
            return
        }

        val prefs = getSharedPreferences("may_task_manager_prefs", Context.MODE_PRIVATE)
        val activeThemeId = prefs.getString("active_theme_id", "system_dynamic") ?: "system_dynamic"
        val isGlass = activeThemeId == "glass"

        if (isGlass) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                val params = window.attributes
                params.blurBehindRadius = 45
                window.attributes = params
            }
            window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            WallpaperGlassProvider.refreshWallpaper(this)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            }
            window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
        }

        setContent {
            MayTheme(themeId = activeThemeId) {
                Box(modifier = Modifier.fillMaxSize()) {
                    PackageInstallDialog(
                        apkUri = uri,
                        onDismiss = { finish() }
                    )
                }
            }
        }
    }
}
