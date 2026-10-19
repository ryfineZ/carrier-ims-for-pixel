package com.android.internal.telephony;

import android.os.Binder;
import android.os.IBinder;

public interface ITelephony extends android.os.IInterface {
    void setCarrierTestOverride(
            int subId,
            String mccmnc,
            String imsi,
            String iccid,
            String gid1,
            String gid2,
            String plmn,
            String spn,
            String carrierPriviledgeRules,
            String apn);

    int setImsProvisioningInt(int subId, int key, int value);

    int getImsProvisioningInt(int subId, int key);

    void setAdvancedCallingSettingEnabled(int subId, boolean isEnabled);

    boolean isAdvancedCallingSettingEnabled(int subId);

    int setVoNrEnabled(int subId, boolean enabled);

    boolean isVoNrEnabled(int subId);

    void resetIms(int slotIndex);

    boolean isImsRegistered(int subId);

    abstract class Stub extends Binder implements ITelephony {
        public native static ITelephony asInterface(IBinder binder);
    }
}
