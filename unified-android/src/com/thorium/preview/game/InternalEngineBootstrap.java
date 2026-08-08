package com.thorium.preview.game;

import android.content.Context;

/** Registers only release-qualified cores that are present in the installed APK. */
public final class InternalEngineBootstrap {
    private InternalEngineBootstrap() {}

    public static void register(Context context) {
        for (final InternalEngineCatalog.Entry entry :
                InternalEngineCatalog.approvedEntries(context)) {
            EngineSessionRegistry.register(entry.id, (sessionContext, request) ->
                    "opengl".equals(entry.renderer)
                            ? new PpssppGlesEngineSession(sessionContext, entry)
                            : new LibretroEngineSession(sessionContext, entry));
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
        }
    }
}
