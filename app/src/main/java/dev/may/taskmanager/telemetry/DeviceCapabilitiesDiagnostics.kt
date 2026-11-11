// SPDX-License-Identifier: GPL-3.0-or-later
package dev.may.taskmanager.telemetry

import android.app.AppOpsManager
import android.content.Context
import android.net.TrafficStats
import android.os.Build
import android.os.Process
import dev.may.taskmanager.R
import dev.may.taskmanager.root.AppRootInspector

enum class CapabilityState {
    FULL_ACCESS,
    LIMITED_ACCESS,
    UNAVAILABLE,
    PERMISSION_REQUIRED,
}

data class CapabilityReport(
    val id: String,
    val title: String,
    val description: String,
    val state: CapabilityState,
    val isRootGranted: Boolean,
    val isSpecialAccessGranted: Boolean,
    val isSystemSupported: Boolean,
    val technicalDetails: String,
)

object DeviceCapabilitiesDiagnostics {

    fun hasUsageStatsAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun inspectNetworkTelemetry(context: Context): CapabilityReport {
        val tsRx = TrafficStats.getTotalRxBytes()
        val isSupported = tsRx != TrafficStats.UNSUPPORTED.toLong()
        val hasUsage = hasUsageStatsAccess(context)
        val isRoot = AppRootInspector.isRootAvailable()

        val state = when {
            isSupported && hasUsage -> CapabilityState.FULL_ACCESS
            isSupported -> CapabilityState.LIMITED_ACCESS
            else -> CapabilityState.UNAVAILABLE
        }

        val details = when {
            state == CapabilityState.FULL_ACCESS -> "TrafficStats ativo para trafego global e NetworkStatsManager autorizado para consumo por app"
            state == CapabilityState.LIMITED_ACCESS -> "TrafficStats ativo para velocidade global; requer PACKAGE_USAGE_STATS para listar consumo por app"
            else -> "TrafficStats nao suportado pelo kernel ou interface de rede atual"
        }

        return CapabilityReport(
            id = "network",
            title = context.getString(R.string.diag_cap_network_title),
            description = context.getString(R.string.diag_cap_network_desc),
            state = state,
            isRootGranted = isRoot,
            isSpecialAccessGranted = hasUsage,
            isSystemSupported = isSupported,
            technicalDetails = details,
        )
    }

    fun probeSuCommand(cmd: String): Boolean {
        if (!AppRootInspector.isRootAvailable()) return false
        return runCatching {
            val process = ProcessBuilder("su", "-c", cmd)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            exitCode == 0 && output.isNotBlank()
        }.getOrDefault(false)
    }

    fun inspectProcessCpuTelemetry(context: Context): CapabilityReport {
        val isRoot = AppRootInspector.isRootAvailable()
        val hasProcAccess = isRoot && probeSuCommand("cat /proc/1/stat 2>/dev/null")
        val state = when {
            hasProcAccess -> CapabilityState.FULL_ACCESS
            isRoot -> CapabilityState.LIMITED_ACCESS
            else -> CapabilityState.LIMITED_ACCESS
        }
        val details = when {
            hasProcAccess -> "Acesso Root ativo e verificado: telemetria de CPU individual via ps e proc/stat com medicao diferencial direta"
            isRoot -> "Root detectado, mas leitura de /proc/1/stat bloqueada por politica do kernel ou permissao negada"
            else -> "Sem acesso Root: SELinux restringe a leitura de CPU de outros processos; ActivityManager fornece apenas processos visiveis"
        }

        return CapabilityReport(
            id = "cpu",
            title = context.getString(R.string.diag_cap_cpu_title),
            description = context.getString(R.string.diag_cap_cpu_desc),
            state = state,
            isRootGranted = hasProcAccess,
            isSpecialAccessGranted = false,
            isSystemSupported = true,
            technicalDetails = details,
        )
    }

    fun inspectMemoryTelemetry(context: Context): CapabilityReport {
        val isRoot = AppRootInspector.isRootAvailable()
        val hasMeminfo = !isRoot || probeSuCommand("cat /proc/meminfo 2>/dev/null")
        val state = if (isRoot && !hasMeminfo) CapabilityState.LIMITED_ACCESS else CapabilityState.FULL_ACCESS
        val details = when {
            isRoot && hasMeminfo -> "Acesso total verificado a RSS, PSS e proc/meminfo com analise de tendencia temporal"
            isRoot -> "Root detectado, mas leitura de /proc/meminfo bloqueada; operando via ActivityManager"
            else -> "Acesso a PSS via ActivityManager e memoria global via MemoryInfo"
        }

        return CapabilityReport(
            id = "ram",
            title = context.getString(R.string.diag_cap_ram_title),
            description = context.getString(R.string.diag_cap_ram_desc),
            state = state,
            isRootGranted = isRoot && hasMeminfo,
            isSpecialAccessGranted = false,
            isSystemSupported = true,
            technicalDetails = details,
        )
    }

    fun inspectBatteryTelemetry(context: Context): CapabilityReport {
        val isRoot = AppRootInspector.isRootAvailable()
        val hasUsage = hasUsageStatsAccess(context)
        val hasPowerDumpsys = isRoot && probeSuCommand("dumpsys power -h 2>/dev/null || dumpsys power 2>/dev/null | head -n 5")

        val state = when {
            hasPowerDumpsys -> CapabilityState.FULL_ACCESS
            isRoot -> CapabilityState.LIMITED_ACCESS
            hasUsage -> CapabilityState.LIMITED_ACCESS
            else -> CapabilityState.PERMISSION_REQUIRED
        }

        val details = when {
            hasPowerDumpsys -> "Acesso Root ativo e verificado: dumpsys power disponivel com rastreamento de wakelocks"
            isRoot -> "Root detectado, mas comando dumpsys power retornou vazio ou falhou"
            hasUsage -> "UsageStats ativo para eventos em primeiro plano; wakelocks de baixo nivel requerem Root ou ADB"
            else -> "Acesso basico ao BatteryManager; requer acesso de uso ou Root para analise aprofundada de repouso"
        }

        return CapabilityReport(
            id = "battery",
            title = context.getString(R.string.diag_cap_battery_title),
            description = context.getString(R.string.diag_cap_battery_desc),
            state = state,
            isRootGranted = hasPowerDumpsys,
            isSpecialAccessGranted = hasUsage,
            isSystemSupported = true,
            technicalDetails = details,
        )
    }

    fun getFullDiagnosticReport(context: Context): List<CapabilityReport> {
        return listOf(
            inspectNetworkTelemetry(context),
            inspectProcessCpuTelemetry(context),
            inspectMemoryTelemetry(context),
            inspectBatteryTelemetry(context),
        )
    }
}
