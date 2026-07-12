package io.github.vvb2060.ims.privileged

import android.annotation.SuppressLint
import android.app.Activity
import android.app.IActivityManager
import android.app.Instrumentation
import android.content.Context
import android.os.Bundle
import android.os.ServiceManager
import android.system.Os
import android.telephony.SubscriptionManager
import android.telephony.TelephonyFrameworkInitializer
import android.util.Log
import com.android.internal.telephony.ISub
import rikka.shizuku.ShizukuBinderWrapper

class SimReader : Instrumentation() {
    companion object {
        private const val TAG = "SimReader"
        const val BUNDLE_RESULT = "sim_list"
    }

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    @SuppressLint("MissingPermission")
    override fun start() {
        super.start()
        if (!waitForShizukuBinderReady()) {
            finish(Activity.RESULT_CANCELED, Bundle())
            return
        }
        val binder = ServiceManager.getService(Context.ACTIVITY_SERVICE)
        val am = IActivityManager.Stub.asInterface(ShizukuBinderWrapper(binder))
        var delegated = false
        try {
            Log.i(TAG, "starting shell permission delegation")
            try {
    am.startDelegateShellPermissionIdentity(Os.getuid(), null)
    delegated = true
} catch (e: Throwable) {
    Log.w(TAG, "start delegation failed, continuing without it", e)
}
            Log.d(TAG, "start read sim info list")
            val resultList = readByISub() ?: run {
                val subManager =
                    context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
                val subList = subManager.activeSubscriptionInfoList
                subList ?: emptyList()
            }
            Log.i(TAG, "read sim info list size: ${resultList.size}")
            val bundle = Bundle()
            bundle.putParcelableArrayList(BUNDLE_RESULT, ArrayList(resultList))
            finish(Activity.RESULT_OK, bundle)
        } catch (t: Throwable) {
            Log.e(TAG, "failed to read sim info list", t)
            finish(Activity.RESULT_CANCELED, Bundle())
        } finally {
            if (delegated) {
                try {
    stopDelegateShellPermissionIdentity()
Log.i(TAG, "stopped shell permission delegation")
} catch (e: Throwable) {
    Log.w(TAG, "failed to stop shell permission delegation", it)
}
            }
        }
    }

    private fun readByISub(): List<android.telephony.SubscriptionInfo>? {
        return try {
            val serviceRegisterer =
                TelephonyFrameworkInitializer
                    .getTelephonyServiceManager()
                    .getSubscriptionServiceRegisterer()
            val binder = serviceRegisterer?.get() ?: return null
            val sub = ISub.Stub.asInterface(ShizukuBinderWrapper(binder))
            sub.getActiveSubscriptionInfoList(null, null, true)
        } catch (e: Throwable) {
            Log.w(TAG, "readByISub failed, fallback to SubscriptionManager", e)
            null
        }
    }
}
