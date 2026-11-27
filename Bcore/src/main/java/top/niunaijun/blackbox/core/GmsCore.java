package top.niunaijun.blackbox.core;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.io.File;

import java.util.HashSet;
import java.util.Set;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.entity.pm.InstallResult;


public class GmsCore {
    private static final String TAG = "GmsCore";

    private static final HashSet<String> GOOGLE_APP = new HashSet<>();
    private static final HashSet<String> GOOGLE_SERVICE = new HashSet<>();
    public static final String GMS_PKG = "com.google.android.gms";
    public static final String GSF_PKG = "com.google.android.gsf";
    public static final String VENDING_PKG = "com.android.vending";

    static {
        GOOGLE_APP.add(VENDING_PKG);
        GOOGLE_APP.add("com.google.android.play.games");
        GOOGLE_APP.add("com.google.android.wearable.app");
        GOOGLE_APP.add("com.google.android.wearable.app.cn");

        
        GOOGLE_SERVICE.add(GMS_PKG);
        GOOGLE_SERVICE.add(GSF_PKG);
        GOOGLE_SERVICE.add("com.google.android.gsf.login");
        GOOGLE_SERVICE.add("com.google.android.backuptransport");
        GOOGLE_SERVICE.add("com.google.android.backup");
        GOOGLE_SERVICE.add("com.google.android.configupdater");
        GOOGLE_SERVICE.add("com.google.android.syncadapters.contacts");
        GOOGLE_SERVICE.add("com.google.android.feedback");
        GOOGLE_SERVICE.add("com.google.android.onetimeinitializer");
        GOOGLE_SERVICE.add("com.google.android.partnersetup");
        GOOGLE_SERVICE.add("com.google.android.setupwizard");
        GOOGLE_SERVICE.add("com.google.android.syncadapters.calendar");
    }

    public static boolean isGoogleService(String packageName) {
        return GOOGLE_SERVICE.contains(packageName);
    }

    public static boolean isGoogleAppOrService(String str) {
        return GOOGLE_APP.contains(str) || GOOGLE_SERVICE.contains(str);
    }

    private static InstallResult installPackages(Set<String> list, int userId) {
        BlackBoxCore blackBoxCore = BlackBoxCore.get();
        for (String packageName : list) {
            if (blackBoxCore.isInstalled(packageName, userId)) {
                continue;
            }
            try {
                BlackBoxCore.getContext().getPackageManager().getApplicationInfo(packageName, 0);
            } catch (PackageManager.NameNotFoundException e) {
                if (GMS_PKG.equals(packageName) || GSF_PKG.equals(packageName)
                        || VENDING_PKG.equals(packageName)) {
                    return new InstallResult().installError(packageName,
                            "Required Google package is not visible on the host: " + packageName);
                }
                continue;
            }
            InstallResult installResult = blackBoxCore.installPackageAsUser(packageName, userId);
            if (installResult == null) {
                return new InstallResult().installError(packageName,
                        "Virtual package manager did not return an installation result");
            }
            if (!installResult.success) {
                return installResult;
            }
        }
        return new InstallResult();
    }

    private static void uninstallPackages(Set<String> list, int userId) {
        BlackBoxCore blackBoxCore = BlackBoxCore.get();
        for (String packageName : list) {
            blackBoxCore.uninstallPackageAsUser(packageName, userId);
        }
    }

    public static InstallResult installGApps(int userId) {
        int other = otherGmsUser(userId, false);
        if (other >= 0) {
            return new InstallResult().installError(GMS_PKG,
                    "User " + other + " uses microG; turn it off there first.");
        }
        Set<String> googleApps = new HashSet<>();

        googleApps.addAll(GOOGLE_SERVICE);
        googleApps.addAll(GOOGLE_APP);

        InstallResult installResult = installPackages(googleApps, userId);
        if (!installResult.success) {
            uninstallGApps(userId);
            return installResult;
        }
        return installResult;
    }

    public static void uninstallGApps(int userId) {
        uninstallPackages(GOOGLE_SERVICE, userId);
        uninstallPackages(GOOGLE_APP, userId);
    }

    /**
     * Installs microG (GmsCore + companion store) as the user's Google services in place of a
     * copy of the host GMS. microG implements the GMS APIs in open source; the virtual package
     * manager spoofs its Google signature (see FakeSignature). It ships its own Gservices
     * provider, so the host GSF is not copied.
     */
    public static InstallResult installMicroG(int userId, File gmsApk, File vendingApk) {
        BlackBoxCore core = BlackBoxCore.get();
        int other = otherGmsUser(userId, true);
        if (other >= 0) {
            return new InstallResult().installError(GMS_PKG,
                    "User " + other + " uses the device's Google services; turn them off there first.");
        }
        uninstallGApps(userId);
        InstallResult r = core.installPackageAsUser(gmsApk, userId);
        if (r == null || !r.success) {
            return r != null ? r : new InstallResult().installError(GMS_PKG, "microG install returned no result");
        }
        if (!GMS_PKG.equals(r.packageName)) {
            core.uninstallPackageAsUser(r.packageName, userId);
            return new InstallResult().installError(GMS_PKG, "Not a microG GmsCore APK: " + r.packageName);
        }
        if (vendingApk != null) {
            InstallResult v = core.installPackageAsUser(vendingApk, userId);
            if (v == null || !v.success) {
                uninstallGApps(userId);
                return v != null ? v : new InstallResult().installError(VENDING_PKG, "Companion install returned no result");
            }
        }
        return r;
    }

    /** True when the virtual com.google.android.gms is microG (it asks for signature spoofing). */
    public static boolean isMicroG(int userId) {
        try {
            if (!BlackBoxCore.get().isInstalled(GMS_PKG, userId)) {
                return false;
            }
            ApplicationInfo ai = BlackBoxCore.getBPackageManager()
                    .getApplicationInfo(GMS_PKG, PackageManager.GET_META_DATA, userId);
            return ai != null && ai.metaData != null && ai.metaData.containsKey("fake-signature");
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Virtual packages are shared by all virtual users, so one user cannot run microG while
     * another runs the host GMS copy. Returns the other user that blocks the switch, or -1.
     */
    private static int otherGmsUser(int userId, boolean wantMicroG) {
        try {
            for (top.niunaijun.blackbox.core.system.user.BUserInfo u : BlackBoxCore.get().getUsers()) {
                if (u.id != userId && BlackBoxCore.get().isInstalled(GMS_PKG, u.id)
                        && isMicroG(u.id) != wantMicroG) {
                    return u.id;
                }
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    public static void remove(String packageName) {
        GOOGLE_SERVICE.remove(packageName);
        GOOGLE_APP.remove(packageName);
    }


    public static boolean isSupportGms() {
        try {
            BlackBoxCore.getPackageManager().getPackageInfo(GMS_PKG, 0);
            return true;
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        return false;
    }

    public static boolean isInstalledGoogleService(int userId) {
        return BlackBoxCore.get().isInstalled(GMS_PKG, userId);
    }
}