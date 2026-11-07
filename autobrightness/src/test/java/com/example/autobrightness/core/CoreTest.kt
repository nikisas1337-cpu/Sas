package com.example.autobrightness.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GammaTest {
    @Test
    fun roundTrip() {
        for (i in 0..100) {
            val p = i / 100f
            assertEquals(p, Gamma.toPerceptual(Gamma.toLinear(p)), 1e-3f)
        }
        assertEquals(0f, Gamma.toLinear(0f), 1e-6f)
        assertEquals(1f, Gamma.toLinear(1f), 1e-3f)
    }

    @Test
    fun rangeMapping() {
        val r = BrightnessRange(1, 255)
        assertEquals(1, r.toSetting(0f))
        assertEquals(255, r.toSetting(1f))
        for (v in listOf(1, 10, 50, 128, 200, 255)) {
            assertEquals(v, r.toSetting(r.toPerceptual(v)))
        }
        // Xiaomi-подібний діапазон
        val big = BrightnessRange(1, 2047)
        assertEquals(2047, big.toSetting(1f))
    }
}

class CurveTest {
    @Test
    fun defaultCurveIsMonotonicAndReadable() {
        var prev = -1f
        var lux = 0f
        while (lux < 50_000f) {
            val v = BrightnessCurve.DEFAULT.at(lux)
            assertTrue("крива спадає на $lux лк", v >= prev)
            prev = v
            lux = lux * 1.2f + 0.5f
        }
        assertTrue(BrightnessCurve.DEFAULT.at(0f) >= 0.15f)
        assertEquals(1f, BrightnessCurve.DEFAULT.at(20_000f), 1e-6f)
    }

    @Test
    fun deviceCurve() {
        val levels = intArrayOf(10, 100, 1000, 10000)
        val values = intArrayOf(10, 40, 100, 200, 255)
        val c = BrightnessCurve.fromDeviceConfig(levels, values)
        assertNotNull(c)
        c!!
        assertEquals(1f, c.at(20000f), 1e-3f)
        // не темніше за універсальну більш ніж на запас
        for (lux in listOf(0f, 5f, 50f, 500f, 5000f)) {
            assertTrue(c.at(lux) >= BrightnessCurve.DEFAULT.at(lux) - 0.081f)
        }
        assertNull(BrightnessCurve.fromDeviceConfig(intArrayOf(), intArrayOf()))
        assertNull(BrightnessCurve.fromDeviceConfig(intArrayOf(10, 5), intArrayOf(1, 2, 3)))
    }
}

class LearningTest {
    @Test
    fun learnsExactlyAtPointAndKeepsMonotonic() {
        val model = BrightnessModel(BrightnessCurve.DEFAULT, LearnedCorrection())
        model.learn(150f, 0.5f)
        assertEquals(0.5f, model.targetFor(150f), 0.01f)
        // далекі точки змінюються мало
        assertEquals(BrightnessCurve.DEFAULT.at(20000f), model.targetFor(20000f), 0.05f)
        var prev = -1f
        var lux = 0f
        while (lux < 40_000f) {
            val v = model.targetFor(lux)
            assertTrue("спадає на $lux лк", v >= prev - 1e-4f)
            prev = v
            lux = lux * 1.3f + 0.5f
        }
    }

    @Test
    fun serialization() {
        val c = LearnedCorrection()
        c.learn(50f, 0.9f) { BrightnessCurve.DEFAULT.at(it) }
        val restored = LearnedCorrection.parse(c.serialize())
        assertEquals(c.at(50f), restored.at(50f), 1e-5f)
        assertEquals(0f, LearnedCorrection.parse("garbage").at(50f), 0f)
    }
}

class ProcessorTest {
    private fun feed(p: AmbientLightProcessor, lux: Float, from: Long, to: Long, step: Long = 100) {
        var t = from
        while (t <= to) {
            p.onLux(lux, t)
            t += step
        }
    }

    @Test
    fun fingerOnSensorIsIgnored() {
        val p = AmbientLightProcessor(10_000f)
        p.reset(0)
        feed(p, 400f, 0, 3000)
        assertEquals(400f, p.ambientLux, 1f)
        // палець закрив датчик на 3 секунди
        feed(p, 0.5f, 3100, 6000)
        assertEquals(Occlusion.SUDDEN_DROP, p.occlusion)
        assertEquals(400f, p.ambientLux, 1f)
        // палець прибрали
        feed(p, 400f, 6100, 7000)
        assertFalse(p.occluded)
        assertEquals(400f, p.ambientLux, 1f)
    }

    @Test
    fun fingerInDaylightIsIgnoredLonger() {
        val p = AmbientLightProcessor(30_000f)
        p.reset(0)
        feed(p, 5000f, 0, 2000)
        feed(p, 0f, 2100, 14_000)
        assertTrue(p.occluded)
        assertEquals(5000f, p.ambientLux, 5f)
    }

    @Test
    fun realDarknessIsAcceptedAfterConfirmation() {
        val p = AmbientLightProcessor(10_000f)
        p.reset(0)
        feed(p, 200f, 0, 2000)
        // вимкнули світло в кімнаті
        feed(p, 1f, 2100, 30_000)
        assertFalse(p.occluded)
        assertTrue("має затемнитись, а зараз ${p.ambientLux}", p.ambientLux < 3f)
    }

    @Test
    fun proximityFreezesBrightness() {
        val p = AmbientLightProcessor(10_000f)
        p.reset(0)
        feed(p, 50f, 0, 2000)
        p.onProximity(true, 2050)
        feed(p, 0f, 2100, 60_000)
        assertEquals(Occlusion.PROXIMITY, p.occlusion)
        assertEquals(50f, p.ambientLux, 0.5f)
        p.onProximity(false, 60_100)
        feed(p, 50f, 60_200, 61_000)
        assertFalse(p.occluded)
        assertEquals(50f, p.ambientLux, 0.5f)
    }

    @Test
    fun shortShadowIsIgnored() {
        val p = AmbientLightProcessor(10_000f)
        p.reset(0)
        feed(p, 300f, 0, 2000)
        feed(p, 120f, 2100, 3500) // тінь на 1.5 с
        feed(p, 300f, 3600, 4000)
        assertEquals(300f, p.ambientLux, 3f)
    }

    @Test
    fun brightensQuickly() {
        val p = AmbientLightProcessor(30_000f)
        p.reset(0)
        feed(p, 100f, 0, 1000)
        feed(p, 10_000f, 1100, 4000)
        assertTrue(p.ambientLux > 8000f)
    }

    @Test
    fun coveredWhileScreenTurnsOn() {
        val p = AmbientLightProcessor(10_000f)
        p.reset(0, lastKnownLux = 500f)
        p.onLux(0f, 10)
        assertTrue(p.occluded)
        assertEquals(500f, p.ambientLux, 0.1f)
    }

    @Test
    fun saturatedSensorMeansBrightSun() {
        val p = AmbientLightProcessor(4095f)
        p.reset(0)
        p.onLux(4095f, 0)
        assertEquals(AmbientLightProcessor.SATURATED_LUX, p.ambientLux, 1f)
    }
}
