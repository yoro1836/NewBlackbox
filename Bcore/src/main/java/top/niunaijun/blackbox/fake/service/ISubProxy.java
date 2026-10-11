package top.niunaijun.blackbox.fake.service;

import android.os.IBinder;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.Slog;


public class ISubProxy extends BinderInvocationStub {
    public static final String TAG = "ISubProxy";

    public ISubProxy() {
        super(BRServiceManager.get().getService("isub"));
    }

    @Override
    protected Object getWho() {
        try {
            IBinder binder = BRServiceManager.get().getService("isub");
            if (binder == null) {
                return null;
            }
            Class<?> stub = Class.forName("com.android.internal.telephony.ISub$Stub");
            return stub.getMethod("asInterface", IBinder.class).invoke(null, binder);
        } catch (Throwable e) {
            Slog.w(TAG, "Failed to get ISub interface", e);
            return null;
        }
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService("isub");
        clearSubscriptionManagerCache();
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    private static void clearSubscriptionManagerCache() {
        try {
            Class<?> manager = Class.forName("android.telephony.SubscriptionManager");
            for (Field field : manager.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                if (!"com.android.internal.telephony.ISub".equals(field.getType().getName())) {
                    continue;
                }
                field.setAccessible(true);
                field.set(null, null);
            }
        } catch (Throwable e) {
            Slog.w(TAG, "Failed to clear SubscriptionManager cache", e);
        }
    }

    // Virtual users have no SIM and no carrier privileges: report no active
    // subscriptions instead of letting the host throw SecurityException.
    @ProxyMethod("getActiveSubIdList")
    public static class GetActiveSubIdList extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) {
            return new int[0];
        }
    }
}
