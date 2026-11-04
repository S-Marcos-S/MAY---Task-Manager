// SPDX-License-Identifier: GPL-3.0-or-later
package dev.may.taskmanager.battery

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import androidx.core.app.NotificationCompat
import dev.may.taskmanager.MainActivity
import dev.may.taskmanager.R
import dev.may.taskmanager.root.RootBatteryStatsCollector
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * Manages the real-time persistent notification for battery drain statistics.
 */
class DrainNotificationManager private constructor(
    private val context: Context,
    private val drainTracker: AdvancedDrainTracker = AdvancedDrainTracker.getInstance(context),
    private val collector: DetailedStatsCollector = DetailedStatsCollector.getInstance(context),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
) {
    companion object {
        const val CHANNEL_ID = "drain_stats_channel"
        const val NOTIFICATION_ID = 2001
        const val ACTION_OPEN_BATTERY_STATS = "dev.may.taskmanager.action.OPEN_BATTERY_STATS"
        const val EXTRA_OPEN_BATTERY_STATS = "open_battery_stats"
        private const val PREF_KEY_ENABLED = "pref_battery_drain_notification_enabled"

        @Volatile
        private var instance: DrainNotificationManager? = null

        fun getInstance(context: Context): DrainNotificationManager {
            return instance ?: synchronized(this) {
                instance ?: DrainNotificationManager(context.applicationContext).also { instance = it }
            }
        }

        fun isNotificationEnabled(context: Context): Boolean {
            val prefs = context.getSharedPreferences("battery_prefs", Context.MODE_PRIVATE)
            return prefs.getBoolean(PREF_KEY_ENABLED, false)
        }

        fun setNotificationEnabled(context: Context, enabled: Boolean) {
            val prefs = context.getSharedPreferences("battery_prefs", Context.MODE_PRIVATE)
            prefs.edit().putBoolean(PREF_KEY_ENABLED, enabled).apply()
            val manager = getInstance(context)
            if (enabled) {
                manager.startNotification()
            } else {
                manager.stopNotification()
            }
        }
    }

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private var updateJob: Job? = null
    private var isShowing = false

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.drain_statistics),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.drain_notification_channel_desc)
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun startNotification() {
        if (isShowing) return
        isShowing = true

        if (!drainTracker.isRunning()) {
            drainTracker.start()
        }

        if (Build.VERSION.SDK_INT >= 33) {
            if (context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                scope.launch {
                    if (RootBatteryStatsCollector.isRootAvailable()) {
                        RootBatteryStatsCollector.runAsRoot("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
                    }
                }
            }
        }

        scope.launch(Dispatchers.Default) {
            if (ShellRunner.hasDumpPermission(context) || RootBatteryStatsCollector.isRootAvailable()) {
                collector.refresh()
            }
        }
        
        updateNow()

        updateJob = scope.launch {
            combine(drainTracker.drainState, collector.snapshot) { state, snapshot ->
                Pair(state, snapshot)
            }.collectLatest { (state, snapshot) ->
                if (isShowing) {
                    try {
                        notificationManager.notify(NOTIFICATION_ID, buildNotification(state, snapshot))
                    } catch (e: SecurityException) {}
                }
            }
        }
    }

    fun stopNotification() {
        isShowing = false
        updateJob?.cancel()
        updateJob = null
        notificationManager.cancel(NOTIFICATION_ID)
        if (drainTracker.isRunning()) {
            drainTracker.stop()
        }
    }

    fun updateNow() {
        if (!isShowing && isNotificationEnabled(context)) {
            startNotification()
            return
        }
        if (isShowing) {
            scope.launch(Dispatchers.Default) {
                if (ShellRunner.hasDumpPermission(context) || RootBatteryStatsCollector.isRootAvailable()) {
                    collector.refresh()
                }
            }
            val state = drainTracker.drainState.value
            val snapshot = collector.snapshot.value
            try {
                notificationManager.notify(NOTIFICATION_ID, buildNotification(state, snapshot))
            } catch (e: SecurityException) {}
        }
    }

    private fun String.removeEmojis(): String {
        return this.replace(Regex("[\\uD83C-\\uDBFF\\uDC00-\\uDFFF\\u2600-\\u27BF]"), "")
            .replace(Regex("[⚡⏳━]"), "")
            .trim()
            .replace(Regex("\\s{2,}"), " ")
    }

    private fun buildNotification(
        state: DrainState,
        snapshot: BatteryStatsParser.FullSnapshot? = collector.snapshot.value
    ): Notification {
        val contentIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).apply {
                action = ACTION_OPEN_BATTERY_STATS
                putExtra(EXTRA_OPEN_BATTERY_STATS, true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val resetIntent = PendingIntent.getBroadcast(
            context,
            1,
            Intent(context, DrainNotificationReceiver::class.java).apply {
                action = DrainNotificationReceiver.ACTION_RESET
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val prefs = context.getSharedPreferences("battery_prefs", Context.MODE_PRIVATE)
        val showRates = prefs.getBoolean("notif_show_rates", true)
        val showDeepSleep = prefs.getBoolean("notif_show_deep_sleep", true)
        val showActiveIdle = prefs.getBoolean("notif_show_active_idle", true)
        val showTotalAvg = prefs.getBoolean("notif_show_total_avg", true)
        val showTemp = prefs.getBoolean("notif_show_temperature", true)
        val showNetwork = prefs.getBoolean("notif_show_network", true)

        val currentTemp = state.batteryTemperatureC ?: drainTracker.getBatteryTemperatureC()
        val tempText = if (showTemp && currentTemp != null) "${Math.round(currentTemp)}°" else null

        val (finalTitle, finalContentText, finalBigText) = if (state.isCharging) {
            val cycleRepo = BatteryCycleRepository.getInstance(context)
            val activeChargeCycle = cycleRepo.cyclesFlow.value.firstOrNull { it.isActive && it.type == CycleType.CHARGE }

            val battIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val voltageMv = battIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 4000) ?: 4000
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

            var currentMicro = runCatching {
                bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)?.toLong()
            }.getOrNull()

            if (currentMicro == null || currentMicro == 0L || currentMicro == Long.MIN_VALUE || currentMicro == Int.MIN_VALUE.toLong()) {
                currentMicro = runCatching {
                    File("/sys/class/power_supply/battery/current_now").readText().trim().toLongOrNull()
                }.getOrNull()
            }

            val currentMa = if (currentMicro != null && currentMicro != 0L) {
                if (abs(currentMicro) > 10_000L) abs(currentMicro) / 1000L else abs(currentMicro)
            } else {
                activeChargeCycle?.telemetryPoints?.lastOrNull()?.let { (it.currentA * 1000L).toLong() } ?: 2000L
            }

            val voltageV = voltageMv / 1000.0f
            val currentA = currentMa / 1000.0f
            val powerWatts = currentA * voltageV

            val screenOffPoints = activeChargeCycle?.telemetryPoints?.filter { !it.isScreenOn }.orEmpty()
            val avgScreenOffWatts = if (screenOffPoints.isNotEmpty()) {
                screenOffPoints.map { it.powerWatts }.average()
            } else {
                powerWatts.toDouble()
            }

            val screenOnPoints = activeChargeCycle?.telemetryPoints?.filter { it.isScreenOn }.orEmpty()
            val avgScreenOnWatts = if (screenOnPoints.isNotEmpty()) {
                screenOnPoints.map { it.powerWatts }.average()
            } else {
                (powerWatts * 0.65).coerceAtLeast(4.0)
            }

            val chargeTitle = if (state.batteryLevel != null) {
                val tempPart = if (tempText != null) " • $tempText" else ""
                "${state.batteryLevel}% • ${String.format(Locale.US, "%.1fW", powerWatts)}$tempPart"
            } else {
                context.getString(R.string.drain_state_charging)
            }

            val screenOffShort = context.getString(
                R.string.notif_charge_screen_off_short,
                String.format(Locale.US, "%.1fW", avgScreenOffWatts)
            )
            val chargeContent = "${String.format(Locale.US, "%.1fW", powerWatts)} • ${String.format(Locale.US, "%.2fV", voltageV)} • ${String.format(Locale.US, "%.2fA", currentA)} | $screenOffShort"

            val chargeBig = buildString {
                appendLine(context.getString(
                    R.string.notif_charge_current_speed,
                    String.format(Locale.US, "%.1f W (%.2f V • %.2f A)", powerWatts, voltageV, currentA)
                ))
                appendLine(context.getString(
                    R.string.notif_charge_avg_screen_off,
                    String.format(Locale.US, "%.1f W", avgScreenOffWatts)
                ))
                appendLine(context.getString(
                    R.string.notif_charge_avg_screen_on,
                    String.format(Locale.US, "%.1f W", avgScreenOnWatts)
                ))
                if (activeChargeCycle != null) {
                    appendLine(context.getString(
                        R.string.notif_charge_received,
                        activeChargeCycle.batteryDeltaPercent,
                        activeChargeCycle.totalMah.toInt(),
                        formatBatteryDuration(activeChargeCycle.durationMs)
                    ))
                    appendLine(context.getString(
                        R.string.notif_charge_cycles_used,
                        String.format(Locale.US, "%.2f", activeChargeCycle.totalCyclesDelta)
                    ))
                }
                if (currentTemp != null) {
                    append(context.getString(
                        R.string.notif_charge_battery_temp,
                        Math.round(currentTemp)
                    ))
                }
            }.trimEnd()

            Triple(chargeTitle, chargeContent, chargeBig)
        } else {
            val metrics = BatteryDisplayMetrics.resolve(snapshot, state)

            val currentStateText = when {
                state.isDeepSleep -> {
                    val sleepText = context.getString(R.string.drain_state_deep_sleep)
                    if (tempText != null) "$tempText • $sleepText" else sleepText
                }
                state.isDozing -> {
                    val dozeText = context.getString(R.string.drain_state_dozing)
                    if (tempText != null) "$tempText • $dozeText" else dozeText
                }
                state.isScreenOn -> {
                    tempText ?: ""
                }
                else -> {
                    val offText = context.getString(R.string.drain_state_screen_off)
                    if (tempText != null) "$tempText • $offText" else offText
                }
            }.removeEmojis()

            val title = if (state.batteryLevel != null) {
                if (currentStateText.isNotBlank()) {
                    "${state.batteryLevel}% • $currentStateText"
                } else {
                    "${state.batteryLevel}%"
                }
            } else {
                if (currentStateText.isNotBlank()) {
                    context.getString(R.string.battery_level_unknown, currentStateText)
                } else {
                    context.getString(R.string.battery_level_unknown, "")
                }
            }.removeEmojis()

            val contentText = context.getString(
                R.string.drain_on_off_sleep,
                formatDrainPercentage(metrics.screenOnDrainRatePercent),
                formatDrainPercentage(metrics.screenOffDrainRatePercent),
                formatDrainPercentage(metrics.deepSleepDrainRatePercent)
            ).removeEmojis()

            val screenOnRateStr = if (metrics.screenOnDischargePercent > 0f) {
                "${formatDrainPercentage(metrics.screenOnDrainRatePercent)} • ${String.format(Locale.getDefault(), "%.1f%%", metrics.screenOnDischargePercent)}"
            } else {
                formatDrainPercentage(metrics.screenOnDrainRatePercent)
            }
            val screenOnStr = context.getString(
                R.string.drain_screen_on_line,
                screenOnRateStr,
                formatBatteryDuration(metrics.screenOnMs)
            ).removeEmojis()

            val screenOffRateStr = if (metrics.screenOffDischargePercent > 0f) {
                "${formatDrainPercentage(metrics.screenOffDrainRatePercent)} • ${String.format(Locale.getDefault(), "%.1f%%", metrics.screenOffDischargePercent)}"
            } else {
                formatDrainPercentage(metrics.screenOffDrainRatePercent)
            }
            val screenOffStr = context.getString(
                R.string.drain_screen_off_line,
                screenOffRateStr,
                formatBatteryDuration(metrics.screenOffMs)
            ).removeEmojis()

            val deepSleepStr = context.getString(
                R.string.drain_deep_sleep_line,
                formatDrainPercentage(metrics.deepSleepDrainRatePercent),
                formatBatteryDuration(metrics.deepSleepMs),
                String.format(Locale.getDefault(), "%.0f%%", metrics.deepSleepPercentage)
            ).removeEmojis()
            val awakeStr = context.getString(
                R.string.drain_awake_line,
                formatDrainPercentage(metrics.awakeDrainRatePercent),
                formatBatteryDuration(metrics.awakeMs)
            ).removeEmojis()
            val activeStr = context.getString(
                R.string.drain_active_line,
                formatDrainPercentage(metrics.activeDrainRatePercent),
                formatBatteryDuration(metrics.activeMs)
            ).removeEmojis()
            val idleStr = context.getString(
                R.string.drain_idle_line,
                formatDrainPercentage(metrics.idleDrainRatePercent),
                formatBatteryDuration(metrics.idleMs)
            ).removeEmojis()

            val totalDrainFormatted = if (metrics.totalDischargePercent > 0f) {
                "${String.format(Locale.getDefault(), "%.1f%%", metrics.totalDischargePercent)} (${String.format(Locale.getDefault(), "%.0f mAh", metrics.totalDrainMah)})"
            } else {
                String.format(Locale.getDefault(), "%.1f mAh", metrics.totalDrainMah)
            }
            val totalStr = context.getString(
                R.string.drain_total_line,
                totalDrainFormatted,
                formatBatteryDuration(metrics.totalTimeMs)
            ).removeEmojis()
            val avgStr = context.getString(
                R.string.drain_average_line,
                formatDrainPercentage(metrics.averageDrainRatePercent)
            ).removeEmojis()
            val netStr = context.getString(
                R.string.drain_network_line,
                android.text.format.Formatter.formatFileSize(context, state.networkRxBytes),
                android.text.format.Formatter.formatFileSize(context, state.networkTxBytes)
            ).removeEmojis()

            val bigText = buildString {
                if (showRates) {
                    appendLine("$screenOnStr • $screenOffStr")
                }
                if (showDeepSleep) {
                    appendLine("$deepSleepStr • $awakeStr")
                }
                if (showActiveIdle) {
                    appendLine("$activeStr • $idleStr")
                }
                if (showTotalAvg) {
                    appendLine("$totalStr • $avgStr")
                }
                if (showNetwork) {
                    append("$netStr")
                }
            }.trimEnd()

            Triple(title, contentText, bigText)
        }

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setContentTitle(finalTitle)
            .setContentText(finalContentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(finalBigText))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setGroup("dev.may.taskmanager.group.BATTERY_DRAIN")
            .setGroupSummary(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                android.R.drawable.ic_menu_rotate,
                context.getString(R.string.reset),
                resetIntent
            )
            .build()
    }
}
