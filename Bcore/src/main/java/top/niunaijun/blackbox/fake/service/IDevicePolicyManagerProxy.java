package top.niunaijun.blackbox.fake.service;

import android.content.ComponentName;
import android.content.Context;

import java.lang.reflect.Method;

import black.android.app.admin.BRIDevicePolicyManagerStub;
import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.MethodParameterUtils;


public class IDevicePolicyManagerProxy extends BinderInvocationStub {
    public IDevicePolicyManagerProxy() {
        super(BRServiceManager.get().getService(Context.DEVICE_POLICY_SERVICE));
    }

    @Override
    protected Object getWho() {
        return BRIDevicePolicyManagerStub.get().asInterface(BRServiceManager.get().getService(Context.DEVICE_POLICY_SERVICE));
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(Context.DEVICE_POLICY_SERVICE);
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    /**
     * The host DevicePolicyManagerService throws SecurityException for most queries made by a
     * non-admin caller (getFactoryResetProtectionPolicy, ...). GMS runs these from
     * IntentOperations, and one uncaught exception killed the whole virtual GMS process. The
     * sandbox has no device policy, so answer with the neutral value for the return type.
     */
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        try {
            return super.invoke(proxy, method, args);
        } catch (SecurityException e) {
            Class<?> type = method.getReturnType();
            if (type == void.class) {
                return null;
            } else if (type == boolean.class) {
                return false;
            } else if (type == int.class) {
                return 0;
            } else if (type == long.class) {
                return 0L;
            } else if (type == float.class) {
                return 0f;
            } else if (type == double.class) {
                return 0d;
            } else if (type.isArray()) {
                return java.lang.reflect.Array.newInstance(type.getComponentType(), 0);
            } else if (java.util.List.class.isAssignableFrom(type)) {
                return new java.util.ArrayList<>();
            }
            return null;
        }
    }

    @ProxyMethod("getStorageEncryptionStatus")
    public static class GetStorageEncryptionStatus extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceFirstAppPkg(args);
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("getDeviceOwnerComponent")
    public static class GetDeviceOwnerComponent extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return new ComponentName("", "");
        }
    }

    @ProxyMethod("getDeviceOwnerName")
    public static class getDeviceOwnerName extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return "BlackBox";
        }
    }

    @ProxyMethod("getProfileOwnerName")
    public static class getProfileOwnerName extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return "BlackBox";
        }
    }

    @ProxyMethod("isDeviceProvisioned")
    public static class isDeviceProvisioned extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return true;
        }
    }

    // The host DevicePolicyManagerService requires the caller to be the real
    // com.google.android.gms uid, so our uid is rejected and the account picker
    // crashes in onResume. No account type is management-disabled in the sandbox.
    @ProxyMethod("getAccountTypesWithManagementDisabled")
    public static class GetAccountTypesWithManagementDisabled extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return new String[0];
        }
    }

    @ProxyMethod("getAccountTypesWithManagementDisabledAsUser")
    public static class GetAccountTypesWithManagementDisabledAsUser extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return new String[0];
        }
    }
}
