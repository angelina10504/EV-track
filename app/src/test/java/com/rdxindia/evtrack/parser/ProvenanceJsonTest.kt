package com.rdxindia.evtrack.parser

import org.junit.Assert.assertTrue
import org.junit.Test

class ProvenanceJsonTest {

    @Test
    fun `serializes fields engines and notes`() {
        val json = ProvenanceJson.build(
            provenance = mapOf(
                "odo" to FieldProvenance("odo", 12676, "paddle", "ORIGINAL", "pass1-ensemble", FieldConfidence.HIGH),
                "battery" to FieldProvenance("battery", 84, "mlkit", "MORPH_CLOSE_BIN", "crop")
            ),
            notes = listOf("battery: only mlkit found 84"),
            engines = listOf("mlkit", "paddle")
        )

        assertTrue(json.startsWith("{") && json.endsWith("}"))
        assertTrue(json.contains("\"engines\":[\"mlkit\",\"paddle\"]"))
        assertTrue(json.contains("\"odo\":{\"value\":12676"))
        assertTrue(json.contains("\"engine\":\"paddle\""))
        assertTrue(json.contains("\"confidence\":\"HIGH\""))
        // A ladder-filled field has no ensemble confidence level.
        assertTrue(json.contains("\"confidence\":null"))
        assertTrue(json.contains("\"notes\":[\"battery: only mlkit found 84\"]"))
    }

    @Test
    fun `escapes quotes and newlines in notes`() {
        val json = ProvenanceJson.build(
            provenance = emptyMap(),
            notes = listOf("odo: lone \"value km\" line\nsecond line")
        )

        assertTrue(json.contains("\\\"value km\\\""))
        assertTrue(json.contains("\\n"))
    }

    @Test
    fun `null value serializes as JSON null`() {
        val json = ProvenanceJson.build(
            mapOf("range" to FieldProvenance("range", null, "7seg", "ORIGINAL", "anchor-region"))
        )
        assertTrue(json.contains("\"value\":null"))
    }
}
