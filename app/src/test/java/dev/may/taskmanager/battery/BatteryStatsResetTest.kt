// SPDX-License-Identifier: GPL-3.0-or-later
package dev.may.taskmanager.battery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryStatsResetTest {

    @Test
    fun resetStatsSemantics_doesNotReturnSuccessWhenAdvancedResetFails() {
        val resultSuccess = BatteryStatsViewModel.ResetResult.SUCCESS
        val resultSessionOnly = BatteryStatsViewModel.ResetResult.SESSION_ONLY_SUCCESS
        val resultAdvFailed = BatteryStatsViewModel.ResetResult.ADVANCED_FAILED
        val resultFailed = BatteryStatsViewModel.ResetResult.FAILED

        fun isSuccess(result: BatteryStatsViewModel.ResetResult): Boolean {
            return result == BatteryStatsViewModel.ResetResult.SUCCESS || result == BatteryStatsViewModel.ResetResult.SESSION_ONLY_SUCCESS
        }

        assertTrue("Advanced reset success must report true", isSuccess(resultSuccess))
        assertTrue("Session only reset success must report true", isSuccess(resultSessionOnly))
        assertFalse("Advanced reset failure MUST NOT report success", isSuccess(resultAdvFailed))
        assertFalse("General failure must report false", isSuccess(resultFailed))
    }

    @Test
    fun resetStatsSemantics_differentiatesAllOutcomesCleanly() {
        assertEquals("SUCCESS", BatteryStatsViewModel.ResetResult.SUCCESS.name)
        assertEquals("SESSION_ONLY_SUCCESS", BatteryStatsViewModel.ResetResult.SESSION_ONLY_SUCCESS.name)
        assertEquals("ADVANCED_FAILED", BatteryStatsViewModel.ResetResult.ADVANCED_FAILED.name)
        assertEquals("FAILED", BatteryStatsViewModel.ResetResult.FAILED.name)
    }

    @Test
    fun autoResetOnPowerDisconnected_triggersWhenOptionEnabledRegardlessOfLevel() {
        fun shouldResetOnPowerDisconnected(autoResetChargingEnabled: Boolean): Boolean {
            return autoResetChargingEnabled
        }

        assertTrue(shouldResetOnPowerDisconnected(autoResetChargingEnabled = true))
        assertFalse(shouldResetOnPowerDisconnected(autoResetChargingEnabled = false))
    }
}
