package com.rdxindia.evtrack.parser

data class ExtractionResult(
    val odo: Int?,
    val battery: Int?,
    val range: Int?,
    val rawLines: List<OcrLine>,
    val confidenceNotes: List<String>
)
