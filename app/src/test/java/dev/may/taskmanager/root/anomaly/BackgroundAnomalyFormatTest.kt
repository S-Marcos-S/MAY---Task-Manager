// SPDX-License-Identifier: GPL-3.0-or-later
package dev.may.taskmanager.root.anomaly

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import java.util.regex.Pattern

class BackgroundAnomalyFormatTest {

    @Test
    fun testAnomalyStringResourcesFormatWithoutExceptions() {
        val locales = listOf("values", "values-pt", "values-pt-rBR")

        for (loc in locales) {
            val file = listOf(
                File("src/main/res/$loc/strings.xml"),
                File("app/src/main/res/$loc/strings.xml")
            ).firstOrNull { it.exists() }

            assertTrue("strings.xml must exist for locale $loc", file != null && file.exists())
            val content = file!!.readText()

            val cpuPattern = Pattern.compile("<string name=\"anomaly_desc_cpu\">(.*?)</string>")
            val cpuMatcher = cpuPattern.matcher(content)
            assertTrue("anomaly_desc_cpu must exist in ${file.path}", cpuMatcher.find())
            val cpuTemplate = cpuMatcher.group(1) ?: ""
            assertFalse("anomaly_desc_cpu should not use %d for formatted CPU string", cpuTemplate.contains("%2\$d"))
            val formattedCpu = String.format(Locale.getDefault(), cpuTemplate, "AppTest", "12.5%")
            assertTrue(formattedCpu.contains("AppTest"))
            assertTrue(formattedCpu.contains("12.5%"))

            val ramPattern = Pattern.compile("<string name=\"anomaly_desc_ram\">(.*?)</string>")
            val ramMatcher = ramPattern.matcher(content)
            assertTrue("anomaly_desc_ram must exist in ${file.path}", ramMatcher.find())
            val ramTemplate = ramMatcher.group(1) ?: ""
            val formattedRam = String.format(Locale.getDefault(), ramTemplate, "AppTest", "350 MB")
            assertTrue(formattedRam.contains("AppTest"))
            assertTrue(formattedRam.contains("350 MB"))

            val sleepPattern = Pattern.compile("<string name=\"anomaly_desc_deep_sleep\">(.*?)</string>")
            val sleepMatcher = sleepPattern.matcher(content)
            assertTrue("anomaly_desc_deep_sleep must exist in ${file.path}", sleepMatcher.find())
            val sleepTemplate = sleepMatcher.group(1) ?: ""
            assertFalse("anomaly_desc_deep_sleep should not use %d for formatted minute strings", sleepTemplate.contains("%2\$d"))
            val formattedSleep = String.format(Locale.getDefault(), sleepTemplate, "AppTest", "10m", "45m")
            assertTrue(formattedSleep.contains("AppTest"))
            assertTrue(formattedSleep.contains("10m"))
            assertTrue(formattedSleep.contains("45m"))

            val awakePattern = Pattern.compile("<string name=\"anomaly_value_awake_minutes\">(.*?)</string>")
            val awakeMatcher = awakePattern.matcher(content)
            assertTrue("anomaly_value_awake_minutes must exist in ${file.path}", awakeMatcher.find())
            val awakeTemplate = awakeMatcher.group(1) ?: ""
            val formattedAwake = String.format(Locale.getDefault(), awakeTemplate, 15)
            assertTrue(formattedAwake.contains("15"))
        }
    }
}
