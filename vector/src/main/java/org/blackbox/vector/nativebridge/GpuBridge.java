// SPDX-License-Identifier: GPL-3.0-only
package org.blackbox.vector.nativebridge;

/**
 * JNI entry point for the custom Vulkan driver loader (libblackbox_vector.so).
 *
 * <p>Loads a user-supplied Mesa driver (Turnip, PanVK, ...) into a dedicated
 * linker namespace and hooks {@code android_load_sphal_library} so the virtual
 * app's {@code libvulkan.so} picks it up in place of the system GPU HAL.
 */
public final class GpuBridge {
    private static volatile boolean sLoaded;

    private GpuBridge() {
    }

    /**
     * Loads and activates the custom driver for the current process. Idempotent:
     * once a driver is active only one ICD can be used per process.
     *
     * @param driverDir absolute directory holding the extracted driver files
     * @param soName    the driver library file name (from the pack's meta.json)
     * @param apiLevel  {@code Build.VERSION.SDK_INT}; the loader needs API 28+
     * @return true when the driver was loaded and the HAL load was intercepted
     */
    public static synchronized boolean loadDriver(String driverDir, String soName, int apiLevel) {
        if (!sLoaded) {
            System.loadLibrary("blackbox_vector");
            sLoaded = true;
        }
        return nativeLoadDriver(driverDir, soName, apiLevel);
    }

    private static native boolean nativeLoadDriver(String driverDir, String soName, int apiLevel);
}
