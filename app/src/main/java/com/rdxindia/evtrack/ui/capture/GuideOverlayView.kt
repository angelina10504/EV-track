package com.rdxindia.evtrack.ui.capture

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Scrim with a clear rounded-rectangle window sized for a scooter dashboard
 * display (~2.2:1). Coaxes the user into filling the frame with the display,
 * square-on — the single biggest OCR accuracy factor found in testing.
 */
class GuideOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val scrimPaint = Paint().apply { color = 0x99000000.toInt() }
    private val clearPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        isAntiAlias = true
    }
    private val borderPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
        color = 0xFFFFFFFF.toInt()
        isAntiAlias = true
    }
    private val cornerRadius = 16f * resources.displayMetrics.density

    init {
        // CLEAR xfermode needs an offscreen buffer.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun windowRect(): RectF {
        val windowWidth = width * 0.88f
        val windowHeight = windowWidth / 2.2f
        val centerX = width / 2f
        val centerY = height * 0.42f
        return RectF(
            centerX - windowWidth / 2f,
            centerY - windowHeight / 2f,
            centerX + windowWidth / 2f,
            centerY + windowHeight / 2f
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)
        val window = windowRect()
        canvas.drawRoundRect(window, cornerRadius, cornerRadius, clearPaint)
        canvas.drawRoundRect(window, cornerRadius, cornerRadius, borderPaint)
    }
}
