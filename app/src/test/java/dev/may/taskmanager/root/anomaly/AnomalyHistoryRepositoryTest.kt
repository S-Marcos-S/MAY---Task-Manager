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

        val confName = obj.optString("confidence", ConfidenceLevel.HIGH.name)
        val confidence = runCatching { ConfidenceLevel.valueOf(confName) }.getOrDefault(ConfidenceLevel.HIGH)

        val natureName = obj.optString("nature", TelemetryNature.DIRECT_MEASUREMENT.name)
        val nature = runCatching { TelemetryNature.valueOf(natureName) }.getOrDefault(TelemetryNature.DIRECT_MEASUREMENT)

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
        )

        assertEquals("legacy-id-1", event.id)
        assertEquals("com.legacy.app", event.packageName)
        assertEquals(0.0, event.rawValue, 0.01)
        assertEquals(ConfidenceLevel.HIGH, event.confidence)
        assertEquals(TelemetryNature.DIRECT_MEASUREMENT, event.nature)
    }
}
