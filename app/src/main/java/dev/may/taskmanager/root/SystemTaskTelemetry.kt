// SPDX-License-Identifier: GPL-3.0-or-later
package dev.may.taskmanager.root

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import dev.may.taskmanager.data.AppInfo
import dev.may.taskmanager.telemetry.ConfidenceLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

enum class PowerImpactLevel(val labelPt: String, val labelEn: String) {
    MINIMAL("Mínimo", "Minimal"),
    LOW("Baixo", "Low"),
    MEDIUM("Médio", "Medium"),
    HIGH("Alto", "High"),
    VERY_HIGH("Muito Alto", "Very High")
}

data class SystemPerformanceSnapshot(
    val totalCpuPercent: Double = 0.0,
    val cpuCores: Int = 1,
    val cpuFrequenciesMhz: List<Long> = emptyList(),
    val maxCpuFrequencyMhz: Long = 0L,
    val uptimeMillis: Long = 0L,
    val totalProcesses: Int = 0,
    val totalThreads: Int = 0,
    val ramTotalMb: Double = 0.0,
    val ramUsedMb: Double = 0.0,
    val ramAvailableMb: Double = 0.0,
    val ramUsedPercent: Double = 0.0,
    val swapTotalMb: Double = 0.0,
    val swapUsedMb: Double = 0.0,
    val rxSpeedBps: Long = 0L,
    val txSpeedBps: Long = 0L,
    val totalRxBytes: Long = 0L,
    val totalTxBytes: Long = 0L,
    val networkType: String = "Offline",
    val gpuUsagePercent: Double? = null,
    val gpuClockMhz: Long? = null,
    val gpuModel: String? = null,
    val storageTotalGb: Double = 0.0,
    val storageUsedGb: Double = 0.0,
    val storageAvailableGb: Double = 0.0,
    val batteryPercent: Int = 0,
    val isCharging: Boolean = false,
    val batteryCurrentNowMa: Long? = null,
    val batteryVoltageMv: Long? = null,
    val batteryPowerWatts: Double? = null,
    val batteryTempCelsius: Float = 0f,
    val batteryHealth: String = "Boa",
    val chargeTimeRemainingMs: Long? = null,
    val timestamp: Long = System.currentTimeMillis(),
)

data class TaskProcessItem(
    val pid: Int,
    val packageName: String,
    val processName: String,
    val appName: String,
    val user: String,
    val cpuPercent: Double,
    val ramMb: Double,
    val isForeground: Boolean,
    val isSystemProcess: Boolean = false,
    val appInfo: AppInfo? = null,
    val powerImpact: PowerImpactLevel = PowerImpactLevel.LOW,
    val estimatedPowerMw: Double = 0.0,
    val pssMb: Double? = null,
    val rssMb: Double? = null,
    val confidence: ConfidenceLevel = ConfidenceLevel.HIGH,
)

data class RunningTasksSnapshot(
    val foregroundApps: List<TaskProcessItem> = emptyList(),
    val backgroundApps: List<TaskProcessItem> = emptyList(),
    val systemProcesses: List<TaskProcessItem> = emptyList(),
    val timestamp: Long = System.currentTimeMillis(),
    val rootAvailable: Boolean = true,
    val isTelemetryLimited: Boolean = false,
    val diagnosticMessage: String? = null,
)

object SystemTaskInspector {

    private data class CpuSample(val total: Long, val idle: Long, val timestamp: Long)
    private val lastCpuSample = AtomicReference<CpuSample?>(null)

    private data class NetSample(val rx: Long, val tx: Long, val timestamp: Long)
    private val lastNetSample = AtomicReference<NetSample?>(null)

    data class ProcessKey(val pid: Int, val command: String)
    private data class ProcCpuSnapshot(val cpuTimeMs: Long, val timestampMs: Long)
    internal val processCpuHistory = ConcurrentHashMap<ProcessKey, ProcCpuSnapshot>()

    /**
     * Parses time strings produced by ps (e.g., "0:01.01", "52:15.18", "01:36:15.22", "1-02:03:04.50")
     * into total milliseconds.
     */
    fun parseCpuTimeToMs(timeStr: String): Long {
        val trimmed = timeStr.trim()
        if (trimmed.isEmpty()) return 0L

        var days = 0L
        var rest = trimmed
        if (rest.contains("-")) {
            val dStr = rest.substringBefore("-")
            days = dStr.toLongOrNull() ?: 0L
            rest = rest.substringAfter("-")
        }

        val parts = rest.split(":")
        return when (parts.size) {
            3 -> {
                val h = parts[0].toLongOrNull() ?: 0L
                val m = parts[1].toLongOrNull() ?: 0L
                val secParts = parts[2].split(".")
                val s = secParts[0].toLongOrNull() ?: 0L
                val ms = if (secParts.size > 1) {
                    secParts[1].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
                } else 0L
                days * 86_400_000L + h * 3_600_000L + m * 60_000L + s * 1_000L + ms
            }
            2 -> {
                val m = parts[0].toLongOrNull() ?: 0L
                val secParts = parts[1].split(".")
                val s = secParts[0].toLongOrNull() ?: 0L
                val ms = if (secParts.size > 1) {
                    secParts[1].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
                } else 0L
                days * 86_400_000L + m * 60_000L + s * 1_000L + ms
            }
            1 -> {
                val secParts = parts[0].split(".")
                val s = secParts[0].toLongOrNull() ?: 0L
                val ms = if (secParts.size > 1) {
                    secParts[1].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
                } else 0L
                days * 86_400_000L + s * 1_000L + ms
            }
            else -> 0L
        }
    }

    /**
     * Parses elapsed process uptime produced by ps (e.g., "00:05", "12:34", "01:23:45", "1-02:03:04")
     * into total milliseconds.
     */
    /**
     * Parses elapsed process uptime produced by ps (e.g., "00:05", "12:34", "01:23:45", "1-02:03:04")
     * into total milliseconds.
     */
    fun parseElapsedTimeToMs(elapsedStr: String): Long {
        val trimmed = elapsedStr.trim()
        if (trimmed.isEmpty()) return 0L
        var days = 0L
        var rest = trimmed
        if (rest.contains("-")) {
            days = rest.substringBefore("-").toLongOrNull() ?: 0L
            rest = rest.substringAfter("-")
        }
        val parts = rest.split(":")
        return when (parts.size) {
            3 -> {
                val h = parts[0].toLongOrNull() ?: 0L
                val m = parts[1].toLongOrNull() ?: 0L
                val s = parts[2].toLongOrNull() ?: 0L
                days * 86_400_000L + h * 3_600_000L + m * 60_000L + s * 1_000L
            }
            2 -> {
                val m = parts[0].toLongOrNull() ?: 0L
                val s = parts[1].toLongOrNull() ?: 0L
                days * 86_400_000L + m * 60_000L + s * 1_000L
            }
            1 -> {
                val s = parts[0].toLongOrNull() ?: 0L
                days * 86_400_000L + s * 1_000L
            }
            else -> 0L
        }
    }

    data class ProcStatInfo(
        val pid: Int,
        val comm: String,
        val state: String,
        val utimeTicks: Long,
        val stimeTicks: Long,
        val starttimeTicks: Long,
    ) {
        val totalCpuTicks: Long get() = utimeTicks + stimeTicks
    }

    /**
     * Parses Linux /proc/<pid>/stat line accurately handling comm fields with spaces or parentheses.
     */
    fun parseProcStat(statContent: String): ProcStatInfo? {
        val lastParen = statContent.lastIndexOf(')')
        if (lastParen < 0) return null
        val firstParen = statContent.indexOf('(')
        val pidStr = statContent.substring(0, if (firstParen > 0) firstParen else statContent.indexOf(' ')).trim()
        val pid = pidStr.toIntOrNull() ?: return null
        val comm = if (firstParen in 0 until lastParen) statContent.substring(firstParen + 1, lastParen) else ""
        val rest = statContent.substring(lastParen + 1).trim()
        val tokens = rest.split(Regex("\\s+"))
        if (tokens.size < 20) return null
        val state = tokens[0]
        val utime = tokens[11].toLongOrNull() ?: 0L
        val stime = tokens[12].toLongOrNull() ?: 0L
        val starttime = tokens[19].toLongOrNull() ?: 0L
        return ProcStatInfo(pid, comm, state, utime, stime, starttime)
    }

    /**
     * Parses VmRSS in kilobytes from Linux /proc/<pid>/status content.
     */
    fun parseVmRssKb(statusContent: String): Long? {
        statusContent.lineSequence().forEach { line ->
            if (line.startsWith("VmRSS:")) {
                val kbStr = line.substringAfter("VmRSS:").trim().substringBefore(" ").trim()
                return kbStr.toLongOrNull()
            }
        }
        return null
    }

    fun readDeviceTotalNetBytes(): Pair<Long, Long> {
        val tsRx = TrafficStats.getTotalRxBytes()
        val tsTx = TrafficStats.getTotalTxBytes()
        if (tsRx > 0L && tsTx > 0L) {
            return Pair(tsRx, tsTx)
        }
        return runCatching {
            var rxSum = 0L
            var txSum = 0L
            File("/proc/net/dev").forEachLine { line ->
                val trimmed = line.trim()
                if (trimmed.contains(":") && !trimmed.startsWith("lo:")) {
                    val parts = trimmed.substringAfter(":").trim().split(Regex("\\s+"))
                    if (parts.size >= 9) {
                        val rx = parts[0].toLongOrNull() ?: 0L
                        val tx = parts[8].toLongOrNull() ?: 0L
                        rxSum += rx
                        txSum += tx
                    }
                }
            }
            Pair(
                if (tsRx > 0L) tsRx else rxSum,
                if (tsTx > 0L) tsTx else txSum,
            )
        }.getOrDefault(Pair(maxOf(0L, tsRx), maxOf(0L, tsTx)))
    }

    /**
     * Samples overall device performance metrics (CPU, RAM, GPU, Network, Storage).
     */
    suspend fun getPerformanceSnapshot(context: Context): SystemPerformanceSnapshot = withContext(Dispatchers.IO) {
        val now = SystemClock.elapsedRealtime()

        // 1. CPU Utilization
        val cpuUsage = sampleGlobalCpuUsage(now)
        val coresCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val coreFreqs = readCpuCoreFrequencies(coresCount)
        val maxFreq = coreFreqs.maxOrNull() ?: 0L

        // 2. RAM and Swap / zRAM
        val (ramTotal, ramAvail, ramUsed, ramPercent, swapTotal, swapUsed) = readRamAndSwap(context)

        // 3. Network Transfer & Speeds
        val (totalRx, totalTx) = readDeviceTotalNetBytes()
        var rxSpeed = 0L
        var txSpeed = 0L

        val prevNet = lastNetSample.getAndSet(NetSample(totalRx, totalTx, now))
        if (prevNet != null) {
            val dt = (now - prevNet.timestamp) / 1000.0
            if (dt in 0.4..30.0) {
                val dRx = totalRx - prevNet.rx
                val dTx = totalTx - prevNet.tx
                if (prevNet.rx > 0L && dRx > 0) rxSpeed = (dRx / dt).toLong()
                if (prevNet.tx > 0L && dTx > 0) txSpeed = (dTx / dt).toLong()
            }
        }

        val netType = detectNetworkType(context)

        // 4. GPU Telemetry
        val (gpuPercent, gpuClock, gpuModel) = sampleGpuInfo()

        // 5. Storage
        val (storageTotal, storageUsed, storageAvail) = sampleStorage()

        // 6. Process and Thread counts from /proc/loadavg
        val (procCount, threadCount) = readLoadAvgProcCounts()

        // 7. Battery & Power Hardware Telemetry
        val batteryInfo = sampleBatteryInfo(context)

        SystemPerformanceSnapshot(
            totalCpuPercent = cpuUsage,
            cpuCores = coresCount,
            cpuFrequenciesMhz = coreFreqs,
            maxCpuFrequencyMhz = maxFreq,
            uptimeMillis = SystemClock.elapsedRealtime(),
            totalProcesses = procCount,
            totalThreads = threadCount,
            ramTotalMb = ramTotal,
            ramUsedMb = ramUsed,
            ramAvailableMb = ramAvail,
            ramUsedPercent = ramPercent,
            swapTotalMb = swapTotal,
            swapUsedMb = swapUsed,
            rxSpeedBps = rxSpeed,
            txSpeedBps = txSpeed,
            totalRxBytes = totalRx,
            totalTxBytes = totalTx,
            networkType = netType,
            gpuUsagePercent = gpuPercent,
            gpuClockMhz = gpuClock,
            gpuModel = gpuModel,
            storageTotalGb = storageTotal,
            storageUsedGb = storageUsed,
            storageAvailableGb = storageAvail,
            batteryPercent = batteryInfo.percent,
            isCharging = batteryInfo.isCharging,
            batteryCurrentNowMa = batteryInfo.currentNowMa,
            batteryVoltageMv = batteryInfo.voltageMv,
            batteryPowerWatts = batteryInfo.powerWatts,
            batteryTempCelsius = batteryInfo.tempCelsius,
            batteryHealth = batteryInfo.health,
            chargeTimeRemainingMs = batteryInfo.chargeTimeRemainingMs,
            timestamp = System.currentTimeMillis(),
        )
    }

    /**
     * Collects and categorizes all running processes into Foreground Apps,
     * Background Apps, and System Processes with live CPU% and RAM usage.
     */
    suspend fun getRunningTasks(
        context: Context,
        installedApps: List<AppInfo>,
    ): RunningTasksSnapshot = withContext(Dispatchers.IO) {
        val appsByPackage = installedApps.associateBy { it.packageName }
        val pm = context.packageManager

        val isRoot = AppRootInspector.isRootAvailable()
        if (!isRoot) {
            // Estrategia sem Root: consulta honesta via ActivityManager e PSS sem invencao de CPU
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val runningProcs = am?.runningAppProcesses ?: emptyList()
            val fgList = mutableListOf<TaskProcessItem>()
            val bgList = mutableListOf<TaskProcessItem>()

            for (proc in runningProcs) {
                val pkg = proc.pkgList?.firstOrNull() ?: proc.processName.substringBefore(":")
                val matchedApp = appsByPackage[pkg]
                val label = matchedApp?.label ?: runCatching {
                    val info = pm.getApplicationInfo(pkg, 0)
                    pm.getApplicationLabel(info).toString()
                }.getOrDefault(pkg)

                val isFg = proc.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
                val memInfo = runCatching { am?.getProcessMemoryInfo(intArrayOf(proc.pid)) }.getOrNull()
                val pssMb = memInfo?.firstOrNull()?.totalPss?.let { it / 1024.0 } ?: 0.0

                val item = TaskProcessItem(
                    pid = proc.pid,
                    packageName = pkg,
                    processName = proc.processName,
                    appName = label,
                    user = "app",
                    cpuPercent = 0.0,
                    ramMb = pssMb,
                    isForeground = isFg,
                    isSystemProcess = false,
                    appInfo = matchedApp,
                    pssMb = pssMb,
                    confidence = ConfidenceLevel.MEDIUM,
                )
                if (isFg) fgList.add(item) else bgList.add(item)
            }

            return@withContext RunningTasksSnapshot(
                foregroundApps = fgList,
                backgroundApps = bgList,
                systemProcesses = emptyList(),
                timestamp = System.currentTimeMillis(),
                rootAvailable = false,
                isTelemetryLimited = true,
                diagnosticMessage = "Dispositivo sem Root: exibindo processos acessiveis com memoria PSS; telemetria individual de CPU indisponivel sem acesso Root (SELinux).",
            )
        }

        // Shell script to query top/ps and current focused/resumed activity via root
        val shellScript = """
            echo "===ACTIVITIES==="
            dumpsys window 2>/dev/null | grep -E "mCurrentFocus|mFocusedApp"
            dumpsys activity activities 2>/dev/null | grep -E "topResumedActivity|mResumedActivity|visible=true"
            dumpsys activity recents 2>/dev/null | grep -E "Recent #0"
            echo "===PS==="
            ps -A -o pid,user,s,rss,%mem,time,args 2>/dev/null || ps -o pid,user,s,rss,%mem,time,args 2>/dev/null || ps -A -o PID,USER,S,RSS,%MEM,TIME,ARGS 2>/dev/null || ps -o PID,USER,S,RSS,%MEM,TIME,ARGS 2>/dev/null || ps -A -o pid,user,%cpu,%mem,args 2>/dev/null || ps -o pid,user,%cpu,%mem,args 2>/dev/null || ps -A -o PID,USER,%CPU,%MEM,ARGS 2>/dev/null || ps -o PID,USER,%CPU,%MEM,ARGS 2>/dev/null || ps -A -o PID,USER,S,RSS,%MEM,TIME+,ELAPSED,ARGS 2>/dev/null || ps -o PID,USER,S,RSS,%MEM,TIME+,ELAPSED,ARGS 2>/dev/null || ps -A 2>/dev/null || ps 2>/dev/null
        """.trimIndent()

        val output = AppRootInspector.runSuCommand(shellScript).getOrDefault("")
        val sections = parseSections(output)

        val activitiesText = sections["ACTIVITIES"] ?: ""
        val psText = sections["PS"] ?: ""

        val foregroundPackages = extractForegroundPackages(activitiesText)

        // Determine CPU cores count to normalize raw Irix-mode CPU percentage (where 100% = 1 core)
        // to Solaris / Windows Task Manager total-system scale (where 100% = all cores fully utilized).
        val coresCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

        val nowRealtime = SystemClock.elapsedRealtime()

        // Parse processes with instantaneous delta CPU sampling (preventing cumulative lifetime %CPU decay)
        val rawList = parsePsLines(psText, coresCount = coresCount, nowRealtime = nowRealtime)

        // Get total system RAM to convert %MEM into MB
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memInfo)
        val totalRamMb = memInfo.totalMem / (1024.0 * 1024.0)

        val foregroundList = mutableListOf<TaskProcessItem>()
        val backgroundList = mutableListOf<TaskProcessItem>()
        val systemList = mutableListOf<TaskProcessItem>()

        // Cache for resolving package app names
        val appNameCache = mutableMapOf<String, String>()

        for (proc in rawList) {
            val cmd = proc.args.trim()
            val baseCmd = cmd.substringBefore(" ").substringAfterLast("/")
            val cleanCmd = baseCmd.removeSurrounding("[", "]").removeSurrounding("(", ")")

            val matchedApp = appsByPackage[cleanCmd]
                ?: appsByPackage[cleanCmd.substringBefore(":")]
                ?: appsByPackage.values.firstOrNull { app ->
                    AppRootInspector.isPackageProcess(cmd, app.packageName) ||
                        cleanCmd == app.packageName ||
                        cleanCmd.startsWith("${app.packageName}:")
                }

            if (matchedApp != null) {
                val pkg = matchedApp.packageName
                val isFg = foregroundPackages.contains(pkg)
                val ramMb = proc.rssMb ?: ((proc.memPercent / 100.0) * totalRamMb)
                val (impact, powerMw) = calculatePowerImpact(proc.cpuPercent, isFg, isSystem = false)
                val item = TaskProcessItem(
                    pid = proc.pid,
                    packageName = pkg,
                    processName = cleanCmd,
                    appName = matchedApp.label,
                    user = proc.user,
                    cpuPercent = proc.cpuPercent,
                    ramMb = ramMb,
                    isForeground = isFg,
                    isSystemProcess = false,
                    appInfo = matchedApp,
                    powerImpact = impact,
                    estimatedPowerMw = powerMw,
                    rssMb = proc.rssMb,
                )
                if (isFg) {
                    foregroundList.add(item)
                } else {
                    backgroundList.add(item)
                }
            } else {
                // Check if user is an Android app user (e.g. u0_a...) or package name format
                val isAppUser = proc.user.startsWith("u0_a") || proc.user.startsWith("u0_i")
                var detectedPkg: String? = null
                if (cleanCmd.contains(".") && !cleanCmd.startsWith("/")) {
                    val potentialPkg = cleanCmd.substringBefore(":")
                    if (potentialPkg.length > 3 && potentialPkg.contains(".")) {
                        detectedPkg = potentialPkg
                    }
                }

                if (detectedPkg != null || isAppUser) {
                    val pkgName = detectedPkg ?: cleanCmd.substringBefore(":")
                    val isFg = foregroundPackages.contains(pkgName)
                    val label = appNameCache.getOrPut(pkgName) {
                        runCatching {
                            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                pm.getApplicationInfo(pkgName, PackageManager.ApplicationInfoFlags.of(0))
                            } else {
                                @Suppress("DEPRECATION")
                                pm.getApplicationInfo(pkgName, 0)
                            }
                            pm.getApplicationLabel(info).toString()
                        }.getOrDefault(pkgName)
                    }

                    val ramMb = proc.rssMb ?: ((proc.memPercent / 100.0) * totalRamMb)
                    val (impact, powerMw) = calculatePowerImpact(proc.cpuPercent, isFg, isSystem = false)
                    val resolvedAppInfo = appsByPackage[pkgName] ?: AppInfo(
                        componentName = ComponentName(pkgName, ""),
                        label = label,
                    )
                    val item = TaskProcessItem(
                        pid = proc.pid,
                        packageName = pkgName,
                        processName = cleanCmd,
                        appName = label,
                        user = proc.user,
                        cpuPercent = proc.cpuPercent,
                        ramMb = ramMb,
                        isForeground = isFg,
                        isSystemProcess = false,
                        appInfo = resolvedAppInfo,
                        powerImpact = impact,
                        estimatedPowerMw = powerMw,
                        rssMb = proc.rssMb,
                    )

                    if (isFg) {
                        foregroundList.add(item)
                    } else {
                        backgroundList.add(item)
                    }
                } else {
                    // System daemon / kernel thread
                    val ramMb = proc.rssMb ?: ((proc.memPercent / 100.0) * totalRamMb)
                    val (impact, powerMw) = calculatePowerImpact(proc.cpuPercent, isForeground = false, isSystem = true)
                    systemList.add(
                        TaskProcessItem(
                            pid = proc.pid,
                            packageName = cleanCmd,
                            processName = cleanCmd,
                            appName = cleanCmd,
                            user = proc.user,
                            cpuPercent = proc.cpuPercent,
                            ramMb = ramMb,
                            isForeground = false,
                            isSystemProcess = true,
                            appInfo = null,
                            powerImpact = impact,
                            estimatedPowerMw = powerMw,
                            rssMb = proc.rssMb,
                        )
                    )
                }
            }
        }

        // Sort each category by CPU descending, then by RAM descending
        val sortedFg = foregroundList.sortedWith(compareByDescending<TaskProcessItem> { it.cpuPercent }.thenByDescending { it.ramMb })
        val sortedBg = backgroundList.sortedWith(compareByDescending<TaskProcessItem> { it.cpuPercent }.thenByDescending { it.ramMb })
        val sortedSys = systemList.sortedWith(compareByDescending<TaskProcessItem> { it.cpuPercent }.thenByDescending { it.ramMb })

        RunningTasksSnapshot(
            foregroundApps = sortedFg,
            backgroundApps = sortedBg,
            systemProcesses = sortedSys,
            timestamp = System.currentTimeMillis(),
            rootAvailable = true,
            isTelemetryLimited = false,
        )
    }

    internal data class RawProc(
        val pid: Int,
        val user: String,
        val cpuPercent: Double,
        val memPercent: Double,
        val args: String,
        val rssMb: Double? = null,
        val elapsedStr: String = "",
    )

    fun parsePsLines(
        psText: String,
        coresCount: Int = 1,
        nowRealtime: Long = SystemClock.elapsedRealtime(),
    ): List<RawProc> {
        val rawList = mutableListOf<RawProc>()
        val currentKeys = mutableSetOf<ProcessKey>()
        val effectiveCores = coresCount.coerceAtLeast(1)

        var pidIdx = 0
        var userIdx = 1
        var stateIdx = -1
        var rssIdx = -1
        var memIdx = -1
        var cpuIdx = -1
        var timeIdx = -1
        var elapsedIdx = -1
        var argsIdx = -1
        var hasHeader = false

        psText.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach

            // Check if this line is the header
            if (trimmed.startsWith("PID", ignoreCase = true) && !hasHeader) {
                val headerTokens = trimmed.split(Regex("\\s+")).map { it.uppercase(Locale.ROOT) }
                pidIdx = headerTokens.indexOf("PID")
                userIdx = headerTokens.indexOf("USER")
                stateIdx = headerTokens.indexOfFirst { it == "S" || it == "STAT" || it == "STATE" }
                rssIdx = headerTokens.indexOf("RSS")
                memIdx = headerTokens.indexOf("%MEM")
                cpuIdx = headerTokens.indexOf("%CPU")
                timeIdx = headerTokens.indexOfFirst { it == "TIME" || it == "TIME+" }
                elapsedIdx = headerTokens.indexOf("ELAPSED")
                argsIdx = headerTokens.indexOfFirst { it == "ARGS" || it == "COMMAND" || it == "CMD" }
                hasHeader = true
                return@forEach
            }

            val tokens = trimmed.split(Regex("\\s+"))
            if (tokens.size < 4) return@forEach

            val pid = tokens.getOrNull(if (pidIdx >= 0) pidIdx else 0)?.toIntOrNull() ?: return@forEach
            val user = tokens.getOrNull(if (userIdx >= 0) userIdx else 1) ?: "unknown"

            val state = if (stateIdx >= 0 && stateIdx < tokens.size) tokens[stateIdx] else "S"
            val rssKb = if (rssIdx >= 0 && rssIdx < tokens.size) tokens[rssIdx].toDoubleOrNull() ?: 0.0 else 0.0
            val rssMb = if (rssIdx >= 0 && rssKb > 0.0) rssKb / 1024.0 else null
            val memPercent = if (memIdx >= 0 && memIdx < tokens.size) tokens[memIdx].toDoubleOrNull() ?: 0.0 else 0.0
            val rawCpu = if (cpuIdx >= 0 && cpuIdx < tokens.size) tokens[cpuIdx].replace("%", "").toDoubleOrNull() else null
            val timeStr = if (timeIdx >= 0 && timeIdx < tokens.size) tokens[timeIdx] else null
            val elapsedStr = if (elapsedIdx >= 0 && elapsedIdx < tokens.size) tokens[elapsedIdx] else ""

            val args = if (argsIdx >= 0 && argsIdx < tokens.size) {
                tokens.subList(argsIdx, tokens.size).joinToString(" ")
            } else {
                val maxCol = maxOf(pidIdx, userIdx, stateIdx, rssIdx, memIdx, cpuIdx, timeIdx, elapsedIdx)
                if (maxCol >= 0 && maxCol + 1 < tokens.size) {
                    tokens.drop(maxCol + 1).joinToString(" ")
                } else {
                    tokens.lastOrNull() ?: ""
                }
            }

            val cleanCmd = args.substringBefore(" ").substringAfterLast("/").removeSurrounding("[", "]").removeSurrounding("(", ")")
            val key = ProcessKey(pid, cleanCmd)
            currentKeys.add(key)

            var cpuPercent = 0.0

            if (timeStr != null && timeStr.contains(":")) {
                val cpuTimeMs = parseCpuTimeToMs(timeStr)
                val prev = processCpuHistory[key]
                processCpuHistory[key] = ProcCpuSnapshot(cpuTimeMs, nowRealtime)

                cpuPercent = if (prev != null) {
                    val deltaCpuMs = cpuTimeMs - prev.cpuTimeMs
                    val deltaWallMs = (nowRealtime - prev.timestampMs).coerceAtLeast(1L)
                    if (deltaCpuMs < 0L || deltaWallMs > 60000L) {
                        // PID was recycled or snapshot is stale
                        if (rawCpu != null) (rawCpu / effectiveCores).coerceIn(0.0, 100.0) else 0.0
                    } else if (deltaCpuMs == 0L) {
                        0.0
                    } else {
                        ((deltaCpuMs.toDouble() / (deltaWallMs * effectiveCores)) * 100.0).coerceIn(0.0, 100.0)
                    }
                } else {
                    if (rawCpu != null) {
                        (rawCpu / effectiveCores).coerceIn(0.0, 100.0)
                    } else {
                        0.0
                    }
                }
            } else if (rawCpu != null) {
                cpuPercent = (rawCpu / effectiveCores).coerceIn(0.0, 100.0)
            }

            rawList.add(RawProc(pid, user, cpuPercent, memPercent, args, rssMb, elapsedStr))
        }

        // Clean up history for dead/terminated PIDs
        processCpuHistory.keys.retainAll(currentKeys)

        return rawList
    }

    private suspend fun sampleGlobalCpuUsage(now: Long): Double {
        return try {
            var firstLine = try {
                val statFile = File("/proc/stat")
                if (statFile.canRead()) {
                    statFile.bufferedReader().use { it.readLine() }
                } else null
            } catch (_: Throwable) {
                null
            }

            // Fallback via root execution if SELinux denies direct access
            if (firstLine.isNullOrBlank()) {
                firstLine = AppRootInspector.runSuCommand("head -n 1 /proc/stat 2>/dev/null").getOrNull()?.trim()
            }

            if (!firstLine.isNullOrBlank() && firstLine.startsWith("cpu ")) {
                val parts = firstLine.removePrefix("cpu ").trim().split(Regex("\\s+"))
                if (parts.size >= 4) {
                    val user = parts[0].toLongOrNull() ?: 0L
                    val nice = parts[1].toLongOrNull() ?: 0L
                    val system = parts[2].toLongOrNull() ?: 0L
                    val idle = parts[3].toLongOrNull() ?: 0L
                    val iowait = parts.getOrNull(4)?.toLongOrNull() ?: 0L
                    val irq = parts.getOrNull(5)?.toLongOrNull() ?: 0L
                    val softirq = parts.getOrNull(6)?.toLongOrNull() ?: 0L
                    val steal = parts.getOrNull(7)?.toLongOrNull() ?: 0L

                    val total = user + nice + system + idle + iowait + irq + softirq + steal
                    val idleTotal = idle + iowait

                    val prev = lastCpuSample.getAndSet(CpuSample(total, idleTotal, now))
                    if (prev != null) {
                        val dTotal = total - prev.total
                        val dIdle = idleTotal - prev.idle
                        if (dTotal > 0) {
                            val usage = ((dTotal - dIdle).toDouble() / dTotal.toDouble()) * 100.0
                            return usage.coerceIn(0.0, 100.0)
                        }
                    } else {
                        // First sample ever: briefly delay to obtain an immediate baseline calculation
                        delay(120L)
                        return sampleGlobalCpuUsage(SystemClock.elapsedRealtime())
                    }
                }
            }
            0.0
        } catch (_: Throwable) {
            0.0
        }
    }

    private suspend fun readCpuCoreFrequencies(cores: Int): List<Long> {
        val targetSize = maxOf(cores, 8)
        val freqs = MutableList(targetSize) { 0L }
        var needRootFallback = false

        for (i in 0 until targetSize) {
            val freqFile = File("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq")
            val freqKHz = if (freqFile.canRead()) {
                runCatching { freqFile.readText().trim().toLongOrNull() }.getOrNull()
            } else null

            if (freqKHz != null && freqKHz > 0) {
                freqs[i] = freqKHz / 1000 // Convert to MHz
            } else {
                needRootFallback = true
            }
        }

        if (needRootFallback) {
            val cmd = (0 until targetSize).joinToString("; ") { i ->
                "cat /sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq 2>/dev/null || echo 0"
            }
            val raw = AppRootInspector.runSuCommand(cmd).getOrNull() ?: ""
            val lines = raw.lines().map { it.trim().toLongOrNull() ?: 0L }
            for (i in 0 until minOf(targetSize, lines.size)) {
                val khz = lines[i]
                if (khz > 0) {
                    freqs[i] = khz / 1000
                }
            }
        }

        return freqs
    }

    private fun readRamAndSwap(context: Context): RamSwapData {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memInfo)

        val ramTotalMb = memInfo.totalMem / (1024.0 * 1024.0)
        val ramAvailMb = memInfo.availMem / (1024.0 * 1024.0)
        val ramUsedMb = (ramTotalMb - ramAvailMb).coerceAtLeast(0.0)
        val ramPercent = if (ramTotalMb > 0) (ramUsedMb / ramTotalMb) * 100.0 else 0.0

        var swapTotalMb = 0.0
        var swapUsedMb = 0.0

        runCatching {
            val memInfoFile = File("/proc/meminfo")
            if (memInfoFile.canRead()) {
                memInfoFile.forEachLine { line ->
                    if (line.startsWith("SwapTotal:")) {
                        val kb = line.substringAfter(":").trim().substringBefore(" ").trim().toDoubleOrNull() ?: 0.0
                        swapTotalMb = kb / 1024.0
                    } else if (line.startsWith("SwapFree:")) {
                        val freeKb = line.substringAfter(":").trim().substringBefore(" ").trim().toDoubleOrNull() ?: 0.0
                        val freeMb = freeKb / 1024.0
                        swapUsedMb = (swapTotalMb - freeMb).coerceAtLeast(0.0)
                    }
                }
            }
        }

        return RamSwapData(ramTotalMb, ramAvailMb, ramUsedMb, ramPercent, swapTotalMb, swapUsedMb)
    }

    private data class RamSwapData(
        val totalMb: Double,
        val availMb: Double,
        val usedMb: Double,
        val usedPercent: Double,
        val swapTotalMb: Double,
        val swapUsedMb: Double,
    )
    

    @SuppressLint("MissingPermission")
    private fun detectNetworkType(context: Context): String {
        return runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val activeNet = cm?.activeNetwork ?: return "Sem conexão"
            val caps = cm.getNetworkCapabilities(activeNet) ?: return "Sem conexão"

            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Dados Móveis"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                else -> "Conectado"
            }
        }.getOrDefault("Offline")
    }

    private fun sampleGpuInfo(): Triple<Double?, Long?, String?> {
        // Try Qualcomm Adreno sysfs
        val adrenoBusy = File("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage")
        val adrenoClock = File("/sys/class/kgsl/kgsl-3d0/gpuclk")

        if (adrenoBusy.canRead()) {
            val busy = runCatching { adrenoBusy.readText().trim().removeSuffix("%").toDoubleOrNull() }.getOrNull()
            val clockHz = runCatching { adrenoClock.readText().trim().toLongOrNull() }.getOrNull()
            val clockMhz = if (clockHz != null && clockHz > 0) clockHz / 1_000_000L else null
            return Triple(busy, clockMhz, "Qualcomm Adreno")
        }

        // Try ARM Mali sysfs
        val maliUtil = File("/sys/class/misc/mali0/device/utilization")
        val maliClock = File("/sys/class/misc/mali0/device/clock")
        if (maliUtil.canRead()) {
            val util = runCatching { maliUtil.readText().trim().toDoubleOrNull() }.getOrNull()
            val clockHz = runCatching { maliClock.readText().trim().toLongOrNull() }.getOrNull()
            val clockMhz = if (clockHz != null && clockHz > 0) clockHz / 1_000_000L else null
            return Triple(util, clockMhz, "ARM Mali")
        }

        return Triple(null, null, null)
    }

    private fun sampleStorage(): Triple<Double, Double, Double> {
        return runCatching {
            val stat = StatFs(Environment.getDataDirectory().path)
            val totalBytes = stat.totalBytes
            val availBytes = stat.availableBytes
            val usedBytes = totalBytes - availBytes

            val totalGb = totalBytes / (1024.0 * 1024.0 * 1024.0)
            val usedGb = usedBytes / (1024.0 * 1024.0 * 1024.0)
            val availGb = availBytes / (1024.0 * 1024.0 * 1024.0)

            Triple(totalGb, usedGb, availGb)
        }.getOrDefault(Triple(0.0, 0.0, 0.0))
    }

    private fun readLoadAvgProcCounts(): Pair<Int, Int> {
        return runCatching {
            val loadAvgFile = File("/proc/loadavg")
            if (loadAvgFile.canRead()) {
                val parts = loadAvgFile.readText().trim().split(Regex("\\s+"))
                if (parts.size >= 4) {
                    val procParts = parts[3].split("/")
                    val activeThreads = procParts.getOrNull(0)?.toIntOrNull() ?: 0
                    val totalEntities = procParts.getOrNull(1)?.toIntOrNull() ?: 0
                    return Pair(totalEntities, activeThreads)
                }
            }
            Pair(0, 0)
        }.getOrDefault(Pair(0, 0))
    }

    data class BatteryInfoSample(
        val percent: Int = 0,
        val isCharging: Boolean = false,
        val currentNowMa: Long? = null,
        val voltageMv: Long? = null,
        val powerWatts: Double? = null,
        val tempCelsius: Float = 0f,
        val health: String = "Boa",
        val chargeTimeRemainingMs: Long? = null,
    )

    fun sampleBatteryInfo(context: Context): BatteryInfoSample {
        var percent = 0
        var isCharging = false
        var voltageMv: Long? = null
        var tempCelsius = 0f
        var healthStr = "Boa"

        runCatching {
            val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val intent = context.registerReceiver(null, ifilter)
            if (intent != null) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level >= 0 && scale > 0) {
                    percent = ((level * 100f) / scale).roundToInt().coerceIn(0, 100)
                }
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL

                val v = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
                if (v > 0) voltageMv = v.toLong()

                val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
                if (temp > 0) tempCelsius = temp / 10f

                healthStr = when (intent.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)) {
                    BatteryManager.BATTERY_HEALTH_GOOD -> "Boa"
                    BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Superaquecendo"
                    BatteryManager.BATTERY_HEALTH_DEAD -> "Esgotada"
                    BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Sobretensão"
                    BatteryManager.BATTERY_HEALTH_COLD -> "Fria"
                    else -> "Normal"
                }
            }
        }

        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        var currentMicro = runCatching {
            bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)?.toLong()
        }.getOrNull()

        if (currentMicro == null || currentMicro == 0L || currentMicro == Long.MIN_VALUE || currentMicro == Int.MIN_VALUE.toLong()) {
            currentMicro = runCatching {
                File("/sys/class/power_supply/battery/current_now").readText().trim().toLongOrNull()
            }.getOrNull()
        }

        var currentMa: Long? = null
        if (currentMicro != null && currentMicro != 0L && currentMicro != Long.MIN_VALUE && currentMicro != Int.MIN_VALUE.toLong()) {
            currentMa = if (kotlin.math.abs(currentMicro) > 10_000L) {
                currentMicro / 1000L
            } else {
                currentMicro
            }
        }

        val powerWatts = if (currentMa != null && voltageMv != null && voltageMv > 0L) {
            val amps = kotlin.math.abs(currentMa) / 1000.0
            val volts = voltageMv / 1000.0
            amps * volts
        } else null

        val chargeRemaining = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { bm?.computeChargeTimeRemaining()?.takeIf { it > 0L } }.getOrNull()
        } else null

        return BatteryInfoSample(
            percent = percent,
            isCharging = isCharging,
            currentNowMa = currentMa,
            voltageMv = voltageMv,
            powerWatts = powerWatts,
            tempCelsius = tempCelsius,
            health = healthStr,
            chargeTimeRemainingMs = chargeRemaining,
        )
    }

    fun calculatePowerImpact(
        cpuPercent: Double,
        isForeground: Boolean,
        isSystem: Boolean,
    ): Pair<PowerImpactLevel, Double> {
        val level = if (isForeground) {
            when {
                cpuPercent >= 25.0 -> PowerImpactLevel.VERY_HIGH
                cpuPercent >= 10.0 -> PowerImpactLevel.HIGH
                cpuPercent >= 3.0 -> PowerImpactLevel.MEDIUM
                cpuPercent >= 0.5 -> PowerImpactLevel.LOW
                else -> PowerImpactLevel.MINIMAL
            }
        } else {
            when {
                cpuPercent >= 8.0 -> PowerImpactLevel.VERY_HIGH
                cpuPercent >= 3.0 -> PowerImpactLevel.HIGH
                cpuPercent >= 0.8 -> PowerImpactLevel.MEDIUM
                cpuPercent >= 0.1 -> PowerImpactLevel.LOW
                else -> PowerImpactLevel.MINIMAL
            }
        }
        val estimatedMw = (cpuPercent / 100.0) * 2800.0
        return Pair(level, estimatedMw)
    }

    private fun extractForegroundPackages(text: String): Set<String> {
        val result = mutableSetOf<String>()
        val regex = Regex("([a-zA-Z0-9_]+(?:\\.[a-zA-Z0-9_]+)+)/([a-zA-Z0-9_.]+)")
        text.lineSequence().forEach { line ->
            regex.findAll(line).forEach { match ->
                val pkg = match.groupValues[1]
                if (pkg.contains(".") && !pkg.startsWith("android.")) {
                    result.add(pkg)
                }
            }
        }
        return result
    }

    private fun parseSections(raw: String): Map<String, String> {
        val map = mutableMapOf<String, StringBuilder>()
        var currentSection = ""
        raw.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("===") && trimmed.endsWith("===")) {
                currentSection = trimmed.removeSurrounding("===").trim()
                map[currentSection] = StringBuilder()
            } else if (currentSection.isNotEmpty()) {
                map[currentSection]?.appendLine(line)
            }
        }
        return map.mapValues { it.value.toString().trim() }
    }
}
