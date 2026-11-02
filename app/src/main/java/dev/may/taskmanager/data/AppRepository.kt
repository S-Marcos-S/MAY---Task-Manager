// SPDX-License-Identifier: GPL-3.0-or-later
package dev.may.taskmanager.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

class AppRepository(private val context: Context) {

    private val pm: PackageManager get() = context.packageManager

    fun queryAllApps(): List<AppInfo> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val resolveInfos = pm.queryIntentActivities(intent, 0)
        return resolveInfos
            .mapNotNull { ri ->
                val ai = ri.activityInfo ?: return@mapNotNull null
                AppInfo(
                    componentName = ComponentName(ai.packageName, ai.name),
                    label = ri.loadLabel(pm)?.toString() ?: ai.packageName,
                )
            }
            .distinctBy { it.key }
            .sortedBy { it.label.lowercase() }
    }
}
