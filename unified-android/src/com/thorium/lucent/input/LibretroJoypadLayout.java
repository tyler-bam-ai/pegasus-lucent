package com.thorium.lucent.input;

/** Maps Lucent's physical geometry to the libretro RetroPad IDs expected by a system. */
public final class LibretroJoypadLayout {
    private LibretroJoypadLayout() {}

    public static int idFor(String systemId, CanonicalControl control) {
        if (control == null) return -1;
        String system = normalize(systemId);
        // The left stick doubles as the D-pad on every console that never had
        // an analog stick, so a stick-only player can play them. On consoles
        // whose core reads RETRO_DEVICE_ANALOG index 0 as a separate control
        // (N64, GameCube, Wii, Dreamcast, PSP) the stick must NOT also press
        // the digital D-pad: those games treat the two as different inputs.
        boolean analogStick = hasAnalogStick(system);
        switch (control) {
            case LEFT_Y_NEGATIVE: return analogStick ? -1 : 4;
            case LEFT_Y_POSITIVE: return analogStick ? -1 : 5;
            case LEFT_X_NEGATIVE: return analogStick ? -1 : 6;
            case LEFT_X_POSITIVE: return analogStick ? -1 : 7;
            case DPAD_UP: return 4;
            case DPAD_DOWN: return 5;
            case DPAD_LEFT: return 6;
            case DPAD_RIGHT: return 7;
            case START: return 3;
            // The right stick is always an analog control (N64 C-buttons, the
            // GameCube C-stick, Wii IR pointing). It never becomes a button.
            case RIGHT_X_NEGATIVE: case RIGHT_X_POSITIVE:
            case RIGHT_Y_NEGATIVE: case RIGHT_Y_POSITIVE:
                return -1;
            default: break;
        }
        if (isNintendo64(system)) return nintendo64(control);
        if (isGameCube(system)) return gameCube(control);
        if (isWii(system)) return wii(control);
        return retroPad(control, isNintendoFace(system));
    }

    /**
     * Generic RetroPad. Nintendo-family systems label their face buttons to
     * match the Thor's physical Xbox-style positions: the bottom (SOUTH) button
     * is A and the right (EAST) is B, with left=X and top=Y. The generic
     * libretro convention instead puts B at the south position, which made A/B
     * and X/Y feel swapped on NES/SNES/GB/GBA. Sega/PlayStation keep the
     * standard convention (their confirm button belongs at the south position),
     * so the swap is scoped to the Nintendo face group.
     */
    private static int retroPad(CanonicalControl control, boolean nintendoFace) {
        switch (control) {
            case SOUTH: return nintendoFace ? 8 : 0; // A : B
            case EAST: return nintendoFace ? 0 : 8; // B : A
            case WEST: return nintendoFace ? 9 : 1; // X : Y
            case NORTH: return nintendoFace ? 1 : 9; // Y : X
            case SELECT: return 2;
            case L1: return 10;
            case R1: return 11;
            case L2: return 12;
            case R2: return 13;
            case L3: return 14;
            case R3: return 15;
            default: return -1;
        }
    }

    /**
     * mupen64plus-next with its shipped {@code mupen64plus-next-alt-map=False}
     * default. That layout is: A=B(0), B=Y(1), C1=A(8), C4=X(9), Z=L2(12),
     * L=L(10), R=R(11), and R2(13) is the "C Buttons Mode" modifier that turns
     * the face buttons into C-buttons. The four C directions are read from
     * RETRO_DEVICE_ANALOG index 1, so the right stick is the primary C control
     * and the face buttons are the held-R2 fallback.
     */
    private static int nintendo64(CanonicalControl control) {
        switch (control) {
            case SOUTH: return 0; // A
            case EAST: return 1; // B
            case WEST: return 8; // C-Right while R2 is held (core option C1)
            case NORTH: return 9; // C-Up while R2 is held (core option C4)
            case L1: return 10; // L shoulder
            case R1: return 11; // R shoulder
            case L2: return 12; // Z trigger
            case R2: return 13; // C Buttons Mode modifier
            // The N64 pad has no Select. RetroPad ID 2 is unread by this core
            // in its default map, and Lucent's Select is the Stop gesture.
            case SELECT: return -1;
            default: return -1;
        }
    }

    /**
     * Dolphin's {@code descGC}: A=A(8), B=B(0), X=X(9), Y=Y(1), L=L2(12),
     * R=R2(13), Z=R(11). RetroPad L(10) is Dolphin's "Triforce - Test" and
     * SELECT(2) its "Triforce - Coin"; neither belongs on a normal GameCube
     * pad, so both stay unmapped. The C-stick is RETRO_DEVICE_ANALOG index 1.
     */
    private static int gameCube(CanonicalControl control) {
        switch (control) {
            case SOUTH: return 8; // A
            case EAST: return 0; // B
            case WEST: return 1; // Y
            case NORTH: return 9; // X
            case L2: return 12; // L trigger
            case R2: return 13; // R trigger
            case R1: return 11; // Z
            case L1: return -1; // Triforce test switch, not a GameCube control
            case SELECT: return -1; // Triforce coin switch
            case L3: return 14;
            case R3: return 15;
            default: return -1;
        }
    }

    /**
     * Dolphin's Wii bindings, chosen so one table is correct under both Wii
     * device types. A=A(8) and B=B(0) are identical for a bare Wiimote and for
     * Wiimote+Nunchuk. X(9)/Y(1) are the Wiimote's 1/2 without an extension and
     * the Nunchuk's C/Z with one. L(10)/R(11) and L2(12) are unread without an
     * extension and become -/+ and "shake Nunchuk" with one. R2(13) shakes the
     * Wiimote and R3(15) is Home under both. IR pointing is the right stick
     * (core option {@code dolphin_ir_mode}) and the Nunchuk stick is the left.
     */
    private static int wii(CanonicalControl control) {
        switch (control) {
            case SOUTH: return 8; // A
            case EAST: return 0; // B
            case WEST: return 9; // 1 / Nunchuk C
            case NORTH: return 1; // 2 / Nunchuk Z
            case L1: return 10; // - (Wiimote+Nunchuk only)
            case R1: return 11; // + (Wiimote+Nunchuk only)
            case L2: return 12; // shake Nunchuk (Wiimote+Nunchuk only)
            case R2: return 13; // shake Wiimote
            case SELECT: return 2; // - / 2
            case L3: return 14;
            case R3: return 15; // Home
            default: return -1;
        }
    }

    /**
     * RETRO_DEVICE type Lucent wants on port 0 for this system. Everything runs
     * as a plain RetroPad except Wii, which needs Dolphin's Wiimote+Nunchuk
     * device so extension-only titles (Super Mario Galaxy 2) accept input at
     * all. Selecting it requires {@code retro_set_controller_port_device}; the
     * in-process host currently hard-codes RETRO_DEVICE_JOYPAD.
     */
    public static int portDeviceFor(String systemId) {
        return isWii(normalize(systemId)) ? WIIMOTE_NUNCHUK : RETRO_DEVICE_JOYPAD;
    }

    public static final int RETRO_DEVICE_JOYPAD = 1;
    /** Dolphin's {@code RETRO_DEVICE_WIIMOTE_NC}: {@code (3 << 8) | JOYPAD}. */
    public static final int WIIMOTE_NUNCHUK = (3 << 8) | RETRO_DEVICE_JOYPAD;

    /** True when the core reads RETRO_DEVICE_ANALOG index 0 as its own control. */
    public static boolean hasAnalogStick(String systemId) {
        switch (normalize(systemId)) {
            case "n64": case "nintendo64":
            case "gc": case "gamecube": case "nintendogamecube":
            case "wii": case "nintendowii":
            case "dreamcast": case "naomi": case "atomiswave":
            case "psp":
                return true;
            default:
                return false;
        }
    }

    private static boolean isNintendo64(String system) {
        return "n64".equals(system) || "nintendo64".equals(system);
    }

    private static boolean isGameCube(String system) {
        return "gc".equals(system) || "gamecube".equals(system) ||
                "nintendogamecube".equals(system);
    }

    private static boolean isWii(String system) {
        return "wii".equals(system) || "nintendowii".equals(system);
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
