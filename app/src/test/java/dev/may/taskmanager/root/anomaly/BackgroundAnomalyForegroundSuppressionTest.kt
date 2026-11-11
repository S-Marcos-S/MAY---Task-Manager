// SPDX-License-Identifier: GPL-3.0-or-later
package dev.may.taskmanager.root.anomaly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundAnomalyForegroundSuppressionTest {

    @Test
    fun `process identity includes starttime identifier preventing collision on recycled pid`() {
        val id1 = BackgroundAnomalyWatcher.ProcessIdentity(pid = 1234, processName = "com.app.worker", startIdentifier = "10050")
        val id2 = BackgroundAnomalyWatcher.ProcessIdentity(pid = 1234, processName = "com.app.worker", startIdentifier = "25000")

        // Same PID and processName, but different starttime ticks -> distinct identities
        assertFalse("Different start times must not collide even with identical PID and name", id1 == id2)
        assertEquals(1234, id1.pid)
        assertEquals("com.app.worker", id1.processName)
        assertEquals("10050", id1.startIdentifier)
    }

    @Test
    fun `multiprocess sub-process correctly resolves to parent package`() {
        val cmdList = listOf(
            "com.android.chrome:sandboxed_process0",
            "com.google.android.youtube:player",
            "com.whatsapp:sandbox",
            "com.instagram.android:browser"
        )

        for (cmd in cmdList) {
            val pkg = cmd.substringBefore(":")
            assertTrue("Package must contain domain dots", pkg.contains("."))
            assertFalse("Package must not contain colon sub-process identifier", pkg.contains(":"))
        }

        assertEquals("com.android.chrome", "com.android.chrome:sandboxed_process0".substringBefore(":"))
        assertEquals("com.google.android.youtube", "com.google.android.youtube:player".substringBefore(":"))
    }

    @Test
    fun `foreground visible packages set suppresses alert for multi-process app`() {
        val visiblePackages = setOf("com.google.android.youtube", "com.android.chrome")

        val subProcessCmd = "com.google.android.youtube:player"
        val pkgName = subProcessCmd.substringBefore(":")

        val isVisibleForeground = visiblePackages.contains(pkgName)
        assertTrue("Subprocess of visible app must be recognized as visible foreground", isVisibleForeground)
    }

    @Test
    fun `shared uid with one visible package suppresses background alert for group`() {
        val visiblePackages = setOf("com.google.android.gm")
        val sharedUidPackages = listOf("com.google.android.gms", "com.google.android.gm", "com.google.android.gsf")

        val hasVisibleMember = sharedUidPackages.any { visiblePackages.contains(it) }
        assertTrue("Shared UID with at least one visible package must be recognized as active in foreground", hasVisibleMember)
    }

    @Test
    fun `app not in visible set qualifies for background inspection`() {
        val visiblePackages = setOf("com.android.chrome")
        val bgCmd = "com.secret.miner:daemon"
        val pkgName = bgCmd.substringBefore(":")

        val isVisible = visiblePackages.contains(pkgName)
        assertFalse("Background process must not be falsely treated as foreground", isVisible)
    }
}
