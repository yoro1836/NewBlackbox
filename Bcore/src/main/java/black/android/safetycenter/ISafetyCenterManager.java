package black.android.safetycenter;

import android.os.IBinder;
import android.os.IInterface;

import top.niunaijun.blackreflection.annotation.BClassName;
import top.niunaijun.blackreflection.annotation.BStaticMethod;

@BClassName("android.safetycenter.ISafetyCenterManager")
public interface ISafetyCenterManager {
    @BClassName("android.safetycenter.ISafetyCenterManager$Stub")
    interface Stub {
        @BStaticMethod
        IInterface asInterface(IBinder IBinder0);
    }
}
