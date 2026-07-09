package io.github.vvb2060.ims.privileged

import android.app.Activity
import android.app.IActivityManager
import android.app.Instrumentation
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.os.ServiceManager
import android.system.Os
import android.telephony.CarrierConfigManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import com.android.internal.telephony.ITelephony
import io.github.vvb2060.ims.LogcatRepository
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper

class ImsModifier : Instrumentation() {
    companion object Companion {
        private const val TAG = "ImsModifier"
        private const val KEY_NR_ADVANCED_THRESHOLD_BANDWIDTH_KHZ = "nr_advanced_threshold_bandwidth_khz_int"
        private const val KEY_ADDITIONAL_NR_ADVANCED_BANDS = "additional_nr_advanced_bands_int_array"
        private const val KEY_5G_ICON_CONFIGURATION = "5g_icon_configuration_string"
        private const val KEY_NR_ADVANCED_CAPABLE_PCO_ID = "nr_advanced_capable_pco_id_int"
        private const val KEY_INCLUDE_LTE_FOR_NR_ADVANCED_THRESHOLD_BANDWIDTH =
            "include_lte_for_nr_advanced_threshold_bandwidth_bool"
        private const val NR_ADVANCED_THRESHOLD_KHZ_FOR_5GA = 110_000
        private const val NR_ICON_CONFIGURATION_5GA =
            "connected_mmwave:5G_Plus,connected:5G,connected_rrc_idle:5G,not_restricted_rrc_idle:5G,not_restricted_rrc_con:5G"
        private const val BUNDLE_COUNTRY_MCC_OVERRIDE = "country_mcc_override"
        private const val BUNDLE_COUNTRY_MNC_HINT = "country_mnc_hint"
        private val NR_ADVANCED_BANDS_FOR_CHINA = intArrayOf(
            1, 3, 8, 28, 41, 78, 79
        )
        const val BUNDLE_SELECT_SIM_ID = "select_sim_id"
        const val BUNDLE_RESET = "reset"
        const val BUNDLE_PREFER_PERSISTENT = "prefer_persistent"
        const val BUNDLE_RESULT = "result"
        const val BUNDLE_RESULT_MSG = "result_msg"

        fun buildResetBundle(): Bundle = Bundle().apply {
            putBoolean(BUNDLE_RESET, true)
        }

        fun buildBundle(
            carrierName: String?,
            countryISO: String?,
            countryMcc: String?,
            countryMncHint: String?,
            enableVoLTE: Boolean,
            enableVoWiFi: Boolean,
            enableVT: Boolean,
            enableVoNR: Boolean,
            enableCrossSIM: Boolean,
            enableUT: Boolean,
            enable5GNR: Boolean,
            enable5GThreshold: Boolean,
            enable5GPlusIcon: Boolean,
            enableShow4GForLTE: Boolean,
        ): Bundle {
            val bundle = Bundle()
            // 运营商名称
            if (carrierName?.isNotBlank() == true) {
                bundle.putBoolean(CarrierConfigManager.KEY_CARRIER_NAME_OVERRIDE_BOOL, true)
                bundle.putString(CarrierConfigManager.KEY_CARRIER_NAME_STRING, carrierName)
                bundle.putString(CarrierConfigManager.KEY_CARRIER_CONFIG_VERSION_STRING, ":3")
            }
            // 运营商国家码
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                if (countryISO?.isNotBlank() == true) {
                    bundle.putString(
                        CarrierConfigManager.KEY_SIM_COUNTRY_ISO_OVERRIDE_STRING,
                        countryISO
                    )
                }
            }
            normalizeMccForOverride(countryMcc)?.let {
                bundle.putString(BUNDLE_COUNTRY_MCC_OVERRIDE, it)
            }
            normalizeMncForOverride(countryMncHint)?.let {
                bundle.putString(BUNDLE_COUNTRY_MNC_HINT, it)
            }
            // VoLTE 配置
            if (enableVoLTE) {
                bundle.putBoolean(CarrierConfigManager.KEY_CARRIER_VOLTE_AVAILABLE_BOOL, true)
                bundle.putBoolean(CarrierConfigManager.KEY_EDITABLE_ENHANCED_4G_LTE_BOOL, true)
                bundle.putBoolean(CarrierConfigManager.KEY_HIDE_ENHANCED_4G_LTE_BOOL, false)
                bundle.putBoolean(CarrierConfigManager.KEY_HIDE_LTE_PLUS_DATA_ICON_BOOL, false)
            }

            // LTE 显示为 4G
            if (enableShow4GForLTE) {
                bundle.putBoolean("show_4g_for_lte_data_icon_bool", true)
            }

            // VT (视频通话) 配置
            if (enableVT) {
                bundle.putBoolean(CarrierConfigManager.KEY_CARRIER_VT_AVAILABLE_BOOL, true)
            }

            // UT 补充服务配置
            if (enableUT) {
                bundle.putBoolean(CarrierConfigManager.KEY_CARRIER_SUPPORTS_SS_OVER_UT_BOOL, true)
            }

            // 跨 SIM 通话配置
            if (enableCrossSIM) {
                bundle.putBoolean(
                    CarrierConfigManager.KEY_CARRIER_CROSS_SIM_IMS_AVAILABLE_BOOL,
                    true
                )
                bundle.putBoolean(
                    CarrierConfigManager.KEY_ENABLE_CROSS_SIM_CALLING_ON_OPPORTUNISTIC_DATA_BOOL,
                    true
                )
            }

            // VoWiFi 配置
            if (enableVoWiFi) {
                bundle.putBoolean(CarrierConfigManager.KEY_CARRIER_WFC_IMS_AVAILABLE_BOOL, true)
                bundle.putBoolean(
                    CarrierConfigManager.KEY_CARRIER_WFC_SUPPORTS_WIFI_ONLY_BOOL,
                    true
                )
                bundle.putBoolean(CarrierConfigManager.KEY_EDITABLE_WFC_MODE_BOOL, true)
                bundle.putBoolean(CarrierConfigManager.KEY_EDITABLE_WFC_ROAMING_MODE_BOOL, true)
                // KEY_SHOW_WIFI_CALLING_ICON_IN_STATUS_BAR_BOOL
                bundle.putBoolean("show_wifi_calling_icon_in_status_bar_bool", true)
                // KEY_WFC_SPN_FORMAT_IDX_INT
                bundle.putInt("wfc_spn_format_idx_int", 6)
            }

            // VoNR (5G 语音) 配置
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                if (enableVoNR) {
                    bundle.putBoolean(CarrierConfigManager.KEY_VONR_ENABLED_BOOL, true)
                    bundle.putBoolean(CarrierConfigManager.KEY_VONR_SETTING_VISIBILITY_BOOL, true)
                }
            }

            // 5G NR 配置
            if (enable5GNR) {
                bundle.putIntArray(
                    CarrierConfigManager.KEY_CARRIER_NR_AVAILABILITIES_INT_ARRAY,
                    intArrayOf(
                        CarrierConfigManager.CARRIER_NR_AVAILABILITY_NSA,
                        CarrierConfigManager.CARRIER_NR_AVAILABILITY_SA
                    )
                )
                if (enable5GPlusIcon) {
                    // 5GA / 5G+ 图标判定逻辑：
                    // 1) 只有达到较高 NR 聚合带宽（这里使用 110MHz）才进入 NR Advanced；
                    // 2) 将 NR Advanced 对应状态映射到 5G_Plus 图标；
                    // 3) 补充常见国内 NR 频段，避免 Sub-6 场景因非毫米波而无法进入高级图标状态。
                    bundle.putInt(KEY_NR_ADVANCED_THRESHOLD_BANDWIDTH_KHZ, NR_ADVANCED_THRESHOLD_KHZ_FOR_5GA)
                    bundle.putBoolean(
                        KEY_INCLUDE_LTE_FOR_NR_ADVANCED_THRESHOLD_BANDWIDTH,
                        false
                    )
                    bundle.putIntArray(KEY_ADDITIONAL_NR_ADVANCED_BANDS, NR_ADVANCED_BANDS_FOR_CHINA)
                    bundle.putString(KEY_5G_ICON_CONFIGURATION, NR_ICON_CONFIGURATION_5GA)
                    // 将 PCO 约束置零，避免被运营商 PCO gate 阻断 NR Advanced 图标显示。
                    bundle.putInt(KEY_NR_ADVANCED_CAPABLE_PCO_ID, 0)
                }
                if (enable5GThreshold) {
                    bundle.putIntArray(
                        CarrierConfigManager.KEY_5G_NR_SSRSRP_THRESHOLDS_INT_ARRAY,  // Boundaries: [-140 dBm, -44 dBm]
                        intArrayOf(
                            -128,  /* SIGNAL_STRENGTH_POOR */
                            -118,  /* SIGNAL_STRENGTH_MODERATE */
                            -108,  /* SIGNAL_STRENGTH_GOOD */
                            -98,  /* SIGNAL_STRENGTH_GREAT */
                        )
                    )
                }
            }
            return bundle
        }

        private fun normalizeMccForOverride(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            val firstPart = raw.trim().substringBefore('-')
            val digits = firstPart.filter { it.isDigit() }.take(3)
            return digits.takeIf { it.length == 3 }
        }

        private fun normalizeMncForOverride(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            val digits = raw.filter { it.isDigit() }.take(3)
            return digits.takeIf { it.length in 2..3 }
        }
    }

    override fun onCreate(arguments: Bundle) {
        // 等待 Shizuku binder 准备好
        var index = 0
        val maxRetries = 50 // 最多等待 5 秒
        while (!Shizuku.pingBinder()) {
            index++
            Log.d(TAG, "wait for shizuku binder ready")
            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {
                break
            }
            if (index >= maxRetries) {
                break
            }
        }
        val results = Bundle()
        if (index >= maxRetries) {
            results.putBoolean(BUNDLE_RESULT, false)
            results.putString(BUNDLE_RESULT_MSG, "shizuku binder is not ready")
            finish(Activity.RESULT_OK, results)
            return
        }
        Log.i(TAG, "shizuku binder is ready")

        try {
            overrideConfig(arguments)
            if (LogcatRepository.isCapturing()) {
                Log.i(TAG, "overrideConfig success")
            }
            results.putBoolean(BUNDLE_RESULT, true)
        } catch (t: Throwable) {
            if (LogcatRepository.isCapturing()) {
                Log.i(TAG, "overrideConfig failed")
            }
            Log.e(TAG, "failed to override config", t)
            results.putBoolean(BUNDLE_RESULT, false)
            results.putString(BUNDLE_RESULT_MSG, t.message ?: t.javaClass.simpleName)
        }
        finish(Activity.RESULT_OK, results)
    }

    @Throws(Exception::class)
    private fun overrideConfig(arguments: Bundle) {
        val binder = ServiceManager.getService(Context.ACTIVITY_SERVICE)
        val am = IActivityManager.Stub.asInterface(ShizukuBinderWrapper(binder))
        Log.i(TAG, "starting shell permission delegation")
        am.startDelegateShellPermissionIdentity(Os.getuid(), null)
        try {
            val cm = context.getSystemService(CarrierConfigManager::class.java)
            val sm = context.getSystemService(SubscriptionManager::class.java)

            val selectedSubId = arguments.getInt(BUNDLE_SELECT_SIM_ID, -1)
            arguments.remove(BUNDLE_SELECT_SIM_ID)

            val subIds: IntArray = if (selectedSubId == -1) {
                // 应用到所有 SIM 卡
                sm.javaClass.getMethod("getActiveSubscriptionIdList").invoke(sm) as IntArray
            } else {
                // 只应用到选中的 SIM 卡
                intArrayOf(selectedSubId)
            }
            val reset = arguments.getBoolean(BUNDLE_RESET, false)
            arguments.remove(BUNDLE_RESET)
            val preferPersistent = arguments.getBoolean(BUNDLE_PREFER_PERSISTENT, false)
            arguments.remove(BUNDLE_PREFER_PERSISTENT)
            val countryMccOverride = arguments.getString(BUNDLE_COUNTRY_MCC_OVERRIDE)
            arguments.remove(BUNDLE_COUNTRY_MCC_OVERRIDE)
            val countryMncHint = arguments.getString(BUNDLE_COUNTRY_MNC_HINT)
            arguments.remove(BUNDLE_COUNTRY_MNC_HINT)
            val baseValues = if (reset) null else arguments.toPersistableBundle()
            for (subId in subIds) {
                val values = baseValues?.let { PersistableBundle(it) }
                Log.i(TAG, "overrideConfig for subId $subId with values $values")
                applyOverrideConfig(
                    cm,
                    subId,
                    values,
                    preferPersistent = preferPersistent
                )
                if (reset) {
                    clearCarrierTestOverride(subId)
                } else if (!countryMccOverride.isNullOrBlank()) {
                    applyCarrierTestMccOverride(subId, countryMccOverride, countryMncHint)
                }
            }
        } finally {
            runCatching {
                am.stopDelegateShellPermissionIdentity()
                Log.i(TAG, "stopped shell permission delegation")
            }.onFailure { Log.w(TAG, "stop delegate shell identity failed", it) }
        }
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
        // 使用反射调用 overrideConfig
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

    @Throws(Exception::class)
    private fun applyCarrierTestMccOverride(
        subId: Int,
        mccOverrideRaw: String,
        mncHintRaw: String?,
    ) {
        val normalizedMcc = mccOverrideRaw.filter { it.isDigit() }.take(3)
        if (normalizedMcc.length != 3) {
            Log.w(TAG, "skip carrier test override: invalid MCC=$mccOverrideRaw")
            return
        }
        val normalizedMnc = mncHintRaw
            ?.filter { it.isDigit() }
            ?.take(3)
            ?.takeIf { it.length in 2..3 }
            ?: resolveCurrentMnc(subId)
            ?: throw IllegalStateException("unable to resolve MNC for subId=$subId")
        val mccmnc = normalizedMcc + normalizedMnc
        val binder = ServiceManager.getService(Context.TELEPHONY_SERVICE)
            ?: throw IllegalStateException("phone service unavailable")
        val telephony = ITelephony.Stub.asInterface(ShizukuBinderWrapper(binder))
            ?: throw IllegalStateException("ITelephony unavailable")
        Log.i(TAG, "setCarrierTestOverride for subId=$subId mccmnc=$mccmnc")
        telephony.setCarrierTestOverride(
            subId,
            mccmnc,
            "",
            "",
            "",
            "",
            "",
            "",
            null,
            null
        )
    }

    @Throws(Exception::class)
    private fun clearCarrierTestOverride(subId: Int) {
        val binder = ServiceManager.getService(Context.TELEPHONY_SERVICE)
            ?: throw IllegalStateException("phone service unavailable")
        val telephony = ITelephony.Stub.asInterface(ShizukuBinderWrapper(binder))
            ?: throw IllegalStateException("ITelephony unavailable")

        val clearMethod = runCatching {
            telephony.javaClass.getMethod("clearCarrierTestOverride", Int::class.javaPrimitiveType)
        }.getOrNull()

        if (clearMethod != null) {
            Log.i(TAG, "clearCarrierTestOverride for subId=$subId")
            clearMethod.invoke(telephony, subId)
            return
        }

        val currentMccMnc = resolveActiveSubscriptionMccMnc(subId)
        if (currentMccMnc.isNullOrBlank()) {
            Log.w(
                TAG,
                "clearCarrierTestOverride unavailable and unable to resolve MCCMNC for subId=$subId; skip fallback to avoid empty operator override"
            )
            return
        }

        Log.i(
            TAG,
            "clearCarrierTestOverride unavailable, fallback setCarrierTestOverride(current=$currentMccMnc) for subId=$subId"
        )
        telephony.setCarrierTestOverride(
            subId,
            currentMccMnc,
            "",
            "",
            "",
            "",
            "",
            "",
            null,
            null
        )
    }

    private fun resolveActiveSubscriptionMccMnc(subId: Int): String? {
        val sm = context.getSystemService(SubscriptionManager::class.java) ?: return null
        val info = sm.activeSubscriptionInfoList?.firstOrNull { it.subscriptionId == subId } ?: return null

        val mcc = info.mccString
            ?.filter { it.isDigit() }
            ?.takeIf { it.length == 3 }
            ?: info.mcc.takeIf { it in 0..999 }?.toString()?.padStart(3, '0')

        val mnc = info.mncString
            ?.filter { it.isDigit() }
            ?.takeIf { it.length in 2..3 }
            ?: info.mnc.takeIf { it in 0..999 }?.toString()?.padStart(2, '0')

        return if (mcc != null && mnc != null) mcc + mnc else null
    }

    private fun resolveCurrentMnc(subId: Int): String? {
        val telephony = context.getSystemService(TelephonyManager::class.java) ?: return null
        val bySub = telephony.createForSubscriptionId(subId).simOperator.orEmpty()
        val operator = bySub.filter { it.isDigit() }
        if (operator.length >= 5) {
            return operator.substring(3)
        }
        val fallback = telephony.simOperator.orEmpty().filter { it.isDigit() }
        return if (fallback.length >= 5) fallback.substring(3) else null
    }

    @Suppress("UNCHECKED_CAST", "DEPRECATION")
    fun Bundle.toPersistableBundle(): PersistableBundle {
        val pb = PersistableBundle()

        // 遍历 Bundle 的所有 Key
        for (key in this.keySet()) {
            val value = this.get(key)

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
