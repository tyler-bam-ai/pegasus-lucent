package com.thorium.lucent.video;

import com.thorium.lucent.TestSupport;

public final class DualScreenLayoutTest {
    public static void main(String[] args) {
        DualScreenLayout.Region top = DualScreenLayout.top(256, 384);
        DualScreenLayout.Region bottom = DualScreenLayout.bottom(256, 384);
        TestSupport.equal(0, top.top, "top screen begins at frame top");
        TestSupport.equal(192, top.height(), "top screen height");
        TestSupport.equal(192, bottom.top, "bottom screen begins at midpoint");
        TestSupport.equal(192, bottom.height(), "bottom screen height");
        TestSupport.equal(Short.MIN_VALUE, DualScreenLayout.pointerCoordinate(0f),
                "pointer minimum");
        TestSupport.equal((short)0, DualScreenLayout.pointerCoordinate(0.5f),
                "pointer midpoint");
        TestSupport.equal(Short.MAX_VALUE, DualScreenLayout.pointerCoordinate(1f),
                "pointer maximum");
        TestSupport.equal((short)0, DualScreenLayout.lowerScreenPointerY(0f),
                "lower display top maps to composite midpoint");
        TestSupport.equal(Short.MAX_VALUE,
                DualScreenLayout.lowerScreenPointerY(1f),
                "lower display bottom maps to composite bottom");
    }
}
