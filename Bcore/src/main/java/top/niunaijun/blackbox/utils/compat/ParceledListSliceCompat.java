package top.niunaijun.blackbox.utils.compat;

import java.lang.reflect.Method;
import java.util.List;

import black.android.content.pm.BRParceledListSlice;
import black.android.content.pm.BRPackageInfoList;

public class ParceledListSliceCompat {

	public static boolean isReturnParceledListSlice(Method method) {
		return method != null && method.getReturnType() == BRParceledListSlice.getRealClass();
	}

	public static boolean isParceledListSlice(Object obj) {
		return obj != null && obj.getClass() == BRParceledListSlice.getRealClass();
	}

	public static Object create(Method method, List<?> list) {
		if (method != null
				&& "android.content.pm.PackageInfoList".equals(
				method.getReturnType().getName())) {
			Object packageInfoList = BRPackageInfoList.get()._new(list);
			if (packageInfoList != null) {
				return packageInfoList;
			}
			throw new IllegalStateException("Unable to create PackageInfoList");
		}
		return create(list);
	}

	public static Object create(List<?> list) {
		Object slice = BRParceledListSlice.get()._new(list);
		if (slice != null) {
			return slice;
		} else {
			slice = BRParceledListSlice.get()._new();
		}
		for (Object item : list) {
			BRParceledListSlice.get(slice).append(item);
		}
		BRParceledListSlice.get(slice).setLastSlice(true);
		return slice;
	}
}
