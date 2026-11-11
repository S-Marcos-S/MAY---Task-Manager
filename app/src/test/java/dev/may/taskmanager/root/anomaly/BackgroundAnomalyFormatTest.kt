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

            val sharedUidPattern = Pattern.compile("<string name=\"anomaly_shared_uid_info\">(.*?)</string>")
            val sharedUidMatcher = sharedUidPattern.matcher(content)
            assertTrue("anomaly_shared_uid_info must exist in ${file.path}", sharedUidMatcher.find())
            val sharedUidTemplate = sharedUidMatcher.group(1) ?: ""
            val formattedSharedUid = String.format(Locale.getDefault(), sharedUidTemplate, 10045, 3)
            assertTrue(formattedSharedUid.contains("10045"))
            assertTrue(formattedSharedUid.contains("3"))

            val ramLeakPattern = Pattern.compile("<string name=\"anomaly_cause_ram_leak_suspect\">(.*?)</string>")
            val ramLeakMatcher = ramLeakPattern.matcher(content)
            assertTrue("anomaly_cause_ram_leak_suspect must exist in ${file.path}", ramLeakMatcher.find())
            val ramLeakTemplate = ramLeakMatcher.group(1) ?: ""
            val formattedRamLeak = String.format(Locale.getDefault(), ramLeakTemplate, 50, 180)
            assertTrue(formattedRamLeak.contains("50"))
            assertTrue(formattedRamLeak.contains("180"))

            val cpuHitsPattern = Pattern.compile("<string name=\"anomaly_reason_cpu_hits\">(.*?)</string>")
            val cpuHitsMatcher = cpuHitsPattern.matcher(content)
            assertTrue("anomaly_reason_cpu_hits must exist in ${file.path}", cpuHitsMatcher.find())
            val cpuHitsTemplate = cpuHitsMatcher.group(1) ?: ""
            val formattedCpuHits = String.format(Locale.getDefault(), cpuHitsTemplate, 3)
            assertTrue(formattedCpuHits.contains("3"))

            val appNormalPattern = Pattern.compile("<string name=\"net_speed_app_normal\">(.*?)</string>")
            val appNormalMatcher = appNormalPattern.matcher(content)
            assertTrue("net_speed_app_normal must exist in ${file.path}", appNormalMatcher.find())
            val appNormalTemplate = appNormalMatcher.group(1) ?: ""
            val formattedAppNormal = String.format(Locale.getDefault(), appNormalTemplate, "Telegram", "1.2 MB/s")
            assertTrue(formattedAppNormal.contains("Telegram"))
            assertTrue(formattedAppNormal.contains("1.2 MB/s"))

            val appDivPattern = Pattern.compile("<string name=\"net_speed_app_divergence\">(.*?)</string>")
            val appDivMatcher = appDivPattern.matcher(content)
            assertTrue("net_speed_app_divergence must exist in ${file.path}", appDivMatcher.find())
            val appDivTemplate = appDivMatcher.group(1) ?: ""
            val formattedAppDiv = String.format(Locale.getDefault(), appDivTemplate, "Telegram", "1.2 MB/s")
            assertTrue(formattedAppDiv.contains("Telegram"))
            assertTrue(formattedAppDiv.contains("1.2 MB/s"))

            val sharedUidCountPattern = Pattern.compile("<string name=\"shared_uid_app_count\">(.*?)</string>")
            val sharedUidCountMatcher = sharedUidCountPattern.matcher(content)
            assertTrue("shared_uid_app_count must exist in ${file.path}", sharedUidCountMatcher.find())
            val sharedUidCountTemplate = sharedUidCountMatcher.group(1) ?: ""
            val formattedSharedUidCount = String.format(Locale.getDefault(), sharedUidCountTemplate, "GMS", 2)
            assertTrue(formattedSharedUidCount.contains("GMS"))
            assertTrue(formattedSharedUidCount.contains("2"))
        }
    }
}
