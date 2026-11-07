package com.example.autobrightness

import android.content.Context
import android.provider.Settings

/** Тонка обгортка над системними налаштуваннями яскравості. */
object BrightnessSettings {
    fun canWrite(context: Context): Boolean = Settings.System.canWrite(context)

    fun readBrightness(context: Context): Int? = try {
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
    } catch (e: Exception) {
        null
    }

    fun readMode(context: Context): Int? = try {
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE)
    } catch (e: Exception) {
        null
    }

    fun writeBrightness(context: Context, value: Int): Boolean = try {
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, value)
    } catch (e: SecurityException) {
        false
    }

    fun writeMode(context: Context, mode: Int): Boolean = try {
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, mode)
    } catch (e: SecurityException) {
        false
    }
}
