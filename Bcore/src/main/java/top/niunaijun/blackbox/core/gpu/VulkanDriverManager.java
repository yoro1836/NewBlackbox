package top.niunaijun.blackbox.core.gpu;

import android.os.Build;
import android.text.TextUtils;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import top.niunaijun.blackbox.core.env.BEnvironment;
import top.niunaijun.blackbox.utils.FileUtils;
import top.niunaijun.blackbox.utils.Slog;

/**
 * Imports and applies custom Vulkan drivers (Mesa Turnip, PanVK, ...) to virtual
 * apps.
 *
 * <p>Drivers are distributed as adrenotools-style packages: a zip containing the
 * driver {@code .so} alongside a {@code meta.json} descriptor. Each imported
 * package is extracted into its own directory under {@link
 * BEnvironment#getGpuDriverDir()} and keyed by a generated id. Assignment is one
 * driver per app, stored in {@link BEnvironment#getGpuAppConf()}.
 *
 * <p>{@link #apply(String)} runs inside the virtual app process just before the
 * application is created and hands the driver off to the native loader. It never
 * throws: a missing or incompatible driver simply leaves the app on the system
 * driver.
 */
public final class VulkanDriverManager {
    private static final String TAG = "VulkanDriverManager";
    private static final String META_NAME = "meta.json";
    /** The native loader needs the stable linker-namespace APIs from Android 9. */
    public static final int MIN_API = Build.VERSION_CODES.P;

    private VulkanDriverManager() {
    }

    /** Metadata of an imported driver package. */
    public static final class DriverInfo {
        public final String id;
        public final String name;
        public final String author;
        public final String description;
        public final String vendor;
        public final String driverVersion;
        public final String libraryName;
        public final int minApi;
        public final File path;

        DriverInfo(String id, String name, String author, String description, String vendor,
                   String driverVersion, String libraryName, int minApi, File path) {
            this.id = id;
            this.name = name;
            this.author = author;
            this.description = description;
            this.vendor = vendor;
            this.driverVersion = driverVersion;
            this.libraryName = libraryName;
            this.minApi = minApi;
            this.path = path;
        }

        public File libraryFile() {
            return new File(path, libraryName);
        }
    }

    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= MIN_API;
    }

    // ---------------------------------------------------------------- import

    /**
     * Extracts a driver package from {@code zipStream} and registers it.
     *
     * @return the imported driver, or {@code null} when the package is invalid
     * @throws IOException on read/extract failure
     */
    public static synchronized DriverInfo importDriver(InputStream zipStream) throws IOException {
        File root = BEnvironment.getGpuDriverDir();
        FileUtils.mkdirs(root.getAbsolutePath());
        File tmp = new File(root, ".import-" + System.currentTimeMillis());
        FileUtils.mkdirs(tmp.getAbsolutePath());
        try {
            extractFlat(zipStream, tmp);
            File meta = new File(tmp, META_NAME);
            if (!meta.isFile()) {
                Slog.w(TAG, "Package has no " + META_NAME);
                return null;
            }
            DriverInfo parsed = parseMeta(meta, tmp);
            if (parsed == null) {
                return null;
            }
            if (!new File(tmp, parsed.libraryName).isFile()) {
                Slog.w(TAG, "Package is missing its driver library: " + parsed.libraryName);
                return null;
            }
            String id = sanitize(parsed.name.isEmpty() ? "driver" : parsed.name)
                    + "-" + Long.toHexString(System.currentTimeMillis());
            File dest = new File(root, id);
            if (dest.exists()) {
                FileUtils.deleteDir(dest);
            }
            if (!tmp.renameTo(dest)) {
                throw new IOException("Cannot move imported driver into place");
            }
            return parseMeta(new File(dest, META_NAME), dest);
        } finally {
            if (tmp.exists()) {
                FileUtils.deleteDir(tmp);
            }
        }
    }

    /** Extracts every zip entry by base name into {@code dir} (guards zip-slip). */
    private static void extractFlat(InputStream zipStream, File dir) throws IOException {
        ZipInputStream zis = new ZipInputStream(zipStream);
        try {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    zis.closeEntry();
                    continue;
                }
                String base = new File(entry.getName()).getName();
                if (base.isEmpty() || base.equals(".") || base.equals("..")) {
                    zis.closeEntry();
                    continue;
                }
                File out = new File(dir, base);
                java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
                try {
                    int count;
                    while ((count = zis.read(buffer)) != -1) {
                        fos.write(buffer, 0, count);
                    }
                } finally {
                    fos.close();
                }
                zis.closeEntry();
            }
        } finally {
            zis.close();
        }
    }

    private static DriverInfo parseMeta(File meta, File dir) {
        try {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new FileReader(meta))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            JSONObject json = new JSONObject(sb.toString());
            String library = json.optString("libraryName", "");
            if (TextUtils.isEmpty(library)) {
                Slog.w(TAG, "meta.json has no libraryName");
                return null;
            }
            return new DriverInfo(
                    dir.getName(),
                    json.optString("name", dir.getName()),
                    json.optString("author", ""),
                    json.optString("description", ""),
                    json.optString("vendor", ""),
                    json.optString("driverVersion", ""),
                    library,
                    json.optInt("minApi", MIN_API),
                    dir);
        } catch (Exception e) {
            Slog.w(TAG, "Cannot parse driver meta.json", e);
            return null;
        }
    }

    private static String sanitize(String name) {
        String s = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return s.length() > 40 ? s.substring(0, 40) : s;
    }

    // ----------------------------------------------------------------- list

    public static List<DriverInfo> listDrivers() {
        List<DriverInfo> out = new ArrayList<>();
        File root = BEnvironment.getGpuDriverDir();
        File[] children = root.listFiles();
        if (children == null) {
            return out;
        }
        for (File child : children) {
            if (!child.isDirectory() || child.getName().startsWith(".")) {
                continue;
            }
            File meta = new File(child, META_NAME);
            if (!meta.isFile()) {
                continue;
            }
            DriverInfo info = parseMeta(meta, child);
            if (info != null) {
                out.add(info);
            }
        }
        return out;
    }

    public static DriverInfo getDriver(String id) {
        if (TextUtils.isEmpty(id)) {
            return null;
        }
        File dir = new File(BEnvironment.getGpuDriverDir(), id);
        File meta = new File(dir, META_NAME);
        if (!meta.isFile()) {
            return null;
        }
        return parseMeta(meta, dir);
    }

    public static synchronized void deleteDriver(String id) {
        if (TextUtils.isEmpty(id)) {
            return;
        }
        File dir = new File(BEnvironment.getGpuDriverDir(), id);
        if (dir.isDirectory()) {
            FileUtils.deleteDir(dir);
        }
        // Drop any app assignments that pointed at the removed driver.
        Map<String, String> map = readAppMap();
        boolean changed = map.values().removeAll(java.util.Collections.singleton(id));
        if (changed) {
            writeAppMap(map);
        }
    }

    // --------------------------------------------------------- app mapping

    private static synchronized Map<String, String> readAppMap() {
        Map<String, String> out = new LinkedHashMap<>();
        File conf = BEnvironment.getGpuAppConf();
        if (conf == null || !conf.isFile()) {
            return out;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(conf))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                int eq = line.indexOf('=');
                if (line.isEmpty() || line.startsWith("#") || eq <= 0) {
                    continue;
                }
                String pkg = line.substring(0, eq).trim();
                String id = line.substring(eq + 1).trim();
                if (!pkg.isEmpty() && !id.isEmpty()) {
                    out.put(pkg, id);
                }
            }
        } catch (IOException e) {
            Slog.w(TAG, "Failed to read gpu app conf", e);
        }
        return out;
    }

    private static synchronized void writeAppMap(Map<String, String> map) {
        File conf = BEnvironment.getGpuAppConf();
        if (conf == null) {
            return;
        }
        File parent = conf.getParentFile();
        if (parent != null) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        try (FileWriter writer = new FileWriter(conf, false)) {
            for (Map.Entry<String, String> e : map.entrySet()) {
                writer.write(e.getKey() + "=" + e.getValue() + "\n");
            }
        } catch (IOException e) {
            Slog.w(TAG, "Failed to save gpu app conf", e);
        }
    }

    /** The driver id assigned to {@code packageName}, or null when none. */
    public static String getDriverForApp(String packageName) {
        return readAppMap().get(packageName);
    }

    /** Assigns (driverId != null) or clears (driverId == null) an app's driver. */
    public static synchronized void setDriverForApp(String packageName, String driverId) {
        Map<String, String> map = readAppMap();
        if (TextUtils.isEmpty(driverId)) {
            map.remove(packageName);
        } else {
            map.put(packageName, driverId);
        }
        writeAppMap(map);
    }

    /** Packages currently assigned to {@code driverId}. */
    public static Set<String> appsForDriver(String driverId) {
        Set<String> out = new LinkedHashSet<>();
        for (Map.Entry<String, String> e : readAppMap().entrySet()) {
            if (e.getValue().equals(driverId)) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    // ----------------------------------------------------------------- apply

    /**
     * Loads the assigned custom driver into the current virtual app process.
     * Called from {@code BActivityThread} before the application is created.
     * Never throws.
     */
    public static void apply(String packageName) {
        try {
            if (!isSupported()) {
                return;
            }
            String id = getDriverForApp(packageName);
            if (TextUtils.isEmpty(id)) {
                return;
            }
            DriverInfo info = getDriver(id);
            if (info == null) {
                Slog.w(TAG, "Assigned driver " + id + " no longer exists for " + packageName);
                return;
            }
            if (!info.libraryFile().isFile()) {
                Slog.w(TAG, "Driver library missing: " + info.libraryFile());
                return;
            }
            boolean ok = org.blackbox.vector.nativebridge.GpuBridge.loadDriver(
                    info.path.getAbsolutePath(), info.libraryName, Build.VERSION.SDK_INT);
            Slog.d(TAG, "GPU driver '" + info.name + "' for " + packageName + " loaded=" + ok);
        } catch (Throwable t) {
            Slog.w(TAG, "GPU driver apply failed, app continues on the system driver", t);
        }
    }
}
