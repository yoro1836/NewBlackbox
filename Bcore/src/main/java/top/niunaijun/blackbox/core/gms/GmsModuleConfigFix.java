package top.niunaijun.blackbox.core.gms;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;

import top.niunaijun.blackbox.core.env.BEnvironment;
import top.niunaijun.blackbox.utils.Slog;

/**
 * Clears the "optional modules disabled" bit in the virtual GMS's chimera config before GMS
 * starts.
 *
 * ModuleSetMgr keeps app_chimera/stored_modulesets.pb; its field 8 (default true) is copied
 * from Chimera__disable_container_optional_module_download, which only flips to false after a
 * Phenotype sync the virtual GMS never completes. While it is true ModuleSetMgr refuses every
 * optional module ("Download of container feature games is disabled"), so Play Games never
 * gets its implementation.
 */
public final class GmsModuleConfigFix {
    private static final String TAG = "GmsModuleConfigFix";
    private static final String GMS = "com.google.android.gms";
    private static final int FIELD = 8;

    private GmsModuleConfigFix() {
    }

    /** Returns true when a config was changed and GMS should recheck its modules. */
    public static boolean apply(int userId) {
        boolean a = patch(new File(BEnvironment.getDeDataDir(GMS, userId), "app_chimera/stored_modulesets.pb"));
        boolean b = patch(new File(BEnvironment.getDataDir(GMS, userId), "app_chimera/stored_modulesets.pb"));
        // Builds that patched the file without asking for a recheck left it patched but
        // unused; ask once more per marker version.
        boolean first = false;
        try {
            File marker = new File(BEnvironment.getDeDataDir(GMS, userId), "app_chimera/.bb_recheck_1");
            first = marker.getParentFile().isDirectory() && marker.createNewFile();
        } catch (Throwable ignored) {
        }
        return a || b || first;
    }

    /**
     * Only ChimeraConfigurator's config update downloads modules (via Play Store's Zapp), and
     * it runs on container events that the virtual GMS rarely sees. Ask for one, as the
     * system does when requested features change.
     */
    public static void requestRecheck(final Context context) {
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    Intent i = new Intent("com.google.android.chimera.container.REQUESTED_FEATURES_CHANGED")
                            .setClassName(GMS, "com.google.android.gms.chimera.GmsIntentOperationService");
                    context.startService(i);
                    Slog.i(TAG, "asked GMS to recheck chimera modules");
                } catch (Throwable e) {
                    Slog.w(TAG, "chimera recheck request failed: " + e);
                }
            }
        }, 5000);
    }

    private static boolean patch(File f) {
        try {
            if (!f.isFile()) {
                Slog.d(TAG, "no config at " + f);
                return false;
            }
            byte[] in = Files.readAllBytes(f.toPath());
            ByteArrayOutputStream out = new ByteArrayOutputStream(in.length + 2);
            int p = 0;
            boolean alreadyOff = false;
            while (p < in.length) {
                int start = p;
                long[] tag = varint(in, p);
                p = (int) tag[1];
                int field = (int) (tag[0] >>> 3);
                int wire = (int) (tag[0] & 7);
                long value = 0;
                switch (wire) {
                    case 0: {
                        long[] v = varint(in, p);
                        value = v[0];
                        p = (int) v[1];
                        break;
                    }
                    case 1:
                        p += 8;
                        break;
                    case 2: {
                        long[] len = varint(in, p);
                        p = (int) len[1] + (int) len[0];
                        break;
                    }
                    case 5:
                        p += 4;
                        break;
                    default:
                        Slog.w(TAG, "unknown wire type " + wire + " in " + f);
                        return false;
                }
                if (p > in.length) {
                    Slog.w(TAG, "truncated " + f);
                    return false;
                }
                if (field == FIELD && wire == 0) {
                    if (value == 0) alreadyOff = true;
                    continue;
                }
                out.write(in, start, p - start);
            }
            if (alreadyOff) {
                Slog.d(TAG, "optional chimera modules already enabled in " + f);
                return false;
            }
            out.write(FIELD << 3);
            out.write(0);
            File tmp = new File(f.getParentFile(), f.getName() + ".bbtmp");
            try (FileOutputStream os = new FileOutputStream(tmp)) {
                os.write(out.toByteArray());
                os.getFD().sync();
            }
            if (!tmp.renameTo(f)) {
                tmp.delete();
                Slog.w(TAG, "rename failed for " + f);
                return false;
            }
            Slog.i(TAG, "enabled optional chimera module downloads in " + f);
            return true;
        } catch (Throwable e) {
            Slog.w(TAG, "patch " + f + " failed: " + e);
            return false;
        }
    }

    private static long[] varint(byte[] b, int p) {
        long r = 0;
        int shift = 0;
        while (p < b.length) {
            int x = b[p++] & 0xff;
            r |= (long) (x & 0x7f) << shift;
            if ((x & 0x80) == 0) break;
            shift += 7;
        }
        return new long[]{r, p};
    }
}
