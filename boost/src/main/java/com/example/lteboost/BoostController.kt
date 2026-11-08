package com.example.lteboost

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.telephony.SubscriptionManager

/**
 * Вмикає та вимикає режим «максимум» і пам'ятає, що саме змінено,
 * щоб при вимкненні повернути все як було.
 */
object BoostController {

    private const val PREFS = "boost"
    private const val KEY_ACTIVE = "active"
    private const val KEY_SUB_ID = "sub_id"
    private const val KEY_PREV_MASK = "prev_mask"
    private const val KEY_WIFI_CHANGED = "wifi_changed"
    private const val KEY_SAVER_CHANGED = "saver_changed"
    const val KEY_OPT_WIFI = "opt_wifi"
    const val KEY_OPT_SAVER = "opt_saver"

    /** Лише LTE (разом з агрегацією несучих). 2G, 3G і 5G заборонені. */
    // Біт мережі = 1 << (тип - 1): LTE = 13, LTE_CA = 19.
    private const val LTE_ONLY_MASK = (1L shl 12) or (1L shl 18)

    /** Усі типи мереж — «автоматичний» режим, якщо попереднє значення прочитати не вдалося. */
    private const val ALL_TYPES_MASK = (1L shl 20) - 1

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isActive(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ACTIVE, false)

    fun option(ctx: Context, key: String): Boolean = prefs(ctx).getBoolean(key, true)

    fun setOption(ctx: Context, key: String, value: Boolean) {
        prefs(ctx).edit().putBoolean(key, value).apply()
    }

    class Report(val ok: Boolean, val lines: List<String>) {
        override fun toString() = lines.joinToString("\n")
    }

    fun toggle(ctx: Context): Report = if (isActive(ctx)) disable(ctx) else enable(ctx)

    fun enable(ctx: Context): Report {
        val p = prefs(ctx)
        val lines = mutableListOf<String>()
        val edit = p.edit()
        var anyOk = false

        // 1. Лише LTE
        val subId = SubscriptionManager.getDefaultDataSubscriptionId()
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            lines += "✗ LTE only: немає SIM-картки для мобільного інтернету"
        } else {
            try {
                val prev = runCatching { Privileged.getAllowedNetworkTypes(subId) }.getOrDefault(ALL_TYPES_MASK)
                if (Privileged.setAllowedNetworkTypes(subId, LTE_ONLY_MASK)) {
                    edit.putInt(KEY_SUB_ID, subId).putLong(KEY_PREV_MASK, prev)
                    lines += "✓ Лише LTE: перехід на 3G/2G/5G заборонено"
                    anyOk = true
                } else {
                    lines += "✗ LTE only: система відхилила зміну"
                }
            } catch (t: Throwable) {
                lines += "✗ LTE only: ${t.message ?: t.javaClass.simpleName}"
            }
        }

        // 2. Вимкнути Wi-Fi, щоб увесь трафік ішов через 4G
        if (option(ctx, KEY_OPT_WIFI)) {
            val wm = ctx.applicationContext.getSystemService(WifiManager::class.java)
            if (wm.isWifiEnabled) {
                try {
                    if (Privileged.setWifiEnabled(false)) {
                        edit.putBoolean(KEY_WIFI_CHANGED, true)
                        lines += "✓ Wi-Fi вимкнено, увесь трафік іде через 4G"
                        anyOk = true
                    } else {
                        lines += "✗ Wi-Fi: система відхилила зміну"
                    }
                } catch (t: Throwable) {
                    lines += "✗ Wi-Fi: ${t.message ?: t.javaClass.simpleName}"
                }
            }
        }

        // 3. Вимкнути енергозбереження, яке обмежує фонові дані та процесор
        if (option(ctx, KEY_OPT_SAVER)) {
            val pm = ctx.getSystemService(PowerManager::class.java)
            if (pm.isPowerSaveMode) {
                try {
                    if (Privileged.setPowerSaveMode(false)) {
                        edit.putBoolean(KEY_SAVER_CHANGED, true)
                        lines += "✓ Енергозбереження вимкнено"
                        anyOk = true
                    } else {
                        lines += "✗ Енергозбереження: система відхилила зміну"
                    }
                } catch (t: Throwable) {
                    lines += "✗ Енергозбереження: ${t.message ?: t.javaClass.simpleName}"
                }
            }
        }

        if (anyOk) edit.putBoolean(KEY_ACTIVE, true)
        edit.apply()
        return Report(anyOk, lines)
    }

    fun disable(ctx: Context): Report {
        val p = prefs(ctx)
        val lines = mutableListOf<String>()

        if (p.contains(KEY_PREV_MASK)) {
            val subId = p.getInt(KEY_SUB_ID, SubscriptionManager.getDefaultDataSubscriptionId())
            try {
                Privileged.setAllowedNetworkTypes(subId, p.getLong(KEY_PREV_MASK, ALL_TYPES_MASK))
                lines += "✓ Типи мереж повернуто як було"
            } catch (t: Throwable) {
                lines += "✗ Типи мереж: ${t.message ?: t.javaClass.simpleName}"
            }
        }
        if (p.getBoolean(KEY_WIFI_CHANGED, false)) {
            try {
                Privileged.setWifiEnabled(true)
                lines += "✓ Wi-Fi увімкнено назад"
            } catch (t: Throwable) {
                lines += "✗ Wi-Fi: ${t.message ?: t.javaClass.simpleName}"
            }
        }
        if (p.getBoolean(KEY_SAVER_CHANGED, false)) {
            try {
                Privileged.setPowerSaveMode(true)
                lines += "✓ Енергозбереження увімкнено назад"
            } catch (t: Throwable) {
                lines += "✗ Енергозбереження: ${t.message ?: t.javaClass.simpleName}"
            }
        }

        p.edit()
            .remove(KEY_ACTIVE).remove(KEY_SUB_ID).remove(KEY_PREV_MASK)
            .remove(KEY_WIFI_CHANGED).remove(KEY_SAVER_CHANGED)
            .apply()
        if (lines.isEmpty()) lines += "Нічого повертати не треба"
        return Report(true, lines)
    }
}
