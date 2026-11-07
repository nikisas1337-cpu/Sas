package com.example.batteryoverlay

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** Готові рядки для показу. */
data class Readout(val powerText: String, val timeText: String)

/**
 * Зчитує струм, напругу та заряд акумулятора й рахує:
 *  - миттєву потужність (Вт), яку зараз споживає телефон;
 *  - точний час (год:хв:с), за який батарея розрядиться при поточному споживанні
 *    (або час до повного заряду, якщо телефон заряджається).
 */
class BatteryMonitor(context: Context) {

    private val ctx = context.applicationContext
    private val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    private val prefs = Prefs.get(ctx)
    private val designCapacityMicroAh: Long? by lazy { readDesignCapacity() }

    /** Згладжений струм для розрахунку часу, мкА (+ розряд, − заряд). */
    private var smoothedMicroA: Double? = null
    private var lastPlugged: Boolean? = null

    fun update(): Readout {
        val batt = ContextCompat.registerReceiver(
            ctx, null, IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        val level = batt?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batt?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val percent = if (level >= 0 && scale > 0) level * 100f / scale
        else bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).toFloat()
        val status = batt?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = (batt?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val voltageMv = normalizeVoltage(batt?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0)

        // Миттєвий струм. Більшість телефонів віддає мкА, деякі — мА.
        val raw = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val currentMicroA: Long? = when {
            raw == Int.MIN_VALUE || raw == 0 -> null
            abs(raw) < 10_000 -> raw.toLong() * 1000
            else -> raw.toLong()
        }

        // Знак розряду різний у різних виробників: запам'ятовуємо його, коли зарядка відключена.
        if (currentMicroA != null && !plugged) {
            val sign = if (currentMicroA < 0) -1 else 1
            if (prefs.getInt(Prefs.KEY_DISCHARGE_SIGN, -1) != sign) {
                prefs.edit().putInt(Prefs.KEY_DISCHARGE_SIGN, sign).apply()
            }
        }
        val dischargeSign = prefs.getInt(Prefs.KEY_DISCHARGE_SIGN, -1)
        // > 0 — батарея розряджається, < 0 — заряджається.
        val dischargeMicroA = currentMicroA?.let { it * dischargeSign }

        if (lastPlugged != plugged) smoothedMicroA = null
        lastPlugged = plugged
        if (dischargeMicroA != null) {
            val s = smoothedMicroA
            smoothedMicroA = if (s == null) dischargeMicroA.toDouble()
            else s + SMOOTHING * (dischargeMicroA - s)
        }

        // Заряд, що залишився, та повна ємність, мкА·год.
        var chargeMicroAh: Long? = null
        var fullMicroAh: Long? = designCapacityMicroAh
        val cc = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        if (cc != Int.MIN_VALUE && cc > 0 && percent > 0) {
            var counter = cc.toLong()
            // Якщо значення схоже на мА·год — переводимо в мкА·год.
            if (counter * 100 / percent < 500_000) counter *= 1000
            chargeMicroAh = counter
            fullMicroAh = (counter * 100 / percent).toLong()
        } else if (designCapacityMicroAh != null) {
            chargeMicroAh = (designCapacityMicroAh!! * percent / 100).toLong()
        }

        val isFull = status == BatteryManager.BATTERY_STATUS_FULL || (plugged && percent >= 100f)
        return Readout(
            powerText = formatPower(dischargeMicroA, voltageMv),
            timeText = formatTime(isFull, percent, chargeMicroAh, fullMicroAh),
        )
    }

    private fun formatPower(dischargeMicroA: Long?, voltageMv: Int): String {
        if (dischargeMicroA == null) return "⚡ — (датчик струму недоступний)"
        val ma = abs(dischargeMicroA) / 1000
        val sign = if (dischargeMicroA >= 0) "⚡ " else "🔌 +"
        if (voltageMv <= 0) return String.format(Locale.getDefault(), "%s%d мА", sign, ma)
        val watts = abs(dischargeMicroA).toDouble() * voltageMv / 1e9
        return String.format(Locale.getDefault(), "%s%.2f Вт · %d мА", sign, watts, ma)
    }

    private fun formatTime(isFull: Boolean, percent: Float, chargeMicroAh: Long?, fullMicroAh: Long?): String {
        val pct = percent.toInt()
        if (isFull) return "🔋 Заряджено ($pct%)"
        val current = smoothedMicroA
        if (current == null || chargeMicroAh == null) return "⏳ — ($pct%)"
        if (abs(current) < 1000) return "⏳ ∞ ($pct%)"
        return if (current > 0) {
            val seconds = (chargeMicroAh / current * 3600).toLong()
            "⏳ ${formatDuration(seconds)} · до ${formatEnd(seconds)} ($pct%)"
        } else {
            val full = fullMicroAh ?: return "🔌 Заряджається ($pct%)"
            val seconds = ((full - chargeMicroAh).coerceAtLeast(0L) / -current * 3600).toLong()
            "🔌 До 100%: ${formatDuration(seconds)} · о ${formatEnd(seconds)}"
        }
    }

    private fun normalizeVoltage(v: Int): Int = when {
        v <= 0 -> 0
        v < 10 -> v * 1000          // вольти
        v > 100_000 -> v / 1000     // мікровольти
        else -> v                   // мілівольти
    }

    private fun readDesignCapacity(): Long? = try {
        val cls = Class.forName("com.android.internal.os.PowerProfile")
        val profile = cls.getConstructor(Context::class.java).newInstance(ctx)
        val mah = cls.getMethod("getBatteryCapacity").invoke(profile) as Double
        if (mah > 100) (mah * 1000).toLong() else null
    } catch (t: Throwable) {
        null
    }

    companion object {
        private const val SMOOTHING = 0.3

        fun formatDuration(totalSeconds: Long): String {
            if (totalSeconds > 99L * 86400) return "∞"
            val d = totalSeconds / 86400
            val h = totalSeconds % 86400 / 3600
            val m = totalSeconds % 3600 / 60
            val s = totalSeconds % 60
            return if (d > 0) String.format(Locale.getDefault(), "%d д %d год %02d хв %02d с", d, h, m, s)
            else String.format(Locale.getDefault(), "%d год %02d хв %02d с", h, m, s)
        }

        fun formatEnd(secondsFromNow: Long): String {
            val end = Date(System.currentTimeMillis() + secondsFromNow * 1000)
            val now = Calendar.getInstance()
            val then = Calendar.getInstance().apply { time = end }
            val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
                now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
            val pattern = if (sameDay) "HH:mm:ss" else "dd.MM HH:mm"
            return SimpleDateFormat(pattern, Locale.getDefault()).format(end)
        }
    }
}
