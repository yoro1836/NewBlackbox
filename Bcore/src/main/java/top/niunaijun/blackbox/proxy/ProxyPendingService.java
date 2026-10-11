package top.niunaijun.blackbox.proxy;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;

import androidx.annotation.Nullable;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.proxy.record.ProxyPendingRecord;
import top.niunaijun.blackbox.utils.Slog;

/**
 * Host-side target of service PendingIntents created by virtual apps.
 *
 * Without it the system started the real (host) package's service, so a virtual
 * GMS never received its own IntentOperationService SAVED_INTENT and stopped
 * processing every queued operation, including checkin.
 */
public class ProxyPendingService extends Service {
    public static final String TAG = "ProxyPendingService";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            if (intent != null) {
                intent.setExtrasClassLoader(ProxyPendingService.class.getClassLoader());
                ProxyPendingRecord record = ProxyPendingRecord.create(intent);
                Intent target = record.mTarget;
                if (target != null) {
                    // PendingIntent.send() fill-in extras land on the shadow intent; hand them on.
                    Bundle extras = intent.getExtras();
                    if (extras != null) {
                        extras.remove(ProxyPendingRecord.KEY_USER_ID);
                        extras.remove(ProxyPendingRecord.KEY_TARGET);
                        if (!extras.isEmpty()) {
                            target.putExtras(extras);
                        }
                    }
                    BlackBoxCore.getBActivityManager().startService(target, null, false, record.mUserId);
                }
            }
        } catch (Throwable e) {
            Slog.w(TAG, "failed to deliver pending service intent", e);
        }
        stopSelf(startId);
        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
