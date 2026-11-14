// SPDX-License-Identifier: GPL-3.0-or-later
package dev.may.taskmanager

import android.app.Application
import android.app.NotificationManager
import dev.may.taskmanager.battery.AdvancedDrainTracker
import dev.may.taskmanager.battery.TransparencyNotificationManager
import dev.may.taskmanager.data.AppRepository
import dev.may.taskmanager.network.NetSpeedSettings
import dev.may.taskmanager.network.SpeedMonitorService
import dev.may.taskmanager.root.anomaly.BackgroundAnomalyWatcher
import dev.may.taskmanager.ui.theme.glass.WallpaperGlassProvider
import dev.may.taskmanager.update.UpdateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MayApp : Application() {

    override fun onCreate() {
        super.onCreate()
        WallpaperGlassProvider.init(this)
        UpdateManager.init(this)
        BackgroundAnomalyWatcher.getInstance(this).start()
        AdvancedDrainTracker.getInstance(this).start()
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as? NotificationManager
        notificationManager?.cancel(2001)
        TransparencyNotificationManager.init(this)
        if (TransparencyNotificationManager.isNotificationEnabled(this)) {
            TransparencyNotificationManager.getInstance(this).scheduleNextAlarm()
        }
        if (NetSpeedSettings(this).isEnabled) {
            SpeedMonitorService.start(this)
        }
        CoroutineScope(Dispatchers.IO).launch {
            AppRepository(this@MayApp).queryAllApps()
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        BackgroundAnomalyWatcher.getInstance(this).onSystemTrimMemory(level)
    }
}
