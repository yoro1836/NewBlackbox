package top.niunaijun.blackbox.fake.service;

import java.lang.reflect.Method;

import top.niunaijun.blackbox.fake.hook.ClassInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.Slog;


public class AndroidIdProxy extends ClassInvocationStub {
    public static final String TAG = "AndroidIdProxy";

    public AndroidIdProxy() {
        super();
    }

    @Override
    protected Object getWho() {
        return null;
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @ProxyMethod("getAndroidId")
    public static class GetAndroidId extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                Slog.d(TAG, "AndroidId: Handling getAndroidId call");
                Object result = method.invoke(who, args);
                if (result == null || "0".equals(result.toString()) || "".equals(result.toString())) {
                    Slog.w(TAG, "AndroidId: Invalid Android ID detected, returning mock ID");
                    return getStableAndroidId();
                }
                return result;
            } catch (Exception e) {
                Slog.w(TAG, "AndroidId: GetAndroidId error, returning mock ID", e);
                return getStableAndroidId();
            }
        }
    }

    @ProxyMethod("getString")
    public static class GetString extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                Slog.d(TAG, "AndroidId: Handling getString call");
                Object result = method.invoke(who, args);
                
                
                if (args != null && args.length > 0 && args[0] instanceof String) {
                    String key = (String) args[0];
                    if (key.contains("android_id") || key.contains("ANDROID_ID") || 
                        key.contains("secure_id") || key.contains("device_id")) {
                        if (result == null || "0".equals(result.toString()) || "".equals(result.toString())) {
                            Slog.w(TAG, "AndroidId: Invalid Android ID string detected, returning mock ID");
                            return getStableAndroidId();
                        }
                    }
                }
                return result;
            } catch (Exception e) {
                Slog.w(TAG, "AndroidId: GetString error, returning original result", e);
                return method.invoke(who, args);
            }
        }
    }

    @ProxyMethod("getLong")
    public static class GetLong extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                Slog.d(TAG, "AndroidId: Handling getLong call");
                Object result = method.invoke(who, args);
                
                
                if (args != null && args.length > 0 && args[0] instanceof String) {
                    String key = (String) args[0];
                    if (key.contains("android_id") || key.contains("ANDROID_ID") || 
                        key.contains("secure_id") || key.contains("device_id")) {
                        if (result == null || ((Number) result).longValue() == 0) {
                            Slog.w(TAG, "AndroidId: Invalid Android ID long detected, returning mock ID");
                            return getStableAndroidIdLong();
                        }
                    }
                }
                return result;
            } catch (Exception e) {
                Slog.w(TAG, "AndroidId: GetLong error, returning original result", e);
                return method.invoke(who, args);
            }
        }
    }

    @ProxyMethod("get")
    public static class Get extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                Slog.d(TAG, "AndroidId: Handling get call");
                Object result = method.invoke(who, args);
                
                
                if (args != null && args.length > 0 && args[0] instanceof String) {
                    String key = (String) args[0];
                    if (key.contains("android_id") || key.contains("ANDROID_ID") || 
                        key.contains("secure_id") || key.contains("device_id")) {
                        if (result == null || "0".equals(result.toString()) || "".equals(result.toString())) {
                            Slog.w(TAG, "AndroidId: Invalid Android ID get detected, returning mock ID");
                            return getStableAndroidId();
                        }
                    }
                }
                return result;
            } catch (Exception e) {
                Slog.w(TAG, "AndroidId: Get error, returning original result", e);
                return method.invoke(who, args);
            }
        }
    }

    @ProxyMethod("read")
    public static class Read extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                Slog.d(TAG, "AndroidId: Handling read call");
                Object result = method.invoke(who, args);
                
                
                if (args != null && args.length > 0 && args[0] instanceof String) {
                    String key = (String) args[0];
                    if (key.contains("android_id") || key.contains("ANDROID_ID") || 
                        key.contains("secure_id") || key.contains("device_id")) {
                        if (result == null || "0".equals(result.toString()) || "".equals(result.toString())) {
                            Slog.w(TAG, "AndroidId: Invalid Android ID read detected, returning mock ID");
                            return getStableAndroidId();
                        }
                    }
                }
                return result;
            } catch (Exception e) {
                Slog.w(TAG, "AndroidId: Read error, returning original result", e);
                return method.invoke(who, args);
            }
        }
    }

    private static final Object sIdLock = new Object();
    private static final java.util.Map<String, String> sIdCache = new java.util.HashMap<>();

    /**
     * Stable mock ID, persisted per virtual user and package. A fresh random
     * value on every query breaks apps that bind sessions to the device ID
     * (games log out on every launch).
     */
    private static String getStableAndroidId() {
        String key;
        try {
            key = top.niunaijun.blackbox.app.BActivityThread.getUserId()
                    + "_" + top.niunaijun.blackbox.app.BActivityThread.getAppPackageName();
        } catch (Throwable t) {
            key = "0_unknown";
        }
        synchronized (sIdLock) {
            String cached = sIdCache.get(key);
            if (cached != null) {
                return cached;
            }
            String persisted = readPersistedId(key);
            if (persisted != null) {
                sIdCache.put(key, persisted);
                return persisted;
            }
            String fresh = generateMockAndroidId();
            sIdCache.put(key, fresh);
            persistId(key, fresh);
            return fresh;
        }
    }

    private static Long getStableAndroidIdLong() {
        try {
            return Long.parseUnsignedLong(getStableAndroidId(), 16);
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static java.io.File idFile(String key) {
        String safe = key.replaceAll("[^A-Za-z0-9_.-]", "_");
        return new java.io.File(
                top.niunaijun.blackbox.core.env.BEnvironment.getSystemDir(),
                "android_id/" + safe);
    }

    private static String readPersistedId(String key) {
        java.io.File file = idFile(key);
        if (!file.isFile()) {
            return null;
        }
        try {
            byte[] bytes = new byte[(int) Math.min(file.length(), 64)];
            java.io.FileInputStream in = new java.io.FileInputStream(file);
            try {
                int read = in.read(bytes);
                if (read > 0) {
                    String value = new String(bytes, 0, read, "UTF-8").trim();
                    if (value.matches("[0-9A-Fa-f]{16}")) {
                        return value.toUpperCase();
                    }
                }
            } finally {
                try {
                    in.close();
                } catch (java.io.IOException ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void persistId(String key, String value) {
        try {
            java.io.File file = idFile(key);
            java.io.File parent = file.getParentFile();
            if (parent != null) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            java.io.FileWriter writer = new java.io.FileWriter(file, false);
            try {
                writer.write(value);
            } finally {
                try {
                    writer.close();
                } catch (java.io.IOException ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static String generateMockAndroidId() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            sb.append(Integer.toHexString((int) (Math.random() * 16)));
        }
        String mockId = sb.toString().toUpperCase();
        Slog.d(TAG, "AndroidId: Generated mock Android ID: " + mockId);
        return mockId;
    }

    private static Long generateMockAndroidIdLong() {
        long mockId = (long) (Math.random() * Long.MAX_VALUE);
        Slog.d(TAG, "AndroidId: Generated mock Android ID long: " + mockId);
        return mockId;
    }
}
