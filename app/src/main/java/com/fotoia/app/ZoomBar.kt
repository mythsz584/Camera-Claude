package com.fotoia.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Barra vertical de zoom: arrastar para cima aumenta, para baixo diminui.
 * A escala e logaritmica, entao o comeco (1x, 2x, 5x) tem tanto espaco quanto o fim (50x, 100x).
 */
class ZoomBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var onZoomChanged: ((Float) -> Unit)? = null

    private var minZoom = 1f
    private var maxZoom = 100f
    private var zoom = 1f

    private val density = resources.displayMetrics.density
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt()
        strokeWidth = 2f * density
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FFFFFF.toInt()
        strokeWidth = 1.5f * density
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFC107.toInt()
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66000000
    }

    fun setRange(minValue: Float, maxValue: Float) {
        minZoom = max(0.1f, minValue)
        maxZoom = max(minZoom + 0.01f, maxValue)
        zoom = zoom.coerceIn(minZoom, maxZoom)
        invalidate()
    }

    fun setZoom(value: Float) {
        zoom = value.coerceIn(minZoom, maxZoom)
        invalidate()
    }

    private fun pad(): Float = 14f * density

    private fun fractionOf(value: Float): Float =
        (ln(value / minZoom) / ln(maxZoom / minZoom)).coerceIn(0f, 1f)

    private fun valueAt(fraction: Float): Float =
        minZoom * (maxZoom / minZoom).pow(fraction.coerceIn(0f, 1f))

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val top = pad()
        val bottom = height - pad()
        val span = bottom - top
        if (span <= 0f) return
        val x = width - 14f * density

        canvas.drawRect(x - 22f * density, top - 8f * density, x + 8f * density, bottom + 8f * density, shadowPaint)
        canvas.drawLine(x, top, x, bottom, linePaint)
        val ticks = 24
        for (i in 0..ticks) {
            val y = bottom - span * i / ticks
            val length = (if (i % 4 == 0) 16f else 9f) * density
            canvas.drawLine(x - length, y, x, y, tickPaint)
        }
        val thumbY = bottom - span * fractionOf(zoom)
        canvas.drawCircle(x, thumbY, 9f * density, thumbPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val top = pad()
                val bottom = height - pad()
                val span = max(1f, bottom - top)
                val fraction = (bottom - min(max(event.y, top), bottom)) / span
                zoom = valueAt(fraction)
                invalidate()
                onZoomChanged?.invoke(zoom)
            }
        }
        return true
    }
}
