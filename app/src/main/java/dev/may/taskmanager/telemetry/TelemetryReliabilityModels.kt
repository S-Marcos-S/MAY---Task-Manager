// SPDX-License-Identifier: GPL-3.0-or-later
package dev.may.taskmanager.telemetry

import android.content.Context
import dev.may.taskmanager.R

/**
 * Nivel de confianca tecnica de uma amostra de telemetria coletada.
 */
enum class ConfidenceLevel(val labelRes: Int) {
    HIGH(R.string.telemetry_confidence_high),
    MEDIUM(R.string.telemetry_confidence_medium),
    LOW(R.string.telemetry_confidence_low),
    UNAVAILABLE(R.string.telemetry_confidence_unavailable);

    fun getLabel(context: Context): String = context.getString(labelRes)
}

/**
 * Natureza metodologica da medicao apresentada.
 */
enum class TelemetryNature(val labelRes: Int) {
    DIRECT_MEASUREMENT(R.string.telemetry_nature_direct),
    AGGREGATED_STATISTIC(R.string.telemetry_nature_aggregated),
    ESTIMATE(R.string.telemetry_nature_estimate),
    UNAVAILABLE(R.string.telemetry_nature_unavailable);

    fun getLabel(context: Context): String = context.getString(labelRes)
}

/**
 * Amostra de telemetria com rastreabilidade, justificativa e nivel de confiabilidade.
 */
data class TelemetrySample(
    val packageName: String?,
    val uid: Int?,
    val metric: String,
    val value: Double,
    val unit: String,
    val source: String,
    val startTime: Long,
    val endTime: Long,
    val confidence: ConfidenceLevel,
    val nature: TelemetryNature = TelemetryNature.DIRECT_MEASUREMENT,
    val isEstimated: Boolean = false,
    val isComplete: Boolean = true,
    val rootAvailable: Boolean = false,
    val reason: String? = null,
) {
    val durationMs: Long
        get() = (endTime - startTime).coerceAtLeast(0L)

    val isReliable: Boolean
        get() = confidence == ConfidenceLevel.HIGH || confidence == ConfidenceLevel.MEDIUM
}
