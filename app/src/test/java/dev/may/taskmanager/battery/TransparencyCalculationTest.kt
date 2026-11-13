// SPDX-License-Identifier: GPL-3.0-or-later
package dev.may.taskmanager.battery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class TransparencyCalculationTest {

    @Test
    fun calculateNextTriggerMillis_schedulesFutureToday() {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 10)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
        }
        val targetHour = 21
        val targetMinute = 30

        val targetCalendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, targetHour)
            set(Calendar.MINUTE, targetMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= calendar.timeInMillis) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        assertTrue(targetCalendar.timeInMillis > calendar.timeInMillis)
        assertEquals(targetHour, targetCalendar.get(Calendar.HOUR_OF_DAY))
        assertEquals(targetMinute, targetCalendar.get(Calendar.MINUTE))
    }

    @Test
    fun calculateNextTriggerMillis_schedulesNextDayIfPassed() {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
        }
        val targetHour = 21
        val targetMinute = 0

        val targetCalendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, targetHour)
            set(Calendar.MINUTE, targetMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= calendar.timeInMillis) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        assertTrue(targetCalendar.timeInMillis > calendar.timeInMillis)
        assertEquals(targetHour, targetCalendar.get(Calendar.HOUR_OF_DAY))
        assertEquals(targetMinute, targetCalendar.get(Calendar.MINUTE))
    }

    @Test
    fun formatBatteryDuration_formatsCorrectly() {
        assertEquals("0m", formatBatteryDuration(0L))
        assertEquals("45s", formatBatteryDuration(45_000L))
        assertEquals("15m", formatBatteryDuration(15 * 60 * 1000L))
        assertEquals("2h 30m", formatBatteryDuration(150 * 60 * 1000L))
    }
}
