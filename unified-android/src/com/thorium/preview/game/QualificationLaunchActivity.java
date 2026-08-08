package com.thorium.preview.game;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import java.io.File;
import java.io.IOException;

/**
 * Emulator-only entry point packaged exclusively when qualification cores are
 * explicitly enabled. Release APKs never declare this activity.
 */
public final class QualificationLaunchActivity extends Activity {
    public static final String ACTION_QUALIFY =
            "com.thorium.preview.QUALIFY_INTERNAL_GAME";

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        launch(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        launch(intent);
    }

    private void launch(Intent source) {
        if (source == null || !ACTION_QUALIFY.equals(source.getAction())) {
            finish();
            return;
        }
        String path = clean(source.getStringExtra("path"));
        if (!isSafeFixture(path) || clean(source.getStringExtra("engine_id")).isEmpty() ||
                clean(source.getStringExtra("system_id")).isEmpty() ||
                clean(source.getStringExtra("game_id")).isEmpty()) {
            finish();
            return;
        }

        String engine = clean(source.getStringExtra("engine_id"));
        Intent internal = new Intent(this, InternalGameLaunchActivity.class)
                .setAction(InternalGameLaunchActivity.ACTION_LAUNCH)
                .putExtra("path", path)
                .putExtra("engine_id", engine)
                .putExtra("system_id", clean(source.getStringExtra("system_id")))
                .putExtra("game_id", clean(source.getStringExtra("game_id")))
                .putExtra("title", clean(source.getStringExtra("title")));
        try {
            startActivity(internal);
        } finally {
            finish();
        }
    }

    private static boolean isSafeFixture(String path) {
        if (path.isEmpty()) return false;
        try {
            File file = new File(path).getCanonicalFile();
            String canonical = file.getPath();
            return file.isFile() && (canonical.startsWith("/storage/emulated/") ||
                    canonical.startsWith("/storage/self/primary/"));
        } catch (IOException ignored) {
            return false;
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
