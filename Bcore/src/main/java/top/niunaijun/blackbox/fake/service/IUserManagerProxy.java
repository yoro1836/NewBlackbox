package top.niunaijun.blackbox.fake.service;

import android.content.Context;
import android.os.Build;

import java.lang.reflect.Method;
import java.util.ArrayList;

import black.android.content.pm.BRUserInfo;
import black.android.os.BRIUserManagerStub;
import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.core.system.user.BUserInfo;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;


public class IUserManagerProxy extends BinderInvocationStub {
    public IUserManagerProxy() {
        super(BRServiceManager.get().getService(Context.USER_SERVICE));
    }

    @Override
    protected Object getWho() {
        return BRIUserManagerStub.get().asInterface(BRServiceManager.get().getService(Context.USER_SERVICE));
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(Context.USER_SERVICE);
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @ProxyMethod("getApplicationRestrictions")
    public static class GetApplicationRestrictions extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            args[0] = BlackBoxCore.getHostPkg();
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("getApplicationRestrictionsForUser")
    public static class GetApplicationRestrictionsForUser extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            args[0] = BlackBoxCore.getHostPkg();
            args[1] = BlackBoxCore.getHostUserId();
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("getUserInfo")
    public static class GetUserInfo extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            int userId = (Integer) args[0];
            if (userId != BActivityThread.getUserId()) {
                return null;
            }
            for (BUserInfo user : BlackBoxCore.get().getUsers()) {
                if (user.id == userId) {
                    int flags = userId == 0 ? BRUserInfo.get().FLAG_PRIMARY() : 0;
                    if (userId == 0 && Build.VERSION.SDK_INT >= 34) {
                        flags |= BRUserInfo.get().FLAG_MAIN();
                    }
                    return BRUserInfo.get()._new(user.id, user.name, flags);
                }
            }
            return null;
        }
    }


    @ProxyMethod("getSeedAccountName")
    public static class GetSeedAccountName extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) {
            return null;
        }
    }

    @ProxyMethod("getSeedAccountType")
    public static class GetSeedAccountType extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) {
            return null;
        }
    }

    @ProxyMethod("hasSeedAccount")
    public static class HasSeedAccount extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) {
            return false;
        }
    }

    @ProxyMethod("getSeedAccountOptions")
    public static class GetSeedAccountOptions extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) {
            return null;
        }
    }

    @ProxyMethod("getSeedAccountData")
    public static class GetSeedAccountData extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) {
            return null;
        }
    }
    @ProxyMethod("getProfileParent")
    public static class GetProfileParent extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Object blackBox = BRUserInfo.get()._new(BActivityThread.getUserId(), "BlackBox", BRUserInfo.get().FLAG_PRIMARY());
            return blackBox;
        }
    }

    @ProxyMethod("getUsers")
    public static class getUsers extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return new ArrayList<>();
        }
    }

    // GMS asks isMainUser() while starting the add-account flow. The host
    // enforces MANAGE_USERS/QUERY_USERS for it and throws for our uid, which
    // crashed AccountIntroActivity. The virtual user 0 is the main user.
    @ProxyMethod("isMainUser")
    public static class IsMainUser extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) {
            return BActivityThread.getUserId() == 0;
        }
    }

    // The virtual user is a full (non-profile, non-managed) user. Answer from
    // the virtual user model instead of letting the host throw for missing
    // MANAGE_USERS / QUERY_USERS permission.
    @ProxyMethod("isUserOfType")
    public static class IsUserOfType extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) {
            if (args != null) {
                for (Object arg : args) {
                    if ("android.os.usertype.FULL_SYSTEM".equals(arg)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }
}
