// SPDX-License-Identifier: GPL-3.0-or-later
package dev.may.taskmanager

import android.app.Application
import dev.may.taskmanager.battery.AdvancedDrainTracker
import dev.may.taskmanager.root.anomaly.BackgroundAnomalyWatcher
import dev.may.taskmanager.ui.theme.glass.WallpaperGlassProvider
import dev.may.taskmanager.update.UpdateManager

class MayApp : Application() {

    override fun onCreate() {
        super.onCreate()
        WallpaperGlassProvider.init(this)
        UpdateManager.init(this)
        BackgroundAnomalyWatcher.getInstance(this).start()
        AdvancedDrainTracker.getInstance(this).start()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        BackgroundAnomalyWatcher.getInstance(this).onSystemTrimMemory(level)
    }
}
