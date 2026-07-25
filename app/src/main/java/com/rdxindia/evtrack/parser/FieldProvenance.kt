package com.rdxindia.evtrack.parser

/**
 * Where an accepted field value came from: which engine produced it, which
 * preprocessing variant the image had, which pipeline stage ran, and how much
 * the ensemble trusts it.
 */
data class FieldProvenance(
    val field: String,
    val value: Int?,
    val engine: String,
    val variant: String,
    val stage: String,
    val confidence: FieldConfidence? = null
) {
    /** Compact one-line form for the debug panel. */
    fun display(): String {
        val level = confidence?.let { " [$it]" } ?: ""
        return "$value ← $engine / $variant / $stage$level"
    }
}

/**
 * Serializes provenance for the `debugJson` column. Hand-rolled so it stays a
 * pure Kotlin function (org.json is stubbed in JVM unit tests) and so the
 * stored shape is stable regardless of Android version.
 */
object ProvenanceJson {

    fun build(
        provenance: Map<String, FieldProvenance>,
        notes: List<String> = emptyList(),
        engines: List<String> = emptyList()
    ): String {
        val sb = StringBuilder()
        sb.append('{')

        sb.append("\"engines\":[")
        engines.forEachIndexed { i, engine ->
            if (i > 0) sb.append(',')
            sb.append(quote(engine))
        }
        sb.append("],")

        sb.append("\"fields\":{")
        var first = true
        for ((field, p) in provenance) {
            if (!first) sb.append(',')
            first = false
            sb.append(quote(field)).append(":{")
            sb.append("\"value\":").append(p.value?.toString() ?: "null").append(',')
            sb.append("\"engine\":").append(quote(p.engine)).append(',')
            sb.append("\"variant\":").append(quote(p.variant)).append(',')
            sb.append("\"stage\":").append(quote(p.stage)).append(',')
            sb.append("\"confidence\":").append(p.confidence?.let { quote(it.name) } ?: "null")
            sb.append('}')
        }
        sb.append("},")

        sb.append("\"notes\":[")
        notes.forEachIndexed { i, note ->
            if (i > 0) sb.append(',')
            sb.append(quote(note))
        }
        sb.append(']')

        sb.append('}')
        return sb.toString()
    }

    private fun quote(raw: String): String {
        val sb = StringBuilder(raw.length + 2)
        sb.append('"')
        for (ch in raw) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch < ' ') sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
