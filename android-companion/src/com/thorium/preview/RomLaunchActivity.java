package com.thorium.preview;

import android.app.Activity;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import java.io.File;
import java.io.IOException;

/**
 * Lucent's own content-URI trampoline for scoped-storage external emulators.
 *
 * Pegasus executes an {@code am start} recipe that targets this Activity for any
 * emulator whose delivery is {@code content-uri} (see EmulatorCatalog). It
 * converts the ROM filesystem path from Pegasus into a one-time, read-only
 * content URI, grants that exact URI to the target emulator, and forwards the
 * launch directly into the emulator's gameplay Activity.
 *
 * The Activity is declared {@code android:exported="false"}: Android then only
 * lets components in Lucent's own uid start it, which is exactly the boundary
 * required — only Pegasus's in-process {@code am start} reaches it, never
 * another app. It is a trampoline, not an emulator, and carries no launcher or
 * home category.
 */
public final class RomLaunchActivity extends Activity {
    public static final String ACTION_LAUNCH_FILE = "com.thorium.preview.LAUNCH_FILE";
    private static final String AUTHORITY = "com.thorium.preview.roms";

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        launch(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        launch(intent);
    }

    private void launch(Intent request) {
        if (request == null) { finish(); return; }
        String path = request.getStringExtra("path");
        String targetPackage = request.getStringExtra("target_package");
        String targetActivity = request.getStringExtra("target_activity");
        String targetAction = request.getStringExtra("target_action");
        if (path == null || targetPackage == null || targetActivity == null ||
                targetPackage.isEmpty() || targetActivity.isEmpty()) {
            finish();
            return;
        }

        // Confine the source to the same storage roots RomFileProvider serves,
        // so a malformed recipe can never hand out an arbitrary system file.
        File rom;
        try {
            rom = new File(path).getCanonicalFile();
        } catch (IOException error) {
            finish();
            return;
        }
        String canonical = rom.getPath();
        if (!(canonical.startsWith("/storage/") || canonical.startsWith("/mnt/media_rw/")) ||
                !rom.isFile()) {
            finish();
            return;
        }

        Uri uri = new Uri.Builder().scheme("content").authority(AUTHORITY)
                .appendPath("rom").appendPath(rom.getName())
                .appendQueryParameter("path", canonical).build();
        int grant = Intent.FLAG_GRANT_READ_URI_PERMISSION;
        grantUriPermission(targetPackage, uri, grant);
        if (targetActivity.startsWith(".")) targetActivity = targetPackage + targetActivity;
        if (targetAction == null || targetAction.isEmpty()) targetAction = Intent.ACTION_VIEW;

        Intent launch = new Intent(targetAction)
                .setComponent(new ComponentName(targetPackage, targetActivity))
                .setDataAndType(uri, "application/octet-stream")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                        Intent.FLAG_ACTIVITY_CLEAR_TOP | grant);
        launch.setClipData(ClipData.newRawUri("rom", uri));
        try {
            startActivity(launch);
        } catch (RuntimeException error) {
            // A missing target simply ends the trampoline; the external route's
            // install flow surfaces the missing emulator elsewhere.
        } finally {
            finish();
        }
    }
}
