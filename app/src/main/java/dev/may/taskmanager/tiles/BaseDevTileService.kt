// SPDX-License-Identifier: GPL-3.0-or-later
package dev.may.taskmanager.tiles

import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import dev.may.taskmanager.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

abstract class BaseDevTileService(protected val settingKey: String) : TileService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var observer: ContentObserver? = null

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
        registerObserver()
    }

    override fun onStopListening() {
        super.onStopListening()
        unregisterObserver()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    protected fun updateTile() {
        val tile = qsTile ?: return
        val isActive = DevTilesController.isSettingEnabled(this, settingKey)
        tile.state = if (isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        serviceScope.launch {
            val success = DevTilesController.toggleSetting(this@BaseDevTileService, settingKey)
            withContext(Dispatchers.Main) {
                updateTile()
                if (!success) {
                    Toast.makeText(
                        this@BaseDevTileService,
                        getString(R.string.toast_permission_write_secure_settings),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun registerObserver() {
        if (observer == null) {
            try {
                observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        updateTile()
                    }
                }
                contentResolver.registerContentObserver(
                    Settings.Global.getUriFor(settingKey),
                    false,
                    observer!!
                )
            } catch (_: Exception) {}
        }
    }

    private fun unregisterObserver() {
        observer?.let {
            try {
                contentResolver.unregisterContentObserver(it)
            } catch (_: Exception) {}
            observer = null
        }
    }
}

class DevModeTileService : BaseDevTileService(DevTilesController.KEY_DEV_MODE)

class UsbDebugTileService : BaseDevTileService(DevTilesController.KEY_USB_DEBUG)

class WifiDebugTileService : BaseDevTileService(DevTilesController.KEY_WIFI_DEBUG) {
    override fun onClick() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Toast.makeText(
                this,
                getString(R.string.toast_wifi_debug_requires_android_11),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        super.onClick()
    }
}
