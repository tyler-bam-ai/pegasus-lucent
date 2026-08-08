package com.thorium.lucent.timing;

/** Monotonic absolute-deadline pacer that does not accumulate sleep overshoot. */
public final class AbsoluteFramePacer {
    private static final long DEFAULT_MAX_LATE_PERIODS = 4L;

    private final long periodNanos;
    private final long maxLatenessNanos;
    private long deadlineNanos;
    private boolean started;

    public AbsoluteFramePacer(long periodNanos) {
        if (periodNanos < 1L) throw new IllegalArgumentException("period must be positive");
        this.periodNanos = periodNanos;
        this.maxLatenessNanos = saturatingMultiply(
                periodNanos, DEFAULT_MAX_LATE_PERIODS);
    }

    /** Starts/restarts the timeline at resume or after a deliberate pause. */
    public void reset(long nowNanos) {
        deadlineNanos = nowNanos;
        started = true;
    }

    /** Returns time remaining until the next absolute frame deadline. */
    public long delayAfterFrame(long nowNanos) {
        if (!started) reset(nowNanos);
        long candidate = saturatingAdd(deadlineNanos, periodNanos);
        if (nowNanos > candidate && nowNanos - candidate > maxLatenessNanos)
            candidate = saturatingAdd(nowNanos, periodNanos);
        deadlineNanos = candidate;
        return candidate > nowNanos ? candidate - nowNanos : 0L;
    }

    public long periodNanos() { return periodNanos; }

    private static long saturatingAdd(long first, long second) {
        if (second > 0L && first > Long.MAX_VALUE - second) return Long.MAX_VALUE;
        return first + second;
    }

    private static long saturatingMultiply(long value, long multiplier) {
        if (value > Long.MAX_VALUE / multiplier) return Long.MAX_VALUE;
        return value * multiplier;
    }
}
