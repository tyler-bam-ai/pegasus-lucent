package com.thorium.lucent.video;

/** Pure layout math for a vertically stacked two-screen emulator frame. */
public final class DualScreenLayout {
    public static final class Region {
        public final int left;
        public final int top;
        public final int right;
        public final int bottom;

        Region(int left, int top, int right, int bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        public int width() { return right - left; }
        public int height() { return bottom - top; }
    }

    private DualScreenLayout() {}

    public static Region top(int width, int height) {
        validate(width, height);
        return new Region(0, 0, width, height / 2);
    }

    public static Region bottom(int width, int height) {
        validate(width, height);
        return new Region(0, height / 2, width, height);
    }

    public static short pointerCoordinate(float normalized) {
        if (Float.isNaN(normalized)) normalized = 0f;
        float clamped = Math.max(0f, Math.min(1f, normalized));
        if (clamped <= 0f) return Short.MIN_VALUE;
        if (clamped >= 1f) return Short.MAX_VALUE;
        return (short)Math.round(-32768f + clamped * 65535f);
    }

    /** Maps a physical lower-display Y coordinate into the lower half of the
     * vertically stacked libretro composite that both dual-screen cores see. */
    public static short lowerScreenPointerY(float normalized) {
        if (Float.isNaN(normalized)) normalized = 0f;
        float clamped = Math.max(0f, Math.min(1f, normalized));
        return pointerCoordinate(0.5f + clamped * 0.5f);
    }

    private static void validate(int width, int height) {
        if (width < 1 || height < 2 || (height & 1) != 0)
            throw new IllegalArgumentException("an even two-screen frame is required");
    }
}
