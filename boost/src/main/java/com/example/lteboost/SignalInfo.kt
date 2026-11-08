package com.example.lteboost

import android.annotation.SuppressLint
import android.content.Context
import android.net.TrafficStats
import android.os.SystemClock
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellSignalStrengthNr
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager

/** Знімає поточні показники мобільної мережі для екрана застосунку. */
class SignalInfo(private val ctx: Context) {

    private var lastRx = -1L
    private var lastTx = -1L
    private var lastTime = 0L

    @SuppressLint("MissingPermission")
    fun read(): String {
        val sb = StringBuilder()
        val tm = ctx.getSystemService(TelephonyManager::class.java)
            .createForSubscriptionId(SubscriptionManager.getDefaultDataSubscriptionId())

        sb.append("Оператор: ").append(tm.networkOperatorName.ifBlank { "—" }).append('\n')
        val type = runCatching { networkTypeName(tm.dataNetworkType) }.getOrDefault("немає дозволу")
        sb.append("Мережа: ").append(type).append('\n')

        runCatching { tm.serviceState?.cellBandwidths }.getOrNull()?.let { bw ->
            if (bw.isNotEmpty()) {
                val total = bw.sum() / 1000
                val ca = if (bw.size > 1) "агрегація ${bw.size} несучих" else "1 несуча"
                sb.append("Смуга: $total МГц ($ca)\n")
            }
        }

        val cells = runCatching { tm.allCellInfo }.getOrNull()
        if (cells == null) {
            sb.append("\nДайте дозвіл на геолокацію, щоб бачити сигнал і діапазон.\n")
        } else {
            val serving = cells.filter { it.isRegistered }
            if (serving.isEmpty()) sb.append("\nНемає підключення до вежі\n")
            serving.forEach { sb.append('\n').append(describe(it)) }
            val neighbours = cells.size - serving.size
            if (neighbours > 0) sb.append("\nСусідніх веж видно: $neighbours\n")
        }

        sb.append('\n').append(speed())
        return sb.toString()
    }

    private fun describe(cell: CellInfo): String = when (cell) {
        is CellInfoLte -> {
            val id = cell.cellIdentity
            val s = cell.cellSignalStrength
            buildString {
                append("LTE").append(bands(id)).append('\n')
                append("  EARFCN ${v(id.earfcn)}, PCI ${v(id.pci)}")
                if (id.bandwidth != CellInfo.UNAVAILABLE) append(", ${id.bandwidth / 1000} МГц")
                append('\n')
                append("  RSRP ${v(s.rsrp)} дБм — ${quality(s.rsrp)}\n")
                append("  RSRQ ${v(s.rsrq)} дБ, SINR ${v(s.rssnr)} дБ\n")
            }
        }
        is CellInfoNr -> {
            val id = cell.cellIdentity as CellIdentityNr
            val s = cell.cellSignalStrength as CellSignalStrengthNr
            buildString {
                append("5G NR").append(if (id.bands.isNotEmpty()) " n" + id.bands.joinToString("/n") else "").append('\n')
                append("  NRARFCN ${v(id.nrarfcn)}, PCI ${v(id.pci)}\n")
                append("  RSRP ${v(s.ssRsrp)} дБм — ${quality(s.ssRsrp)}\n")
                append("  RSRQ ${v(s.ssRsrq)} дБ, SINR ${v(s.ssSinr)} дБ\n")
            }
        }
        else -> cell.javaClass.simpleName.removePrefix("CellInfo") + "\n"
    }

    private fun bands(id: CellIdentityLte): String =
        if (id.bands.isEmpty()) "" else " band " + id.bands.joinToString("/")

    /** Поточна швидкість мобільного інтернету за останній інтервал. */
    private fun speed(): String {
        val rx = TrafficStats.getMobileRxBytes()
        val tx = TrafficStats.getMobileTxBytes()
        val now = SystemClock.elapsedRealtime()
        val result = if (lastRx >= 0 && rx >= lastRx && now > lastTime) {
            val sec = (now - lastTime) / 1000.0
            "Швидкість 4G: ↓ %.1f Мбіт/с  ↑ %.1f Мбіт/с".format(
                (rx - lastRx) * 8 / 1e6 / sec, (tx - lastTx) * 8 / 1e6 / sec
            )
        } else {
            "Швидкість 4G: вимірюю…"
        }
        lastRx = rx; lastTx = tx; lastTime = now
        return result
    }

    private fun v(x: Int) = if (x == CellInfo.UNAVAILABLE) "—" else x.toString()

    private fun quality(rsrp: Int) = when {
        rsrp == CellInfo.UNAVAILABLE -> "невідомо"
        rsrp >= -80 -> "відмінний"
        rsrp >= -90 -> "добрий"
        rsrp >= -100 -> "середній"
        rsrp >= -110 -> "слабкий"
        else -> "дуже слабкий"
    }

    private fun networkTypeName(t: Int) = when (t) {
        TelephonyManager.NETWORK_TYPE_LTE -> "4G (LTE)"
        TelephonyManager.NETWORK_TYPE_NR -> "5G"
        TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_HSPA,
        TelephonyManager.NETWORK_TYPE_HSDPA, TelephonyManager.NETWORK_TYPE_HSUPA,
        TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
        TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_GPRS,
        TelephonyManager.NETWORK_TYPE_GSM -> "2G"
        TelephonyManager.NETWORK_TYPE_IWLAN -> "Wi-Fi дзвінки"
        TelephonyManager.NETWORK_TYPE_UNKNOWN -> "немає даних"
        else -> "тип $t"
    }
}
