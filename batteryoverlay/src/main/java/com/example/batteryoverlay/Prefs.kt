package com.example.batteryoverlay

import android.content.Context
import android.content.SharedPreferences

/** Два незалежні написи в оверлеї. Кожен має власні налаштування. */
enum class OverlayItem(
    val id: String,
    val title: String,
    val defaultTextColor: Int,
    val defaultYdp: Int,
) {
    POWER("power", "Споживання зараз", 0xFFFFEB3B.toInt(), 120),
    TIME("time", "Розрахунковий час роботи", 0xFF69F0AE.toInt(), 180),
}

data class ItemConfig(
    val enabled: Boolean,
    val x: Int,
    val y: Int,
    val sizeSp: Float,
    val stretchX: Float,
    val stretchY: Float,
    val textColor: Int,
    val bgColor: Int,
)

object Prefs {
    private const val FILE = "overlay_settings"
    const val KEY_LOCKED = "locked"
    const val KEY_DISCHARGE_SIGN = "discharge_sign"

    const val ENABLED = "enabled"
    const val X = "x"
    const val Y = "y"
    const val SIZE = "size"
    const val STRETCH_X = "stretch_x"
    const val STRETCH_Y = "stretch_y"
    const val TEXT_COLOR = "text_color"
    const val BG_COLOR = "bg_color"
    private val ALL = listOf(ENABLED, X, Y, SIZE, STRETCH_X, STRETCH_Y, TEXT_COLOR, BG_COLOR)

    const val MIN_SIZE = 6f
    const val MAX_SIZE = 160f
    const val MIN_STRETCH = 0.3f
    const val MAX_STRETCH = 4f

    private const val DEFAULT_SIZE = 18f
    private const val DEFAULT_BG = 0x66000000

    fun get(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun key(item: OverlayItem, name: String) = "${item.id}_$name"

    /** Повертає пункт, якому належить ключ налаштувань, або null. */
    fun itemForKey(key: String?): OverlayItem? =
        OverlayItem.values().firstOrNull { key != null && key.startsWith(it.id + "_") }

    fun load(context: Context, item: OverlayItem): ItemConfig {
        val p = get(context)
        val density = context.resources.displayMetrics.density
        return ItemConfig(
            enabled = p.getBoolean(key(item, ENABLED), true),
            x = p.getInt(key(item, X), (16 * density).toInt()),
            y = p.getInt(key(item, Y), (item.defaultYdp * density).toInt()),
            sizeSp = p.getFloat(key(item, SIZE), DEFAULT_SIZE).coerceIn(MIN_SIZE, MAX_SIZE),
            stretchX = p.getFloat(key(item, STRETCH_X), 1f).coerceIn(MIN_STRETCH, MAX_STRETCH),
            stretchY = p.getFloat(key(item, STRETCH_Y), 1f).coerceIn(MIN_STRETCH, MAX_STRETCH),
            textColor = p.getInt(key(item, TEXT_COLOR), item.defaultTextColor),
            bgColor = p.getInt(key(item, BG_COLOR), DEFAULT_BG),
        )
    }

    fun saveGeometry(
        context: Context, item: OverlayItem,
        x: Int, y: Int, sizeSp: Float, stretchX: Float, stretchY: Float,
    ) {
        get(context).edit()
            .putInt(key(item, X), x)
            .putInt(key(item, Y), y)
            .putFloat(key(item, SIZE), sizeSp)
            .putFloat(key(item, STRETCH_X), stretchX)
            .putFloat(key(item, STRETCH_Y), stretchY)
            .apply()
    }

    fun reset(context: Context, item: OverlayItem) {
        val e = get(context).edit()
        ALL.forEach { e.remove(key(item, it)) }
        e.apply()
    }

    fun isLocked(context: Context) = get(context).getBoolean(KEY_LOCKED, false)
}
