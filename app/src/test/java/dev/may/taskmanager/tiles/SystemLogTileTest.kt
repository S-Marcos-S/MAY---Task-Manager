// SPDX-License-Identifier: GPL-3.0-or-later
package dev.may.taskmanager.tiles

import dev.may.taskmanager.root.AppLogCaptureService
import dev.may.taskmanager.root.log.ActiveSessionInfo
import dev.may.taskmanager.root.log.RecordingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemLogTileTest {

    @Test
    fun `recording session states correctly determine active tile state`() {
        val idleSession = ActiveSessionInfo(state = RecordingState.IDLE)
        val recordingSession = ActiveSessionInfo(state = RecordingState.RECORDING)
        val pausedSession = ActiveSessionInfo(state = RecordingState.PAUSED)

        assertFalse(idleSession.state != RecordingState.IDLE)
        assertTrue(recordingSession.state != RecordingState.IDLE)
        assertTrue(pausedSession.state != RecordingState.IDLE)
    }

    @Test
    fun `system log session with empty packages targets global logs`() {
        val emptyPkgs = emptySet<String>()
        val appNames = listOf("Sistema")

        val session = ActiveSessionInfo(
            state = RecordingState.RECORDING,
            targetPackage = emptyPkgs.firstOrNull(),
            targetAppName = appNames.firstOrNull(),
            targetPackages = emptyPkgs,
            targetAppNames = appNames,
        )

        assertEquals(null, session.targetPackage)
        assertTrue(session.targetPackages.isEmpty())
        assertEquals("Sistema", session.targetAppName)
        assertTrue(session.state != RecordingState.IDLE)
    }

    @Test
    fun `action constants for capture service are consistent`() {
        assertEquals("dev.may.taskmanager.action.START_LOG_CAPTURE", AppLogCaptureService.ACTION_START_CAPTURE)
        assertEquals("dev.may.taskmanager.action.SAVE_LOG", AppLogCaptureService.ACTION_SAVE_LOG)
        assertEquals("dev.may.taskmanager.action.PAUSE_RESUME_CAPTURE", AppLogCaptureService.ACTION_PAUSE_RESUME)
        assertEquals("dev.may.taskmanager.action.CANCEL_LOG_CAPTURE", AppLogCaptureService.ACTION_CANCEL_CAPTURE)
    }
}
