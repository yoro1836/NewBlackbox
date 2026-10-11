package top.niunaijun.blackbox.utils.compat;

import android.content.Intent;

import java.lang.reflect.Method;

/** Compatibility helpers for hidden Intent APIs that vary by Android release. */
public final class IntentCompat {
    private static final Method COLLECT_EXTRA_INTENT_KEYS = findCollectExtraIntentKeys();

    private IntentCompat() {
    }

    /**
     * Marks nested Intent extras before a proxy Intent crosses into the host system.
     * Older releases do not expose this hidden API, so they simply retain the
     * existing proxy behavior.
     */
    public static void collectExtraIntentKeys(Intent intent) {
        if (intent == null || COLLECT_EXTRA_INTENT_KEYS == null) {
            return;
        }
        try {
            COLLECT_EXTRA_INTENT_KEYS.invoke(intent);
        } catch (Throwable ignored) {
            // The method is hidden and is not available on all Android releases.
        }
    }

    private static Method findCollectExtraIntentKeys() {
        try {
            Method method = Intent.class.getDeclaredMethod("collectExtraIntentKeys");
            method.setAccessible(true);
            return method;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
