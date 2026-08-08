package com.thorium.lucent.input;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/** Curated zero-configuration Phase 1 layouts; values are engine-facing control names. */
public final class SystemControlLayouts {
    private static final Map<String, Map<CanonicalControl, String>> LAYOUTS = build();
    private SystemControlLayouts() {}

    public static Map<CanonicalControl, String> forSystem(String systemId) {
        Map<CanonicalControl, String> result = LAYOUTS.get(normalize(systemId));
        return result == null ? Collections.<CanonicalControl, String>emptyMap() : result;
    }

    private static Map<String, Map<CanonicalControl, String>> build() {
        Map<String, Map<CanonicalControl, String>> layouts = new HashMap<>();
        // Nintendo two-button systems match the Thor's Xbox-style physical
        // labels: the bottom (SOUTH=BUTTON_A) button is "A", the right
        // (EAST=BUTTON_B) is "B". (An earlier mapping used the Nintendo hardware
        // layout, swapping A/B against the device's printed labels — see
        // LibretroJoypadLayout.isNintendoFace for the matching runtime scope.)
        Map<CanonicalControl, String> nintendoTwoButton = base();
        nintendoTwoButton.put(CanonicalControl.SOUTH, "A");
        nintendoTwoButton.put(CanonicalControl.EAST, "B");
        nintendoTwoButton.put(CanonicalControl.START, "START");
        nintendoTwoButton.put(CanonicalControl.SELECT, "SELECT");
        add(layouts, nintendoTwoButton, "nes", "famicom", "gb", "gbc", "gameboy",
                "gameboycolor", "virtualboy");

        // Non-Nintendo two-button systems keep the generic RetroPad convention
        // (south=B), matching their libretro cores.
        Map<CanonicalControl, String> twoButton = base();
        twoButton.put(CanonicalControl.SOUTH, "B");
        twoButton.put(CanonicalControl.EAST, "A");
        twoButton.put(CanonicalControl.START, "START");
        twoButton.put(CanonicalControl.SELECT, "SELECT");
        add(layouts, twoButton, "atari2600", "atari5200", "atari7800", "colecovision",
                "intellivision", "odyssey2", "pcengine", "turbografx16", "wonderswan",
                "wonderswancolor", "neogeopocket", "neogeopocketcolor");

        Map<CanonicalControl, String> snes = base();
        snes.put(CanonicalControl.SOUTH, "A"); snes.put(CanonicalControl.EAST, "B");
        snes.put(CanonicalControl.WEST, "X"); snes.put(CanonicalControl.NORTH, "Y");
        snes.put(CanonicalControl.L1, "L"); snes.put(CanonicalControl.R1, "R");
        snes.put(CanonicalControl.START, "START"); snes.put(CanonicalControl.SELECT, "SELECT");
        add(layouts, snes, "snes", "superfamicom", "gba", "gameboyadvance");

        Map<CanonicalControl, String> sega = base();
        sega.put(CanonicalControl.WEST, "A"); sega.put(CanonicalControl.SOUTH, "B");
        sega.put(CanonicalControl.EAST, "C"); sega.put(CanonicalControl.NORTH, "X");
        sega.put(CanonicalControl.L1, "Y"); sega.put(CanonicalControl.R1, "Z");
        sega.put(CanonicalControl.START, "START"); sega.put(CanonicalControl.SELECT, "MODE");
        add(layouts, sega, "sg1000", "mastersystem", "gamegear", "genesis", "megadrive",
                "segacd", "megacd", "sega32x");

        Map<CanonicalControl, String> playstation = baseWithSticks();
        playstation.put(CanonicalControl.SOUTH, "CROSS"); playstation.put(CanonicalControl.EAST, "CIRCLE");
        playstation.put(CanonicalControl.WEST, "SQUARE"); playstation.put(CanonicalControl.NORTH, "TRIANGLE");
        playstation.put(CanonicalControl.L1, "L1"); playstation.put(CanonicalControl.R1, "R1");
        playstation.put(CanonicalControl.L2, "L2"); playstation.put(CanonicalControl.R2, "R2");
        playstation.put(CanonicalControl.L3, "L3"); playstation.put(CanonicalControl.R3, "R3");
        playstation.put(CanonicalControl.START, "START"); playstation.put(CanonicalControl.SELECT, "SELECT");
        add(layouts, playstation, "psx", "ps1", "playstation");

        Map<CanonicalControl, String> n64 = baseWithSticks();
        n64.put(CanonicalControl.SOUTH, "A"); n64.put(CanonicalControl.EAST, "B");
        n64.put(CanonicalControl.NORTH, "C_UP"); n64.put(CanonicalControl.WEST, "C_LEFT");
        n64.put(CanonicalControl.RIGHT_X_NEGATIVE, "C_LEFT");
        n64.put(CanonicalControl.RIGHT_X_POSITIVE, "C_RIGHT");
        n64.put(CanonicalControl.RIGHT_Y_NEGATIVE, "C_UP");
        n64.put(CanonicalControl.RIGHT_Y_POSITIVE, "C_DOWN");
        n64.put(CanonicalControl.L2, "Z"); n64.put(CanonicalControl.L1, "L");
        n64.put(CanonicalControl.R1, "R"); n64.put(CanonicalControl.START, "START");
        add(layouts, n64, "n64", "nintendo64");

        Map<CanonicalControl, String> gameCube = baseWithSticks();
        gameCube.put(CanonicalControl.SOUTH, "A");
        gameCube.put(CanonicalControl.EAST, "B");
        gameCube.put(CanonicalControl.WEST, "Y");
        gameCube.put(CanonicalControl.NORTH, "X");
        gameCube.put(CanonicalControl.L2, "L");
        gameCube.put(CanonicalControl.R2, "R");
        gameCube.put(CanonicalControl.R1, "Z");
        gameCube.put(CanonicalControl.START, "START");
        add(layouts, gameCube, "gc", "gamecube", "nintendogamecube");

        Map<CanonicalControl, String> wii = baseWithSticks();
        wii.put(CanonicalControl.SOUTH, "A");
        wii.put(CanonicalControl.EAST, "B");
        wii.put(CanonicalControl.WEST, "1");
        wii.put(CanonicalControl.NORTH, "2");
        wii.put(CanonicalControl.L1, "C");
        wii.put(CanonicalControl.R1, "Z");
        wii.put(CanonicalControl.START, "+");
        wii.put(CanonicalControl.SELECT, "-");
        add(layouts, wii, "wii", "nintendowii");

        Map<CanonicalControl, String> nds = new EnumMap<>(snes);
        nds.put(CanonicalControl.TOUCH_PRIMARY, "TOUCH");
        nds.put(CanonicalControl.L3, "LID");
        add(layouts, nds, "nds", "nintendods");

        Map<CanonicalControl, String> arcade = baseWithSticks();
        arcade.put(CanonicalControl.SOUTH, "BUTTON_1"); arcade.put(CanonicalControl.EAST, "BUTTON_2");
        arcade.put(CanonicalControl.WEST, "BUTTON_3"); arcade.put(CanonicalControl.NORTH, "BUTTON_4");
        arcade.put(CanonicalControl.L1, "BUTTON_5"); arcade.put(CanonicalControl.R1, "BUTTON_6");
        arcade.put(CanonicalControl.L2, "BUTTON_7"); arcade.put(CanonicalControl.R2, "BUTTON_8");
        arcade.put(CanonicalControl.START, "START_1"); arcade.put(CanonicalControl.SELECT, "COIN_1");
        add(layouts, arcade, "arcade", "mame", "neogeo", "neogeocd");

        Map<CanonicalControl, String> computer = baseWithSticks();
        computer.put(CanonicalControl.SOUTH, "FIRE_1"); computer.put(CanonicalControl.EAST, "FIRE_2");
        computer.put(CanonicalControl.START, "START"); computer.put(CanonicalControl.SELECT, "MENU");
        add(layouts, computer, "c64", "commodore64", "amstradcpc", "atarist", "atari8bit",
                "msx", "zx", "zxspectrum", "dos", "windows9x");

        Map<String, Map<CanonicalControl, String>> frozen = new HashMap<>();
        for (Map.Entry<String, Map<CanonicalControl, String>> entry : layouts.entrySet())
            frozen.put(entry.getKey(), Collections.unmodifiableMap(entry.getValue()));
        return Collections.unmodifiableMap(frozen);
    }

    private static Map<CanonicalControl, String> base() {
        EnumMap<CanonicalControl, String> map = new EnumMap<>(CanonicalControl.class);
        map.put(CanonicalControl.DPAD_UP, "UP"); map.put(CanonicalControl.DPAD_DOWN, "DOWN");
        map.put(CanonicalControl.DPAD_LEFT, "LEFT"); map.put(CanonicalControl.DPAD_RIGHT, "RIGHT");
        return map;
    }

    private static Map<CanonicalControl, String> baseWithSticks() {
        Map<CanonicalControl, String> map = base();
        map.put(CanonicalControl.LEFT_X_NEGATIVE, "LEFT_X_NEGATIVE");
        map.put(CanonicalControl.LEFT_X_POSITIVE, "LEFT_X_POSITIVE");
        map.put(CanonicalControl.LEFT_Y_NEGATIVE, "LEFT_Y_NEGATIVE");
        map.put(CanonicalControl.LEFT_Y_POSITIVE, "LEFT_Y_POSITIVE");
        map.put(CanonicalControl.RIGHT_X_NEGATIVE, "RIGHT_X_NEGATIVE");
        map.put(CanonicalControl.RIGHT_X_POSITIVE, "RIGHT_X_POSITIVE");
        map.put(CanonicalControl.RIGHT_Y_NEGATIVE, "RIGHT_Y_NEGATIVE");
        map.put(CanonicalControl.RIGHT_Y_POSITIVE, "RIGHT_Y_POSITIVE");
        return map;
    }

    private static void add(Map<String, Map<CanonicalControl, String>> target,
            Map<CanonicalControl, String> mapping, String... ids) {
        for (String id : ids) target.put(normalize(id), new EnumMap<>(mapping));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase().replaceAll("[^a-z0-9]", "");
    }
}
