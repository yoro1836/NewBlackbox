// SPDX-License-Identifier: GPL-3.0-only
package org.blackbox.vector.nativebridge;

import java.lang.reflect.Member;
import java.lang.reflect.Method;

/** JNI entry points of the LSPlant engine (libblackbox_vector.so). */
public final class LspBridge {
    private static volatile boolean sLoaded;

    private LspBridge() {
    }

    public static synchronized boolean load() {
        if (!sLoaded) {
            System.loadLibrary("blackbox_vector");
            sLoaded = true;
        }
        return nativeInit();
    }

    public static native boolean nativeInit();

    public static native Object nativeHook(Member target, Object hooker, Method callback);

    public static native boolean nativeUnhook(Member target);

    public static native boolean nativeIsHooked(Member target);

    public static native boolean nativeDeoptimize(Member target);
}
