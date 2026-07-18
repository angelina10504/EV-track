package com.rdxindia.evtrack.parser

/**
 * One recognized text line with its bounding box in image coordinates.
 * Plain ints (no android.graphics.Rect) so the parser is testable on the JVM.
 */
data class OcrLine(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val height: Int get() = bottom - top

    fun boxString(): String = "[L:$left T:$top R:$right B:$bottom]"
}
