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

    /**
     * Makes the virtual app's {@code dlopen("libvulkan.so")} resolve to a wrapper
     * library. The wrapper gets the real libvulkan handle (the custom driver's when
     * driverDir/driverName are given, else the system one) through its exported
     * {@code bb_vulkan_wrapper_init(void *real, const char *config)}.
     */
    public static synchronized boolean installWrapper(String wrapperPath, String config,
                                                      String hookDir, String driverDir,
                                                      String driverName) {
        if (!sLoaded) {
            System.loadLibrary("blackbox_gpu");
            sLoaded = true;
        }
        return nativeInstallWrapper(wrapperPath, config, hookDir, driverDir, driverName);
    }

    private static native boolean nativeInstallWrapper(String wrapperPath, String config,
                                                       String hookDir, String driverDir,
                                                       String driverName);

    private static native boolean nativeInstall(String hookDir, String driverDir, String driverName);
}
