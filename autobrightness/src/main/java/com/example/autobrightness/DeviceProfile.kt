package com.example.autobrightness

import android.content.Context
import android.content.res.Resources
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import com.example.autobrightness.core.BrightnessCurve
import com.example.autobrightness.core.BrightnessRange

/**
 * Автоматичне визначення характеристик конкретного телефона при першому ж запуску:
 * діапазон системної яскравості (у Xiaomi/Redmi це не 0–255, а до 2047/4095 і т.п.),
 * крива автояскравості від виробника, параметри датчиків.
 */
class DeviceProfile private constructor(
    val range: BrightnessRange,
    val curve: BrightnessCurve,
    val curveFromManufacturer: Boolean,
    val lightSensor: Sensor?,
    val proximitySensor: Sensor?,
) {
    val lightMaxRange: Float get() = lightSensor?.maximumRange ?: 0f

    fun describe(): String = buildString {
        append("${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}\n")
        append("Діапазон яскравості: ${range.min}–${range.max}\n")
        append(
            "Датчик світла: " + (lightSensor?.let { "${it.name} (до ${it.maximumRange.toInt()} лк)" }
                ?: "відсутній") + "\n"
        )
        append("Датчик наближення: " + (proximitySensor?.name ?: "відсутній") + "\n")
        append(
            "Крива: " + if (curveFromManufacturer) "від виробника + запас читабельності"
            else "універсальна (з запасом читабельності)"
        )
    }

    companion object {
        fun detect(context: Context): DeviceProfile {
            val sys = Resources.getSystem()
            var min = sysInt(sys, "config_screenBrightnessSettingMinimum") ?: 1
            var max = sysInt(sys, "config_screenBrightnessSettingMaximum") ?: 255
            if (max <= 0) max = 255
            if (min < 1) min = 1
            if (min >= max) min = 1

            // Деякі прошивки (MIUI/HyperOS) мають більший діапазон, ніж повідомляє ресурс.
            val current = BrightnessSettings.readBrightness(context)
            if (current != null && current > max) {
                max = when {
                    current <= 1023 -> 1023
                    current <= 2047 -> 2047
                    current <= 4095 -> 4095
                    else -> 8191
                }
            }

            val levels = sysIntArray(sys, "config_autoBrightnessLevels")
            val values = sysIntArray(sys, "config_autoBrightnessLcdBacklightValues")
            val deviceCurve = if (levels != null && values != null) {
                BrightnessCurve.fromDeviceConfig(levels, values, 255)
            } else null

            val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            return DeviceProfile(
                range = BrightnessRange(min, max),
                curve = deviceCurve ?: BrightnessCurve.DEFAULT,
                curveFromManufacturer = deviceCurve != null,
                lightSensor = sm.getDefaultSensor(Sensor.TYPE_LIGHT),
                proximitySensor = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY),
            )
        }

        private fun sysInt(res: Resources, name: String): Int? = try {
            val id = res.getIdentifier(name, "integer", "android")
            if (id != 0) res.getInteger(id) else null
        } catch (e: Exception) {
            null
        }

        private fun sysIntArray(res: Resources, name: String): IntArray? = try {
            val id = res.getIdentifier(name, "array", "android")
            if (id != 0) res.getIntArray(id).takeIf { it.isNotEmpty() } else null
        } catch (e: Exception) {
            null
        }
    }
}
