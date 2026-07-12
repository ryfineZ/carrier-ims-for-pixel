package io.github.vvb2060.ims.privileged

import android.app.IActivityManager
import android.os.Build
import android.system.Os
import android.util.Log
import com.android.internal.telephony.ITelephony
import rikka.shizuku.Shizuku

internal fun waitForShizukuBinderReady(
    maxRetries: Int = 20,
    intervalMs: Long = 100L,
): Boolean {
    var retries = 0
    while (!Shizuku.pingBinder()) {
        retries++
        if (retries > maxRetries) return false
        try {
            Thread.sleep(intervalMs)
        } catch (_: InterruptedException) {
            return false
        }
    }
    return true
}

private const val ANDROID_17 = 37

internal fun IActivityManager.tryStartDelegation(): Boolean {
    return try {
        startDelegateShellPermissionIdentity(Os.getuid(), null)
        true
    } catch (e: Throwable) {
        Log.w("ShizukuCompat", "startDelegateShellPermissionIdentity failed", e)
        false
    }
}

internal fun IActivityManager.tryStopDelegation() {
    try {
        stopDelegateShellPermissionIdentity()
    } catch (e: Throwable) {
        Log.w("ShizukuCompat", "stopDelegateShellPermissionIdentity failed", e)
    }
}

internal fun ITelephony.tryResetIms(slotIndex: Int, subId: Int, tag: String = "ShizukuCompat"): Boolean {
    try {
        resetIms(slotIndex)
        Log.i(tag, "resetIms(" + slotIndex + ") succeeded")
        return true
    } catch (e: Throwable) {
        Log.w(tag, "resetIms(" + slotIndex + ") failed: " + e.message)
    }

    try {
        val m = javaClass.getMethod("resetImsForSubId", Int::class.javaPrimitiveType)
        m.invoke(this, subId)
        Log.i(tag, "resetImsForSubId(" + subId + ") succeeded")
        return true
    } catch (e: NoSuchMethodException) {
        Log.w(tag, "resetImsForSubId not available")
    } catch (e: Throwable) {
        Log.w(tag, "resetImsForSubId failed: " + e.message)
    }

    try {
        val alt = javaClass.methods.firstOrNull { m ->
            val n = m.name.lowercase()
            n.contains("reset") && n.contains("ims") &&
                m.parameterCount() == 1 &&
                m.parameterTypes[0] == Int::class.javaPrimitiveType
        }
        if (alt != null) {
            alt.invoke(this, subId)
            Log.i(tag, "alt '" + alt.name + "(" + subId + ")' succeeded")
            return true
        }
    } catch (e: Throwable) {
        Log.w(tag, "alt ims reset probe failed", e)
    }

    try {
        setImsProvisioningInt(subId, 0, 0)
        Thread.sleep(300)
        setImsProvisioningInt(subId, 0, 1)
        Log.i(tag, "IMS provisioning toggle for subId=" + subId + " done")
    } catch (e: Throwable) {
        Log.w(tag, "IMS provisioning toggle failed: " + e.message)
    }

    return false
}

internal val isAtLeastAndroid17: Boolean
    get() = Build.VERSION.SDK_INT >= ANDROID_17
