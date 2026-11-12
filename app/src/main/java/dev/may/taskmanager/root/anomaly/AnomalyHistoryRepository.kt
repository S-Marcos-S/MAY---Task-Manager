// SPDX-License-Identifier: GPL-3.0-or-later
package dev.may.taskmanager.root.anomaly

import android.content.Context
import dev.may.taskmanager.root.AppRootInspector
import dev.may.taskmanager.telemetry.ConfidenceLevel
import dev.may.taskmanager.telemetry.TelemetryNature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Aggregated summary of background anomalies for a specific application.
 */
data class FlaggedAppSummary(
    val packageName: String,
    val appName: String,
    val totalFlags: Int = 0,
    val deepSleepDisruptions: Int = 0,
    val backgroundDataFlags: Int = 0,
    val cpuFlags: Int = 0,
    val ramFlags: Int = 0,
    val locationFlags: Int = 0,
    val totalEstimatedBytes: Long = 0L,
    val lastFlaggedTimestamp: Long = 0L,
    val lastServiceOrProcess: String? = null,
    val isWhitelisted: Boolean = false,
    val isMuted: Boolean = false,
)

/**
 * Persistent repository for background anomaly events and app rankings.
 */
class AnomalyHistoryRepository private constructor(private val context: Context) {

    companion object {
        private const val DIR_NAME = "anomaly_history"
        private const val FILE_NAME = "anomalies.json"
        private const val MAX_EVENTS = 500

        @Volatile
        private var instance: AnomalyHistoryRepository? = null

        fun getInstance(context: Context): AnomalyHistoryRepository {
            return instance ?: synchronized(this) {
                instance ?: AnomalyHistoryRepository(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dataFile: File by lazy {
        val dir = File(context.filesDir, DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        File(dir, FILE_NAME)
    }

    private val _events = MutableStateFlow<List<AnomalyEvent>>(emptyList())
    val events: StateFlow<List<AnomalyEvent>> = _events.asStateFlow()

    private val _flaggedApps = MutableStateFlow<List<FlaggedAppSummary>>(emptyList())
    val flaggedApps: StateFlow<List<FlaggedAppSummary>> = _flaggedApps.asStateFlow()

    init {
        loadFromDisk()
    }

    private fun loadFromDisk() {
        scope.launch {
            if (!dataFile.exists()) return@launch
            val content = runCatching { dataFile.readText(Charsets.UTF_8) }.getOrNull() ?: return@launch
            val parsedList = parseEventsJson(content)
            _events.value = parsedList
            recalculateAggregates(parsedList)
        }
    }

    private fun saveToDisk(eventsList: List<AnomalyEvent>) {
        scope.launch {
            runCatching {
                val json = serializeEventsJson(eventsList)
                dataFile.writeText(json, Charsets.UTF_8)
            }
        }
    }

    fun recordAnomaly(event: AnomalyEvent) {
        val current = _events.value.toMutableList()
        current.add(0, event)
        if (current.size > MAX_EVENTS) {
            current.removeAt(current.size - 1)
        }
        _events.value = current
        recalculateAggregates(current)
        saveToDisk(current)
    }

    fun clearAll() {
        _events.value = emptyList()
        _flaggedApps.value = emptyList()
        saveToDisk(emptyList())
    }

    fun removeForPackage(packageName: String) {
        val filtered = _events.value.filter { it.packageName != packageName }
        _events.value = filtered
        recalculateAggregates(filtered)
        saveToDisk(filtered)
    }

    private fun recalculateAggregates(eventsList: List<AnomalyEvent>) {
        val watcher = BackgroundAnomalyWatcher.getInstance(context)
        val cfg = watcher.config.value
        val whitelist = cfg.whitelistedPackages

        val map = mutableMapOf<String, MutableFlaggedData>()
        for (event in eventsList) {
            val data = map.getOrPut(event.packageName) {
                MutableFlaggedData(
                    packageName = event.packageName,
                    appName = event.appName,
                )
            }
            data.totalFlags++
            if (event.timestamp > data.lastFlaggedTimestamp) {
                data.lastFlaggedTimestamp = event.timestamp
                data.lastServiceOrProcess = event.serviceOrProcess
            }

            when (event.type) {
                AnomalyType.DEEP_SLEEP -> data.deepSleepDisruptions++
                AnomalyType.NETWORK -> {
                    data.backgroundDataFlags++
                    val bytes = if (event.rawValue > 0.0 && (event.unit.isEmpty() || event.unit == "B")) {
                        event.rawValue.toLong()
                    } else {
                        parseBytesFromFormatted(event.valueFormatted)
                    }
                    data.totalEstimatedBytes += bytes
                }
                AnomalyType.CPU -> data.cpuFlags++
                AnomalyType.RAM -> data.ramFlags++
                AnomalyType.LOCATION -> data.locationFlags++
            }
        }

        val summaries = map.values.map { d ->
            FlaggedAppSummary(
                packageName = d.packageName,
                appName = d.appName,
                totalFlags = d.totalFlags,
                deepSleepDisruptions = d.deepSleepDisruptions,
                backgroundDataFlags = d.backgroundDataFlags,
                cpuFlags = d.cpuFlags,
                ramFlags = d.ramFlags,
                locationFlags = d.locationFlags,
                totalEstimatedBytes = d.totalEstimatedBytes,
                lastFlaggedTimestamp = d.lastFlaggedTimestamp,
                lastServiceOrProcess = d.lastServiceOrProcess,
                isWhitelisted = whitelist.contains(d.packageName),
                isMuted = watcher.isPackageMuted(d.packageName),
            )
        }.sortedByDescending { it.totalFlags }

        _flaggedApps.value = summaries
    }

    private fun parseBytesFromFormatted(formatted: String): Long {
        return runCatching {
            val parts = formatted.trim().split(" ")
            if (parts.size >= 2) {
                val num = parts[0].replace(",", ".").toDoubleOrNull() ?: 0.0
                val unit = parts[1].uppercase()
                when {
                    unit.startsWith("GB") -> (num * 1024 * 1024 * 1024).toLong()
                    unit.startsWith("MB") -> (num * 1024 * 1024).toLong()
                    unit.startsWith("KB") -> (num * 1024).toLong()
                    else -> num.toLong()
                }
            } else 0L
        }.getOrDefault(0L)
    }

    private data class MutableFlaggedData(
        val packageName: String,
        val appName: String,
        var totalFlags: Int = 0,
        var deepSleepDisruptions: Int = 0,
        var backgroundDataFlags: Int = 0,
        var cpuFlags: Int = 0,
        var ramFlags: Int = 0,
        var locationFlags: Int = 0,
        var totalEstimatedBytes: Long = 0L,
        var lastFlaggedTimestamp: Long = 0L,
        var lastServiceOrProcess: String? = null,
    )

    private fun serializeEventsJson(list: List<AnomalyEvent>): String {
        val array = JSONArray()
        for (event in list) {
            val obj = JSONObject().apply {
                put("id", event.id)
                put("packageName", event.packageName)
                put("appName", event.appName)
                put("type", event.type.name)
                put("valueFormatted", event.valueFormatted)
                put("description", event.description)
                put("timestamp", event.timestamp)
                put("screenWasOff", event.screenWasOff)
                if (event.pid != null) put("pid", event.pid)
                if (event.serviceOrProcess != null) put("serviceOrProcess", event.serviceOrProcess)
                if (event.probableCause != null) put("probableCause", event.probableCause)
                put("rawValue", event.rawValue)
                put("unit", event.unit)
                if (event.uid != null) put("uid", event.uid)
                put("startTime", event.startTime)
                put("endTime", event.endTime)
                put("durationMs", event.durationMs)
                put("confidence", event.confidence.name)
                put("nature", event.nature.name)
                put("source", event.source)
                put("isEstimated", event.isEstimated)
                put("isComplete", event.isComplete)
                put("rootAvailable", event.rootAvailable)
                put("collectionMethod", event.collectionMethod)
                put("isFallback", event.isFallback)
                if (event.reason != null) put("reason", event.reason)
            }
            array.put(obj)
        }
        return array.toString()
    }

    private fun parseEventsJson(jsonStr: String): List<AnomalyEvent> {
        return runCatching {
            val array = JSONArray(jsonStr)
            val list = mutableListOf<AnomalyEvent>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val typeName = obj.optString("type", AnomalyType.NETWORK.name)
                val type = runCatching { AnomalyType.valueOf(typeName) }.getOrDefault(AnomalyType.NETWORK)
                val pid = if (obj.has("pid")) obj.optInt("pid") else null
                val service = if (obj.has("serviceOrProcess")) obj.optString("serviceOrProcess") else null
                val cause = if (obj.has("probableCause")) obj.optString("probableCause") else null

                val isLegacy = !obj.has("confidence")
                val confName = obj.optString("confidence", ConfidenceLevel.LOW.name)
                val confidence = runCatching { ConfidenceLevel.valueOf(confName) }.getOrDefault(ConfidenceLevel.LOW)

                val natureName = obj.optString("nature", TelemetryNature.ESTIMATE.name)
                val nature = runCatching { TelemetryNature.valueOf(natureName) }.getOrDefault(TelemetryNature.ESTIMATE)

                val defaultReason = if (isLegacy) "Registro legado anterior sem metadados de medicao" else null
                val reason = if (obj.has("reason")) obj.optString("reason") else defaultReason

                val ts = obj.optLong("timestamp", System.currentTimeMillis())
                val start = obj.optLong("startTime", ts)
                val end = obj.optLong("endTime", ts)
                val duration = obj.optLong("durationMs", (end - start).coerceAtLeast(0L))

                list.add(
                    AnomalyEvent(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        packageName = obj.optString("packageName", ""),
                        appName = obj.optString("appName", ""),
                        type = type,
                        valueFormatted = obj.optString("valueFormatted", ""),
                        description = obj.optString("description", ""),
                        timestamp = ts,
                        screenWasOff = obj.optBoolean("screenWasOff", false),
                        pid = pid,
                        serviceOrProcess = service,
                        probableCause = cause,
                        rawValue = obj.optDouble("rawValue", 0.0),
                        unit = obj.optString("unit", ""),
                        uid = if (obj.has("uid")) obj.optInt("uid") else null,
                        startTime = start,
                        endTime = end,
                        durationMs = duration,
                        confidence = confidence,
                        nature = nature,
                        source = obj.optString("source", if (isLegacy) "legacy" else "system"),
                        isEstimated = if (isLegacy) true else obj.optBoolean("isEstimated", false),
                        isComplete = obj.optBoolean("isComplete", true),
                        rootAvailable = obj.optBoolean("rootAvailable", false),
                        collectionMethod = obj.optString("collectionMethod", if (isLegacy) "legacy_import" else "native"),
                        isFallback = obj.optBoolean("isFallback", false),
                        reason = reason,
                    )
                )
            }
            list
        }.getOrDefault(emptyList())
    }

    suspend fun exportJson(): String = withContext(Dispatchers.IO) {
        serializeEventsJson(_events.value)
    }

    suspend fun importJson(jsonStr: String) = withContext(Dispatchers.IO) {
        val parsed = parseEventsJson(jsonStr)
        if (parsed.isNotEmpty()) {
            _events.value = parsed
            recalculateAggregates(parsed)
            saveToDisk(parsed)
        }
    }
}
