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

    fun inspectProcessCpuTelemetry(context: Context): CapabilityReport {
        val isRoot = AppRootInspector.isRootAvailable()
        val state = if (isRoot) CapabilityState.FULL_ACCESS else CapabilityState.LIMITED_ACCESS
        val details = if (isRoot) {
            "Acesso Root ativo: telemetria de CPU individual via ps e proc/stat com medicao diferencial direta"
        } else {
            "Sem acesso Root: SELinux restringe a leitura de CPU de outros processos; apenas memoria PSS e processos visiveis pelo Android"
        }

        return CapabilityReport(
            id = "cpu",
            title = context.getString(R.string.diag_cap_cpu_title),
            description = context.getString(R.string.diag_cap_cpu_desc),
            state = state,
            isRootGranted = isRoot,
            isSpecialAccessGranted = false,
            isSystemSupported = true,
            technicalDetails = details,
        )
    }

    fun inspectMemoryTelemetry(context: Context): CapabilityReport {
        val isRoot = AppRootInspector.isRootAvailable()
        return CapabilityReport(
            id = "ram",
            title = context.getString(R.string.diag_cap_ram_title),
            description = context.getString(R.string.diag_cap_ram_desc),
            state = CapabilityState.FULL_ACCESS,
            isRootGranted = isRoot,
            isSpecialAccessGranted = false,
            isSystemSupported = true,
            technicalDetails = if (isRoot) "Acesso total a RSS, PSS e proc/meminfo com analise de tendencia temporal" else "Acesso a PSS via ActivityManager e memoria global via MemoryInfo",
        )
    }

    fun inspectBatteryTelemetry(context: Context): CapabilityReport {
        val isRoot = AppRootInspector.isRootAvailable()
        val hasUsage = hasUsageStatsAccess(context)
        val state = if (isRoot) CapabilityState.FULL_ACCESS else if (hasUsage) CapabilityState.LIMITED_ACCESS else CapabilityState.PERMISSION_REQUIRED

        val details = when {
            isRoot -> "Acesso Root ativo: dumpsys batterystats e dumpsys power disponiveis com rastreamento completo de wakelocks"
            hasUsage -> "UsageStats ativo para eventos em primeiro plano; wakelocks de baixo nivel requerem Root ou ADB"
            else -> "Acesso basico ao BatteryManager; requer acesso de uso ou Root para analise aprofundada de repouso"
        }

        return CapabilityReport(
            id = "battery",
            title = context.getString(R.string.diag_cap_battery_title),
            description = context.getString(R.string.diag_cap_battery_desc),
            state = state,
            isRootGranted = isRoot,
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
