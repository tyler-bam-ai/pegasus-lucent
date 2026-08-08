package com.thorium.preview;

import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.view.Surface;

/** Routes one in-process emulator session to Lucent's physical lower display. */
public final class SecondaryGameplaySurfaceRouter {
    public static final String ACTION_SECONDARY_GAMEPLAY =
            "com.thorium.preview.SECONDARY_GAMEPLAY";
    static final String EXTRA_GENERATION = "secondary_gameplay_generation";
    static final String EXTRA_SYSTEM = "secondary_gameplay_system";
    private static final String TAG = "LucentSecondary";

    public interface Listener {
        void onSecondarySurfaceAvailable(Surface surface, int width, int height);
        void onSecondarySurfaceDestroyed();
        void onSecondaryTouch(float normalizedX, float normalizedY, boolean pressed);
    }

    private static Listener listener;
    private static long generation;

    private SecondaryGameplaySurfaceRouter() {}

    public static synchronized boolean request(
            Context context, String systemId, Listener next) {
        int secondaryDisplay = context == null ? -1 : BootReceiver.secondaryDisplayId(context);
        boolean overlays = context != null && Settings.canDrawOverlays(context);
        if (context == null || next == null || !supports(systemId) || secondaryDisplay < 0) {
            Log.i(TAG, "request rejected returned=false system=" + systemId +
                    " supports=" + supports(systemId) +
                    " secondaryDisplayId=" + secondaryDisplay +
                    " canDrawOverlays=" + overlays);
            return false;
        }
        Log.i(TAG, "request accepted system=" + systemId +
                " secondaryDisplayId=" + secondaryDisplay +
                " path=" + (overlays ? "startActivity(canDrawOverlays)" : "PendingIntent") +
                " (SDK=" + Build.VERSION.SDK_INT + ")");
        listener = next;
        long requestedGeneration = ++generation;
        Intent activity = new Intent(context, PreviewActivity.class)
                .setAction(ACTION_SECONDARY_GAMEPLAY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .putExtra(EXTRA_GENERATION, requestedGeneration)
                .putExtra(EXTRA_SYSTEM, systemId);
        ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(BootReceiver.secondaryDisplayId(context));
        if (Build.VERSION.SDK_INT >= 34) {
            options.setPendingIntentCreatorBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
            options.setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
        }
        try {
            if (Settings.canDrawOverlays(context)) {
                context.startActivity(activity, options.toBundle());
            } else {
                PendingIntent pending = PendingIntent.getActivity(
                        context, 43822, activity,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE,
                        options.toBundle());
                pending.send(context, 0, null, null, null, null, options.toBundle());
            }
            Log.i(TAG, "request launched secondary gameplay activity returned=true system=" +
                    systemId + " generation=" + requestedGeneration +
                    " displayId=" + secondaryDisplay);
            return true;
        } catch (PendingIntent.CanceledException | RuntimeException failure) {
            if (listener == next && generation == requestedGeneration) listener = null;
            Log.e(TAG, "Unable to open the secondary gameplay surface", failure);
            return false;
        }
    }

    public static synchronized void release(Context context, Listener owner) {
        if (owner == null || listener != owner) return;
        listener = null;
        ++generation;
        if (context != null) context.sendBroadcast(new Intent(PreviewService.ACTION_BLANK)
                .setPackage(context.getPackageName()));
    }

    static synchronized boolean isCurrent(long candidate) {
        return listener != null && generation == candidate;
    }

    static synchronized void surfaceAvailable(
            long candidate, Surface surface, int width, int height) {
        boolean matched = listener != null && generation == candidate;
        Log.i(TAG, "surfaceAvailable candidate=" + candidate + " generation=" + generation +
                " match=" + matched + " listener=" + (listener != null) +
                " valid=" + (surface != null && surface.isValid()) +
                " size=" + width + "x" + height);
        if (matched)
            listener.onSecondarySurfaceAvailable(surface, width, height);
    }

    /** Synchronous: returns only after the listener detached from the dying
     * Surface (bounded), so callers may remove the view right after. */
    static synchronized void surfaceDestroyed(long candidate) {
        boolean matched = listener != null && generation == candidate;
        Log.i(TAG, "surfaceDestroyed candidate=" + candidate + " generation=" + generation +
                " match=" + matched + " listener=" + (listener != null));
        if (matched)
            listener.onSecondarySurfaceDestroyed();
    }

    static synchronized void touch(
            long candidate, float normalizedX, float normalizedY, boolean pressed) {
        if (listener != null && generation == candidate)
            listener.onSecondaryTouch(clamp(normalizedX), clamp(normalizedY), pressed);
    }

    static boolean supports(String systemId) {
        String value = systemId == null ? "" : systemId.trim().toLowerCase();
        return "nds".equals(value) || "ds".equals(value) ||
                "3ds".equals(value) || "n3ds".equals(value) ||
                "wiiu".equals(value) || "wii-u".equals(value);
    }

    private static float clamp(float value) {
        if (Float.isNaN(value)) return 0f;
        return Math.max(0f, Math.min(1f, value));
    }
}
