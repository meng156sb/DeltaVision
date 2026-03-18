package com.deltavision.app.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.deltavision.app.model.Detection

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        textSize = 32f
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }
    private var detections: List<Detection> = emptyList()
    private var statsText: String = ""

    fun update(detections: List<Detection>, statsText: String) {
        this.detections = detections
        this.statsText = statsText
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        detections.forEach { detection ->
            canvas.drawRect(detection.left, detection.top, detection.right, detection.bottom, boxPaint)
            canvas.drawText(
                "${detection.label} ${(detection.confidence * 100).toInt()}% #${detection.trackId}",
                detection.left,
                (detection.top - 8f).coerceAtLeast(32f),
                textPaint,
            )
        }
        if (statsText.isNotBlank()) {
            canvas.drawText(statsText, 24f, 42f, textPaint)
        }
    }
}
