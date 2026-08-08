package com.thorium.lucent.input;

/** Maps Lucent's physical geometry to the libretro RetroPad IDs expected by a system. */
public final class LibretroJoypadLayout {
    private LibretroJoypadLayout() {}

    public static int idFor(String systemId, CanonicalControl control) {
        if (control == null) return -1;
        String system = normalize(systemId);
        boolean gameCube = "gamecube".equals(system) || "gc".equals(system);
        boolean dolphin = gameCube || "wii".equals(system);
        // Nintendo-family systems label their face buttons to match the Thor's
        // physical Xbox-style positions: the bottom (SOUTH) button is A and the
        // right (EAST) is B, with left=X and top=Y. The generic libretro
        // RetroPad convention instead puts B at the south position, which made
        // A/B and X/Y feel swapped on NES/SNES/GB/GBA. Sega/PlayStation/N64 keep
        // the standard convention (their confirm button belongs at the south
        // position), so the swap is scoped to the Nintendo face group.
        boolean nintendoFace = isNintendoFace(system);
        boolean southIsA = dolphin || nintendoFace;
        switch (control) {
            case SOUTH: return southIsA ? 8 : 0; // A : B
            case EAST: return southIsA ? 0 : 8; // B : A
            /* GameCube/Wii keep their console-native kidney layout (west=Y,
             * north=X); the Nintendo handheld/SNES face uses west=X, north=Y. */
            case WEST: return nintendoFace ? 9 : 1; // X : Y
            case NORTH: return nintendoFace ? 1 : 9; // Y : X
            case SELECT: return 2;
            case START: return 3;
            case DPAD_UP: case LEFT_Y_NEGATIVE: return 4;
            case DPAD_DOWN: case LEFT_Y_POSITIVE: return 5;
            case DPAD_LEFT: case LEFT_X_NEGATIVE: return 6;
            case DPAD_RIGHT: case LEFT_X_POSITIVE: return 7;
            case L1: return gameCube ? -1 : 10;
            case R1: return 11;
            case L2: return 12;
            case R2: return 13;
            case L3: return 14;
            case R3: return 15;
            default: return -1;
        }
    }

    private static boolean isNintendoFace(String system) {
        switch (system) {
            case "nes": case "famicom":
            case "snes": case "superfamicom":
            case "gb": case "gbc": case "gba":
            case "gameboy": case "gameboycolor": case "gameboyadvance":
            case "virtualboy":
            case "nds": case "ds":
                return true;
            default:
                return false;
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase().replaceAll("[^a-z0-9]", "");
    }
}
