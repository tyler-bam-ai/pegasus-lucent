package com.thorium.lucent.input;

import com.thorium.lucent.TestSupport;

import java.util.Map;

public final class LibretroJoypadLayoutTest {
    public static void main(String[] args) {
        TestSupport.equal(8, LibretroJoypadLayout.idFor("gamecube", CanonicalControl.SOUTH),
                "GameCube south is A");
        TestSupport.equal(0, LibretroJoypadLayout.idFor("gamecube", CanonicalControl.EAST),
                "GameCube east is B");
        TestSupport.equal(1, LibretroJoypadLayout.idFor("gamecube", CanonicalControl.WEST),
                "GameCube west is Y");
        TestSupport.equal(9, LibretroJoypadLayout.idFor("gamecube", CanonicalControl.NORTH),
                "GameCube north is X");
        TestSupport.equal(-1, LibretroJoypadLayout.idFor("gamecube", CanonicalControl.L1),
                "GameCube does not expose Dolphin's Triforce-test Retro L mapping");
        TestSupport.equal(12, LibretroJoypadLayout.idFor("gamecube", CanonicalControl.L2),
                "GameCube left trigger");
        TestSupport.equal(13, LibretroJoypadLayout.idFor("gamecube", CanonicalControl.R2),
                "GameCube right trigger");
        TestSupport.equal(11, LibretroJoypadLayout.idFor("gamecube", CanonicalControl.R1),
                "GameCube Z shoulder");
        TestSupport.equal(8, LibretroJoypadLayout.idFor("wii", CanonicalControl.SOUTH),
                "Wii primary south is A");
        TestSupport.equal(0, LibretroJoypadLayout.idFor("wii", CanonicalControl.EAST),
                "Wii secondary east is B");
        TestSupport.equal(8, LibretroJoypadLayout.idFor("snes", CanonicalControl.SOUTH),
                "RetroPad south is A, matching the device's bottom button label");
        TestSupport.equal(0, LibretroJoypadLayout.idFor("snes", CanonicalControl.EAST),
                "RetroPad east is B, matching the device's right button label");
        TestSupport.equal(9, LibretroJoypadLayout.idFor("snes", CanonicalControl.WEST),
                "RetroPad west is X, matching the device's left button label");
        TestSupport.equal(1, LibretroJoypadLayout.idFor("snes", CanonicalControl.NORTH),
                "RetroPad north is Y, matching the device's top button label");
        TestSupport.equal("A", SystemControlLayouts.forSystem("gamecube")
                        .get(CanonicalControl.SOUTH),
                "GameCube remap label matches runtime A mapping");
        TestSupport.equal("L", SystemControlLayouts.forSystem("gamecube")
                        .get(CanonicalControl.L2),
                "GameCube remap label exposes L on the trigger");
        TestSupport.equal("A", SystemControlLayouts.forSystem("wii")
                        .get(CanonicalControl.SOUTH),
                "Wii remap label matches runtime A mapping");
        wiiUsesTheDolphinWiimoteIds();
        nintendo64UsesTheMupenDefaultMap();
        dpadOnlySystemsAlsoAcceptTheLeftStick();
        analogStickSystemsKeepTheStickOffTheDpad();
        theRightStickIsNeverADigitalButton();
        System.out.println("LibretroJoypadLayoutTest passed");
    }

    /**
     * Dolphin's Wiimote descriptors: X(9) is "1" bare and Nunchuk "C" with an
     * extension, Y(1) is "2"/"Z", L(10)/R(11) are -/+ and L2(12) shakes the
     * Nunchuk. The runtime IDs used to be the mirror of the declared layout.
     */
    private static void wiiUsesTheDolphinWiimoteIds() {
        TestSupport.equal(9, LibretroJoypadLayout.idFor("wii", CanonicalControl.WEST),
                "Wii west is the 1 button / Nunchuk C");
        TestSupport.equal(1, LibretroJoypadLayout.idFor("wii", CanonicalControl.NORTH),
                "Wii north is the 2 button / Nunchuk Z");
        TestSupport.equal(10, LibretroJoypadLayout.idFor("wii", CanonicalControl.L1),
                "Wii L1 is the Nunchuk-era minus button");
        TestSupport.equal(11, LibretroJoypadLayout.idFor("wii", CanonicalControl.R1),
                "Wii R1 is the Nunchuk-era plus button");
        TestSupport.equal(13, LibretroJoypadLayout.idFor("wii", CanonicalControl.R2),
                "Wii R2 shakes the Wiimote");
        TestSupport.equal(LibretroJoypadLayout.WIIMOTE_NUNCHUK,
                LibretroJoypadLayout.portDeviceFor("wii"),
                "Wii asks for Dolphin's Wiimote+Nunchuk port device");
        TestSupport.equal(LibretroJoypadLayout.RETRO_DEVICE_JOYPAD,
                LibretroJoypadLayout.portDeviceFor("gamecube"),
                "every other system is a plain RetroPad");
        TestSupport.equal(-1, LibretroJoypadLayout.idFor("gamecube", CanonicalControl.SELECT),
                "GameCube does not expose Dolphin's Triforce-coin switch");
    }

    /**
     * mupen64plus-next with its shipped alt-map=False default: A=B(0), B=Y(1),
     * Z=L2(12), L=L(10), R=R(11), and R2(13) is the C-buttons modifier. The
     * generic RetroPad table put N64 B on RetroPad A(8), which the core reads
     * as a C-button rather than B.
     */
    private static void nintendo64UsesTheMupenDefaultMap() {
        TestSupport.equal(0, LibretroJoypadLayout.idFor("n64", CanonicalControl.SOUTH),
                "N64 south is A");
        TestSupport.equal(1, LibretroJoypadLayout.idFor("n64", CanonicalControl.EAST),
                "N64 east is B, not a C-button");
        TestSupport.equal(10, LibretroJoypadLayout.idFor("n64", CanonicalControl.L1),
                "N64 L1 is the L shoulder");
        TestSupport.equal(11, LibretroJoypadLayout.idFor("n64", CanonicalControl.R1),
                "N64 R1 is the R shoulder");
        TestSupport.equal(12, LibretroJoypadLayout.idFor("n64", CanonicalControl.L2),
                "N64 L2 is the Z trigger");
        TestSupport.equal(13, LibretroJoypadLayout.idFor("n64", CanonicalControl.R2),
                "N64 R2 is the C-buttons modifier");
        TestSupport.equal(-1, LibretroJoypadLayout.idFor("n64", CanonicalControl.SELECT),
                "the N64 pad has no Select");
        TestSupport.equal(4, LibretroJoypadLayout.idFor("n64", CanonicalControl.DPAD_UP),
                "the N64 D-pad stays the D-pad");
        // The four C directions are analog: mupen reads them from
        // RETRO_DEVICE_ANALOG index 1, which is the right stick.
        TestSupport.equal("C_UP", SystemControlLayouts.forSystem("n64")
                        .get(CanonicalControl.RIGHT_Y_NEGATIVE), "right stick up is C-up");
        TestSupport.equal("C_DOWN", SystemControlLayouts.forSystem("n64")
                        .get(CanonicalControl.RIGHT_Y_POSITIVE), "right stick down is C-down");
        TestSupport.equal("C_LEFT", SystemControlLayouts.forSystem("n64")
                        .get(CanonicalControl.RIGHT_X_NEGATIVE), "right stick left is C-left");
        TestSupport.equal("C_RIGHT", SystemControlLayouts.forSystem("n64")
                        .get(CanonicalControl.RIGHT_X_POSITIVE), "right stick right is C-right");
        TestSupport.equal("LEFT_X_NEGATIVE", SystemControlLayouts.forSystem("n64")
                        .get(CanonicalControl.LEFT_X_NEGATIVE),
                "the N64 analog stick is the left stick");
    }

    /** Every console that shipped without a stick still accepts one. */
    private static void dpadOnlySystemsAlsoAcceptTheLeftStick() {
        String[] systems = {
                "nes", "snes", "gb", "gbc", "gba", "genesis", "megadrive", "mastersystem",
                "gamegear", "sg1000", "segacd", "sega32x", "pcengine", "turbografx16",
                "neogeo", "neogeocd", "arcade", "atari2600", "atari5200", "atari7800",
                "atari800", "atarist", "amstradcpc", "c64", "msx", "zxspectrum",
                "wonderswan", "wonderswancolor", "ngp", "ngpc", "virtualboy", "dos",
                "scummvm", "psx", "nds", "colecovision", "intellivision", "odyssey2",
        };
        for (String system : systems) {
            TestSupport.equal(4, LibretroJoypadLayout.idFor(system,
                    CanonicalControl.LEFT_Y_NEGATIVE), system + " left stick up is D-pad up");
            TestSupport.equal(5, LibretroJoypadLayout.idFor(system,
                    CanonicalControl.LEFT_Y_POSITIVE), system + " left stick down is D-pad down");
            TestSupport.equal(6, LibretroJoypadLayout.idFor(system,
                    CanonicalControl.LEFT_X_NEGATIVE), system + " left stick left is D-pad left");
            TestSupport.equal(7, LibretroJoypadLayout.idFor(system,
                    CanonicalControl.LEFT_X_POSITIVE),
                    system + " left stick right is D-pad right");
            Map<CanonicalControl, String> layout = SystemControlLayouts.forSystem(system);
            if (layout.isEmpty()) continue;
            TestSupport.equal("UP", layout.get(CanonicalControl.LEFT_Y_NEGATIVE),
                    system + " declares the left stick as its D-pad");
            TestSupport.equal("RIGHT", layout.get(CanonicalControl.LEFT_X_POSITIVE),
                    system + " declares the left stick as its D-pad");
        }
    }

    /**
     * Consoles whose core reads RETRO_DEVICE_ANALOG index 0 must keep the stick
     * off the digital D-pad: those games read the two as separate controls.
     */
    private static void analogStickSystemsKeepTheStickOffTheDpad() {
        String[] systems = {"n64", "gamecube", "gc", "wii", "dreamcast", "naomi",
                "atomiswave", "psp"};
        for (String system : systems) {
            TestSupport.truth(LibretroJoypadLayout.hasAnalogStick(system),
                    system + " has its own analog stick");
            TestSupport.equal(-1, LibretroJoypadLayout.idFor(system,
                    CanonicalControl.LEFT_Y_NEGATIVE),
                    system + " does not press the D-pad from the stick");
            TestSupport.equal(-1, LibretroJoypadLayout.idFor(system,
                    CanonicalControl.LEFT_X_POSITIVE),
                    system + " does not press the D-pad from the stick");
            TestSupport.equal(4, LibretroJoypadLayout.idFor(system, CanonicalControl.DPAD_UP),
                    system + " keeps a working D-pad");
        }
        TestSupport.truth(!LibretroJoypadLayout.hasAnalogStick("snes"),
                "the SNES pad has no analog stick");
    }

    private static void theRightStickIsNeverADigitalButton() {
        for (String system : new String[] {"snes", "n64", "gamecube", "wii", "psx"})
            for (CanonicalControl control : new CanonicalControl[] {
                    CanonicalControl.RIGHT_X_NEGATIVE, CanonicalControl.RIGHT_X_POSITIVE,
                    CanonicalControl.RIGHT_Y_NEGATIVE, CanonicalControl.RIGHT_Y_POSITIVE})
                TestSupport.equal(-1, LibretroJoypadLayout.idFor(system, control),
                        system + " keeps the right stick analog for " + control);
    }
}
