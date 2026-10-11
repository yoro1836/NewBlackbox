package top.niunaijun.blackbox.proxy.record;

import android.content.Intent;


public class ProxyPendingRecord {
    public int mUserId;
    public Intent mTarget;

    public ProxyPendingRecord(Intent target, int userId) {
        mUserId = userId;
        mTarget = target;
    }

    public static final String KEY_USER_ID = "_B_|_P_user_id_";
    public static final String KEY_TARGET = "_B_|_P_target_";

    public static void saveStub(Intent shadow, Intent target, int userId) {
        shadow.putExtra(KEY_USER_ID, userId);
        shadow.putExtra(KEY_TARGET, target);
    }

    public static ProxyPendingRecord create(Intent intent) {
        int userId = intent.getIntExtra(KEY_USER_ID, 0);
        Intent target = intent.getParcelableExtra(KEY_TARGET);
        return new ProxyPendingRecord(target, userId);
    }

    @Override
    public String toString() {
        return "ProxyPendingActivityRecord{" +
                "mUserId=" + mUserId +
                ", mTarget=" + mTarget +
                '}';
    }
}
