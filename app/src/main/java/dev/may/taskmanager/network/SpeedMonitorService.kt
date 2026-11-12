// SPDX-License-Identifier: GPL-3.0-or-later
package dev.may.taskmanager.network

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.net.TrafficStats
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import dev.may.taskmanager.MainActivity
import dev.may.taskmanager.R
import dev.may.taskmanager.root.AppRootInspector
import dev.may.taskmanager.telemetry.ConfidenceLevel
import dev.may.taskmanager.telemetry.TelemetryNature
import dev.may.taskmanager.telemetry.TelemetryValidator
import dev.may.taskmanager.update.RootInstaller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class SpeedMonitorService : Service() {

    companion object {
        const val CHANNEL_ID = "speed_monitor_channel_v2"
        const val OLD_CHANNEL_ID = "speed_monitor_channel"
        const val NOTIFICATION_ID = 2100
        const val GROUP_KEY = "dev.may.taskmanager.group.SPEED_MONITOR"

        const val ACTION_START = "dev.may.taskmanager.network.ACTION_START_SPEED_MONITOR"
        const val ACTION_STOP = "dev.may.taskmanager.network.ACTION_STOP_SPEED_MONITOR"
        const val ACTION_FORCE_STOP_CONSUMING_APP = "dev.may.taskmanager.network.ACTION_FORCE_STOP_CONSUMING_APP"
        const val EXTRA_APP_PACKAGE = "extra_app_package"
        const val EXTRA_APP_NAME = "extra_app_name"
        const val ACTION_VIEW_APPS = "dev.may.taskmanager.network.ACTION_VIEW_APPS"
        const val ACTION_HIDE_APPS = "dev.may.taskmanager.network.ACTION_HIDE_APPS"
        const val ACTION_TOGGLE_APPS = "dev.may.taskmanager.network.ACTION_TOGGLE_APPS"
        const val ACTION_OPEN_NETWORK_STATS = "dev.may.taskmanager.action.OPEN_NETWORK_STATS"

        @Volatile
        var isServiceRunning: Boolean = false
            private set

        fun start(context: Context) {
            val settings = NetSpeedSettings(context)
            settings.isEnabled = true
            val intent = Intent(context, SpeedMonitorService::class.java).apply {
                action = ACTION_START
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {}
        }

        fun stop(context: Context) {
            val settings = NetSpeedSettings(context)
            settings.isEnabled = false
            val intent = Intent(context, SpeedMonitorService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {}
        }

        fun calculateActiveAppsFromDeltas(
            currentRxTotals: Map<Int, Long>,
            previousRxTotals: Map<Int, Long>,
            currentTxTotals: Map<Int, Long>,
            previousTxTotals: Map<Int, Long>,
            deltaTimeSec: Double,
            minBytesPerSec: Double,
            totalDownSpeed: Double? = null,
            totalUpSpeed: Double? = null,
            resolveName: (Int) -> String?
        ): List<ActiveAppSpeed> {
            if (deltaTimeSec <= 0.0) return emptyList()

            val isMeasurementAvailable = totalDownSpeed != null && totalUpSpeed != null && totalDownSpeed >= 0.0 && totalUpSpeed >= 0.0
            val totalDeviceSpeed = if (isMeasurementAvailable) totalDownSpeed!! + totalUpSpeed!! else null

            // Regra 4 e 7: Se a velocidade geral medida for menor que o limiar mínimo (incluindo 0.0 B/s),
            // o dispositivo está comprovadamente ocioso (ausência real de tráfego).
            // Estatísticas acumuladas passadas e lotes atrasados do NetworkStatsManager são descartados
            // para não serem apresentados indevidamente como velocidade instantânea atual.
            if (isMeasurementAvailable && totalDeviceSpeed!! < minBytesPerSec) {
                return emptyList()
            }

            val allUids = currentRxTotals.keys + currentTxTotals.keys

            data class RawUidSpeed(
                val uid: Int,
                val rxSpeed: Double,
                val txSpeed: Double,
                val totalSpeed: Double
            )

            val rawList = mutableListOf<RawUidSpeed>()

            for (uid in allUids) {
                if (uid < 10000 && uid != 0 && uid != 1000) continue

                val curRx = currentRxTotals[uid] ?: 0L
                val prevRx = previousRxTotals[uid] ?: curRx
                val deltaRx = (curRx - prevRx).coerceAtLeast(0L)

                val curTx = currentTxTotals[uid] ?: 0L
                val prevTx = previousTxTotals[uid] ?: curTx
                val deltaTx = (curTx - prevTx).coerceAtLeast(0L)

                val rawRxSpeed = deltaRx / deltaTimeSec
                val rawTxSpeed = deltaTx / deltaTimeSec
                val rawTotal = rawRxSpeed + rawTxSpeed

                if (rawTotal >= minBytesPerSec) {
                    rawList.add(RawUidSpeed(uid, rawRxSpeed, rawTxSpeed, rawTotal))
                }
            }

            if (rawList.isEmpty()) return emptyList()

            val sumRawRx = rawList.sumOf { it.rxSpeed }
            val sumRawTx = rawList.sumOf { it.txSpeed }
            val sumRawTotal = sumRawRx + sumRawTx

            // Verificação de divergência (atraso de agregação de lote do NetworkStatsManager)
            val rxDiverges = isMeasurementAvailable && totalDownSpeed!! > 0.0 && sumRawRx > (totalDownSpeed * 1.05)
            val txDiverges = isMeasurementAvailable && totalUpSpeed!! > 0.0 && sumRawTx > (totalUpSpeed * 1.05)
            val totalDiverges = if (isMeasurementAvailable) {
                (totalDeviceSpeed!! > 0.0 && sumRawTotal > (totalDeviceSpeed * 1.05)) || rxDiverges || txDiverges
            } else {
                true
            }

            // Aplicação da Lei da Conservação Física:
            // A soma das velocidades atribuídas aos aplicativos no mesmo intervalo não pode
            // fisicamente ultrapassar a medição real instantânea do dispositivo.
            val result = mutableListOf<ActiveAppSpeed>()

            for (item in rawList) {
                val name = resolveName(item.uid) ?: continue

                val adjustedRx = if (isMeasurementAvailable) {
                    if (rxDiverges && sumRawRx > 0.0) {
                        (item.rxSpeed / sumRawRx) * totalDownSpeed!!
                    } else if (totalDownSpeed!! <= 0.0) {
                        0.0
                    } else {
                        item.rxSpeed
                    }
                } else {
                    item.rxSpeed
                }

                val adjustedTx = if (isMeasurementAvailable) {
                    if (txDiverges && sumRawTx > 0.0) {
                        (item.txSpeed / sumRawTx) * totalUpSpeed!!
                    } else if (totalUpSpeed!! <= 0.0) {
                        0.0
                    } else {
                        item.txSpeed
                    }
                } else {
                    item.txSpeed
                }

                val finalSpeed = adjustedRx + adjustedTx

                if (finalSpeed >= minBytesPerSec) {
                    result.add(
                        ActiveAppSpeed(
                            appName = name,
                            speedBytesSec = finalSpeed,
                            rxSpeedBytesSec = adjustedRx,
                            txSpeedBytesSec = adjustedTx,
                            uid = item.uid,
                            nature = TelemetryNature.AGGREGATED_STATISTIC,
                            confidence = if (totalDiverges || !isMeasurementAvailable) ConfidenceLevel.LOW else ConfidenceLevel.MEDIUM,
                            hasDivergence = totalDiverges,
                        )
                    )
                }
            }

            return result.sortedByDescending { it.speedBytesSec }
        }

        fun calculateActiveAppsFromDeltas(
            currentTotals: Map<Int, Long>,
            previousTotals: Map<Int, Long>,
            deltaTimeSec: Double,
            minBytesPerSec: Double,
            totalDeviceSpeed: Double? = null,
            resolveName: (Int) -> String?
        ): List<ActiveAppSpeed> {
            if (deltaTimeSec <= 0.0) return emptyList()

            val isMeasurementAvailable = totalDeviceSpeed != null && totalDeviceSpeed >= 0.0

            if (isMeasurementAvailable && totalDeviceSpeed!! < minBytesPerSec) {
                return emptyList()
            }

            val rawActive = mutableListOf<ActiveAppSpeed>()
            for ((uid, current) in currentTotals) {
                if (uid < 10000 && uid != 0 && uid != 1000) continue
                val prev = previousTotals[uid] ?: continue
                if (current > prev) {
                    val delta = current - prev
                    val rawSpeed = delta / deltaTimeSec
                    if (rawSpeed >= minBytesPerSec) {
                        val name = resolveName(uid)
                        if (name != null) {
                            rawActive.add(
                                ActiveAppSpeed(
                                    appName = name,
                                    speedBytesSec = rawSpeed,
                                    uid = uid,
                                    nature = TelemetryNature.AGGREGATED_STATISTIC,
                                    confidence = ConfidenceLevel.MEDIUM,
                                )
                            )
                        }
                    }
                }
            }
            if (rawActive.isEmpty()) return emptyList()

            val sumRaw = rawActive.sumOf { it.speedBytesSec }
            val hasDivergence = if (isMeasurementAvailable) {
                totalDeviceSpeed!! > 0.0 && sumRaw > (totalDeviceSpeed * 1.05)
            } else {
                true
            }

            // Princípio de conservação física: normaliza proporcionalmente se houver divergência
            val result = rawActive.map { app ->
                if (isMeasurementAvailable && hasDivergence && sumRaw > 0.0) {
                    val scaledSpeed = (app.speedBytesSec / sumRaw) * totalDeviceSpeed!!
                    app.copy(
                        speedBytesSec = scaledSpeed,
                        hasDivergence = true,
                        confidence = ConfidenceLevel.LOW,
                    )
                } else if (!isMeasurementAvailable) {
                    app.copy(
                        hasDivergence = true,
                        confidence = ConfidenceLevel.LOW,
                    )
                } else {
                    app
                }
            }

            return result.filter { it.speedBytesSec >= minBytesPerSec }
                .sortedByDescending { it.speedBytesSec }
        }
    }

    data class ActiveAppSpeed(
        val appName: String,
        val speedBytesSec: Double,
        val rxSpeedBytesSec: Double = 0.0,
        val txSpeedBytesSec: Double = 0.0,
        val uid: Int? = null,
        val isSharedUid: Boolean = false,
        val nature: TelemetryNature = TelemetryNature.AGGREGATED_STATISTIC,
        val confidence: ConfidenceLevel = ConfidenceLevel.MEDIUM,
        val hasDivergence: Boolean = false,
    )
    private data class RecentAppRecord(val app: ActiveAppSpeed, val timestamp: Long)

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var settings: NetSpeedSettings

    private var lastRxBytes: Long = 0L
    private var lastTxBytes: Long = 0L
    private var lastElapsedRealtimeNanos: Long = 0L
    private var lastValidSampleTimeMillis: Long = 0L
    private var isCollectionError: Boolean = false
    private var isStale: Boolean = false
    private var lastStatsUpdateTime: Long = 0L
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // Cached daily stats from NetworkStatsManager
    private var dailyMobileTotal: Long = 0L
    private var dailyWifiTotal: Long = 0L

    private var isScreenOn = true
    private var isViewingApps = false
    private var isAppsLoading = false
    private var appsViewStartTime = 0L

    private var lastNotificationText = ""
    private var lastIconSpeedText = ""
    private var lastIsDownload = false
    private var lastShowRealTime = false
    private var lastAppsSummary = ""

    private val appNameCache = mutableMapOf<Int, String>()
    private val lastUidRxTotals = mutableMapOf<Int, Long>()
    private val lastUidTxTotals = mutableMapOf<Int, Long>()
    private val recentActiveApps = mutableMapOf<String, RecentAppRecord>()
    private var lastAppStatsQueryTime = 0L

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> {
                    isScreenOn = true
                    // Force daily stats update when screen turns on
                    lastStatsUpdateTime = 0L
                    handler.removeCallbacks(updateRunnable)
                    handler.post(updateRunnable)
                }
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenOn = false
                    handler.removeCallbacks(updateRunnable)
                }
            }
        }
    }

    private val updateRunnable = object : Runnable {
        override fun run() {
            if (!isScreenOn) return
            updateStats()
            handler.postDelayed(this, settings.updateInterval.coerceAtLeast(500L))
        }
    }

    private fun resetBaselineCounters() {
        lastRxBytes = TrafficStats.getTotalRxBytes()
        lastTxBytes = TrafficStats.getTotalTxBytes()
        lastElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
    }

    override fun onCreate() {
        super.onCreate()
        settings = NetSpeedSettings(this)
        createNotificationChannel()

        val initialNotif = createNotification("0 B/s", "0 B/s", "0 B/s", "0 B", "0 B", false, "", emptyList())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, initialNotif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, initialNotif)
        }

        resetBaselineCounters()
        lastValidSampleTimeMillis = System.currentTimeMillis()

        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        runCatching {
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    resetBaselineCounters()
                }
                override fun onLost(network: Network) {
                    resetBaselineCounters()
                }
            }
            val request = NetworkRequest.Builder().build()
            connectivityManager?.registerNetworkCallback(request, networkCallback!!)
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenReceiver, filter)
        isServiceRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                settings.isEnabled = false
                isServiceRunning = false
                handler.removeCallbacks(updateRunnable)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.cancel(NOTIFICATION_ID)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_FORCE_STOP_CONSUMING_APP -> {
                val pkg = intent.getStringExtra(EXTRA_APP_PACKAGE)
                val appLabel = intent.getStringExtra(EXTRA_APP_NAME) ?: pkg ?: ""
                if (!pkg.isNullOrBlank()) {
                    val basePkg = pkg.substringBefore(":")
                    CoroutineScope(Dispatchers.IO).launch {
                        val rootAvailable = AppRootInspector.isRootAvailable()
                        if (rootAvailable) {
                            AppRootInspector.runSuCommand("am force-stop $basePkg; pkill -9 -f $basePkg 2>/dev/null")
                            Handler(Looper.getMainLooper()).post {
                                Toast.makeText(
                                    applicationContext,
                                    getString(R.string.anomaly_toast_force_stopped, appLabel),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        } else {
                            val detailsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", basePkg, null)
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            startActivity(detailsIntent)
                        }
                    }
                }
                updateStats()
                return START_STICKY
            }
            ACTION_VIEW_APPS -> {
                isViewingApps = true
                isAppsLoading = true
                appsViewStartTime = System.currentTimeMillis()
                lastAppsSummary = ""
                recentActiveApps.clear()
                lastUidRxTotals.clear()
                lastUidTxTotals.clear()
                lastAppStatsQueryTime = 0L
                takeAppsBaselineSnapshot()
                updateStats(forceUpdate = true)
                handler.postDelayed({
                    if (isViewingApps) updateStats(forceUpdate = true)
                }, 1000L)
                return START_STICKY
            }
            ACTION_HIDE_APPS -> {
                isViewingApps = false
                isAppsLoading = false
                lastAppsSummary = ""
                recentActiveApps.clear()
                lastUidRxTotals.clear()
                lastUidTxTotals.clear()
                lastAppStatsQueryTime = 0L
                updateStats(forceUpdate = true)
                return START_STICKY
            }
            ACTION_TOGGLE_APPS -> {
                isViewingApps = !isViewingApps
                lastAppsSummary = ""
                if (isViewingApps) {
                    isAppsLoading = true
                    appsViewStartTime = System.currentTimeMillis()
                    recentActiveApps.clear()
                    lastUidRxTotals.clear()
                    lastUidTxTotals.clear()
                    lastAppStatsQueryTime = 0L
                    takeAppsBaselineSnapshot()
                    handler.postDelayed({
                        if (isViewingApps) updateStats(forceUpdate = true)
                    }, 1000L)
                } else {
                    isAppsLoading = false
                    recentActiveApps.clear()
                    lastUidRxTotals.clear()
                    lastUidTxTotals.clear()
                    lastAppStatsQueryTime = 0L
                }
                updateStats(forceUpdate = true)
                return START_STICKY
            }
        }

        // Start in foreground
        val notif = createNotification(
            iconSpeedText = lastIconSpeedText.ifEmpty { "0 B/s" },
            downSpeedText = "0 B/s",
            upSpeedText = "0 B/s",
            mobileText = formatBytes(dailyMobileTotal),
            wifiText = formatBytes(dailyWifiTotal),
            showRealTime = lastShowRealTime,
            contentText = lastNotificationText,
            activeApps = emptyList()
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notif)
        }

        resetBaselineCounters()
        lastValidSampleTimeMillis = System.currentTimeMillis()
        lastStatsUpdateTime = 0L // Force update on start

        handler.removeCallbacks(updateRunnable)
        handler.post(updateRunnable)
        isServiceRunning = true
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isServiceRunning = false
        runCatching {
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        }
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {}
        handler.removeCallbacks(updateRunnable)
        recentActiveApps.clear()
        lastUidRxTotals.clear()
        lastUidTxTotals.clear()
        appNameCache.clear()
        super.onDestroy()
    }

    private fun updateDailyTotalsFromSystem() {
        val networkStatsManager = getSystemService(Context.NETWORK_STATS_SERVICE) as? NetworkStatsManager ?: return
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startTime = calendar.timeInMillis
        val endTime = System.currentTimeMillis()

        dailyMobileTotal = queryDeviceTotal(networkStatsManager, ConnectivityManager.TYPE_MOBILE, startTime, endTime)
        dailyWifiTotal = queryDeviceTotal(networkStatsManager, ConnectivityManager.TYPE_WIFI, startTime, endTime)
    }

    private fun queryDeviceTotal(manager: NetworkStatsManager, type: Int, start: Long, end: Long): Long {
        return try {
            val bucket = manager.querySummaryForDevice(type, null, start, end)
            bucket.rxBytes + bucket.txBytes
        } catch (_: Exception) {
            0L
        }
    }

    private fun updateStats(forceUpdate: Boolean = false) {
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        val currentTime = System.currentTimeMillis()

        // Update daily totals from system every 30 seconds to save battery
        if (currentTime - lastStatsUpdateTime >= 30000L || lastStatsUpdateTime == 0L) {
            updateDailyTotalsFromSystem()
            lastStatsUpdateTime = currentTime
        }

        val currentTotalRx = TrafficStats.getTotalRxBytes()
        val currentTotalTx = TrafficStats.getTotalTxBytes()

        val isSupported = currentTotalRx != TrafficStats.UNSUPPORTED.toLong() && currentTotalTx != TrafficStats.UNSUPPORTED.toLong()

        val deltaNanos = if (lastElapsedRealtimeNanos > 0L) nowNanos - lastElapsedRealtimeNanos else 0L
        val deltaTime = (deltaNanos.toDouble() / 1_000_000_000.0).coerceAtLeast(0.1)

        val rxState = TelemetryValidator.evaluateCounter(currentTotalRx, lastRxBytes)
        val txState = TelemetryValidator.evaluateCounter(currentTotalTx, lastTxBytes)

        val isStaleInterval = (lastElapsedRealtimeNanos > 0L && deltaTime > 30.0)

        if (!isSupported || rxState == TelemetryValidator.CounterState.NEGATIVE_OR_UNSUPPORTED || txState == TelemetryValidator.CounterState.NEGATIVE_OR_UNSUPPORTED) {
            isCollectionError = true
            isStale = false
        } else if (rxState == TelemetryValidator.CounterState.RESET_OR_REBOOT || txState == TelemetryValidator.CounterState.RESET_OR_REBOOT) {
            // Reinicialização de contadores detectada (reboot ou reinício de driver de rede)
            isCollectionError = false
            isStale = false
            lastRxBytes = currentTotalRx
            lastTxBytes = currentTotalTx
            lastElapsedRealtimeNanos = nowNanos
            return
        } else if (isStaleInterval) {
            // Intervalo excessivo (>30s): descarta velocidade instantanea para evitar picos artificiais
            isCollectionError = false
            isStale = true
            lastRxBytes = currentTotalRx
            lastTxBytes = currentTotalTx
            lastElapsedRealtimeNanos = nowNanos
        } else {
            isCollectionError = false
            isStale = false
        }

        val deltaRx = if (!isCollectionError && !isStale && lastRxBytes > 0L && currentTotalRx >= lastRxBytes) currentTotalRx - lastRxBytes else 0L
        val deltaTx = if (!isCollectionError && !isStale && lastTxBytes > 0L && currentTotalTx >= lastTxBytes) currentTotalTx - lastTxBytes else 0L

        val downloadSpeed = if (!isCollectionError && !isStale && isSupported) deltaRx / deltaTime else 0.0
        val uploadSpeed = if (!isCollectionError && !isStale && isSupported) deltaTx / deltaTime else 0.0

        if (!isCollectionError && !isStale && isSupported) {
            lastRxBytes = currentTotalRx
            lastTxBytes = currentTotalTx
            lastElapsedRealtimeNanos = nowNanos
            if (lastRxBytes > 0L) {
                lastValidSampleTimeMillis = currentTime
            }
        }

        val isDownload = downloadSpeed >= uploadSpeed
        val displaySpeed = if (isDownload) downloadSpeed else uploadSpeed
        val totalSpeed = downloadSpeed + uploadSpeed

        val thresholdInBytes = settings.getThresholdInBytes()

        val showRealTime = settings.isRealTimeEnabled && (thresholdInBytes <= 0.0 || totalSpeed >= thresholdInBytes)

        val finalDownSpeed = if (showRealTime) downloadSpeed else 0.0
        val finalUpSpeed = if (showRealTime) uploadSpeed else 0.0
        val finalIconSpeed = if (showRealTime) displaySpeed else 0.0

        val speedText = formatSpeed(finalIconSpeed)
        val downSpeedText = formatSpeed(finalDownSpeed)
        val upSpeedText = formatSpeed(finalUpSpeed)
        val mobileText = formatBytes(dailyMobileTotal)
        val wifiText = formatBytes(dailyWifiTotal)

        val contentText = buildString {
            if (settings.isRealTimeEnabled && showRealTime) {
                append("↓$downSpeedText ↑$upSpeedText")
            }
            if (settings.isDailyUsageEnabled) {
                if (isNotEmpty()) append(" | ")
                append("M:$mobileText W:$wifiText")
            }
            if (isEmpty()) {
                append("↓0 B/s ↑0 B/s")
            }
        }

        val measuredDown: Double? = if (!isCollectionError && !isStale && isSupported) downloadSpeed else null
        val measuredUp: Double? = if (!isCollectionError && !isStale && isSupported) uploadSpeed else null
        val activeApps = if (isViewingApps) getActiveAppsSpeed(deltaTime, measuredDown, measuredUp) else emptyList()
        val appsSummary = if (isViewingApps) activeApps.joinToString { "${it.appName}:${it.speedBytesSec}:${it.rxSpeedBytesSec}:${it.txSpeedBytesSec}" } else ""

        // Only notify if something meaningful changed or if forceUpdate is requested
        if (forceUpdate ||
            contentText != lastNotificationText ||
            appsSummary != lastAppsSummary ||
            showRealTime != lastShowRealTime ||
            (showRealTime && (speedText != lastIconSpeedText || isDownload != lastIsDownload))
        ) {
            lastNotificationText = contentText
            lastIconSpeedText = speedText
            lastIsDownload = isDownload
            lastAppsSummary = appsSummary
            lastShowRealTime = showRealTime

            val notification = createNotification(
                iconSpeedText = speedText,
                downSpeedText = downSpeedText,
                upSpeedText = upSpeedText,
                mobileText = mobileText,
                wifiText = wifiText,
                showRealTime = showRealTime,
                contentText = contentText,
                activeApps = activeApps
            )
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun tryAutoGrantUsagePermission() {
        if (RootInstaller.isRootAvailable()) {
            Thread {
                try {
                    val cmd = "pm grant $packageName android.permission.PACKAGE_USAGE_STATS 2>/dev/null; cmd appops set $packageName GET_USAGE_STATS allow 2>/dev/null; appops set $packageName GET_USAGE_STATS allow 2>/dev/null"
                    Runtime.getRuntime().exec(arrayOf("su", "-c", cmd)).waitFor()
                } catch (_: Exception) {}
            }.start()
        }
    }

    private fun takeAppsBaselineSnapshot() {
        if (!hasUsageStatsPermission()) {
            tryAutoGrantUsagePermission()
            return
        }
        val nsm = getSystemService(Context.NETWORK_STATS_SERVICE) as? NetworkStatsManager ?: return
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startOfDay = calendar.timeInMillis
        val currentTime = System.currentTimeMillis()

        val rxTotals = mutableMapOf<Int, Long>()
        val txTotals = mutableMapOf<Int, Long>()
        collectUidCumulative(nsm, ConnectivityManager.TYPE_WIFI, startOfDay, currentTime, rxTotals, txTotals)
        collectUidCumulative(nsm, ConnectivityManager.TYPE_MOBILE, startOfDay, currentTime, rxTotals, txTotals)
        collectUidCumulative(nsm, ConnectivityManager.TYPE_ETHERNET, startOfDay, currentTime, rxTotals, txTotals)

        lastUidRxTotals.clear()
        lastUidRxTotals.putAll(rxTotals)
        lastUidTxTotals.clear()
        lastUidTxTotals.putAll(txTotals)
        lastAppStatsQueryTime = currentTime
    }

    private fun getActiveAppsSpeed(
        deltaTime: Double,
        totalDownSpeed: Double?,
        totalUpSpeed: Double?
    ): List<ActiveAppSpeed> {
        if (!hasUsageStatsPermission()) {
            tryAutoGrantUsagePermission()
            return emptyList()
        }

        val isMeasurementAvailable = totalDownSpeed != null && totalUpSpeed != null && totalDownSpeed >= 0.0 && totalUpSpeed >= 0.0
        val totalDeviceSpeed = if (isMeasurementAvailable) totalDownSpeed!! + totalUpSpeed!! else null
        val currentTime = System.currentTimeMillis()
        val minBytesPerSec = (settings.minAppUsageKb.coerceAtLeast(1L) * 1024.0)

        // Se a velocidade geral do dispositivo for menor que o limiar mínimo (incluindo 0.0 B/s),
        // o dispositivo está ocioso. Limpa imediatamente apps recentes para não apresentar
        // estatísticas acumuladas passadas como se fossem velocidades instantâneas atuais.
        if (isMeasurementAvailable && totalDeviceSpeed!! < minBytesPerSec && recentActiveApps.isNotEmpty()) {
            recentActiveApps.clear()
            return emptyList()
        }

        val nsm = getSystemService(Context.NETWORK_STATS_SERVICE) as? NetworkStatsManager ?: return emptyList()

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startOfDay = calendar.timeInMillis

        val currentRxTotals = mutableMapOf<Int, Long>()
        val currentTxTotals = mutableMapOf<Int, Long>()
        collectUidCumulative(nsm, ConnectivityManager.TYPE_WIFI, startOfDay, currentTime, currentRxTotals, currentTxTotals)
        collectUidCumulative(nsm, ConnectivityManager.TYPE_MOBILE, startOfDay, currentTime, currentRxTotals, currentTxTotals)
        collectUidCumulative(nsm, ConnectivityManager.TYPE_ETHERNET, startOfDay, currentTime, currentRxTotals, currentTxTotals)

        if (lastUidRxTotals.isNotEmpty() && lastAppStatsQueryTime > 0) {
            val elapsedSec = ((currentTime - lastAppStatsQueryTime) / 1000.0)
            if (elapsedSec >= 0.8) {
                val detected = calculateActiveAppsFromDeltas(
                    currentRxTotals = currentRxTotals,
                    previousRxTotals = lastUidRxTotals,
                    currentTxTotals = currentTxTotals,
                    previousTxTotals = lastUidTxTotals,
                    deltaTimeSec = elapsedSec,
                    minBytesPerSec = minBytesPerSec,
                    totalDownSpeed = totalDownSpeed,
                    totalUpSpeed = totalUpSpeed,
                    resolveName = { uid -> getAppNameForUid(uid) }
                )
                if (detected.isNotEmpty()) {
                    isAppsLoading = false
                    recentActiveApps.clear()
                    for (app in detected) {
                        recentActiveApps[app.appName] = RecentAppRecord(app, currentTime)
                    }
                } else if (currentTime - appsViewStartTime >= 1800L) {
                    isAppsLoading = false
                }
                lastUidRxTotals.clear()
                lastUidRxTotals.putAll(currentRxTotals)
                lastUidTxTotals.clear()
                lastUidTxTotals.putAll(currentTxTotals)
                lastAppStatsQueryTime = currentTime
            }
        } else {
            lastUidRxTotals.clear()
            lastUidRxTotals.putAll(currentRxTotals)
            lastUidTxTotals.clear()
            lastUidTxTotals.putAll(currentTxTotals)
            lastAppStatsQueryTime = currentTime
        }

        // Prune records older than 2500ms
        recentActiveApps.entries.removeAll { (currentTime - it.value.timestamp) > 2500L }

        // Conservação física contínua: se a velocidade global diminuiu, a velocidade do app em cache
        // não pode ultrapassar a velocidade total física atual do dispositivo.
        val maxAllowed = if (isMeasurementAvailable) (totalDeviceSpeed!! * 1.05).coerceAtLeast(0.0) else null
        val activeList = recentActiveApps.values.map { record ->
            val app = record.app
            if (maxAllowed != null && totalDeviceSpeed != null && totalDeviceSpeed > 0.0 && app.speedBytesSec > maxAllowed) {
                val factor = totalDeviceSpeed / app.speedBytesSec
                app.copy(
                    speedBytesSec = totalDeviceSpeed,
                    rxSpeedBytesSec = app.rxSpeedBytesSec * factor,
                    txSpeedBytesSec = app.txSpeedBytesSec * factor,
                    hasDivergence = true,
                    confidence = ConfidenceLevel.LOW
                )
            } else {
                app
            }
        }.filter { it.speedBytesSec >= minBytesPerSec }
         .sortedByDescending { it.speedBytesSec }
         .take(4)

        if (activeList.isNotEmpty()) {
            isAppsLoading = false
        } else if (currentTime - appsViewStartTime >= 1800L) {
            isAppsLoading = false
        }

        return activeList
    }

    private fun collectUidCumulative(
        nsm: NetworkStatsManager,
        networkType: Int,
        startTime: Long,
        endTime: Long,
        rxResult: MutableMap<Int, Long>,
        txResult: MutableMap<Int, Long>
    ) {
        try {
            val stats = nsm.querySummary(networkType, null, startTime, endTime)
            val bucket = NetworkStats.Bucket()
            while (stats.hasNextBucket()) {
                stats.getNextBucket(bucket)
                val uid = bucket.uid
                rxResult[uid] = (rxResult[uid] ?: 0L) + bucket.rxBytes
                txResult[uid] = (txResult[uid] ?: 0L) + bucket.txBytes
            }
            stats.close()
        } catch (_: Throwable) {}
    }

    private fun getAppNameForUid(uid: Int): String? {
        if (uid == android.os.Process.myUid()) return null
        val cached = appNameCache[uid]
        if (cached != null) return cached

        val resolvedName = when (uid) {
            0 -> getString(R.string.system_uid_root)
            1000 -> getString(R.string.system_uid_android)
            else -> {
                val packages = packageManager.getPackagesForUid(uid)
                if (packages.isNullOrEmpty()) {
                    packageManager.getNameForUid(uid) ?: "UID $uid"
                } else if (packages.size > 1) {
                    val firstApp = runCatching {
                        val info = packageManager.getApplicationInfo(packages[0], 0)
                        packageManager.getApplicationLabel(info).toString()
                    }.getOrDefault(packages[0])
                    getString(R.string.shared_uid_app_count, firstApp, packages.size - 1)
                } else {
                    val pkg = packages[0]
                    if (pkg == packageName) return null
                    try {
                        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            packageManager.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
                        } else {
                            @Suppress("DEPRECATION")
                            packageManager.getApplicationInfo(pkg, 0)
                        }
                        packageManager.getApplicationLabel(info).toString()
                    } catch (_: Exception) {
                        pkg
                    }
                }
            }
        }
        appNameCache[uid] = resolvedName
        return resolvedName
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 * 1024 -> String.format(Locale.getDefault(), "%.2f GB", bytes / (1024.0 * 1024 * 1024))
            bytes >= 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024))
            bytes >= 1024 -> String.format(Locale.getDefault(), "%d KB", bytes / 1024)
            else -> "$bytes B"
        }
    }

    private fun formatSpeed(bytesPerSecond: Double): String {
        return when {
            bytesPerSecond >= 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f MB/s", bytesPerSecond / (1024 * 1024))
            bytesPerSecond >= 1024 -> String.format(Locale.getDefault(), "%d KB/s", (bytesPerSecond / 1024).toInt())
            else -> String.format(Locale.getDefault(), "%d B/s", bytesPerSecond.toInt())
        }
    }

    private fun formatAppSpeedText(app: ActiveAppSpeed): String {
        val rx = app.rxSpeedBytesSec
        val tx = app.txSpeedBytesSec
        return when {
            rx >= 1024.0 && tx >= 1024.0 -> "↓${formatSpeed(rx)} ↑${formatSpeed(tx)}"
            tx >= 1024.0 && rx < 1024.0 -> "↑${formatSpeed(tx)}"
            rx >= 1024.0 && tx < 1024.0 -> "↓${formatSpeed(rx)}"
            else -> formatSpeed(app.speedBytesSec)
        }
    }

    private fun createNotification(
        iconSpeedText: String,
        downSpeedText: String,
        upSpeedText: String,
        mobileText: String,
        wifiText: String,
        showRealTime: Boolean,
        contentText: String = "",
        activeApps: List<ActiveAppSpeed> = emptyList()
    ): Notification {
        val hasUsagePermission = hasUsageStatsPermission()
        val notifyIntent = if (isViewingApps && !hasUsagePermission) {
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        } else {
            Intent(this, MainActivity::class.java).apply {
                action = ACTION_OPEN_NETWORK_STATS
                putExtra("open_network_stats", true)
                setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        }
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 1000, notifyIntent, pendingIntentFlags)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.net_speed_title))
            .setContentText(contentText)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setGroup(GROUP_KEY)
            .setGroupSummary(false)
            .setSortKey("0_speed_monitor")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && showRealTime) {
            val bitmap = createSpeedBitmap(iconSpeedText)
            builder.setSmallIcon(IconCompat.createWithBitmap(bitmap))
        } else {
            builder.setSmallIcon(R.drawable.ic_net_speed)
        }

        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val lastSampleTimeStr = if (lastValidSampleTimeMillis > 0L) {
            timeFormat.format(Date(lastValidSampleTimeMillis))
        } else {
            "--:--:--"
        }
        val isStaleData = isStale || (lastValidSampleTimeMillis > 0L && System.currentTimeMillis() - lastValidSampleTimeMillis > 15000L)
        val statusText = when {
            isCollectionError -> getString(R.string.telemetry_status_error)
            !isScreenOn -> getString(R.string.telemetry_status_paused)
            isStaleData -> getString(R.string.telemetry_status_stale)
            else -> getString(R.string.telemetry_status_active)
        }

        // Expanded view showing speeds, daily usage, and active apps if toggled
        val bigTextStyle = NotificationCompat.BigTextStyle()
        val bigText = buildString {
            appendLine(getString(R.string.net_speed_status, statusText))
            appendLine(getString(R.string.net_speed_last_sample, lastSampleTimeStr))

            if (settings.isRealTimeEnabled) {
                if (isCollectionError || isStaleData) {
                    appendLine("${getString(R.string.net_speed_download)}: --  |  ${getString(R.string.net_speed_upload)}: --")
                } else {
                    appendLine("${getString(R.string.net_speed_download)}: $downSpeedText  |  ${getString(R.string.net_speed_upload)}: $upSpeedText")
                }
            }
            if (settings.isDailyUsageEnabled) {
                appendLine("${getString(R.string.net_speed_mobile_today)}: $mobileText")
                appendLine("${getString(R.string.net_speed_wifi_today)}: $wifiText")
            }

            if (isViewingApps) {
                append("\n")
                if (!hasUsagePermission) {
                    append(getString(R.string.net_speed_permission_required))
                } else if (activeApps.isNotEmpty()) {
                    appendLine(getString(R.string.net_speed_active_apps_header, settings.minAppUsageKb))
                    for (app in activeApps) {
                        val speedFormatted = formatAppSpeedText(app)
                        if (app.hasDivergence) {
                            appendLine(getString(R.string.net_speed_app_divergence, app.appName, speedFormatted))
                        } else {
                            appendLine(getString(R.string.net_speed_app_normal, app.appName, speedFormatted))
                        }
                    }
                } else if (isAppsLoading) {
                    append(getString(R.string.net_speed_calculating_apps))
                } else {
                    append(getString(R.string.net_speed_no_active_apps, settings.minAppUsageKb))
                }
            }
        }.trimEnd()
        builder.setStyle(bigTextStyle.bigText(bigText))

        // Action Buttons: extremely concise, displayed right below the apps
        // 1. Toggle Apps view button ("Ocultar" when open, "Ver apps" when closed)
        val toggleAppsIntent = Intent(this, SpeedMonitorService::class.java).apply {
            action = if (isViewingApps) ACTION_HIDE_APPS else ACTION_VIEW_APPS
        }
        val toggleAppsPending = PendingIntent.getService(this, 1001, toggleAppsIntent, pendingIntentFlags)
        val toggleLabel = if (isViewingApps) {
            getString(R.string.net_speed_action_hide_apps)
        } else {
            getString(R.string.net_speed_action_view_apps)
        }
        builder.addAction(0, toggleLabel, toggleAppsPending)

        // 2. Stop consuming app button ("Parar" stops the consuming app if active, or stops monitor if idle)
        val topApp = activeApps.firstOrNull()
        val topAppPkg = topApp?.let { app ->
            app.packageName ?: (app.uid?.let { uid -> packageManager.getPackagesForUid(uid)?.firstOrNull() })
        }

        val stopPending = if (!topAppPkg.isNullOrBlank()) {
            val stopAppIntent = Intent(this, SpeedMonitorService::class.java).apply {
                action = ACTION_FORCE_STOP_CONSUMING_APP
                putExtra(EXTRA_APP_PACKAGE, topAppPkg)
                putExtra(EXTRA_APP_NAME, topApp.appName)
            }
            PendingIntent.getService(this, 1002, stopAppIntent, pendingIntentFlags)
        } else {
            val stopIntent = Intent(this, SpeedMonitorService::class.java).apply {
                action = ACTION_STOP
            }
            PendingIntent.getService(this, 1002, stopIntent, pendingIntentFlags)
        }
        builder.addAction(0, getString(R.string.background_btn_stop), stopPending)

        // 3. Settings or Permission button
        val action3Pending = if (isViewingApps && !hasUsagePermission) {
            val usageIntent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            PendingIntent.getActivity(this, 1004, usageIntent, pendingIntentFlags)
        } else {
            val settingsIntent = Intent(this, MainActivity::class.java).apply {
                action = ACTION_OPEN_NETWORK_STATS
                putExtra("open_network_stats", true)
                setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            PendingIntent.getActivity(this, 1003, settingsIntent, pendingIntentFlags)
        }
        val action3Label = if (isViewingApps && !hasUsagePermission) {
            getString(R.string.net_speed_action_grant_permission)
        } else {
            getString(R.string.net_speed_action_settings)
        }
        builder.addAction(0, action3Label, action3Pending)

        return builder.build()
    }

    private fun createSpeedBitmap(speedText: String): Bitmap {
        val size = 96
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply {
            isAntiAlias = true
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
        }

        val parts = speedText.split(" ")
        val valueOnly = parts.getOrNull(0) ?: "0"
        val unit = parts.getOrNull(1) ?: "B/s"

        paint.textSize = 50f
        paint.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText(valueOnly, size / 2f, size / 2f + 10f, paint)

        paint.textSize = 25f
        canvas.drawText(unit, size / 2f, size - 10f, paint)

        return bitmap
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Delete legacy channel so system immediately upgrades to default importance without sound
            runCatching {
                notificationManager.deleteNotificationChannel(OLD_CHANNEL_ID)
            }

            val name = getString(R.string.net_speed_notif_channel_name)
            val descriptionText = getString(R.string.net_speed_notif_channel_desc)
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }
}
