package com.rdxindia.evtrack.parser

/**
 * Fuzzy matching of OCR tokens against the small, closed dashboard vocabulary
 * (ODO, BATTERY, RANGE, TRIP, ...). OCR errors are near-misses, not noise:
 * digit/letter confusions ("0D0" for "ODO", "8ATTERY") and dropped or swapped
 * characters ("BATERY", "RNGE"). Confusable characters are normalized first,
 * then a small Levenshtein distance is allowed, scaled by label length.
 */
object FuzzyLabel {

    private val CONFUSABLES = mapOf(
        '0' to 'O', '1' to 'I', '2' to 'Z', '5' to 'S', '6' to 'G', '8' to 'B'
    )

    private val NON_ALNUM = Regex("""[^A-Z0-9]+""")

    fun tokenize(text: String): List<String> =
        text.split(NON_ALNUM).filter { it.isNotEmpty() }

    /**
     * Whether [token] is a plausible OCR misread of [label].
     *
     * Short labels (≤4 chars, e.g. "ODO") only accept tokens of at least the
     * label's length — otherwise the big speed digits "00" would match "ODO"
     * by a single insertion and resurrect the speed-trap problem.
     */
    fun matches(token: String, label: String): Boolean {
        val maxDist = if (label.length <= 6) 1 else 2
        if (token.length < label.length && label.length <= 4) return false
        if (label.length - token.length > maxDist) return false
        if (token.length - label.length > maxDist) return false
        return levenshtein(normalize(token), label) <= maxDist
    }

    /** True when [text] contains a label as a substring or a fuzzy token match. */
    fun lineMatches(text: String, vararg labels: String): Boolean {
        if (labels.any { text.contains(it) }) return true
        val tokens = tokenize(text)
        return labels.any { label -> tokens.any { matches(it, label) } }
    }

    private fun normalize(token: String): String =
        buildString(token.length) { token.forEach { append(CONFUSABLES[it] ?: it) } }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        var previous = IntArray(b.length + 1) { it }
        val current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, substitution)
            }
            previous = current.copyInto(IntArray(b.length + 1))
        }
        return previous[b.length]
    }
}
