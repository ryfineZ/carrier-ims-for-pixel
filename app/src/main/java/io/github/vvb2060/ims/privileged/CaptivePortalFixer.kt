package io.github.vvb2060.ims.privileged

import android.app.Activity
import android.app.IActivityManager
import android.app.Instrumentation
import android.content.Context
import android.os.Bundle
import android.os.ServiceManager
import android.provider.Settings
import android.system.Os
import android.util.Log
import rikka.shizuku.ShizukuBinderWrapper

class CaptivePortalFixer : Instrumentation() {
    companion object {
        private const val TAG = "CaptivePortalFixer"
        private const val KEY_CAPTIVE_PORTAL_HTTP_URL = "captive_portal_http_url"
        private const val KEY_CAPTIVE_PORTAL_HTTPS_URL = "captive_portal_https_url"
        private const val ACTION_APPLY_CN = "apply_cn"
        private const val ACTION_QUERY = "query"
        private const val ACTION_RESTORE_DEFAULT = "restore_default"

        const val CN_HTTP_URL = "http://connectivitycheck.gstatic.cn/generate_204"
        const val CN_HTTPS_URL = "https://www.google.cn/generate_204"

        const val BUNDLE_ACTION = "action"
        const val BUNDLE_RESULT = "result"
        const val BUNDLE_RESULT_MSG = "result_msg"
        const val BUNDLE_HTTP_URL = "http_url"
        const val BUNDLE_HTTPS_URL = "https_url"
        const val BUNDLE_IS_CN_URL = "is_cn_url"
        const val BUNDLE_IS_OVERRIDDEN = "is_overridden"

        fun actionApplyCn(): String = ACTION_APPLY_CN
        fun actionQuery(): String = ACTION_QUERY
        fun actionRestoreDefault(): String = ACTION_RESTORE_DEFAULT
    }

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
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
            try {
    am.startDelegateShellPermissionIdentity(Os.getuid(), null)
    delegated = true
} catch (e: Throwable) {
    Log.w(TAG, "start delegation failed, continuing without it", e)
}
            val resolver = context.contentResolver
            val action = arguments?.getString(BUNDLE_ACTION)?.takeIf { it.isNotBlank() } ?: ACTION_APPLY_CN
            when (action) {
                ACTION_APPLY_CN -> {
                    val setHttp = Settings.Global.putString(
                        resolver,
                        KEY_CAPTIVE_PORTAL_HTTP_URL,
                        CN_HTTP_URL
                    )
                    val setHttps = Settings.Global.putString(
                        resolver,
                        KEY_CAPTIVE_PORTAL_HTTPS_URL,
                        CN_HTTPS_URL
                    )
                    val currentHttp = Settings.Global.getString(resolver, KEY_CAPTIVE_PORTAL_HTTP_URL).orEmpty()
                    val currentHttps = Settings.Global.getString(resolver, KEY_CAPTIVE_PORTAL_HTTPS_URL).orEmpty()
                    fillUrlState(result, currentHttp, currentHttps)
                    val verified =
                        setHttp && setHttps && currentHttp == CN_HTTP_URL && currentHttps == CN_HTTPS_URL
                    if (!verified) {
                        throw IllegalStateException(
                            "verify failed, http=$currentHttp, https=$currentHttps, putHttp=$setHttp, putHttps=$setHttps"
                        )
                    }
                }

                ACTION_RESTORE_DEFAULT -> {
                    val setHttp = Settings.Global.putString(
                        resolver,
                        KEY_CAPTIVE_PORTAL_HTTP_URL,
                        null
                    )
                    val setHttps = Settings.Global.putString(
                        resolver,
                        KEY_CAPTIVE_PORTAL_HTTPS_URL,
                        null
                    )
                    val currentHttp = Settings.Global.getString(resolver, KEY_CAPTIVE_PORTAL_HTTP_URL).orEmpty()
                    val currentHttps = Settings.Global.getString(resolver, KEY_CAPTIVE_PORTAL_HTTPS_URL).orEmpty()
                    fillUrlState(result, currentHttp, currentHttps)
                    val verified = setHttp && setHttps && currentHttp.isBlank() && currentHttps.isBlank()
                    if (!verified) {
                        throw IllegalStateException(
                            "restore failed, http=$currentHttp, https=$currentHttps, putHttp=$setHttp, putHttps=$setHttps"
                        )
                    }
                }

                ACTION_QUERY -> {
                    val currentHttp = Settings.Global.getString(resolver, KEY_CAPTIVE_PORTAL_HTTP_URL).orEmpty()
                    val currentHttps = Settings.Global.getString(resolver, KEY_CAPTIVE_PORTAL_HTTPS_URL).orEmpty()
                    fillUrlState(result, currentHttp, currentHttps)
                }

                else -> {
                    throw IllegalArgumentException("unsupported action=$action")
                }
            }
            result.putBoolean(BUNDLE_RESULT, true)
        } catch (t: Throwable) {
            Log.e(TAG, "apply captive portal urls failed", t)
            result.putBoolean(BUNDLE_RESULT, false)
            result.putString(BUNDLE_RESULT_MSG, t.message ?: t.javaClass.simpleName)
        } finally {
            if (delegated) {
                try { am.stopDelegateShellPermissionIdentity() } catch (e: Throwable) { Log.w(TAG, "stop delegate shell identity failed", it) }
            }
        }
        finish(Activity.RESULT_OK, result)
    }

    private fun fillUrlState(result: Bundle, currentHttp: String, currentHttps: String) {
        result.putString(BUNDLE_HTTP_URL, currentHttp)
        result.putString(BUNDLE_HTTPS_URL, currentHttps)
        result.putBoolean(BUNDLE_IS_CN_URL, currentHttp == CN_HTTP_URL && currentHttps == CN_HTTPS_URL)
        result.putBoolean(BUNDLE_IS_OVERRIDDEN, currentHttp.isNotBlank() || currentHttps.isNotBlank())
    }
}
