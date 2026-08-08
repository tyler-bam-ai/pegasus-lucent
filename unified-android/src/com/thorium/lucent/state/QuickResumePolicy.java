package com.thorium.lucent.state;

/** Memory and exit rules shared by large in-process emulator states. */
public final class QuickResumePolicy {
    public static final int MAX_IN_MEMORY_RECOVERY_COPY_BYTES = 64 * 1024 * 1024;

    private QuickResumePolicy() {}

    public static boolean shouldCopyPreviousToRecovery(int newStateBytes) {
        if (newStateBytes < 0) throw new IllegalArgumentException("state size cannot be negative");
        return newStateBytes <= MAX_IN_MEMORY_RECOVERY_COPY_BYTES;
    }

    public static boolean mayCompleteStop(boolean exitToLucent,
                                          boolean commitSucceeded) {
        return !exitToLucent || commitSucceeded;
    }
}
