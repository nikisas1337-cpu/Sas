package com.example.autobrightness

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.example.autobrightness.core.Occlusion
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var stateText: TextView
    private lateinit var luxText: TextView
    private lateinit var brightnessText: TextView
    private lateinit var sensorText: TextView
    private lateinit var messageText: TextView
    private lateinit var permissionButton: Button
    private lateinit var toggleButton: Button
    private lateinit var enableSwitch: SwitchCompat
    private lateinit var offsetSeek: SeekBar
    private lateinit var offsetLabel: TextView
    private lateinit var sunlightSwitch: SwitchCompat
    private lateinit var learningSwitch: SwitchCompat
    private lateinit var deviceText: TextView

    private var updatingUi = false

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val statusListener: (Status) -> Unit = { render(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        setupEdgeToEdge()

        stateText = findViewById(R.id.stateText)
        luxText = findViewById(R.id.luxText)
        brightnessText = findViewById(R.id.brightnessText)
        sensorText = findViewById(R.id.sensorText)
        messageText = findViewById(R.id.messageText)
        permissionButton = findViewById(R.id.permissionButton)
        toggleButton = findViewById(R.id.toggleButton)
        enableSwitch = findViewById(R.id.enableSwitch)
        offsetSeek = findViewById(R.id.offsetSeek)
        offsetLabel = findViewById(R.id.offsetLabel)
        sunlightSwitch = findViewById(R.id.sunlightSwitch)
        learningSwitch = findViewById(R.id.learningSwitch)
        deviceText = findViewById(R.id.deviceText)

        permissionButton.setOnClickListener { openWriteSettings() }
        toggleButton.setOnClickListener {
            if (BrightnessService.instance != null) BrightnessService.stop(this) else enable()
        }

        enableSwitch.setOnCheckedChangeListener { _, checked ->
            if (updatingUi) return@setOnCheckedChangeListener
            if (checked) enable() else BrightnessService.stop(this)
        }

        offsetSeek.max = OFFSET_STEPS * 2
        offsetSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                renderOffset(progress)
                if (fromUser) {
                    prefs.userOffset = (progress - OFFSET_STEPS) / OFFSET_STEPS.toFloat() * MAX_OFFSET
                    BrightnessService.instance?.reloadSettings()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })

        sunlightSwitch.setOnCheckedChangeListener { _, checked ->
            if (updatingUi) return@setOnCheckedChangeListener
            prefs.sunlightBoost = checked
            BrightnessService.instance?.reloadSettings()
        }
        learningSwitch.setOnCheckedChangeListener { _, checked ->
            if (updatingUi) return@setOnCheckedChangeListener
            prefs.learning = checked
            BrightnessService.instance?.reloadSettings()
        }
        findViewById<Button>(R.id.resetButton).setOnClickListener {
            prefs.correction = com.example.autobrightness.core.LearnedCorrection()
            prefs.userOffset = 0f
            BrightnessService.instance?.reloadSettings(resetLearning = true)
            updatingUi = true
            offsetSeek.progress = OFFSET_STEPS
            updatingUi = false
            messageText.text = getString(R.string.msg_reset)
            messageText.visibility = View.VISIBLE
        }
    }

    override fun onResume() {
        super.onResume()
        val canWrite = BrightnessSettings.canWrite(this)
        permissionButton.visibility = if (canWrite) View.GONE else View.VISIBLE
        // Користувач повернувся з екрана дозволу — запускаємо, якщо він цього хотів.
        if (prefs.enabled && canWrite && BrightnessService.instance == null) {
            BrightnessService.start(this)
        }

        updatingUi = true
        offsetSeek.progress =
            (prefs.userOffset / MAX_OFFSET * OFFSET_STEPS + OFFSET_STEPS).roundToInt()
        renderOffset(offsetSeek.progress)
        sunlightSwitch.isChecked = prefs.sunlightBoost
        learningSwitch.isChecked = prefs.learning
        updatingUi = false

        deviceText.text = DeviceProfile.detect(this).describe()
        StatusBus.subscribe(statusListener)
    }

    override fun onPause() {
        StatusBus.unsubscribe(statusListener)
        super.onPause()
    }

    /**
     * Android 15+ малює вміст під системними панелями. Відсуваємо його на висоту
     * рядка стану та панелі навігації, щоб нічого не ховалося.
     */
    private fun setupEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }
        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    private fun enable() {
        prefs.enabled = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (!BrightnessSettings.canWrite(this)) {
            openWriteSettings()
            return
        }
        BrightnessService.start(this)
    }

    private fun openWriteSettings() {
        val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))
        try {
            startActivity(intent)
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun renderOffset(progress: Int) {
        val pct = ((progress - OFFSET_STEPS) / OFFSET_STEPS.toFloat() * MAX_OFFSET * 100).roundToInt()
        offsetLabel.text = getString(R.string.offset_label, if (pct > 0) "+$pct" else "$pct")
    }

    private fun render(s: Status) {
        updatingUi = true
        val on = s.running || (prefs.enabled && BrightnessService.instance != null)
        enableSwitch.isChecked = on
        updatingUi = false
        toggleButton.text = getString(if (on) R.string.turn_off else R.string.turn_on)

        stateText.text = getString(if (s.running) R.string.state_on else R.string.state_off)
        if (s.running && s.lux >= 0f) {
            luxText.text = getString(R.string.lux_value, s.lux.roundToInt(), s.rawLux.roundToInt())
            brightnessText.text = if (s.sunlightMode) getString(R.string.brightness_sun)
            else getString(R.string.brightness_value, s.brightnessPercent)
            sensorText.text = getString(
                when (s.occlusion) {
                    Occlusion.NONE -> R.string.sensor_ok
                    Occlusion.PROXIMITY -> R.string.sensor_covered_proximity
                    Occlusion.SUDDEN_DROP -> R.string.sensor_covered_drop
                }
            )
            sensorText.setTextColor(
                ContextCompat.getColor(
                    this, if (s.occlusion == Occlusion.NONE) R.color.ok else R.color.warn
                )
            )
        } else {
            luxText.text = "—"
            brightnessText.text = "—"
            sensorText.text = "—"
        }
        messageText.text = s.message ?: ""
        messageText.visibility = if (s.message.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    companion object {
        private const val OFFSET_STEPS = 30
        private const val MAX_OFFSET = 0.3f
    }
}
