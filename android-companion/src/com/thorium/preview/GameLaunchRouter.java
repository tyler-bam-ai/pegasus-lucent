package com.thorium.preview;

import android.content.Context;

import com.thorium.preview.game.InWindowGameHost;
import com.thorium.preview.game.InternalEngineCatalog;
import com.thorium.preview.game.NativeAdapterCatalog;
import com.thorium.preview.game.Phase2QualificationCatalog;
import com.thorium.lucent.metadata.EngineSystemIdResolver;
import com.thorium.lucent.metadata.MetadataGameLaunchCommand;

/**
 * Builds Lucent's only supported game-launch route.
 *
 * A game may launch only when an approved in-process engine is packaged and
 * available for its system. Pegasus accepts only {@code am start} metadata on
 * Android, so Lucent intercepts its exact internal action inside the inherited
 * launchAmCommand method before startActivity. No Activity lifecycle
 * transition, router Activity, emulator Activity, second task, or external
 * package participates in emulation.
 */
public final class GameLaunchRouter {
    private GameLaunchRouter() {}

    public static boolean supportsSystem(Context context, String system) {
        return !engineIdForSystem(context, system).isEmpty();
    }

    public static String metadataCommand(Context context, String system) {
        String canonical = EngineSystemIdResolver.canonical(system);
        String engineId = engineIdForSystem(context, canonical);
        return MetadataGameLaunchCommand.build(
                canonical, engineId, InWindowGameHost.ACTION_LAUNCH);
    }

    private static String engineIdForSystem(Context context, String system) {
        String normalized = EngineSystemIdResolver.canonical(system);
        InternalEngineCatalog.Entry release =
                InternalEngineCatalog.availableForSystem(context, normalized);
        if (release != null) return release.id;
        String phase2 =
                Phase2QualificationCatalog.libraryEngineIdForSystem(context, normalized);
        if (!phase2.isEmpty()) return phase2;
        // Phase 3 native-adapter engines (Wii U/Cemu) resolve INTERNAL only when
        // the adapter .so is bundled and hash-verified. Until then the catalog is
        // empty, so a system like Wii U falls through to its external Cemu route.
        return NativeAdapterCatalog.libraryEngineIdForSystem(context, normalized);
    }
}
