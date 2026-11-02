// SPDX-License-Identifier: GPL-3.0-or-later
package dev.may.taskmanager

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import dev.may.taskmanager.data.AppInfo
import dev.may.taskmanager.root.TaskManagerEvents
import dev.may.taskmanager.root.anomaly.BackgroundAnomalyNotificationManager
import dev.may.taskmanager.root.log.CrashManager
import dev.may.taskmanager.root.log.LogViewerEvents
import dev.may.taskmanager.ui.root.TaskManagerScreen
import dev.may.taskmanager.ui.settings.SettingsScreen
import dev.may.taskmanager.ui.theme.MayTheme
import dev.may.taskmanager.ui.theme.glass.WallpaperGlassProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

private const val PREFS_NAME = "may_task_manager_prefs"
private const val KEY_ACTIVE_THEME = "active_theme_id"

private enum class CurrentScreen {
    TASK_MANAGER,
    SETTINGS,
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        handleIncomingIntent(intent)

        setContent {
            val prefs = remember { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
            var activeThemeId by remember {
                mutableStateOf(prefs.getString(KEY_ACTIVE_THEME, "system_dynamic") ?: "system_dynamic")
            }
            var currentScreen by remember { mutableStateOf(CurrentScreen.TASK_MANAGER) }
            var allApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }

            LaunchedEffect(Unit) {
                withContext(Dispatchers.IO) {
                    val apps = loadInstalledApps(this@MainActivity)
                    withContext(Dispatchers.Main) {
                        allApps = apps
                    }
                }
            }

            DisposableEffect(activeThemeId) {
                val window = this@MainActivity.window
                val isGlass = activeThemeId == "glass"
                val isOled = activeThemeId == "oled_black"
                if (isGlass) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                        val params = window.attributes
                        params.blurBehindRadius = 45
                        window.attributes = params
                    }
                    window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
                    WallpaperGlassProvider.refreshWallpaper(this@MainActivity)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                    }
                    if (isOled) {
                        window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.BLACK))
                    } else {
                        window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.BLACK))
                    }
                }
                onDispose { }
            }

            MayTheme(activeThemeId = activeThemeId) {
                val isGlassTheme = activeThemeId == "glass"
                val isOledTheme = activeThemeId == "oled_black"
                val isDark = isSystemInDarkTheme()
                val wallpaperBitmap by WallpaperGlassProvider.currentWallpaperFlow.collectAsState()

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            when {
                                isGlassTheme -> Color.Transparent
                                isOledTheme -> Color.Black
                                else -> MaterialTheme.colorScheme.background
                            }
                        ),
                ) {
                    if (isGlassTheme) {
                        if (wallpaperBitmap != null) {
                            Image(
                                bitmap = wallpaperBitmap!!.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    if (isDark) Color.Black.copy(alpha = 0.42f)
                                    else Color.White.copy(alpha = 0.30f)
                                ),
                        )
                    }

                    when (currentScreen) {
                        CurrentScreen.TASK_MANAGER -> {
                            TaskManagerScreen(
                                allApps = allApps,
                                onOpenSettings = { currentScreen = CurrentScreen.SETTINGS },
                            )
                        }
                        CurrentScreen.SETTINGS -> {
                            BackHandler {
                                currentScreen = CurrentScreen.TASK_MANAGER
                            }
                            SettingsScreen(
                                activeThemeId = activeThemeId,
                                onSelectTheme = { themeId ->
                                    activeThemeId = themeId
                                    prefs.edit().putString(KEY_ACTIVE_THEME, themeId).apply()
                                    if (themeId == "glass") {
                                        WallpaperGlassProvider.refreshWallpaper(this@MainActivity)
                                    }
                                },
                                onNavigateBack = {
                                    currentScreen = CurrentScreen.TASK_MANAGER
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        WallpaperGlassProvider.refreshWallpaper(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return

        if (intent.action == BackgroundAnomalyNotificationManager.ACTION_INSPECT_APP) {
            val pkg = intent.getStringExtra(BackgroundAnomalyNotificationManager.EXTRA_PACKAGE_NAME)
            if (!pkg.isNullOrBlank()) {
                TaskManagerEvents.open(pkg)
            }
        }

        val crashPkg = intent.getStringExtra(CrashManager.EXTRA_CRASH_PACKAGE)
        if (!crashPkg.isNullOrBlank()) {
            LogViewerEvents.open(crashPkg, "crashes")
        }
    }

    private fun loadInstalledApps(context: Context): List<AppInfo> {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
        return resolveInfos.mapNotNull { ri ->
            val ai = ri.activityInfo ?: return@mapNotNull null
            val componentName = ComponentName(ai.packageName, ai.name)
            val label = ri.loadLabel(pm).toString().ifBlank { ai.packageName }
            AppInfo(componentName = componentName, label = label)
        }.sortedBy { it.label.lowercase(Locale.ROOT) }
    }
}
