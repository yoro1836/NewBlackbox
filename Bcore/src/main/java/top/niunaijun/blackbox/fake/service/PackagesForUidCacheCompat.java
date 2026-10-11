package top.niunaijun.blackbox.fake.service;

import java.lang.reflect.Method;

import top.niunaijun.blackbox.fake.hook.IInjectHook;
import top.niunaijun.blackbox.utils.Slog;

/**
 * ApplicationPackageManager keeps a process-wide getPackagesForUid cache keyed by uid.
 * Our getPackagesForUid hook answers the host uid relative to the current binder caller,
 * so a cached answer is wrong for every later caller: GMS kept rejecting Play Store with
 * "Package not matching uid 10613 com.android.vending" after the first lookup.
 */
public class PackagesForUidCacheCompat implements IInjectHook {
    private static final String TAG = "PackagesForUidCacheCompat";

    private static volatile boolean sInstalled;

    @Override
    public void injectHook() {
        install();
    }

    @Override
    public boolean isBadEnv() {
        return !sInstalled;
    }

    public static void install() {
        if (sInstalled) {
            return;
        }
        synchronized (PackagesForUidCacheCompat.class) {
            if (sInstalled) {
                return;
            }
            try {
                Class<?> apm = Class.forName("android.app.ApplicationPackageManager");
                Method disable = apm.getDeclaredMethod("disableGetPackagesForUidCache");
                disable.setAccessible(true);
                disable.invoke(null);
                sInstalled = true;
                Slog.d(TAG, "disabled framework getPackagesForUid cache");
            } catch (Throwable e) {
                Slog.w(TAG, "install failed: " + e.getMessage(), e);
            }
        }
    }
}
