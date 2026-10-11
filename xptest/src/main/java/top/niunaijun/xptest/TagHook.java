package top.niunaijun.xptest;

import android.widget.TextView;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Proof module: prefixes all TextView text with [XP] in every hooked app. */
public class TagHook implements IXposedHookLoadPackage {
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if ("top.niunaijun.xptest".equals(lpparam.packageName)) {
            return;
        }
        hookExitTracers();
        XposedHelpers.findAndHookMethod(TextView.class, "setText", CharSequence.class,
                TextView.BufferType.class, boolean.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        CharSequence text = (CharSequence) param.args[0];
                        if (text != null && !text.toString().startsWith("[XP] ")) {
                            param.args[0] = "[XP] " + text;
                        }
                    }
                });
    }

    /** TEMP-DIAG: log (not block) every self-exit attempt with its caller. */
    private static void hookExitTracers() {
        try {
            XposedHelpers.findAndHookMethod(Runtime.class, "exit", int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            android.util.Log.e("ExitTracer",
                                    "Runtime.exit(" + param.args[0] + ") called",
                                    new Throwable("exit-caller"));
                        }
                    });
        } catch (Throwable t) {
            android.util.Log.w("ExitTracer", "cannot hook Runtime.exit: " + t);
        }
        try {
            XposedHelpers.findAndHookMethod(android.os.Process.class, "killProcess", int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            android.util.Log.e("ExitTracer",
                                    "killProcess(" + param.args[0] + ") called",
                                    new Throwable("kill-caller"));
                        }
                    });
        } catch (Throwable t) {
            android.util.Log.w("ExitTracer", "cannot hook killProcess: " + t);
        }
    }
}
