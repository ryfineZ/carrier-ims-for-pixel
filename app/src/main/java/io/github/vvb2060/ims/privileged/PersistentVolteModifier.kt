package io.github.vvb2060.ims.privileged

import android.app.Activity
import android.app.IActivityManager
import android.app.Instrumentation
import android.content.Context
import android.os.Bundle
import android.os.ServiceManager
import android.system.Os
import android.telephony.TelephonyFrameworkInitializer
import android.util.Log
import com.android.internal.telephony.ITelephony
import rikka.shizuku.ShizukuBinderWrapper

/**
 * 持久化 VoLTE。
 *
 * CarrierConfig 覆盖项（[ImsModifier]）只活在 com.android.phone 进程内存里，重启即失效：
 * 2026-03 之后的 Pixel 固件在 `CarrierConfigLoader.secureOverrideConfig()` 里加了
 * `persistent=true only can be invoked by system app` 的校验，非系统应用无解。
 *
 * 但是 VoLTE 的开关还有第二条路。固件里的 `ImsManager.isVolteEnabledByPlatform()` 是：
 *
 * ```
 * if (SystemProperties.getInt("persist.dbg.volte_avail_ovr" + phoneId, -1) == 1) return true;
 * if (SystemProperties.getInt("persist.dbg.volte_avail_ovr", -1) == 1) return true;
 * if (getLocalImsConfigKeyInt(68) == 1) return true;          // ← 这里
 * return config_device_volte_available
 *     && getBooleanCarrierConfig("carrier_volte_available_bool")
 *     && isGbaValid();
 * ```
 *
 * key 68 是 `ProvisioningManager.KEY_VOIMS_OPT_IN_STATUS`，读的是订阅数据库里的
 * `voims_opt_in_status` 列——不是 CarrierConfig。它是个短路的 or 条件，直接绕过
 * `carrier_volte_available_bool`，而且写进数据库后能扛过重启和系统更新。
 *
 * 平台开关通了之后还要用户侧开关（`volte_vt_enabled`）也是 1，否则
 * `isEnhanced4gLteModeSettingEnabledByUser()` 在 voims=1 时会直接返回 `setting == 1`。
 * 所以这里两个一起写。
 *
 * 这些接口只要求 MODIFY_PHONE_STATE，Shizuku 的 shell 身份委派就够；也没有
 * `overrideConfig` 那种 isShell 拦截。
 */
class PersistentVolteModifier : Instrumentation() {
    companion object {
        private const val TAG = "PersistentVolteModifier"

        /** ProvisioningManager.KEY_VOIMS_OPT_IN_STATUS */
        private const val KEY_VOIMS_OPT_IN_STATUS = 68

        const val BUNDLE_SELECT_SIM_ID = "select_sim_id"
        const val BUNDLE_ACTION = "action"
        const val BUNDLE_RESULT = "result"
        const val BUNDLE_RESULT_MSG = "result_msg"

        const val BUNDLE_VOIMS_OPT_IN = "voims_opt_in"
        const val BUNDLE_ADVANCED_CALLING = "advanced_calling"
        const val BUNDLE_VONR = "vonr"
        const val BUNDLE_IMS_REGISTERED = "ims_registered"

        const val ACTION_QUERY = "query"
        const val ACTION_ENABLE = "enable"
        const val ACTION_DISABLE = "disable"
    }

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        if (arguments == null) {
            finish(Activity.RESULT_CANCELED, Bundle())
            return
        }

        val result = Bundle()
        if (!waitForShizukuBinderReady()) {
            result.putBoolean(BUNDLE_RESULT, false)
            result.putString(BUNDLE_RESULT_MSG, "shizuku binder is not ready")
            finish(Activity.RESULT_OK, result)
            return
        }

        val binder = ServiceManager.getService(Context.ACTIVITY_SERVICE)
        val am = IActivityManager.Stub.asInterface(ShizukuBinderWrapper(binder))
        var delegated = false
        try {
            am.startDelegateShellPermissionIdentity(Os.getuid(), null)
            delegated = true

            val subId = arguments.getInt(BUNDLE_SELECT_SIM_ID, -1)
            if (subId < 0) {
                result.putBoolean(BUNDLE_RESULT, false)
                result.putString(BUNDLE_RESULT_MSG, "invalid subId")
                finish(Activity.RESULT_OK, result)
                return
            }

            val telephony = ITelephony.Stub.asInterface(
                ShizukuBinderWrapper(
                    TelephonyFrameworkInitializer
                        .getTelephonyServiceManager()
                        .getTelephonyServiceRegisterer()
                        .get()!!
                )
            )

            when (arguments.getString(BUNDLE_ACTION)) {
                ACTION_ENABLE -> applyPersistentVolte(telephony, subId, true)
                ACTION_DISABLE -> applyPersistentVolte(telephony, subId, false)
            }

            readState(telephony, subId, result)
            result.putBoolean(BUNDLE_RESULT, true)
        } catch (t: Throwable) {
            Log.e(TAG, "persistent volte operation failed", t)
            result.putBoolean(BUNDLE_RESULT, false)
            result.putString(BUNDLE_RESULT_MSG, t.message ?: t.javaClass.simpleName)
        } finally {
            if (delegated) {
                runCatching { am.stopDelegateShellPermissionIdentity() }
                    .onFailure { Log.w(TAG, "stop delegate shell identity failed", it) }
            }
        }

        finish(Activity.RESULT_OK, result)
    }

    @Throws(Exception::class)
    private fun applyPersistentVolte(telephony: ITelephony, subId: Int, enable: Boolean) {
        val value = if (enable) 1 else 0
        Log.i(TAG, "setImsProvisioningInt subId=$subId key=$KEY_VOIMS_OPT_IN_STATUS value=$value")
        telephony.setImsProvisioningInt(subId, KEY_VOIMS_OPT_IN_STATUS, value)

        if (enable) {
            // 平台开关放行之后，用户侧开关必须显式为 1：voims=1 时
            // isEnhanced4gLteModeSettingEnabledByUser() 不再回落到运营商默认值。
            runCatching { telephony.setAdvancedCallingSettingEnabled(subId, true) }
                .onFailure { Log.w(TAG, "setAdvancedCallingSettingEnabled failed", it) }
            // VoNR 用户开关同样落在订阅数据库里，配合 CarrierConfig 恢复后免去二次操作。
            runCatching { telephony.setVoNrEnabled(subId, true) }
                .onFailure { Log.w(TAG, "setVoNrEnabled failed", it) }
        }
        // 关闭时只回退 voims：用户侧开关留给系统设置里的 VoLTE 开关，
        // 避免把用户自己设过的值一起清掉。
    }

    private fun readState(telephony: ITelephony, subId: Int, result: Bundle) {
        result.putInt(
            BUNDLE_VOIMS_OPT_IN,
            runCatching { telephony.getImsProvisioningInt(subId, KEY_VOIMS_OPT_IN_STATUS) }
                .getOrElse {
                    Log.w(TAG, "getImsProvisioningInt failed", it)
                    -1
                }
        )
        result.putBoolean(
            BUNDLE_ADVANCED_CALLING,
            runCatching { telephony.isAdvancedCallingSettingEnabled(subId) }.getOrDefault(false)
        )
        result.putBoolean(
            BUNDLE_VONR,
            runCatching { telephony.isVoNrEnabled(subId) }.getOrDefault(false)
        )
        result.putBoolean(
            BUNDLE_IMS_REGISTERED,
            runCatching { telephony.isImsRegistered(subId) }.getOrDefault(false)
        )
    }
}
