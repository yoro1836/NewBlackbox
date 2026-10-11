package top.niunaijun.blackbox.fake.service;

import android.content.pm.PackageManager;

import java.lang.reflect.Method;

import black.android.app.BRActivityThread;
import black.android.app.BRContextImpl;
import black.android.os.BRServiceManager;
import black.android.permission.BRIPermissionManagerStub;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.service.base.PkgMethodProxy;
import top.niunaijun.blackbox.fake.service.base.ValueMethodProxy;
import top.niunaijun.blackbox.utils.Reflector;
import top.niunaijun.blackbox.utils.Slog;
import top.niunaijun.blackbox.utils.compat.BuildCompat;



public class IPermissionManagerProxy extends BinderInvocationStub {
    public static final String TAG = "IPermissionManagerProxy";

    private static final String P = "permissionmgr";

    public IPermissionManagerProxy() {
        super(BRServiceManager.get().getService(P));
    }

    @Override
    protected Object getWho() {
        return BRIPermissionManagerStub.get().asInterface(BRServiceManager.get().getService(P));
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService("permissionmgr");
        BRActivityThread.getWithException()._set_sPermissionManager(proxyInvocation);
        
    }

    @Override
    protected void onBindMethod() {
        super.onBindMethod();
        addMethodHook(new ValueMethodProxy("addPermissionAsync", true));
        addMethodHook(new ValueMethodProxy("addPermission", true));
        addMethodHook(new ValueMethodProxy("performDexOpt", true));
        addMethodHook(new ValueMethodProxy("performDexOptIfNeeded", false));
        addMethodHook(new ValueMethodProxy("performDexOptSecondary", true));
        addMethodHook(new ValueMethodProxy("addOnPermissionsChangeListener", 0));
        addMethodHook(new ValueMethodProxy("removeOnPermissionsChangeListener", 0));
        addMethodHook(new PkgMethodProxy("shouldShowRequestPermissionRationale"));
        addMethodHook(new CheckNetworkStatePermission());
        addMethodHook(new CheckNetworkStatePermission("checkSelfPermission"));
        if (BuildCompat.isOreo()) {
            addMethodHook(new ValueMethodProxy("notifyDexLoad", 0));
            addMethodHook(new ValueMethodProxy("notifyPackageUse", 0));
            addMethodHook(new ValueMethodProxy("setInstantAppCookie", false));
            addMethodHook(new ValueMethodProxy("isInstantApp", false));
        }
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }
    /**
     * Virtual packages are unknown to the system PackageManager, so the
     * stock check denies them (e.g. Chromium's DNS config gate on
     * ACCESS_NETWORK_STATE, cached forever per process). Grant the
     * network-state read; it reveals nothing sensitive.
     */
    public static class CheckNetworkStatePermission extends MethodHook {
        private final String mMethod;

        CheckNetworkStatePermission() {
            this("checkPermission");
        }

        CheckNetworkStatePermission(String method) {
            mMethod = method;
        }

        @Override
        protected String getMethodName() {
            return mMethod;
        }

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                if (args != null && args.length >= 1
                        && android.Manifest.permission.ACCESS_NETWORK_STATE.equals(args[0])) {
                    Slog.d(TAG, "Granting ACCESS_NETWORK_STATE to virtual caller");
                    return PackageManager.PERMISSION_GRANTED;
                }
            } catch (Throwable ignored) {
            }
            return method.invoke(who, args);
        }
    }

}
