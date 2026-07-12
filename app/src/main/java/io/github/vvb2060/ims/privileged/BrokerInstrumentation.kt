package io.github.vvb2060.ims.privileged

import android.app.Activity
import android.app.IActivityManager
import android.app.Instrumentation
import android.content.Context
import android.os.Bundle
import android.os.PersistableBundle
import android.os.ServiceManager
import android.system.Os
import android.telephony.CarrierConfigManager
import android.telephony.SubscriptionManager
import android.util.Log
import rikka.shizuku.ShizukuBinderWrapper

class BrokerInstrumentation : Instrumentation() {
    companion object {
        private const val TAG = "BrokerInstrumentation"
    }

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        if (arguments == null) {
            finish(Activity.RESULT_CANCELED, Bundle())
            return
        }

        val result = Bundle()
        if (!waitForShizukuBinderReady()) {
            result.putBoolean(ImsModifier.BUNDLE_RESULT, false)
            result.putString(ImsModifier.BUNDLE_RESULT_MSG, "shizuku binder is not ready")
            finish(Activity.RESULT_OK, result)
            return
        }
        val binder = ServiceManager.getService(Context.ACTIVITY_SERVICE)
        val am = IActivityManager.Stub.asInterface(ShizukuBinderWrapper(binder))
        var delegated = false
        try {
            try {
    am.startDelegateShellPermissionIdentity(Os.getuid(), null)
    delegated = true
} catch (e: Throwable) {
    Log.w(TAG, "start delegation failed, continuing without it", e)
}
            val cm = context.getSystemService(CarrierConfigManager::class.java)
            val sm = context.getSystemService(SubscriptionManager::class.java)

            val selectedSubId = arguments.getInt(ImsModifier.BUNDLE_SELECT_SIM_ID, -1)
            arguments.remove(ImsModifier.BUNDLE_SELECT_SIM_ID)
            val reset = arguments.getBoolean(ImsModifier.BUNDLE_RESET, false)
            arguments.remove(ImsModifier.BUNDLE_RESET)
            val preferPersistent = arguments.getBoolean(ImsModifier.BUNDLE_PREFER_PERSISTENT, false)
            arguments.remove(ImsModifier.BUNDLE_PREFER_PERSISTENT)

            val subIds: IntArray = if (selectedSubId == -1) {
                sm.javaClass.getMethod("getActiveSubscriptionIdList").invoke(sm) as IntArray
            } else {
                intArrayOf(selectedSubId)
            }

            val baseValues = if (reset) null else toPersistableBundle(arguments)
            for (subId in subIds) {
                val values = baseValues?.let { PersistableBundle(it) }
                Log.i(TAG, "overrideConfig for subId $subId with values $values")
                applyOverrideConfig(
                    cm,
                    subId,
                    values,
                    preferPersistent = preferPersistent
                )
            }
            result.putBoolean(ImsModifier.BUNDLE_RESULT, true)
        } catch (t: Throwable) {
            Log.e(TAG, "failed to override config", t)
            result.putBoolean(ImsModifier.BUNDLE_RESULT, false)
            result.putString(ImsModifier.BUNDLE_RESULT_MSG, t.message ?: t.javaClass.simpleName)
        } finally {
            if (delegated) {
                try { am.stopDelegateShellPermissionIdentity() } catch (e: Throwable) { Log.w(TAG, "stop delegate shell identity failed", it) }
            }
        }

        finish(Activity.RESULT_OK, result)
    }

    @Throws(Exception::class)
    private fun applyOverrideConfig(
        cm: CarrierConfigManager,
        subId: Int,
        values: PersistableBundle?,
        preferPersistent: Boolean,
    ) {
        if (!preferPersistent) {
            invokeOverrideConfig(cm, subId, values, persistent = false)
            return
        }
        try {
            invokeOverrideConfig(cm, subId, values, persistent = true)
            Log.i(TAG, "overrideConfig persistent success for subId $subId")
        } catch (persistentError: Throwable) {
            Log.w(
                TAG,
                "overrideConfig persistent failed for subId $subId, fallback to non-persistent",
                persistentError
            )
            try {
                invokeOverrideConfig(cm, subId, values, persistent = false)
                Log.i(TAG, "overrideConfig fallback non-persistent success for subId $subId")
            } catch (fallbackError: Throwable) {
                fallbackError.addSuppressed(persistentError)
                throw fallbackError
            }
        }
    }

    @Throws(Exception::class)
    private fun invokeOverrideConfig(
        cm: CarrierConfigManager,
        subId: Int,
        values: PersistableBundle?,
        persistent: Boolean,
    ) {
        try {
            cm.javaClass.getMethod(
                "overrideConfig",
                Int::class.javaPrimitiveType,
                PersistableBundle::class.java,
                Boolean::class.javaPrimitiveType
            ).invoke(cm, subId, values, persistent)
        } catch (_: NoSuchMethodException) {
            cm.javaClass.getMethod(
                "overrideConfig",
                Int::class.javaPrimitiveType,
                PersistableBundle::class.java
            ).invoke(cm, subId, values)
        }
    }

    @Suppress("UNCHECKED_CAST", "DEPRECATION")
    private fun toPersistableBundle(bundle: Bundle): PersistableBundle {
        val pb = PersistableBundle()
        for (key in bundle.keySet()) {
            val value = bundle.get(key)
            when (value) {
                is Int -> pb.putInt(key, value)
                is Long -> pb.putLong(key, value)
                is Double -> pb.putDouble(key, value)
                is String -> pb.putString(key, value)
                is Boolean -> pb.putBoolean(key, value)
                is IntArray -> pb.putIntArray(key, value)
                is LongArray -> pb.putLongArray(key, value)
                is DoubleArray -> pb.putDoubleArray(key, value)
                is BooleanArray -> pb.putBooleanArray(key, value)
                else -> {
                    if (value is Array<*> && value.isArrayOf<String>()) {
                        pb.putStringArray(key, value as Array<String>)
                    } else {
                        Log.i(TAG, "toPersistableBundle: unsupported type for key $key")
                    }
                }
            }
        }
        return pb
    }
}
