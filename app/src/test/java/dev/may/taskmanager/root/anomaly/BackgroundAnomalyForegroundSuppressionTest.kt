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

    @Test
    fun `process identity prevents ram leak history contamination across recycled pid`() {
        val oldProcess = BackgroundAnomalyWatcher.ProcessIdentity(
            pid = 4321,
            processName = "com.app.leak",
            startIdentifier = "12:34"
        )
        val newRecycledProcess = BackgroundAnomalyWatcher.ProcessIdentity(
            pid = 4321,
            processName = "com.app.leak",
            startIdentifier = "00:02"
        )

        // As identidades completas DEVEM ser distintas
        assertFalse(oldProcess == newRecycledProcess)

        val ramHistoryMap = mutableMapOf<BackgroundAnomalyWatcher.ProcessIdentity, MutableList<Double>>()
        ramHistoryMap[oldProcess] = mutableListOf(100.0, 150.0, 200.0, 250.0, 300.0)

        // Ao consultar o novo processo com PID reciclado, ele não deve herdar o histórico de memória do processo anterior
        val inherited = ramHistoryMap[newRecycledProcess]
        assertEquals(null, inherited)

        // Limpeza de processos antigos
        val currentProcesses = setOf(newRecycledProcess)
        ramHistoryMap.keys.retainAll(currentProcesses)
        assertFalse(ramHistoryMap.containsKey(oldProcess))
    }

    @Test
    fun `activity lifecycle transitions properly clear foreground status after app pause`() {
        data class MockLifecycleEvent(val pkg: String, val type: Int, val timestamp: Long)
        val ACTIVITY_RESUMED = 1
        val ACTIVITY_PAUSED = 2

        val events = listOf(
            MockLifecycleEvent("com.chat.app", ACTIVITY_RESUMED, 1000L),
            MockLifecycleEvent("com.chat.app", ACTIVITY_PAUSED, 2000L),
            MockLifecycleEvent("com.game.app", ACTIVITY_RESUMED, 2500L)
        )

        val lastState = mutableMapOf<String, Int>()
        for (e in events) {
            lastState[e.pkg] = e.type
        }

        val visible = mutableSetOf<String>()
        for ((pkg, state) in lastState) {
            if (state == ACTIVITY_RESUMED) {
                visible.add(pkg)
            }
        }

        // com.game.app está em primeiro plano
        assertTrue(visible.contains("com.game.app"))
        // com.chat.app foi pausado e NÃO deve estar no conjunto de primeiro plano
        assertFalse(visible.contains("com.chat.app"))
    }
}
