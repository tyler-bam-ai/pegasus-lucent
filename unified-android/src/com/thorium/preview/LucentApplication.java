package com.thorium.preview;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Bundle;

import com.thorium.preview.game.InternalEngineBootstrap;

import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicBoolean;

/** Starts Lucent's frontend, in-window engines, and companion features. */
public final class LucentApplication
        extends org.qtproject.qt5.android.bindings.QtApplication {
    // The inherited Qt/JNI class name is retained for binary compatibility;
    // it is Lucent's sole foreground MainActivity in this package.
    private static final String LUCENT_ACTIVITY =
            "org.pegasus_frontend.android.MainActivity";
    private static volatile WeakReference<Activity> liveMainActivity =
            new WeakReference<>(null);
    private final AtomicBoolean completingFirstSetup = new AtomicBoolean(false);

    @Override
    public void onCreate() {
        super.onCreate();
        // Engine registration hash-verifies every bundled core; register()
        // runs that on its own background thread and catalog consumers block
        // until it completes, so onCreate stays off the ANR path.
        InternalEngineBootstrap.register(this);
        // Theme extraction can touch thousands of external-storage files and
        // must never delay Application startup. PreviewService installs or
        // updates it on its worker thread; the first-setup lifecycle callback
        // restarts Lucent once that initial install is complete.
        startLucentService();
        registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(Activity activity) {
                routeVolumeKeysToMusicStream(activity);
                rememberMainActivity(activity);
                completeFirstSetupIfNeeded(activity);
            }
            @Override public void onActivityCreated(Activity activity, Bundle state) {
                routeVolumeKeysToMusicStream(activity);
                rememberMainActivity(activity);
            }
            @Override public void onActivityStarted(Activity activity) {
                rememberMainActivity(activity);
            }
            @Override public void onActivityPaused(Activity activity) {}
            @Override public void onActivityStopped(Activity activity) {}
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
            @Override public void onActivityDestroyed(Activity activity) {
                Activity remembered = liveMainActivity.get();
                if (remembered == activity) liveMainActivity = new WeakReference<>(null);
            }
        });
    }

    /**
     * Points the hardware volume keys at the one stream Lucent actually plays
     * on.
     *
     * Everything audible in this process is STREAM_MUSIC: both engine
     * AudioTracks (LibretroEngineSession/PpssppGlesEngineSession
     * createAudioTrack) and the preview MediaPlayer. Without this call Android
     * gives an Activity whose window has no active stream the *default* target
     * — ring/notification volume on most devices — so the keys move a stream
     * nothing plays on while STREAM_MUSIC keeps whatever level it last had.
     * That is the reported "turn it down and it only goes part-way, turn it up
     * and it stays low", and the silent-menus case is simply STREAM_MUSIC
     * having been left at zero.
     *
     * Every Activity in the process is covered here rather than in each
     * onCreate: the callbacks are registered in Application.onCreate, before
     * any Activity of this process exists, so MainActivity (whose Java class is
     * Pegasus's and is only reachable through smali patching), PreviewActivity,
     * BrowserActivity, and RomLaunchActivity all get it with no extra patch.
     * It is applied again on resume so a re-created or restored Activity can
     * never come back with the platform default.
     */
    private static void routeVolumeKeysToMusicStream(Activity activity) {
        if (activity != null) activity.setVolumeControlStream(AudioManager.STREAM_MUSIC);
    }

    /** Returns Lucent's existing Qt activity without creating or resuming one. */
    public static Activity currentMainActivity() {
        Activity activity = liveMainActivity.get();
        return activity != null && !activity.isFinishing() && !activity.isDestroyed()
                ? activity : null;
    }

    private static void rememberMainActivity(Activity activity) {
        if (activity != null && LUCENT_ACTIVITY.equals(activity.getClass().getName()))
            liveMainActivity = new WeakReference<>(activity);
    }

    private void startLucentService() {
        Intent service = new Intent(this, PreviewService.class);
        // Every Lucent process is created to show its foreground MainActivity.
        // A normal same-process start lets PreviewService promote itself in
        // onCreate without creating a second FGS deadline that can outlive a
        // process-death Quick Resume restoration.
        startService(service);
    }

    private void completeFirstSetupIfNeeded(Activity activity) {
        if (!LUCENT_ACTIVITY.equals(activity.getClass().getName()) ||
                !ThemeInstaller.hasStorageAccess(this) ||
                !ThemeInstaller.installedVersion().isEmpty() ||
                !completingFirstSetup.compareAndSet(false, true)) return;

        Thread worker = new Thread(() -> {
            ThemeInstaller.installBundledNow(this);
            startLucentService();
            activity.runOnUiThread(() -> {
                Intent restart = new Intent(Intent.ACTION_MAIN)
                        .setClassName(getPackageName(), LUCENT_ACTIVITY)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                                Intent.FLAG_ACTIVITY_CLEAR_TASK);
                activity.startActivity(restart);
                activity.finishAffinity();
            });
        }, "lucent-first-setup");
        worker.setDaemon(true);
        worker.start();
    }
}
