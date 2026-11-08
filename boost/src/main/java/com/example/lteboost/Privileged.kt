package com.example.lteboost

import android.content.pm.PackageManager
import android.os.IBinder
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Виклики системних сервісів від імені shell (ADB) через Shizuku.
 * Звичайному застосунку ці дії заборонені, а shell має потрібні дозволи
 * (MODIFY_PHONE_STATE, NETWORK_SETTINGS, DEVICE_POWER).
 */
object Privileged {

    /** Обмеження, яке задає користувач (TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER). */
    private const val REASON_USER = 0

    private const val SHELL_PACKAGE = "com.android.shell"

    enum class Status { NOT_RUNNING, TOO_OLD, NO_PERMISSION, READY }

    fun status(): Status = when {
        !Shizuku.pingBinder() -> Status.NOT_RUNNING
        Shizuku.isPreV11() -> Status.TOO_OLD
        Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> Status.NO_PERMISSION
        else -> Status.READY
    }

    fun isReady(): Boolean = status() == Status.READY

    /** Shizuku передає доступ асинхронно після старту процесу, тож трохи чекаємо. */
    fun awaitBinder(timeoutMs: Long): Boolean {
        if (Shizuku.pingBinder()) return true
        val latch = CountDownLatch(1)
        val listener = Shizuku.OnBinderReceivedListener { latch.countDown() }
        Shizuku.addBinderReceivedListenerSticky(listener)
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } finally {
            Shizuku.removeBinderReceivedListener(listener)
        }
        return Shizuku.pingBinder()
    }

    private fun service(name: String, aidl: String): Any {
        val raw = SystemServiceHelper.getSystemService(name)
            ?: throw IllegalStateException("системний сервіс «$name» недоступний")
        val stub = Class.forName("$aidl\$Stub")
        return stub.getMethod("asInterface", IBinder::class.java)
            .invoke(null, ShizukuBinderWrapper(raw))
            ?: throw IllegalStateException("не вдалося підключитися до «$name»")
    }

    private fun call(target: Any, method: String, vararg args: Any): Any? {
        val m = target.javaClass.methods.firstOrNull {
            it.name == method && it.parameterTypes.size == args.size
        } ?: throw NoSuchMethodException("$method не підтримується цією прошивкою")
        return try {
            m.invoke(target, *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException ?: e
        }
    }

    private fun telephony() = service("phone", "com.android.internal.telephony.ITelephony")

    fun getAllowedNetworkTypes(subId: Int): Long =
        call(telephony(), "getAllowedNetworkTypesForReason", subId, REASON_USER) as Long

    fun setAllowedNetworkTypes(subId: Int, mask: Long): Boolean =
        call(telephony(), "setAllowedNetworkTypesForReason", subId, REASON_USER, mask) as? Boolean ?: true

    fun setWifiEnabled(enabled: Boolean): Boolean =
        call(service("wifi", "android.net.wifi.IWifiManager"), "setWifiEnabled", SHELL_PACKAGE, enabled)
            as? Boolean ?: true

    fun setPowerSaveMode(enabled: Boolean): Boolean =
        call(service("power", "android.os.IPowerManager"), "setPowerSaveModeEnabled", enabled)
            as? Boolean ?: true
}
