package com.thorium.lucent.input;

/** Maps Lucent's physical geometry to the libretro RetroPad IDs expected by a system. */
public final class LibretroJoypadLayout {
    private LibretroJoypadLayout() {}

    public static int idFor(String systemId, CanonicalControl control) {
        if (control == null) return -1;
        String system = normalize(systemId);
        boolean gameCube = "gamecube".equals(system) || "gc".equals(system);
        boolean dolphin = gameCube || "wii".equals(system);
        switch (control) {
            /* Nintendo's GameCube/Wii primary face button is A. Dolphin maps
             * that to RetroPad A, unlike the SNES-shaped libretro convention
             * where the physical south position is RetroPad B. */
            case SOUTH: return dolphin ? 8 : 0; // A : B
            case EAST: return dolphin ? 0 : 8; // B : A
            case WEST: return 1; // Y (GameCube) / 1 (Wii Remote)
            case NORTH: return 9; // X (GameCube) / 2 (Wii Remote)
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

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase().replaceAll("[^a-z0-9]", "");
    }
}
