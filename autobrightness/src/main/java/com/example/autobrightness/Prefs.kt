package com.example.autobrightness

import android.content.Context
import com.example.autobrightness.core.LearnedCorrection

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("auto_brightness", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    /** Загальний зсув «темніше ↔ яскравіше», перцептивні одиниці −0.3..+0.3. */
    var userOffset: Float
        get() = sp.getFloat("offset", 0f)
        set(v) = sp.edit().putFloat("offset", v).apply()

    /** На яскравому сонці передавати керування системі, щоб увімкнувся режим високої яскравості. */
    var sunlightBoost: Boolean
        get() = sp.getBoolean("sunlight", true)
        set(v) = sp.edit().putBoolean("sunlight", v).apply()

    var learning: Boolean
        get() = sp.getBoolean("learning", true)
        set(v) = sp.edit().putBoolean("learning", v).apply()

    var correction: LearnedCorrection
        get() = LearnedCorrection.parse(sp.getString("correction", null))
        set(v) = sp.edit().putString("correction", v.serialize()).apply()

    /** Режим яскравості, що був до вмикання програми (щоб повернути його при вимкненні). */
    var originalMode: Int
        get() = sp.getInt("original_mode", -1)
        set(v) = sp.edit().putInt("original_mode", v).apply()

    var lastLux: Float
        get() = sp.getFloat("last_lux", -1f)
        set(v) = sp.edit().putFloat("last_lux", v).apply()
}
