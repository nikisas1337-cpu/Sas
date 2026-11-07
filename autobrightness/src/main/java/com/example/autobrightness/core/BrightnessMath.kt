package com.example.autobrightness.core

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Перетворення між «перцептивною» яскравістю (те, що бачить людина і що показує
 * повзунок Android 9+) та лінійним значенням підсвітки.
 *
 * Використовується та сама HLG-крива, що й у AOSP (BrightnessUtils), тож відсотки
 * у програмі збігаються з положенням системного повзунка.
 */
object Gamma {
    private const val R = 0.5f
    private const val A = 0.17883277f
    private const val B = 0.28466892f
    private const val C = 0.55991073f

    /** Перцептивне 0..1 → лінійне 0..1. */
    fun toLinear(perceptual: Float): Float {
        val v = perceptual.coerceIn(0f, 1f)
        val ret = if (v <= R) (v / R).pow(2) else exp((v - C) / A) + B
        return ret.coerceIn(0f, 12f) / 12f
    }

    /** Лінійне 0..1 → перцептивне 0..1. */
    fun toPerceptual(linear: Float): Float {
        val v = linear.coerceIn(0f, 1f) * 12f
        val ret = if (v <= 1f) sqrt(v) * R else A * ln(v - B) + C
        return ret.coerceIn(0f, 1f)
    }
}

/** Освітленість переводиться в логарифмічну шкалу: так людське око сприймає світло. */
internal fun luxToX(lux: Float): Float = log10(lux.coerceAtLeast(0f) + 1f)

/**
 * Крива «освітленість (лк) → перцептивна яскравість 0..1».
 * Інтерполяція лінійна в логарифмічній шкалі освітленості.
 */
class BrightnessCurve(points: List<Pair<Float, Float>>) {
    private val xs: FloatArray
    private val ys: FloatArray

    init {
        require(points.size >= 2) { "Потрібно щонайменше дві точки" }
        val sorted = points.sortedBy { it.first }
        xs = FloatArray(sorted.size) { luxToX(sorted[it].first) }
        // Крива має бути неспадною: більше світла — не менша яскравість.
        var prev = 0f
        ys = FloatArray(sorted.size) {
            prev = maxOf(prev, sorted[it].second.coerceIn(0f, 1f))
            prev
        }
    }

    fun at(lux: Float): Float {
        val x = luxToX(lux)
        if (x <= xs.first()) return ys.first()
        if (x >= xs.last()) return ys.last()
        var i = 1
        while (xs[i] < x) i++
        val t = (x - xs[i - 1]) / (xs[i] - xs[i - 1])
        return ys[i - 1] + (ys[i] - ys[i - 1]) * t
    }

    companion object {
        /**
         * Універсальна крива, налаштована на читабельність (з запасом яскравості):
         * темна кімната ≈ 7 ніт, вітальня ≈ 90 ніт, офіс ≈ 180 ніт,
         * біля вікна ≈ 300 ніт, вулиця/сонце — максимум.
         */
        val DEFAULT = BrightnessCurve(
            listOf(
                0f to 0.20f,
                3f to 0.32f,
                10f to 0.43f,
                30f to 0.55f,
                80f to 0.66f,
                200f to 0.76f,
                450f to 0.83f,
                1000f to 0.89f,
                2000f to 0.93f,
                5000f to 0.98f,
                12000f to 1.00f,
            )
        )

        /** Наскільки крива виробника може бути темнішою за універсальну. */
        private const val READABILITY_MARGIN = 0.08f

        /**
         * Крива з конфігурації виробника (config_autoBrightnessLevels +
         * config_autoBrightnessLcdBacklightValues). Значення підсвітки — 0..[backlightMax].
         * Повертає null, якщо дані відсутні або некоректні.
         */
        fun fromDeviceConfig(
            luxLevels: IntArray,
            backlight: IntArray,
            backlightMax: Int = 255,
        ): BrightnessCurve? {
            if (luxLevels.size < 2 || backlight.size != luxLevels.size + 1) return null
            for (i in 1 until luxLevels.size) if (luxLevels[i] <= luxLevels[i - 1]) return null
            if (luxLevels.first() < 0 || backlightMax <= 0) return null
            if (backlight.any { it < 0 || it > backlightMax }) return null
            val p = { v: Int -> Gamma.toPerceptual(v.toFloat() / backlightMax) }
            val points = ArrayList<Pair<Float, Float>>()
            points += 0f to p(backlight[0])
            for (i in luxLevels.indices) {
                val lux = luxLevels[i].toFloat()
                // Крива виробника ступінчаста: зберігаємо сходинку, але згладжуємо інтерполяцією.
                points += lux to p(backlight[i + 1])
            }
            val device = BrightnessCurve(points)
            // Не дозволяємо кривій виробника бути помітно темнішою за універсальну:
            // пріоритет — читабельність.
            val merged = (points.map { it.first } + listOf(3f, 30f, 200f, 1000f, 5000f, 12000f))
                .distinct()
                .map { lux -> lux to maxOf(device.at(lux), DEFAULT.at(lux) - READABILITY_MARGIN) }
            return BrightnessCurve(merged)
        }
    }
}

/**
 * Особисті поправки, яких програма навчається, коли користувач сам змінює яскравість.
 * Зберігаються в кошиках за логарифмом освітленості (0, 0.5, ... 4.5 → 0..31 600 лк).
 */
class LearnedCorrection(initial: FloatArray? = null) {
    val values: FloatArray =
        if (initial != null && initial.size == BINS) initial.copyOf() else FloatArray(BINS)

    fun at(lux: Float): Float = interp(values, luxToX(lux))

    /**
     * Запам'ятати, що при освітленості [lux] користувач хоче яскравість [desired].
     * [base] — яскравість без поправки (крива + загальний зсув) як функція від лк.
     */
    fun learn(lux: Float, desired: Float, base: (Float) -> Float) {
        val x = luxToX(lux)
        val diff = (desired - base(lux)) - at(lux)
        val kernel = FloatArray(BINS) { i ->
            val d = binX(i) - x
            exp(-(d * d) / (2f * SIGMA * SIGMA))
        }
        val kx = interp(kernel, x)
        if (kx > 1e-3f) {
            for (i in 0 until BINS) {
                values[i] = (values[i] + diff / kx * kernel[i]).coerceIn(-LIMIT, LIMIT)
            }
        }
        // Зберігаємо монотонність навколо навченої точки.
        val t = base(lux) + at(lux)
        for (i in 0 until BINS) {
            val lx = binLux(i)
            val total = base(lx) + values[i]
            if (binX(i) > x && total < t) values[i] = (t - base(lx)).coerceIn(-LIMIT, LIMIT)
            if (binX(i) < x && total > t) values[i] = (t - base(lx)).coerceIn(-LIMIT, LIMIT)
        }
    }

    fun reset() = values.fill(0f)

    fun serialize(): String = values.joinToString(",")

    companion object {
        const val BINS = 10
        private const val STEP = 0.5f
        private const val SIGMA = 0.6f
        private const val LIMIT = 0.6f

        fun binX(i: Int) = i * STEP
        fun binLux(i: Int): Float = 10f.pow(binX(i)) - 1f

        fun parse(s: String?): LearnedCorrection {
            val arr = s?.split(',')?.mapNotNull { it.toFloatOrNull() }?.toFloatArray()
            return LearnedCorrection(arr)
        }

        private fun interp(arr: FloatArray, x: Float): Float {
            val pos = (x / STEP).coerceIn(0f, (BINS - 1).toFloat())
            val i = pos.toInt().coerceAtMost(BINS - 2)
            val t = pos - i
            return arr[i] + (arr[i + 1] - arr[i]) * t
        }
    }
}

/** Повна модель: крива пристрою + особисті поправки + загальний зсув користувача. */
class BrightnessModel(
    val curve: BrightnessCurve,
    val correction: LearnedCorrection,
    var userOffset: Float = 0f,
) {
    private fun base(lux: Float) = curve.at(lux) + userOffset

    fun targetFor(lux: Float): Float = (base(lux) + correction.at(lux)).coerceIn(0f, 1f)

    fun learn(lux: Float, desired: Float) = correction.learn(lux, desired, ::base)
}

/** Перетворення перцептивної яскравості у значення системного налаштування і назад. */
class BrightnessRange(val min: Int, val max: Int) {
    init {
        require(max > min) { "max має бути більшим за min" }
    }

    fun toSetting(perceptual: Float): Int {
        val linear = Gamma.toLinear(perceptual)
        return (min + linear * (max - min) + 0.5f).toInt().coerceIn(min, max)
    }

    fun toPerceptual(setting: Int): Float {
        val linear = (setting - min).toFloat() / (max - min)
        return Gamma.toPerceptual(linear)
    }
}
