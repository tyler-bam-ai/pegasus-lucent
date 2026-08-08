package com.thorium.lucent.input;

import com.thorium.lucent.TestSupport;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;

public final class InputRouterTest {
    public static void main(String[] ignored) throws Exception {
        detectsHandheldAndSuppressesTouch();
        enablesTouchWithoutACompletePad();
        appliesSystemThenGameRemaps();
        acceptsStandardHatAndTriggerAxes();
        acceptsEveryHatAndTriggerFallback();
        customRemapsDisableOnlyTheirAxisFallback();
        systemEditorExcludesGameOverrides();
        preservesActivePadAcrossDeviceRefresh();
        persistsRemapsAtomically();
        persistsApi21SafeUnicodeScopeKeys();
        exposesCuratedSystemLayouts();
        System.out.println("InputRouterTest passed");
    }

    private static void detectsHandheldAndSuppressesTouch() {
        GamepadDescriptor thor = completePad("AYN Thor Built-in Controller", 0, 0);
        DeviceCatalog catalog = DeviceCatalog.standard();
        TestSupport.equal("ayn-handheld", catalog.match(thor).id, "Thor profile matched");
        InputRouter router = new InputRouter(catalog, new InMemoryRemapStore(), true);
        router.updateDevices(Collections.singletonList(thor));
        TestSupport.truth(!router.shouldShowOnScreenControls(), "handheld never gets touch overlay");
    }

    private static void enablesTouchWithoutACompletePad() {
        InputRouter router = new InputRouter(DeviceCatalog.standard(),
                new InMemoryRemapStore(), false);
        router.updateDevices(Collections.<GamepadDescriptor>emptyList());
        TestSupport.truth(router.shouldShowOnScreenControls(), "phone with no pad gets touch controls");
        router.updateDevices(Collections.singletonList(completePad("Xbox Wireless Controller", 0x045e, 1)));
        TestSupport.truth(!router.shouldShowOnScreenControls(), "physical pad removes touch controls");
    }

    private static void appliesSystemThenGameRemaps() throws Exception {
        InMemoryRemapStore store = new InMemoryRemapStore();
        InputRouter router = new InputRouter(DeviceCatalog.standard(), store, false);
        GamepadDescriptor pad = completePad("Generic Controller", 10, 20);
        router.updateDevices(Collections.singletonList(pad));
        router.setGame("snes", "chrono-trigger");

        EnumMap<CanonicalControl, InputSignal> system = new EnumMap<>(CanonicalControl.class);
        system.put(CanonicalControl.SOUTH, InputSignal.key(AndroidInputCodes.BUTTON_X));
        store.save(new MappingScope("snes", ""), pad.persistentKey(), system);
        EnumMap<CanonicalControl, InputSignal> game = new EnumMap<>(CanonicalControl.class);
        game.put(CanonicalControl.SOUTH, InputSignal.key(AndroidInputCodes.BUTTON_Y));
        store.save(new MappingScope("snes", "chrono-trigger"), pad.persistentKey(), game);
        TestSupport.equal(CanonicalControl.SOUTH,
                router.resolve(pad, InputSignal.key(AndroidInputCodes.BUTTON_Y)),
                "game override wins");
        TestSupport.equal(null, router.resolve(pad, InputSignal.key(AndroidInputCodes.BUTTON_A)),
                "overridden physical input no longer resolves");
    }

    private static void persistsRemapsAtomically() throws Exception {
        File root = TestSupport.temporaryDirectory("remaps");
        try {
            FileRemapStore first = new FileRemapStore(new File(root, "controls.properties"));
            MappingScope scope = new MappingScope("n64", "mario-64");
            EnumMap<CanonicalControl, InputSignal> mapping = new EnumMap<>(CanonicalControl.class);
            mapping.put(CanonicalControl.L2, InputSignal.key(AndroidInputCodes.BUTTON_R2));
            first.save(scope, "pad:one", mapping);
            FileRemapStore reopened = new FileRemapStore(new File(root, "controls.properties"));
            TestSupport.equal(InputSignal.key(AndroidInputCodes.BUTTON_R2),
                    reopened.load(scope, "pad:one").get(CanonicalControl.L2),
                    "remap survives process restart");
            reopened.clear(scope, "pad:one");
            TestSupport.truth(reopened.load(scope, "pad:one").isEmpty(), "reset removes overrides");
        } finally { TestSupport.deleteTree(root); }
    }

    private static void acceptsStandardHatAndTriggerAxes() throws Exception {
        InputRouter router = new InputRouter(DeviceCatalog.standard(),
                new InMemoryRemapStore(), false);
        GamepadDescriptor pad = completePad("Generic Controller", 10, 20);
        router.updateDevices(Collections.singletonList(pad));
        router.setGame("nes", "example");
        TestSupport.equal(CanonicalControl.DPAD_LEFT,
                router.resolve(pad, InputSignal.axis(AndroidInputCodes.AXIS_HAT_X, -1)),
                "hat axis supplies D-pad");
        TestSupport.equal(CanonicalControl.L2,
                router.resolve(pad, InputSignal.axis(AndroidInputCodes.AXIS_LTRIGGER, 1)),
                "analog left trigger supplies L2");
        TestSupport.equal(CanonicalControl.R2,
                router.resolve(pad, InputSignal.axis(AndroidInputCodes.AXIS_GAS, 1)),
                "gas axis supplies R2");
    }

    private static void acceptsEveryHatAndTriggerFallback() throws Exception {
        InputRouter router = new InputRouter(DeviceCatalog.standard(),
                new InMemoryRemapStore(), false);
        GamepadDescriptor pad = completePad("Generic Controller", 10, 20);
        router.updateDevices(Collections.singletonList(pad));
        router.setGame("nes", "example");
        TestSupport.equal(CanonicalControl.DPAD_RIGHT,
                router.resolve(pad, InputSignal.axis(AndroidInputCodes.AXIS_HAT_X, 1)),
                "positive hat X supplies right");
        TestSupport.equal(CanonicalControl.DPAD_UP,
                router.resolve(pad, InputSignal.axis(AndroidInputCodes.AXIS_HAT_Y, -1)),
                "negative hat Y supplies up");
        TestSupport.equal(CanonicalControl.DPAD_DOWN,
                router.resolve(pad, InputSignal.axis(AndroidInputCodes.AXIS_HAT_Y, 1)),
                "positive hat Y supplies down");
        TestSupport.equal(CanonicalControl.L2,
                router.resolve(pad, InputSignal.axis(AndroidInputCodes.AXIS_BRAKE, 1)),
                "brake axis supplies L2");
        TestSupport.equal(CanonicalControl.R2,
                router.resolve(pad, InputSignal.axis(AndroidInputCodes.AXIS_RTRIGGER, 1)),
                "right trigger axis supplies R2");
        TestSupport.equal(null,
                router.resolve(pad, InputSignal.axis(AndroidInputCodes.AXIS_LTRIGGER, -1)),
                "negative trigger travel is not a press");
    }

    private static void customRemapsDisableOnlyTheirAxisFallback() throws Exception {
        InMemoryRemapStore store = new InMemoryRemapStore();
        InputRouter router = new InputRouter(DeviceCatalog.standard(), store, false);
        GamepadDescriptor pad = completePad("Generic Controller", 10, 20);
        router.updateDevices(Collections.singletonList(pad));
        router.setGame("nes", "custom");
        EnumMap<CanonicalControl, InputSignal> system = new EnumMap<>(CanonicalControl.class);
        system.put(CanonicalControl.L2, InputSignal.key(AndroidInputCodes.BUTTON_X));
        store.save(new MappingScope("nes", ""), pad.persistentKey(), system);
        TestSupport.equal(null,
                router.resolve(pad, InputSignal.axis(AndroidInputCodes.AXIS_LTRIGGER, 1)),
                "trigger fallback cannot bypass a custom L2 mapping");
        TestSupport.equal(CanonicalControl.R2,
                router.resolve(pad, InputSignal.axis(AndroidInputCodes.AXIS_RTRIGGER, 1)),
                "unmodified R2 keeps its standard fallback");
    }

    private static void systemEditorExcludesGameOverrides() throws Exception {
        InMemoryRemapStore store = new InMemoryRemapStore();
        InputRouter router = new InputRouter(DeviceCatalog.standard(), store, false);
        GamepadDescriptor pad = completePad("Generic Controller", 10, 20);
        router.updateDevices(Collections.singletonList(pad));
        router.setGame("snes", "one-game");
        EnumMap<CanonicalControl, InputSignal> game = new EnumMap<>(CanonicalControl.class);
        game.put(CanonicalControl.SOUTH, InputSignal.key(AndroidInputCodes.BUTTON_Y));
        store.save(new MappingScope("snes", "one-game"), pad.persistentKey(), game);
        TestSupport.equal(InputSignal.key(AndroidInputCodes.BUTTON_A),
                router.effectiveSystemMapping(pad).get(CanonicalControl.SOUTH),
                "system editor ignores current game override");
        TestSupport.equal(InputSignal.key(AndroidInputCodes.BUTTON_Y),
                router.effectiveMapping(pad).get(CanonicalControl.SOUTH),
                "gameplay still includes game override");
    }

    private static void preservesActivePadAcrossDeviceRefresh() {
        InputRouter router = new InputRouter(DeviceCatalog.standard(),
                new InMemoryRemapStore(), false);
        GamepadDescriptor first = completePad("First", 1, 1);
        GamepadDescriptor second = completePad("Second", 2, 2);
        router.updateDevices(Arrays.asList(first, second));
        TestSupport.equal(first.persistentKey(), router.activeDevice().persistentKey(),
                "first complete pad is initially active");
        GamepadDescriptor refreshedFirst = completePad("First", 1, 1);
        router.updateDevices(Arrays.asList(second, refreshedFirst));
        TestSupport.equal(refreshedFirst.persistentKey(), router.activeDevice().persistentKey(),
                "device enumeration order cannot unexpectedly switch the active pad");
    }

    private static void persistsApi21SafeUnicodeScopeKeys() throws Exception {
        File root = TestSupport.temporaryDirectory("remap-unicode");
        try {
            FileRemapStore store = new FileRemapStore(new File(root, "controls.properties"));
            MappingScope scope = new MappingScope("pc engine/cd", "Pokémon: 青");
            String device = "pad.with/slashes:and spaces 🎮";
            EnumMap<CanonicalControl, InputSignal> mapping = new EnumMap<>(CanonicalControl.class);
            mapping.put(CanonicalControl.START, InputSignal.key(AndroidInputCodes.BUTTON_START));
            store.save(scope, device, mapping);
            TestSupport.equal(InputSignal.key(AndroidInputCodes.BUTTON_START),
                    new FileRemapStore(new File(root, "controls.properties"))
                            .load(scope, device).get(CanonicalControl.START),
                    "URL-safe encoder persists arbitrary UTF-8 without java.util.Base64");
        } finally { TestSupport.deleteTree(root); }
    }

    private static void exposesCuratedSystemLayouts() {
        Map<CanonicalControl, String> n64 = SystemControlLayouts.forSystem("Nintendo 64");
        TestSupport.equal("Z", n64.get(CanonicalControl.L2), "N64 Z trigger mapping");
        TestSupport.equal("C_RIGHT", n64.get(CanonicalControl.RIGHT_X_POSITIVE),
                "N64 C-buttons use right stick");
        TestSupport.equal("CROSS", SystemControlLayouts.forSystem("psx")
                .get(CanonicalControl.SOUTH), "PlayStation geometry is canonical");
    }

    private static GamepadDescriptor completePad(String name, int vendor, int product) {
        HashSet<Integer> keys = new HashSet<>(Arrays.asList(
                AndroidInputCodes.DPAD_UP, AndroidInputCodes.DPAD_DOWN,
                AndroidInputCodes.DPAD_LEFT, AndroidInputCodes.DPAD_RIGHT,
                AndroidInputCodes.BUTTON_A, AndroidInputCodes.BUTTON_B,
                AndroidInputCodes.BUTTON_X, AndroidInputCodes.BUTTON_Y,
                AndroidInputCodes.BUTTON_START));
        HashSet<Integer> axes = new HashSet<>(Arrays.asList(
                AndroidInputCodes.AXIS_X, AndroidInputCodes.AXIS_Y,
                AndroidInputCodes.AXIS_Z, AndroidInputCodes.AXIS_RZ));
        return new GamepadDescriptor(1, vendor, product, "descriptor-" + name, name,
                true, true, true, keys, axes);
    }
}
