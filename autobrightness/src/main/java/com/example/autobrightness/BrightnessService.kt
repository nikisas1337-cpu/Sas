package com.example.autobrightness

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.autobrightness.core.AmbientLightProcessor
import com.example.autobrightness.core.BrightnessModel
import com.example.autobrightness.core.Occlusion
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * Фоновий сервіс: читає датчики, рахує потрібну яскравість і плавно її виставляє.
 */
class BrightnessService : Service(), SensorEventListener {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var profile: DeviceProfile
    private lateinit var model: BrightnessModel
    private lateinit var processor: AmbientLightProcessor
    private lateinit var sensorManager: SensorManager

    private var sensing = false
    private var initialized = false
    private var sunlightBoost = true
    private var learning = true

    /** Поточна (анімована) яскравість і ціль, обидві в перцептивних одиницях 0..1. */
    private var currentP = -1f
    private var targetP = -1f
    private var snapNext = true
    private var lastStepMs = 0L

    /** Після ручної зміни тримаємо значення користувача, доки світло помітно не зміниться. */
    private var holdLux = -1f

    private var lastWritten = -1
    private var sunlightActive = false
    private var expectingAutoMode = false
    private var stoppingByUser = false

    private var lastPublishMs = 0L
    private var lastNotifMs = 0L
    private var lastNotifText = ""

    private val tick = object : Runnable {
        override fun run() {
            step()
            handler.postDelayed(this, TICK_MS)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> startSensing()
                Intent.ACTION_SCREEN_OFF -> stopSensing()
            }
        }
    }

    private val brightnessObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            if (uri == MODE_URI) onModeChanged() else onBrightnessChanged()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        createChannel()
        startForegroundCompat(buildNotification(getString(R.string.notif_starting)))

        if (!BrightnessSettings.canWrite(this)) {
            fail(getString(R.string.msg_no_permission))
            return
        }
        profile = DeviceProfile.detect(this)
        if (profile.lightSensor == null) {
            fail(getString(R.string.msg_no_sensor))
            return
        }
        model = BrightnessModel(profile.curve, prefs.correction, prefs.userOffset)
        processor = AmbientLightProcessor(profile.lightMaxRange)
        sunlightBoost = prefs.sunlightBoost
        learning = prefs.learning

        val mode = BrightnessSettings.readMode(this)
        if (prefs.originalMode == -1 && mode != null) prefs.originalMode = mode
        BrightnessSettings.writeMode(this, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)

        contentResolver.registerContentObserver(BRIGHTNESS_URI, false, brightnessObserver)
        contentResolver.registerContentObserver(MODE_URI, false, brightnessObserver)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        initialized = true

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isInteractive) startSensing()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopByUser()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    /** Вимкнення користувачем: повертаємо режим яскравості, що був до програми. */
    fun stopByUser() {
        prefs.enabled = false
        stoppingByUser = true
        StatusBus.publish(Status())
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (initialized) {
            stopSensing()
            contentResolver.unregisterContentObserver(brightnessObserver)
            unregisterReceiver(screenReceiver)
            if (stoppingByUser) restoreOriginalMode()
        }
        if (instance === this) instance = null
        StatusBus.publish(Status(running = false, message = StatusBus.latest.message))
        super.onDestroy()
    }

    /** Активність змінила налаштування. */
    fun reloadSettings(resetLearning: Boolean = false) {
        if (!initialized) return
        model.userOffset = prefs.userOffset
        sunlightBoost = prefs.sunlightBoost
        learning = prefs.learning
        if (resetLearning) {
            model.correction.reset()
            prefs.correction = model.correction
        }
        if (!sunlightBoost && sunlightActive) exitSunlight()
        holdLux = -1f
        targetP = -1f
    }

    // ---------------------------------------------------------------- датчики

    private fun startSensing() {
        if (sensing || !initialized) return
        sensing = true
        val now = SystemClock.uptimeMillis()
        processor.reset(now, prefs.lastLux)
        snapNext = true
        lastStepMs = now
        sensorManager.registerListener(this, profile.lightSensor, SensorManager.SENSOR_DELAY_NORMAL)
        profile.proximitySensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    private fun stopSensing() {
        if (!sensing) return
        sensing = false
        sensorManager.unregisterListener(this)
        handler.removeCallbacks(tick)
        if (processor.ambientLux >= 0f) prefs.lastLux = processor.ambientLux
    }

    override fun onSensorChanged(event: SensorEvent) {
        val now = SystemClock.uptimeMillis()
        when (event.sensor.type) {
            Sensor.TYPE_LIGHT -> processor.onLux(event.values[0], now)
            Sensor.TYPE_PROXIMITY -> {
                val near = event.values[0] < event.sensor.maximumRange
                processor.onProximity(near, now)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    // ---------------------------------------------------------------- основний цикл

    private fun step() {
        val now = SystemClock.uptimeMillis()
        val dt = (now - lastStepMs).coerceAtLeast(0L) / 1000f
        lastStepMs = now
        processor.update(now)
        val lux = processor.ambientLux
        if (lux < 0f) return

        if (!processor.occluded) {
            if (sunlightBoost && !sunlightActive && lux >= SUN_ENTER_LUX) enterSunlight()
            else if (sunlightActive && lux < SUN_EXIT_LUX) exitSunlight()
        }
        if (sunlightActive) {
            publish(lux)
            return
        }

        if (holdLux >= 0f && abs(log10(lux + 1f) - log10(holdLux + 1f)) > HOLD_RELEASE_LOG) {
            holdLux = -1f
        }
        // Поки датчик прикрито — ціль не змінюємо (яскравість «заморожена»).
        if (targetP < 0f || (!processor.occluded && holdLux < 0f)) {
            val t = model.targetFor(lux)
            if (targetP < 0f || abs(t - targetP) >= TARGET_HYSTERESIS || (t >= 1f && targetP < 1f)) {
                targetP = t
            }
        }

        if (snapNext || currentP < 0f) {
            currentP = targetP
            snapNext = false
        } else if (currentP != targetP) {
            val rate = if (targetP > currentP) RAMP_UP_PER_S else RAMP_DOWN_PER_S
            val delta = rate * dt
            currentP = if (abs(targetP - currentP) <= delta) targetP
            else currentP + if (targetP > currentP) delta else -delta
        }

        val value = profile.range.toSetting(currentP)
        if (value != lastWritten) {
            lastWritten = value
            if (!BrightnessSettings.writeBrightness(this, value)) {
                fail(getString(R.string.msg_no_permission))
                return
            }
        }
        publish(lux)
    }

    private fun enterSunlight() {
        sunlightActive = true
        expectingAutoMode = true
        // В автоматичному режимі система може ввімкнути підвищену яскравість (HBM),
        // недоступну для звичайних програм.
        BrightnessSettings.writeBrightness(this, profile.range.max)
        BrightnessSettings.writeMode(this, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
    }

    private fun exitSunlight() {
        sunlightActive = false
        expectingAutoMode = false
        BrightnessSettings.writeMode(this, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        lastWritten = -1
    }

    // ---------------------------------------------------------------- реакція на користувача

    private fun onBrightnessChanged() {
        if (!initialized || sunlightActive || lastWritten < 0) return
        if (BrightnessSettings.readMode(this) != Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) return
        val v = BrightnessSettings.readBrightness(this) ?: return
        val tolerance = maxOf(1, (profile.range.max - profile.range.min) / 200)
        if (abs(v - lastWritten) <= tolerance) return

        // Користувач сам посунув повзунок — запам'ятовуємо його вподобання.
        val userP = profile.range.toPerceptual(v)
        val lux = processor.ambientLux
        if (learning && lux >= 0f && !processor.occluded) {
            model.learn(lux, userP)
            prefs.correction = model.correction
        }
        lastWritten = v
        currentP = userP
        targetP = userP
        holdLux = if (lux >= 0f) lux else -1f
        StatusBus.publish(
            StatusBus.latest.copy(
                message = getString(if (learning) R.string.msg_learned else R.string.msg_manual_hold)
            )
        )
    }

    private fun onModeChanged() {
        if (!initialized) return
        val mode = BrightnessSettings.readMode(this) ?: return
        if (mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC && !expectingAutoMode) {
            // Користувач увімкнув системну автояскравість — поступаємося їй.
            prefs.enabled = false
            prefs.originalMode = -1
            StatusBus.publish(Status(message = getString(R.string.msg_system_auto)))
            stopSelf()
        } else if (mode == Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL && expectingAutoMode) {
            sunlightActive = false
            expectingAutoMode = false
            lastWritten = -1
        }
    }

    private fun restoreOriginalMode() {
        val mode = prefs.originalMode
        if (mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) {
            expectingAutoMode = true
            BrightnessSettings.writeMode(this, mode)
        } else if (sunlightActive) {
            BrightnessSettings.writeMode(this, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        }
        prefs.originalMode = -1
    }

    private fun fail(message: String) {
        prefs.enabled = false
        StatusBus.publish(Status(message = message))
        stopSelf()
    }

    // ---------------------------------------------------------------- стан і сповіщення

    private fun publish(lux: Float) {
        val now = SystemClock.uptimeMillis()
        if (now - lastPublishMs < PUBLISH_MS) return
        lastPublishMs = now
        val percent = if (sunlightActive) 100 else (currentP * 100f).roundToInt()
        val prev = StatusBus.latest
        StatusBus.publish(
            Status(
                running = true,
                lux = lux,
                rawLux = processor.lastRawLux,
                brightnessPercent = percent,
                occlusion = processor.occlusion,
                sunlightMode = sunlightActive,
                message = prev.message.takeIf { prev.running },
            )
        )

        val text = when {
            processor.occlusion != Occlusion.NONE -> getString(R.string.notif_covered, percent)
            sunlightActive -> getString(R.string.notif_sun, lux.roundToInt())
            else -> getString(R.string.notif_normal, lux.roundToInt(), percent)
        }
        if (text != lastNotifText && now - lastNotifMs >= NOTIF_MS) {
            lastNotifText = text
            lastNotifMs = now
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, BrightnessService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_brightness)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, getString(R.string.action_stop), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val ACTION_STOP = "com.example.autobrightness.STOP"
        private const val CHANNEL_ID = "auto_brightness"
        private const val NOTIFICATION_ID = 1

        private val BRIGHTNESS_URI: Uri = Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS)
        private val MODE_URI: Uri = Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE)

        private const val TICK_MS = 100L
        private const val PUBLISH_MS = 250L
        private const val NOTIF_MS = 3000L

        /** Мінімальна зміна цілі, на яку реагуємо (щоб екран не мерехтів). */
        private const val TARGET_HYSTERESIS = 0.02f

        /** Швидкість анімації в перцептивних одиницях за секунду. */
        private const val RAMP_UP_PER_S = 0.6f
        private const val RAMP_DOWN_PER_S = 0.15f

        /** На скільки (log10 лк) має змінитися світло, щоб скасувати ручне значення. */
        private const val HOLD_RELEASE_LOG = 0.35f

        private const val SUN_ENTER_LUX = 15_000f
        private const val SUN_EXIT_LUX = 6_000f

        var instance: BrightnessService? = null
            private set

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, BrightnessService::class.java))
        }

        fun stop(context: Context) {
            val running = instance
            if (running != null) running.stopByUser() else Prefs(context).enabled = false
        }
    }
}
