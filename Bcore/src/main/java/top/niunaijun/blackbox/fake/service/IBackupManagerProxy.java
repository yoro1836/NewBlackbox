package top.niunaijun.blackbox.fake.service;

import android.os.IBinder;

import java.lang.reflect.Method;

import black.android.app.backup.BRIBackupManagerStub;
import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;

/**
 * Most IBackupManager queries need android.permission.BACKUP, which a normal host app
 * cannot hold. GMS asks them while finishing account setup (GoogleServicesActivity) and
 * crashes on the SecurityException. The sandbox has no backup transport, so answer "off".
 */
public class IBackupManagerProxy extends BinderInvocationStub {
    // Context.BACKUP_SERVICE is @hide.
    private static final String SERVICE = "backup";

    public IBackupManagerProxy() {
        super(BRServiceManager.get().getService(SERVICE));
    }

    @Override
    protected Object getWho() {
        IBinder binder = BRServiceManager.get().getService(SERVICE);
        return binder == null ? null : BRIBackupManagerStub.get().asInterface(binder);
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(SERVICE);
    }

    @Override
    public boolean isBadEnv() {
        return BRServiceManager.get().getService(SERVICE) == null;
    }

    @ProxyMethod("isBackupServiceActive")
    public static class IsBackupServiceActive extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) {
            return false;
        }
    }

    @ProxyMethod("isBackupEnabled")
    public static class IsBackupEnabled extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) {
            return false;
        }
    }

    @ProxyMethod("hasBackupPassword")
    public static class HasBackupPassword extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) {
            return false;
        }
    }

    @ProxyMethod("getCurrentTransport")
    public static class GetCurrentTransport extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) {
            return null;
        }
    }

    @ProxyMethod("listAllTransports")
    public static class ListAllTransports extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) {
            return new String[0];
        }
    }
}
