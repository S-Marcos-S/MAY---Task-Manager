// SPDX-License-Identifier: GPL-3.0-or-later
package dev.may.taskmanager.battery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class TransparencyAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.Default).launch {
            try {
                val manager = TransparencyNotificationManager.getInstance(context)
                when (action) {
                    ACTION_TRIGGER_NOTIFICATION -> {
                        manager.deliverDailyNotification()
                        manager.scheduleNextAlarm()
                    }
                    Intent.ACTION_BOOT_COMPLETED,
                    Intent.ACTION_MY_PACKAGE_REPLACED -> {
                        if (TransparencyNotificationManager.isNotificationEnabled(context)) {
                            manager.scheduleNextAlarm()
                        }
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_TRIGGER_NOTIFICATION = "dev.may.taskmanager.action.TRIGGER_TRANSPARENCY_NOTIFICATION"
    }
}
