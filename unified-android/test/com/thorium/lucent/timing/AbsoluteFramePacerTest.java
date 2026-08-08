package com.thorium.lucent.timing;

import com.thorium.lucent.TestSupport;

public final class AbsoluteFramePacerTest {
    public static void main(String[] ignored) {
        carriesSleepOvershootIntoTheNextDeadline();
        resetsAfterLargeExternalStalls();
        rejectsInvalidPeriods();
        System.out.println("AbsoluteFramePacerTest passed");
    }

    private static void carriesSleepOvershootIntoTheNextDeadline() {
        AbsoluteFramePacer pacer = new AbsoluteFramePacer(10L);
        pacer.reset(0L);
        TestSupport.equal(7L, pacer.delayAfterFrame(3L), "first absolute delay");
        // The scheduler woke two nanoseconds late and work took three. A
        // frame-relative sleep would incorrectly return 7 again; the absolute
        // deadline carries that overshoot and returns only 5.
        TestSupport.equal(5L, pacer.delayAfterFrame(15L),
                "sleep overshoot must not accumulate into a lower frame rate");
        TestSupport.equal(7L, pacer.delayAfterFrame(23L),
                "timeline remains anchored after the scheduler catches up");
        TestSupport.equal(9L, pacer.delayAfterFrame(31L),
                "minor lateness is carried into the next absolute deadline");
    }

    private static void resetsAfterLargeExternalStalls() {
        AbsoluteFramePacer pacer = new AbsoluteFramePacer(10L);
        pacer.reset(0L);
        TestSupport.equal(7L, pacer.delayAfterFrame(3L), "initial delay");
        TestSupport.equal(10L, pacer.delayAfterFrame(100L),
                "large screenshot/background stall starts a fresh deadline");
    }

    private static void rejectsInvalidPeriods() {
        boolean rejected = false;
        try { new AbsoluteFramePacer(0L); }
        catch (IllegalArgumentException expected) { rejected = true; }
        TestSupport.truth(rejected, "zero frame period rejected");
    }
}
