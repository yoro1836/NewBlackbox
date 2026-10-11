package top.niunaijun.blackbox.fake.service;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.Context;

import java.lang.reflect.Method;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.fake.frameworks.BAccountManager;
import top.niunaijun.blackbox.fake.hook.ClassInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.Slog;

/**
 * Keeps AccountManager calls inside the current virtual user.
 *
 * The binder hook is the source of truth for account operations. This class only
 * prevents Google code from falling back to the host account database when the
 * virtual user has no account, and never fabricates accounts, tokens, or success.
 */
public class GoogleAccountManagerProxy extends ClassInvocationStub {
    public static final String TAG = "GoogleAccountManagerProxy";

    public GoogleAccountManagerProxy() {
        super();
    }

    @Override
    protected Object getWho() {
        try {
            Context context = BlackBoxCore.getContext();
            if (context != null) {
                return AccountManager.get(context);
            }
        } catch (Exception e) {
            Slog.w(TAG, "Failed to get AccountManager instance", e);
        }
        return null;
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @ProxyMethod("getAccounts")
    public static class GetAccounts extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return BAccountManager.get().getAccountsAsUser(null);
        }
    }

    @ProxyMethod("getAccountsByType")
    public static class GetAccountsByType extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String type = args != null && args.length > 0 ? (String) args[0] : null;
            return BAccountManager.get().getAccountsAsUser(type);
        }
    }

    @ProxyMethod("getPassword")
    public static class GetPassword extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return BAccountManager.get().getPassword((Account) args[0]);
        }
    }

    @ProxyMethod("getUserData")
    public static class GetUserData extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return BAccountManager.get().getUserData((Account) args[0], (String) args[1]);
        }
    }

    @ProxyMethod("addAccount")
    public static class AddAccount extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("removeAccount")
    public static class RemoveAccount extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("hasFeatures")
    public static class HasFeatures extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("getAuthToken")
    public static class GetAuthToken extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("invalidateAuthToken")
    public static class InvalidateAuthToken extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("peekAuthToken")
    public static class PeekAuthToken extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return BAccountManager.get().peekAuthToken((Account) args[0], (String) args[1]);
        }
    }

    @ProxyMethod("setPassword")
    public static class SetPassword extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("setUserData")
    public static class SetUserData extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("getAuthenticatorTypes")
    public static class GetAuthenticatorTypes extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return BAccountManager.get().getAuthenticatorTypes();
        }
    }

    @ProxyMethod("isAccountPresent")
    public static class IsAccountPresent extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Account account = (Account) args[0];
            Account[] accounts = BAccountManager.get().getAccountsAsUser(account.type);
            if (accounts == null) {
                return false;
            }
            for (Account candidate : accounts) {
                if (account.equals(candidate)) {
                    return true;
                }
            }
            return false;
        }
    }
}
