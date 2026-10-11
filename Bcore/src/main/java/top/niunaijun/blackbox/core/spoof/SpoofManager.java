package top.niunaijun.blackbox.core.spoof;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.LinkedHashMap;
import java.util.Map;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.NativeCore;
import top.niunaijun.blackbox.utils.Slog;

/**
 * User-configurable device profile (model/brand/fingerprint...).
 * Applied per virtual process over the compiled-in defaults.
 */
public final class SpoofManager {
    private static final String TAG = "SpoofManager";
    private static final String PREFS = "device_spoof";

    /** Property key -> pref key. */
    private static final String[][] ENTRIES = {
            {"ro.product.model", "model"},
            {"ro.product.brand", "brand"},
            {"ro.product.manufacturer", "manufacturer"},
            {"ro.product.device", "device"},
            {"ro.build.fingerprint", "fingerprint"},
    };

    private SpoofManager() {
    }

    public static Map<String, String> defaults() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("ro.product.model", "Pixel 6");
        map.put("ro.product.brand", "google");
        map.put("ro.product.manufacturer", "Google");
        map.put("ro.product.device", "oriole");
        map.put("ro.build.fingerprint",
                "google/oriole/oriole:12/SP1A.210812.015/7679548:user/release-keys");
        return map;
    }

    public static Map<String, String> loadProfile() {
        Map<String, String> profile = defaults();
        try {
            Context context = BlackBoxCore.getContext();
            if (context == null) {
                return profile;
            }
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            for (String[] entry : ENTRIES) {
                String custom = prefs.getString(entry[1], null);
                if (custom != null && !custom.isEmpty()) {
                    profile.put(entry[0], custom);
                }
            }
        } catch (Throwable t) {
            Slog.w(TAG, "Failed to load spoof profile", t);
        }
        return profile;
    }

    public static void saveProfile(Map<String, String> values) {
        try {
            Context context = BlackBoxCore.getContext();
            if (context == null) {
                return;
            }
            SharedPreferences.Editor editor =
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
            for (String[] entry : ENTRIES) {
                // Accept both short keys ("model") and prop keys ("ro.product.model").
                String value = values.get(entry[1]);
                if (value == null) {
                    value = values.get(entry[0]);
                }
                if (value == null || value.isEmpty()) {
                    editor.remove(entry[1]);
                } else {
                    editor.putString(entry[1], value);
                }
            }
            editor.apply();
        } catch (Throwable t) {
            Slog.w(TAG, "Failed to save spoof profile", t);
        }
    }

    /** Pushes the profile into the native property hook and Build fields. Never throws. */
    public static void applyProfile() {
        Map<String, String> profile;
        try {
            profile = loadProfile();
        } catch (Throwable t) {
            Slog.w(TAG, "Failed to load spoof profile", t);
            return;
        }
        try {
            NativeCore.clearSpoofProps();
            for (Map.Entry<String, String> e : profile.entrySet()) {
                NativeCore.setSpoofProp(e.getKey(), e.getValue());
            }
        } catch (Throwable t) {
            Slog.w(TAG, "Failed to apply native spoof profile", t);
        }
        // android.os.Build fields are Java statics fixed at boot; the native
        // property hook cannot change what DevCheck-style apps display.
        // Patch them in this process via reflection (runs before app code).
        patchBuildField("MODEL", profile.get("ro.product.model"));
        patchBuildField("BRAND", profile.get("ro.product.brand"));
        patchBuildField("MANUFACTURER", profile.get("ro.product.manufacturer"));
        patchBuildField("DEVICE", profile.get("ro.product.device"));
        patchBuildField("FINGERPRINT", profile.get("ro.build.fingerprint"));
        // Apps reading /system/build.prop directly (file, not API) bypass
        // both hooks above. Serve a patched copy via the IO redirect table.
        redirectBuildProp(profile);
    }

    private static void redirectBuildProp(java.util.Map<String, String> profile) {
        try {
            java.io.File src = new java.io.File("/system/build.prop");
            // Sealed devices deny reading /system/build.prop: serve a
            // profile-only file instead of skipping the redirect entirely.
            if (!src.isFile() || !src.canRead()) {
                writeBuildProp(profile, null);
                return;
            }
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.FileReader(src));
            StringBuilder out = new StringBuilder();
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    out.append(replacePropLine(line, profile)).append('\n');
                }
            } finally {
                try {
                    reader.close();
                } catch (java.io.IOException ignored) {
                }
            }
            StringBuilder content = out;
            writeBuildProp(profile, content);
        } catch (Throwable t) {
            Slog.w(TAG, "Failed to redirect build.prop", t);
        }
    }

    private static void writeBuildProp(
            java.util.Map<String, String> profile, StringBuilder patched) throws Exception {
        android.content.Context context = BlackBoxCore.getContext();
        if (context == null) {
            return;
        }
        StringBuilder out = (patched != null) ? patched : new StringBuilder();
        if (patched == null) {
            for (java.util.Map.Entry<String, String> e : profile.entrySet()) {
                out.append(e.getKey()).append('=').append(e.getValue()).append('\n');
            }
        }
        java.io.File dst = new java.io.File(context.getFilesDir(), "spoof_build.prop");
        java.io.FileWriter writer = new java.io.FileWriter(dst);
        try {
            writer.write(out.toString());
        } finally {
            try {
                writer.close();
            } catch (java.io.IOException ignored) {
            }
        }
        NativeCore.addIORule("/system/build.prop", dst.getAbsolutePath());
    }

    private static String replacePropLine(String line, java.util.Map<String, String> profile) {
        int eq = line.indexOf('=');
        if (eq <= 0 || line.startsWith("#")) {
            return line;
        }
        String key = line.substring(0, eq).trim();
        String value = profile.get(key);
        if (value == null) {
            return line;
        }
        return key + "=" + value;
    }

    private static void patchBuildField(String fieldName, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        try {
            java.lang.reflect.Field field = android.os.Build.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            try {
                field.set(null, value);
            } catch (IllegalAccessException e) {
                // ART refuses plain set on static final: strip FINAL first.
                // Hidden-API exemptions (NativeCore.disableHiddenApi) cover this.
                java.lang.reflect.Field modifiers =
                        java.lang.reflect.Field.class.getDeclaredField("modifiers");
                modifiers.setAccessible(true);
                modifiers.setInt(field, field.getModifiers() & ~java.lang.reflect.Modifier.FINAL);
                field.set(null, value);
            }
            Slog.d(TAG, "Spoofed Build." + fieldName + "=" + value);
        } catch (Throwable t) {
            Slog.w(TAG, "Failed to patch Build." + fieldName, t);
        }
    }
}
