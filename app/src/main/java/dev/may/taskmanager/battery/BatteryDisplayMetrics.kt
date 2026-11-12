// SPDX-License-Identifier: GPL-3.0-or-later
package dev.may.taskmanager.battery

/**
 * Unified battery display metrics calculated identically for both in-app screens
 * and the persistent notification.
 */
data class BatteryDisplayMetrics(
    val screenOnMs: Long,
    val screenOffMs: Long,
    val totalTimeMs: Long,
    val screenOnDischargePercent: Float,
    val screenOffDischargePercent: Float,
    val totalDischargePercent: Float,
    val totalDrainMah: Double,
    val deepSleepMs: Long,
    val awakeMs: Long,
    val deepSleepPercentage: Float,
    val activeMs: Long,
    val idleMs: Long,
    val screenOnDrainRatePercent: Double,
    val screenOffDrainRatePercent: Double,
    val deepSleepDrainRatePercent: Double,
    val awakeDrainRatePercent: Double,
    val activeDrainRatePercent: Double,
    val idleDrainRatePercent: Double,
    val averageDrainRatePercent: Double
) {
    companion object {
        fun resolve(
            snapshot: BatteryStatsParser.FullSnapshot?,
            drainState: DrainState
        ): BatteryDisplayMetrics {
            // Se a sessão do DrainTracker acabou de ser resetada e o snapshot do dumpsys
            // contém histórico antigo acumulado que não foi zerado, descarta o snapshot
            // desatualizado para não sobrepor métricas antigas sobre a sessão recém-zerada.
            val isSnapshotStale = snapshot != null && (
                (drainState.sessionStartTime > 0L && snapshot.capturedAt < drainState.sessionStartTime) ||
                (drainState.sessionStartTime > 0L && snapshot.batteryRealtimeMs > (drainState.totalTimeMs + 30_000L))
            )

            val effectiveSnapshot = if (isSnapshotStale) null else snapshot

            val screenOnMs = when {
                effectiveSnapshot != null && effectiveSnapshot.screenOnTimeMs > 0L -> effectiveSnapshot.screenOnTimeMs
                drainState.screenOnTimeMs > 0L -> drainState.screenOnTimeMs
                else -> 0L
            }

            val totalTimeMs = when {
                effectiveSnapshot != null && effectiveSnapshot.batteryRealtimeMs > 0L -> effectiveSnapshot.batteryRealtimeMs
                drainState.totalTimeMs > 0L -> drainState.totalTimeMs
                else -> 0L
            }

            val screenOffMs = when {
                effectiveSnapshot != null && effectiveSnapshot.batteryRealtimeMs > effectiveSnapshot.screenOnTimeMs ->
                    (effectiveSnapshot.batteryRealtimeMs - effectiveSnapshot.screenOnTimeMs).coerceAtLeast(0L)
                drainState.screenOffTimeMs > 0L -> drainState.screenOffTimeMs
                drainState.screenOnTimeMs > 0L && totalTimeMs > screenOnMs -> (totalTimeMs - screenOnMs).coerceAtLeast(0L)
                else -> 0L
            }

            val levelDrop = if (drainState.sessionStartBatteryLevel != null && drainState.batteryLevel != null) {
                (drainState.sessionStartBatteryLevel - drainState.batteryLevel).coerceAtLeast(0)
            } else null

            val isZeroPercentDischarge = levelDrop != null && levelDrop == 0

            val rawScreenOnDischarge = if (isZeroPercentDischarge) 0f else when {
                effectiveSnapshot != null && effectiveSnapshot.screenOnDischargePercent > 0f -> effectiveSnapshot.screenOnDischargePercent
                drainState.screenOnDrainMah > 0.0 && drainState.batteryCapacityMah > 0 ->
                    (drainState.screenOnDrainMah / drainState.batteryCapacityMah * 100.0).toFloat()
                else -> 0f
            }

            val rawScreenOffDischarge = if (isZeroPercentDischarge) 0f else when {
                effectiveSnapshot != null && effectiveSnapshot.screenOffDischargePercent > 0f -> effectiveSnapshot.screenOffDischargePercent
                drainState.screenOffDrainMah > 0.0 && drainState.batteryCapacityMah > 0 ->
                    (drainState.screenOffDrainMah / drainState.batteryCapacityMah * 100.0).toFloat()
                else -> 0f
            }

            val screenOnDischarge = if (levelDrop != null) rawScreenOnDischarge.coerceAtMost(levelDrop.toFloat()) else rawScreenOnDischarge
            val screenOffDischarge = if (levelDrop != null) rawScreenOffDischarge.coerceAtMost(levelDrop.toFloat()) else rawScreenOffDischarge

            val rawTotalDischarge = if (isZeroPercentDischarge) 0f else when {
                screenOnDischarge + screenOffDischarge > 0f -> screenOnDischarge + screenOffDischarge
                drainState.totalDrainMah > 0.0 && drainState.batteryCapacityMah > 0 ->
                    (drainState.totalDrainMah / drainState.batteryCapacityMah * 100.0).toFloat()
                else -> 0f
            }
            val totalDischarge = if (levelDrop != null) rawTotalDischarge.coerceAtMost(levelDrop.toFloat()) else rawTotalDischarge

            val capacityMah = when {
                effectiveSnapshot != null && effectiveSnapshot.estimatedCapacityMah > 0 -> effectiveSnapshot.estimatedCapacityMah.toDouble()
                drainState.batteryCapacityMah > 0 -> drainState.batteryCapacityMah
                else -> 4000.0
            }

            val totalDrainMah = when {
                isZeroPercentDischarge -> 0.0
                totalDischarge > 0f -> (totalDischarge / 100.0) * capacityMah
                drainState.totalDrainMah > 0.0 -> drainState.totalDrainMah
                else -> 0.0
            }

            val deepSleepMs = when {
                effectiveSnapshot != null && effectiveSnapshot.batteryRealtimeMs > 0L && effectiveSnapshot.batteryRealtimeMs >= effectiveSnapshot.batteryUptimeMs ->
                    (effectiveSnapshot.batteryRealtimeMs - effectiveSnapshot.batteryUptimeMs).coerceAtMost(screenOffMs).coerceAtLeast(0L)
                drainState.deepSleepTimeMs > 0L -> drainState.deepSleepTimeMs.coerceAtMost(screenOffMs)
                else -> 0L
            }

            val awakeMs = when {
                effectiveSnapshot != null && effectiveSnapshot.batteryUptimeMs > 0L && effectiveSnapshot.batteryUptimeMs >= effectiveSnapshot.screenOnTimeMs ->
                    (effectiveSnapshot.batteryUptimeMs - effectiveSnapshot.screenOnTimeMs).coerceAtMost(screenOffMs).coerceAtLeast(0L)
                drainState.awakeTimeMs > 0L -> drainState.awakeTimeMs.coerceAtMost(screenOffMs)
                screenOffMs >= deepSleepMs -> (screenOffMs - deepSleepMs).coerceAtLeast(0L)
                else -> 0L
            }

            val deepSleepPercentage = when {
                screenOffMs > 0L -> (deepSleepMs.toFloat() / screenOffMs * 100f).coerceIn(0f, 100f)
                else -> drainState.deepSleepPercentage
            }

            val activeMs = drainState.activeTimeMs.coerceAtMost(screenOnMs)
            val idleMs = if (screenOnMs >= activeMs) (screenOnMs - activeMs).coerceAtLeast(0L) else drainState.idleTimeMs

            val screenOnHours = screenOnMs / 3_600_000.0
            val screenOnDrainRatePercent = if (!isZeroPercentDischarge && screenOnDischarge > 0f && screenOnHours >= (60.0 / 3600.0)) {
                (screenOnDischarge / screenOnHours).coerceIn(0.0, 100.0)
            } else 0.0

            val screenOffHours = screenOffMs / 3_600_000.0
            val screenOffDrainRatePercent = if (!isZeroPercentDischarge && screenOffDischarge > 0f && screenOffHours >= (60.0 / 3600.0)) {
                (screenOffDischarge / screenOffHours).coerceIn(0.0, 100.0)
            } else 0.0

            val totalHours = totalTimeMs / 3_600_000.0
            val averageDrainRatePercent = if (!isZeroPercentDischarge && totalDischarge > 0f && totalHours >= (60.0 / 3600.0)) {
                (totalDischarge / totalHours).coerceIn(0.0, 100.0)
            } else 0.0

            val deepSleepDrainRatePercent = when {
                screenOffDrainRatePercent > 0.0 && drainState.screenOffDrainRatePercent > 0.0 && drainState.deepSleepDrainRatePercent > 0.0 -> {
                    val ratio = (drainState.deepSleepDrainRatePercent / drainState.screenOffDrainRatePercent).coerceIn(0.0, 1.0)
                    screenOffDrainRatePercent * ratio
                }
                screenOffDrainRatePercent > 0.0 && screenOffMs > 0L -> {
                    val ratio = (deepSleepMs.toDouble() / screenOffMs).coerceIn(0.0, 1.0)
                    screenOffDrainRatePercent * ratio
                }
                else -> 0.0
            }

            val awakeDrainRatePercent = when {
                screenOffDrainRatePercent > 0.0 && drainState.screenOffDrainRatePercent > 0.0 && drainState.awakeDrainRatePercent > 0.0 -> {
                    val ratio = (drainState.awakeDrainRatePercent / drainState.screenOffDrainRatePercent).coerceAtLeast(1.0)
                    screenOffDrainRatePercent * ratio
                }
                screenOffDrainRatePercent > 0.0 && screenOffMs > 0L -> {
                    val ratio = (awakeMs.toDouble() / screenOffMs).coerceIn(0.0, 2.0)
                    screenOffDrainRatePercent * ratio
                }
                else -> 0.0
            }

            val activeDrainRatePercent = when {
                screenOnDrainRatePercent > 0.0 && drainState.screenOnDrainRatePercent > 0.0 && drainState.activeDrainRatePercent > 0.0 -> {
                    val ratio = (drainState.activeDrainRatePercent / drainState.screenOnDrainRatePercent).coerceAtLeast(1.0)
                    screenOnDrainRatePercent * ratio
                }
                screenOnDrainRatePercent > 0.0 && screenOnMs > 0L -> {
                    val ratio = (activeMs.toDouble() / screenOnMs).coerceIn(0.0, 2.0)
                    screenOnDrainRatePercent * ratio
                }
                else -> 0.0
            }

            val idleDrainRatePercent = when {
                screenOnDrainRatePercent > 0.0 && drainState.screenOnDrainRatePercent > 0.0 && drainState.idleDrainRatePercent > 0.0 -> {
                    val ratio = (drainState.idleDrainRatePercent / drainState.screenOnDrainRatePercent).coerceIn(0.0, 1.0)
                    screenOnDrainRatePercent * ratio
                }
                screenOnDrainRatePercent > 0.0 && screenOnMs > 0L -> {
                    val ratio = (idleMs.toDouble() / screenOnMs).coerceIn(0.0, 1.0)
                    screenOnDrainRatePercent * ratio
                }
                else -> 0.0
            }

            return BatteryDisplayMetrics(
                screenOnMs = screenOnMs,
                screenOffMs = screenOffMs,
                totalTimeMs = totalTimeMs,
                screenOnDischargePercent = screenOnDischarge,
                screenOffDischargePercent = screenOffDischarge,
                totalDischargePercent = totalDischarge,
                totalDrainMah = totalDrainMah,
                deepSleepMs = deepSleepMs,
                awakeMs = awakeMs,
                deepSleepPercentage = deepSleepPercentage,
                activeMs = activeMs,
                idleMs = idleMs,
                screenOnDrainRatePercent = screenOnDrainRatePercent,
                screenOffDrainRatePercent = screenOffDrainRatePercent,
                deepSleepDrainRatePercent = deepSleepDrainRatePercent,
                awakeDrainRatePercent = awakeDrainRatePercent,
                activeDrainRatePercent = activeDrainRatePercent,
                idleDrainRatePercent = idleDrainRatePercent,
                averageDrainRatePercent = averageDrainRatePercent
            )
        }
    }
}

/**
 * Format milliseconds into a clean, compact duration (e.g., "1d 4h", "2h 30m", "45m", "15s").
 */
fun formatBatteryDuration(ms: Long): String {
    if (ms <= 0L) return "0m"
    val seconds = ms / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    val days = hours / 24

    return when {
        days > 0 -> "${days}d ${hours % 24}h"
        hours > 0 -> "${hours}h ${minutes % 60}m"
        minutes > 0 -> "${minutes}m"
        else -> "${seconds}s"
    }
}
