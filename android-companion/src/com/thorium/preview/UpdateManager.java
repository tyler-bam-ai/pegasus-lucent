package com.thorium.preview;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** GitHub-backed theme and APK updater with a small JSON status surface. */
final class UpdateManager {
    private static final String TAG = "LucentUpdater";
    private static final String MANIFEST_URL =
            "https://raw.githubusercontent.com/wildonrio/pegasus-lucent/main/release-manifest.json";
    private static final long MAX_THEME = 512L * 1024L * 1024L;
    private static final long MAX_APK = 768L * 1024L * 1024L;

    private final Context context;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Object statusLock = new Object();
    private JSONObject status = status("idle", 0, "Updater ready", false, false);
    private volatile JSONObject latest;

    UpdateManager(Context context) {
        this.context = context.getApplicationContext();
        // A process can be killed while a network request is in flight. Never
        // resurrect that persisted progress state as though work were still
        // running: there is no worker left to complete it. Terminal states are
        // safe to restore; interrupted work becomes an explicit retry state.
        android.content.SharedPreferences saved =
                this.context.getSharedPreferences("updates-ui", 0);
        String previous = saved.getString("state", "idle");
        boolean interrupted = "checking".equals(previous) ||
                "theme".equals(previous) || "software".equals(previous);
        if (interrupted) {
            setStatus("interrupted", 1,
                    "Previous update check was interrupted — tap Check for Updates to retry",
                    false, false);
        } else if (!"idle".equals(previous)) {
            setStatus(previous, 1,
                    saved.getString("message", "Updater ready"),
                    saved.getBoolean("appAvailable", false),
                    saved.getBoolean("installReady", false));
        }
    }

    void checkAsync(boolean userInitiated) {
        if (!running.compareAndSet(false, true)) return;
        setStatus("checking", 0.05, "Checking GitHub for updates…", false, false);
        Thread worker = new Thread(() -> {
            try {
                check(userInitiated);
            } catch (Exception error) {
                Log.e(TAG, "Update check failed", error);
                setStatus("error", 1, "Update check failed safely", false, false);
            } finally {
                running.set(false);
            }
        }, "lucent-update-check");
        worker.setDaemon(true);
        worker.start();
    }

    String statusJson() {
        synchronized (statusLock) { return status.toString(); }
    }

    void installDownloadedApk() {
        try {
            File apk = updateFile();
            if (!apk.isFile()) {
                setStatus("complete", 1,
                        "No downloaded update is available — run Check for Updates first",
                        false, false);
                return;
            }
            if (Build.VERSION.SDK_INT >= 26 &&
                    !context.getPackageManager().canRequestPackageInstalls()) {
                Intent permission = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + context.getPackageName()))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(permission);
                setStatus("permission", 1,
                        "Allow installs from this app, then choose Install Update again",
                        true, true);
                return;
            }
            Uri uri = Uri.parse("content://" + context.getPackageName() +
                    ".updates/" + UpdateFileProvider.FILE_NAME);
            Intent install = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.startActivity(install);
        } catch (Exception error) {
            Log.e(TAG, "Unable to open installer", error);
            setStatus("error", 1, "Android could not open the downloaded update", true, true);
        }
    }

    private void check(boolean userInitiated) throws Exception {
        JSONObject manifest = new JSONObject(new String(fetch(MANIFEST_URL, 2L * 1024L * 1024L),
                StandardCharsets.UTF_8));
        latest = manifest;
        String remoteTheme = manifest.optString("themeVersion", "");
        boolean themeNew = isVersionNewer(remoteTheme, ThemeInstaller.installedVersion());
        int currentCode = currentVersionCode();
        int remoteCode = manifest.optInt("companionVersionCode", currentCode);
        boolean appNew = remoteCode > currentCode;

        if (themeNew) {
            setStatus("theme", 0.18, "Downloading the latest Lucent theme…",
                    appNew, false);
            File theme = new File(context.getCacheDir(), "pegasus-lucent-theme.zip");
            download(manifest.optString("themeZipUrl"), theme, MAX_THEME,
                    manifest.optString("themeSha256"));
            setStatus("theme", 0.62, "Installing the theme update…", appNew, false);
            ThemeInstaller.installZip(theme, remoteTheme);
            theme.delete();
        }

        if (appNew) {
            setStatus("software", 0.68, "Downloading the latest app update…", true, false);
            download(manifest.optString("companionApkUrl"), updateFile(), MAX_APK,
                    manifest.optString("companionSha256"));
            setStatus("available", 1,
                    "Software update ready — choose Install or Later in Lucent", true, true);
        } else {
            String message = themeNew ? "Theme updated successfully" :
                    (userInitiated ? "Lucent is up to date" : "Updates checked");
            setStatus("complete", 1, message, false, false);
        }
    }

    private int currentVersionCode() {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? (int) info.getLongVersionCode() : info.versionCode;
        } catch (Exception ignored) { return 0; }
    }

    private static boolean isVersionNewer(String candidate, String installed) {
        if (candidate == null || candidate.trim().isEmpty()) return false;
        if (installed == null || installed.trim().isEmpty()) return true;
        String[] left = candidate.trim().split("[^0-9]+");
        String[] right = installed.trim().split("[^0-9]+");
        int count = Math.max(left.length, right.length);
        for (int index = 0; index < count; index++) {
            int a = versionPart(left, index);
            int b = versionPart(right, index);
            if (a != b) return a > b;
        }
        return false;
    }

    private static int versionPart(String[] parts, int index) {
        if (index >= parts.length || parts[index].isEmpty()) return 0;
        try { return Integer.parseInt(parts[index]); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private File updateFile() {
        File folder = context.getExternalFilesDir("updates");
        if (folder == null) folder = new File(context.getCacheDir(), "updates");
        if (folder != null) folder.mkdirs();
        return new File(folder, UpdateFileProvider.FILE_NAME);
    }

    private static byte[] fetch(String url, long maximum) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "Lucent-Updater/1.0");
        try (InputStream input = new BufferedInputStream(connection.getInputStream());
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[32 * 1024];
            int count;
            long total = 0;
            while ((count = input.read(buffer)) >= 0) {
                total += count;
                if (total > maximum) throw new java.io.IOException("Update response is too large");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        } finally { connection.disconnect(); }
    }

    private static void download(String url, File target, long maximum, String expectedSha)
            throws Exception {
        if (url == null || url.isEmpty() || !url.startsWith("https://"))
            throw new java.io.IOException("Missing secure update URL");
        File parent = target.getParentFile();
        if (parent != null) parent.mkdirs();
        File partial = new File(target.getAbsolutePath() + ".partial");
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(60000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "Lucent-Updater/1.0");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new BufferedInputStream(connection.getInputStream());
             BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(partial))) {
            byte[] buffer = new byte[128 * 1024];
            int count;
            long total = 0;
            while ((count = input.read(buffer)) >= 0) {
                total += count;
                if (total > maximum) throw new java.io.IOException("Update is too large");
                digest.update(buffer, 0, count);
                output.write(buffer, 0, count);
            }
        } finally { connection.disconnect(); }
        String actual = hex(digest.digest());
        if (expectedSha != null && !expectedSha.isEmpty() &&
                !actual.equalsIgnoreCase(expectedSha)) {
            partial.delete();
            throw new java.io.IOException("Update checksum does not match");
        }
        if (target.isFile()) target.delete();
        if (!partial.renameTo(target))
            throw new java.io.IOException("Unable to commit update download");
    }

    private void setStatus(String state, double progress, String message,
                           boolean appAvailable, boolean installReady) {
        synchronized (statusLock) {
            status = status(state, progress, message, appAvailable, installReady);
        }
        // The dashboard and foreground service are separate Android
        // components. Persist the small terminal/progress state so the visible
        // checklist never depends on a localhost timing race to turn green.
        context.getSharedPreferences("updates-ui", 0).edit()
                .putString("state", state)
                .putString("message", message)
                .putLong("updatedAt", System.currentTimeMillis())
                .putBoolean("appAvailable", appAvailable)
                .putBoolean("installReady", installReady)
                .apply();
    }

    private static JSONObject status(String state, double progress, String message,
                                     boolean appAvailable, boolean installReady) {
        JSONObject value = new JSONObject();
        try {
            value.put("state", state);
            value.put("progress", Math.max(0, Math.min(1, progress)));
            value.put("message", message);
            value.put("appAvailable", appAvailable);
            value.put("installReady", installReady);
            value.put("running", "checking".equals(state) || "theme".equals(state) ||
                    "software".equals(state));
            value.put("themeVersion", ThemeInstaller.installedVersion());
        } catch (Exception ignored) {}
        return value;
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) out.append(String.format(Locale.US, "%02x", value & 0xff));
        return out.toString();
    }
}
