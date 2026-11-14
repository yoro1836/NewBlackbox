package top.niunaijun.blackbox.core.gms;

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

    public static void apply(int userId) {
        patch(new File(BEnvironment.getDeDataDir(GMS, userId), "app_chimera/stored_modulesets.pb"));
        patch(new File(BEnvironment.getDataDir(GMS, userId), "app_chimera/stored_modulesets.pb"));
    }

    private static void patch(File f) {
        try {
            if (!f.isFile()) {
                Slog.d(TAG, "no config at " + f);
                return;
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
                        return;
                }
                if (p > in.length) {
                    Slog.w(TAG, "truncated " + f);
                    return;
                }
                if (field == FIELD && wire == 0) {
                    if (value == 0) alreadyOff = true;
                    continue;
                }
                out.write(in, start, p - start);
            }
            if (alreadyOff) {
                Slog.d(TAG, "optional chimera modules already enabled in " + f);
                return;
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
                return;
            }
            Slog.i(TAG, "enabled optional chimera module downloads in " + f);
        } catch (Throwable e) {
            Slog.w(TAG, "patch " + f + " failed: " + e);
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
