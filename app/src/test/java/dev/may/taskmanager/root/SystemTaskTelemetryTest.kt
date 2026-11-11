// SPDX-License-Identifier: GPL-3.0-or-later
package dev.may.taskmanager.root

import dev.may.taskmanager.telemetry.ConfidenceLevel
import dev.may.taskmanager.telemetry.TelemetryNature
import dev.may.taskmanager.telemetry.TelemetryValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemTaskTelemetryTest {

    @Test
    fun `SystemPerformanceSnapshot default values are consistent`() {
        val snapshot = SystemPerformanceSnapshot(
            totalCpuPercent = 14.5,
            cpuCores = 8,
            ramTotalMb = 8192.0,
            ramUsedMb = 4096.0,
            ramUsedPercent = 50.0,
        )

        assertEquals(14.5, snapshot.totalCpuPercent, 0.01)
        assertEquals(8, snapshot.cpuCores)
        assertEquals(8192.0, snapshot.ramTotalMb, 0.01)
        assertEquals(4096.0, snapshot.ramUsedMb, 0.01)
        assertEquals(50.0, snapshot.ramUsedPercent, 0.01)
    }

    @Test
    fun `TaskProcessItem models process accurately`() {
        val item = TaskProcessItem(
            pid = 1234,
            packageName = "com.android.chrome",
            processName = "com.android.chrome:sandboxed_process0",
            appName = "Chrome",
            user = "u0_a123",
            cpuPercent = 5.2,
            ramMb = 128.5,
            isForeground = true,
            isSystemProcess = false,
        )

        assertEquals(1234, item.pid)
        assertEquals("com.android.chrome", item.packageName)
        assertTrue(item.isForeground)
        assertFalse(item.isSystemProcess)
        assertEquals(5.2, item.cpuPercent, 0.01)
        assertEquals(128.5, item.ramMb, 0.01)
    }

    @Test
    fun `RunningTasksSnapshot represents non-root limitation with technical transparency`() {
        val snapshot = RunningTasksSnapshot(
            foregroundApps = listOf(
                TaskProcessItem(
                    pid = 2001,
                    packageName = "com.example.app",
                    processName = "com.example.app",
                    appName = "Example",
                    user = "app",
                    cpuPercent = 0.0,
                    ramMb = 45.0,
                    isForeground = true,
                    pssMb = 45.0,
                    confidence = ConfidenceLevel.MEDIUM,
                )
            ),
            rootAvailable = false,
            isTelemetryLimited = true,
            diagnosticMessage = "SELinux blocks non-root CPU queries",
        )

        assertFalse(snapshot.rootAvailable)
        assertTrue(snapshot.isTelemetryLimited)
        assertEquals(1, snapshot.foregroundApps.size)
        assertEquals(45.0, snapshot.foregroundApps[0].pssMb ?: 0.0, 0.01)
        assertEquals(ConfidenceLevel.MEDIUM, snapshot.foregroundApps[0].confidence)
    }

    @Test
    fun `parseCpuTimeToMs parses various ps time formats accurately`() {
        assertEquals(50L, SystemTaskInspector.parseCpuTimeToMs("0:00.05"))
        assertEquals(1010L, SystemTaskInspector.parseCpuTimeToMs("0:01.01"))
        assertEquals(3135180L, SystemTaskInspector.parseCpuTimeToMs("52:15.18"))
        assertEquals(5775220L, SystemTaskInspector.parseCpuTimeToMs("01:36:15.22"))
        assertEquals(93784500L, SystemTaskInspector.parseCpuTimeToMs("1-02:03:04.50"))
        assertEquals(0L, SystemTaskInspector.parseCpuTimeToMs(""))
        assertEquals(0L, SystemTaskInspector.parseCpuTimeToMs("invalid"))
    }

    @Test
    fun `parseElapsedTimeToMs parses various ps elapsed formats accurately`() {
        assertEquals(5000L, SystemTaskInspector.parseElapsedTimeToMs("00:05"))
        assertEquals(754000L, SystemTaskInspector.parseElapsedTimeToMs("12:34"))
        assertEquals(5025000L, SystemTaskInspector.parseElapsedTimeToMs("01:23:45"))
        assertEquals(183845000L, SystemTaskInspector.parseElapsedTimeToMs("2-03:04:05"))
        assertEquals(0L, SystemTaskInspector.parseElapsedTimeToMs(""))
    }

    @Test
    fun `telemetryValidator classifies cpu with consecutive hits`() {
        val sample = TelemetryValidator.classifyProcessCpu(
            packageName = "com.bad.app",
            uid = 10055,
            pid = 4321,
            deltaCpuMs = 250L,
            deltaWallMs = 1000L,
            coresCount = 8,
            consecutiveHits = 2,
            rootAvailable = true,
        )

        // (250 / (1000 * 8)) * 100 = 3.125%
        assertEquals(3.125, sample.value, 0.001)
        assertEquals(ConfidenceLevel.HIGH, sample.confidence)
        assertEquals(TelemetryNature.DIRECT_MEASUREMENT, sample.nature)
        assertFalse(sample.isEstimated)
    }

    @Test
    fun `telemetryValidator reports cpu unavailable honestly when root is absent`() {
        val sample = TelemetryValidator.classifyProcessCpu(
            packageName = "com.bad.app",
            uid = 10055,
            pid = 4321,
            deltaCpuMs = 0L,
            deltaWallMs = 1000L,
            coresCount = 8,
            consecutiveHits = 0,
            rootAvailable = false,
        )

        assertEquals(ConfidenceLevel.UNAVAILABLE, sample.confidence)
        assertEquals(TelemetryNature.UNAVAILABLE, sample.nature)
        assertFalse(sample.isComplete)
    }
}
