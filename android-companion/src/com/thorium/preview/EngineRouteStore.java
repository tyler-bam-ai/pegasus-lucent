package com.thorium.preview;

import android.content.Context;
import android.content.SharedPreferences;

import com.thorium.lucent.metadata.EngineSystemIdResolver;

import java.util.Locale;

/**
 * Per-canonical-system launch-route preference and the single source of truth
 * for the metadata {@code launch:} command Lucent emits for a system.
 *
 * Product rule (supersedes the old "internal only" policy): internal emulation
 * is the DEFAULT for every system that has a bundled, release-qualified
 * in-process engine. External emulators are a per-system user choice and are
 * never selected automatically for a system that can run internally. A system
 * with no internal engine resolves to EXTERNAL so its games can still launch
 * through a standalone app. Choosing External installs nothing on its own; the
 * external route only opens the emulator's install source when a matching ROM
 * is present and the emulator is missing.
 */
public final class EngineRouteStore {
    public static final String INTERNAL = "internal";
    public static final String EXTERNAL = "external";

    private static final String PREFS = "engine-routes";
    private static final String ROUTE_PREFIX = "route.";
    private static final String EMULATOR_PREFIX = "emulator.";

    private EngineRouteStore() {}

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** True when a bundled, release-qualified internal engine supports the system. */
    public static boolean hasInternalEngine(Context context, String system) {
        return GameLaunchRouter.supportsSystem(context,
                EngineSystemIdResolver.canonical(system));
    }

    /** True when the user has recorded an explicit route for the system. */
    public static boolean isExplicit(Context context, String system) {
        String stored = storedRoute(context, system);
        return INTERNAL.equals(stored) || EXTERNAL.equals(stored);
    }

    private static String storedRoute(Context context, String system) {
        return prefs(context).getString(
                ROUTE_PREFIX + EngineSystemIdResolver.canonical(system), "");
    }

    /**
     * Resolves the effective route: the explicit user choice when set (an
     * INTERNAL choice degrades to EXTERNAL if no internal engine is available),
     * otherwise INTERNAL when an internal engine exists, otherwise EXTERNAL.
     */
    public static String resolve(Context context, String system) {
        String canonical = EngineSystemIdResolver.canonical(system);
        String stored = storedRoute(context, canonical);
        if (EXTERNAL.equals(stored)) return EXTERNAL;
        boolean internalAvailable = hasInternalEngine(context, canonical);
        if (INTERNAL.equals(stored)) return internalAvailable ? INTERNAL : EXTERNAL;
        return internalAvailable ? INTERNAL : EXTERNAL;
    }

    /** The user's chosen external emulator id for a system, or "" for default order. */
    public static String chosenEmulator(Context context, String system) {
        return prefs(context).getString(
                EMULATOR_PREFIX + EngineSystemIdResolver.canonical(system), "");
    }

    /**
     * Records an explicit route. INTERNAL is rejected when no internal engine
     * supports the system; an external emulator id is only stored when it is a
     * real catalog entry for that system. Returns false when the request is
     * invalid so callers never persist an unlaunchable preference.
     */
    public static boolean setRoute(Context context, String system, String route,
                                   String emulatorId) {
        String canonical = EngineSystemIdResolver.canonical(system);
        String normalized = route == null ? "" :
                route.trim().toLowerCase(Locale.US);
        if (!INTERNAL.equals(normalized) && !EXTERNAL.equals(normalized)) return false;
        if (INTERNAL.equals(normalized) && !hasInternalEngine(context, canonical)) return false;
        if (EXTERNAL.equals(normalized) && !EmulatorCatalog.hasExternalOption(canonical))
            return false;

        SharedPreferences.Editor editor = prefs(context).edit();
        editor.putString(ROUTE_PREFIX + canonical, normalized);
        if (EXTERNAL.equals(normalized)) {
            String trimmed = emulatorId == null ? "" : emulatorId.trim();
            if (!trimmed.isEmpty()) {
                if (EmulatorCatalog.optionForId(canonical, trimmed) == null) return false;
                editor.putString(EMULATOR_PREFIX + canonical, trimmed);
            } else {
                editor.remove(EMULATOR_PREFIX + canonical);
            }
        } else { // INTERNAL clears any stale external emulator choice
            editor.remove(EMULATOR_PREFIX + canonical);
        }
        editor.apply();
        return true;
    }

    /** Clears an explicit route, returning the system to its default. */
    public static void clearRoute(Context context, String system) {
        String canonical = EngineSystemIdResolver.canonical(system);
        prefs(context).edit()
                .remove(ROUTE_PREFIX + canonical)
                .remove(EMULATOR_PREFIX + canonical)
                .apply();
    }

    /**
     * The metadata {@code launch:} command for a system, honouring the resolved
     * route. Both the importer's fresh metadata and the on-disk metadata
     * migration route through here so a route change re-emits the correct
     * command everywhere.
     *
     * INTERNAL reuses the stable in-process runtime router (Pegasus runs the
     * {@code am start} that MainActivity intercepts). EXTERNAL emits a normal
     * {@code am start} recipe that Pegasus executes to open the chosen
     * standalone emulator directly into gameplay. Returns "" only when neither
     * an internal engine nor a supported external option exists (fail closed).
     */
    public static String launchCommand(Context context, String system) {
        String canonical = EngineSystemIdResolver.canonical(system);
        if (INTERNAL.equals(resolve(context, canonical)) &&
                GameLaunchRouter.supportsSystem(context, canonical)) {
            return GameLaunchRouter.metadataCommand(context, canonical);
        }
        return EmulatorCatalog.externalLaunchCommand(
                context, canonical, chosenEmulator(context, canonical));
    }
}
