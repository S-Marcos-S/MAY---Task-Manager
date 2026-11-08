// SPDX-License-Identifier: GPL-3.0-or-later
package dev.may.taskmanager.tiles

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import dev.may.taskmanager.root.RootBatteryStatsCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Inet4Address

object DevTilesController {

    const val KEY_DEV_MODE = Settings.Global.DEVELOPMENT_SETTINGS_ENABLED
    const val KEY_USB_DEBUG = Settings.Global.ADB_ENABLED
    const val KEY_WIFI_DEBUG = "adb_wifi_enabled"
    const val KEY_WIFI_DEBUG_PORT = "adb_wifi_port"

    fun isDevModeEnabled(context: Context): Boolean {
        return Settings.Global.getInt(context.contentResolver, KEY_DEV_MODE, 0) != 0
    }

    fun isUsbDebugEnabled(context: Context): Boolean {
        return Settings.Global.getInt(context.contentResolver, KEY_USB_DEBUG, 0) != 0
    }

    fun isWifiDebugEnabled(context: Context): Boolean {
        return Settings.Global.getInt(context.contentResolver, KEY_WIFI_DEBUG, 0) != 0
    }

    fun isSettingEnabled(context: Context, settingKey: String): Boolean {
        return Settings.Global.getInt(context.contentResolver, settingKey, 0) != 0
    }

    fun hasWriteSecureSettings(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_SECURE_SETTINGS
        ) == PackageManager.PERMISSION_GRANTED
    }

    suspend fun setSettingState(context: Context, settingKey: String, enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        val value = if (enabled) 1 else 0
        try {
            val success = Settings.Global.putInt(context.contentResolver, settingKey, value)
            if (success) {
                notifyTilesChanged(context)
                return@withContext true
            }
        } catch (_: SecurityException) {
            // Fall through to root fallback
        }

        if (RootBatteryStatsCollector.isRootAvailable()) {
            val res = RootBatteryStatsCollector.runAsRoot("settings put global $settingKey $value")
            RootBatteryStatsCollector.runAsRoot("pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS")
            notifyTilesChanged(context)
            return@withContext res != null
        }

        false
    }

    suspend fun toggleSetting(context: Context, settingKey: String): Boolean {
        val current = isSettingEnabled(context, settingKey)
        return setSettingState(context, settingKey, !current)
    }

    fun getIpAddress(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return "N/A"
        val network = cm.activeNetwork ?: return "N/A"
        val lp = cm.getLinkProperties(network) ?: return "N/A"
        for (addr in lp.linkAddresses) {
            val address = addr.address
            if (address is Inet4Address && !address.isLoopbackAddress) {
                return address.hostAddress ?: "N/A"
            }
        }
        return "N/A"
    }

    fun getAdbWifiPort(context: Context): String {
        return try {
            val port = Settings.Global.getInt(context.contentResolver, KEY_WIFI_DEBUG_PORT, 0)
            if (port == 0) "5555" else port.toString()
        } catch (_: Exception) {
            "5555"
        }
    }

    fun notifyTilesChanged(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                TileService.requestListeningState(context, ComponentName(context, DevModeTileService::class.java))
                TileService.requestListeningState(context, ComponentName(context, UsbDebugTileService::class.java))
                TileService.requestListeningState(context, ComponentName(context, WifiDebugTileService::class.java))
            } catch (_: Exception) {}
        }
    }
}
