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
        const val BUNDLE_HEAL_ONLY = "heal_only"
        const val BUNDLE_RESULT = "result"
        const val BUNDLE_RESULT_MSG = "result_msg"

        private const val IMS_TYPE = "ims"
        private const val IMS_APN_VALUE = "ims"
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
            am.startDelegateShellPermissionIdentity(Os.getuid(), null)
            delegated = true
            applyApn(arguments)
            result.putBoolean(BUNDLE_RESULT, true)
        } catch (t: Throwable) {
            Log.e(TAG, "apply apn failed", t)
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

    private fun applyApn(arguments: Bundle) {
        val mcc = arguments.getString(BUNDLE_MCC).orEmpty().filter { it.isDigit() }.take(3)
        val mnc = arguments.getString(BUNDLE_MNC).orEmpty().filter { it.isDigit() }.take(3)
        require(mcc.length == 3) { "MCC must be 3 digits" }
        require(mnc.length in 2..3) { "MNC must be 2 or 3 digits" }
        val numeric = mcc + mnc

        // Self-heal path: the network/SIM can silently re-push a merged "default,supl,ims"
        // APN row after our split (e.g. OMA-CP/OTA carrier provisioning), so this needs to be
        // re-checked periodically, not just once. No general APN row is created/edited here.
        if (arguments.getBoolean(BUNDLE_HEAL_ONLY, false)) {
            splitOutImsType(numeric)
            Log.i(TAG, "APN ims-type drift check completed for numeric=$numeric")
            return
        }

        val subId = arguments.getInt(BUNDLE_SELECT_SIM_ID, -1)
        val name = arguments.getString(BUNDLE_NAME).orEmpty().trim()
        val apn = arguments.getString(BUNDLE_APN).orEmpty().trim()
        val rawType = arguments.getString(BUNDLE_TYPE).orEmpty().trim().ifBlank { "default,supl,ims" }
        require(subId >= 0) { "invalid subId" }
        require(name.isNotBlank()) { "APN name is blank" }
        require(apn.isNotBlank()) { "APN is blank" }

        val requestedTypes = rawType.split(",").map { it.trim() }.filter { it.isNotBlank() }
        val generalTypes = requestedTypes.filterNot { it == IMS_TYPE }

        // On some Android 17 builds, an APN row whose type list mixes "ims" together with
        // "default"/"supl" never brings up the dedicated IMS PDN (the modem/QNS layer can't
        // resolve a clean IMS-only bearer), which silently blocks IMS registration. Keep
        // "ims" on its own APN row, split out from any general-purpose row that carries it.
        splitOutImsType(numeric)

        val generalId = if (generalTypes.isNotEmpty()) {
            upsertApnRow(subId, numeric, mcc, mnc, name, apn, generalTypes.joinToString(","))
        } else {
            null
        }
        if (requestedTypes.contains(IMS_TYPE)) {
            upsertApnRow(
                subId, numeric, mcc, mnc, imsApnName(name), IMS_APN_VALUE, IMS_TYPE,
                matchTypeOnly = true,
            )
        }
        if (generalId != null) {
            setPreferredApn(subId, generalId)
        }
        Log.i(TAG, "APN applied for subId=$subId name=$name apn=$apn type=$rawType")
    }

    private fun splitOutImsType(numeric: String) {
        val rows = runCatching {
            context.contentResolver.query(
                APN_URI,
                arrayOf("_id", "type"),
                "numeric=?",
                arrayOf(numeric),
                null
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow("_id")
                val typeIndex = cursor.getColumnIndexOrThrow("type")
                buildList {
                    while (cursor.moveToNext()) {
                        add(cursor.getLong(idIndex) to cursor.getString(typeIndex).orEmpty())
                    }
                }
            }
        }.getOrNull().orEmpty()

        for ((id, typeString) in rows) {
            val types = typeString.split(",").map { it.trim() }.filter { it.isNotBlank() }
            if (types.size <= 1 || IMS_TYPE !in types) continue
            val remaining = types.filterNot { it == IMS_TYPE }.joinToString(",")
            val updated = runCatching {
                context.contentResolver.update(
                    Uri.withAppendedPath(APN_URI, id.toString()),
                    ContentValues().apply {
                        put("type", remaining)
                        put("edited", 1)
                    },
                    null,
                    null
                )
            }.getOrNull() ?: 0
            if (updated > 0) {
                Log.i(TAG, "split ims type out of APN id=$id, remaining type=$remaining")
            }
        }
    }

    private fun upsertApnRow(
        subId: Int,
        numeric: String,
        mcc: String,
        mnc: String,
        name: String,
        apn: String,
        type: String,
        matchTypeOnly: Boolean = false,
    ): Long {
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

        val existingId = findExistingApnId(subId, numeric, apn, type, matchTypeOnly)
        return if (existingId != null) {
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
    }

    private fun setPreferredApn(subId: Int, apnId: Long) {
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
    }

    private fun imsApnName(generalName: String): String {
        val base = generalName.ifBlank { "IMS" }
        return "$base IMS"
    }

    private fun findExistingApnId(
        subId: Int,
        numeric: String,
        apn: String,
        type: String,
        matchTypeOnly: Boolean = false,
    ): Long? {
        val queries = buildList {
            add("numeric=? AND apn=? AND type=? AND sub_id=?" to arrayOf(numeric, apn, type, subId.toString()))
            add("numeric=? AND apn=? AND type=?" to arrayOf(numeric, apn, type))
            if (matchTypeOnly) {
                // Reuse an existing dedicated row for this type (e.g. a carrier-shipped "ims"
                // APN) even if its apn value differs from our placeholder, so we update it
                // in place instead of inserting a duplicate.
                add("numeric=? AND type=? AND sub_id=?" to arrayOf(numeric, type, subId.toString()))
                add("numeric=? AND type=?" to arrayOf(numeric, type))
            }
        }
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
