package top.niunaijun.blackbox.fake.service;

import android.os.IBinder;

import java.lang.reflect.Method;
import java.util.ArrayList;

import black.android.content.pm.BRICrossProfileAppsStub;
import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;

/**
 * The host system rejects cross-profile queries whose callingPackage is a virtual app
 * ("Package com.android.vending does not belong to 10613"). Play Store's account menu asks
 * for target profiles while building itself and died there, leaving a blank dialog.
 * The sandbox has no other profiles, so answer "none".
 */
public class ICrossProfileAppsProxy extends BinderInvocationStub {
    private static final String SERVICE = "crossprofileapps";

    public ICrossProfileAppsProxy() {
        super(BRServiceManager.get().getService(SERVICE));
    }

    @Override
    protected Object getWho() {
        IBinder binder = BRServiceManager.get().getService(SERVICE);
        return binder == null ? null : BRICrossProfileAppsStub.get().asInterface(binder);
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(SERVICE);
    }

    @Override
    public boolean isBadEnv() {
        return BRServiceManager.get().getService(SERVICE) == null;
    }

    @ProxyMethod("getTargetUserProfiles")
    public static class GetTargetUserProfiles extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) {
            return new ArrayList<>();
        }
    }

    @ProxyMethod("canInteractAcrossProfiles")
    public static class CanInteractAcrossProfiles extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) {
            return false;
        }
    }

    @ProxyMethod("canRequestInteractAcrossProfiles")
    public static class CanRequestInteractAcrossProfiles extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) {
            return false;
        }
    }
}
