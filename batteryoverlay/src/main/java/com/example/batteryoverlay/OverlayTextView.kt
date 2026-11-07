package com.example.batteryoverlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.util.TypedValue
import android.view.View
import kotlin.math.ceil

/**
 * Текст, який можна незалежно розтягувати по ширині (stretchX) і висоті (stretchY).
 * Розмір вікна оверлею підлаштовується під розмір тексту.
 */
class OverlayTextView(context: Context) : View(context) {

    private val dm = resources.displayMetrics

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        fontFeatureSettings = "tnum" // цифри однакової ширини — текст не «стрибає»
        setShadowLayer(3f, 1f, 1f, 0x99000000.toInt())
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * dm.density
        color = Color.WHITE
        pathEffect = DashPathEffect(floatArrayOf(6 * dm.density, 4 * dm.density), 0f)
    }

    var text: String = "…"
        set(value) {
            if (field != value) {
                field = value
                requestLayout(); invalidate()
            }
        }

    var textSizeSp: Float = 18f
        set(value) {
            field = value.coerceIn(Prefs.MIN_SIZE, Prefs.MAX_SIZE)
            textPaint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, field, dm)
            requestLayout(); invalidate()
        }

    var stretchX: Float = 1f
        set(value) {
            field = value.coerceIn(Prefs.MIN_STRETCH, Prefs.MAX_STRETCH)
            requestLayout(); invalidate()
        }

    var stretchY: Float = 1f
        set(value) {
            field = value.coerceIn(Prefs.MIN_STRETCH, Prefs.MAX_STRETCH)
            requestLayout(); invalidate()
        }

    var textColor: Int = Color.WHITE
        set(value) {
            field = value
            textPaint.color = value
            invalidate()
        }

    var bgColor: Int = 0x66000000
        set(value) {
            field = value
            bgPaint.color = value
            invalidate()
        }

    /** Пунктирна рамка в режимі редагування. */
    var highlighted: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    init {
        textSizeSp = 18f
        textColor = Color.WHITE
        bgColor = 0x66000000
    }

    private val padH get() = textPaint.textSize * 0.35f * stretchX
    private val padV get() = textPaint.textSize * 0.15f * stretchY

    /** Розмір, якого набуде view при поточних параметрах (ширина, висота). */
    fun desiredSize(): Pair<Int, Int> {
        val fm = textPaint.fontMetrics
        val w = textPaint.measureText(text) * stretchX + 2 * padH
        val h = (fm.descent - fm.ascent) * stretchY + 2 * padV
        return Pair(ceil(w).toInt(), ceil(h).toInt())
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val (w, h) = desiredSize()
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (Color.alpha(bgColor) > 0) {
            val r = textPaint.textSize * 0.3f
            canvas.drawRoundRect(0f, 0f, w, h, r, r, bgPaint)
        }
        val fm = textPaint.fontMetrics
        canvas.save()
        canvas.translate(padH, padV)
        canvas.scale(stretchX, stretchY)
        canvas.drawText(text, 0f, -fm.ascent, textPaint)
        canvas.restore()
        if (highlighted) {
            val i = borderPaint.strokeWidth / 2
            canvas.drawRect(i, i, w - i, h - i, borderPaint)
        }
    }
}
