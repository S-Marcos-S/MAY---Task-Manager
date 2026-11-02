// SPDX-License-Identifier: GPL-3.0-or-later
package dev.may.taskmanager.data

import android.content.ComponentName
import androidx.compose.runtime.Immutable

@Immutable
data class AppInfo(
    val componentName: ComponentName,
    val label: String,
) {
    val key: String = componentName.flattenToString()
    val packageName: String get() = componentName.packageName
}
