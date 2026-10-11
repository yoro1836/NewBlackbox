package top.niunaijun.blackbox.fake.service;

import android.os.IBinder;

import java.lang.reflect.Method;

import black.android.os.BRServiceManager;
import black.android.safetycenter.BRISafetyCenterManagerStub;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;

/**
 * SafetyCenter queries need READ_SAFETY_CENTER_STATUS, which the host app cannot hold.
 * GMS's accountsettings ModuleInitializer asks it from an IntentOperation and the uncaught
 * SecurityException killed the whole virtual GMS process. Report it as disabled.
 */
public class ISafetyCenterManagerProxy extends BinderInvocationStub {
    private static final String SERVICE = "safety_center";

    public ISafetyCenterManagerProxy() {
        super(BRServiceManager.get().getService(SERVICE));
    }

    @Override
    protected Object getWho() {
        IBinder binder = BRServiceManager.get().getService(SERVICE);
        return binder == null ? null : BRISafetyCenterManagerStub.get().asInterface(binder);
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(SERVICE);
    }

    @Override
    public boolean isBadEnv() {
        return BRServiceManager.get().getService(SERVICE) == null;
    }

    @ProxyMethod("isSafetyCenterEnabled")
    public static class IsSafetyCenterEnabled extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) {
            return false;
        }
    }
}
