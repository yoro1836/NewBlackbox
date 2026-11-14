package top.niunaijun.blackbox.proxy;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PersistableBundle;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import top.niunaijun.blackbox.core.system.am.BActivityManagerService;
import top.niunaijun.blackbox.utils.Slog;

/**
 * Host job service for every virtual job. Runs in the server process, which outlives the
 * virtual apps, so a job that fires after its app died still runs: it binds the virtual
 * JobService through the virtual activity manager (starting the app's process) and hands
 * it the JobParameters. The virtual service reports back through the parameters' own
 * callback, so jobFinished goes straight to the system.
 *
 * The per-process ProxyJobService slots only worked while the app that scheduled the job
 * still lived in the same slot, so GMS's deferred jobs (Phenotype sync, module downloads)
 * effectively never ran.
 */
public class VirtualJobService extends JobService {
    private static final String TAG = "VirtualJobService";
    public static final String EXTRA_PKG = "_B_|job_pkg";
    public static final String EXTRA_CLS = "_B_|job_cls";
    public static final String EXTRA_USER = "_B_|job_user";
    private static final long UNBIND_AFTER_MS = 10 * 60 * 1000L;

    private final Map<Integer, Conn> mRunning = new HashMap<>();
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    @Override
    public boolean onStartJob(JobParameters params) {
        PersistableBundle ex = params.getExtras();
        String pkg = ex == null ? null : ex.getString(EXTRA_PKG);
        String cls = ex == null ? null : ex.getString(EXTRA_CLS);
        int user = ex == null ? 0 : ex.getInt(EXTRA_USER, 0);
        if (pkg == null || cls == null) {
            Slog.w(TAG, "job " + params.getJobId() + " has no virtual target");
            return false;
        }
        try {
            Intent target = new Intent().setComponent(new ComponentName(pkg, cls));
            Intent stub = BActivityManagerService.get().bindService(target, null, null, user);
            if (stub == null) {
                Slog.w(TAG, "no virtual service for job " + params.getJobId() + " " + pkg + "/" + cls);
                return false;
            }
            Conn conn = new Conn(params);
            if (!bindService(stub, conn, BIND_AUTO_CREATE)) {
                Slog.w(TAG, "bind failed for job " + params.getJobId() + " " + pkg + "/" + cls);
                return false;
            }
            synchronized (mRunning) {
                Conn old = mRunning.put(params.getJobId(), conn);
                if (old != null) old.release();
            }
            Slog.d(TAG, "start job " + params.getJobId() + " -> " + pkg + "/" + cls);
            return true;
        } catch (Throwable e) {
            Slog.w(TAG, "start job " + params.getJobId() + " failed: " + e);
            return false;
        }
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        Conn conn;
        synchronized (mRunning) {
            conn = mRunning.remove(params.getJobId());
        }
        if (conn != null) {
            conn.call("stopJob", params);
            conn.release();
        }
        return false;
    }

    @Override
    public void onDestroy() {
        synchronized (mRunning) {
            for (Conn c : mRunning.values()) c.release();
            mRunning.clear();
        }
        super.onDestroy();
    }

    private class Conn implements ServiceConnection {
        private final JobParameters mParams;
        private IBinder mBinder;
        private boolean mBound = true;
        private final Runnable mTimeout = this::release;

        Conn(JobParameters params) {
            mParams = params;
            mHandler.postDelayed(mTimeout, UNBIND_AFTER_MS);
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            boolean first = mBinder == null;
            mBinder = service;
            if (first) call("startJob", mParams);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mBinder = null;
        }

        void call(String method, JobParameters params) {
            IBinder b = mBinder;
            if (b == null) return;
            try {
                Class<?> stub = Class.forName("android.app.job.IJobService$Stub");
                Object svc = stub.getMethod("asInterface", IBinder.class).invoke(null, b);
                Method m = Class.forName("android.app.job.IJobService").getMethod(method, JobParameters.class);
                m.invoke(svc, params);
            } catch (Throwable e) {
                Slog.w(TAG, method + " for job " + params.getJobId() + " failed: " + e);
            }
        }

        void release() {
            mHandler.removeCallbacks(mTimeout);
            if (!mBound) return;
            mBound = false;
            try {
                unbindService(this);
            } catch (Throwable ignored) {
            }
        }
    }
}
