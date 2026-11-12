// SPDX-License-Identifier: GPL-3.0-or-later
package dev.may.taskmanager.battery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryStatsParserTest {

    @Test
    fun parseCheckin_globalBluetoothController_parsesIdleRxTxAndPower() {
        // AOSP format: parts[4] = idle, parts[5] = rx, parts[6] = power (mAms), parts[7..n] = tx levels
        // 3600000 mAms = 1.0 mAh
        val raw = """
            9,0,i,uid,1000,android
            9,0,l,gble,120000,5000,3600000,1500,1500
        """.trimIndent()

        val snapshot = BatteryStatsParser.parseCheckin(raw)
        val bt = snapshot.bluetooth
        assertNotNull(bt)
        assertEquals(120000L, bt!!.idleTimeMs)
        assertEquals(5000L, bt.rxTimeMs)
        assertEquals(3000L, bt.txTimeMs)
        assertEquals(1.0, bt.powerMah, 0.001)
    }

    @Test
    fun parseCheckin_componentPowerBlue_isCaptured() {
        val raw = """
            9,0,i,uid,1000,android
            9,0,l,pwi,blue,3.75
        """.trimIndent()

        val snapshot = BatteryStatsParser.parseCheckin(raw)
        val bt = snapshot.bluetooth
        assertNotNull(bt)
        assertEquals(3.75, bt!!.powerMah, 0.001)
    }

    @Test
    fun parseCheckin_bluetoothStateTime_providesFallbackTimers() {
        // bst line: parts[4] = inactive (idle), parts[5] = low (rx), parts[6] = med, parts[7] = high (tx = med+high)
        val raw = """
            9,0,i,uid,1000,android
            9,0,l,bst,400000,8000,4000,2000
        """.trimIndent()

        val snapshot = BatteryStatsParser.parseCheckin(raw)
        val bt = snapshot.bluetooth
        assertNotNull(bt)
        assertEquals(400000L, bt!!.idleTimeMs)
        assertEquals(8000L, bt.rxTimeMs)
        assertEquals(6000L, bt.txTimeMs)
    }

    @Test
    fun parseCheckin_blemScanTime_aggregatesScanDurations() {
        val raw = """
            9,0,i,uid,10200,com.example.ble
            9,10200,l,blem,35000,0,0,0,0,0,12000
        """.trimIndent()

        val snapshot = BatteryStatsParser.parseCheckin(raw)
        val bt = snapshot.bluetooth
        assertNotNull(bt)
        assertEquals(35000L, bt!!.scanTimeMs)

        val app = snapshot.apps.firstOrNull { it.uid == 10200 }
        assertNotNull(app)
        assertEquals(35000L, app!!.bluetoothScanTimeMs)
        assertEquals(12000L, app.bluetoothUnoptimizedScanTimeMs)
    }

    @Test
    fun parseCheckin_noBluetoothData_returnsZeroedStatsNeverNull() {
        val raw = """
            9,0,i,uid,1000,android
            9,0,l,bt,1,50000,45000
        """.trimIndent()

        val snapshot = BatteryStatsParser.parseCheckin(raw)
        val bt = snapshot.bluetooth
        assertNotNull(bt)
        assertEquals(0L, bt!!.idleTimeMs)
        assertEquals(0L, bt.rxTimeMs)
        assertEquals(0L, bt.txTimeMs)
        assertEquals(0.0, bt.powerMah, 0.0001)
        assertEquals(0L, bt.scanTimeMs)
    }
}
