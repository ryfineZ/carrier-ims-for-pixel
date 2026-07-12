package io.github.vvb2060.ims.privileged

import android.annotation.SuppressLint
import android.app.Activity
import android.app.IActivityManager
import android.app.Instrumentation
import android.content.Context
import android.os.Bundle
import android.os.PersistableBundle
import android.os.ServiceManager
import android.system.Os
import android.telephony.CarrierConfigManager
import android.util.Log
import rikka.shizuku.ShizukuBinderWrapper

class ConfigReader : Instrumentation() {
    companion object {
        private const val TAG = "ConfigReader"
        const val BUNDLE_SELECT_SIM_ID = "select_sim_id"
        const val BUNDLE_KEYS = "keys"
        const val BUNDLE_DUMP = "dump"
        const val BUNDLE_RESULT = "result"
        const val BUNDLE_DUMP_TEXT = "dump_text"
    }

    @SuppressLint("MissingPermission")
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        if (arguments == null) {
            finish(Activity.RESULT_CANCELED, Bundle())
            return
        }

        val result = Bundle()
        if (!waitForShizukuBinderReady()) {
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
            val subId = arguments.getInt(BUNDLE_SELECT_SIM_ID, -1)
            val cm = context.getSystemService(CarrierConfigManager::class.java)
            val config = cm.getConfigForSubId(subId)
            if (config == null) {
                result.putString(BUNDLE_DUMP_TEXT, "")
            } else if (arguments.getBoolean(BUNDLE_DUMP, false)) {
                result.putString(BUNDLE_DUMP_TEXT, buildDumpText(config))
            } else {
                val keys = arguments.getStringArray(BUNDLE_KEYS) ?: emptyArray()
                val values = Bundle()
                for (key in keys) {
                    putValue(values, key, config.get(key))
                }
                result.putBundle(BUNDLE_RESULT, values)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "read config failed", t)
        } finally {
            if (delegated) {
                try { am.stopDelegateShellPermissionIdentity() } catch (e: Throwable) { Log.w(TAG, "stop delegate shell identity failed", it) }
            }
        }

        finish(Activity.RESULT_OK, result)
    }

    private fun putValue(bundle: Bundle, key: String, value: Any?) {
        when (value) {
            is Int -> bundle.putInt(key, value)
            is Long -> bundle.putLong(key, value)
            is Double -> bundle.putDouble(key, value)
            is String -> bundle.putString(key, value)
            is Boolean -> bundle.putBoolean(key, value)
            is IntArray -> bundle.putIntArray(key, value)
            is LongArray -> bundle.putLongArray(key, value)
            is DoubleArray -> bundle.putDoubleArray(key, value)
            is BooleanArray -> bundle.putBooleanArray(key, value)
            is Array<*> -> if (value.isArrayOf<String>()) {
                @Suppress("UNCHECKED_CAST")
                bundle.putStringArray(key, value as Array<String>)
            }
        }
    }

    private fun buildDumpText(config: PersistableBundle): String {
        val fields =
            listOf(CarrierConfigManager::class.java, *CarrierConfigManager::class.java.declaredClasses)
                .flatMap { it.declaredFields.asList() }
                .filter { it.name != "KEY_PREFIX" && it.name.startsWith("KEY_") }
                .sortedBy { it.name }

        val lines = fields.map { field ->
            val key = try {
                field.get(field) as String
            } catch (_: Throwable) {
                null
            }
            if (key == null) {
                "${field.name}: (invalid)"
            } else {
                val value = config.get(key)
                "${field.name}: ${valueToString(value)}"
            }
        }

        return lines.joinToString("\n")
    }

    private fun valueToString(value: Any?): String {
        return when (value) {
            null -> "null"
            is IntArray -> value.joinToString(",")
            is LongArray -> value.joinToString(",")
            is DoubleArray -> value.joinToString(",")
            is BooleanArray -> value.joinToString(",")
            is Array<*> -> value.joinToString(",")
            else -> value.toString()
        }
    }
}
