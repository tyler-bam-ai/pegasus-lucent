package com.thorium.preview;

import android.content.Context;
import android.content.pm.PackageManager;
import android.Manifest;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Installs the bundled or downloaded Pegasus Lucent theme without touching ROMs. */
final class ThemeInstaller {
    private static final String TAG = "LucentThemeInstaller";
    private static final String ASSET_ZIP = "pegasus-lucent-theme.zip";
    private static final String ASSET_VERSION = "pegasus-lucent-version.txt";
    private static final File PEGASUS = new File(Environment.getExternalStorageDirectory(),
            "pegasus-frontend");
    private static final File PEGASUS_CONFIG = new File(Environment.getExternalStorageDirectory(),
            "Android/data/org.pegasus_frontend.android/files/pegasus-frontend");
    private static final File LUCENT_CONFIG = new File(Environment.getExternalStorageDirectory(),
            "Android/data/com.thorium.preview/files/pegasus-frontend");
    private static final File THEME = new File(PEGASUS, "themes/lucent");
    private static final File VERSION = new File(THEME, ".lucent-version");
    private static final File CONFIG_MIGRATION = new File(LUCENT_CONFIG,
            ".lucent-config-migrated");

    private ThemeInstaller() {}

    static boolean hasStorageAccess(Context context) {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return Build.VERSION.SDK_INT < 23 || context.checkSelfPermission(
                Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    static void installBundledIfNeeded(Context context) {
        installBundledIfNeeded(context, null);
    }

    static void installBundledIfNeeded(Context context, Runnable completion) {
        installBundled(context, false, completion);
    }

    static void forceInstallBundled(Context context, Runnable completion) {
        installBundled(context, true, completion);
    }

    /** Installs Lucent before the embedded Pegasus activity draws its first frame. */
    static void installBundledNow(Context context) {
        try {
            installBundledBlocking(context, false);
        } catch (java.io.FileNotFoundException missingOptionalAsset) {
            // Development builds may intentionally omit the large theme bundle.
        } catch (Exception error) {
            Log.e(TAG, "Unable to install bundled theme before startup", error);
        }
    }

    private static void installBundled(Context context, boolean force, Runnable completion) {
        Thread worker = new Thread(() -> {
            try {
                installBundledBlocking(context, force);
            } catch (java.io.FileNotFoundException missingOptionalAsset) {
                // Development builds may intentionally omit the large theme bundle.
            } catch (Exception error) {
                Log.e(TAG, "Unable to install bundled theme", error);
            } finally {
                if (completion != null) completion.run();
            }
        }, "lucent-theme-install");
        worker.setDaemon(true);
        worker.start();
    }

    private static void installBundledBlocking(Context context, boolean force) throws Exception {
        if (!hasStorageAccess(context)) return;
        migratePegasusConfig();
        String bundled = readAssetText(context, ASSET_VERSION).trim();
        boolean firstLucentInstall = !VERSION.isFile();
        if (!bundled.isEmpty() && (force || !bundled.equals(readText(VERSION).trim()))) {
            try (InputStream input = context.getAssets().open(ASSET_ZIP)) {
                installZip(input, bundled);
            }
        }
        // Lucent is the default, not a lock-in. Select it on first setup, but
        // preserve any other Pegasus theme the user chooses afterward. The
        // ROM-only provider rule remains enforced on every launch.
        updatePegasusSettings(firstLucentInstall);
    }

    /**
     * Carries the existing Pegasus library, play history and theme memory into
     * the unified package once. ROMs and media are referenced in place and are
     * never copied, moved or deleted by this migration.
     */
    private static synchronized void migratePegasusConfig() throws Exception {
        if (CONFIG_MIGRATION.isFile() || !PEGASUS_CONFIG.isDirectory()) return;
        copyConfigTree(PEGASUS_CONFIG, LUCENT_CONFIG);
        writeText(CONFIG_MIGRATION, "migrated from org.pegasus_frontend.android\n");
    }

    static synchronized void installZip(File zip, String version) throws Exception {
        try (InputStream input = new FileInputStream(zip)) {
            installZip(input, version);
        }
    }

    static synchronized void installZip(InputStream source, String version) throws Exception {
        PEGASUS.mkdirs();
        File themes = new File(PEGASUS, "themes");
        themes.mkdirs();
        File staging = new File(themes, ".lucent-installing");
        deleteTree(staging);
        if (!staging.mkdirs() && !staging.isDirectory())
            throw new java.io.IOException("Unable to create the theme staging directory");
        String stagingPath = staging.getCanonicalPath() + File.separator;
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(source))) {
            ZipEntry entry;
            byte[] buffer = new byte[128 * 1024];
            while ((entry = zip.getNextEntry()) != null) {
                String relative = entry.getName();
                if (relative.startsWith("lucent/")) relative = relative.substring(7);
                if (relative.isEmpty()) continue;
                File output = new File(staging, relative);
                String canonical = output.getCanonicalPath();
                if (!canonical.startsWith(stagingPath))
                    throw new java.io.IOException("Unsafe theme archive path");
                if (entry.isDirectory()) {
                    output.mkdirs();
                    continue;
                }
                File parent = output.getParentFile();
                if (parent != null) parent.mkdirs();
                try (BufferedOutputStream out = new BufferedOutputStream(
                        new FileOutputStream(output))) {
                    int count;
                    long total = 0;
                    while ((count = zip.read(buffer)) >= 0) {
                        total += count;
                        if (total > 512L * 1024L * 1024L)
                            throw new java.io.IOException("Theme entry is unexpectedly large");
                        out.write(buffer, 0, count);
                    }
                }
            }
        }
        if (!new File(staging, "theme.qml").isFile() ||
                !new File(staging, "theme.cfg").isFile()) {
            deleteTree(staging);
            throw new java.io.IOException("Theme archive is incomplete");
        }
        deleteTree(THEME);
        if (!staging.renameTo(THEME)) {
            copyTree(staging, THEME);
            deleteTree(staging);
        }
        writeText(VERSION, version == null ? "unknown" : version.trim());
    }

    static String installedVersion() {
        return readText(VERSION).trim();
    }

    private static void updatePegasusSettings(boolean selectLucent) throws Exception {
        // Modern Pegasus Android builds keep runtime settings under their
        // app-specific external directory. Disable the Android Apps provider:
        // Lucent is deliberately a ROM library, never an application launcher.
        updateSettings(new File(PEGASUS_CONFIG, "settings.txt"), selectLucent);
        updateSettings(new File(LUCENT_CONFIG, "settings.txt"), selectLucent);
        updateSettings(new File(PEGASUS, "settings.txt"), selectLucent);
    }

    private static void updateSettings(File settings, boolean selectLucent) throws Exception {
        List<String> lines = new ArrayList<>();
        boolean themeReplaced = false;
        boolean appsReplaced = false;
        if (settings.isFile()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(settings))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("general.theme:")) {
                        if (selectLucent && !themeReplaced) {
                            lines.add("general.theme: " + THEME.getAbsolutePath() + "/");
                            themeReplaced = true;
                        } else if (!selectLucent) lines.add(line);
                    } else if (line.startsWith("providers.androidapps.enabled:")) {
                        if (!appsReplaced) {
                            lines.add("providers.androidapps.enabled: false");
                            appsReplaced = true;
                        }
                    } else lines.add(line);
                }
            }
        }
        if (selectLucent && !themeReplaced)
            lines.add(0, "general.theme: " + THEME.getAbsolutePath() + "/");
        if (!appsReplaced) lines.add("providers.androidapps.enabled: false");
        File parent = settings.getParentFile();
        if (parent != null) parent.mkdirs();
        File temporary = new File(settings.getParentFile(), ".settings.lucent.tmp");
        try (FileWriter writer = new FileWriter(temporary, false)) {
            for (String line : lines) writer.write(line + "\n");
        }
        if (settings.isFile() && !settings.delete())
            throw new java.io.IOException("Unable to replace Lucent settings");
        if (!temporary.renameTo(settings))
            throw new java.io.IOException("Unable to commit Lucent settings");
    }

    private static String readAssetText(Context context, String name) throws Exception {
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                context.getAssets().open(name), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) out.append(line).append('\n');
        }
        return out.toString();
    }

    private static String readText(File file) {
        if (!file.isFile()) return "";
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) out.append(line).append('\n');
        } catch (Exception ignored) {}
        return out.toString();
    }

    private static void writeText(File file, String text) throws Exception {
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        try (FileWriter writer = new FileWriter(file, false)) {
            writer.write(text == null ? "" : text);
        }
    }

    private static void copyTree(File source, File target) throws Exception {
        if (source.isDirectory()) {
            target.mkdirs();
            File[] children = source.listFiles();
            if (children != null)
                for (File child : children) copyTree(child, new File(target, child.getName()));
            return;
        }
        File parent = target.getParentFile();
        if (parent != null) parent.mkdirs();
        try (InputStream in = new BufferedInputStream(new FileInputStream(source));
             BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(target))) {
            byte[] buffer = new byte[128 * 1024];
            int count;
            while ((count = in.read(buffer)) >= 0) out.write(buffer, 0, count);
        }
    }

    private static void copyConfigTree(File source, File target) throws Exception {
        if (source.isDirectory()) {
            target.mkdirs();
            File[] children = source.listFiles();
            if (children != null) {
                for (File child : children) {
                    if ("lastrun.log".equals(child.getName())) continue;
                    copyConfigTree(child, new File(target, child.getName()));
                }
            }
            return;
        }
        copyTree(source, target);
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteTree(child);
        }
        file.delete();
    }
}
