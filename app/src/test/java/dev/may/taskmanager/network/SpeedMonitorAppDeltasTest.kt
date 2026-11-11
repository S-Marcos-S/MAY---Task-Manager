// SPDX-License-Identifier: GPL-3.0-or-later
package dev.may.taskmanager.network

import dev.may.taskmanager.telemetry.ConfidenceLevel
import dev.may.taskmanager.telemetry.TelemetryValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedMonitorAppDeltasTest {

    @Test
    fun calculateActiveAppsFromDeltas_correctlyComputesSpeedAndSorts() {
        val previousTotals = mapOf(
            10100 to 1_000_000L,
            10200 to 5_000_000L,
            10300 to 2_000_000L
        )
        val currentTotals = mapOf(
            10100 to 1_200_000L, // delta = 200,000 bytes
            10200 to 6_000_000L, // delta = 1,000,000 bytes
            10300 to 2_010_000L  // delta = 10,000 bytes
        )
        val deltaTimeSec = 2.0
        val minBytesPerSec = 10_000.0 // 10 KB/s

        val names = mapOf(
            10100 to "Browser",
            10200 to "YouTube",
            10300 to "Messenger"
        )

        val result = SpeedMonitorService.calculateActiveAppsFromDeltas(
            currentTotals = currentTotals,
            previousTotals = previousTotals,
            deltaTimeSec = deltaTimeSec,
            minBytesPerSec = minBytesPerSec,
            resolveName = { names[it] }
        )

        // 10200 speed = 1,000,000 / 2 = 500,000 B/s
        // 10100 speed = 200,000 / 2 = 100,000 B/s
        // 10300 speed = 10,000 / 2 = 5,000 B/s (below 10,000 threshold -> excluded)
        assertEquals(2, result.size)
        assertEquals("YouTube", result[0].appName)
        assertEquals(500_000.0, result[0].speedBytesSec, 0.001)

        assertEquals("Browser", result[1].appName)
        assertEquals(100_000.0, result[1].speedBytesSec, 0.001)
    }

    @Test
    fun calculateActiveAppsFromDeltas_excludesSelfOrNullNames() {
        val previous = mapOf(10100 to 100L)
        val current = mapOf(10100 to 100_100L)

        val result = SpeedMonitorService.calculateActiveAppsFromDeltas(
            currentTotals = current,
            previousTotals = previous,
            deltaTimeSec = 1.0,
            minBytesPerSec = 10.0,
            resolveName = { null } // Self app returns null
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun calculateActiveAppsFromDeltas_handlesZeroOrNegativeDeltaTime() {
        val previous = mapOf(10100 to 100L)
        val current = mapOf(10100 to 500L)

        val resultZero = SpeedMonitorService.calculateActiveAppsFromDeltas(
            currentTotals = current,
            previousTotals = previous,
            deltaTimeSec = 0.0,
            minBytesPerSec = 10.0,
            resolveName = { "App" }
        )
        assertTrue(resultZero.isEmpty())

        val resultNeg = SpeedMonitorService.calculateActiveAppsFromDeltas(
            currentTotals = current,
            previousTotals = previous,
            deltaTimeSec = -1.0,
            minBytesPerSec = 10.0,
            resolveName = { "App" }
        )
        assertTrue(resultNeg.isEmpty())
    }

    @Test
    fun calculateActiveAppsFromDeltas_includesRootAndSystemUids() {
        val previous = mapOf(
            0 to 100_000L,
            1000 to 200_000L
        )
        val current = mapOf(
            0 to 300_000L,   // delta 200,000 in 1s = 200,000 B/s
            1000 to 250_000L  // delta 50,000 in 1s = 50,000 B/s
        )

        val result = SpeedMonitorService.calculateActiveAppsFromDeltas(
            currentTotals = current,
            previousTotals = previous,
            deltaTimeSec = 1.0,
            minBytesPerSec = 10_000.0,
            resolveName = { uid ->
                when (uid) {
                    0 -> "Sistema (Root)"
                    1000 -> "Sistema Android"
                    else -> null
                }
            }
        )

        assertEquals(2, result.size)
        assertEquals("Sistema (Root)", result[0].appName)
        assertEquals(200_000.0, result[0].speedBytesSec, 0.001)
        assertEquals("Sistema Android", result[1].appName)
        assertEquals(50_000.0, result[1].speedBytesSec, 0.001)
    }

    @Test
    fun calculateActiveAppsFromDeltas_preservesRealSpeedAndFlagsDivergenceWithoutArtificialDownscaling() {
        val previous = mapOf(10100 to 0L)
        val current = mapOf(10100 to 3_000_000L) // Delta = 3 MB/s in 1s

        val totalDeviceSpeed = 1_000_000.0 // Global instantaneous is 1 MB/s (divergence due to bucket delay)

        val result = SpeedMonitorService.calculateActiveAppsFromDeltas(
            currentTotals = current,
            previousTotals = previous,
            deltaTimeSec = 1.0,
            minBytesPerSec = 10_000.0,
            totalDeviceSpeed = totalDeviceSpeed,
            resolveName = { "YouTube" }
        )

        assertEquals(1, result.size)
        assertEquals("YouTube", result[0].appName)
        // Regra fundamental: NUNCA apresentar estimativa ou diminuicao artificial
        // como se fosse medicao comprovada. A medicao real delta (3 MB/s) e preservada
        // e a divergencia estatistica e explicitamente sinalizada com confianca LOW.
        assertEquals(3_000_000.0, result[0].speedBytesSec, 0.001)
        assertTrue(result[0].hasDivergence)
        assertEquals(ConfidenceLevel.LOW, result[0].confidence)
    }

    @Test
    fun calculateActiveAppsFromDeltas_keepsOriginalSpeedAndMediumConfidenceWhenWithinTotalSpeed() {
        val previous = mapOf(10100 to 0L)
        val current = mapOf(10100 to 500_000L) // 500 KB/s

        val totalDeviceSpeed = 1_000_000.0 // Total device speed is 1 MB/s

        val result = SpeedMonitorService.calculateActiveAppsFromDeltas(
            currentTotals = current,
            previousTotals = previous,
            deltaTimeSec = 1.0,
            minBytesPerSec = 10_000.0,
            totalDeviceSpeed = totalDeviceSpeed,
            resolveName = { "App" }
        )

        assertEquals(1, result.size)
        assertEquals("App", result[0].appName)
        assertEquals(500_000.0, result[0].speedBytesSec, 0.001)
        assertFalse(result[0].hasDivergence)
        assertEquals(ConfidenceLevel.MEDIUM, result[0].confidence)
    }

    @Test
    fun telemetryValidator_evaluatesCounterDifferencesAndResets() {
        assertEquals(TelemetryValidator.CounterState.VALID, TelemetryValidator.evaluateCounter(1500L, 1000L))
        assertEquals(TelemetryValidator.CounterState.RESET_OR_REBOOT, TelemetryValidator.evaluateCounter(500L, 1000L))
        assertEquals(TelemetryValidator.CounterState.NEGATIVE_OR_UNSUPPORTED, TelemetryValidator.evaluateCounter(-1L, 1000L))
        assertEquals(TelemetryValidator.CounterState.NEGATIVE_OR_UNSUPPORTED, TelemetryValidator.evaluateCounter(1000L, -1L))
        assertEquals(TelemetryValidator.CounterState.SAME_SAMPLE, TelemetryValidator.evaluateCounter(1000L, 1000L))
    }

    @Test
    fun telemetryValidator_computesMonotonicGlobalSpeedAccurately() {
        val (rxSample, txSample) = TelemetryValidator.computeGlobalNetworkSpeed(
            currentRx = 2_000_000L,
            currentTx = 1_500_000L,
            prevRx = 1_000_000L,
            prevTx = 1_000_000L,
            deltaNanos = 1_000_000_000L, // 1 segundo
            isSupported = true,
            rootAvailable = false,
        )

        assertEquals(1_000_000.0, rxSample.value, 0.01)
        assertEquals(500_000.0, txSample.value, 0.01)
        assertEquals(ConfidenceLevel.HIGH, rxSample.confidence)
        assertEquals(ConfidenceLevel.HIGH, txSample.confidence)
        assertFalse(rxSample.isEstimated)
    }

    @Test
    fun telemetryValidator_handlesCounterResetTransparently() {
        val (rxSample, _) = TelemetryValidator.computeGlobalNetworkSpeed(
            currentRx = 100L, // Menor que anterior -> reinicializacao
            currentTx = 100L,
            prevRx = 50_000L,
            prevTx = 50_000L,
            deltaNanos = 1_000_000_000L,
            isSupported = true,
            rootAvailable = false,
        )

        assertEquals(ConfidenceLevel.LOW, rxSample.confidence)
        assertTrue(rxSample.isEstimated)
    }
}
