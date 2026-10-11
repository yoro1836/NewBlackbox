package top.niunaijun.blackbox.fake.delegate;

import android.app.IServiceConnection;
import android.content.ComponentName;
import android.content.Intent;
import android.os.IBinder;
import android.os.Bundle;
import android.os.Parcel;
import android.os.RemoteException;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.GmsCore;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.utils.Slog;

/**
 * Keeps the host's service dispatcher callback compatible with every
 * IServiceConnection signature used by the Android release running the host.
 *
 * The AIDL shipped with this module intentionally contains the oldest
 * two-argument callback so it remains loadable on old Android releases.
 * Newer releases add a dead flag and, on recent releases, an IBinderSession.
 * Those arguments are delivered to the delegate's local callback through
 * reflection instead of being part of the packaged AIDL contract.
 */
public class ServiceConnectionDelegate extends IServiceConnection.Stub {
    private static final String GMS_SERVICE_BROKER_DESCRIPTOR =
            "com.google.android.gms.common.internal.IGmsServiceBroker";
    private static final String MEASUREMENT_SERVICE_DESCRIPTOR =
            "com.google.android.gms.measurement.internal.IMeasurementService";
    private static final int SAFE_PARCEL_OBJECT_ID = 20293;
    private static final int MEASUREMENT_METADATA_FIELD_ID = 2;
    private static final Map<IBinder, ServiceConnectionDelegate> sServiceConnectDelegate =
            new HashMap<>();

    private final IServiceConnection mConn;
    private ComponentName mComponentName;
    private boolean mWrapServiceBinder;

    private ServiceConnectionDelegate(IServiceConnection mConn, ComponentName targetComponent,
                                      boolean wrapServiceBinder) {
        this.mConn = mConn;
        this.mComponentName = targetComponent;
        this.mWrapServiceBinder = wrapServiceBinder;
    }

    public static ServiceConnectionDelegate getDelegate(IBinder iBinder) {
        return sServiceConnectDelegate.get(iBinder);
    }

    public static IServiceConnection createProxy(IServiceConnection base, Intent intent) {
        return createProxy(base, intent, false);
    }

    public static IServiceConnection createProxy(IServiceConnection base, Intent intent,
                                                boolean wrapServiceBinder) {
        final IBinder iBinder = base.asBinder();
        ComponentName targetComponent = intent != null ? intent.getComponent() : null;
        ServiceConnectionDelegate delegate = sServiceConnectDelegate.get(iBinder);
        if (delegate == null) {
            try {
                iBinder.linkToDeath(new IBinder.DeathRecipient() {
                    @Override
                    public void binderDied() {
                        sServiceConnectDelegate.remove(iBinder);
                        iBinder.unlinkToDeath(this, 0);
                    }
                }, 0);
            } catch (RemoteException ignored) {
                // The callback can still be used until its owner goes away.
            }
            delegate = new ServiceConnectionDelegate(base, targetComponent, wrapServiceBinder);
            sServiceConnectDelegate.put(iBinder, delegate);
        } else {
            delegate.mComponentName = targetComponent;
            delegate.mWrapServiceBinder = wrapServiceBinder;
        }
        return delegate;
    }

    @Override
    public void connected(ComponentName name, IBinder service) throws RemoteException {
        dispatchConnected(name, service, null, false);
    }

    /**
     * API 26+ callback shape. Kept as an overload because the packaged AIDL
     * intentionally exposes only the API 25-compatible method.
     */
    public void connected(ComponentName name, IBinder service, boolean dead)
            throws RemoteException {
        dispatchConnected(name, service, null, dead);
    }

    /**
     * API 36+ callback shape used by reflective callers whose session class is
     * not available at compile time.
     */
    public void connected(ComponentName name, IBinder service, Object session, boolean dead)
            throws RemoteException {
        dispatchConnected(name, service, session, dead);
    }

    /**
     * Exact API 37 method signature. The framework's generated Stub invokes
     * this overload directly; the Object overload alone is not sufficient
     * because method dispatch does not use assignability.
     */
    public void connected(ComponentName name, IBinder service,
                          android.app.IBinderSession session, boolean dead)
            throws RemoteException {
        dispatchConnected(name, service, session, dead);
    }

    private void dispatchConnected(ComponentName name, IBinder service, Object session,
                                   boolean dead) throws RemoteException {
        ComponentName callbackName = mComponentName != null ? mComponentName : name;
        IBinder callbackService = mWrapServiceBinder
                ? wrapServiceBinder(callbackName != null ? callbackName : name, service)
                : service;
        Method callback = findCallbackMethod(mConn.getClass());
        if (callback == null) {
            mConn.connected(callbackName, callbackService);
            return;
        }

        try {
            callback.setAccessible(true);
            Class<?>[] parameterTypes = callback.getParameterTypes();
            if (parameterTypes.length == 4) {
                callback.invoke(mConn, callbackName, callbackService, session, dead);
            } else if (parameterTypes.length == 3
                    && isBoolean(parameterTypes[2])) {
                callback.invoke(mConn, callbackName, callbackService, dead);
            } else if (parameterTypes.length == 3) {
                callback.invoke(mConn, callbackName, callbackService, session);
            } else {
                callback.invoke(mConn, callbackName, callbackService);
            }
        } catch (InvocationTargetException e) {
            rethrowCallbackFailure(e.getCause());
        } catch (IllegalAccessException | RuntimeException e) {
            // Hidden framework callbacks may reject access on some releases.
            // The two-argument callback is the portable last resort.
            mConn.connected(callbackName, callbackService);
        }
    }

    private static IBinder wrapServiceBinder(ComponentName component, IBinder service) {
        if (service == null || component == null
                || !GmsCore.isGoogleAppOrService(component.getPackageName())
                || service instanceof GmsServiceBinderProxy) {
            return service;
        }
        return new GmsServiceBinderProxy(service);
    }

    /**
     * This wrapper is installed only for a host GMS bind. Host GMS validates
     * the package in GetServiceRequest against the caller UID; virtual apps
     * share the host UID, so forward the host package while preserving binder
     * objects and parcel headers.
     */
    private static final class GmsServiceBinderProxy extends BinderInvocationStub {
        private final IBinder mBase;

        GmsServiceBinderProxy(IBinder base) {
            super(base);
            mBase = base;
        }

        @Override
        protected Object getWho() {
            return mBase;
        }

        @Override
        protected void inject(Object baseInvocation, Object proxyInvocation) {
        }

        @Override
        public boolean isBadEnv() {
            return false;
        }

        @Override
        public android.os.IInterface queryLocalInterface(String descriptor) {
            return null;
        }

        @Override
        public boolean transact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            Parcel rewritten = code == 46 ? rewriteGetServiceRequest(data) : null;
            if (rewritten == null) {
                rewritten = rewriteMeasurementRequest(data, code);
            }
            if (rewritten == null) {
                return mBase.transact(code, data, reply, flags);
            }
            try {
                return mBase.transact(code, rewritten, reply, flags);
            } finally {
                rewritten.recycle();
            }
        }
    }

    private static Parcel rewriteMeasurementRequest(Parcel source, int code) {
        if (source == null) {
            return null;
        }
        String virtualPackage = BlackBoxCore.getAppPackageName();
        String hostPackage = BlackBoxCore.getHostPkg();
        if (virtualPackage == null || hostPackage == null
                || virtualPackage.equals(hostPackage)) {
            return null;
        }

        int originalPosition = source.dataPosition();
        Parcel rewrittenObject = null;
        try {
            int tokenPosition = findInterfaceToken(source, MEASUREMENT_SERVICE_DESCRIPTOR);
            if (tokenPosition < 0) {
                return null;
            }
            source.setDataPosition(tokenPosition);
            if (!MEASUREMENT_SERVICE_DESCRIPTOR.equals(source.readString())) {
                return null;
            }

            int metadataPosition = findMeasurementMetadata(source, code);
            if (metadataPosition < 0) {
                return null;
            }
            source.setDataPosition(metadataPosition);
            int present = source.readInt();
            if (present != 1) {
                return null;
            }
            int objectStart = source.dataPosition();
            int objectEnd = readSafeParcelObjectEnd(source, objectStart);
            if (objectEnd < 0) {
                return null;
            }
            rewrittenObject = rewriteSafeParcelObject(
                    source, objectStart, objectEnd, virtualPackage, hostPackage,
                    MEASUREMENT_METADATA_FIELD_ID);
            if (rewrittenObject == null) {
                return null;
            }

            Parcel rewritten = Parcel.obtain();
            appendFrom(rewritten, source, 0, metadataPosition);
            rewritten.writeInt(present);
            appendFrom(rewritten, rewrittenObject, 0, rewrittenObject.dataSize());
            appendFrom(rewritten, source, objectEnd,
                    source.dataSize() - objectEnd);
            rewritten.setDataPosition(0);
            Slog.d("GmsBinderProxy",
                    "Rewrote measurement metadata package "
                            + virtualPackage + " to " + hostPackage
                            + " for transaction " + code);
            return rewritten;
        } catch (Throwable e) {
            Slog.w("GmsBinderProxy",
                    "Unable to rewrite measurement request for transaction " + code, e);
            return null;
        } finally {
            if (rewrittenObject != null) {
                rewrittenObject.recycle();
            }
            source.setDataPosition(originalPosition);
        }
    }

    private static int findMeasurementMetadata(Parcel source, int code) {
        try {
            switch (code) {
                case 1:
                case 2:
                case 12:
                    if (skipSafeParcelable(source) < 0) {
                        return -1;
                    }
                    return source.dataPosition();
                case 4:
                case 6:
                case 11:
                case 18:
                    return source.dataPosition();
                case 7:
                    return source.dataPosition();
                case 14:
                    source.readString();
                    source.readString();
                    source.readInt();
                    return source.dataPosition();
                case 16:
                    source.readString();
                    source.readString();
                    return source.dataPosition();
                case 19:
                    if (skipBundle(source) < 0) {
                        return -1;
                    }
                    return source.dataPosition();
                default:
                    return -1;
            }
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static int skipSafeParcelable(Parcel source) {
        if (source.dataPosition() + 4 > source.dataSize()) {
            return -1;
        }
        int present = source.readInt();
        if (present == 0) {
            return source.dataPosition();
        }
        if (present != 1) {
            return -1;
        }
        int objectEnd = readSafeParcelObjectEnd(source, source.dataPosition());
        if (objectEnd < 0) {
            return -1;
        }
        source.setDataPosition(objectEnd);
        return objectEnd;
    }

    private static int skipBundle(Parcel source) {
        if (source.dataPosition() + 4 > source.dataSize()) {
            return -1;
        }
        int present = source.readInt();
        if (present == 0) {
            return source.dataPosition();
        }
        if (present != 1) {
            return -1;
        }
        Bundle.CREATOR.createFromParcel(source);
        return source.dataPosition();
    }

    private static int readSafeParcelObjectEnd(Parcel source, int objectStart) {
        int savedPosition = source.dataPosition();
        try {
            source.setDataPosition(objectStart);
            int objectHeader = source.readInt();
            if ((objectHeader & 0xFFFF) != SAFE_PARCEL_OBJECT_ID) {
                return -1;
            }
            int headerSize = objectHeader >>> 16;
            int payloadStart;
            int payloadSize;
            if (headerSize == 0xFFFF) {
                payloadSize = source.readInt();
                payloadStart = source.dataPosition();
            } else {
                payloadSize = headerSize;
                payloadStart = objectStart + 4;
            }
            long objectEnd = (long) payloadStart + payloadSize;
            if (objectEnd < payloadStart || objectEnd > source.dataSize()) {
                return -1;
            }
            return (int) objectEnd;
        } finally {
            source.setDataPosition(savedPosition);
        }
    }

    private static Parcel rewriteSafeParcelObject(
            Parcel source, int objectStart, int objectEnd,
            String virtualPackage, String hostPackage, int packageFieldId) {
        int savedPosition = source.dataPosition();
        Parcel payload = Parcel.obtain();
        try {
            source.setDataPosition(objectStart);
            int objectHeader = source.readInt();
            int headerSize = objectHeader >>> 16;
            if (headerSize == 0xFFFF) {
                source.readInt();
            }
            int payloadStart = source.dataPosition();
            boolean changed = false;
            while (source.dataPosition() < objectEnd) {
                int fieldStart = source.dataPosition();
                int fieldHeader = source.readInt();
                int fieldSize = fieldHeader >>> 16;
                if (fieldSize == 0xFFFF) {
                    fieldSize = source.readInt();
                }
                int fieldDataStart = source.dataPosition();
                long fieldEndLong = (long) fieldDataStart + fieldSize;
                if (fieldEndLong < fieldDataStart || fieldEndLong > objectEnd) {
                    return null;
                }
                int fieldEnd = (int) fieldEndLong;
                int fieldId = fieldHeader & 0xFFFF;
                boolean isPackageField = fieldId == packageFieldId;
                if (isPackageField) {
                    try {
                        source.setDataPosition(fieldDataStart);
                        isPackageField = virtualPackage.equals(source.readString());
                    } catch (Throwable ignored) {
                        isPackageField = false;
                    }
                    source.setDataPosition(fieldDataStart);
                }
                if (isPackageField) {
                    Parcel stringPayload = Parcel.obtain();
                    try {
                        stringPayload.writeString(hostPackage);
                        writeSafeParcelField(payload, fieldId, stringPayload);
                    } finally {
                        stringPayload.recycle();
                    }
                    changed = true;
                } else {
                    appendFrom(payload, source, fieldStart, fieldEnd - fieldStart);
                }
                source.setDataPosition(fieldEnd);
            }
            if (!changed || source.dataPosition() != objectEnd) {
                return null;
            }

            Parcel rewritten = Parcel.obtain();
            rewritten.writeInt(0xFFFF0000 | SAFE_PARCEL_OBJECT_ID);
            rewritten.writeInt(payload.dataSize());
            appendFrom(rewritten, payload, 0, payload.dataSize());
            rewritten.setDataPosition(0);
            return rewritten;
        } finally {
            payload.recycle();
            source.setDataPosition(savedPosition);
        }
    }

    private static void writeSafeParcelField(Parcel target, int fieldId, Parcel value) {
        int fieldSize = value.dataSize();
        if (fieldSize >= 0xFFFF) {
            target.writeInt(0xFFFF0000 | fieldId);
            target.writeInt(fieldSize);
        } else {
            target.writeInt((fieldSize << 16) | fieldId);
        }
        appendFrom(target, value, 0, fieldSize);
    }

    private static Parcel rewriteGetServiceRequest(Parcel source) {
        if (source == null) {
            return null;
        }
        String virtualPackage = BlackBoxCore.getAppPackageName();
        String hostPackage = BlackBoxCore.getHostPkg();
        if (virtualPackage == null || hostPackage == null
                || virtualPackage.equals(hostPackage)) {
            return null;
        }

        int originalPosition = source.dataPosition();
        Parcel rewrittenObject = null;
        try {
            /*
             * Do not deserialize GetServiceRequest here. The request class is
             * loaded by Play services and its CREATOR can differ from the
             * class loader active at this callback. Rebuilding it through the
             * wrong CREATOR either loses binder objects or corrupts the
             * safe-parcel header. Rewrite only the package string in place.
             */
            int tokenPosition = findInterfaceToken(source, GMS_SERVICE_BROKER_DESCRIPTOR);
            if (tokenPosition < 0) {
                return null;
            }
            source.setDataPosition(tokenPosition);
            String descriptor = source.readString();
            if (!GMS_SERVICE_BROKER_DESCRIPTOR.equals(descriptor)) {
                return null;
            }
            IBinder callback = source.readStrongBinder();
            int present = source.readInt();
            if (present != 1) {
                return null;
            }

            int objectStart = source.dataPosition();
            int objectEnd = readSafeParcelObjectEnd(source, objectStart);
            if (objectEnd < 0) {
                return null;
            }
            rewrittenObject = rewriteSafeParcelObject(
                    source, objectStart, objectEnd, virtualPackage, hostPackage, 4);
            if (rewrittenObject == null) {
                return null;
            }

            Parcel rewritten = Parcel.obtain();
            appendFrom(rewritten, source, 0, tokenPosition);
            rewritten.writeString(descriptor);
            rewritten.writeStrongBinder(callback);
            rewritten.writeInt(present);
            appendFrom(rewritten, rewrittenObject, 0, rewrittenObject.dataSize());
            appendFrom(rewritten, source, objectEnd, source.dataSize() - objectEnd);
            rewritten.setDataPosition(0);
            Slog.d("GmsBinderProxy", "Rewriting broker package "
                    + virtualPackage + " to " + hostPackage);
            return rewritten;
        } catch (Throwable e) {
            Slog.w("GmsBinderProxy", "Unable to rewrite GMS broker request", e);
            return null;
        } finally {
            if (rewrittenObject != null) {
                rewrittenObject.recycle();
            }
            source.setDataPosition(originalPosition);
        }
    }

    private static int findInterfaceToken(Parcel source, String descriptor) {
        for (int offset = 0; offset <= 128 && offset + 4 <= source.dataSize(); offset += 4) {
            try {
                source.setDataPosition(offset);
                if (descriptor.equals(source.readString())) {
                    return offset;
                }
            } catch (Throwable ignored) {
            }
        }
        return -1;
    }

    private static void appendFrom(Parcel target, Parcel source, int offset, int length) {
        if (length <= 0) {
            return;
        }
        target.setDataPosition(target.dataSize());
        target.appendFrom(source, offset, length);
    }


    private static Method findCallbackMethod(Class<?> type) {
        Method best = null;
        int bestScore = -1;
        for (Class<?> current = type;
             current != null && current != Object.class;
             current = current.getSuperclass()) {
            Method[] methods;
            try {
                methods = current.getDeclaredMethods();
            } catch (Throwable ignored) {
                continue;
            }
            for (Method method : methods) {
                if (!"connected".equals(method.getName())) {
                    continue;
                }
                Class<?>[] params = method.getParameterTypes();
                if (params.length < 2
                        || !ComponentName.class.isAssignableFrom(params[0])
                        || !IBinder.class.isAssignableFrom(params[1])) {
                    continue;
                }

                int score = callbackScore(params);
                if (score > bestScore) {
                    best = method;
                    bestScore = score;
                }
            }
        }
        return best;
    }

    private static int callbackScore(Class<?>[] params) {
        if (params.length == 4 && isBoolean(params[3])) {
            return 400;
        }
        if (params.length == 3 && isBoolean(params[2])) {
            return 300;
        }
        if (params.length == 3) {
            return 200;
        }
        if (params.length == 2) {
            return 100;
        }
        return -1;
    }

    private static boolean isBoolean(Class<?> type) {
        return type == boolean.class || type == Boolean.class;
    }

    private static void rethrowCallbackFailure(Throwable failure) throws RemoteException {
        if (failure instanceof RemoteException) {
            throw (RemoteException) failure;
        }
        if (failure instanceof RuntimeException) {
            throw (RuntimeException) failure;
        }
        if (failure instanceof Error) {
            throw (Error) failure;
        }
        RemoteException wrapped = new RemoteException("IServiceConnection callback failed");
        wrapped.initCause(failure);
        throw wrapped;
    }
}
