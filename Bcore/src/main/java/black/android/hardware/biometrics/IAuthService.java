package black.android.hardware.biometrics;

import android.os.IBinder;
import android.os.IInterface;

import top.niunaijun.blackreflection.annotation.BClassName;
import top.niunaijun.blackreflection.annotation.BStaticMethod;

@BClassName("android.hardware.biometrics.IAuthService")
public interface IAuthService {
    @BClassName("android.hardware.biometrics.IAuthService$Stub")
    interface Stub {
        @BStaticMethod
        IInterface asInterface(IBinder IBinder0);
    }
}
