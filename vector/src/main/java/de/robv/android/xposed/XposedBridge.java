// SPDX-License-Identifier: GPL-3.0-only
package de.robv.android.xposed;

import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

import de.robv.android.xposed.callbacks.XC_LoadPackage;
import org.blackbox.vector.nativebridge.LspBridge;

/**
 * Xposed API entry point backed by the LSPlant engine. Method hooks are multiplexed: LSPlant
 * redirects a target to one {@link Hooker} that dispatches to every registered
 * {@link XC_MethodHook} in priority order.
 */
public final class XposedBridge {
    public static final String TAG = "BBXposed";

    /** Class loader that module APKs use as their parent so the Xposed API resolves. */
    public static final ClassLoader BOOTCLASSLOADER = XposedBridge.class.getClassLoader();

    /** Reported to modules asking for the framework version. */
    public static final int XPOSED_BRIDGE_VERSION = 93;

    /** Package-load callbacks registered by modules; BlackBox dispatches into them. */
    public static final CopyOnWriteArraySet<XC_LoadPackage> sLoadedPackageCallbacks =
            new CopyOnWriteArraySet<>();

    private static final Map<Member, Hooker> sHooks = new ConcurrentHashMap<>();
    private static volatile boolean sEngineReady;
    private static volatile boolean sEngineTried;

    private XposedBridge() {
    }

    /** Loads and initializes the native engine once. */
    public static synchronized boolean ensureEngine() {
        if (!sEngineTried) {
            sEngineTried = true;
            try {
                sEngineReady = LspBridge.load();
            } catch (Throwable t) {
                Log.e(TAG, "LSPlant engine unavailable", t);
            }
        }
        return sEngineReady;
    }

    public static int getXposedVersion() {
        return XPOSED_BRIDGE_VERSION;
    }

    public static synchronized void log(String text) {
        Log.i(TAG, text);
    }

    public static synchronized void log(Throwable t) {
        Log.e(TAG, Log.getStackTraceString(t));
    }

    /** Runs a module's {@code initZygote}; StartupParam can only be built from this package. */
    public static void initModule(IXposedHookZygoteInit module, String modulePath) throws Throwable {
        IXposedHookZygoteInit.StartupParam startup = new IXposedHookZygoteInit.StartupParam();
        startup.modulePath = modulePath;
        startup.startsSystemServer = false;
        module.initZygote(startup);
    }

    public static void hookLoadPackage(XC_LoadPackage callback) {
        sLoadedPackageCallbacks.add(callback);
    }

    /** Hooks a method or constructor; every callback registered for it runs around the original. */
    public static XC_MethodHook.Unhook hookMethod(Member hookMethod, XC_MethodHook callback) {
        if (!(hookMethod instanceof Executable)) {
            throw new IllegalArgumentException("Only methods and constructors can be hooked: " + hookMethod);
        }
        Executable target = (Executable) hookMethod;
        if (Modifier.isAbstract(target.getModifiers())) {
            throw new IllegalArgumentException(hookMethod + " is abstract: hook the concrete override");
        }
        if (target.getDeclaringClass().getClassLoader() == XposedBridge.class.getClassLoader()
                && target.getDeclaringClass().getName().startsWith("de.robv.android.xposed.")) {
            throw new IllegalArgumentException(hookMethod + " belongs to the Xposed framework itself");
        }
        if (target.getDeclaringClass() == Method.class && target.getName().equals("invoke")) {
            throw new IllegalArgumentException("Method.invoke cannot be hooked: the engine calls it");
        }
        if (target.getDeclaringClass() == Constructor.class && target.getName().equals("newInstance")) {
            throw new IllegalArgumentException("Constructor.newInstance cannot be hooked");
        }
        if (target.getDeclaringClass() == Object.class && target.getName().equals("getClass")) {
            throw new IllegalArgumentException("Object.getClass cannot be hooked");
        }
        if (!ensureEngine()) {
            throw new IllegalStateException("Hook engine is not available in this process");
        }
        if (callback == null) {
            throw new IllegalArgumentException("callback must not be null");
        }

        Hooker hooker;
        synchronized (sHooks) {
            hooker = sHooks.get(hookMethod);
            if (hooker == null) {
                hooker = new Hooker(target);
                Object backup = LspBridge.nativeHook(target, hooker, Hooker.CALLBACK);
                if (!(backup instanceof Method)) {
                    throw new IllegalStateException("Cannot hook " + hookMethod);
                }
                hooker.backup = (Method) backup;
                sHooks.put(hookMethod, hooker);
            }
        }
        hooker.callbacks.add(callback);
        return callback.new Unhook(hookMethod);
    }

    public static void unhookMethod(Member hookMethod, XC_MethodHook callback) {
        Hooker hooker = sHooks.get(hookMethod);
        if (hooker != null) {
            hooker.callbacks.remove(callback);
        }
    }

    public static Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> hookClass, String methodName,
                                                           XC_MethodHook callback) {
        Set<XC_MethodHook.Unhook> unhooks = new HashSet<>();
        for (Member method : hookClass.getDeclaredMethods()) {
            if (method.getName().equals(methodName)) {
                unhooks.add(hookMethod(method, callback));
            }
        }
        return unhooks;
    }

    public static Set<XC_MethodHook.Unhook> hookAllConstructors(Class<?> hookClass,
                                                                XC_MethodHook callback) {
        Set<XC_MethodHook.Unhook> unhooks = new HashSet<>();
        for (Member constructor : hookClass.getDeclaredConstructors()) {
            unhooks.add(hookMethod(constructor, callback));
        }
        return unhooks;
    }

    /** Runs the original body of {@code method}, bypassing every hook. */
    public static Object invokeOriginalMethod(Member method, Object thisObject, Object[] args)
            throws Throwable {
        Hooker hooker = sHooks.get(method);
        try {
            if (hooker != null) {
                return hooker.backup.invoke(thisObject, args);
            }
            Executable executable = (Executable) method;
            executable.setAccessible(true);
            if (executable instanceof Method) {
                return ((Method) executable).invoke(thisObject, args);
            }
            return ((Constructor<?>) executable).newInstance(args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    /** Per-target dispatcher. LSPlant calls {@link #callback} with {@code this} as args[0]. */
    public static final class Hooker {
        static final Method CALLBACK;

        static {
            try {
                CALLBACK = Hooker.class.getDeclaredMethod("callback", Object[].class);
            } catch (NoSuchMethodException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        final Executable target;
        final boolean isStatic;
        final Class<?> returnType;
        final CopyOnWriteSortedSet<XC_MethodHook> callbacks = new CopyOnWriteSortedSet<>();
        volatile Method backup;
        private volatile boolean firstHitLogged;

        Hooker(Executable target) {
            this.target = target;
            this.isStatic = Modifier.isStatic(target.getModifiers());
            this.returnType = target instanceof Method ? ((Method) target).getReturnType() : void.class;
        }

        public Object callback(Object[] args) throws Throwable {
            Object thisObject = isStatic ? null : args[0];
            Object[] callArgs = isStatic ? args : Arrays.copyOfRange(args, 1, args.length);
            Object[] snapshot = callbacks.getSnapshot();
            if (snapshot.length == 0) {
                return invokeBackup(thisObject, callArgs);
            }

            if (!firstHitLogged) {
                firstHitLogged = true;
                Log.d(TAG, "first dispatch of hooked " + target + " (" + snapshot.length + " callback(s))");
            }
            XC_MethodHook.MethodHookParam<?> param = newParam(thisObject, callArgs);

            int beforeIdx = 0;
            do {
                try {
                    ((XC_MethodHook) snapshot[beforeIdx]).callBeforeHookedMethod(param);
                } catch (Throwable t) {
                    XposedBridge.log(t);
                    param.setResult(null);
                    param.returnEarly = false;
                    continue;
                }
                if (param.returnEarly) {
                    beforeIdx++;
                    break;
                }
            } while (++beforeIdx < snapshot.length);

            if (!param.returnEarly) {
                try {
                    param.setResult(invokeBackup(param.thisObject, param.args));
                } catch (Throwable t) {
                    param.setThrowable(t);
                }
            }

            int afterIdx = beforeIdx - 1;
            do {
                Object lastResult = param.getResult();
                Throwable lastThrowable = param.getThrowable();
                try {
                    ((XC_MethodHook) snapshot[afterIdx]).callAfterHookedMethod(param);
                } catch (Throwable t) {
                    XposedBridge.log(t);
                    if (lastThrowable == null) {
                        param.setResult(lastResult);
                    } else {
                        param.setThrowable(lastThrowable);
                    }
                }
            } while (--afterIdx >= 0);

            if (param.hasThrowable()) {
                throw param.getThrowable();
            }
            return unboxSafe(param.getResult());
        }

        private XC_MethodHook.MethodHookParam<?> newParam(Object thisObject, Object[] args) {
            @SuppressWarnings({"rawtypes", "unchecked"})
            XC_MethodHook.MethodHookParam<?> param = new XC_MethodHook.MethodHookParam();
            param.method = target;
            param.thisObject = thisObject;
            param.args = args;
            return param;
        }

        private Object invokeBackup(Object thisObject, Object[] args) throws Throwable {
            try {
                return backup.invoke(thisObject, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }

        /** A primitive-returning hook must not hand a null result back to the runtime. */
        private Object unboxSafe(Object result) {
            if (result != null || !returnType.isPrimitive() || returnType == void.class) {
                return result;
            }
            if (returnType == boolean.class) return false;
            if (returnType == char.class) return (char) 0;
            if (returnType == byte.class) return (byte) 0;
            if (returnType == short.class) return (short) 0;
            if (returnType == int.class) return 0;
            if (returnType == long.class) return 0L;
            if (returnType == float.class) return 0f;
            return 0d;
        }
    }

    /** Copy-on-write set kept ordered by {@link XCallback#priority}. */
    public static final class CopyOnWriteSortedSet<E> {
        private transient volatile Object[] elements = new Object[0];

        @SuppressWarnings("UnusedReturnValue")
        public synchronized boolean add(E e) {
            if (indexOf(e) >= 0) {
                return false;
            }
            Object[] newElements = Arrays.copyOf(elements, elements.length + 1);
            newElements[elements.length] = e;
            Arrays.sort(newElements, (a, b) ->
                    Integer.compare(((de.robv.android.xposed.callbacks.XCallback) b).priority,
                            ((de.robv.android.xposed.callbacks.XCallback) a).priority));
            elements = newElements;
            return true;
        }

        @SuppressWarnings("UnusedReturnValue")
        public synchronized boolean remove(E e) {
            int index = indexOf(e);
            if (index == -1) {
                return false;
            }
            Object[] newElements = new Object[elements.length - 1];
            System.arraycopy(elements, 0, newElements, 0, index);
            System.arraycopy(elements, index + 1, newElements, index, elements.length - index - 1);
            elements = newElements;
            return true;
        }

        private int indexOf(Object o) {
            for (int i = 0; i < elements.length; i++) {
                if (o.equals(elements[i])) {
                    return i;
                }
            }
            return -1;
        }

        public Object[] getSnapshot() {
            return elements;
        }
    }
}
