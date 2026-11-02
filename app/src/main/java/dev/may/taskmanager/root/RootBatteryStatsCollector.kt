// SPDX-License-Identifier: GPL-3.0-or-later
package dev.may.taskmanager.root

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Collects root and kernel-level battery statistics.
 * Reads sysfs power supply nodes, kernel wakelocks, thermal zones, and CPU frequencies.
 */
object RootBatteryStatsCollector {

    private const val TAG = "RootBatteryStatsCollector"
    private const val ROOT_PROBE_TIMEOUT_MS = 4_000L
    private const val CMD_TIMEOUT_MS = 15_000L
    private const val NEGATIVE_CACHE_MS = 60_000L

    private val rootProbeLock = Mutex()

    @Volatile
    private var cachedRoot: Boolean? = null

    @Volatile
    private var cachedRootAt = 0L

    data class KernelBatteryInfo(
        val technology: String?,
        val cycleCount: Int?,
        val chargeFullDesign: Long?, // μAh or mAh
        val chargeFull: Long?, // μAh or mAh
        val chargeNow: Long?, // μAh
        val currentNow: Long?, // μA
        val voltageNow: Int?, // μV
        val tempNow: Int?, // 0.1°C
        val health: String?,
        val status: String?,
        val capacityLevel: String?,
        val timeToEmptyNow: Long?, // secs
        val timeToFullNow: Long?, // secs
        val batteryAge: Double? // percentage of design capacity
    )

    data class KernelWakelockInfo(
        val name: String,
        val count: Int,
        val expireCount: Int,
        val wakeCount: Int,
        val activeCount: Int,
        val totalTime: Long, // nanosecs
        val sleepTime: Long, // nanosecs
        val maxTime: Long, // nanosecs
        val lastChange: Long // nanosecs
    )

    data class CpuInfo(
        val cluster: Int,
        val currentFreq: Long, // kHz
        val minFreq: Long,
        val maxFreq: Long,
        val governor: String,
        val timeInState: Map<Long, Long>, // freq -> time in jiffies
        val cores: List<Int> = emptyList()
    )

    data class ThermalZone(
        val name: String,
        val type: String,
        val tempMilliC: Int,
        val tripPoints: List<TripPoint> = emptyList()
    )

    data class TripPoint(
        val type: String,
        val tempMilliC: Int
    )

    suspend fun isRootAvailable(): Boolean {
        cachedRoot?.let { cached ->
            if (cached || SystemClock.elapsedRealtime() - cachedRootAt < NEGATIVE_CACHE_MS) return cached
        }
        return rootProbeLock.withLock {
            cachedRoot?.let { cached ->
                if (cached || SystemClock.elapsedRealtime() - cachedRootAt < NEGATIVE_CACHE_MS) {
                    return@withLock cached
                }
            }
            val available = withContext(Dispatchers.IO) {
                exec("id", ROOT_PROBE_TIMEOUT_MS)?.contains("uid=0") == true
            }
            cachedRoot = available
            cachedRootAt = SystemClock.elapsedRealtime()
            available
        }
    }

    fun invalidateRootCache() {
        cachedRoot = null
        cachedRootAt = 0L
    }

    suspend fun resetBatteryStats(): Boolean = withContext(Dispatchers.IO) {
        val result = exec("dumpsys batterystats --reset", CMD_TIMEOUT_MS) ?: return@withContext false
        result.contains("Battery stats reset") || result.isBlank()
    }

    suspend fun getKernelBatteryInfo(): KernelBatteryInfo? = withContext(Dispatchers.IO) {
        try {
            val batteryPath = "/sys/class/power_supply/battery"
            val hasRoot = isRootAvailable()

            fun readFile(name: String): String? {
                try {
                    val f = File("$batteryPath/$name")
                    if (f.exists() && f.canRead()) {
                        val text = f.readText().trim()
                        if (text.isNotBlank()) return text
                    }
                } catch (_: Exception) {}

                if (hasRoot) {
                    val rootText = exec("cat $batteryPath/$name", 2000L)?.trim()
                    if (!rootText.isNullOrBlank() && !rootText.contains("No such file")) {
                        return rootText
                    }
                }
                return null
            }

            val chargeFullDesign = readFile("charge_full_design")?.toLongOrNull()
            val chargeFull = readFile("charge_full")?.toLongOrNull()

            KernelBatteryInfo(
                technology = readFile("technology"),
                cycleCount = readFile("cycle_count")?.toIntOrNull(),
                chargeFullDesign = chargeFullDesign,
                chargeFull = chargeFull,
                chargeNow = readFile("charge_now")?.toLongOrNull(),
                currentNow = readFile("current_now")?.toLongOrNull(),
                voltageNow = readFile("voltage_now")?.toIntOrNull(),
                tempNow = readFile("temp")?.toIntOrNull(),
                health = readFile("health"),
                status = readFile("status"),
                capacityLevel = readFile("capacity_level"),
                timeToEmptyNow = readFile("time_to_empty_now")?.toLongOrNull(),
                timeToFullNow = readFile("time_to_full_now")?.toLongOrNull(),
                batteryAge = if (chargeFullDesign != null && chargeFull != null && chargeFullDesign > 0) {
                    (chargeFull.toDouble() / chargeFullDesign) * 100
                } else null
            )
        } catch (_: Exception) {
            null
        }
    }

    suspend fun getKernelWakelocks(): List<KernelWakelockInfo> = withContext(Dispatchers.IO) {
        val result = mutableMapOf<String, KernelWakelockInfo>()
        try {
            if (isRootAvailable()) {
                val rootCmd = "sh -c '" +
                    "if [ -r /sys/kernel/debug/wakeup_sources ]; then " +
                    "  cat /sys/kernel/debug/wakeup_sources; " +
                    "elif [ -r /d/wakeup_sources ]; then " +
                    "  cat /d/wakeup_sources; " +
                    "else " +
                    "  mount -t debugfs none /sys/kernel/debug 2>/dev/null; " +
                    "  if [ -r /sys/kernel/debug/wakeup_sources ]; then " +
                    "    cat /sys/kernel/debug/wakeup_sources; " +
                    "  elif [ -r /d/wakeup_sources ]; then " +
                    "    cat /d/wakeup_sources; " +
                    "  else " +
                    "    for w in /sys/class/wakeup/wakeup* /sys/devices/virtual/wakeup/wakeup*; do " +
                    "      [ -d \"\$w\" ] || continue; " +
                    "      n=\$(cat \"\$w/name\" 2>/dev/null); " +
                    "      [ -z \"\$n\" ] && continue; " +
                    "      ac=\$(cat \"\$w/active_count\" 2>/dev/null); " +
                    "      ec=\$(cat \"\$w/expire_count\" 2>/dev/null); " +
                    "      wc=\$(cat \"\$w/wakeup_count\" 2>/dev/null); " +
                    "      tt=\$(cat \"\$w/total_time_ms\" 2>/dev/null); " +
                    "      mt=\$(cat \"\$w/max_time_ms\" 2>/dev/null); " +
                    "      echo \"WAKEUP:\$n|\${ac:-0}|\${ec:-0}|\${wc:-0}|\${tt:-0}|\${mt:-0}\"; " +
                    "    done; " +
                    "    cat /sys/kernel/wakelock_stats 2>/dev/null || cat /proc/wakelocks 2>/dev/null; " +
                    "  fi; " +
                    "fi'"

                val out = exec(rootCmd, 3500L)
                if (!out.isNullOrBlank()) {
                    parseKernelWakelocksOutput(out, result)
                }

                if (result.isEmpty()) {
                    val dumpOut = exec("dumpsys batterystats --checkin 2>/dev/null | grep ',kwl,'", 3000L)
                    if (!dumpOut.isNullOrBlank()) {
                        parseDumpsysKernelWakelocks(dumpOut, result)
                    }
                }
            }

            if (result.isEmpty()) {
                val wakelockPath = when {
                    File("/sys/kernel/wakelock_stats").exists() -> "/sys/kernel/wakelock_stats"
                    File("/proc/wakelocks").exists() -> "/proc/wakelocks"
                    else -> null
                }
                if (wakelockPath != null) {
                    try {
                        val f = File(wakelockPath)
                        if (f.canRead()) {
                            parseKernelWakelocksOutput(f.readText(), result)
                        }
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
        result.values.sortedByDescending { it.totalTime }
    }

    private fun parseKernelWakelocksOutput(out: String, result: MutableMap<String, KernelWakelockInfo>) {
        out.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isBlank()) return@forEach

            if (line.startsWith("WAKEUP:")) {
                val parts = line.removePrefix("WAKEUP:").split('|')
                if (parts.size >= 6) {
                    val name = parts[0].trim('"')
                    if (name.isNotBlank()) {
                        val ac = parts[1].toIntOrNull() ?: 0
                        val ec = parts[2].toIntOrNull() ?: 0
                        val wc = parts[3].toIntOrNull() ?: 0
                        val ttMs = parts[4].toLongOrNull() ?: 0L
                        val mtMs = parts[5].toLongOrNull() ?: 0L
                        result[name] = KernelWakelockInfo(
                            name = name,
                            count = wc,
                            expireCount = ec,
                            wakeCount = wc,
                            activeCount = ac,
                            totalTime = ttMs * 1_000_000L,
                            sleepTime = 0L,
                            maxTime = mtMs * 1_000_000L,
                            lastChange = 0L
                        )
                    }
                }
            } else {
                val parts = line.split(Regex("\\s+"))
                if (parts.size >= 6) {
                    val name = parts[0].trim('"')
                    if (name.isNotBlank() && !name.equals("name", ignoreCase = true)) {
                        val c1 = parts.getOrNull(1)?.toIntOrNull() ?: 0
                        val c2 = parts.getOrNull(2)?.toIntOrNull() ?: 0
                        val c3 = parts.getOrNull(3)?.toIntOrNull() ?: 0
                        val c4 = parts.getOrNull(4)?.toIntOrNull() ?: 0
                        val t5 = parts.getOrNull(5)?.toLongOrNull() ?: 0L
                        val t6 = parts.getOrNull(6)?.toLongOrNull() ?: 0L
                        val t7 = parts.getOrNull(7)?.toLongOrNull() ?: 0L
                        val t8 = parts.getOrNull(8)?.toLongOrNull() ?: 0L

                        val isDebugfs = parts.size >= 10 || (parts.size >= 8 && parts[0].contains('.'))
                        val totalTimeNs: Long
                        val maxTimeNs: Long
                        val activeCount: Int
                        val wakeCount: Int
                        val expireCount: Int

                        if (isDebugfs) {
                            val ttMs = parts.getOrNull(6)?.toLongOrNull() ?: 0L
                            val mtMs = parts.getOrNull(7)?.toLongOrNull() ?: 0L
                            totalTimeNs = ttMs * 1_000_000L
                            maxTimeNs = mtMs * 1_000_000L
                            activeCount = c1
                            wakeCount = c3
                            expireCount = c4
                        } else {
                            totalTimeNs = if (t5 < 100_000_000L) t5 * 1_000_000L else t5
                            maxTimeNs = if (t7 < 100_000_000L) t7 * 1_000_000L else t7
                            activeCount = c4
                            wakeCount = c3
                            expireCount = c2
                        }

                        result[name] = KernelWakelockInfo(
                            name = name,
                            count = if (isDebugfs) wakeCount else c1,
                            expireCount = expireCount,
                            wakeCount = wakeCount,
                            activeCount = activeCount,
                            totalTime = totalTimeNs,
                            sleepTime = if (isDebugfs) 0L else t6,
                            maxTime = maxTimeNs,
                            lastChange = if (isDebugfs) 0L else t8
                        )
                    }
                }
            }
        }
    }

    private fun parseDumpsysKernelWakelocks(dumpOut: String, result: MutableMap<String, KernelWakelockInfo>) {
        dumpOut.lineSequence().forEach { line ->
            val parts = line.split(',')
            val kwlIndex = parts.indexOf("kwl")
            if (kwlIndex >= 0 && parts.size > kwlIndex + 3) {
                val name = parts.getOrNull(kwlIndex + 1)?.trim('"') ?: return@forEach
                if (name.isBlank()) return@forEach
                val count = parts.getOrNull(kwlIndex + 2)?.toIntOrNull() ?: 0
                val timeMs = parts.getOrNull(kwlIndex + 3)?.toLongOrNull() ?: 0L
                val currentMs = parts.getOrNull(kwlIndex + 4)?.toLongOrNull() ?: 0L
                val maxMs = parts.getOrNull(kwlIndex + 5)?.toLongOrNull() ?: 0L

                result[name] = KernelWakelockInfo(
                    name = name,
                    count = count,
                    expireCount = 0,
                    wakeCount = count,
                    activeCount = if (currentMs > 0) 1 else 0,
                    totalTime = timeMs * 1_000_000L,
                    sleepTime = 0L,
                    maxTime = maxMs * 1_000_000L,
                    lastChange = 0L
                )
            }
        }
    }

    suspend fun getCpuInfo(): List<CpuInfo> = withContext(Dispatchers.IO) {
        val result = mutableListOf<CpuInfo>()
        val hasRoot = isRootAvailable()

        // 1. If root is available, use consolidated root execution for complete SELinux bypass
        if (hasRoot) {
            val rootCmd = "sh -c '" +
                "DIRS=\"\"; " +
                "for d in /sys/devices/system/cpu/cpufreq/policy* /sys/devices/system/cpu/cpu[0-9]*/cpufreq; do " +
                "  [ -d \"\$d\" ] && DIRS=\"\$DIRS \$d\"; " +
                "done; " +
                "SEEN=\"\"; " +
                "for p in \$DIRS; do " +
                "  aff=\$(cat \"\$p/affected_cpus\" 2>/dev/null); " +
                "  [ -z \"\$aff\" ] && aff=\$(echo \"\$p\" | grep -o \"[0-9]*\" | tail -n 1); " +
                "  case \"\$SEEN\" in *\" \$aff \"*) continue ;; esac; " +
                "  SEEN=\"\$SEEN \$aff \"; " +
                "  echo \"===POLICY===\"; " +
                "  echo \"affected:\$aff\"; " +
                "  gov=\$(cat \"\$p/scaling_governor\" 2>/dev/null); " +
                "  [ -z \"\$gov\" ] && gov=\$(cat \"\$p/scaling_driver\" 2>/dev/null); " +
                "  echo \"governor:\${gov:-unknown}\"; " +
                "  cur=\$(cat \"\$p/scaling_cur_freq\" 2>/dev/null); " +
                "  [ -z \"\$cur\" ] && cur=\$(cat \"\$p/cpuinfo_cur_freq\" 2>/dev/null); " +
                "  echo \"cur:\${cur:-0}\"; " +
                "  min=\$(cat \"\$p/scaling_min_freq\" 2>/dev/null); " +
                "  [ -z \"\$min\" ] && min=\$(cat \"\$p/cpuinfo_min_freq\" 2>/dev/null); " +
                "  echo \"min:\${min:-0}\"; " +
                "  max=\$(cat \"\$p/scaling_max_freq\" 2>/dev/null); " +
                "  [ -z \"\$max\" ] && max=\$(cat \"\$p/cpuinfo_max_freq\" 2>/dev/null); " +
                "  echo \"max:\${max:-0}\"; " +
                "  echo \"---TIS---\"; " +
                "  cat \"\$p/stats/time_in_state\" 2>/dev/null; " +
                "  echo \"===END===\"; " +
                "done'"

            val out = exec(rootCmd, 3500L)
            if (!out.isNullOrBlank()) {
                val parsed = parseRootCpuOutput(out)
                if (parsed.isNotEmpty()) {
                    return@withContext parsed
                }
            }
        }

        // 2. Fallback to direct sysfs file reading (if accessible without root)
        try {
            val policyDir = File("/sys/devices/system/cpu/cpufreq")
            val policyDirs = if (policyDir.exists() && policyDir.canRead()) {
                policyDir.listFiles { f -> f.name.startsWith("policy") }
                    ?.sortedBy { it.name.removePrefix("policy").toIntOrNull() ?: 0 }
            } else null

            if (!policyDirs.isNullOrEmpty()) {
                policyDirs.forEach { p ->
                    val policyNum = p.name.removePrefix("policy").toIntOrNull() ?: 0
                    val affectedStr = readFileDirect(p, "affected_cpus") ?: "$policyNum"
                    val cores = affectedStr.split(Regex("\\s+")).mapNotNull { it.toIntOrNull() }.sorted()
                    val gov = readFileDirect(p, "scaling_governor") ?: readFileDirect(p, "scaling_driver") ?: "unknown"
                    val cur = (readFileDirect(p, "scaling_cur_freq") ?: readFileDirect(p, "cpuinfo_cur_freq"))?.toLongOrNull() ?: 0L
                    val min = (readFileDirect(p, "scaling_min_freq") ?: readFileDirect(p, "cpuinfo_min_freq"))?.toLongOrNull() ?: 0L
                    val max = (readFileDirect(p, "scaling_max_freq") ?: readFileDirect(p, "cpuinfo_max_freq"))?.toLongOrNull() ?: 0L
                    val tis = readTimeInStateDirect(File(p, "stats/time_in_state"))

                    result.add(
                        CpuInfo(
                            cluster = policyNum,
                            currentFreq = cur,
                            minFreq = min,
                            maxFreq = max,
                            governor = gov,
                            timeInState = tis,
                            cores = cores
                        )
                    )
                }
            } else {
                val cpuDir = File("/sys/devices/system/cpu")
                val cpuDirs = cpuDir.listFiles { f -> f.name.matches(Regex("cpu\\d+")) }
                    ?.sortedBy { it.name.removePrefix("cpu").toIntOrNull() ?: 0 }
                    ?: return@withContext result

                val clusters = mutableMapOf<Int, MutableList<Int>>()
                cpuDirs.forEach { cpu ->
                    val cpuNum = cpu.name.removePrefix("cpu").toIntOrNull() ?: return@forEach
                    val policyPath = File("${cpu.absolutePath}/cpufreq/affected_cpus")
                    val cluster = if (policyPath.exists() && policyPath.canRead()) {
                        policyPath.readText().trim().split(Regex("\\s+")).firstOrNull()?.toIntOrNull() ?: cpuNum
                    } else cpuNum
                    clusters.getOrPut(cluster) { mutableListOf() }.add(cpuNum)
                }

                clusters.forEach { (clusterNum, coreList) ->
                    val cpuPath = File("/sys/devices/system/cpu/cpu$clusterNum/cpufreq")
                    if (!cpuPath.exists()) return@forEach

                    val gov = readFileDirect(cpuPath, "scaling_governor") ?: readFileDirect(cpuPath, "scaling_driver") ?: "unknown"
                    val cur = (readFileDirect(cpuPath, "scaling_cur_freq") ?: readFileDirect(cpuPath, "cpuinfo_cur_freq"))?.toLongOrNull() ?: 0L
                    val min = (readFileDirect(cpuPath, "scaling_min_freq") ?: readFileDirect(cpuPath, "cpuinfo_min_freq"))?.toLongOrNull() ?: 0L
                    val max = (readFileDirect(cpuPath, "scaling_max_freq") ?: readFileDirect(cpuPath, "cpuinfo_max_freq"))?.toLongOrNull() ?: 0L
                    val tis = readTimeInStateDirect(File(cpuPath, "stats/time_in_state"))

                    result.add(
                        CpuInfo(
                            cluster = clusterNum,
                            currentFreq = cur,
                            minFreq = min,
                            maxFreq = max,
                            governor = gov,
                            timeInState = tis,
                            cores = coreList.sorted()
                        )
                    )
                }
            }
        } catch (_: Exception) {}

        result.sortedBy { it.cluster }
    }

    private fun readFileDirect(dir: File, name: String): String? = try {
        val f = File(dir, name)
        if (f.exists() && f.canRead()) f.readText().trim().takeIf { it.isNotBlank() } else null
    } catch (_: Exception) { null }

    private fun readTimeInStateDirect(file: File): Map<Long, Long> {
        val map = mutableMapOf<Long, Long>()
        try {
            if (file.exists() && file.canRead()) {
                file.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val parts = line.trim().split(Regex("\\s+"))
                        if (parts.size >= 2) {
                            val freq = parts[0].toLongOrNull() ?: return@forEach
                            val time = parts[1].toLongOrNull() ?: 0L
                            map[freq] = time
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return map
    }

    private fun parseRootCpuOutput(output: String): List<CpuInfo> {
        val result = mutableListOf<CpuInfo>()
        val blocks = output.split("===POLICY===")
        var clusterIndex = 0

        for (block in blocks) {
            val trimmed = block.trim()
            if (trimmed.isEmpty()) continue

            var affected = ""
            var governor = "unknown"
            var cur = 0L
            var min = 0L
            var max = 0L
            val timeInState = mutableMapOf<Long, Long>()
            var inTis = false

            for (line in trimmed.lines()) {
                val l = line.trim()
                when {
                    l == "---TIS---" -> inTis = true
                    l == "===END===" -> inTis = false
                    inTis -> {
                        val parts = l.split(Regex("\\s+"))
                        if (parts.size >= 2) {
                            val freq = parts[0].toLongOrNull()
                            val time = parts[1].toLongOrNull()
                            if (freq != null && time != null) {
                                timeInState[freq] = time
                            }
                        }
                    }
                    l.startsWith("affected:") -> affected = l.removePrefix("affected:").trim()
                    l.startsWith("governor:") -> governor = l.removePrefix("governor:").trim()
                    l.startsWith("cur:") -> cur = l.removePrefix("cur:").trim().toLongOrNull() ?: 0L
                    l.startsWith("min:") -> min = l.removePrefix("min:").trim().toLongOrNull() ?: 0L
                    l.startsWith("max:") -> max = l.removePrefix("max:").trim().toLongOrNull() ?: 0L
                }
            }

            val cores = affected.split(Regex("\\s+"))
                .mapNotNull { it.toIntOrNull() }
                .sorted()

            val clusterNum = cores.firstOrNull() ?: clusterIndex

            result.add(
                CpuInfo(
                    cluster = clusterNum,
                    currentFreq = cur,
                    minFreq = min,
                    maxFreq = max,
                    governor = governor,
                    timeInState = timeInState,
                    cores = cores
                )
            )
            clusterIndex++
        }

        return result.sortedBy { it.cluster }
    }

    suspend fun getThermalZones(): List<ThermalZone> = withContext(Dispatchers.IO) {
        val result = mutableMapOf<String, ThermalZone>()
        try {
            if (isRootAvailable()) {
                val rootCmd = "sh -c '" +
                    "for z in /sys/class/thermal/thermal_zone* /sys/devices/virtual/thermal/thermal_zone*; do " +
                    "  [ -d \"\$z\" ] || continue; " +
                    "  n=\$(basename \"\$z\"); " +
                    "  type=\$(cat \"\$z/type\" 2>/dev/null); " +
                    "  temp=\$(cat \"\$z/temp\" 2>/dev/null); " +
                    "  [ -z \"\$type\" ] && continue; " +
                    "  echo \"ZONE:\$n|\$type|\${temp:-0}\"; " +
                    "  for t in \"\$z\"/trip_point_*_type; do " +
                    "    [ -f \"\$t\" ] || continue; " +
                    "    idx=\$(echo \"\$t\" | sed -E \"s/.*trip_point_([0-9]+)_type/\\1/\"); " +
                    "    ttype=\$(cat \"\$t\" 2>/dev/null); " +
                    "    ttemp=\$(cat \"\$z/trip_point_\${idx}_temp\" 2>/dev/null); " +
                    "    echo \"TRIP:\$idx|\$ttype|\${ttemp:-0}\"; " +
                    "  done; " +
                    "done'"

                val out = exec(rootCmd, 3500L)
                if (!out.isNullOrBlank()) {
                    parseThermalZonesOutput(out, result)
                }

                if (result.isEmpty()) {
                    val dump = exec("dumpsys thermalservice 2>/dev/null | grep -i 'Temperature{'", 3000L)
                    if (!dump.isNullOrBlank()) {
                        parseDumpsysThermal(dump, result)
                    }
                }
            }

            if (result.isEmpty()) {
                val dirs = listOf(File("/sys/class/thermal"), File("/sys/devices/virtual/thermal"))
                dirs.forEach { thermalDir ->
                    val zones = thermalDir.listFiles { f -> f.name.startsWith("thermal_zone") }
                    zones?.forEach { zone ->
                        fun read(name: String): String? = try {
                            File("${zone.absolutePath}/$name").readText().trim()
                        } catch (_: Exception) { null }

                        val type = read("type") ?: "unknown"
                        val rawTemp = read("temp")?.toIntOrNull() ?: 0
                        val tripPoints = mutableListOf<TripPoint>()
                        var i = 0
                        while (i < 5) {
                            val tripType = read("trip_point_${i}_type") ?: break
                            val tripTemp = read("trip_point_${i}_temp")?.toIntOrNull() ?: break
                            tripPoints.add(TripPoint(tripType, normalizeTripTemp(tripTemp)))
                            i++
                        }

                        if (!result.containsKey(zone.name)) {
                            result[zone.name] = ThermalZone(
                                name = zone.name,
                                type = type,
                                tempMilliC = normalizeTempMilliC(rawTemp.toDouble()),
                                tripPoints = tripPoints
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        result.values.sortedBy { it.name }
    }

    private fun normalizeTempMilliC(raw: Double): Int {
        return when {
            raw in -50.0..150.0 -> (raw * 1000).toInt()
            raw in 150.0..1500.0 -> (raw * 100).toInt()
            raw in 1500.0..150000.0 -> raw.toInt()
            else -> raw.toInt().coerceIn(0, 150000)
        }
    }

    private fun normalizeTripTemp(raw: Int): Int {
        return normalizeTempMilliC(raw.toDouble())
    }

    private fun parseThermalZonesOutput(out: String, result: MutableMap<String, ThermalZone>) {
        var currentZoneName: String? = null
        var currentZoneType = "unknown"
        var currentZoneTemp = 0
        var currentTrips = mutableListOf<TripPoint>()

        fun flushCurrent() {
            val name = currentZoneName ?: return
            if (!result.containsKey(name)) {
                result[name] = ThermalZone(
                    name = name,
                    type = currentZoneType,
                    tempMilliC = currentZoneTemp,
                    tripPoints = currentTrips.toList()
                )
            }
        }

        out.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("ZONE:")) {
                flushCurrent()
                val parts = trimmed.removePrefix("ZONE:").split('|')
                currentZoneName = parts.getOrNull(0)
                currentZoneType = parts.getOrNull(1) ?: "unknown"
                val rawTemp = parts.getOrNull(2)?.toDoubleOrNull() ?: 0.0
                currentZoneTemp = normalizeTempMilliC(rawTemp)
                currentTrips = mutableListOf()
            } else if (trimmed.startsWith("TRIP:")) {
                val parts = trimmed.removePrefix("TRIP:").split('|')
                if (parts.size >= 3) {
                    val tripType = parts[1]
                    val rawTripTemp = parts[2].toIntOrNull() ?: 0
                    currentTrips.add(TripPoint(tripType, normalizeTripTemp(rawTripTemp)))
                }
            }
        }
        flushCurrent()
    }

    private fun parseDumpsysThermal(out: String, result: MutableMap<String, ThermalZone>) {
        val tempRegex = Regex("""Temperature\{mValue=([0-9.]+),\s*mType=(\d+),\s*mName=([^,]+),\s*mStatus=(\d+)\}""")
        var index = 0
        tempRegex.findAll(out).forEach { match ->
            val value = match.groupValues[1].toDoubleOrNull() ?: 0.0
            val name = match.groupValues[3].trim()
            val zoneId = "thermal_zone$index"
            result[zoneId] = ThermalZone(
                name = zoneId,
                type = name,
                tempMilliC = normalizeTempMilliC(value),
                tripPoints = emptyList()
            )
            index++
        }
    }

    val isRootCached: Boolean? get() = cachedRoot

    suspend fun runAsRoot(command: String): String? = withContext(Dispatchers.IO) {
        exec(command, CMD_TIMEOUT_MS)
    }

    /**
     * Executes a command as root and returns true if it completed within [timeoutMs] and exited with code 0.
     */
    suspend fun runAsRootSuccessful(command: String, timeoutMs: Long = 3_000L): Boolean = withContext(Dispatchers.IO) {
        var process: Process? = null
        var watchdog: Thread? = null
        val timedOut = AtomicBoolean(false)
        try {
            val p = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            process = p
            runCatching { p.outputStream.close() }

            watchdog = Thread {
                try {
                    if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                        timedOut.set(true)
                        Log.w(TAG, "su timed out after $timeoutMs ms: $command")
                        p.destroyForcibly()
                    }
                } catch (ignored: InterruptedException) {}
            }.apply {
                isDaemon = true
                start()
            }

            p.inputStream.bufferedReader().use { it.readText() }
            val exitCode = p.waitFor()
            !timedOut.get() && exitCode == 0
        } catch (e: Exception) {
            Log.d(TAG, "su failed for '$command': ${e.message}")
            false
        } finally {
            watchdog?.interrupt()
            runCatching { process?.destroy() }
        }
    }

    /**
     * Turns off / locks the screen using root command `input keyevent 26` (KEYCODE_POWER),
     * which activates the system's smooth display sleep transition without abrupt termination.
     */
    suspend fun lockScreen(): Boolean = withContext(Dispatchers.IO) {
        if (!isRootAvailable()) return@withContext false
        runAsRootSuccessful("input keyevent 26")
    }

    private fun exec(command: String, timeoutMs: Long): String? {
        var process: Process? = null
        var watchdog: Thread? = null
        val timedOut = AtomicBoolean(false)
        return try {
            val p = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            process = p
            runCatching { p.outputStream.close() }

            watchdog = Thread {
                try {
                    if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                        timedOut.set(true)
                        Log.w(TAG, "su timed out after $timeoutMs ms: $command")
                        p.destroyForcibly()
                    }
                } catch (ignored: InterruptedException) {}
            }.apply {
                isDaemon = true
                start()
            }

            val out = p.inputStream.bufferedReader().use(BufferedReader::readText)
            if (timedOut.get()) null else out
        } catch (e: Exception) {
            Log.d(TAG, "su failed for '$command': ${e.message}")
            null
        } finally {
            watchdog?.interrupt()
            runCatching { process?.destroy() }
        }
    }
}

