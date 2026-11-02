// SPDX-License-Identifier: GPL-3.0-or-later
package dev.may.taskmanager.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import dev.may.taskmanager.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MayAccessibilityService : AccessibilityService() {

    companion object {
        private val _isServiceActive = MutableStateFlow(false)
        val isServiceActive: StateFlow<Boolean> = _isServiceActive.asStateFlow()

        const val PREFS_COMMANDS = "may_commands_prefs"
        const val KEY_VOLUME_SHORTCUT_ENABLED = "cmd_volume_keys_enabled"
    }

    private var isVolumeUpPressed = false
    private var isVolumeDownPressed = false
    private var lastTriggerTimeMs = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        _isServiceActive.value = true
    }

    override fun onDestroy() {
        super.onDestroy()
        _isServiceActive.value = false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Key events filtering only
    }

    override fun onInterrupt() {
        _isServiceActive.value = false
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val prefs = getSharedPreferences(PREFS_COMMANDS, Context.MODE_PRIVATE)
        val isEnabled = prefs.getBoolean(KEY_VOLUME_SHORTCUT_ENABLED, false)

        if (!isEnabled) {
            return super.onKeyEvent(event)
        }

        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                isVolumeUpPressed = event.action == KeyEvent.ACTION_DOWN
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                isVolumeDownPressed = event.action == KeyEvent.ACTION_DOWN
            }
        }

        if (isVolumeUpPressed && isVolumeDownPressed) {
            val now = System.currentTimeMillis()
            if (now - lastTriggerTimeMs > 1500L) {
                lastTriggerTimeMs = now
                isVolumeUpPressed = false
                isVolumeDownPressed = false
                launchMay()
                return true
            }
        }

        return super.onKeyEvent(event)
    }

    private fun launchMay() {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
