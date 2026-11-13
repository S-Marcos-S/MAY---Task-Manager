// SPDX-License-Identifier: GPL-3.0-or-later
package dev.may.taskmanager.battery

import android.app.Activity
import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Process
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import dev.may.taskmanager.MainActivity
import dev.may.taskmanager.R
import dev.may.taskmanager.root.RootBatteryStatsCollector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

class TransparencyNotificationManager private constructor(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val scope = CoroutineScope(Dispatchers.Default)

    private var isAppInBackground = false
    private var bgStartRealtime: Long = 0L
    private var bgStartCpuMs: Long = 0L

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notif_transparency_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.notif_transparency_channel_desc)
                setShowBadge(true)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    @Synchronized
    fun onAppBackground() {
        isAppInBackground = true
        checkDateRollover()
        bgStartRealtime = SystemClock.elapsedRealtime()
        bgStartCpuMs = Process.getElapsedCpuTime()
        prefs.edit()
            .putBoolean(KEY_IS_IN_BG, true)
            .putLong(KEY_BG_START_REALTIME, bgStartRealtime)
            .putLong(KEY_BG_START_CPU, bgStartCpuMs)
            .apply()
    }

    @Synchronized
    fun onAppForeground() {
        if (isAppInBackground) {
            flushBackgroundDeltas()
            isAppInBackground = false
            prefs.edit().putBoolean(KEY_IS_IN_BG, false).apply()
        }
    }

    @Synchronized
    private fun flushBackgroundDeltas() {
        if (!isAppInBackground || bgStartRealtime <= 0L) return
        checkDateRollover()
        val nowReal = SystemClock.elapsedRealtime()
        val nowCpu = Process.getElapsedCpuTime()
        val deltaReal = (nowReal - bgStartRealtime).coerceAtLeast(0L)
        val deltaCpu = (nowCpu - bgStartCpuMs).coerceAtLeast(0L)

        bgStartRealtime = nowReal
        bgStartCpuMs = nowCpu

        val accumulatedBg = prefs.getLong(KEY_BG_TIME_MS, 0L) + deltaReal
        val accumulatedCpu = prefs.getLong(KEY_CPU_TIME_MS, 0L) + deltaCpu

        prefs.edit()
            .putLong(KEY_BG_TIME_MS, accumulatedBg)
            .putLong(KEY_CPU_TIME_MS, accumulatedCpu)
            .putLong(KEY_BG_START_REALTIME, bgStartRealtime)
            .putLong(KEY_BG_START_CPU, bgStartCpuMs)
            .apply()
    }

    private fun checkDateRollover() {
        val today = LocalDate.now().toString()
        val savedDate = prefs.getString(KEY_DATE, "")
        if (savedDate != today) {
            prefs.edit()
                .putString(KEY_DATE, today)
                .putLong(KEY_BG_TIME_MS, 0L)
                .putLong(KEY_CPU_TIME_MS, 0L)
                .apply()
        }
    }

    fun scheduleNextAlarm() {
        if (!isNotificationEnabled(context)) return

        checkPermissionForNotifications()

        val hour = getScheduleHour(context)
        val minute = getScheduleMinute(context)
        val triggerAtMs = calculateNextTriggerMillis(hour, minute)

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, TransparencyAlarmReceiver::class.java).apply {
            action = TransparencyAlarmReceiver.ACTION_TRIGGER_NOTIFICATION
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
            }
        }.onFailure {
            runCatching {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
            }
        }
    }

    fun cancelAlarm() {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, TransparencyAlarmReceiver::class.java).apply {
            action = TransparencyAlarmReceiver.ACTION_TRIGGER_NOTIFICATION
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }

    private fun checkPermissionForNotifications() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                scope.launch {
                    if (RootBatteryStatsCollector.isRootAvailable()) {
                        RootBatteryStatsCollector.runAsRoot("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
                    }
                }
            }
        }
    }

    fun deliverDailyNotification() {
        if (!isNotificationEnabled(context)) return

        checkDateRollover()
        if (isAppInBackground && bgStartRealtime > 0L) {
            flushBackgroundDeltas()
        }

        val totalBgTimeMs = prefs.getLong(KEY_BG_TIME_MS, 0L)
        val totalCpuTimeMs = prefs.getLong(KEY_CPU_TIME_MS, 0L)

        // Consult dumpsys snapshot if available
        val detailedCollector = DetailedStatsCollector.getInstance(context)
        val snapshot = detailedCollector.snapshot.value
        val myAppStats = snapshot?.apps?.firstOrNull { it.packageName == context.packageName }

        val wakelockMs = myAppStats?.wakeLockTimeMs ?: 0L
        val (drainMah, drainPercent) = computeDrain(totalCpuTimeMs, myAppStats)

        val notification = buildNotification(
            totalBgTimeMs = totalBgTimeMs,
            totalCpuTimeMs = totalCpuTimeMs,
            drainMah = drainMah,
            drainPercent = drainPercent,
            wakelockMs = wakelockMs
        )

        try {
            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {}
    }

    private fun computeDrain(
        cpuTimeMs: Long,
        appStats: BatteryStatsParser.AppPowerStats?
    ): Pair<Double, Float> {
        val capacityMah = getBatteryCapacityMah()

        if (appStats != null && appStats.powerMah > 0.0) {
            val totalTime = (appStats.backgroundTimeMs + appStats.foregroundTimeMs).coerceAtLeast(1L)
            val bgRatio = (appStats.backgroundTimeMs.toDouble() / totalTime).coerceIn(0.0, 1.0)
            val mah = appStats.powerMah * bgRatio
            val pct = ((mah / capacityMah) * 100.0).toFloat().coerceIn(0f, 100f)
            return Pair(mah, pct)
        }

        val avgCpuMa = getAverageCpuPowerMa()
        val cpuHours = cpuTimeMs / 3600000.0
        val mah = cpuHours * avgCpuMa
        val pct = ((mah / capacityMah) * 100.0).toFloat().coerceIn(0f, 100f)
        return Pair(mah, pct)
    }

    private fun buildNotification(
        totalBgTimeMs: Long,
        totalCpuTimeMs: Long,
        drainMah: Double,
        drainPercent: Float,
        wakelockMs: Long
    ): Notification {
        val contentIntent = PendingIntent.getActivity(
            context,
            REQUEST_CODE_OPEN,
            Intent(context, MainActivity::class.java).apply {
                action = DrainNotificationManager.ACTION_OPEN_BATTERY_STATS
                putExtra(DrainNotificationManager.EXTRA_OPEN_BATTERY_STATS, true)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val formattedMah = String.format(Locale.getDefault(), "%.2f mAh", drainMah)
        val formattedPct = String.format(Locale.getDefault(), "%.3f%%", drainPercent)
        val formattedCpu = formatDurationExact(totalCpuTimeMs)
        val formattedBg = formatBatteryDuration(totalBgTimeMs)
        val formattedWakelock = formatDurationExact(wakelockMs)

        val summary = context.getString(
            R.string.notif_transparency_summary_line,
            formattedMah,
            formattedPct,
            formattedCpu,
            formattedBg
        )

        val bigText = buildString {
            appendLine(context.getString(R.string.notif_transparency_big_drain, formattedMah, formattedPct))
            appendLine(context.getString(R.string.notif_transparency_big_bg_time, formattedBg))
            appendLine(context.getString(R.string.notif_transparency_big_cpu_time, formattedCpu))
            append(context.getString(R.string.notif_transparency_big_wakelocks, formattedWakelock))
        }

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setContentTitle(context.getString(R.string.notif_transparency_title))
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun formatDurationExact(ms: Long): String {
        if (ms <= 0L) return "0s"
        val seconds = ms / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        return when {
            hours > 0 -> "${hours}h ${minutes % 60}m"
            minutes > 0 -> "${minutes}m ${seconds % 60}s"
            else -> "${seconds}s"
        }
    }

    private fun getBatteryCapacityMah(): Double {
        runCatching {
            val powerProfileClass = Class.forName("com.android.internal.os.PowerProfile")
            val powerProfile = powerProfileClass.getConstructor(Context::class.java).newInstance(context)
            val cap = powerProfileClass.getMethod("getBatteryCapacity").invoke(powerProfile) as? Double
            if (cap != null && cap > 1000.0) return cap
        }
        for (path in listOf(
            "/sys/class/power_supply/battery/charge_full_design",
            "/sys/class/power_supply/battery/charge_full"
        )) {
            runCatching {
                val f = File(path)
                if (f.exists() && f.canRead()) {
                    val value = f.readText().trim().toLongOrNull() ?: 0L
                    if (value > 100_000L) return value / 1000.0
                    if (value in 1000..20000) return value.toDouble()
                }
            }
        }
        return 4000.0
    }

    private fun getAverageCpuPowerMa(): Double {
        runCatching {
            val powerProfileClass = Class.forName("com.android.internal.os.PowerProfile")
            val powerProfile = powerProfileClass.getConstructor(Context::class.java).newInstance(context)
            val method = powerProfileClass.getMethod("getAveragePower", String::class.java)
            val activePower = method.invoke(powerProfile, "cpu.active") as? Double
            if (activePower != null && activePower > 10.0) return activePower
        }
        return 180.0
    }

    companion object {
        const val CHANNEL_ID = "may_battery_transparency_channel"
        private const val NOTIFICATION_ID = 1043
        private const val REQUEST_CODE_OPEN = 1043
        private const val ALARM_REQUEST_CODE = 1044

        private const val PREF_FILE = "may_transparency_prefs"
        private const val KEY_ENABLED = "pref_battery_transparency_notification_enabled"
        private const val KEY_SCHEDULE_HOUR = "transparency_schedule_hour"
        private const val KEY_SCHEDULE_MINUTE = "transparency_schedule_minute"

        private const val KEY_DATE = "transparency_date"
        private const val KEY_BG_TIME_MS = "transparency_bg_time_ms"
        private const val KEY_CPU_TIME_MS = "transparency_cpu_time_ms"
        private const val KEY_BG_START_REALTIME = "transparency_bg_start_realtime"
        private const val KEY_BG_START_CPU = "transparency_bg_start_cpu"
        private const val KEY_IS_IN_BG = "transparency_is_in_bg"

        const val DEFAULT_HOUR = 21
        const val DEFAULT_MINUTE = 0

        private var instance: TransparencyNotificationManager? = null

        fun getInstance(context: Context): TransparencyNotificationManager {
            return instance ?: synchronized(this) {
                instance ?: TransparencyNotificationManager(context.applicationContext).also { instance = it }
            }
        }

        fun init(app: Application) {
            val manager = getInstance(app)
            app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                private val activityCounter = AtomicInteger(0)

                override fun onActivityStarted(activity: Activity) {
                    if (activityCounter.getAndIncrement() == 0) {
                        manager.onAppForeground()
                    }
                }

                override fun onActivityStopped(activity: Activity) {
                    if (activityCounter.decrementAndGet() == 0) {
                        manager.onAppBackground()
                    }
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) {}
                override fun onActivityResumed(activity: Activity) {}
                override fun onActivityPaused(activity: Activity) {}
                override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) {}
                override fun onActivityDestroyed(activity: Activity) {}
            })
        }

        fun isNotificationEnabled(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_ENABLED, false)
        }

        fun setNotificationEnabled(context: Context, enabled: Boolean) {
            val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
            val manager = getInstance(context)
            if (enabled) {
                manager.scheduleNextAlarm()
            } else {
                manager.cancelAlarm()
            }
        }

        fun getScheduleHour(context: Context): Int {
            val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            return prefs.getInt(KEY_SCHEDULE_HOUR, DEFAULT_HOUR).coerceIn(0, 23)
        }

        fun getScheduleMinute(context: Context): Int {
            val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            return prefs.getInt(KEY_SCHEDULE_MINUTE, DEFAULT_MINUTE).coerceIn(0, 59)
        }

        fun setScheduleTime(context: Context, hour: Int, minute: Int) {
            val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            prefs.edit()
                .putInt(KEY_SCHEDULE_HOUR, hour.coerceIn(0, 23))
                .putInt(KEY_SCHEDULE_MINUTE, minute.coerceIn(0, 59))
                .apply()
            val manager = getInstance(context)
            if (isNotificationEnabled(context)) {
                manager.scheduleNextAlarm()
            }
        }

        fun calculateNextTriggerMillis(hour: Int, minute: Int): Long {
            val calendar = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
            }
            return calendar.timeInMillis
        }
    }
}
