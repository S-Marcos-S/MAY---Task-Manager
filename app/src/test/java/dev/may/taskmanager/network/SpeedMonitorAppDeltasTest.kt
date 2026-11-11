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
    fun calculateActiveAppsFromDeltas_scalesSpeedToConserveTotalBandwidthAndFlagsDivergence() {
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
        // Lei da Conservação Física: a velocidade atribuída ao aplicativo não pode
        // exceder a velocidade total física observada na interface de rede no mesmo intervalo.
        // O valor é reconciliado proporcionalmente e sinalizado como agregado (hasDivergence = true).
        assertEquals(1_000_000.0, result[0].speedBytesSec, 0.001)
        assertTrue(result[0].hasDivergence)
        assertEquals(ConfidenceLevel.LOW, result[0].confidence)
    }

    @Test
    fun calculateActiveAppsFromDeltas_mandatoryTest1_comparesOverallSpeedWithSumOfAppSpeeds() {
        // Cenário exato reportado:
        // Download geral: 1,2 MB/s (1.200.000 B/s)
        // Upload geral: 35 KB/s (35.000 B/s)
        // Lote bruto do NetworkStatsManager: Telegram 2,8 MB/s RX, Termux 448 KB/s RX
        val totalDownSpeed = 1_200_000.0
        val totalUpSpeed = 35_000.0

        val prevRx = mapOf(10100 to 0L, 10200 to 0L)
        val curRx = mapOf(10100 to 2_800_000L, 10200 to 448_000L) // Telegram = 2.8M, Termux = 448K
        val prevTx = mapOf(10100 to 0L, 10200 to 0L)
        val curTx = mapOf(10100 to 0L, 10200 to 0L)

        val names = mapOf(10100 to "Telegram", 10200 to "Termux")

        val result = SpeedMonitorService.calculateActiveAppsFromDeltas(
            currentRxTotals = curRx,
            previousRxTotals = prevRx,
            currentTxTotals = curTx,
            previousTxTotals = prevTx,
            deltaTimeSec = 1.0,
            minBytesPerSec = 10_000.0,
            totalDownSpeed = totalDownSpeed,
            totalUpSpeed = totalUpSpeed,
            resolveName = { names[it] }
        )

        assertEquals(2, result.size)
        assertEquals("Telegram", result[0].appName)
        assertEquals("Termux", result[1].appName)

        // Soma das velocidades dos aplicativos DEVE ser compatível e igual ao download geral medido
        val sumAppDown = result.sumOf { it.rxSpeedBytesSec }
        val sumAppTotal = result.sumOf { it.speedBytesSec }
        assertEquals(totalDownSpeed, sumAppDown, 0.01)
        assertEquals(totalDownSpeed, sumAppTotal, 0.01)

        // Telegram nunca pode ser 2.8 MB/s se o download geral é 1.2 MB/s
        val expectedTelegram = (2_800_000.0 / (2_800_000.0 + 448_000.0)) * totalDownSpeed
        val expectedTermux = (448_000.0 / (2_800_000.0 + 448_000.0)) * totalDownSpeed
        assertEquals(expectedTelegram, result[0].rxSpeedBytesSec, 0.01)
        assertEquals(expectedTermux, result[1].rxSpeedBytesSec, 0.01)
        assertTrue(result[0].hasDivergence)
        assertTrue(result[1].hasDivergence)
    }

    @Test
    fun calculateActiveAppsFromDeltas_mandatoryTest2_simultaneousDownloadAndUploadAcrossApps() {
        // App A baixando a 1 MB/s, App B enviando a 500 KB/s
        val totalDownSpeed = 1_000_000.0
        val totalUpSpeed = 500_000.0

        val prevRx = mapOf(10100 to 0L, 10200 to 0L)
        val curRx = mapOf(10100 to 1_000_000L, 10200 to 0L)
        val prevTx = mapOf(10100 to 0L, 10200 to 0L)
        val curTx = mapOf(10100 to 0L, 10200 to 500_000L)

        val names = mapOf(10100 to "DownloaderApp", 10200 to "UploaderApp")

        val result = SpeedMonitorService.calculateActiveAppsFromDeltas(
            currentRxTotals = curRx,
            previousRxTotals = prevRx,
            currentTxTotals = curTx,
            previousTxTotals = prevTx,
            deltaTimeSec = 1.0,
            minBytesPerSec = 10_000.0,
            totalDownSpeed = totalDownSpeed,
            totalUpSpeed = totalUpSpeed,
            resolveName = { names[it] }
        )

        assertEquals(2, result.size)
        val downApp = result.first { it.appName == "DownloaderApp" }
        val upApp = result.first { it.appName == "UploaderApp" }

        assertEquals(1_000_000.0, downApp.rxSpeedBytesSec, 0.01)
        assertEquals(0.0, downApp.txSpeedBytesSec, 0.01)
        assertEquals(1_000_000.0, downApp.speedBytesSec, 0.01)
        assertFalse(downApp.hasDivergence)

        assertEquals(0.0, upApp.rxSpeedBytesSec, 0.01)
        assertEquals(500_000.0, upApp.txSpeedBytesSec, 0.01)
        assertEquals(500_000.0, upApp.speedBytesSec, 0.01)
        assertFalse(upApp.hasDivergence)
    }

    @Test
    fun calculateActiveAppsFromDeltas_mandatoryTest3_accumulatedStatsNotPresentedAsInstantSpeedWhenIdle() {
        // Dispositivo ocioso (0 B/s geral), mas NetworkStatsManager despejou lote atrasado de 5 MB
        val totalDownSpeed = 0.0
        val totalUpSpeed = 0.0

        val prevRx = mapOf(10100 to 0L)
        val curRx = mapOf(10100 to 5_000_000L)
        val prevTx = mapOf(10100 to 0L)
        val curTx = mapOf(10100 to 0L)

        val result = SpeedMonitorService.calculateActiveAppsFromDeltas(
            currentRxTotals = curRx,
            previousRxTotals = prevRx,
            currentTxTotals = curTx,
            previousTxTotals = prevTx,
            deltaTimeSec = 1.0,
            minBytesPerSec = 10_000.0,
            totalDownSpeed = totalDownSpeed,
            totalUpSpeed = totalUpSpeed,
            resolveName = { "StaleApp" }
        )

        // Estatísticas acumuladas passadas NÃO devem ser mostradas como velocidade instantânea quando ocioso
        assertTrue(result.isEmpty())
    }

    @Test
    fun calculateActiveAppsFromDeltas_mandatoryTest4_sharedUidAndMultipleProcesses() {
        // UID compartilhado por múltiplos processos/pacotes
        val prev = mapOf(10050 to 0L)
        val cur = mapOf(10050 to 800_000L)

        val result = SpeedMonitorService.calculateActiveAppsFromDeltas(
            currentTotals = cur,
            previousTotals = prev,
            deltaTimeSec = 1.0,
            minBytesPerSec = 10_000.0,
            totalDeviceSpeed = 1_000_000.0,
            resolveName = { "Google Play Services (+2 apps)" }
        )

        assertEquals(1, result.size)
        assertEquals("Google Play Services (+2 apps)", result[0].appName)
        assertEquals(800_000.0, result[0].speedBytesSec, 0.01)
        assertFalse(result[0].hasDivergence)
    }

    @Test
    fun calculateActiveAppsFromDeltas_mandatoryTest5_validatesUnitConsistency() {
        // Validação estrita de unidades: B/s, KB/s, MB/s
        val prev = mapOf(10100 to 0L, 10200 to 0L, 10300 to 0L)
        val cur = mapOf(
            10100 to 500L,             // 500 B/s (abaixo de 1 KB/s -> excluído pelo threshold de 1024)
            10200 to 512_000L,         // 500 KB/s
            10300 to 2_621_440L        // 2.5 MB/s
        )

        val names = mapOf(10100 to "ByteApp", 10200 to "KbApp", 10300 to "MbApp")

        val result = SpeedMonitorService.calculateActiveAppsFromDeltas(
            currentTotals = cur,
            previousTotals = prev,
            deltaTimeSec = 1.0,
            minBytesPerSec = 1024.0, // 1 KB/s
            totalDeviceSpeed = 5_000_000.0,
            resolveName = { names[it] }
        )

        assertEquals(2, result.size)
        assertEquals("MbApp", result[0].appName)
        assertEquals(2_621_440.0, result[0].speedBytesSec, 0.01)

        assertEquals("KbApp", result[1].appName)
        assertEquals(512_000.0, result[1].speedBytesSec, 0.01)
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

    @Test
    fun telemetryValidator_flagsStaleIntervalAccurately() {
        val (rxSample, txSample) = TelemetryValidator.computeGlobalNetworkSpeed(
            currentRx = 2_000_000L,
            currentTx = 2_000_000L,
            prevRx = 1_000_000L,
            prevTx = 1_000_000L,
            deltaNanos = 45_000_000_000L, // 45 segundos (> 30s)
            isSupported = true,
            rootAvailable = false,
        )

        assertEquals(ConfidenceLevel.LOW, rxSample.confidence)
        assertEquals(dev.may.taskmanager.telemetry.TelemetryNature.AGGREGATED_STATISTIC, rxSample.nature)
        assertEquals(ConfidenceLevel.LOW, txSample.confidence)
    }

    @Test
    fun calculateActiveAppsFromDeltas_zeroTotalDeviceSpeedInSingleMapOverload_returnsEmptyList() {
        val prev = mapOf(10100 to 0L)
        val cur = mapOf(10100 to 10_000_000L) // 10 MB atrasados

        val result = SpeedMonitorService.calculateActiveAppsFromDeltas(
            currentTotals = cur,
            previousTotals = prev,
            deltaTimeSec = 1.0,
            minBytesPerSec = 10_000.0,
            totalDeviceSpeed = 0.0, // Velocidade real comprovada de 0 B/s
            resolveName = { "StaleDelayedBatch" }
        )

        // Ausência real de tráfego: lotes atrasados com velocidade total 0 não devem ser exibidos
        assertTrue(result.isEmpty())
    }

    @Test
    fun calculateActiveAppsFromDeltas_temporaryReadFailure_distinguishedFromRealZeroTraffic() {
        // Falha temporária de leitura (totalDownSpeed = null, totalUpSpeed = null)
        val prevRx = mapOf(10100 to 0L)
        val curRx = mapOf(10100 to 500_000L)
        val prevTx = mapOf(10100 to 0L)
        val curTx = mapOf(10100 to 0L)

        val result = SpeedMonitorService.calculateActiveAppsFromDeltas(
            currentRxTotals = curRx,
            previousRxTotals = prevRx,
            currentTxTotals = curTx,
            previousTxTotals = prevTx,
            deltaTimeSec = 1.0,
            minBytesPerSec = 10_000.0,
            totalDownSpeed = null, // Falha temporária de leitura
            totalUpSpeed = null,   // Falha temporária de leitura
            resolveName = { "AppInReadFailure" }
        )

        // Deve retornar o app mas explicitamente sinalizado com LOW confidence e hasDivergence = true
        assertEquals(1, result.size)
        assertEquals("AppInReadFailure", result[0].appName)
        assertEquals(500_000.0, result[0].speedBytesSec, 0.01)
        assertTrue(result[0].hasDivergence)
        assertEquals(ConfidenceLevel.LOW, result[0].confidence)
    }

    @Test
    fun calculateActiveAppsFromDeltas_partialZeroTraffic_downZeroUpActive() {
        // Dispositivo com 0 B/s de download real e 500 KB/s de upload real
        val totalDownSpeed = 0.0
        val totalUpSpeed = 500_000.0

        val prevRx = mapOf(10100 to 0L, 10200 to 0L)
        val curRx = mapOf(10100 to 2_000_000L, 10200 to 0L) // App A tem lote atrasado de 2 MB de download
        val prevTx = mapOf(10100 to 0L, 10200 to 0L)
        val curTx = mapOf(10100 to 0L, 10200 to 500_000L)   // App B enviando a 500 KB/s

        val names = mapOf(10100 to "StaleDownloader", 10200 to "ActiveUploader")

        val result = SpeedMonitorService.calculateActiveAppsFromDeltas(
            currentRxTotals = curRx,
            previousRxTotals = prevRx,
            currentTxTotals = curTx,
            previousTxTotals = prevTx,
            deltaTimeSec = 1.0,
            minBytesPerSec = 10_000.0,
            totalDownSpeed = totalDownSpeed,
            totalUpSpeed = totalUpSpeed,
            resolveName = { names[it] }
        )

        // StaleDownloader teve 0 B/s de download ajustado (pois download total é 0), ficando abaixo do threshold
        // ActiveUploader tem 500 KB/s de upload legítimo
        assertEquals(1, result.size)
        assertEquals("ActiveUploader", result[0].appName)
        assertEquals(500_000.0, result[0].speedBytesSec, 0.01)
        assertEquals(0.0, result[0].rxSpeedBytesSec, 0.01)
        assertEquals(500_000.0, result[0].txSpeedBytesSec, 0.01)
    }
}
