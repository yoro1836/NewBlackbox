package black.android.content.pm;

import java.util.List;

import top.niunaijun.blackreflection.annotation.BClassName;
import top.niunaijun.blackreflection.annotation.BConstructor;

@BClassName("android.content.pm.PackageInfoList")
public interface PackageInfoList {
    @BConstructor
    Object _new(List<?> list);
}
