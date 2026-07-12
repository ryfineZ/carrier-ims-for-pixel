package io.github.vvb2060.ims.privileged

import android.app.Activity
import android.app.IActivityManager
import android.app.Instrumentation
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.ServiceManager
import android.provider.Telephony
import android.system.Os
import android.util.Log
import rikka.shizuku.ShizukuBinderWrapper

class ApnModifier : Instrumentation() {
    companion object {
        private const val TAG = "ApnModifier"
        private val APN_URI: Uri = Telephony.Carriers.CONTENT_URI
        private const val PREFER_APN_URI_PREFIX = "content://telephony/carriers/preferapn/subId/"

        const val BUNDLE_SELECT_SIM_ID = "select_sim_id"
        const val BUNDLE_NAME = "name"
        const val BUNDLE_APN = "apn"
        const val BUNDLE_TYPE = "type"
        const val BUNDLE_MCC = "mcc"
        const val BUNDLE_MNC = "mnc"
        const val BUNDLE_RESULT = "result"
        const val BUNDLE_RESULT_MSG = "result_msg"
    }

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        val result = Bundle()
        if (arguments == null) {
            result.putBoolean(BUNDLE_RESULT, false)
            result.putString(BUNDLE_RESULT_MSG, "missing arguments")
            finish(Activity.RESULT_OK, result)
            return
        }
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
            applyApn(arguments)
            result.putBoolean(BUNDLE_RESULT, true)
        } catch (t: Throwable) {
            Log.e(TAG, "apply apn failed", t)
            result.putBoolean(BUNDLE_RESULT, false)
            result.putString(BUNDLE_RESULT_MSG, t.message ?: t.javaClass.simpleName)
        } finally {
            if (delegated) {
                try { am.stopDelegateShellPermissionIdentity() } catch (e: Throwable) { Log.w(TAG, "stop delegate shell identity failed", it) }
            }
        }
        finish(Activity.RESULT_OK, result)
    }

    private fun applyApn(arguments: Bundle) {
        val subId = arguments.getInt(BUNDLE_SELECT_SIM_ID, -1)
        val name = arguments.getString(BUNDLE_NAME).orEmpty().trim()
        val apn = arguments.getString(BUNDLE_APN).orEmpty().trim()
        val type = arguments.getString(BUNDLE_TYPE).orEmpty().trim().ifBlank { "default,supl,ims" }
        val mcc = arguments.getString(BUNDLE_MCC).orEmpty().filter { it.isDigit() }.take(3)
        val mnc = arguments.getString(BUNDLE_MNC).orEmpty().filter { it.isDigit() }.take(3)
        require(subId >= 0) { "invalid subId" }
        require(name.isNotBlank()) { "APN name is blank" }
        require(apn.isNotBlank()) { "APN is blank" }
        require(mcc.length == 3) { "MCC must be 3 digits" }
        require(mnc.length in 2..3) { "MNC must be 2 or 3 digits" }

        val numeric = mcc + mnc
        val values = ContentValues().apply {
            put("name", name)
            put("apn", apn)
            put("type", type)
            put("mcc", mcc)
            put("mnc", mnc)
            put("numeric", numeric)
            put("protocol", "IPV4V6")
            put("roaming_protocol", "IPV4V6")
            put("carrier_enabled", 1)
            put("edited", 1)
            put("sub_id", subId)
        }

        val existingId = findExistingApnId(subId, numeric, apn, type)
        val apnId = if (existingId != null) {
            val updated = context.contentResolver.update(
                Uri.withAppendedPath(APN_URI, existingId.toString()),
                values,
                null,
                null
            )
            if (updated <= 0) {
                throw IllegalStateException("update APN failed")
            }
            existingId
        } else {
            val inserted = context.contentResolver.insert(APN_URI, values)
                ?: throw IllegalStateException("insert APN failed")
            inserted.lastPathSegment?.toLongOrNull()
                ?: throw IllegalStateException("invalid APN id: $inserted")
        }
        val preferValues = ContentValues().apply {
            put("apn_id", apnId)
        }
        val preferredUpdated = context.contentResolver.update(
            Uri.parse("$PREFER_APN_URI_PREFIX$subId"),
            preferValues,
            null,
            null
        )
        if (preferredUpdated <= 0) {
            throw IllegalStateException("set preferred APN failed")
        }
        Log.i(TAG, "APN inserted for subId=$subId id=$apnId name=$name apn=$apn")
    }

    private fun findExistingApnId(
        subId: Int,
        numeric: String,
        apn: String,
        type: String,
    ): Long? {
        val queries = listOf(
            "numeric=? AND apn=? AND type=? AND sub_id=?" to arrayOf(numeric, apn, type, subId.toString()),
            "numeric=? AND apn=? AND type=?" to arrayOf(numeric, apn, type),
        )
        for ((selection, args) in queries) {
            val existing = runCatching {
                context.contentResolver.query(
                    APN_URI,
                    arrayOf("_id"),
                    selection,
                    args,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getLong(0) else null
                }
            }.getOrNull()
            if (existing != null) return existing
        }
        return null
    }
}
