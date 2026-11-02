// SPDX-License-Identifier: GPL-3.0-or-later
package dev.may.taskmanager

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.may.taskmanager.data.AppInfo
import dev.may.taskmanager.root.TaskManagerEvents
import dev.may.taskmanager.root.anomaly.BackgroundAnomalyNotificationManager
import dev.may.taskmanager.root.log.CrashManager
import dev.may.taskmanager.root.log.LogViewerEvents
import dev.may.taskmanager.ui.root.TaskManagerScreen
import dev.may.taskmanager.ui.settings.SettingsScreen
import dev.may.taskmanager.ui.theme.MayTheme
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

            MayTheme(activeThemeId = activeThemeId) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Transparent,
                ) {
                    when (currentScreen) {
                        CurrentScreen.TASK_MANAGER -> {
                            TaskManagerScreen(
                                allApps = allApps,
                                onNavigateBack = { finish() },
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
