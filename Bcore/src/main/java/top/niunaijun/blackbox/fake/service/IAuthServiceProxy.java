package top.niunaijun.blackbox.fake.service;

import android.os.IBinder;

import java.lang.reflect.Method;

import black.android.hardware.biometrics.BRIAuthServiceStub;
import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.MethodParameterUtils;
import top.niunaijun.blackbox.utils.Slog;

/**
 * AuthService notes USE_BIOMETRIC against the opPackageName and rejects a package
 * that does not belong to the calling uid. GMS (virtual) passes its own package
 * while running under the host uid, which crashed the add-account flow.
 * Present the host package instead.
 */
public class IAuthServiceProxy extends BinderInvocationStub {
    private static final String SERVICE = "auth";

    public IAuthServiceProxy() {
        super(BRServiceManager.get().getService(SERVICE));
    }

    @Override
    protected Object getWho() {
        IBinder binder = BRServiceManager.get().getService(SERVICE);
        return binder == null ? null : BRIAuthServiceStub.get().asInterface(binder);
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(SERVICE);
    }

    @Override
    public boolean isBadEnv() {
        return BRServiceManager.get().getService(SERVICE) == null;
    }

    private abstract static class HostPkgHook extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceFirstAppPkg(args);
            return method.invoke(who, args);
        }
    }

    private static final int BIOMETRIC_ERROR_NO_HARDWARE = 12;

    // GMS's BiometricPrompt sets logo/content-view fields, which make the host
    // demand SET_BIOMETRIC_DIALOG_ADVANCED (system-only). Clear them so a plain
    // prompt is shown under the host package.
    private static final String[] ADVANCED_FIELDS = {
            "mLogoRes", "mLogoBitmap", "mLogoDescription", "mContentView",
    };

    private static void stripAdvancedPromptFields(Object[] args) {
        if (args == null) {
            return;
        }
        for (Object arg : args) {
            if (arg == null || !"android.hardware.biometrics.PromptInfo".equals(arg.getClass().getName())) {
                continue;
            }
            for (String name : ADVANCED_FIELDS) {
                try {
                    java.lang.reflect.Field f = arg.getClass().getDeclaredField(name);
                    f.setAccessible(true);
                    Class<?> t = f.getType();
                    if (t == int.class) {
                        f.setInt(arg, 0);
                    } else if (!t.isPrimitive()) {
                        f.set(arg, null);
                    }
                } catch (Throwable e) {
                    Slog.w("IAuthServiceProxy", "cannot clear PromptInfo." + name, e);
                }
            }
        }
    }

    @ProxyMethod("authenticate")
    public static class Authenticate extends HostPkgHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            stripAdvancedPromptFields(args);
            try {
                return super.hook(who, method, args);
            } catch (java.lang.reflect.InvocationTargetException e) {
                if (e.getCause() instanceof SecurityException) {
                    Slog.w("IAuthServiceProxy", "authenticate denied for virtual process", e.getCause());
                    return 0L;
                }
                throw e.getCause();
            }
        }
    }

    @ProxyMethod("canAuthenticate")
    public static class CanAuthenticate extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return BIOMETRIC_ERROR_NO_HARDWARE;
        }
    }

    @ProxyMethod("hasEnrolledBiometrics")
    public static class HasEnrolledBiometrics extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return false;
        }
    }

    @ProxyMethod("cancelAuthentication")
    public static class CancelAuthentication extends HostPkgHook {
    }
}
