package top.niunaijun.blackbox.fake.service;

import android.content.Context;
import android.os.IBinder;
import android.telephony.TelephonyManager;
import android.util.Log;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

import black.android.os.BRServiceManager;
import black.com.android.internal.telephony.BRITelephonyStub;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.entity.location.BCell;
import top.niunaijun.blackbox.fake.frameworks.BLocationManager;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.Md5Utils;


public class ITelephonyManagerProxy extends BinderInvocationStub {
    public static final String TAG = "ITelephonyManagerProxy";

    public ITelephonyManagerProxy() {
        super(BRServiceManager.get().getService(Context.TELEPHONY_SERVICE));
    }

    @Override
    protected Object getWho() {
        IBinder telephony = BRServiceManager.get().getService(Context.TELEPHONY_SERVICE);
        return BRITelephonyStub.get().asInterface(telephony);
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(Context.TELEPHONY_SERVICE);
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    /**
     * Stable, well-formed fake identifiers. Google's checkin rejects anything that is not
     * a 15-digit Luhn IMEI ("invalid hardware identifier ... is not a valid IMEI"), which
     * made every add-account attempt fail with a server communication error.
     */
    private static int slotOf(Object[] args) {
        return args != null && args.length > 0 && args[0] instanceof Integer ? (Integer) args[0] : 0;
    }

    private static String digitsOf(String seed, int count) {
        String hex = Md5Utils.md5(seed);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; sb.length() < count; i++) {
            sb.append((char) ('0' + Character.digit(hex.charAt(i % hex.length()), 16) % 10));
        }
        return sb.toString();
    }

    private static String fakeImei(int slot) {
        // 35 = common GSMA reporting body prefix; remaining 12 digits derived from the host pkg.
        String body = "35" + digitsOf(BlackBoxCore.getHostPkg() + ":imei:" + slot, 12);
        int sum = 0;
        for (int i = 0; i < body.length(); i++) {
            int d = body.charAt(i) - '0';
            if (i % 2 == 1) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
        }
        return body + ((10 - sum % 10) % 10);
    }

    private static String fakeImsi() {
        // MCC 450 (KR) + MNC 05 + 10 derived digits = 15 digits.
        return "45005" + digitsOf(BlackBoxCore.getHostPkg() + ":imsi", 10);
    }

    @ProxyMethod("getDeviceId")
    public static class GetDeviceId extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return fakeImei(0);
        }
    }

    @ProxyMethod("getImeiForSlot")
    public static class getImeiForSlot extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return fakeImei(slotOf(args));
        }
    }

    // GSM-only device: like real hardware, there is no MEID.
    @ProxyMethod("getMeidForSlot")
    public static class GetMeidForSlot extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return null;
        }
    }

    @ProxyMethod("isUserDataEnabled")
    public static class IsUserDataEnabled extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return true;
        }
    }


    @ProxyMethod("getLine1NumberForDisplay")
    public static class getLine1NumberForDisplay extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return null;
        }
    }

    @ProxyMethod("getSubscriberId")
    public static class GetSubscriberId extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return fakeImsi();
        }
    }

    @ProxyMethod("getDeviceIdWithFeature")
    public static class GetDeviceIdWithFeature extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return fakeImei(0);
        }
    }

    @ProxyMethod("getCellLocation")
    public static class GetCellLocation extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Log.d(TAG, "getCellLocation");
            if (BLocationManager.isFakeLocationEnable()) {
                BCell cell = BLocationManager.get().getCell(BActivityThread.getUserId(), BActivityThread.getAppPackageName());
                if (cell != null) {
                    
                    return null;
                }
            }
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("getAllCellInfo")
    public static class GetAllCellInfo extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (BLocationManager.isFakeLocationEnable()) {
                List<BCell> cell = BLocationManager.get().getAllCell(BActivityThread.getUserId(), BActivityThread.getAppPackageName());
                
                return cell;
            }
            try {
                return method.invoke(who, args);
            } catch (Throwable e) {
                return null;
            }
        }
    }

    @ProxyMethod("getNetworkOperator")
    public static class GetNetworkOperator extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Log.d(TAG, "getNetworkOperator");
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("getNetworkTypeForSubscriber")
    public static class GetNetworkTypeForSubscriber extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(who, args);
            } catch (Throwable e) {
                return 0;
            }
        }
    }

    @ProxyMethod("getNeighboringCellInfo")
    public static class GetNeighboringCellInfo extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Log.d(TAG, "getNeighboringCellInfo");
            if (BLocationManager.isFakeLocationEnable()) {
                List<BCell> cell = BLocationManager.get().getNeighboringCell(BActivityThread.getUserId(), BActivityThread.getAppPackageName());
                
                return null;
            }
            return method.invoke(who, args);
        }
    }
}
