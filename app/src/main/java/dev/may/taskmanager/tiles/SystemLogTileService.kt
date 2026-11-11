// SPDX-License-Identifier: GPL-3.0-or-later
package dev.may.taskmanager.tiles

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import dev.may.taskmanager.R
import dev.may.taskmanager.root.AppLogCaptureService
import dev.may.taskmanager.root.log.RecordingState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class SystemLogTileService : TileService() {

    companion object {
        fun requestTileUpdate(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    TileService.requestListeningState(
                        context,
                        ComponentName(context, SystemLogTileService::class.java)
                    )
                } catch (_: Throwable) {}
            }
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var observeJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
        startObservingState()
    }

    override fun onStopListening() {
        super.onStopListening()
        stopObservingState()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    override fun onClick() {
        super.onClick()
        val isRecording = isSessionActive()
        if (isRecording) {
            AppLogCaptureService.saveLog(this)
            Toast.makeText(
                this,
                getString(R.string.toast_system_log_recording_stopped),
                Toast.LENGTH_SHORT
            ).show()
            updateTileDisplay(isActive = false)
        } else {
            val sysLabel = getString(R.string.tools_system_logs_label)
            AppLogCaptureService.startCapture(
                context = this,
                packageName = null,
                appName = sysLabel
            )
            Toast.makeText(
                this,
                getString(R.string.toast_system_log_recording_started),
                Toast.LENGTH_SHORT
            ).show()
            updateTileDisplay(isActive = true)
        }
    }

    private fun isSessionActive(): Boolean {
        val recordingsManager = AppLogCaptureService.getRecordingsManager(this)
        return recordingsManager.session.value.state != RecordingState.IDLE
    }

    private fun updateTileState() {
        val active = isSessionActive()
        updateTileDisplay(active)
    }

    private fun updateTileDisplay(isActive: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (isActive) {
                getString(R.string.tile_system_log_recording)
            } else {
                getString(R.string.tile_system_log_idle)
            }
        }
        tile.updateTile()
    }

    private fun startObservingState() {
        observeJob?.cancel()
        val recordingsManager = AppLogCaptureService.getRecordingsManager(this)
        observeJob = serviceScope.launch {
            recordingsManager.session.collect { sessionInfo ->
                val isActive = sessionInfo.state != RecordingState.IDLE
                updateTileDisplay(isActive)
            }
        }
    }

    private fun stopObservingState() {
        observeJob?.cancel()
        observeJob = null
    }
}
