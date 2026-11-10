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
 * Barra vertical com marcas: arrastar para cima aumenta o valor, para baixo diminui.
 * Usada para o zoom (lado direito, escala logaritmica) e para a luz (lado esquerdo, escala linear).
 */
class SliderBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var onValueChanged: ((Float) -> Unit)? = null

    /** Desenha a barra encostada na esquerda (padrao: direita). */
    var alignLeft = false
        set(v) { field = v; invalidate() }

    /** Escala logaritmica (zoom) ou linear (luz). */
    var logScale = true
        set(v) { field = v; invalidate() }

    /** Destaca a marca do meio (o zero da barra de luz). */
    var centerMark = false
        set(v) { field = v; invalidate() }

    private var minValue = 1f
    private var maxValue = 100f
    private var value = 1f

    private val density = resources.displayMetrics.density
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt()
        strokeWidth = 2f * density
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FFFFFF.toInt()
        strokeWidth = 1.5f * density
    }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        strokeWidth = 2.5f * density
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFC107.toInt()
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66000000
    }

    fun setRange(newMin: Float, newMax: Float) {
        minValue = if (logScale) max(0.1f, newMin) else newMin
        maxValue = max(minValue + 0.01f, newMax)
        value = value.coerceIn(minValue, maxValue)
        invalidate()
    }

    fun setValue(newValue: Float) {
        value = newValue.coerceIn(minValue, maxValue)
        invalidate()
    }

    private fun pad(): Float = 14f * density

    private fun fractionOf(v: Float): Float {
        val f = if (logScale) {
            ln(v / minValue) / ln(maxValue / minValue)
        } else {
            (v - minValue) / (maxValue - minValue)
        }
        return f.coerceIn(0f, 1f)
    }

    private fun valueAt(fraction: Float): Float {
        val f = fraction.coerceIn(0f, 1f)
        return if (logScale) {
            minValue * (maxValue / minValue).pow(f)
        } else {
            minValue + (maxValue - minValue) * f
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val top = pad()
        val bottom = height - pad()
        val span = bottom - top
        if (span <= 0f) return
        // dir: para que lado as marcas crescem a partir da linha
        val x = if (alignLeft) 14f * density else width - 14f * density
        val dir = if (alignLeft) 1f else -1f

        val shadowNear = x - dir * 8f * density
        val shadowFar = x + dir * 22f * density
        canvas.drawRect(
            min(shadowNear, shadowFar), top - 8f * density,
            max(shadowNear, shadowFar), bottom + 8f * density, shadowPaint
        )
        canvas.drawLine(x, top, x, bottom, linePaint)
        val ticks = 24
        for (i in 0..ticks) {
            val y = bottom - span * i / ticks
            val isCenter = centerMark && i == ticks / 2
            val length = (if (isCenter) 20f else if (i % 4 == 0) 16f else 9f) * density
            canvas.drawLine(x, y, x + dir * length, y, if (isCenter) centerPaint else tickPaint)
        }
        val thumbY = bottom - span * fractionOf(value)
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
                value = valueAt(fraction)
                invalidate()
                onValueChanged?.invoke(value)
            }
        }
        return true
    }
}
