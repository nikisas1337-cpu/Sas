package com.example.batteryoverlay

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var root: LinearLayout
    private lateinit var monitor: BatteryMonitor
    private lateinit var liveText: TextView
    private lateinit var toggleButton: Button
    private lateinit var permissionButton: Button
    private val handler = Handler(Looper.getMainLooper())

    private val liveTick = object : Runnable {
        override fun run() {
            val r = monitor.update()
            liveText.text = "${r.powerText}\n${r.timeText}"
            refreshState()
            handler.postDelayed(this, 1000)
        }
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        monitor = BatteryMonitor(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(32))
        }
        setContentView(ScrollView(this).apply { addView(root) })

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        buildUi()
        handler.post(liveTick)
    }

    override fun onPause() {
        handler.removeCallbacks(liveTick)
        super.onPause()
    }

    // ------------------------------------------------------------------ UI

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        root.removeAllViews()

        liveText = TextView(this).apply {
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(0x22808080)
            }
        }
        root.addView(liveText)

        permissionButton = Button(this).apply {
            text = "Дозволити показ поверх інших застосунків"
            setOnClickListener { requestOverlayPermission() }
        }
        root.addView(permissionButton)

        toggleButton = Button(this).apply {
            setOnClickListener {
                if (OverlayService.isRunning) {
                    stopService(Intent(this@MainActivity, OverlayService::class.java))
                } else {
                    startOverlay(null)
                }
                handler.postDelayed({ refreshState() }, 300)
            }
        }
        root.addView(toggleButton)

        root.addView(SwitchCompat(this).apply {
            text = "Заблокувати написи (дотики проходять крізь них)"
            isChecked = Prefs.isLocked(this@MainActivity)
            setPadding(0, dp(8), 0, dp(8))
            setOnCheckedChangeListener { _, checked ->
                Prefs.get(this@MainActivity).edit().putBoolean(Prefs.KEY_LOCKED, checked).apply()
            }
        })

        root.addView(TextView(this).apply {
            text = "Жести на самому написі:\n" +
                "• перетягніть пальцем — перемістити будь-куди\n" +
                "• щипок — збільшити / зменшити\n" +
                "• щипок горизонтально — розтягнути по ширині, вертикально — по висоті\n" +
                "• довгий дотик — режим редагування на весь екран (зручно для маленького тексту)\n" +
                "• подвійний дотик — відкрити ці налаштування"
            setPadding(0, dp(4), 0, dp(8))
            alpha = 0.8f
        })

        OverlayItem.values().forEach { addItemSection(it) }
        refreshState()
    }

    private fun refreshState() {
        val canDraw = Settings.canDrawOverlays(this)
        permissionButton.visibility = if (canDraw) View.GONE else View.VISIBLE
        toggleButton.isEnabled = canDraw
        toggleButton.text = if (OverlayService.isRunning) "Зупинити оверлей" else "Запустити оверлей"
    }

    private fun requestOverlayPermission() {
        startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        )
    }

    private fun startOverlay(editItem: OverlayItem?) {
        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
            return
        }
        val intent = Intent(this, OverlayService::class.java)
        if (editItem != null) {
            intent.action = OverlayService.ACTION_EDIT
            intent.putExtra(OverlayService.EXTRA_ITEM, editItem.id)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun addItemSection(item: OverlayItem) {
        val prefs = Prefs.get(this)
        val cfg = Prefs.load(this, item)

        root.addView(TextView(this).apply {
            text = item.title
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(24), 0, dp(4))
        })

        root.addView(SwitchCompat(this).apply {
            text = "Показувати"
            isChecked = cfg.enabled
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(Prefs.key(item, Prefs.ENABLED), checked).apply()
            }
        })

        addSlider("Розмір тексту", Prefs.MIN_SIZE.toInt(), Prefs.MAX_SIZE.toInt(), cfg.sizeSp.toInt(), { "$it sp" }) {
            prefs.edit().putFloat(Prefs.key(item, Prefs.SIZE), it.toFloat()).apply()
        }
        addSlider(
            "Розтягнути по ширині",
            (Prefs.MIN_STRETCH * 100).toInt(), (Prefs.MAX_STRETCH * 100).toInt(),
            (cfg.stretchX * 100).toInt(), { "$it%" }
        ) {
            prefs.edit().putFloat(Prefs.key(item, Prefs.STRETCH_X), it / 100f).apply()
        }
        addSlider(
            "Розтягнути по висоті",
            (Prefs.MIN_STRETCH * 100).toInt(), (Prefs.MAX_STRETCH * 100).toInt(),
            (cfg.stretchY * 100).toInt(), { "$it%" }
        ) {
            prefs.edit().putFloat(Prefs.key(item, Prefs.STRETCH_Y), it / 100f).apply()
        }

        addColorRow("Колір тексту", cfg.textColor, withAlpha = false) {
            prefs.edit().putInt(Prefs.key(item, Prefs.TEXT_COLOR), it).apply()
        }
        addColorRow("Колір фону", cfg.bgColor, withAlpha = true) {
            prefs.edit().putInt(Prefs.key(item, Prefs.BG_COLOR), it).apply()
        }

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Button(this).apply {
            text = "Редагувати на екрані"
            setOnClickListener { startOverlay(item) }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(Button(this).apply {
            text = "Скинути"
            setOnClickListener {
                Prefs.reset(this@MainActivity, item)
                buildUi()
            }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(buttons)
    }

    private fun addSlider(
        label: String,
        min: Int,
        max: Int,
        value: Int,
        format: (Int) -> String,
        onChange: (Int) -> Unit,
    ) {
        val title = TextView(this).apply {
            text = "$label: ${format(value)}"
            setPadding(0, dp(8), 0, 0)
        }
        val bar = SeekBar(this).apply {
            this.max = max - min
            progress = (value - min).coerceIn(0, max - min)
        }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                val v = p + min
                title.text = "$label: ${format(v)}"
                if (fromUser) onChange(v)
            }

            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        root.addView(title)
        root.addView(bar)
    }

    private fun addColorRow(label: String, color: Int, withAlpha: Boolean, onPicked: (Int) -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        val swatchBg = GradientDrawable().apply {
            cornerRadius = dp(6).toFloat()
            setColor(color)
            setStroke(dp(1), Color.GRAY)
        }
        val swatch = View(this).apply { background = swatchBg }
        row.addView(swatch, LinearLayout.LayoutParams(dp(40), dp(40)).apply { rightMargin = dp(12) })
        var currentColor = color
        row.addView(Button(this).apply {
            text = label
            setOnClickListener {
                ColorPicker.show(this@MainActivity, label, currentColor, withAlpha) { picked ->
                    currentColor = picked
                    swatchBg.setColor(picked)
                    onPicked(picked)
                }
            }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row)
    }
}
