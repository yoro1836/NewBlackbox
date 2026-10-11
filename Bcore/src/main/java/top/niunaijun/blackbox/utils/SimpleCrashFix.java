package top.niunaijun.blackbox.utils;

import android.content.Context;
import android.content.ContextWrapper;
import android.util.Log;
import android.app.Application;

import top.niunaijun.blackbox.BlackBoxCore;


public class SimpleCrashFix {
    private static final String TAG = "SimpleCrashFix";
    private static boolean sIsInstalled = false;

    
    public static void installSimpleFix() {
        if (sIsInstalled) {
            Slog.d(TAG, "Simple crash fix already installed");
            return;
        }
        
        try {
            Slog.d(TAG, "Installing essential crash fix...");
            
            
            installGlobalExceptionHandler();
            installMainLooperGuard();

            
            installContextWrapperHook();
            
            sIsInstalled = true;
            Slog.d(TAG, "Essential crash fix installed successfully");
        } catch (Exception e) {
            Slog.e(TAG, "Failed to install essential crash fix: " + e.getMessage(), e);
        }
    }
    
    
    /**
     * The handlers below "recover" a main-thread exception by returning from it. By then the
     * exception has already unwound Looper.loop(), so the process stays alive with no UI thread:
     * the window is still there, input is never handled and the system raises an ANR (Asphalt
     * froze this way after a WebView constructor threw). Keep the main looper running instead.
     */
    private static void installMainLooperGuard() {
        try {
            final android.os.Looper main = android.os.Looper.getMainLooper();
            new android.os.Handler(main).post(new Runnable() {
                @Override
                public void run() {
                    while (true) {
                        try {
                            android.os.Looper.loop();
                            return;
                        } catch (Throwable t) {
                            Slog.w(TAG, "Main looper survived an exception: " + t, t);
                        }
                    }
                }
            });
        } catch (Throwable t) {
            Slog.w(TAG, "Failed to install main looper guard: " + t.getMessage());
        }
    }

    private static void installGlobalExceptionHandler() {
        try {
            
            Thread.UncaughtExceptionHandler currentHandler = Thread.getDefaultUncaughtExceptionHandler();
            
            Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                @Override
                public void uncaughtException(Thread thread, Throwable throwable) {
                    
                    if (isNullContextCrash(throwable)) {
                        Slog.w(TAG, "Caught null context crash, preventing crash: " + throwable.getMessage());
                        BlackBoxCore.get().sendLogs("CRASH DETECTED (Caught/NullContext): " + throwable.getMessage(), true);
                        return; 
                    }

                    
                    if (isGooglePlayServicesCrash(throwable)) {
                        Slog.w(TAG, "Caught Google Play Services crash, preventing crash: " + throwable.getMessage());
                        BlackBoxCore.get().sendLogs("CRASH DETECTED (Caught/GMS): " + throwable.getMessage(), true);
                        return; 
                    }

                    
                    if (isWebViewCrash(throwable)) {
                        Slog.w(TAG, "Caught WebView crash, preventing crash: " + throwable.getMessage());
                        BlackBoxCore.get().sendLogs("CRASH DETECTED (Caught/WebView): " + throwable.getMessage(), true);
                        return; 
                    }

                    
                    if (isAttributionSourceCrash(throwable)) {
                        Slog.w(TAG, "Caught AttributionSource crash, preventing crash: " + throwable.getMessage());
                        BlackBoxCore.get().sendLogs("CRASH DETECTED (Caught/Attribution): " + throwable.getMessage(), true);
                        return; 
                    }

                    
                    if (isSocialMediaAppCrash(throwable)) {
                        Slog.w(TAG, "Caught social media app crash, preventing crash: " + throwable.getMessage());
                        BlackBoxCore.get().sendLogs("CRASH DETECTED (Caught/SocialMedia): " + throwable.getMessage(), true);
                        return; 
                    }

                    
                    // A background thread of a virtual Google app (GMS IntentOperations, Play
                    // Store executors) hitting a host-system restriction must not take the whole
                    // process down: every restart re-ran the same operation and crash-looped
                    // GMS. Pool workers are replaced, and the main thread stays fatal.
                    if (isGoogleBackgroundThreadCrash(thread)) {
                        Slog.w(TAG, "Swallowed background crash in " + thread.getName() + ": " + throwable);
                        return;
                    }

                    Slog.e(TAG, "Fatal crash detected, attempting to report before death...");
                    try {
                         BlackBoxCore.get().sendLogs("FATAL CRASH (Uncaught): " + throwable.getMessage(), false);
                    } catch (Throwable e) {
                         Slog.e(TAG, "Failed to report fatal crash: " + e.getMessage());
                    }

                    
                    if (currentHandler != null) {
                        currentHandler.uncaughtException(thread, throwable);
                    }
                }
            });
            
            Slog.d(TAG, "Global exception handler installed successfully");
        } catch (Exception e) {
            Slog.e(TAG, "Failed to install global exception handler: " + e.getMessage(), e);
        }
    }
    
    
    private static void installContextWrapperHook() {
        try {
            
            ContextWrapperHook.installHook();
            Slog.d(TAG, "Context wrapper hook installed");
        } catch (Exception e) {
            Slog.e(TAG, "Failed to install context wrapper hook: " + e.getMessage(), e);
        }
    }
    
    
    private static boolean isNullContextCrash(Throwable throwable) {
        if (throwable == null) {
            return false;
        }
        
        String message = throwable.getMessage();
        if (message != null) {
            return message.contains("Context") || 
                   message.contains("context") ||
                   message.contains("getResources") ||
                   message.contains("getPackageManager") ||
                   message.contains("getClassLoader");
        }
        
        
        StackTraceElement[] stackTrace = throwable.getStackTrace();
        if (stackTrace != null) {
            for (StackTraceElement element : stackTrace) {
                String className = element.getClassName();
                String methodName = element.getMethodName();
                
                if (className.contains("Context") || 
                    className.contains("ContextWrapper") ||
                    methodName.contains("getResources") ||
                    methodName.contains("getPackageManager") ||
                    methodName.contains("getClassLoader")) {
                    return true;
                }
            }
        }
        
        return false;
    }
    
    
    private static boolean isGoogleBackgroundThreadCrash(Thread thread) {
        try {
            if (thread == null || thread == android.os.Looper.getMainLooper().getThread()) {
                return false;
            }
            String pkg = top.niunaijun.blackbox.app.BActivityThread.getAppPackageName();
            return pkg != null && top.niunaijun.blackbox.core.GmsCore.isGoogleAppOrService(pkg);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isGooglePlayServicesCrash(Throwable throwable) {
        if (throwable == null) {
            return false;
        }
        
        String message = throwable.getMessage();
        if (message != null) {
            return message.contains("Google Play Services") ||
                   message.contains("GooglePlayServicesUtil") ||
                   message.contains("GoogleApiAvailability") ||
                   message.contains("com.google.android.gms");
        }
        
        
        StackTraceElement[] stackTrace = throwable.getStackTrace();
        if (stackTrace != null) {
            for (StackTraceElement element : stackTrace) {
                String className = element.getClassName();
                if (className.contains("com.google.android.gms") ||
                    className.contains("GooglePlayServicesUtil") ||
                    className.contains("GoogleApiAvailability")) {
                    return true;
                }
            }
        }
        
        return false;
    }

    
    private static boolean isWebViewCrash(Throwable throwable) {
        if (throwable == null) {
            return false;
        }
        
        String message = throwable.getMessage();
        if (message != null) {
            return message.contains("WebView") ||
                   message.contains("webview") ||
                   message.contains("WebViewDatabase") ||
                   message.contains("WebSettings") ||
                   message.contains("data directory");
        }
        
        
        StackTraceElement[] stackTrace = throwable.getStackTrace();
        if (stackTrace != null) {
            for (StackTraceElement element : stackTrace) {
                String className = element.getClassName();
                String methodName = element.getMethodName();
                if (className.contains("WebView") ||
                    className.contains("WebViewDatabase") ||
                    className.contains("WebSettings") ||
                    methodName.contains("webView") ||
                    methodName.contains("WebView")) {
                    return true;
                }
            }
        }
        
        return false;
    }

    
    private static boolean isAttributionSourceCrash(Throwable throwable) {
        if (throwable == null) {
            return false;
        }
        
        String message = throwable.getMessage();
        if (message != null) {
            return message.contains("AttributionSource") ||
                   message.contains("attribution") ||
                   message.contains("Calling uid") ||
                   message.contains("source uid") ||
                   message.contains("UID mismatch");
        }
        
        
        StackTraceElement[] stackTrace = throwable.getStackTrace();
        if (stackTrace != null) {
            for (StackTraceElement element : stackTrace) {
                String className = element.getClassName();
                String methodName = element.getMethodName();
                if (className.contains("AttributionSource") ||
                    className.contains("ContentProvider") ||
                    methodName.contains("enforceCallingUid") ||
                    methodName.contains("enforceCallingUidAndPid")) {
                    return true;
                }
            }
        }
        
        return false;
    }

    
    private static boolean isSocialMediaAppCrash(Throwable throwable) {
        if (throwable == null) {
            return false;
        }
        
        String message = throwable.getMessage();
        if (message != null) {
            return message.contains("Facebook") ||
                   message.contains("Instagram") ||
                   message.contains("WhatsApp") ||
                   message.contains("Telegram") ||
                   message.contains("Twitter") ||
                   message.contains("TikTok") ||
                   message.contains("Snapchat") ||
                   message.contains("YouTube") ||
                   message.contains("LinkedIn");
        }
        
        
        StackTraceElement[] stackTrace = throwable.getStackTrace();
        if (stackTrace != null) {
            for (StackTraceElement element : stackTrace) {
                String className = element.getClassName();
                if (className.contains("com.facebook") ||
                    className.contains("com.instagram") ||
                    className.contains("com.whatsapp") ||
                    className.contains("org.telegram") ||
                    className.contains("com.twitter") ||
                    className.contains("com.zhiliaoapp.musically") ||
                    className.contains("com.snapchat") ||
                    className.contains("com.google.android.youtube") ||
                    className.contains("com.linkedin")) {
                    return true;
                }
            }
        }
        
        return false;
    }
}
