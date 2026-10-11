package top.niunaijun.blackbox.fake.service;

import android.os.IBinder;
import android.os.LocaleList;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import black.android.app.BRILocaleManagerStub;
import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.Slog;

/** Prevents virtual apps from sending signature-protected locale writes to system_server. */
public class ILocaleManagerProxy extends BinderInvocationStub {
    public static final String TAG = "ILocaleManagerProxy";

    public ILocaleManagerProxy() {
        super(BRServiceManager.get().getService("locale"));
    }

    @Override
    protected Object getWho() {
        IBinder binder = BRServiceManager.get().getService("locale");
        return binder == null ? null : BRILocaleManagerStub.get().asInterface(binder);
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService("locale");
    }

    @Override
    public boolean isBadEnv() {
        return BRServiceManager.get().getService("locale") == null;
    }

    @ProxyMethod("setApplicationLocales")
    public static class SetApplicationLocales extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(who, args);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof SecurityException) {
                    Slog.w(TAG, "setApplicationLocales denied for virtual process", cause);
                    return null;
                }
                throw cause;
            }
        }
    }

    @ProxyMethod("getApplicationLocales")
    public static class GetApplicationLocales extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                Object result = method.invoke(who, args);
                return result == null ? LocaleList.getEmptyLocaleList() : result;
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof SecurityException) {
                    return LocaleList.getEmptyLocaleList();
                }
                throw cause;
            }
        }
    }

    @ProxyMethod("setSystemLocales")
    public static class SetSystemLocales extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) {
            Slog.d(TAG, "setSystemLocales intercepted");
            return null;
        }
    }
}

