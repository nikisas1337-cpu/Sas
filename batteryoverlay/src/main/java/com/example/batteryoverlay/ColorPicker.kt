package com.example.batteryoverlay

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

/** Діалог-палітра: готові кольори + точне налаштування відтінку, насиченості, яскравості й прозорості. */
object ColorPicker {

    private val PRESETS = intArrayOf(
        0xFFFFFFFF.toInt(), 0xFFBDBDBD.toInt(), 0xFF757575.toInt(), 0xFF000000.toInt(),
        0xFFF44336.toInt(), 0xFFFF5722.toInt(), 0xFFFF9800.toInt(), 0xFFFFC107.toInt(),
        0xFFFFEB3B.toInt(), 0xFFCDDC39.toInt(), 0xFF8BC34A.toInt(), 0xFF4CAF50.toInt(),
        0xFF69F0AE.toInt(), 0xFF009688.toInt(), 0xFF00BCD4.toInt(), 0xFF03A9F4.toInt(),
        0xFF2196F3.toInt(), 0xFF3F51B5.toInt(), 0xFF673AB7.toInt(), 0xFF9C27B0.toInt(),
        0xFFE91E63.toInt(), 0xFFFF80AB.toInt(), 0xFF795548.toInt(), 0xFF607D8B.toInt(),
    )

    fun show(
        context: Context,
        title: String,
        initial: Int,
        withAlpha: Boolean,
        onPicked: (Int) -> Unit,
    ) {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val hsv = FloatArray(3)
        Color.colorToHSV(initial, hsv)
        var alpha = if (withAlpha) Color.alpha(initial) else 255
        fun current() = Color.HSVToColor(alpha, hsv)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(8))
        }

        val previewBg = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setStroke(dp(1), Color.GRAY)
        }
        val preview = View(context).apply { background = previewBg }
        root.addView(preview, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)))
        val hexText = TextView(context).apply { setPadding(0, dp(4), 0, dp(8)) }
        root.addView(hexText)

        // Сітка готових кольорів
        val grid = GridLayout(context).apply { columnCount = 6 }
        root.addView(grid)

        // Повзунки HSV + прозорість, над кожним — кольорова смуга-підказка
        val bars = mutableListOf<SeekBar>()
        val strips = mutableListOf<View>()
        fun addBar(label: String, max: Int, value: Int, onChange: (Int) -> Unit): SeekBar {
            root.addView(TextView(context).apply {
                text = label
                setPadding(0, dp(10), 0, dp(2))
            })
            val strip = View(context)
            root.addView(strip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(14)).apply {
                leftMargin = dp(16); rightMargin = dp(16)
            })
            strips.add(strip)
            val bar = SeekBar(context).apply {
                this.max = max
                progress = value
            }
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) onChange(p)
                }

                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
            root.addView(bar)
            bars.add(bar)
            return bar
        }

        lateinit var refresh: () -> Unit
        val hueBar = addBar("Відтінок", 360, hsv[0].toInt()) { hsv[0] = it.toFloat(); refresh() }
        val satBar = addBar("Насиченість", 100, (hsv[1] * 100).toInt()) { hsv[1] = it / 100f; refresh() }
        val valBar = addBar("Яскравість", 100, (hsv[2] * 100).toInt()) { hsv[2] = it / 100f; refresh() }
        val alphaBar = if (withAlpha) addBar("Непрозорість", 255, alpha) { alpha = it; refresh() } else null

        fun gradient(vararg colors: Int) = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, colors).apply {
            cornerRadius = dp(7).toFloat()
        }

        refresh = {
            val c = current()
            previewBg.setColor(c)
            hexText.text = String.format("#%08X", c)
            val hueColors = IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f)) }
            strips[0].background = gradient(*hueColors)
            strips[1].background = gradient(
                Color.HSVToColor(floatArrayOf(hsv[0], 0f, hsv[2])),
                Color.HSVToColor(floatArrayOf(hsv[0], 1f, hsv[2]))
            )
            strips[2].background = gradient(Color.BLACK, Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], 1f)))
            if (withAlpha) strips[3].background = gradient(
                Color.HSVToColor(0, hsv), Color.HSVToColor(255, hsv)
            )
        }

        fun syncBars() {
            hueBar.progress = hsv[0].toInt()
            satBar.progress = (hsv[1] * 100).toInt()
            valBar.progress = (hsv[2] * 100).toInt()
            alphaBar?.progress = alpha
        }

        val size = dp(36)
        PRESETS.forEach { color ->
            val swatch = View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                    setStroke(dp(1), Color.GRAY)
                }
                setOnClickListener {
                    Color.colorToHSV(color, hsv)
                    syncBars()
                    refresh()
                }
            }
            grid.addView(swatch, GridLayout.LayoutParams().apply {
                width = size
                height = size
                setMargins(dp(4), dp(4), dp(4), dp(4))
            })
        }

        if (withAlpha) {
            // Кнопка «прозорий фон»
            root.addView(TextView(context).apply {
                text = "Зробити повністю прозорим"
                setPadding(0, dp(12), 0, dp(4))
                setTextColor(0xFF2196F3.toInt())
                setOnClickListener {
                    alpha = 0
                    syncBars()
                    refresh()
                }
            })
        }

        refresh()

        AlertDialog.Builder(context)
            .setTitle(title)
            .setView(ScrollView(context).apply { addView(root) })
            .setPositiveButton("OK") { _, _ -> onPicked(current()) }
            .setNegativeButton("Скасувати", null)
            .show()
    }
}
