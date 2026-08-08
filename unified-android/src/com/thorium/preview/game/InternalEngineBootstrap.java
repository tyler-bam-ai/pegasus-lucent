package com.thorium.preview.game;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

/** Registers only release-qualified cores that are present in the installed APK. */
public final class InternalEngineBootstrap {
    private static final String TAG = "LucentEngineBootstrap";

    private InternalEngineBootstrap() {}

    /**
     * Catalog verification hashes every packaged core — hundreds of megabytes
     * in a qualification APK — so it must never run on the main thread. One
     * named daemon thread does the work; catalog lookups (and therefore
     * launch requests) block until it finishes, so the route stays fail-closed
     * without ever serving an unverified entry.
     */
    public static void register(Context context) {
        final Context application = context.getApplicationContext();
        Thread verifier = new Thread(() -> {
            try {
                registerVerifiedEngines(application);
            } finally {
                // Always open the gates: a verification failure must surface
                // as an empty catalog, not a permanently blocked launch.
                InternalEngineCatalog.bootstrapComplete();
                Phase2QualificationCatalog.bootstrapComplete();
                NativeAdapterCatalog.bootstrapComplete();
            }
        }, "lucent-engine-verify");
        verifier.setDaemon(true);
        InternalEngineCatalog.expectBootstrapOn(verifier);
        Phase2QualificationCatalog.expectBootstrapOn(verifier);
        NativeAdapterCatalog.expectBootstrapOn(verifier);
        verifier.start();
    }

    private static void registerVerifiedEngines(Context context) {
        long started = SystemClock.elapsedRealtime();
        int verified = 0;
        for (final InternalEngineCatalog.Entry entry :
                InternalEngineCatalog.approvedEntries(context)) {
            EngineSessionRegistry.register(entry.id, (sessionContext, request) ->
                    "opengl".equals(entry.renderer)
                            ? new PpssppGlesEngineSession(sessionContext, entry)
                            : new LibretroEngineSession(sessionContext, entry));
            verified++;
        }
        for (final Phase2QualificationCatalog.Entry entry :
                Phase2QualificationCatalog.entries(context)) {
            // Phase 2 packages are explicit and autoSelect=false. Registering
            // an exact engine ID enables signed qualification intents only;
            // normal metadata keeps the release route fail-closed.
            EngineSessionRegistry.register(entry.id, (sessionContext, request) ->
                    "scummvm".equals(entry.id)
                            ? new LibretroEngineSession(sessionContext,
                                    LibretroEngineSpec.phaseTwo(entry))
                            : new PpssppGlesEngineSession(sessionContext, entry));
            verified++;
        }
        for (final NativeAdapterCatalog.Entry entry :
                NativeAdapterCatalog.entries(context)) {
            // Phase 3 native-adapter engines are present only when the adapter
            // .so is bundled and hash-verified, which a default/release APK
            // never does. In an opted-in qualification build (Switch/Eden) this
            // registers the in-process session factory; otherwise the catalog is
            // empty and the loop is a no-op.
            EngineSessionRegistry.register(entry.id, (sessionContext, request) ->
                    new NativeAdapterEngineSession(sessionContext, entry));
            verified++;
        }
        Log.i(TAG, "Engine verification complete engines=" + verified +
                " elapsedMs=" + (SystemClock.elapsedRealtime() - started));
    }
}
