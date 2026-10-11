// SPDX-License-Identifier: GPL-3.0-only
package org.matrix.vector.nativebridge;

import java.lang.reflect.Field;

/** Compatibility surface for the legacy Xposed API sources copied from Vector. */
public final class HookBridge {
    private HookBridge() {
    }

    /** Not supported by this port; callers fall back to reporting the original error. */
    public static boolean makeFieldWritable(Field field, int modifiers) {
        return false;
    }
}
