package top.niunaijun.blackbox.fake.service.context.providers;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;

import java.util.LinkedHashMap;
import java.util.Map;

import top.niunaijun.blackbox.utils.Slog;

/**
 * Gservices values the virtual GMS needs but never receives.
 *
 * Until Phenotype has synced, GMS reads its own flags from Gservices ("gms:<pkg>:<flag>") and
 * falls back to the flag default. The virtual GMS never completes a Phenotype sync, and
 * Chimera__disable_container_optional_module_download defaults to true, so ModuleSetMgr
 * reported every optional module ("games" among them) as "is disabled" and Play Games
 * sign-in in games never found its implementation.
 */
final class GservicesOverrides {
    private static final String TAG = "GservicesOverrides";
    private static final String SELECTION_ARGS = "android:query-arg-sql-selection-args";

    private static final Map<String, String> VALUES = new LinkedHashMap<>();

    static {
        VALUES.put("gms:chimera:Chimera__disable_container_optional_module_download", "false");
        // Allowlist used if GMS still thinks the device is unprovisioned: default
        // [enterprise] plus the Play Games modules.
        VALUES.put("ChimeraCriticalPath__allowed_container_optional_features_when_unprovisioned",
                "CgplbnRlcnByaXNlCgVnYW1lcwoNZ2FtZV9wbGF0Zm9ybQ==");
    }

    private GservicesOverrides() {
    }

    static Object apply(Object[] args, Object result) {
        if (!(result instanceof Cursor)) {
            return result;
        }
        Uri uri = null;
        String[] keys = null;
        for (Object a : args) {
            if (a instanceof Uri) {
                uri = (Uri) a;
            } else if (a instanceof Bundle) {
                keys = ((Bundle) a).getStringArray(SELECTION_ARGS);
            }
        }
        if (uri == null || keys == null || keys.length == 0) {
            return result;
        }
        boolean prefix = "prefix".equals(uri.getLastPathSegment());
        Map<String, String> add = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : VALUES.entrySet()) {
            for (String k : keys) {
                if (k != null && (prefix ? e.getKey().startsWith(k) : e.getKey().equals(k))) {
                    add.put(e.getKey(), e.getValue());
                    break;
                }
            }
        }
        if (add.isEmpty()) {
            return result;
        }
        Cursor in = (Cursor) result;
        try {
            String[] cols = in.getColumnNames();
            if (cols == null || cols.length < 2) {
                cols = new String[]{"key", "value"};
            }
            MatrixCursor out = new MatrixCursor(cols);
            while (in.moveToNext()) {
                String key = in.getString(0);
                if (add.containsKey(key)) continue;
                Object[] row = new Object[cols.length];
                for (int i = 0; i < cols.length && i < in.getColumnCount(); i++) {
                    row[i] = in.getString(i);
                }
                out.addRow(row);
            }
            for (Map.Entry<String, String> e : add.entrySet()) {
                Object[] row = new Object[cols.length];
                row[0] = e.getKey();
                row[1] = e.getValue();
                out.addRow(row);
                Slog.d(TAG, "gservices override " + e.getKey() + "=" + e.getValue());
            }
            in.close();
            return out;
        } catch (Throwable t) {
            Slog.w(TAG, "override failed: " + t);
            in.moveToPosition(-1);
            return result;
        }
    }
}
