// SPDX-License-Identifier: GPL-3.0-or-later
package dev.may.taskmanager.telemetry

/**
 * Validador e classificador tecnico de telemetria do MAY Task Manager.
 * Responsavel por garantir que estimativas ou falhas nunca sejam exibidas
 * como medicoes comprovadas.
 */
object TelemetryValidator {

    enum class CounterState {
        VALID,
        RESET_OR_REBOOT,
        NEGATIVE_OR_UNSUPPORTED,
        SAME_SAMPLE,
    }

    /**
     * Avalia o estado diferencial de um contador acumulativo de bytes (ex: TrafficStats).
     */
    fun evaluateCounter(current: Long, previous: Long): CounterState {
        if (current < 0L) return CounterState.NEGATIVE_OR_UNSUPPORTED
        if (previous < 0L) return CounterState.NEGATIVE_OR_UNSUPPORTED
        if (previous == 0L) return CounterState.VALID // Primeiro ponto de calibracao
        if (current < previous) return CounterState.RESET_OR_REBOOT
        if (current == previous) return CounterState.SAME_SAMPLE
        return CounterState.VALID
    }

    /**
     * Calcula velocidade de rede com base em relogio monotonico em nanosegundos.
     * Retorna TelemetrySample categorizada com confianca correspondente.
     */
    fun computeGlobalNetworkSpeed(
        currentRx: Long,
        currentTx: Long,
        prevRx: Long,
        prevTx: Long,
        deltaNanos: Long,
        isSupported: Boolean,
        rootAvailable: Boolean,
        currentTimeMs: Long = System.currentTimeMillis(),
    ): Pair<TelemetrySample, TelemetrySample> {
        val deltaSec = (deltaNanos.toDouble() / 1_000_000_000.0).coerceAtLeast(0.001)

        val rxState = evaluateCounter(currentRx, prevRx)
        val txState = evaluateCounter(currentTx, prevTx)

        val rxDelta = if (rxState == CounterState.VALID && prevRx > 0L) currentRx - prevRx else 0L
        val txDelta = if (txState == CounterState.VALID && prevTx > 0L) currentTx - prevTx else 0L

        val rxSpeed = if (rxState == CounterState.VALID) rxDelta / deltaSec else 0.0
        val txSpeed = if (txState == CounterState.VALID) txDelta / deltaSec else 0.0

        val (rxConf, rxNature, rxReason) = when {
            !isSupported || rxState == CounterState.NEGATIVE_OR_UNSUPPORTED ->
                Triple(ConfidenceLevel.UNAVAILABLE, TelemetryNature.UNAVAILABLE, "Contador de rede nao suportado ou indisponivel no kernel")
            rxState == CounterState.RESET_OR_REBOOT ->
                Triple(ConfidenceLevel.LOW, TelemetryNature.ESTIMATE, "Reinicializacao de contador detectada (reboot ou interface reset)")
            deltaSec > 30.0 ->
                Triple(ConfidenceLevel.LOW, TelemetryNature.AGGREGATED_STATISTIC, "Intervalo temporal excessivo entre amostras")
            prevRx == 0L ->
                Triple(ConfidenceLevel.MEDIUM, TelemetryNature.DIRECT_MEASUREMENT, "Amostra inicial de calibracao")
            else ->
                Triple(ConfidenceLevel.HIGH, TelemetryNature.DIRECT_MEASUREMENT, "Medicao diferencial monotonica direta")
        }

        val (txConf, txNature, txReason) = when {
            !isSupported || txState == CounterState.NEGATIVE_OR_UNSUPPORTED ->
                Triple(ConfidenceLevel.UNAVAILABLE, TelemetryNature.UNAVAILABLE, "Contador de rede nao suportado ou indisponivel no kernel")
            txState == CounterState.RESET_OR_REBOOT ->
                Triple(ConfidenceLevel.LOW, TelemetryNature.ESTIMATE, "Reinicializacao de contador detectada (reboot ou interface reset)")
            deltaSec > 30.0 ->
                Triple(ConfidenceLevel.LOW, TelemetryNature.AGGREGATED_STATISTIC, "Intervalo temporal excessivo entre amostras")
            prevTx == 0L ->
                Triple(ConfidenceLevel.MEDIUM, TelemetryNature.DIRECT_MEASUREMENT, "Amostra inicial de calibracao")
            else ->
                Triple(ConfidenceLevel.HIGH, TelemetryNature.DIRECT_MEASUREMENT, "Medicao diferencial monotonica direta")
        }

        val rxSample = TelemetrySample(
            packageName = null,
            uid = null,
            metric = "rx_speed",
            value = rxSpeed,
            unit = "B/s",
            source = "TrafficStats.getTotalRxBytes",
            startTime = currentTimeMs - (deltaSec * 1000).toLong(),
            endTime = currentTimeMs,
            confidence = rxConf,
            nature = rxNature,
            isEstimated = rxNature == TelemetryNature.ESTIMATE,
            isComplete = rxConf != ConfidenceLevel.UNAVAILABLE,
            rootAvailable = rootAvailable,
            reason = rxReason,
        )

        val txSample = TelemetrySample(
            packageName = null,
            uid = null,
            metric = "tx_speed",
            value = txSpeed,
            unit = "B/s",
            source = "TrafficStats.getTotalTxBytes",
            startTime = currentTimeMs - (deltaSec * 1000).toLong(),
            endTime = currentTimeMs,
            confidence = txConf,
            nature = txNature,
            isEstimated = txNature == TelemetryNature.ESTIMATE,
            isComplete = txConf != ConfidenceLevel.UNAVAILABLE,
            rootAvailable = rootAvailable,
            reason = txReason,
        )

        return Pair(rxSample, txSample)
    }

    /**
     * Classifica telemetria de CPU coletada para processo individual.
     */
    fun classifyProcessCpu(
        packageName: String?,
        uid: Int?,
        pid: Int?,
        deltaCpuMs: Long,
        deltaWallMs: Long,
        coresCount: Int,
        consecutiveHits: Int,
        rootAvailable: Boolean,
        nowMs: Long = System.currentTimeMillis(),
    ): TelemetrySample {
        val safeCores = coresCount.coerceAtLeast(1)
        val safeWall = deltaWallMs.coerceAtLeast(1L)
        val rawCpu = if (deltaCpuMs <= 0L) 0.0 else ((deltaCpuMs.toDouble() / (safeWall * safeCores)) * 100.0).coerceIn(0.0, 100.0)

        val (confidence, nature, reason) = when {
            !rootAvailable -> Triple(
                ConfidenceLevel.UNAVAILABLE,
                TelemetryNature.UNAVAILABLE,
                "Acesso a estatisticas individuais de CPU por processo restrito pelo SELinux sem Root"
            )
            deltaCpuMs < 0L -> Triple(
                ConfidenceLevel.LOW,
                TelemetryNature.ESTIMATE,
                "Reinicializacao ou reciclagem de PID detectada; baseline reiniciado"
            )
            consecutiveHits >= 2 -> Triple(
                ConfidenceLevel.HIGH,
                TelemetryNature.DIRECT_MEASUREMENT,
                "Confirmacao continuada por amostras consecutivas via tempo de CPU do kernel"
            )
            consecutiveHits == 1 -> Triple(
                ConfidenceLevel.MEDIUM,
                TelemetryNature.DIRECT_MEASUREMENT,
                "Primeira ocorrencia detectada; aguardando confirmacao de persistencia"
            )
            else -> Triple(
                ConfidenceLevel.LOW,
                TelemetryNature.ESTIMATE,
                "Amostra isolada sem confirmacao historica"
            )
        }

        return TelemetrySample(
            packageName = packageName,
            uid = uid,
            metric = "cpu_percent",
            value = rawCpu,
            unit = "%",
            source = if (rootAvailable) "ps/proc_stat" else "unavailable",
            startTime = nowMs - safeWall,
            endTime = nowMs,
            confidence = confidence,
            nature = nature,
            isEstimated = nature == TelemetryNature.ESTIMATE,
            isComplete = confidence != ConfidenceLevel.UNAVAILABLE,
            rootAvailable = rootAvailable,
            reason = reason,
        )
    }
}
