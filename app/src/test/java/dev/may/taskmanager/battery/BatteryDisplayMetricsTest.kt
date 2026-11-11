// SPDX-License-Identifier: GPL-3.0-or-later
package dev.may.taskmanager.battery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryDisplayMetricsTest {

    @Test
    fun resolve_discardsStalePreResetDumpsysSnapshotWhenSessionIsFreshlyReset() {
        // Sessão do DrainState recém-resetada (0ms de tela, 1s decorrido, 0mAh descarregados)
        val now = System.currentTimeMillis()
        val freshDrainState = DrainState(
            sessionStartTime = now,
            screenOnTimeMs = 0L,
            screenOffTimeMs = 0L,
            deepSleepTimeMs = 0L,
            awakeTimeMs = 0L,
            screenOnDrainMah = 0.0,
            screenOffDrainMah = 0.0,
            batteryCapacityMah = 4000.0,
        )

        // Snapshot antigo acumulado do dumpsys que não havia sido zerado pelo sistema (ex: 5h de tela, 25% de descarga)
        val staleDumpsysSnapshot = BatteryStatsParser.FullSnapshot(
            capturedAt = now,
            batteryRealtimeMs = 18_000_000L, // 5 horas
            batteryUptimeMs = 9_000_000L,
            screenOnTimeMs = 7_200_000L,     // 2 horas de tela
            screenOnDischargePercent = 15.0f,
            screenOffDischargePercent = 10.0f,
            estimatedCapacityMah = 4000,
        )

        val metrics = BatteryDisplayMetrics.resolve(
            snapshot = staleDumpsysSnapshot,
            drainState = freshDrainState
        )

        // Verificação: o snapshot antigo NÃO deve sobrepor a sessão recém-resetada
        assertEquals(0L, metrics.screenOnMs)
        assertEquals(0L, metrics.screenOffMs)
        assertEquals(0L, metrics.totalTimeMs)
        assertEquals(0f, metrics.screenOnDischargePercent, 0.001f)
        assertEquals(0f, metrics.screenOffDischargePercent, 0.001f)
        assertEquals(0f, metrics.totalDischargePercent, 0.001f)
        assertEquals(0.0, metrics.totalDrainMah, 0.001)
    }

    @Test
    fun resolve_computesMetricsCorrectlyFromFreshDumpsysSnapshot() {
        val now = System.currentTimeMillis()
        val drainState = DrainState(
            sessionStartTime = now - 3_600_000L,
            screenOnTimeMs = 1_800_000L,
            screenOffTimeMs = 1_800_000L,
            batteryCapacityMah = 4000.0,
        )

        val activeSnapshot = BatteryStatsParser.FullSnapshot(
            capturedAt = now,
            batteryRealtimeMs = 3_600_000L,
            batteryUptimeMs = 2_400_000L,
            screenOnTimeMs = 1_800_000L,
            screenOnDischargePercent = 8.0f,
            screenOffDischargePercent = 2.0f,
            estimatedCapacityMah = 4000,
        )

        val metrics = BatteryDisplayMetrics.resolve(
            snapshot = activeSnapshot,
            drainState = drainState
        )

        assertEquals(1_800_000L, metrics.screenOnMs)
        assertEquals(1_800_000L, metrics.screenOffMs)
        assertEquals(3_600_000L, metrics.totalTimeMs)
        assertEquals(8.0f, metrics.screenOnDischargePercent, 0.001f)
        assertEquals(2.0f, metrics.screenOffDischargePercent, 0.001f)
        assertEquals(10.0f, metrics.totalDischargePercent, 0.001f)
        assertEquals(400.0, metrics.totalDrainMah, 0.001)
    }

    @Test
    fun resolve_computesMetricsFromDrainStateWhenSnapshotIsNull() {
        val now = System.currentTimeMillis()
        val drainState = DrainState(
            sessionStartTime = now - 1_200_000L,
            screenOnTimeMs = 600_000L,
            screenOffTimeMs = 600_000L,
            deepSleepTimeMs = 400_000L,
            awakeTimeMs = 200_000L,
            screenOnDrainMah = 120.0,
            screenOffDrainMah = 40.0,
            batteryCapacityMah = 4000.0,
        )

        val metrics = BatteryDisplayMetrics.resolve(
            snapshot = null,
            drainState = drainState
        )

        assertEquals(600_000L, metrics.screenOnMs)
        assertEquals(600_000L, metrics.screenOffMs)
        assertEquals(1_200_000L, metrics.totalTimeMs)
        assertEquals(3.0f, metrics.screenOnDischargePercent, 0.001f)
        assertEquals(1.0f, metrics.screenOffDischargePercent, 0.001f)
        assertEquals(4.0f, metrics.totalDischargePercent, 0.001f)
        assertEquals(160.0, metrics.totalDrainMah, 0.001)
    }
}
