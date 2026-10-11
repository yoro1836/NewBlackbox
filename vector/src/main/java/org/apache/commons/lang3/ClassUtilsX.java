/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.commons.lang3;

import java.util.HashMap;
import java.util.Map;

/**
 * The subset of Apache Commons Lang's {@code ClassUtils} that the Xposed helper API needs
 * (class lookup by name, and assignability with boxing/widening), with the same semantics.
 */
public final class ClassUtilsX {
    private static final Map<String, Class<?>> PRIMITIVES = new HashMap<>();
    private static final Map<Class<?>, Class<?>> PRIMITIVE_TO_WRAPPER = new HashMap<>();
    private static final Map<Class<?>, Class<?>> WRAPPER_TO_PRIMITIVE = new HashMap<>();

    static {
        add(boolean.class, Boolean.class);
        add(byte.class, Byte.class);
        add(char.class, Character.class);
        add(short.class, Short.class);
        add(int.class, Integer.class);
        add(long.class, Long.class);
        add(float.class, Float.class);
        add(double.class, Double.class);
        add(void.class, Void.class);
    }

    private static void add(Class<?> primitive, Class<?> wrapper) {
        PRIMITIVES.put(primitive.getName(), primitive);
        PRIMITIVE_TO_WRAPPER.put(primitive, wrapper);
        WRAPPER_TO_PRIMITIVE.put(wrapper, primitive);
    }

    private ClassUtilsX() {
    }

    /** Loads {@code className}, understanding primitives, {@code int[]} style arrays and inner-class dots. */
    public static Class<?> getClass(ClassLoader classLoader, String className, boolean initialize)
            throws ClassNotFoundException {
        try {
            Class<?> clazz = PRIMITIVES.get(className);
            return clazz != null ? clazz : Class.forName(toCanonicalName(className), initialize, classLoader);
        } catch (ClassNotFoundException ex) {
            int lastDot = className.lastIndexOf('.');
            if (lastDot != -1) {
                try {
                    return getClass(classLoader, className.substring(0, lastDot) + '$'
                            + className.substring(lastDot + 1), initialize);
                } catch (ClassNotFoundException ignored) {
                    // fall through to the original failure
                }
            }
            throw ex;
        }
    }

    private static String toCanonicalName(String className) {
        String name = className.trim();
        if (name.endsWith("[]")) {
            StringBuilder dims = new StringBuilder();
            while (name.endsWith("[]")) {
                name = name.substring(0, name.length() - 2);
                dims.append('[');
            }
            Class<?> primitive = PRIMITIVES.get(name);
            if (primitive == int.class) name = "I";
            else if (primitive == boolean.class) name = "Z";
            else if (primitive == float.class) name = "F";
            else if (primitive == long.class) name = "J";
            else if (primitive == short.class) name = "S";
            else if (primitive == byte.class) name = "B";
            else if (primitive == double.class) name = "D";
            else if (primitive == char.class) name = "C";
            else name = "L" + name + ";";
            return dims + name;
        }
        return name;
    }

    public static boolean isAssignable(Class<?> cls, Class<?> toClass) {
        return isAssignable(cls, toClass, true);
    }

    /** Returns the primitive for a wrapper class, or null when {@code cls} is not a wrapper. */
    public static Class<?> wrapperToPrimitive(Class<?> cls) {
        return WRAPPER_TO_PRIMITIVE.get(cls);
    }

    public static boolean isAssignable(Class<?>[] classArray, Class<?>[] toClassArray, boolean autoboxing) {
        if (classArray == null) {
            classArray = new Class<?>[0];
        }
        if (toClassArray == null) {
            toClassArray = new Class<?>[0];
        }
        if (classArray.length != toClassArray.length) {
            return false;
        }
        for (int i = 0; i < classArray.length; i++) {
            if (!isAssignable(classArray[i], toClassArray[i], autoboxing)) {
                return false;
            }
        }
        return true;
    }

    public static boolean isAssignable(Class<?> cls, Class<?> toClass, boolean autoboxing) {
        if (toClass == null) {
            return false;
        }
        if (cls == null) {
            return !toClass.isPrimitive();
        }
        if (autoboxing) {
            if (cls.isPrimitive() && !toClass.isPrimitive()) {
                cls = PRIMITIVE_TO_WRAPPER.get(cls);
                if (cls == null) {
                    return false;
                }
            }
            if (toClass.isPrimitive() && !cls.isPrimitive()) {
                cls = WRAPPER_TO_PRIMITIVE.get(cls);
                if (cls == null) {
                    return false;
                }
            }
        }
        if (cls.equals(toClass)) {
            return true;
        }
        if (cls.isPrimitive()) {
            if (!toClass.isPrimitive()) {
                return false;
            }
            if (cls == int.class) {
                return toClass == long.class || toClass == float.class || toClass == double.class;
            }
            if (cls == long.class) {
                return toClass == float.class || toClass == double.class;
            }
            if (cls == boolean.class || cls == double.class) {
                return false;
            }
            if (cls == float.class) {
                return toClass == double.class;
            }
            if (cls == char.class || cls == short.class) {
                return toClass == int.class || toClass == long.class || toClass == float.class
                        || toClass == double.class;
            }
            if (cls == byte.class) {
                return toClass == short.class || toClass == int.class || toClass == long.class
                        || toClass == float.class || toClass == double.class;
            }
            return false;
        }
        return toClass.isAssignableFrom(cls);
    }
}
