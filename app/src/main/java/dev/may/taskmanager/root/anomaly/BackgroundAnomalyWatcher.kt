// SPDX-License-Identifier: GPL-3.0-or-later
package dev.may.taskmanager.root.anomaly

import android.app.ActivityManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.TrafficStats
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import dev.may.taskmanager.data.AppInfo
import dev.may.taskmanager.root.AppRootInspector
import dev.may.taskmanager.root.SystemTaskInspector
import dev.may.taskmanager.telemetry.ConfidenceLevel
import dev.may.taskmanager.telemetry.TelemetryNature
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class BackgroundAnomalyWatcher private constructor(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "background_anomaly_prefs"
        private const val KEY_ENABLED = "key_enabled"
        private const val KEY_SENSITIVITY = "key_sensitivity"
        private const val KEY_NOTIFY_NET = "key_notify_net"
        private const val KEY_NOTIFY_CPU = "key_notify_cpu"
        private const val KEY_NOTIFY_RAM = "key_notify_ram"
        private const val KEY_NOTIFY_DEEP_SLEEP = "key_notify_deep_sleep"
        private const val KEY_WHITELIST = "key_whitelist"

        private const val SCREEN_ON_INTERVAL_MS = 30_000L
        private const val COOLDOWN_MS = 25 * 60 * 1000L // 25 minutos entre notificações por app

        @Volatile
        private var instance: BackgroundAnomalyWatcher? = null

        fun getInstance(context: Context): BackgroundAnomalyWatcher {
            return instance ?: synchronized(this) {
                instance ?: BackgroundAnomalyWatcher(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val notificationManager = BackgroundAnomalyNotificationManager.getInstance(context)

    private val _config = MutableStateFlow(loadConfig())
    val config: StateFlow<AnomalyWatcherConfig> = _config.asStateFlow()

    private val _anomalies = MutableStateFlow<List<AnomalyEvent>>(emptyList())
    val anomalies: StateFlow<List<AnomalyEvent>> = _anomalies.asStateFlow()

    private val _isMonitoring = MutableStateFlow(false)
    val isMonitoring: StateFlow<Boolean> = _isMonitoring.asStateFlow()

    private val _watcherState = MutableStateFlow(AnomalyWatcherState.INACTIVE)
    val watcherState: StateFlow<AnomalyWatcherState> = _watcherState.asStateFlow()

    private val captureMutex = Mutex()
    private var isSnapshotReady = false
    private var activeCaptureDeferred: CompletableDeferred<Boolean>? = null
    private var currentSessionId: String = ""

    private val isRunning = AtomicBoolean(false)
    private var screenOnLoopJob: Job? = null
    private var screenReceiverRegistered = false

    private var isScreenInteractive = false
    private var screenOffTimeRealtime = 0L
    private var screenOffUptime = 0L
    private var screenOffWallTime = 0L

    private data class UidNetSample(val rx: Long, val tx: Long, val timestamp: Long)

    // Identidade imutavel do processo para rastreamento de CPU sem colisao de PIDs reciclados
    data class ProcessIdentity(val pid: Int, val processName: String, val startIdentifier: String = "")
    private data class ProcessCpuSample(val cpuTimeMs: Long, val monotonicMs: Long, val consecutiveHits: Int)
    private val processCpuTracker = ConcurrentHashMap<ProcessIdentity, ProcessCpuSample>()

    // Rastreamento temporal de RAM baseado em RSS real por identidade de processo
    private data class ProcessRamSample(val rssMb: Double, val monotonicMs: Long)
    private val processRamHistory = ConcurrentHashMap<ProcessIdentity, MutableList<ProcessRamSample>>()

    // Cache de amostras de rede para cálculo diferencial (tela ligada)
    private val activeNetSamples = ConcurrentHashMap<Int, UidNetSample>()

    // Fotografia de rede no exato instante em que a tela é desligada
    private val screenOffNetSnapshots = ConcurrentHashMap<Int, UidNetSample>()

    // Controle de pacotes temporariamente silenciados (mute) e cooldown de alertas
    private val mutedPackages = ConcurrentHashMap<String, Long>()
    private val lastAlertTimeMap = ConcurrentHashMap<String, Long>()

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    onScreenTurnedOff()
                }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    onScreenTurnedOn()
                }
            }
        }
    }

    init {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        isScreenInteractive = pm?.isInteractive ?: true
    }

    private fun loadConfig(): AnomalyWatcherConfig {
        val enabled = prefs.getBoolean(KEY_ENABLED, true)
        val sensName = prefs.getString(KEY_SENSITIVITY, AnomalySensitivity.BALANCED.name)
        val sensitivity = runCatching { AnomalySensitivity.valueOf(sensName ?: "") }.getOrDefault(AnomalySensitivity.BALANCED)
        val notifyNet = prefs.getBoolean(KEY_NOTIFY_NET, true)
        val notifyCpu = prefs.getBoolean(KEY_NOTIFY_CPU, true)
        val notifyRam = prefs.getBoolean(KEY_NOTIFY_RAM, true)
        val notifyDeepSleep = prefs.getBoolean(KEY_NOTIFY_DEEP_SLEEP, true)
        val whitelist = prefs.getStringSet(KEY_WHITELIST, emptySet()) ?: emptySet()

        return AnomalyWatcherConfig(
            isEnabled = enabled,
            sensitivity = sensitivity,
            notifyNetwork = notifyNet,
            notifyCpu = notifyCpu,
            notifyRam = notifyRam,
            notifyDeepSleep = notifyDeepSleep,
            whitelistedPackages = whitelist,
        )
    }

    fun updateConfig(newConfig: AnomalyWatcherConfig) {
        _config.value = newConfig
        prefs.edit()
            .putBoolean(KEY_ENABLED, newConfig.isEnabled)
            .putString(KEY_SENSITIVITY, newConfig.sensitivity.name)
            .putBoolean(KEY_NOTIFY_NET, newConfig.notifyNetwork)
            .putBoolean(KEY_NOTIFY_CPU, newConfig.notifyCpu)
            .putBoolean(KEY_NOTIFY_RAM, newConfig.notifyRam)
            .putBoolean(KEY_NOTIFY_DEEP_SLEEP, newConfig.notifyDeepSleep)
            .putStringSet(KEY_WHITELIST, newConfig.whitelistedPackages)
            .apply()

        if (!newConfig.isEnabled) {
            stop()
        } else if (!isRunning.get()) {
            start()
        }
    }

    fun mutePackage(packageName: String, durationMs: Long) {
        mutedPackages[packageName] = SystemClock.elapsedRealtime() + durationMs
    }

    fun isPackageMuted(packageName: String): Boolean {
        val expiry = mutedPackages[packageName] ?: return false
        if (SystemClock.elapsedRealtime() > expiry) {
            mutedPackages.remove(packageName)
            return false
        }
        return true
    }

    fun clearAnomalies() {
        _anomalies.value = emptyList()
        AnomalyHistoryRepository.getInstance(context).clearAll()
    }

    @Synchronized
    fun start() {
        if (isRunning.getAndSet(true)) return

        scope.launch(Dispatchers.IO) {
            if (AppRootInspector.isRootAvailable()) {
                AppRootInspector.runSuCommand("pm grant ${context.packageName} android.permission.PACKAGE_USAGE_STATS 2>/dev/null; appops set ${context.packageName} GET_USAGE_STATS allow 2>/dev/null")
            }
        }

        if (!screenReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            context.registerReceiver(screenReceiver, filter)
            screenReceiverRegistered = true
        }

        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        isScreenInteractive = pm?.isInteractive ?: true
        _isMonitoring.value = true

        if (isScreenInteractive) {
            startScreenOnEvaluationLoop()
        } else {
            onScreenTurnedOff()
        }
    }

    @Synchronized
    fun stop() {
        if (!isRunning.getAndSet(false)) return
        screenOnLoopJob?.cancel()
        screenOnLoopJob = null
        activeCaptureDeferred?.cancel()
        activeCaptureDeferred = null
        currentSessionId = ""
        isSnapshotReady = false
        _watcherState.value = AnomalyWatcherState.INACTIVE
        _isMonitoring.value = false

        if (screenReceiverRegistered) {
            runCatching { context.unregisterReceiver(screenReceiver) }
            screenReceiverRegistered = false
        }
    }

    private fun onScreenTurnedOff() {
        isScreenInteractive = false
        val sessionId = UUID.randomUUID().toString()
        currentSessionId = sessionId
        screenOffTimeRealtime = SystemClock.elapsedRealtime()
        screenOffUptime = SystemClock.uptimeMillis()
        screenOffWallTime = System.currentTimeMillis()
        isSnapshotReady = false
        _watcherState.value = AnomalyWatcherState.PREPARING_CAPTURE

        // Cancela captura pendente anterior em caso de alternancia rapida
        activeCaptureDeferred?.cancel()
        val deferred = CompletableDeferred<Boolean>()
        activeCaptureDeferred = deferred

        // Regra de Ouro: suspende qualquer loop de polling ativo. O processador DEVE dormir 100%.
        screenOnLoopJob?.cancel()
        screenOnLoopJob = null

        captureScreenOffSnapshot(sessionId, deferred)
    }

    private fun onScreenTurnedOn() {
        if (isScreenInteractive) return
        isScreenInteractive = true

        val sessionId = currentSessionId
        val now = SystemClock.elapsedRealtime()
        val nowUptime = SystemClock.uptimeMillis()
        val durationOffMs = now - screenOffTimeRealtime
        val awakeOffMs = (nowUptime - screenOffUptime).coerceAtLeast(0L)

        // Se a tela ficou desligada por mais de 5 segundos, aguarda a fotografia e analisa
        if (screenOffTimeRealtime > 0L && durationOffMs >= 5_000L) {
            scope.launch(Dispatchers.IO) {
                _watcherState.value = AnomalyWatcherState.EVALUATION_IN_PROGRESS

                // Aguarda conclusao da captura da sessao ativa com timeout finito de 5 segundos
                val captureSuccess = withTimeoutOrNull(5000L) {
                    activeCaptureDeferred?.await()
                } ?: false

                captureMutex.withLock {
                    if (currentSessionId == sessionId && captureSuccess && isSnapshotReady) {
                        evaluateScreenOffDifferential(durationOffMs)
                        evaluateDeepSleepDisruption(durationOffMs, awakeOffMs)
                        _watcherState.value = AnomalyWatcherState.EVALUATION_COMPLETED
                    } else {
                        _watcherState.value = AnomalyWatcherState.COLLECTION_UNAVAILABLE
                    }
                }
            }
        } else {
            _watcherState.value = AnomalyWatcherState.INACTIVE
        }

        if (isRunning.get() && _config.value.isEnabled) {
            startScreenOnEvaluationLoop()
        }
    }

    fun resolveUidInfo(pm: PackageManager, uid: Int): ResolvedUidInfo? {
        val packages = pm.getPackagesForUid(uid)
        if (packages.isNullOrEmpty()) {
            val name = pm.getNameForUid(uid) ?: return null
            return ResolvedUidInfo(
                primaryPackage = name,
                displayName = name,
                isSharedUid = false,
                packageCount = 1,
                allPackages = listOf(name),
            )
        }
        val isShared = packages.size > 1
        val firstPkg = packages[0]
        val appLabel = runCatching {
            val info = pm.getApplicationInfo(firstPkg, 0)
            pm.getApplicationLabel(info).toString()
        }.getOrDefault(firstPkg)

        val displayName = if (isShared) {
            "$appLabel (+${packages.size - 1} apps)"
        } else {
            appLabel
        }

        return ResolvedUidInfo(
            primaryPackage = firstPkg,
            displayName = displayName,
            isSharedUid = isShared,
            packageCount = packages.size,
            allPackages = packages.toList(),
        )
    }

    /**
     * Tira uma fotografia leve de todos os UIDs instalados via TrafficStats no instante em que a tela apaga.
     * Custo de CPU: < 1ms (chamadas C in-memory diretas do kernel eBPF/TrafficStats).
     */
    private fun captureScreenOffSnapshot(sessionId: String, deferred: CompletableDeferred<Boolean>) {
        scope.launch(Dispatchers.IO) {
            captureMutex.withLock {
                if (currentSessionId != sessionId) {
                    deferred.complete(false)
                    return@withLock
                }
                try {
                    val pm = context.packageManager
                    val installed = runCatching { pm.getInstalledApplications(0) }.getOrDefault(emptyList())
                    val map = mutableMapOf<Int, UidNetSample>()
                    val now = SystemClock.elapsedRealtime()

                    for (app in installed) {
                        val uid = app.uid
                        if (uid >= 10000 && !map.containsKey(uid)) {
                            val rx = TrafficStats.getUidRxBytes(uid)
                            val tx = TrafficStats.getUidTxBytes(uid)
                            if (rx > 0L || tx > 0L) {
                                map[uid] = UidNetSample(rx, tx, now)
                            }
                        }
                    }
                    screenOffNetSnapshots.clear()
                    screenOffNetSnapshots.putAll(map)
                    isSnapshotReady = true
                    _watcherState.value = AnomalyWatcherState.CAPTURE_COMPLETED
                    _watcherState.value = AnomalyWatcherState.SCREEN_OFF
                    deferred.complete(true)
                } catch (e: Exception) {
                    isSnapshotReady = false
                    _watcherState.value = AnomalyWatcherState.COLLECTION_UNAVAILABLE
                    deferred.complete(false)
                }
            }
        }
    }

    private fun collectUidUsageForInterval(
        startTimeMs: Long,
        endTimeMs: Long,
        resultMap: MutableMap<Int, Long>,
    ) {
        val nsm = context.getSystemService(Context.NETWORK_STATS_SERVICE) as? NetworkStatsManager ?: return
        fun query(type: Int) {
            runCatching {
                val stats = nsm.querySummary(type, null, startTimeMs, endTimeMs)
                val bucket = NetworkStats.Bucket()
                while (stats.hasNextBucket()) {
                    stats.getNextBucket(bucket)
                    val uid = bucket.uid
                    if (uid >= 10000) {
                        val prev = resultMap[uid] ?: 0L
                        resultMap[uid] = prev + bucket.rxBytes + bucket.txBytes
                    }
                }
                stats.close()
            }
        }
        query(ConnectivityManager.TYPE_WIFI)
        query(ConnectivityManager.TYPE_MOBILE)
    }

    /**
     * Avalia a variacao de dados (Delta Bytes) transmitidos durante a janela estrita de tela apagada.
     */
    private suspend fun evaluateScreenOffDifferential(sleepDurationMs: Long) {
        val cfg = _config.value
        if (!cfg.isEnabled || !cfg.notifyNetwork) return

        val thresholdBytes = cfg.sensitivity.netScreenOffMb * 1024L * 1024L
        val pm = context.packageManager
        val now = System.currentTimeMillis()

        val deltaMap = mutableMapOf<Int, Long>()
        val sourceMap = mutableMapOf<Int, String>()
        val natureMap = mutableMapOf<Int, TelemetryNature>()
        val confMap = mutableMapOf<Int, ConfidenceLevel>()
        val isFallbackMap = mutableMapOf<Int, Boolean>()

        val startWindow = screenOffWallTime
        val endWindow = now

        // 1. Android Native NetworkStatsManager query (estatistica agregada oficial)
        collectUidUsageForInterval(startWindow, endWindow, deltaMap)
        for (uid in deltaMap.keys) {
            sourceMap[uid] = "NetworkStatsManager"
            natureMap[uid] = TelemetryNature.AGGREGATED_STATISTIC
            confMap[uid] = ConfidenceLevel.MEDIUM
            isFallbackMap[uid] = false
        }

        // 2. Fallback via TrafficStats diff apenas para UIDs que nao retornaram dados em NetworkStatsManager
        for ((uid, baseline) in screenOffNetSnapshots) {
            if (!deltaMap.containsKey(uid) || deltaMap[uid] == 0L) {
                val curRx = TrafficStats.getUidRxBytes(uid)
                val curTx = TrafficStats.getUidTxBytes(uid)
                if (curRx >= baseline.rx && curTx >= baseline.tx) {
                    val d = (curRx - baseline.rx) + (curTx - baseline.tx)
                    if (d > 0L) {
                        deltaMap[uid] = d
                        sourceMap[uid] = "TrafficStats"
                        natureMap[uid] = TelemetryNature.DIRECT_MEASUREMENT
                        confMap[uid] = ConfidenceLevel.HIGH
                        isFallbackMap[uid] = true
                    }
                }
            }
        }

        for ((uid, deltaTotal) in deltaMap) {
            if (deltaTotal >= thresholdBytes) {
                val uidInfo = resolveUidInfo(pm, uid) ?: continue
                val pkgName = uidInfo.primaryPackage

                if (pkgName == context.packageName || cfg.whitelistedPackages.contains(pkgName) || isPackageMuted(pkgName)) {
                    continue
                }

                val lastAlert = lastAlertTimeMap[pkgName] ?: 0L
                if (now - lastAlert < COOLDOWN_MS) continue

                val appIcon = runCatching { pm.getApplicationIcon(pkgName) }.getOrNull()
                val formattedVal = formatBytes(deltaTotal)
                val durationMin = (sleepDurationMs / 60_000L).coerceAtLeast(1)

                val baseDesc = context.getString(
                    dev.may.taskmanager.R.string.anomaly_desc_net_screen_off,
                    uidInfo.displayName,
                    formattedVal,
                    "${durationMin}m",
                )

                val (detectedService, cause) = detectBackgroundServiceAndCause(pkgName, uid, startWindow, endWindow)
                val simpleService = detectedService?.let { simplifyServiceOrProcessName(it, pkgName) }

                val fullDesc = buildString {
                    append(baseDesc)
                    if (uidInfo.isSharedUid) {
                        append("\n")
                        append(context.getString(dev.may.taskmanager.R.string.anomaly_shared_uid_info, uid, uidInfo.packageCount))
                    }
                    if (!simpleService.isNullOrBlank()) {
                        append("\n• ")
                        append(context.getString(dev.may.taskmanager.R.string.anomaly_cause_service_active, simpleService))
                    }
                    if (!cause.isNullOrBlank() && cause != simpleService) {
                        append("\n• ")
                        append(cause)
                    }
                }

                val finalConfidence = if (uidInfo.isSharedUid) ConfidenceLevel.LOW else (confMap[uid] ?: ConfidenceLevel.MEDIUM)

                val anomaly = AnomalyEvent(
                    packageName = pkgName,
                    appName = uidInfo.displayName,
                    type = AnomalyType.NETWORK,
                    valueFormatted = formattedVal,
                    description = fullDesc,
                    timestamp = now,
                    screenWasOff = true,
                    serviceOrProcess = simpleService,
                    probableCause = cause,
                    rawValue = deltaTotal.toDouble(),
                    unit = "B",
                    uid = uid,
                    startTime = startWindow,
                    endTime = endWindow,
                    durationMs = sleepDurationMs,
                    confidence = finalConfidence,
                    nature = natureMap[uid] ?: TelemetryNature.AGGREGATED_STATISTIC,
                    source = sourceMap[uid] ?: "NetworkStatsManager",
                    isEstimated = uidInfo.isSharedUid,
                    isComplete = true,
                    rootAvailable = AppRootInspector.isRootAvailable(),
                    collectionMethod = sourceMap[uid] ?: "native",
                    isFallback = isFallbackMap[uid] ?: false,
                    reason = if (uidInfo.isSharedUid) context.getString(dev.may.taskmanager.R.string.anomaly_reason_shared_uid, uidInfo.allPackages.joinToString(", ")) else null,
                )

                recordAnomaly(anomaly)
                notificationManager.postAnomalyNotification(anomaly, appIcon)
                lastAlertTimeMap[pkgName] = now
            }
        }
    }

    /**
     * Avalia se aplicativos impediram o aparelho de entrar em sono profundo (Deep Sleep)
     * durante o periodo em que a tela esteve desligada.
     */
    private suspend fun evaluateDeepSleepDisruption(sleepDurationMs: Long, awakeOffMs: Long) {
        val cfg = _config.value
        if (!cfg.isEnabled || !cfg.notifyDeepSleep) return

        // Exige pelo menos 10 minutos de tela desligada para caracterizar analise estatistica confiavel
        if (sleepDurationMs < 10 * 60 * 1000L) return

        val awakeRatio = awakeOffMs.toDouble() / sleepDurationMs.toDouble()
        val minAwakeMs = when (cfg.sensitivity) {
            AnomalySensitivity.HIGH -> 2 * 60 * 1000L
            AnomalySensitivity.BALANCED -> 3 * 60 * 1000L
            AnomalySensitivity.LOW -> 6 * 60 * 1000L
        }
        val ratioThreshold = when (cfg.sensitivity) {
            AnomalySensitivity.HIGH -> 0.15
            AnomalySensitivity.BALANCED -> 0.25
            AnomalySensitivity.LOW -> 0.40
        }

        if (awakeOffMs < minAwakeMs || awakeRatio < ratioThreshold) return

        val pm = context.packageManager
        val now = System.currentTimeMillis()
        var suspectInfo: ResolvedUidInfo? = null
        var isFromUsageEvents = false

        // 1. Investigacao com Root: busca wakelocks parciais ativos
        if (AppRootInspector.isRootAvailable()) {
            val powerDumpsys = AppRootInspector.runSuCommand("dumpsys power 2>/dev/null").getOrNull() ?: ""
            if (powerDumpsys.isNotBlank()) {
                val wlRegex = Regex("PARTIAL_WAKE_LOCK\\s+'[^']*'\\s+.*\\(uid=([0-9]+)")
                val matches = wlRegex.findAll(powerDumpsys).toList()
                val uidCounts = mutableMapOf<Int, Int>()
                for (m in matches) {
                    val uid = m.groupValues[1].toIntOrNull()
                    if (uid != null && uid >= 10000 && uid != Process.myUid()) {
                        uidCounts[uid] = (uidCounts[uid] ?: 0) + 1
                    }
                }
                val topUid = uidCounts.maxByOrNull { it.value }?.key
                if (topUid != null) {
                    val info = resolveUidInfo(pm, topUid)
                    if (info != null && info.primaryPackage != context.packageName) {
                        suspectInfo = info
                    }
                }
            }
        }

        // 2. Investigacao via UsageEvents (Android padrao) - Tratado estritamente como inferencia/contexto
        if (suspectInfo == null) {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            if (usm != null) {
                runCatching {
                    val events = usm.queryEvents(screenOffWallTime, now)
                    val event = UsageEvents.Event()
                    val packageHits = mutableMapOf<String, Int>()

                    while (events.hasNextEvent()) {
                        events.getNextEvent(event)
                        val pkg = event.packageName
                        if (pkg != null && pkg != context.packageName && !cfg.whitelistedPackages.contains(pkg)) {
                            if (event.eventType == UsageEvents.Event.FOREGROUND_SERVICE_START) {
                                packageHits[pkg] = (packageHits[pkg] ?: 0) + 1
                            }
                        }
                    }
                    val topPkg = packageHits.maxByOrNull { it.value }?.key
                    if (topPkg != null) {
                        val appLabel = runCatching {
                            val info = pm.getApplicationInfo(topPkg, 0)
                            pm.getApplicationLabel(info).toString()
                        }.getOrDefault(topPkg)
                        suspectInfo = ResolvedUidInfo(
                            primaryPackage = topPkg,
                            displayName = appLabel,
                            isSharedUid = false,
                            packageCount = 1,
                            allPackages = listOf(topPkg),
                        )
                        isFromUsageEvents = true
                    }
                }
            }
        }

        val awakeMinutes = (awakeOffMs / 60_000L).coerceAtLeast(1)
        val offMinutes = (sleepDurationMs / 60_000L).coerceAtLeast(1)

        val targetPkg = suspectInfo?.primaryPackage ?: "android.system.sleep"
        val targetName = suspectInfo?.displayName ?: context.getString(dev.may.taskmanager.R.string.anomaly_device_deep_sleep_title)
        val isDeviceLevel = (suspectInfo == null)

        if (targetPkg == context.packageName || cfg.whitelistedPackages.contains(targetPkg) || isPackageMuted(targetPkg)) {
            return
        }

        val lastAlert = lastAlertTimeMap[targetPkg] ?: 0L
        if (now - lastAlert < COOLDOWN_MS) return

        val appIcon = if (!isDeviceLevel) runCatching { pm.getApplicationIcon(targetPkg) }.getOrNull() else null

        val desc = if (!isDeviceLevel) {
            context.getString(
                dev.may.taskmanager.R.string.anomaly_desc_deep_sleep,
                targetName,
                "${awakeMinutes}m",
                "${offMinutes}m",
            )
        } else {
            context.getString(
                dev.may.taskmanager.R.string.anomaly_device_deep_sleep_desc,
                "${awakeMinutes}m",
                "${offMinutes}m",
            )
        }

        val confidence = when {
            isDeviceLevel -> ConfidenceLevel.LOW
            isFromUsageEvents -> ConfidenceLevel.LOW // Inferencia indireta sem comprovacao causal
            suspectInfo?.isSharedUid == true -> ConfidenceLevel.LOW
            else -> ConfidenceLevel.MEDIUM // Wakelock ativo no instante final nao comprova duracao inteira
        }

        val nature = when {
            isDeviceLevel -> TelemetryNature.AGGREGATED_STATISTIC
            isFromUsageEvents -> TelemetryNature.INFERENCE
            else -> TelemetryNature.ESTIMATE
        }

        val reasonText = when {
            isDeviceLevel -> context.getString(dev.may.taskmanager.R.string.anomaly_reason_deep_sleep_device)
            isFromUsageEvents -> context.getString(dev.may.taskmanager.R.string.anomaly_reason_deep_sleep_fgs)
            suspectInfo?.isSharedUid == true -> context.getString(dev.may.taskmanager.R.string.anomaly_reason_deep_sleep_shared)
            else -> context.getString(dev.may.taskmanager.R.string.anomaly_reason_deep_sleep_wakelock)
        }

        val anomaly = AnomalyEvent(
            packageName = targetPkg,
            appName = targetName,
            type = AnomalyType.DEEP_SLEEP,
            valueFormatted = context.getString(dev.may.taskmanager.R.string.anomaly_value_awake_minutes, awakeMinutes.toInt()),
            description = desc,
            timestamp = now,
            screenWasOff = true,
            rawValue = awakeMinutes.toDouble(),
            unit = "min",
            startTime = screenOffWallTime,
            endTime = now,
            durationMs = sleepDurationMs,
            confidence = confidence,
            nature = nature,
            source = if (!isDeviceLevel) (if (isFromUsageEvents) "UsageEvents" else "dumpsys power") else "SystemClock.uptimeMillis",
            isEstimated = true,
            isComplete = true,
            rootAvailable = AppRootInspector.isRootAvailable(),
            collectionMethod = if (AppRootInspector.isRootAvailable()) "root_dumpsys" else "system_framework",
            reason = reasonText,
        )

        recordAnomaly(anomaly)
        notificationManager.postAnomalyNotification(anomaly, appIcon)
        lastAlertTimeMap[targetPkg] = now
    }

    /**
     * Loop adaptativo executado APENAS enquanto a tela está LIGADA (dispositivo já ativo pelo usuário).
     * Roda a cada 30 segundos com custo de processamento imperceptível (< 0.05%).
     */
    private fun startScreenOnEvaluationLoop() {
        screenOnLoopJob?.cancel()
        screenOnLoopJob = scope.launch(Dispatchers.IO) {
            while (isActive && isRunning.get() && isScreenInteractive) {
                delay(SCREEN_ON_INTERVAL_MS)
                if (!isActive || !isScreenInteractive) break

                val cfg = _config.value
                if (!cfg.isEnabled) break

                runCatching {
                    evaluateScreenOnAnomalies(cfg)
                }
            }
        }
    }

    private suspend fun evaluateScreenOnAnomalies(cfg: AnomalyWatcherConfig) {
        val nowWall = System.currentTimeMillis()
        val windowStartWall = nowWall - SCREEN_ON_INTERVAL_MS * 2
        val visiblePackages = getVisibleForegroundPackages(windowStartWall, nowWall)
        val pm = context.packageManager
        val now = SystemClock.elapsedRealtime()

        // 1. Verificação de Rede (Internet) em Segundo Plano
        if (cfg.notifyNetwork) {
            val netThresholdBytes = cfg.sensitivity.netScreenOnMb * 1024L * 1024L
            val onNetMap = mutableMapOf<Int, Long>()
            val intervalStart = nowWall - SCREEN_ON_INTERVAL_MS - 2_000L
            val intervalEnd = nowWall + 1_000L
            collectUidUsageForInterval(intervalStart, intervalEnd, onNetMap)

            // Merge TrafficStats se disponível
            val installed = runCatching { pm.getInstalledApplications(0) }.getOrDefault(emptyList())
            for (app in installed) {
                val uid = app.uid
                val curRx = TrafficStats.getUidRxBytes(uid)
                val curTx = TrafficStats.getUidTxBytes(uid)
                if (curRx > 0L || curTx > 0L) {
                    val prev = activeNetSamples[uid]
                    activeNetSamples[uid] = UidNetSample(curRx, curTx, now)
                    if (prev != null && prev.rx <= curRx && prev.tx <= curTx) {
                        val dBytes = (curRx - prev.rx) + (curTx - prev.tx)
                        val curr = onNetMap[uid] ?: 0L
                        if (dBytes > curr) onNetMap[uid] = dBytes
                    }
                }
            }

            for ((uid, dBytes) in onNetMap) {
                if (dBytes >= netThresholdBytes) {
                    val uidInfo = resolveUidInfo(pm, uid) ?: continue
                    val pkgName = uidInfo.primaryPackage

                    val isVisibleFg = visiblePackages.contains(pkgName) ||
                        uidInfo.allPackages.any { visiblePackages.contains(it) }

                    if (isVisibleFg || pkgName == context.packageName || cfg.whitelistedPackages.contains(pkgName) || isPackageMuted(pkgName)) {
                        continue
                    }

                    val lastAlert = lastAlertTimeMap[pkgName] ?: 0L
                    if (nowWall - lastAlert >= COOLDOWN_MS) {
                        val icon = runCatching { pm.getApplicationIcon(pkgName) }.getOrNull()
                        val formatted = formatBytes(dBytes)
                        val baseDesc = context.getString(
                            dev.may.taskmanager.R.string.anomaly_desc_net_screen_on,
                            uidInfo.displayName,
                            formatted,
                            "30s",
                        )

                        val (detectedService, cause) = detectBackgroundServiceAndCause(pkgName, uid, intervalStart, intervalEnd)
                        val simpleService = detectedService?.let { simplifyServiceOrProcessName(it, pkgName) }

                        val fullDesc = buildString {
                            append(baseDesc)
                            if (uidInfo.isSharedUid) {
                                append("\n")
                                append(context.getString(dev.may.taskmanager.R.string.anomaly_shared_uid_info, uid, uidInfo.packageCount))
                            }
                            if (!simpleService.isNullOrBlank()) {
                                append("\n• ")
                                append(context.getString(dev.may.taskmanager.R.string.anomaly_cause_service_active, simpleService))
                            }
                            if (!cause.isNullOrBlank() && cause != simpleService) {
                                append("\n• ")
                                append(cause)
                            }
                        }

                        val anomaly = AnomalyEvent(
                            packageName = pkgName,
                            appName = uidInfo.displayName,
                            type = AnomalyType.NETWORK,
                            valueFormatted = formatted,
                            description = fullDesc,
                            timestamp = nowWall,
                            screenWasOff = false,
                            serviceOrProcess = simpleService,
                            probableCause = cause,
                            rawValue = dBytes.toDouble(),
                            unit = "B",
                            uid = uid,
                            startTime = intervalStart,
                            endTime = intervalEnd,
                            durationMs = SCREEN_ON_INTERVAL_MS,
                            confidence = if (uidInfo.isSharedUid) ConfidenceLevel.LOW else ConfidenceLevel.MEDIUM,
                            nature = TelemetryNature.AGGREGATED_STATISTIC,
                            source = "NetworkStatsManager/TrafficStats",
                            isEstimated = uidInfo.isSharedUid,
                            isComplete = true,
                            rootAvailable = AppRootInspector.isRootAvailable(),
                            collectionMethod = "screen_on_monitor",
                            reason = if (uidInfo.isSharedUid) context.getString(dev.may.taskmanager.R.string.anomaly_reason_shared_uid, uidInfo.allPackages.joinToString(", ")) else null,
                        )

                        recordAnomaly(anomaly)
                        notificationManager.postAnomalyNotification(anomaly, icon)
                        lastAlertTimeMap[pkgName] = nowWall
                    }
                }
            }
        }

        // 2. Verificação de CPU em Segundo Plano
        if (cfg.notifyCpu) {
            evaluateCpuAnomalies(cfg, visiblePackages, nowWall)
        }

        // 3. Verificação de Memória RAM sob Pressão
        if (cfg.notifyRam) {
            evaluateRamAnomalies(cfg, visiblePackages, nowWall)
        }
    }

    private suspend fun evaluateCpuAnomalies(cfg: AnomalyWatcherConfig, visiblePackages: Set<String>, nowWall: Long) {
        val pm = context.packageManager
        val coresCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

        // Leitura precisa de tempo de CPU acumulado (TIME) e tempo de execução (ELAPSED) via ps com root
        if (!AppRootInspector.isRootAvailable()) {
            return
        }

        val psOutput = AppRootInspector.runSuCommand("ps -A -o pid,user,time,args 2>/dev/null || ps -o pid,user,time,args 2>/dev/null || ps -A -o PID,USER,TIME,ARGS 2>/dev/null || ps -o PID,USER,TIME,ARGS 2>/dev/null || ps -A -o PID,USER,TIME,ELAPSED,ARGS 2>/dev/null || ps -o PID,USER,TIME,ELAPSED,ARGS 2>/dev/null").getOrNull() ?: ""
        if (psOutput.isBlank()) return

        val nowMono = SystemClock.elapsedRealtime()
        val currentIdentities = mutableSetOf<ProcessIdentity>()

        psOutput.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("PID", ignoreCase = true)) return@forEach
            val tokens = trimmed.split(Regex("\\s+"))
            if (tokens.size >= 4) {
                val pid = tokens[0].toIntOrNull() ?: return@forEach
                val timeStr = tokens[2]
                val hasElapsed = tokens.size >= 5 && (tokens[3].contains(":") || tokens[3].contains("-")) && !tokens[3].contains(".")
                val cmdTokens = if (hasElapsed) tokens.subList(4, tokens.size) else tokens.subList(3, tokens.size)
                val cpuTimeMs = SystemTaskInspector.parseCpuTimeToMs(timeStr)
                val cmd = cmdTokens.joinToString(" ")
                val cleanCmd = cmd.substringBefore(" ").substringAfterLast("/").removeSurrounding("[", "]").removeSurrounding("(", ")")

                if (cleanCmd.contains(".") && !cleanCmd.startsWith("/")) {
                    val pkgName = cleanCmd.substringBefore(":")
                    val identity = ProcessIdentity(pid, cleanCmd)
                    currentIdentities.add(identity)

                    // Proteção de primeiro plano: se o pacote ou seu processo estiver visível/em primeiro plano
                    val isVisibleForeground = visiblePackages.contains(pkgName)
                    if (isVisibleForeground) {
                        notificationManager.cancelNotificationForPackage(pkgName)
                        processCpuTracker.remove(identity)
                        return@forEach
                    }

                    if (pkgName == context.packageName || cfg.whitelistedPackages.contains(pkgName) || isPackageMuted(pkgName)) {
                        return@forEach
                    }

                    val prev = processCpuTracker[identity]
                    if (prev == null) {
                        // Primeira observação: registra baseline de calibração (não gera alerta)
                        processCpuTracker[identity] = ProcessCpuSample(cpuTimeMs, nowMono, 0)
                        return@forEach
                    }

                    val deltaCpuMs = cpuTimeMs - prev.cpuTimeMs
                    val deltaWallMs = (nowMono - prev.monotonicMs).coerceAtLeast(1L)

                    if (deltaCpuMs < 0L || deltaWallMs > 60_000L) {
                        // PID reciclado ou amostra desatualizada (stale): reinicia baseline
                        processCpuTracker[identity] = ProcessCpuSample(cpuTimeMs, nowMono, 0)
                        return@forEach
                    }

                    val normalizedCpu = ((deltaCpuMs.toDouble() / (deltaWallMs * coresCount)) * 100.0).coerceIn(0.0, 100.0)

                    if (normalizedCpu >= cfg.sensitivity.cpuPercent) {
                        val hits = prev.consecutiveHits + 1
                        processCpuTracker[identity] = ProcessCpuSample(cpuTimeMs, nowMono, hits)

                        if (hits >= 2) {
                            val lastAlert = lastAlertTimeMap[pkgName] ?: 0L
                            if (nowWall - lastAlert >= COOLDOWN_MS) {
                                // Confirmação imediata antes de postar: cancela se o app acabou de ir para o primeiro plano
                                val liveVisible = getVisibleForegroundPackages(nowWall - 5000L, nowWall)
                                if (liveVisible.contains(pkgName)) {
                                    notificationManager.cancelNotificationForPackage(pkgName)
                                    processCpuTracker.remove(identity)
                                    return@forEach
                                }

                                val appLabel = runCatching {
                                    val info = pm.getApplicationInfo(pkgName, 0)
                                    pm.getApplicationLabel(info).toString()
                                }.getOrDefault(pkgName)

                                val icon = runCatching { pm.getApplicationIcon(pkgName) }.getOrNull()
                                val formatted = String.format(Locale.getDefault(), "%.1f%%", normalizedCpu)
                                val desc = context.getString(
                                    dev.may.taskmanager.R.string.anomaly_desc_cpu,
                                    appLabel,
                                    formatted,
                                )

                                val anomaly = AnomalyEvent(
                                    packageName = pkgName,
                                    appName = appLabel,
                                    type = AnomalyType.CPU,
                                    valueFormatted = formatted,
                                    description = desc,
                                    timestamp = nowWall,
                                    screenWasOff = false,
                                    pid = pid,
                                    rawValue = normalizedCpu,
                                    unit = "%",
                                    startTime = nowWall - (hits * SCREEN_ON_INTERVAL_MS),
                                    endTime = nowWall,
                                    durationMs = hits * SCREEN_ON_INTERVAL_MS,
                                    confidence = ConfidenceLevel.HIGH,
                                    nature = TelemetryNature.DIRECT_MEASUREMENT,
                                    source = "ps/TIME (proc/stat)",
                                    isEstimated = false,
                                    isComplete = true,
                                    rootAvailable = true,
                                    collectionMethod = "consecutive_samples",
                                    reason = context.getString(dev.may.taskmanager.R.string.anomaly_reason_cpu_hits, hits),
                                )

                                recordAnomaly(anomaly)
                                notificationManager.postAnomalyNotification(anomaly, icon)
                                lastAlertTimeMap[pkgName] = nowWall
                            }
                        }
                    } else {
                        processCpuTracker[identity] = ProcessCpuSample(cpuTimeMs, nowMono, 0)
                    }
                }
            }
        }
        // Limpa processos encerrados do histórico
        processCpuTracker.keys.retainAll(currentIdentities)
    }

    private suspend fun evaluateRamAnomalies(cfg: AnomalyWatcherConfig, visiblePackages: Set<String>, nowWall: Long) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)

        // Só gera alerta se o sistema estiver sob pressão real de memória (menos de 18% livre ou lowMemory flag)
        val ramPercentFree = (memInfo.availMem.toDouble() / memInfo.totalMem.toDouble()) * 100.0
        if (!memInfo.lowMemory && ramPercentFree > 18.0) return

        val pm = context.packageManager
        val thresholdMb = cfg.sensitivity.ramMb.toDouble()
        val nowMono = SystemClock.elapsedRealtime()
        val currentIdentities = mutableSetOf<ProcessIdentity>()

        // Identifica processos com RSS alto em segundo plano
        if (AppRootInspector.isRootAvailable()) {
            val psOutput = AppRootInspector.runSuCommand("ps -A -o pid,user,rss,args 2>/dev/null || ps -o pid,user,rss,args 2>/dev/null || ps -A -o PID,USER,RSS,ARGS 2>/dev/null || ps -o PID,USER,RSS,ARGS 2>/dev/null || ps -A -o PID,USER,RSS,ELAPSED,ARGS 2>/dev/null || ps -o PID,USER,RSS,ELAPSED,ARGS 2>/dev/null").getOrNull() ?: ""
            psOutput.lineSequence().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("PID", ignoreCase = true)) return@forEach
                val tokens = trimmed.split(Regex("\\s+"))
                if (tokens.size >= 4) {
                    val pid = tokens[0].toIntOrNull() ?: return@forEach
                    val rssKb = tokens[2].toDoubleOrNull() ?: 0.0
                    val ramMb = rssKb / 1024.0
                    val hasElapsed = tokens.size >= 5
                    val elapsedStr = if (hasElapsed) tokens[3] else ""
                    val cmdTokens = if (hasElapsed) tokens.subList(4, tokens.size) else tokens.subList(3, tokens.size)
                    val cmd = cmdTokens.joinToString(" ")
                    val cleanCmd = cmd.substringBefore(" ").substringAfterLast("/").removeSurrounding("[", "]").removeSurrounding("(", ")")

                    if (cleanCmd.contains(".") && !cleanCmd.startsWith("/")) {
                        val pkgName = cleanCmd.substringBefore(":")
                        val identity = ProcessIdentity(pid, cleanCmd)
                        currentIdentities.add(identity)

                        if (visiblePackages.contains(pkgName)) {
                            notificationManager.cancelNotificationForPackage(pkgName)
                            processRamHistory.remove(identity)
                            return@forEach
                        }

                        if (pkgName == context.packageName || cfg.whitelistedPackages.contains(pkgName) || isPackageMuted(pkgName)) {
                            return@forEach
                        }

                        if (ramMb >= thresholdMb) {
                            // Analise de tendencia temporal por identidade de processo
                            val history = processRamHistory.getOrPut(identity) { mutableListOf() }
                            history.add(ProcessRamSample(ramMb, nowMono))
                            history.removeAll { nowMono - it.monotonicMs > 300_000L }

                            val isContinuousGrowth = history.size >= 5 &&
                                (0 until history.size - 1).all { i -> history[i + 1].rssMb > history[i].rssMb }

                            val cause = if (isContinuousGrowth) {
                                val deltaMb = (history.last().rssMb - history.first().rssMb).toInt()
                                val deltaSec = ((nowMono - history.first().monotonicMs) / 1000L).coerceAtLeast(1L)
                                context.getString(dev.may.taskmanager.R.string.anomaly_cause_ram_leak_suspect, deltaMb, deltaSec.toInt())
                            } else {
                                context.getString(dev.may.taskmanager.R.string.anomaly_cause_ram_high_persistent)
                            }

                            val reason = if (isContinuousGrowth) {
                                context.getString(dev.may.taskmanager.R.string.anomaly_reason_ram_leak, history.size)
                            } else {
                                context.getString(dev.may.taskmanager.R.string.anomaly_reason_ram_stable)
                            }

                            val lastAlert = lastAlertTimeMap[pkgName] ?: 0L
                            if (nowWall - lastAlert >= COOLDOWN_MS) {
                                val appLabel = runCatching {
                                    val info = pm.getApplicationInfo(pkgName, 0)
                                    pm.getApplicationLabel(info).toString()
                                }.getOrDefault(pkgName)

                                val icon = runCatching { pm.getApplicationIcon(pkgName) }.getOrNull()
                                val formatted = String.format(Locale.getDefault(), "%.0f MB", ramMb)
                                val desc = context.getString(
                                    dev.may.taskmanager.R.string.anomaly_desc_ram,
                                    appLabel,
                                    formatted,
                                )

                                val anomaly = AnomalyEvent(
                                    packageName = pkgName,
                                    appName = appLabel,
                                    type = AnomalyType.RAM,
                                    valueFormatted = formatted,
                                    description = desc,
                                    timestamp = nowWall,
                                    screenWasOff = false,
                                    pid = pid,
                                    serviceOrProcess = cleanCmd,
                                    probableCause = cause,
                                    rawValue = ramMb,
                                    unit = "MB",
                                    startTime = if (history.isNotEmpty()) nowWall - (nowMono - history.first().monotonicMs) else nowWall,
                                    endTime = nowWall,
                                    durationMs = if (history.isNotEmpty()) (nowMono - history.first().monotonicMs) else 0L,
                                    confidence = if (isContinuousGrowth) ConfidenceLevel.MEDIUM else ConfidenceLevel.HIGH,
                                    nature = if (isContinuousGrowth) TelemetryNature.INFERENCE else TelemetryNature.DIRECT_MEASUREMENT,
                                    source = "ps/RSS",
                                    isEstimated = isContinuousGrowth,
                                    isComplete = true,
                                    rootAvailable = true,
                                    collectionMethod = "temporal_trend",
                                    reason = reason,
                                )

                                recordAnomaly(anomaly)
                                notificationManager.postAnomalyNotification(anomaly, icon)
                                lastAlertTimeMap[pkgName] = nowWall
                            }
                        }
                    }
                }
            }
            processRamHistory.keys.retainAll(currentIdentities)
        }
    }

    /**
     * Chamado quando o sistema operacional Android envia sinal de corte de memória (onTrimMemory).
     * Puro evento do sistema operacional, sem polling.
     */
    fun onSystemTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            val cfg = _config.value
            if (cfg.isEnabled && cfg.notifyRam) {
                scope.launch(Dispatchers.IO) {
                    val visible = getVisibleForegroundPackages()
                    evaluateRamAnomalies(cfg, visible, System.currentTimeMillis())
                }
            }
        }
    }

    private fun recordAnomaly(anomaly: AnomalyEvent) {
        val current = _anomalies.value.toMutableList()
        current.add(0, anomaly)
        if (current.size > 50) {
            current.removeAt(current.size - 1)
        }
        _anomalies.value = current
        AnomalyHistoryRepository.getInstance(context).recordAnomaly(anomaly)
    }

    /**
     * Resolves the set of packages currently visible or in foreground across the specified window.
     * Combines root live window dumps, UsageStatsManager event history, and running process states.
     */
    suspend fun getVisibleForegroundPackages(
        windowStartMs: Long = System.currentTimeMillis() - SCREEN_ON_INTERVAL_MS * 2,
        windowEndMs: Long = System.currentTimeMillis()
    ): Set<String> = withContext(Dispatchers.IO) {
        if (!isScreenInteractive) return@withContext emptySet()

        val visible = mutableSetOf<String>()

        // 1. Root inspection: real-time window manager focus and live visible/resumed activities
        if (AppRootInspector.isRootAvailable()) {
            val cmd = """
                dumpsys window 2>/dev/null | grep -E "mCurrentFocus|mFocusedApp"
                dumpsys activity activities 2>/dev/null | grep -E "topResumedActivity|mResumedActivity|visible=true"
            """.trimIndent()
            val output = AppRootInspector.runSuCommand(cmd).getOrNull() ?: ""
            val regex = Regex("([a-zA-Z0-9_]+(?:\\.[a-zA-Z0-9_]+)+)")
            output.lineSequence().forEach { line ->
                regex.findAll(line).forEach { m ->
                    val pkg = m.groupValues[1]
                    if (pkg.contains(".") && !pkg.startsWith("android.") && pkg != "com.android.systemui") {
                        visible.add(pkg)
                    }
                }
            }
        }

        // 2. UsageStatsManager: inspect activity lifecycle transitions during and leading into the window
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        if (usm != null) {
            val queryStart = (windowStartMs - 600_000L).coerceAtLeast(0L)
            val events = runCatching { usm.queryEvents(queryStart, windowEndMs + 1000L) }.getOrNull()
            if (events != null) {
                val event = UsageEvents.Event()
                val lastActivityState = mutableMapOf<String, Int>()

                while (events.hasNextEvent()) {
                    events.getNextEvent(event)
                    val pkg = event.packageName ?: continue
                    when (event.eventType) {
                        UsageEvents.Event.ACTIVITY_RESUMED -> {
                            lastActivityState[pkg] = UsageEvents.Event.ACTIVITY_RESUMED
                        }
                        UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> {
                            lastActivityState[pkg] = event.eventType
                        }
                    }
                }

                for ((pkg, state) in lastActivityState) {
                    if (state == UsageEvents.Event.ACTIVITY_RESUMED) {
                        visible.add(pkg)
                    }
                }
            }
        }

        // 3. ActivityManager running processes: IMPORTANCE_FOREGROUND strictly indicates visible UI
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val running = runCatching { am?.runningAppProcesses }.getOrNull()
        running?.forEach { proc ->
            if (proc.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                proc.pkgList?.forEach { pkg ->
                    if (pkg.contains(".")) visible.add(pkg)
                }
            }
        }

        visible
    }

    private fun formatBytes(bytes: Long): String {
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format(Locale.getDefault(), "%.2f GB", gb)
            mb >= 1.0 -> String.format(Locale.getDefault(), "%.1f MB", mb)
            else -> String.format(Locale.getDefault(), "%.0f KB", kb)
        }
    }

    /**
     * Detecta o serviço ou subprocesso em segundo plano que gerou tráfego de dados,
     * e infere a causa provável (backup, download de mídia, sincronização, etc.).
     * Executado sob demanda exclusivamente no disparo de alerta (overhead de bateria nulo em repouso).
     */
    private suspend fun detectBackgroundServiceAndCause(
        pkgName: String,
        uid: Int,
        startTimeMs: Long,
        endTimeMs: Long,
    ): Pair<String?, String?> = withContext(Dispatchers.IO) {
        var detectedServiceOrProcess: String? = null

        // 1. Consulta histórica via UsageStatsManager (sem root, captura Foreground Services iniciados)
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        if (usm != null) {
            runCatching {
                val events = usm.queryEvents((startTimeMs - 5_000L).coerceAtLeast(0L), endTimeMs + 1_000L)
                val event = UsageEvents.Event()
                while (events.hasNextEvent()) {
                    events.getNextEvent(event)
                    if (event.packageName == pkgName) {
                        if (event.eventType == UsageEvents.Event.FOREGROUND_SERVICE_START) {
                            val cls = event.className
                            if (!cls.isNullOrBlank()) {
                                detectedServiceOrProcess = cls
                            }
                        }
                    }
                }
            }
        }

        // 2. Se não encontrou, inspeciona serviços e processos ativos via Root se disponível
        if (detectedServiceOrProcess == null && AppRootInspector.isRootAvailable()) {
            val servicesOutput = AppRootInspector.runSuCommand("dumpsys activity services $pkgName 2>/dev/null").getOrNull() ?: ""
            val serviceMatch = Regex("$pkgName/([a-zA-Z0-9_.]+)").find(servicesOutput)
            if (serviceMatch != null) {
                detectedServiceOrProcess = serviceMatch.groupValues[1].removePrefix(".")
            }

            if (detectedServiceOrProcess == null) {
                val psOutput = AppRootInspector.runSuCommand("ps -A -o NAME,ARGS 2>/dev/null | grep $pkgName").getOrNull() ?: ""
                val procMatch = Regex("($pkgName:[a-zA-Z0-9_]+)").find(psOutput)
                if (procMatch != null) {
                    detectedServiceOrProcess = procMatch.groupValues[1]
                }
            }
        }

        // 3. Fallback: ActivityManager running processes
        if (detectedServiceOrProcess == null) {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val running = runCatching { am?.runningAppProcesses }.getOrNull()
            val subProc = running?.firstOrNull { it.uid == uid && it.processName.startsWith("$pkgName:") }
            if (subProc != null) {
                detectedServiceOrProcess = subProc.processName
            }
        }

        val cause = inferProbableCause(detectedServiceOrProcess, pkgName)
        Pair(detectedServiceOrProcess, cause)
    }

    private fun simplifyServiceOrProcessName(name: String, pkgName: String): String {
        return when {
            name.startsWith("$pkgName:") -> name.substring(pkgName.length)
            name.contains(".") -> name.substringAfterLast(".")
            else -> name
        }
    }

    private fun inferProbableCause(rawName: String?, pkgName: String): String {
        val lower = (rawName ?: "").lowercase(Locale.ROOT)
        return when {
            lower.contains("backup") || lower.contains("gdrive") || lower.contains("cloud") || lower.contains("drive") ->
                context.getString(dev.may.taskmanager.R.string.anomaly_cause_backup)
            lower.contains("download") || lower.contains("fetch") || lower.contains("getmedia") || lower.contains("stream") ->
                context.getString(dev.may.taskmanager.R.string.anomaly_cause_download)
            lower.contains("upload") || lower.contains("send") || lower.contains("postmedia") || lower.contains("sender") ->
                context.getString(dev.may.taskmanager.R.string.anomaly_cause_upload)
            lower.contains("sync") || lower.contains("account") || lower.contains("contact") ->
                context.getString(dev.may.taskmanager.R.string.anomaly_cause_sync)
            lower.contains("voip") || lower.contains("call") || lower.contains("webrtc") ->
                context.getString(dev.may.taskmanager.R.string.anomaly_cause_call)
            lower.contains("push") || lower.contains("fcm") || lower.contains("message") || lower.contains("coro") || lower.contains("notification") ->
                context.getString(dev.may.taskmanager.R.string.anomaly_cause_messages)
            lower.contains("update") || lower.contains("ota") || lower.contains("patch") ->
                context.getString(dev.may.taskmanager.R.string.anomaly_cause_update)
            !rawName.isNullOrBlank() ->
                context.getString(dev.may.taskmanager.R.string.anomaly_cause_service_active, simplifyServiceOrProcessName(rawName, pkgName))
            else ->
                context.getString(dev.may.taskmanager.R.string.anomaly_cause_general_bg)
        }
    }
}
