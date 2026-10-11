package top.niunaijun.blackbox.core.xposed;

import android.content.pm.ApplicationInfo;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.core.env.BEnvironment;
import top.niunaijun.blackbox.core.system.user.BUserInfo;
import top.niunaijun.blackbox.fake.frameworks.BPackageManager;
import top.niunaijun.blackbox.fake.frameworks.BUserManager;
import top.niunaijun.blackbox.utils.Slog;

/**
 * Loads Xposed modules (LSPlant engine from the :vector module) into virtual app processes.
 *
 * <p>Scope: Java method hooks only (no resource hooks yet). Enabled modules
 * apply to every virtual app.
 */
public final class XposedManager {
    private static final String TAG = "XposedManager";

    private static volatile boolean sEngineReady;
    private static volatile boolean sEngineProbed;

    private XposedManager() {
    }

    /**
     * Called from {@code BActivityThread} right before the virtual
     * application's {@code onCreate}. Never throws.
     */
    public static void onAppLoading(String packageName, String processName,
                                    android.content.Context packageContext) {
        try {
            Set<String> enabled = loadEnabledModules();
            if (enabled.isEmpty()) {
                return;
            }
            if (!ensureEngine()) {
                return;
            }
            ApplicationInfo appInfo;
            try {
                appInfo = BPackageManager.get()
                        .getApplicationInfo(packageName, 0, BActivityThread.getUserId());
            } catch (Throwable t) {
                Slog.w(TAG, "No app info for " + packageName + ", skipping modules", t);
                return;
            }
            ClassLoader appClassLoader = packageContext.getClassLoader();
            boolean isFirstApp = processName.equals(packageName);
            int loaded = 0;
            for (String modulePkg : enabled) {
                if (modulePkg.equals(packageName) || !isInScope(modulePkg, packageName)) {
                    continue;
                }
                if (loadModule(modulePkg)) {
                    loaded++;
                }
            }
            if (loaded == 0) {
                Slog.w(TAG, "No Xposed module loaded for " + packageName + " (enabled=" + enabled + ")");
            }
            if (loaded > 0) {
                org.blackbox.vector.ModuleLoader.dispatchPackageLoad(packageName, processName,
                        appInfo, isFirstApp, appClassLoader);
                Slog.d(TAG, loaded + " module(s) dispatched into " + packageName);
            }
        } catch (Throwable t) {
            Slog.w(TAG, "Xposed init failed, app continues unhooked", t);
        }
    }

    /** Loads one module APK; true when its callbacks registered. */
    private static boolean loadModule(String modulePkg) {
        try {
            // A module is just an APK: take it from whichever virtual user it was installed into.
            ApplicationInfo moduleInfo = BPackageManager.get()
                    .getApplicationInfo(modulePkg, 0, BActivityThread.getUserId());
            if (moduleInfo == null) {
                for (BUserInfo user : BUserManager.get().getUsers()) {
                    moduleInfo = BPackageManager.get().getApplicationInfo(modulePkg, 0, user.id);
                    if (moduleInfo != null) {
                        break;
                    }
                }
            }
            if (moduleInfo == null || moduleInfo.sourceDir == null) {
                Slog.w(TAG, "Module " + modulePkg + " is not installed in any virtual user");
                return false;
            }
            File apk = new File(moduleInfo.sourceDir);
            if (!apk.isFile()) {
                Slog.w(TAG, "Module apk missing: " + apk);
                return false;
            }
            return org.blackbox.vector.ModuleLoader.loadModule(apk.getAbsolutePath(),
                    moduleInfo.nativeLibraryDir);
        } catch (Throwable t) {
            Slog.w(TAG, "Failed to load module " + modulePkg, t);
            return false;
        }
    }

    private static synchronized boolean ensureEngine() {
        if (sEngineReady) {
            return true;
        }
        if (sEngineProbed) {
            return false;
        }
        sEngineProbed = true;
        try {
            sEngineReady = de.robv.android.xposed.XposedBridge.ensureEngine();
            Slog.d(TAG, "LSPlant engine ready=" + sEngineReady);
        } catch (Throwable t) {
            Slog.w(TAG, "LSPlant engine unavailable, Xposed dormant", t);
        }
        return sEngineReady;
    }

    /** Enabled module package names, one per line. */
    public static Set<String> loadEnabledModules() {
        File conf = BEnvironment.getXPModuleConf();
        if (conf == null || !conf.isFile()) {
            return Collections.emptySet();
        }
        Set<String> out = new LinkedHashSet<>();
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(conf));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) {
                    out.add(line);
                }
            }
        } catch (IOException ignored) {
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignored) {
                }
            }
        }
        return out;
    }

    private static File scopeConf() {
        File conf = BEnvironment.getXPModuleConf();
        return conf == null ? null : new File(conf.getParentFile(), "xposed-scope.conf");
    }

    private static java.util.Map<String, Set<String>> readScopes() {
        java.util.Map<String, Set<String>> out = new java.util.LinkedHashMap<>();
        File conf = scopeConf();
        if (conf == null || !conf.isFile()) {
            return out;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(conf))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                int eq = line.indexOf('=');
                if (line.isEmpty() || line.startsWith("#") || eq <= 0) {
                    continue;
                }
                Set<String> pkgs = new LinkedHashSet<>();
                for (String pkg : line.substring(eq + 1).split(",")) {
                    if (!pkg.trim().isEmpty()) {
                        pkgs.add(pkg.trim());
                    }
                }
                out.put(line.substring(0, eq).trim(), pkgs);
            }
        } catch (IOException e) {
            Slog.w(TAG, "Failed to read scope conf", e);
        }
        return out;
    }

    /**
     * Apps a module is applied to, or null when no scope was ever stored for it: such modules
     * (enabled before scopes existed) keep applying to every virtual app.
     */
    public static Set<String> loadScope(String modulePkg) {
        return readScopes().get(modulePkg);
    }

    public static synchronized void saveScope(String modulePkg, Set<String> packages) {
        File conf = scopeConf();
        if (conf == null) {
            return;
        }
        java.util.Map<String, Set<String>> all = readScopes();
        all.put(modulePkg, new LinkedHashSet<>(packages));
        File parent = conf.getParentFile();
        if (parent != null) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        try (FileWriter writer = new FileWriter(conf, false)) {
            for (java.util.Map.Entry<String, Set<String>> e : all.entrySet()) {
                writer.write(e.getKey() + "=" + android.text.TextUtils.join(",", e.getValue()) + "\n");
            }
        } catch (IOException e) {
            Slog.w(TAG, "Failed to save scope conf", e);
        }
    }

    /** True when {@code modulePkg} should be loaded into {@code appPkg}. */
    public static boolean isInScope(String modulePkg, String appPkg) {
        Set<String> scope = loadScope(modulePkg);
        return scope == null || scope.contains(appPkg);
    }

    public static void saveEnabledModules(Set<String> modules) {
        File conf = BEnvironment.getXPModuleConf();
        if (conf == null) {
            return;
        }
        File parent = conf.getParentFile();
        if (parent != null) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        FileWriter writer = null;
        try {
            writer = new FileWriter(conf, false);
            for (String pkg : modules) {
                writer.write(pkg);
                writer.write('\n');
            }
        } catch (IOException e) {
            Slog.w(TAG, "Failed to save module conf", e);
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}
