// SPDX-License-Identifier: GPL-3.0-or-later
package dev.may.taskmanager.root

import dev.may.taskmanager.telemetry.ConfidenceLevel
import dev.may.taskmanager.telemetry.TelemetryNature
import dev.may.taskmanager.telemetry.TelemetryValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        assertEquals(5.2, item.cpuPercent ?: 0.0, 0.01)
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
                    cpuPercent = null,
                    ramMb = 45.0,
                    isForeground = true,
                    pssMb = 45.0,
                    confidence = ConfidenceLevel.UNAVAILABLE,
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
        assertNull(snapshot.foregroundApps[0].cpuPercent)
        assertEquals(ConfidenceLevel.UNAVAILABLE, snapshot.foregroundApps[0].confidence)
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

    @Test
    fun `telemetryValidator handles negative deltaCpu as recycled process reset`() {
        val sample = TelemetryValidator.classifyProcessCpu(
            packageName = "com.recycled.app",
            uid = 10099,
            pid = 9999,
            deltaCpuMs = -200L, // PID reciclado pelo kernel
            deltaWallMs = 1000L,
            coresCount = 8,
            consecutiveHits = 0,
            rootAvailable = true,
        )

        assertEquals(0.0, sample.value, 0.001)
        assertEquals(ConfidenceLevel.LOW, sample.confidence)
        assertEquals(TelemetryNature.ESTIMATE, sample.nature)
        assertTrue(sample.isEstimated)
    }

    @Test
    fun `parseProcStat parses Linux proc stat line correctly including comm with spaces and parentheses`() {
        val sampleLine = "12345 (Chrome: privileged process) S 1 1234 1234 0 -1 4194560 100 0 0 0 150 250 0 0 20 0 10 0 543210 1234567 1000 18446744073709551615 0 0 0 0 0 0 0 2147483647 0 0 0 0 17 0 0 0 0 0 0 0 0 0 0 0 0 0"
        val stat = SystemTaskInspector.parseProcStat(sampleLine)

        assertNotNull(stat)
        assertEquals(12345, stat?.pid)
        assertEquals("Chrome: privileged process", stat?.comm)
        assertEquals("S", stat?.state)
        assertEquals(150L, stat?.utimeTicks)
        assertEquals(250L, stat?.stimeTicks)
        assertEquals(543210L, stat?.starttimeTicks)
        assertEquals(400L, stat?.totalCpuTicks)
    }

    @Test
    fun `parseVmRssKb parses VmRSS correctly from status content`() {
        val statusText = """
            Name: chrome
            Umask: 0077
            State: S (sleeping)
            VmPeak: 123456 kB
            VmSize: 100000 kB
            VmHWM: 45000 kB
            VmRSS: 32768 kB
            RssAnon: 20000 kB
        """.trimIndent()

        val rssKb = SystemTaskInspector.parseVmRssKb(statusText)
        assertEquals(32768L, rssKb)
    }

    @Test
    fun `parseVmRssKb returns null when VmRSS is absent`() {
        val statusText = "Name: init\nState: S\n"
        val rssKb = SystemTaskInspector.parseVmRssKb(statusText)
        assertEquals(null, rssKb)
    }

    @Test
    fun `parsePsLines calculates instantaneous delta cpu across consecutive samples`() {
        SystemTaskInspector.processCpuHistory.clear()

        val sample1 = """
            PID USER S RSS %MEM TIME ARGS
            1000 u0_a50 S 51200 2.0 0:01.00 com.whatsapp
        """.trimIndent()

        val sample2 = """
            PID USER S RSS %MEM TIME ARGS
            1000 u0_a50 S 51200 2.0 0:01.40 com.whatsapp
        """.trimIndent()

        // Sample 1: first observation sets baseline, delta is not yet available
        val res1 = SystemTaskInspector.parsePsLines(sample1, coresCount = 2, nowRealtime = 10000L)
        assertEquals(1, res1.size)
        assertEquals(1000, res1[0].pid)
        assertEquals("u0_a50", res1[0].user)
        assertNull(res1[0].cpuPercent)
        assertEquals(ConfidenceLevel.UNAVAILABLE, res1[0].confidence)
        assertEquals(50.0, res1[0].rssMb ?: 0.0, 0.01)

        // Sample 2: deltaCpu = 400ms, deltaWall = 1000ms, cores = 2 -> (400 / (1000 * 2)) * 100 = 20.0%
        val res2 = SystemTaskInspector.parsePsLines(sample2, coresCount = 2, nowRealtime = 11000L)
        assertEquals(1, res2.size)
        assertEquals(1000, res2[0].pid)
        assertEquals(20.0, res2[0].cpuPercent ?: -1.0, 0.01)
        assertEquals(ConfidenceLevel.HIGH, res2[0].confidence)
    }

    @Test
    fun `parsePsLines reports exact zero percent for inactive process without historical residual`() {
        SystemTaskInspector.processCpuHistory.clear()

        val sample1 = """
            PID USER S RSS %MEM TIME ARGS
            1000 u0_a50 S 51200 2.0 0:01.00 com.whatsapp
        """.trimIndent()

        val sample2 = """
            PID USER S RSS %MEM TIME ARGS
            1000 u0_a50 S 51200 2.0 0:01.00 com.whatsapp
        """.trimIndent()

        // Baseline
        SystemTaskInspector.parsePsLines(sample1, coresCount = 4, nowRealtime = 10000L)

        // Second sample with no additional CPU ticks (sleeping in background)
        val res2 = SystemTaskInspector.parsePsLines(sample2, coresCount = 4, nowRealtime = 12000L)
        assertEquals(1, res2.size)
        assertEquals(0.0, res2[0].cpuPercent ?: -1.0, 0.001)
        assertEquals(ConfidenceLevel.HIGH, res2[0].confidence)
    }

    @Test
    fun `parsePsLines prioritizes proc stat utime and stime with CLK_TCK conversion`() {
        SystemTaskInspector.processCpuHistory.clear()

        val psText = """
            PID USER S RSS %MEM TIME ARGS
            3000 u0_a70 S 40000 1.5 0:05.00 com.example.service
        """.trimIndent()

        val statMap1 = mapOf(
            3000 to SystemTaskInspector.ProcStatInfo(
                pid = 3000,
                comm = "service",
                state = "S",
                utimeTicks = 100L,
                stimeTicks = 50L,
                starttimeTicks = 50000L,
            )
        )

        val statMap2 = mapOf(
            3000 to SystemTaskInspector.ProcStatInfo(
                pid = 3000,
                comm = "service",
                state = "S",
                utimeTicks = 150L,
                stimeTicks = 80L,
                starttimeTicks = 50000L,
            )
        )

        // Baseline with clkTck = 100L (100 Hz -> 150 ticks = 1500ms)
        val res1 = SystemTaskInspector.parsePsLines(psText, procStatMap = statMap1, coresCount = 4, nowRealtime = 20000L, clkTck = 100L)
        assertNull(res1[0].cpuPercent)
        assertEquals(ConfidenceLevel.UNAVAILABLE, res1[0].confidence)

        // Sample 2: deltaTicks = (230 - 150) = 80 ticks = 800ms. deltaWall = 1000ms. cores = 4 -> (800 / 4000) * 100 = 20.0%
        val res2 = SystemTaskInspector.parsePsLines(psText, procStatMap = statMap2, coresCount = 4, nowRealtime = 21000L, clkTck = 100L)
        assertEquals(20.0, res2[0].cpuPercent ?: -1.0, 0.01)
        assertEquals(ConfidenceLevel.HIGH, res2[0].confidence)
    }

    @Test
    fun `parsePsLines avoids historical fallback and marks cpu unavailable when only raw percent cpu is present`() {
        SystemTaskInspector.processCpuHistory.clear()

        val sample = """
            pid user %cpu %mem command
            2000 u0_a60 16.0 1.5 /system/bin/surfaceflinger
        """.trimIndent()

        // When TIME column is not present and procStat is absent, never fall back to lifetime %CPU
        val res = SystemTaskInspector.parsePsLines(sample, coresCount = 4, nowRealtime = 15000L)
        assertEquals(1, res.size)
        assertEquals(2000, res[0].pid)
        assertNull(res[0].cpuPercent)
        assertEquals(ConfidenceLevel.UNAVAILABLE, res[0].confidence)
        assertEquals(1.5, res[0].memPercent, 0.01)
    }

    @Test
    fun `parsePsLines handles short interval below threshold as unavailable to prevent tick jitter explosion`() {
        SystemTaskInspector.processCpuHistory.clear()

        val sample1 = """
            PID USER S RSS %MEM TIME ARGS
            1000 u0_a50 S 51200 2.0 0:01.00 com.whatsapp
        """.trimIndent()

        val sample2 = """
            PID USER S RSS %MEM TIME ARGS
            1000 u0_a50 S 51200 2.0 0:01.05 com.whatsapp
        """.trimIndent()

        // Baseline at t=10000ms
        SystemTaskInspector.parsePsLines(sample1, coresCount = 4, nowRealtime = 10000L)

        // Sample 2 at t=10010ms (deltaWall = 10ms < 20ms)
        val res2 = SystemTaskInspector.parsePsLines(sample2, coresCount = 4, nowRealtime = 10010L)
        assertNull(res2[0].cpuPercent)
        assertEquals(ConfidenceLevel.UNAVAILABLE, res2[0].confidence)
    }

    @Test
    fun `parsePsLines resets baseline cleanly when PID is recycled with different starttime`() {
        SystemTaskInspector.processCpuHistory.clear()

        val psText = """
            PID USER S RSS %MEM TIME ARGS
            4000 u0_a80 S 10000 1.0 10:00.00 com.browser
        """.trimIndent()

        val statMapOld = mapOf(
            4000 to SystemTaskInspector.ProcStatInfo(
                pid = 4000,
                comm = "browser",
                state = "S",
                utimeTicks = 60000L,
                stimeTicks = 0L,
                starttimeTicks = 10000L,
            )
        )

        val statMapNew = mapOf(
            4000 to SystemTaskInspector.ProcStatInfo(
                pid = 4000,
                comm = "browser",
                state = "S",
                utimeTicks = 50L,
                stimeTicks = 0L,
                starttimeTicks = 25000L, // Recycled PID by Android kernel
            )
        )

        // Old process baseline
        SystemTaskInspector.parsePsLines(psText, procStatMap = statMapOld, coresCount = 4, nowRealtime = 50000L)

        // New recycled process with same PID but different starttime
        val res2 = SystemTaskInspector.parsePsLines(psText, procStatMap = statMapNew, coresCount = 4, nowRealtime = 51000L)

        assertEquals(1, res2.size)
        // Must NOT inherit previous process ticks (which would result in huge negative jump or spike)
        assertNull(res2[0].cpuPercent)
        assertEquals(ConfidenceLevel.UNAVAILABLE, res2[0].confidence)
    }

    @Test
    fun `parsePsLines resets baseline cleanly when PID is recycled with negative delta cpu`() {
        SystemTaskInspector.processCpuHistory.clear()

        val sample1 = """
            PID USER S RSS %MEM TIME ARGS
            4000 u0_a80 S 10000 1.0 10:00.00 com.browser
        """.trimIndent()

        val sample2 = """
            PID USER S RSS %MEM TIME ARGS
            4000 u0_a80 S 10000 1.0 0:00.50 com.browser
        """.trimIndent()

        SystemTaskInspector.parsePsLines(sample1, coresCount = 4, nowRealtime = 50000L)
        val res2 = SystemTaskInspector.parsePsLines(sample2, coresCount = 4, nowRealtime = 51000L)

        assertEquals(1, res2.size)
        assertNull(res2[0].cpuPercent)
        assertEquals(ConfidenceLevel.UNAVAILABLE, res2[0].confidence)
    }

    @Test
    fun `parseProcStats parses multiple lines accurately`() {
        val sampleStats = """
            100 (init) S 0 0 0 0 0 0 0 0 0 0 10 20 0 0 20 0 1 0 1000 10000 100
            200 (system_server) S 1 0 0 0 0 0 0 0 0 0 500 300 0 0 20 0 50 0 2000 20000 500
        """.trimIndent()

        val map = SystemTaskInspector.parseProcStats(sampleStats)
        assertEquals(2, map.size)
        assertEquals(10L, map[100]?.utimeTicks)
        assertEquals(20L, map[100]?.stimeTicks)
        assertEquals(30L, map[100]?.totalCpuTicks)
        assertEquals(1000L, map[100]?.starttimeTicks)

        assertEquals(500L, map[200]?.utimeTicks)
        assertEquals(300L, map[200]?.stimeTicks)
        assertEquals(800L, map[200]?.totalCpuTicks)
        assertEquals(2000L, map[200]?.starttimeTicks)
    }

    @Test
    fun `calculatePowerImpact cleanly differentiates null cpu zero cpu and active cpu without inventing zero for null`() {
        // Scenario 1: Telemetry unavailable (cpuPercent = null) -> impact and mw must be null (never converted to 0.0 or MINIMAL)
        val (impactNullFg, mwNullFg) = SystemTaskInspector.calculatePowerImpact(null, isForeground = true, isSystem = false)
        assertNull(impactNullFg)
        assertNull(mwNullFg)

        val (impactNullBg, mwNullBg) = SystemTaskInspector.calculatePowerImpact(null, isForeground = false, isSystem = false)
        assertNull(impactNullBg)
        assertNull(mwNullBg)

        // Scenario 2: Valid measured 0.0% CPU (process idle/sleeping) -> MINIMAL impact and 0.0 mW
        val (impactZeroFg, mwZeroFg) = SystemTaskInspector.calculatePowerImpact(0.0, isForeground = true, isSystem = false)
        assertEquals(PowerImpactLevel.MINIMAL, impactZeroFg)
        assertEquals(0.0, mwZeroFg ?: -1.0, 0.001)

        val (impactZeroBg, mwZeroBg) = SystemTaskInspector.calculatePowerImpact(0.0, isForeground = false, isSystem = false)
        assertEquals(PowerImpactLevel.MINIMAL, impactZeroBg)
        assertEquals(0.0, mwZeroBg ?: -1.0, 0.001)

        // Scenario 3: Measured active CPU values -> calculate appropriate level and mW
        val (impactFgHigh, mwFgHigh) = SystemTaskInspector.calculatePowerImpact(15.0, isForeground = true, isSystem = false)
        assertEquals(PowerImpactLevel.HIGH, impactFgHigh)
        assertEquals((15.0 / 100.0) * 2800.0, mwFgHigh ?: -1.0, 0.001)

        val (impactBgVeryHigh, mwBgVeryHigh) = SystemTaskInspector.calculatePowerImpact(9.0, isForeground = false, isSystem = false)
        assertEquals(PowerImpactLevel.VERY_HIGH, impactBgVeryHigh)
        assertEquals((9.0 / 100.0) * 2800.0, mwBgVeryHigh ?: -1.0, 0.001)
    }

    @Test
    fun `parsePsLines cleans up dead PIDs from processCpuHistory`() {
        SystemTaskInspector.processCpuHistory.clear()

        val sample1 = """
            PID USER S RSS %MEM TIME ARGS
            5001 u0_a90 S 10000 1.0 0:01.00 com.temp1
            5002 u0_a91 S 10000 1.0 0:01.00 com.temp2
        """.trimIndent()

        SystemTaskInspector.parsePsLines(sample1, coresCount = 2, nowRealtime = 10000L)
        assertEquals(2, SystemTaskInspector.processCpuHistory.size)

        val sample2 = """
            PID USER S RSS %MEM TIME ARGS
            5002 u0_a91 S 10000 1.0 0:02.00 com.temp2
        """.trimIndent()

        SystemTaskInspector.parsePsLines(sample2, coresCount = 2, nowRealtime = 11000L)
        assertEquals(1, SystemTaskInspector.processCpuHistory.size)
        assertTrue(SystemTaskInspector.processCpuHistory.keys.any { it.pid == 5002 })
        assertFalse(SystemTaskInspector.processCpuHistory.keys.any { it.pid == 5001 })
    }
}
