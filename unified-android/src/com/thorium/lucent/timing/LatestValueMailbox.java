package com.thorium.lucent.timing;

/**
 * A bounded one-value handoff for real-time producers.
 *
 * Offering a newer value replaces an unconsumed older value. This keeps a
 * presentation consumer from ever back-pressuring the emulation clock.
 */
public final class LatestValueMailbox<T> {
    private T latest;
    private boolean closed;

    public synchronized boolean offer(T value) {
        if (value == null) throw new IllegalArgumentException("value required");
        if (closed) return false;
        latest = value;
        notifyAll();
        return true;
    }

    public synchronized boolean isEmpty() {
        return latest == null;
    }

    /** Returns null only after the mailbox has been closed. */
    public synchronized T take() throws InterruptedException {
        while (latest == null && !closed) wait();
        if (closed) return null;
        T value = latest;
        latest = null;
        return value;
    }

    public synchronized void close() {
        closed = true;
        latest = null;
        notifyAll();
    }
}
