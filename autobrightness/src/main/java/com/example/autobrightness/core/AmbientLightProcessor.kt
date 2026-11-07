package com.example.autobrightness.core

import kotlin.math.exp
import kotlin.math.ln

/** Чому показання датчика зараз ігноруються. */
enum class Occlusion {
    NONE,

    /** Датчик наближення бачить перешкоду (палець, долоня, кишеня). */
    PROXIMITY,

    /** Різке падіння освітленості майже до нуля — схоже на палець на датчику. */
    SUDDEN_DROP,
}

/**
 * Фільтр показань датчика освітленості.
 *
 * - Згладжує шум у логарифмічній шкалі: світлішає швидко, темніє повільно.
 * - Ігнорує короткі тіні (рука, людина пройшла повз): затемнення починається лише
 *   після того, як світла стало менше протягом [DARKEN_DEBOUNCE_MS].
 * - Розпізнає прикритий пальцем датчик (див. [Occlusion]) і на цей час «заморожує»
 *   освітленість, тому яскравість не провалюється.
 *
 * Усі часи — у мілісекундах монотонного годинника.
 */
class AmbientLightProcessor(sensorMaxRange: Float = 0f) {

    /** Датчики з малим діапазоном насичуються на сонці — тоді вважаємо, що світло яскраве. */
    private val saturationLux: Float =
        if (sensorMaxRange in 1f..SATURATED_LUX) sensorMaxRange * 0.95f else Float.MAX_VALUE

    /** Згладжена освітленість, лк. -1, поки немає даних. */
    var ambientLux: Float = -1f
        private set

    var occlusion: Occlusion = Occlusion.NONE
        private set

    val occluded: Boolean get() = occlusion != Occlusion.NONE

    /** Останнє «сире» значення з датчика (після нормалізації). */
    var lastRawLux: Float = -1f
        private set

    private var lastUpdateMs = 0L
    private var darkenSinceMs = -1L

    private val historyTime = LongArray(HISTORY)
    private val historyLux = FloatArray(HISTORY)
    private var historyCount = 0
    private var historyHead = 0

    private var proximityNear = false
    private var proximityFarSinceMs = Long.MIN_VALUE / 2

    private var suspectSinceMs = -1L
    private var suspectRefLux = 0f

    private var priorLux = -1f

    /**
     * Чи можна порівнювати нове показання зі згладженим значенням. Після того як
     * темряву підтверджено, згладжене значення ще деякий час «наздоганяє» реальне,
     * і порівняння з ним знову хибно вказувало б на палець.
     */
    private var ambientIsReference = true

    /**
     * Скинути стан (наприклад, після вмикання екрана).
     * [lastKnownLux] — освітленість до вимкнення: якщо перше нове показання ≈ 0,
     * а раніше було світло, це, найімовірніше, палець на датчику.
     */
    fun reset(nowMs: Long, lastKnownLux: Float = -1f) {
        ambientLux = -1f
        lastRawLux = -1f
        occlusion = Occlusion.NONE
        darkenSinceMs = -1L
        historyCount = 0
        suspectSinceMs = -1L
        ambientIsReference = true
        proximityNear = false
        proximityFarSinceMs = Long.MIN_VALUE / 2
        lastUpdateMs = nowMs
        priorLux = lastKnownLux
    }

    fun onProximity(near: Boolean, nowMs: Long) {
        update(nowMs)
        if (proximityNear && !near) proximityFarSinceMs = nowMs
        proximityNear = near
        refreshOcclusion(nowMs)
    }

    fun onLux(rawLux: Float, nowMs: Long) {
        val raw = normalize(rawLux)
        update(nowMs)

        if (ambientLux < 0f) {
            // Перше показання після старту / вмикання екрана.
            if (raw < NEAR_ZERO_LUX && priorLux >= MIN_REF_LUX) {
                ambientLux = priorLux
                startSuspect(nowMs, priorLux)
            } else {
                ambientLux = raw
            }
        } else if (suspectSinceMs >= 0) {
            // Палець прибрали — світло повернулося.
            if (raw >= suspectRefLux * RECOVER_RATIO || raw >= ambientLux * RECOVER_RATIO) {
                suspectSinceMs = -1L
            }
        } else if (!proximityNear) {
            val ref = maxOf(recentMax(nowMs), if (ambientIsReference) ambientLux else 0f)
            if (ref >= MIN_REF_LUX && raw <= ref * DROP_RATIO) startSuspect(nowMs, ref)
        }

        lastRawLux = raw
        pushHistory(nowMs, raw)
        refreshOcclusion(nowMs)
    }

    /** Просунути фільтр у часі. Викликати регулярно (наприклад, кожні 100 мс). */
    fun update(nowMs: Long) {
        val dt = (nowMs - lastUpdateMs).coerceAtLeast(0L)
        lastUpdateMs = nowMs
        if (ambientLux < 0f || lastRawLux < 0f) return

        if (suspectSinceMs >= 0) {
            val confirm =
                if (suspectRefLux >= DAYLIGHT_LUX && lastRawLux < NEAR_ZERO_LUX) LONG_CONFIRM_MS
                else SHORT_CONFIRM_MS
            // Темно вже довго — значить, справді вимкнули світло, а не палець.
            if (nowMs - suspectSinceMs >= confirm) {
                suspectSinceMs = -1L
                ambientIsReference = false
            }
        }
        refreshOcclusion(nowMs)
        if (occluded) {
            darkenSinceMs = -1L
            return
        }
        if (dt == 0L) return

        val la = ln(ambientLux + 1f)
        val lr = ln(lastRawLux + 1f)
        val tau: Float
        if (lr >= la) {
            darkenSinceMs = -1L
            ambientIsReference = true
            tau = BRIGHTEN_TAU_MS
        } else {
            if (lastRawLux > ambientLux * (1f - DARKEN_HYSTERESIS)) {
                // Невелике коливання — не реагуємо, щоб екран не «дихав».
                darkenSinceMs = -1L
                ambientIsReference = true
                return
            }
            if (darkenSinceMs < 0) darkenSinceMs = nowMs
            if (nowMs - darkenSinceMs < DARKEN_DEBOUNCE_MS) return
            tau = DARKEN_TAU_MS
        }
        val alpha = 1f - exp(-dt / tau)
        ambientLux = (exp(la + (lr - la) * alpha) - 1f).coerceAtLeast(0f)
    }

    private fun refreshOcclusion(nowMs: Long) {
        occlusion = when {
            proximityNear || nowMs - proximityFarSinceMs < PROXIMITY_RELEASE_MS -> Occlusion.PROXIMITY
            suspectSinceMs >= 0 -> Occlusion.SUDDEN_DROP
            else -> Occlusion.NONE
        }
    }

    private fun startSuspect(nowMs: Long, ref: Float) {
        suspectSinceMs = nowMs
        suspectRefLux = ref
    }

    private fun normalize(lux: Float): Float {
        if (lux.isNaN() || lux < 0f) return 0f
        return if (lux >= saturationLux) SATURATED_LUX else lux
    }

    private fun pushHistory(t: Long, lux: Float) {
        historyTime[historyHead] = t
        historyLux[historyHead] = lux
        historyHead = (historyHead + 1) % HISTORY
        if (historyCount < HISTORY) historyCount++
    }

    private fun recentMax(nowMs: Long): Float {
        var m = 0f
        for (k in 0 until historyCount) {
            val idx = (historyHead - 1 - k + HISTORY) % HISTORY
            if (nowMs - historyTime[idx] > DROP_WINDOW_MS) break
            m = maxOf(m, historyLux[idx])
        }
        return m
    }

    companion object {
        private const val HISTORY = 64

        const val BRIGHTEN_TAU_MS = 800f
        const val DARKEN_TAU_MS = 3500f
        const val DARKEN_DEBOUNCE_MS = 2000L
        const val DARKEN_HYSTERESIS = 0.2f

        /** Нижче цього вважаємо, що датчик бачить «темряву» (прикритий). */
        const val NEAR_ZERO_LUX = 3f

        /** Падіння не менше ніж на 85% за [DROP_WINDOW_MS] — підозра на палець. */
        const val DROP_RATIO = 0.15f
        const val DROP_WINDOW_MS = 1500L

        /** При меншому світлі різке падіння не вважаємо пальцем. */
        const val MIN_REF_LUX = 15f

        /** Денне світло: різко «вимкнути» його неможливо, тож довше вважаємо, що це палець. */
        const val DAYLIGHT_LUX = 1000f
        const val SHORT_CONFIRM_MS = 5_000L
        const val LONG_CONFIRM_MS = 15_000L

        /** Якщо світла знову стало хоча б 40% від попереднього — палець прибрали. */
        const val RECOVER_RATIO = 0.4f

        const val PROXIMITY_RELEASE_MS = 400L

        const val SATURATED_LUX = 30_000f
    }
}
