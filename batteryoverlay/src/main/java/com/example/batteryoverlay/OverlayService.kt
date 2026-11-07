package com.example.batteryoverlay

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlin.math.hypot

/**
 * Сервіс, що малює два незалежні написи поверх усіх застосунків
 * і оновлює їх щосекунди.
 */
class OverlayService : Service(), SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var wm: WindowManager
    private lateinit var monitor: BatteryMonitor
    private val handler = Handler(Looper.getMainLooper())

    private val views = mutableMapOf<OverlayItem, OverlayTextView>()
    private val params = mutableMapOf<OverlayItem, WindowManager.LayoutParams>()

    private var editLayer: View? = null
    private var editItem: OverlayItem? = null
    private var ticking = false

    private val tick = object : Runnable {
        override fun run() {
            updateTexts()
            handler.postDelayed(this, UPDATE_MS)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> stopTicking()
                Intent.ACTION_SCREEN_ON -> startTicking()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        goForeground()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        isRunning = true
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        monitor = BatteryMonitor(this)

        OverlayItem.values().forEach { addOverlay(it) }
        Prefs.get(this).registerOnSharedPreferenceChangeListener(this)

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        startTicking()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        if (!isRunning) return START_NOT_STICKY
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_EDIT -> {
                val id = intent.getStringExtra(EXTRA_ITEM)
                OverlayItem.values().firstOrNull { it.id == id }?.let { enterEditMode(it) }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (isRunning) {
            stopTicking()
            Prefs.get(this).unregisterOnSharedPreferenceChangeListener(this)
            try {
                unregisterReceiver(screenReceiver)
            } catch (_: Exception) {
            }
            exitEditMode()
            views.values.forEach { safeRemove(it) }
            views.clear()
            params.clear()
        }
        isRunning = false
        super.onDestroy()
    }

    // ---------------------------------------------------------------- foreground

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Оверлей батареї", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Показ споживання енергії поверх екрана")
            .setContentIntent(openApp)
            .addAction(0, "Зупинити", stop)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    // ---------------------------------------------------------------- updates

    private fun startTicking() {
        if (ticking) return
        ticking = true
        handler.post(tick)
    }

    private fun stopTicking() {
        ticking = false
        handler.removeCallbacks(tick)
    }

    private fun updateTexts() {
        val r = monitor.update()
        views[OverlayItem.POWER]?.text = r.powerText
        views[OverlayItem.TIME]?.text = r.timeText
    }

    // ---------------------------------------------------------------- windows

    private val overlayType: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    @SuppressLint("ClickableViewAccessibility")
    private fun addOverlay(item: OverlayItem) {
        val view = OverlayTextView(this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            BASE_FLAGS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        views[item] = view
        params[item] = lp
        view.setOnTouchListener(GestureController(item, inEditLayer = false))
        applyConfig(item, addWindow = true)
    }

    /** Застосовує збережені налаштування пункту до його вікна. */
    private fun applyConfig(item: OverlayItem, addWindow: Boolean = false) {
        val view = views[item] ?: return
        val lp = params[item] ?: return
        val cfg = Prefs.load(this, item)
        view.textSizeSp = cfg.sizeSp
        view.stretchX = cfg.stretchX
        view.stretchY = cfg.stretchY
        view.textColor = cfg.textColor
        view.bgColor = cfg.bgColor
        view.visibility = if (cfg.enabled) View.VISIBLE else View.GONE
        lp.x = cfg.x
        lp.y = cfg.y

        val locked = Prefs.isLocked(this)
        lp.flags = if (locked) BASE_FLAGS or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else BASE_FLAGS
        // Android 12+ пропускає дотики крізь чуже вікно лише якщо його непрозорість ≤ 0.8
        lp.alpha = if (locked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 0.8f else 1f

        try {
            if (addWindow) wm.addView(view, lp) else wm.updateViewLayout(view, lp)
        } catch (_: Exception) {
        }
    }

    override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
        if (key == null || key == Prefs.KEY_LOCKED) {
            OverlayItem.values().forEach { applyConfig(it) }
            return
        }
        Prefs.itemForKey(key)?.let { applyConfig(it) }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Після повороту екрана повертаємо написи в межі екрана
        handler.postDelayed({
            OverlayItem.values().forEach { item ->
                val lp = params[item] ?: return@forEach
                if (clamp(item, lp)) {
                    safeUpdate(views[item], lp)
                    saveGeometry(item)
                }
            }
        }, 300)
    }

    private fun screenSize(): Pair<Int, Int> {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        return Pair(dm.widthPixels, dm.heightPixels)
    }

    /** Тримає напис у межах екрана. Повертає true, якщо позицію змінено. */
    private fun clamp(item: OverlayItem, lp: WindowManager.LayoutParams): Boolean {
        val view = views[item] ?: return false
        val (sw, sh) = screenSize()
        val (w, h) = view.desiredSize()
        val nx = lp.x.coerceIn(0, (sw - w).coerceAtLeast(0))
        val ny = lp.y.coerceIn(0, (sh - h).coerceAtLeast(0))
        val changed = nx != lp.x || ny != lp.y
        lp.x = nx
        lp.y = ny
        return changed
    }

    private fun saveGeometry(item: OverlayItem) {
        val view = views[item] ?: return
        val lp = params[item] ?: return
        Prefs.saveGeometry(this, item, lp.x, lp.y, view.textSizeSp, view.stretchX, view.stretchY)
    }

    private fun safeUpdate(view: View?, lp: WindowManager.LayoutParams) {
        if (view == null) return
        try {
            wm.updateViewLayout(view, lp)
        } catch (_: Exception) {
        }
    }

    private fun safeRemove(view: View) {
        try {
            wm.removeView(view)
        } catch (_: Exception) {
        }
    }

    private fun openSettings() {
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    // ---------------------------------------------------------------- edit mode

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    /**
     * Повноекранний шар для зручного редагування: жести можна робити будь-де на екрані,
     * навіть якщо сам напис дуже маленький.
     */
    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    private fun enterEditMode(item: OverlayItem) {
        exitEditMode()
        val ctx = ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault)
        val layer = FrameLayout(ctx).apply { setBackgroundColor(0x33000000) }
        val hint = TextView(ctx).apply {
            text = "Редагування: «${item.title}»\n" +
                "• один палець — перемістити\n" +
                "• щипок двома пальцями — збільшити / зменшити\n" +
                "• розвести пальці горизонтально — розтягнути по ширині\n" +
                "• розвести пальці вертикально — розтягнути по висоті\n" +
                "• подвійний дотик або «Готово» — завершити"
            setTextColor(Color.WHITE)
            setBackgroundColor(0xCC000000.toInt())
            setPadding(dp(16), dp(36), dp(16), dp(12))
            textSize = 14f
        }
        layer.addView(
            hint,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        )
        val done = Button(ctx).apply {
            text = "Готово"
            setOnClickListener { handler.post { exitEditMode() } }
        }
        layer.addView(
            done,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply { bottomMargin = dp(64) }
        )
        layer.setOnTouchListener(GestureController(item, inEditLayer = true))

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            BASE_FLAGS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        try {
            wm.addView(layer, lp)
        } catch (_: Exception) {
            return
        }
        editLayer = layer
        editItem = item
        views[item]?.highlighted = true
    }

    private fun exitEditMode() {
        editLayer?.let { safeRemove(it) }
        editLayer = null
        editItem?.let {
            views[it]?.highlighted = false
            saveGeometry(it)
        }
        editItem = null
    }

    // ---------------------------------------------------------------- gestures

    /**
     * Обробка дотиків:
     *  - перетягування одним пальцем — переміщення;
     *  - щипок — зміна розміру; горизонтальний / вертикальний щипок — розтягування;
     *  - довгий дотик — режим редагування; подвійний дотик — налаштування.
     */
    private inner class GestureController(
        private val item: OverlayItem,
        private val inEditLayer: Boolean,
    ) : View.OnTouchListener {

        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false
        private var multiTouch = false
        private var scaleMode = MODE_UNIFORM
        private val touchSlop = ViewConfiguration.get(this@OverlayService).scaledTouchSlop

        private val scaleDetector = ScaleGestureDetector(
            this@OverlayService,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
                    val sx = d.currentSpanX
                    val sy = d.currentSpanY
                    scaleMode = when {
                        sx > sy * 2.5f -> MODE_HORIZONTAL
                        sy > sx * 2.5f -> MODE_VERTICAL
                        else -> MODE_UNIFORM
                    }
                    return true
                }

                override fun onScale(d: ScaleGestureDetector): Boolean {
                    val view = views[item] ?: return false
                    val lp = params[item] ?: return false
                    val (oldW, oldH) = view.desiredSize()
                    when (scaleMode) {
                        MODE_HORIZONTAL -> if (d.previousSpanX > 1f)
                            view.stretchX = view.stretchX * d.currentSpanX / d.previousSpanX
                        MODE_VERTICAL -> if (d.previousSpanY > 1f)
                            view.stretchY = view.stretchY * d.currentSpanY / d.previousSpanY
                        else -> view.textSizeSp = view.textSizeSp * d.scaleFactor
                    }
                    // Масштабуємо відносно центру напису
                    val (newW, newH) = view.desiredSize()
                    lp.x -= (newW - oldW) / 2
                    lp.y -= (newH - oldH) / 2
                    clamp(item, lp)
                    safeUpdate(view, lp)
                    return true
                }
            }
        ).apply { isQuickScaleEnabled = false }

        private val gestureDetector = GestureDetector(
            this@OverlayService,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (inEditLayer) handler.post { exitEditMode() } else openSettings()
                    return true
                }

                override fun onLongPress(e: MotionEvent) {
                    if (!inEditLayer && !dragging && !multiTouch) handler.post { enterEditMode(item) }
                }
            }
        )

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            scaleDetector.onTouchEvent(e)
            gestureDetector.onTouchEvent(e)
            val lp = params[item] ?: return true
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = e.rawX
                    downRawY = e.rawY
                    startX = lp.x
                    startY = lp.y
                    dragging = false
                    multiTouch = false
                }
                MotionEvent.ACTION_POINTER_DOWN -> multiTouch = true
                MotionEvent.ACTION_MOVE -> if (!multiTouch && e.pointerCount == 1) {
                    val dx = e.rawX - downRawX
                    val dy = e.rawY - downRawY
                    if (!dragging && hypot(dx, dy) > touchSlop) dragging = true
                    if (dragging) {
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        clamp(item, lp)
                        safeUpdate(views[item], lp)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging || multiTouch) saveGeometry(item)
                    dragging = false
                    multiTouch = false
                }
            }
            return true
        }
    }

    companion object {
        const val ACTION_STOP = "com.example.batteryoverlay.STOP"
        const val ACTION_EDIT = "com.example.batteryoverlay.EDIT"
        const val EXTRA_ITEM = "item"

        private const val CHANNEL_ID = "overlay_channel"
        private const val NOTIFICATION_ID = 42
        private const val UPDATE_MS = 1000L

        private const val MODE_UNIFORM = 0
        private const val MODE_HORIZONTAL = 1
        private const val MODE_VERTICAL = 2

        private const val BASE_FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

        @Volatile
        var isRunning = false
            private set
    }
}
