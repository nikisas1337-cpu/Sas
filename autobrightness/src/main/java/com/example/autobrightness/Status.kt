package com.example.autobrightness

import com.example.autobrightness.core.Occlusion

/** Поточний стан сервісу для показу на екрані програми. */
data class Status(
    val running: Boolean = false,
    val lux: Float = -1f,
    val rawLux: Float = -1f,
    val brightnessPercent: Int = -1,
    val occlusion: Occlusion = Occlusion.NONE,
    val sunlightMode: Boolean = false,
    val message: String? = null,
)

/** Простий канал «сервіс → активність» (обидва працюють у головному потоці одного процесу). */
object StatusBus {
    var latest: Status = Status()
        private set
    private val listeners = LinkedHashSet<(Status) -> Unit>()

    fun publish(status: Status) {
        latest = status
        listeners.toList().forEach { it(status) }
    }

    fun subscribe(listener: (Status) -> Unit) {
        listeners += listener
        listener(latest)
    }

    fun unsubscribe(listener: (Status) -> Unit) {
        listeners -= listener
    }
}
