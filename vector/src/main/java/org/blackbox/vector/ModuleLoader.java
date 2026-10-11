// SPDX-License-Identifier: GPL-3.0-only
package org.blackbox.vector;

import android.content.pm.ApplicationInfo;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import dalvik.system.PathClassLoader;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import de.robv.android.xposed.callbacks.XCallback;

/** Loads legacy (de.robv) Xposed module APKs and dispatches package-load events to them. */
public final class ModuleLoader {
    private static final String TAG = "BBXposedLoader";
    private static final String INIT_ASSET = "assets/xposed_init";

    private static final Set<String> sLoadedApks = ConcurrentHashMap.newKeySet();

    private ModuleLoader() {
    }

    /**
     * Instantiates every entry class listed in the module's {@code assets/xposed_init}. Each APK is
     * loaded once per process; returns true when the module is (now or already) loaded.
     */
    public static boolean loadModule(String apkPath, String nativeLibDir) {
        if (!sLoadedApks.add(apkPath)) {
            return true;
        }
        try {
            if (!XposedBridge.ensureEngine()) {
                sLoadedApks.remove(apkPath);
                return false;
            }
            ClassLoader loader = new PathClassLoader(apkPath, nativeLibDir, XposedBridge.BOOTCLASSLOADER);
            int entries = 0;
            for (String className : readEntryClasses(apkPath)) {
                try {
                    Class<?> clazz = Class.forName(className, true, loader);
                    Object instance = clazz.getDeclaredConstructor().newInstance();
                    if (instance instanceof IXposedHookZygoteInit) {
                        XposedBridge.initModule((IXposedHookZygoteInit) instance, apkPath);
                    }
                    if (instance instanceof IXposedHookLoadPackage) {
                        XposedBridge.hookLoadPackage(new IXposedHookLoadPackage.Wrapper((IXposedHookLoadPackage) instance));
                    }
                    entries++;
                } catch (Throwable t) {
                    XposedBridge.log("Failed to initialize module entry " + className + " of " + apkPath);
                    XposedBridge.log(t);
                }
            }
            return entries > 0;
        } catch (Throwable t) {
            sLoadedApks.remove(apkPath);
            XposedBridge.log("Failed to load module " + apkPath);
            XposedBridge.log(t);
            return false;
        }
    }

    /** Tells every registered module that a package is loading in this process. */
    public static void dispatchPackageLoad(String packageName, String processName,
                                           ApplicationInfo appInfo, boolean isFirstApp,
                                           ClassLoader classLoader) {
        XC_LoadPackage.LoadPackageParam param =
                new XC_LoadPackage.LoadPackageParam(XposedBridge.sLoadedPackageCallbacks);
        param.packageName = packageName;
        param.processName = processName;
        param.appInfo = appInfo;
        param.isFirstApplication = isFirstApp;
        param.classLoader = classLoader;
        XCallback.callAll(param);
    }

    private static java.util.List<String> readEntryClasses(String apkPath) throws Exception {
        java.util.List<String> classes = new java.util.ArrayList<>();
        try (ZipFile zip = new ZipFile(new File(apkPath))) {
            ZipEntry entry = zip.getEntry(INIT_ASSET);
            if (entry == null) {
                return classes;
            }
            try (InputStream in = zip.getInputStream(entry);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty() && !line.startsWith("#")) {
                        classes.add(line);
                    }
                }
            }
        }
        return classes;
    }
}
