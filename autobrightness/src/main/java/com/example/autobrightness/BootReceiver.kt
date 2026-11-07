package com.example.autobrightness

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Перезапускає автояскравість після перезавантаження телефона або оновлення програми. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                if (Prefs(context).enabled && BrightnessSettings.canWrite(context)) {
                    try {
                        BrightnessService.start(context)
                    } catch (e: Exception) {
                        // Система може заборонити запуск у фоні — користувач запустить вручну.
                    }
                }
            }
        }
    }
}
