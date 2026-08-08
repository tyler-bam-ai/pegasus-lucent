package com.thorium.lucent.input;

import com.thorium.lucent.TestSupport;

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
        System.out.println("LibretroJoypadLayoutTest passed");
    }
}
