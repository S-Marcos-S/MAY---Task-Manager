// SPDX-License-Identifier: GPL-3.0-or-later
package dev.may.taskmanager.root.anomaly

import dev.may.taskmanager.R
import dev.may.taskmanager.telemetry.ConfidenceLevel
import dev.may.taskmanager.telemetry.TelemetryNature
import java.util.UUID

enum class AnomalyType(val labelPt: String, val labelEn: String, val iconEmoji: String = "") {
    NETWORK("Rede / Dados", "Network / Data", ""),
    CPU("Processador", "Processor / CPU", ""),
    RAM("Memória RAM", "RAM Memory", ""),
    DEEP_SLEEP("Sono Profundo", "Deep Sleep", ""),
}

enum class AnomalySensitivity(
    val labelRes: Int,
    val netScreenOnMb: Long,
    val netScreenOffMb: Long,
    val cpuPercent: Double,
    val ramMb: Long,
) {
    HIGH(
        labelRes = R.string.anomaly_sensitivity_high,
        netScreenOnMb = 10L,
        netScreenOffMb = 15L,
        cpuPercent = 12.0,
        ramMb = 400L,
    ),
    BALANCED(
        labelRes = R.string.anomaly_sensitivity_balanced,
        netScreenOnMb = 20L,
        netScreenOffMb = 25L,
        cpuPercent = 20.0,
        ramMb = 600L,
    ),
    LOW(
        labelRes = R.string.anomaly_sensitivity_low,
        netScreenOnMb = 50L,
        netScreenOffMb = 60L,
        cpuPercent = 32.0,
        ramMb = 850L,
    ),
}

data class AnomalyEvent(
    val id: String = UUID.randomUUID().toString(),
    val packageName: String,
    val appName: String,
    val type: AnomalyType,
    val valueFormatted: String,
    val description: String,
    val timestamp: Long = System.currentTimeMillis(),
    val screenWasOff: Boolean = false,
    val pid: Int? = null,
    val serviceOrProcess: String? = null,
    val probableCause: String? = null,
    // Campos de rastreabilidade tecnica e confiabilidade
    val rawValue: Double = 0.0,
    val unit: String = "",
    val uid: Int? = null,
    val startTime: Long = timestamp,
    val endTime: Long = timestamp,
    val durationMs: Long = (endTime - startTime).coerceAtLeast(0L),
    val confidence: ConfidenceLevel = ConfidenceLevel.HIGH,
    val nature: TelemetryNature = TelemetryNature.DIRECT_MEASUREMENT,
    val source: String = "system",
    val isEstimated: Boolean = false,
    val isComplete: Boolean = true,
    val rootAvailable: Boolean = false,
    val collectionMethod: String = "native",
    val isFallback: Boolean = false,
    val reason: String? = null,
)

data class AnomalyWatcherConfig(
    val isEnabled: Boolean = true,
    val sensitivity: AnomalySensitivity = AnomalySensitivity.BALANCED,
    val notifyNetwork: Boolean = true,
    val notifyCpu: Boolean = true,
    val notifyRam: Boolean = true,
    val notifyDeepSleep: Boolean = true,
    val whitelistedPackages: Set<String> = emptySet(),
)

/**
 * Estados da maquina de estados de monitoramento em segundo plano.
 */
enum class AnomalyWatcherState {
    INACTIVE,
    PREPARING_CAPTURE,
    CAPTURE_COMPLETED,
    SCREEN_OFF,
    EVALUATION_IN_PROGRESS,
    EVALUATION_COMPLETED,
    COLLECTION_UNAVAILABLE,
    MONITORING_ERROR
}

/**
 * Resolucao confiavel e transparente de pacotes associados a um UID.
 */
data class ResolvedUidInfo(
    val primaryPackage: String,
    val displayName: String,
    val isSharedUid: Boolean,
    val packageCount: Int,
    val allPackages: List<String>,
)
