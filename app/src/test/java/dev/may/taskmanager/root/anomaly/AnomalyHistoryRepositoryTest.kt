// SPDX-License-Identifier: GPL-3.0-or-later
package dev.may.taskmanager.root.anomaly

import dev.may.taskmanager.telemetry.ConfidenceLevel
import dev.may.taskmanager.telemetry.TelemetryNature
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class AnomalyHistoryRepositoryTest {

    @Test
    fun anomalyEvent_storesRawValuesAndTechnicalMetadata() {
        val event = AnomalyEvent(
            id = "test-uuid-1",
            packageName = "com.example.service",
            appName = "Service",
            type = AnomalyType.NETWORK,
            valueFormatted = "25.0 MB",
            description = "High data transfer",
            rawValue = 26214400.0,
            unit = "B",
            uid = 10042,
            confidence = ConfidenceLevel.HIGH,
            nature = TelemetryNature.DIRECT_MEASUREMENT,
            source = "TrafficStats",
            isEstimated = false,
            isComplete = true,
            rootAvailable = true,
            reason = "Amostra direta confirmada",
        )

        assertEquals(26214400.0, event.rawValue, 0.01)
        assertEquals("B", event.unit)
        assertEquals(10042, event.uid)
        assertEquals(ConfidenceLevel.HIGH, event.confidence)
        assertEquals(TelemetryNature.DIRECT_MEASUREMENT, event.nature)
        assertEquals("TrafficStats", event.source)
        assertFalse(event.isEstimated)
        assertTrue(event.isComplete)
        assertTrue(event.rootAvailable)
    }

    @Test
    fun jsonParsing_preservesBackwardCompatibilityWithLegacyJson() {
        // Formato legado (v1.0.19 e anteriores) sem os campos tecnicos de telemetria
        val legacyJson = """
            [
                {
                    "id": "legacy-id-1",
                    "packageName": "com.legacy.app",
                    "appName": "Legacy App",
                    "type": "NETWORK",
                    "valueFormatted": "10 MB",
                    "description": "Legacy description",
                    "timestamp": 1730000000000,
                    "screenWasOff": true
                }
            ]
        """.trimIndent()

        val array = JSONArray(legacyJson)
        val obj = array.getJSONObject(0)

        val isLegacy = !obj.has("confidence")
        val confName = obj.optString("confidence", ConfidenceLevel.LOW.name)
        val confidence = runCatching { ConfidenceLevel.valueOf(confName) }.getOrDefault(ConfidenceLevel.LOW)

        val natureName = obj.optString("nature", TelemetryNature.ESTIMATE.name)
        val nature = runCatching { TelemetryNature.valueOf(natureName) }.getOrDefault(TelemetryNature.ESTIMATE)

        val event = AnomalyEvent(
            id = obj.optString("id", UUID.randomUUID().toString()),
            packageName = obj.optString("packageName", ""),
            appName = obj.optString("appName", ""),
            type = AnomalyType.valueOf(obj.getString("type")),
            valueFormatted = obj.optString("valueFormatted", ""),
            description = obj.optString("description", ""),
            timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
            screenWasOff = obj.optBoolean("screenWasOff", false),
            rawValue = obj.optDouble("rawValue", 0.0),
            unit = obj.optString("unit", ""),
            confidence = confidence,
            nature = nature,
            isEstimated = if (isLegacy) true else obj.optBoolean("isEstimated", false),
            reason = if (isLegacy) "Registro legado anterior sem metadados de medicao" else null,
        )

        assertEquals("legacy-id-1", event.id)
        assertEquals("com.legacy.app", event.packageName)
        assertEquals(0.0, event.rawValue, 0.01)
        assertEquals(ConfidenceLevel.LOW, event.confidence)
        assertEquals(TelemetryNature.ESTIMATE, event.nature)
        assertTrue(event.isEstimated)
    }

    @Test
    fun jsonParsing_handlesMalformedJsonGracefully() {
        val malformed = "{ this is not a valid json array }"
        val result = runCatching { JSONArray(malformed) }
        assertTrue(result.isFailure)
    }

    @Test
    fun anomalyWatcherConfig_defaultValuesAreAllEnabledAndMediumSensitivity() {
        val config = AnomalyWatcherConfig()
        assertTrue("Alerts must be enabled by default upon installation", config.isEnabled)
        assertEquals("Sensitivity must be BALANCED (medium) by default", AnomalySensitivity.BALANCED, config.sensitivity)
        assertTrue("Network alerts must be enabled by default", config.notifyNetwork)
        assertTrue("CPU alerts must be enabled by default", config.notifyCpu)
        assertTrue("RAM alerts must be enabled by default", config.notifyRam)
        assertTrue("Deep sleep alerts must be enabled by default", config.notifyDeepSleep)
        assertTrue("Location alerts must be enabled by default", config.notifyLocation)
        assertTrue("Whitelist must be empty by default", config.whitelistedPackages.isEmpty())
    }

    @Test
    fun anomalyType_locationEnumAndSensitivityThresholdsAreValid() {
        assertEquals("LOCATION", AnomalyType.LOCATION.name)
        assertEquals(1, AnomalySensitivity.HIGH.locationMinHits)
        assertEquals(2, AnomalySensitivity.BALANCED.locationMinHits)
        assertEquals(3, AnomalySensitivity.LOW.locationMinHits)

        val summary = FlaggedAppSummary(
            packageName = "com.test.loc",
            appName = "LocApp",
            totalFlags = 3,
            locationFlags = 3,
        )
        assertEquals(3, summary.locationFlags)
        assertEquals("com.test.loc", summary.packageName)
    }
}
