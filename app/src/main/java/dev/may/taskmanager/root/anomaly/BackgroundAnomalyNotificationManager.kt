// SPDX-License-Identifier: GPL-3.0-or-later
package dev.may.taskmanager.root.anomaly

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.core.app.NotificationCompat
import dev.may.taskmanager.MainActivity
import dev.may.taskmanager.R
import dev.may.taskmanager.telemetry.ConfidenceLevel
import dev.may.taskmanager.telemetry.TelemetryNature
import java.util.concurrent.ConcurrentHashMap

class BackgroundAnomalyNotificationManager private constructor(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "channel_background_anomaly"
        const val ACTION_FORCE_STOP = "dev.may.taskmanager.action.ANOMALY_FORCE_STOP"
        const val ACTION_MUTE_APP = "dev.may.taskmanager.action.ANOMALY_MUTE_APP"
        const val ACTION_INSPECT_APP = "dev.may.taskmanager.action.ANOMALY_INSPECT_APP"
        const val EXTRA_PACKAGE_NAME = "extra_package_name"
        const val EXTRA_APP_NAME = "extra_app_name"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"

        private const val MIN_NOTIFICATION_INTERVAL_MS = 60_000L

        @Volatile
        private var instance: BackgroundAnomalyNotificationManager? = null

        fun getInstance(context: Context): BackgroundAnomalyNotificationManager {
            return instance ?: synchronized(this) {
                instance ?: BackgroundAnomalyNotificationManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val recentAlertTimestamps = ConcurrentHashMap<String, Long>()

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.anomaly_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.anomaly_channel_desc)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 150, 80, 150)
                setShowBadge(true)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun postAnomalyNotification(anomaly: AnomalyEvent, appIcon: Drawable? = null) {
        val dedupKey = "${anomaly.packageName}_${anomaly.type.name}"
        val now = System.currentTimeMillis()
        val lastNotified = recentAlertTimestamps[dedupKey] ?: 0L
        if (now - lastNotified < MIN_NOTIFICATION_INTERVAL_MS) {
            return
        }
        recentAlertTimestamps[dedupKey] = now

        val notificationId = 7000 + (anomaly.packageName.hashCode() and 0x7FFF)

        // PendingIntent: Inspecionar (Abre a MainActivity e dispara abertura no Gerenciador de Tarefas)
        val inspectIntent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_INSPECT_APP
            putExtra(EXTRA_PACKAGE_NAME, anomaly.packageName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val inspectPendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            inspectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // PendingIntent: Forçar Parada
        val forceStopIntent = Intent(context, BackgroundAnomalyReceiver::class.java).apply {
            action = ACTION_FORCE_STOP
            putExtra(EXTRA_PACKAGE_NAME, anomaly.packageName)
            putExtra(EXTRA_APP_NAME, anomaly.appName)
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        }
        val forceStopPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId + 1,
            forceStopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // PendingIntent: Silenciar por 1 hora
        val muteIntent = Intent(context, BackgroundAnomalyReceiver::class.java).apply {
            action = ACTION_MUTE_APP
            putExtra(EXTRA_PACKAGE_NAME, anomaly.packageName)
            putExtra(EXTRA_APP_NAME, anomaly.appName)
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        }
        val mutePendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId + 2,
            muteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = when (anomaly.type) {
            AnomalyType.NETWORK -> context.getString(R.string.anomaly_notif_net_title, anomaly.appName)
            AnomalyType.CPU -> context.getString(R.string.anomaly_notif_cpu_title, anomaly.appName)
            AnomalyType.RAM -> context.getString(R.string.anomaly_notif_ram_title, anomaly.appName)
            AnomalyType.DEEP_SLEEP -> context.getString(R.string.anomaly_notif_deep_sleep_title, anomaly.appName)
        }

        val largeBitmap = appIcon?.let { drawableToBitmap(it) }

        val summaryLine = if (!anomaly.probableCause.isNullOrBlank()) {
            "${anomaly.valueFormatted} • ${anomaly.probableCause}"
        } else {
            anomaly.description
        }

        val technicalDescription = buildString {
            append(anomaly.description)
            append("\n\n")
            append("• ${context.getString(R.string.telemetry_notif_source)}: ${anomaly.source}")
            append("  |  ${context.getString(R.string.telemetry_notif_confidence)}: ${anomaly.confidence.getLabel(context)}")
            append("\n• ${context.getString(R.string.telemetry_notif_nature)}: ${anomaly.nature.getLabel(context)}")
            if (anomaly.uid != null) {
                append("  |  UID: ${anomaly.uid}")
            }
            if (anomaly.isEstimated) {
                append(" (${context.getString(R.string.telemetry_nature_estimate).lowercase()})")
            }
            if (!anomaly.reason.isNullOrBlank()) {
                append("\n• ${anomaly.reason}")
            }
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(summaryLine)
            .setStyle(NotificationCompat.BigTextStyle().bigText(technicalDescription))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setGroup("dev.may.taskmanager.group.ANOMALY")
            .setGroupSummary(false)
            .setAutoCancel(true)
            .setContentIntent(inspectPendingIntent)
            .addAction(
                0,
                context.getString(R.string.anomaly_action_force_stop),
                forceStopPendingIntent,
            )
            .addAction(
                0,
                context.getString(R.string.anomaly_action_inspect),
                inspectPendingIntent,
            )
            .addAction(
                0,
                context.getString(R.string.anomaly_action_mute_1h),
                mutePendingIntent,
            )

        if (largeBitmap != null) {
            builder.setLargeIcon(largeBitmap)
        }

        try {
            notificationManager.notify(notificationId, builder.build())
        } catch (_: SecurityException) {}
    }

    fun cancelNotification(notificationId: Int) {
        notificationManager.cancel(notificationId)
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap? {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return drawable.bitmap
        }
        val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 96
        val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 96
        return try {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bitmap
        } catch (_: Throwable) {
            null
        }
    }
}
