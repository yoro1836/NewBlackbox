// SPDX-License-Identifier: GPL-3.0-only
package org.matrix.vector.impl.core;

/** Hook point for deoptimizing code of a package before modules run. Currently a no-op. */
public final class VectorDeopter {
    private VectorDeopter() {
    }

    public static void deoptMethods(String packageName, ClassLoader classLoader) {
    }
}
