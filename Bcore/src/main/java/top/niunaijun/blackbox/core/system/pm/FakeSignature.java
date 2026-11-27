package top.niunaijun.blackbox.core.system.pm;

import android.content.pm.ApplicationInfo;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.content.res.Resources;
import android.os.Build;
import android.os.Bundle;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.utils.Slog;

/**
 * microG-style signature spoofing for virtual packages.
 *
 * A package that requests {@link #PERMISSION} and carries a "fake-signature" meta-data entry
 * (microG GmsCore and its companion store) is reported with that certificate, as signature
 * spoofing ROMs do. Apps then see the Google signature on the virtual com.google.android.gms.
 */
final class FakeSignature {
    private static final String TAG = "FakeSignature";
    static final String PERMISSION = "android.permission.FAKE_PACKAGE_SIGNATURE";
    static final String META_DATA = "fake-signature";

    private static final Signature[] NONE = new Signature[0];
    private static final Map<String, Signature[]> CACHE = new ConcurrentHashMap<>();

    private FakeSignature() {
    }

    /** The spoofed signatures of p, or null when p does not ask for spoofing. */
    static Signature[] get(BPackage p) {
        if (p == null || p.mAppMetaData == null || p.requestedPermissions == null
                || !p.requestedPermissions.contains(PERMISSION)
                || !p.mAppMetaData.containsKey(META_DATA)) {
            return null;
        }
        String key = p.packageName + "@" + p.baseCodePath;
        Signature[] cached = CACHE.get(key);
        if (cached == null) {
            cached = load(p);
            CACHE.put(key, cached);
        }
        return cached.length == 0 ? null : cached;
    }

    private static Signature[] load(BPackage p) {
        try {
            Bundle md = p.mAppMetaData;
            Object v = md.get(META_DATA);
            String hex = null;
            if (v instanceof String) {
                hex = (String) v;
            } else if (v instanceof Integer && p.baseCodePath != null) {
                // Unresolved @string reference: read it from the package's own resources.
                ApplicationInfo ai = new ApplicationInfo();
                ai.packageName = p.packageName;
                ai.sourceDir = p.baseCodePath;
                ai.publicSourceDir = p.baseCodePath;
                Resources res = BlackBoxCore.getPackageManager().getResourcesForApplication(ai);
                hex = res.getString((Integer) v);
            }
            if (hex == null || hex.trim().isEmpty()) {
                Slog.w(TAG, "no usable fake-signature in " + p.packageName);
                return NONE;
            }
            Slog.d(TAG, "spoofing signature of " + p.packageName);
            return new Signature[]{new Signature(hex.trim())};
        } catch (Throwable t) {
            Slog.e(TAG, "fake-signature of " + p.packageName + " unreadable", t);
            return NONE;
        }
    }

    /** A SigningInfo holding sigs, or null when the platform offers no public constructor. */
    static SigningInfo signingInfo(Signature[] sigs) {
        if (Build.VERSION.SDK_INT >= 35) {
            try {
                java.util.List<Signature> signers = java.util.Arrays.asList(sigs);
                return new SigningInfo(2, signers, Collections.emptyList(), signers);
            } catch (Throwable t) {
                Slog.w(TAG, "SigningInfo constructor failed: " + t);
            }
        }
        return null;
    }
}
