package org.blackbox.gpudriver;

/**
 * JNI entry point of libblackbox_gpu.so: loads a custom Vulkan driver through
 * libadrenotools and makes the virtual app's {@code dlopen("libvulkan.so")}
 * resolve to it. arm64 only; other ABIs return false.
 */
public final class GpuBridge {
    private static boolean sLoaded;

    private GpuBridge() {
    }

    /**
     * @param hookDir    directory holding libmain_hook.so and friends; must be the
     *                   host's {@code ApplicationInfo.nativeLibraryDir}
     * @param driverDir  directory of the extracted driver package (internal storage)
     * @param driverName the driver library file name from the package's meta.json
     * @return true when the driver was opened and the dlopen hooks are active
     */
    public static synchronized boolean install(String hookDir, String driverDir, String driverName) {
        if (!sLoaded) {
            System.loadLibrary("blackbox_gpu");
            sLoaded = true;
        }
        return nativeInstall(hookDir, driverDir, driverName);
    }

    private static native boolean nativeInstall(String hookDir, String driverDir, String driverName);
}
