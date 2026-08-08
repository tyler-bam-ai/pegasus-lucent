package com.thorium.preview;

import android.view.Surface;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.Callable;

/**
 * Qualification-only render-thread owner for the experimental GLES bridge.
 * It has no production catalog or EngineSession route.
 */
public final class ExperimentalGlesRenderLoop implements Closeable {
    public interface Listener {
        void onReady();
        void onFramePresented();
        void onContextLost();
        void onError(Throwable failure);
    }

    interface Host extends Closeable {
        void attach(Surface surface);
        void recreate(Surface surface);
        boolean runAndPresent();
        void detach();
        void attachSecondary(Surface surface);
        void detachSecondary();
        void pause();
        void resume();
        void setJoypadButton(int port, int button, boolean pressed);
        void setAnalogAxis(int port, int index, int id, float value);
        void setPointer(int port, short x, short y, boolean pressed);
        short[] drainAudio(int maxFrames);
        ExperimentalGlesLibretroHost.AvInfo avInfo();
        boolean stateReady();
        byte[] serialize();
        void unserialize(byte[] state);
        byte[] readSaveRam();
        void writeSaveRam(byte[] saveRam);
    }

    interface HostFactory {
        Host createAndLoad() throws Exception;
    }

    private static final long DEFAULT_FRAME_DELAY_NANOS = 1_000_000_000L / 60L;
    // UI-thread destroy callbacks release their Surface the moment they
    // return; the native detach must finish first, but may never stall the
    // UI thread longer than this bound.
    private static final long SURFACE_DETACH_WAIT_MILLIS = 250L;

    private final ScheduledExecutorService executor;
    private final HostFactory factory;
    private final Listener listener;
    private final boolean useCoreCadence;
    private volatile long frameDelayNanos;
    private volatile boolean closed;
    private volatile Thread renderThread;

    /* Render-thread state. */
    private Host host;
    private boolean ready;
    private boolean resumeRequested;
    private boolean surfaceAttached;
    private Surface secondarySurface;
    private boolean secondarySurfaceAttached;
    private boolean awaitingRecreate;
    private boolean frameScheduled;
    private long nextFrameDeadlineNanos;

    public ExperimentalGlesRenderLoop(
            File core, File trustedCoreDirectory, File systemDirectory,
            File saveDirectory, File game, Listener listener) {
        this(core, trustedCoreDirectory, systemDirectory, saveDirectory, game,
                ExperimentalGlesLibretroHost.PRESENT_AUTO, listener);
    }

    public ExperimentalGlesRenderLoop(
            File core, File trustedCoreDirectory, File systemDirectory,
            File saveDirectory, File game, int presentationPolicy,
            Listener listener) {
        this(() -> {
            final ExperimentalGlesLibretroHost nativeHost =
                    new ExperimentalGlesLibretroHost(core, trustedCoreDirectory,
                            systemDirectory, saveDirectory, presentationPolicy);
            try {
                nativeHost.loadGame(game);
            } catch (IOException | RuntimeException | Error failure) {
                nativeHost.close();
                throw failure;
            }
            return new Host() {
                @Override public void attach(Surface surface) {
                    nativeHost.attachSurface(surface);
                }
                @Override public void recreate(Surface surface) {
                    nativeHost.recreateSurface(surface);
                }
                @Override public boolean runAndPresent() {
                    return nativeHost.runFrameAndPresent();
                }
                @Override public void detach() { nativeHost.detachSurface(); }
                @Override public void attachSecondary(Surface surface) {
                    throw new UnsupportedOperationException(
                            "secondary hardware surface requires Vulkan");
                }
                @Override public void detachSecondary() {}
                @Override public void pause() { nativeHost.pause(); }
                @Override public void resume() { nativeHost.resume(); }
                @Override public void setJoypadButton(int port, int button,
                                                      boolean pressed) {
                    nativeHost.setJoypadButton(port, button, pressed);
                }
                @Override public void setAnalogAxis(int port, int index, int id,
                                                    float value) {
                    nativeHost.setAnalogAxis(port, index, id, value);
                }
                @Override public void setPointer(int port, short x, short y,
                                                 boolean pressed) {
                    throw new UnsupportedOperationException(
                            "GLES pointer routing is not configured");
                }
                @Override public short[] drainAudio(int maxFrames) {
                    return nativeHost.drainAudio(maxFrames);
                }
                @Override public ExperimentalGlesLibretroHost.AvInfo avInfo() {
                    return nativeHost.avInfo();
                }
                @Override public boolean stateReady() { return nativeHost.stateReady(); }
                @Override public byte[] serialize() { return nativeHost.serialize(); }
                @Override public void unserialize(byte[] state) {
                    nativeHost.unserialize(state);
                }
                @Override public byte[] readSaveRam() { return nativeHost.readSaveRam(); }
                @Override public void writeSaveRam(byte[] saveRam) {
                    nativeHost.writeSaveRam(saveRam);
                }
                @Override public void close() { nativeHost.close(); }
            };
        }, listener, DEFAULT_FRAME_DELAY_NANOS, true);
    }

    public static ExperimentalGlesRenderLoop createVulkan(
            File core, File trustedCoreDirectory, File systemDirectory,
            File saveDirectory, File game, Listener listener) {
        return new ExperimentalGlesRenderLoop(() -> {
            final ExperimentalVulkanLibretroHost nativeHost =
                    new ExperimentalVulkanLibretroHost(core,
                            trustedCoreDirectory, systemDirectory,
                            saveDirectory);
            try {
                nativeHost.loadGame(game);
            } catch (IOException | RuntimeException | Error failure) {
                nativeHost.close();
                throw failure;
            }
            return new Host() {
                @Override public void attach(Surface surface) {
                    nativeHost.attachSurface(surface);
                }
                @Override public void recreate(Surface surface) {
                    nativeHost.recreateSurface(surface);
                }
                @Override public boolean runAndPresent() {
                    return nativeHost.runFrameAndPresent();
                }
                @Override public void detach() { nativeHost.detachSurface(); }
                @Override public void attachSecondary(Surface surface) {
                    nativeHost.attachSecondarySurface(surface);
                }
                @Override public void detachSecondary() {
                    nativeHost.detachSecondarySurface();
                }
                @Override public void pause() { nativeHost.pause(); }
                @Override public void resume() { nativeHost.resume(); }
                @Override public void setJoypadButton(int port, int button,
                                                      boolean pressed) {
                    nativeHost.setJoypadButton(port, button, pressed);
                }
                @Override public void setAnalogAxis(int port, int index, int id,
                                                    float value) {
                    nativeHost.setAnalogAxis(port, index, id, value);
                }
                @Override public void setPointer(int port, short x, short y,
                                                 boolean pressed) {
                    nativeHost.setPointer(port, x, y, pressed);
                }
                @Override public short[] drainAudio(int maxFrames) {
                    return nativeHost.drainAudio(maxFrames);
                }
                @Override public ExperimentalGlesLibretroHost.AvInfo avInfo() {
                    return nativeHost.avInfo();
                }
                @Override public boolean stateReady() { return nativeHost.stateReady(); }
                @Override public byte[] serialize() { return nativeHost.serialize(); }
                @Override public void unserialize(byte[] state) {
                    nativeHost.unserialize(state);
                }
                @Override public byte[] readSaveRam() { return nativeHost.readSaveRam(); }
                @Override public void writeSaveRam(byte[] saveRam) {
                    nativeHost.writeSaveRam(saveRam);
                }
                @Override public void close() { nativeHost.close(); }
            };
        }, listener, DEFAULT_FRAME_DELAY_NANOS, true);
    }

    ExperimentalGlesRenderLoop(HostFactory factory, Listener listener,
                                long frameDelayNanos) {
        this(factory, listener, frameDelayNanos, false);
    }

    private ExperimentalGlesRenderLoop(HostFactory factory, Listener listener,
                                       long frameDelayNanos, boolean useCoreCadence) {
        if (factory == null || listener == null || frameDelayNanos < 0)
            throw new IllegalArgumentException("factory, listener, and frame delay are required");
        this.factory = factory;
        this.listener = listener;
        this.frameDelayNanos = frameDelayNanos;
        this.useCoreCadence = useCoreCadence;
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(() -> {
                renderThread = Thread.currentThread();
                runnable.run();
            }, "lucent-experimental-gles");
            thread.setDaemon(true);
            return thread;
        });
        post(this::initialize);
    }

    public void attachSurface(Surface surface) {
        requireSurface(surface);
        post(() -> {
            requireReady();
            if (surfaceAttached) host.recreate(surface);
            else host.attach(surface);
            surfaceAttached = true;
            attachPendingSecondary();
            awaitingRecreate = false;
            if (resumeRequested) {
                host.resume();
                resetFrameClock();
            }
            scheduleFrame();
        });
    }

    public void recreateSurface(Surface surface) {
        requireSurface(surface);
        post(() -> {
            requireReady();
            if (!surfaceAttached)
                throw new IllegalStateException("no GLES surface exists to recreate");
            if (secondarySurfaceAttached) {
                host.detachSecondary();
                secondarySurfaceAttached = false;
            }
            host.recreate(surface);
            attachPendingSecondary();
            awaitingRecreate = false;
            if (resumeRequested) {
                host.resume();
                resetFrameClock();
            }
            scheduleFrame();
        });
    }

    public void detachSurface() {
        post(() -> {
            requireReady();
            if (secondarySurfaceAttached) host.detachSecondary();
            secondarySurfaceAttached = false;
            if (surfaceAttached) host.detach();
            surfaceAttached = false;
            awaitingRecreate = false;
            nextFrameDeadlineNanos = 0L;
        });
    }

    /**
     * Synchronous, bounded variant of {@link #detachSurface()} for the
     * TextureView destroy callback, which releases the game Surface as soon
     * as it returns.
     *
     * @return false when the bound elapsed first; the queued detach then
     *         completes asynchronously on the render thread instead of
     *         blocking the UI thread further.
     */
    public boolean detachSurfaceAndWait() {
        return awaitDetach(() -> {
            if (host == null) return;
            if (secondarySurfaceAttached) host.detachSecondary();
            secondarySurfaceAttached = false;
            if (surfaceAttached) host.detach();
            surfaceAttached = false;
            awaitingRecreate = false;
            nextFrameDeadlineNanos = 0L;
        });
    }

    public void attachSecondarySurface(Surface surface) {
        requireSurface(surface);
        post(() -> {
            requireReady();
            if (secondarySurfaceAttached) host.detachSecondary();
            secondarySurface = surface;
            secondarySurfaceAttached = false;
            attachPendingSecondary();
        });
    }

    public void detachSecondarySurface() {
        post(() -> {
            requireReady();
            if (secondarySurfaceAttached) host.detachSecondary();
            secondarySurfaceAttached = false;
            secondarySurface = null;
        });
    }

    /** Completes the native lower-swapchain detach before its SurfaceView may
     * be removed by the lower-display preview Activity. */
    public void detachSecondarySurfaceAndWait() {
        call(() -> {
            if (secondarySurfaceAttached) host.detachSecondary();
            secondarySurfaceAttached = false;
            secondarySurface = null;
            return null;
        });
    }

    /**
     * Bounded variant of {@link #detachSecondarySurfaceAndWait()} for the
     * lower SurfaceView destroy callback, whose Surface also dies on return.
     *
     * @return false when the bound elapsed and the detach will finish
     *         asynchronously on the render thread.
     */
    public boolean detachSecondarySurfaceAndWaitBounded() {
        return awaitDetach(() -> {
            if (host != null && secondarySurfaceAttached) host.detachSecondary();
            secondarySurfaceAttached = false;
            secondarySurface = null;
        });
    }

    private boolean awaitDetach(Runnable detach) {
        if (closed) return true;
        if (Thread.currentThread() == renderThread) {
            detach.run();
            return true;
        }
        final CountDownLatch finished = new CountDownLatch(1);
        try {
            executor.execute(() -> {
                try { if (!closed) detach.run(); }
                catch (Throwable failure) { reportError(failure); }
                finally { finished.countDown(); }
            });
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            return true;
        }
        try {
            return finished.await(SURFACE_DETACH_WAIT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public void resume() {
        post(() -> {
            requireReady();
            resumeRequested = true;
            if (surfaceAttached && !awaitingRecreate) host.resume();
            resetFrameClock();
            scheduleFrame();
        });
    }

    public void pause() {
        post(() -> {
            requireReady();
            resumeRequested = false;
            nextFrameDeadlineNanos = 0L;
            host.pause();
        });
    }

    /**
     * Exit-only render barrier. Unlike ordinary menu/background pause, this is
     * synchronous: all earlier frame work finishes on the owning render thread
     * and no later frame can be scheduled before the caller changes Android
     * view z-order or surface ownership.
     */
    public void pauseAndWait() {
        call(() -> {
            resumeRequested = false;
            nextFrameDeadlineNanos = 0L;
            host.pause();
            return null;
        });
    }

    public void setJoypadButton(int port, int button, boolean pressed) {
        post(() -> {
            requireReady();
            host.setJoypadButton(port, button, pressed);
        });
    }

    public void setAnalogAxis(int port, int index, int id, float value) {
        post(() -> {
            requireReady();
            host.setAnalogAxis(port, index, id, value);
        });
    }

    public void setPointer(int port, short x, short y, boolean pressed) {
        post(() -> {
            requireReady();
            host.setPointer(port, x, y, pressed);
        });
    }

    /** Drains native PCM on the render thread; safe to call from any thread. */
    public short[] drainAudio(final int maxFrames) {
        if (maxFrames <= 0) return new short[0];
        return call(() -> host.drainAudio(maxFrames));
    }

    public ExperimentalGlesLibretroHost.AvInfo avInfo() {
        return call(() -> host.avInfo());
    }

    public byte[] serialize() { return call(() -> host.serialize()); }

    public boolean stateReady() { return call(() -> host.stateReady()); }

    public void unserialize(final byte[] state) {
        if (state == null || state.length == 0)
            throw new IllegalArgumentException("serialized state is required");
        call(() -> { host.unserialize(state); return null; });
    }

    public byte[] readSaveRam() { return call(() -> host.readSaveRam()); }

    public void writeSaveRam(final byte[] saveRam) {
        if (saveRam == null) throw new IllegalArgumentException("save RAM is required");
        call(() -> { host.writeSaveRam(saveRam); return null; });
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        if (Thread.currentThread() == renderThread) {
            teardown();
            executor.shutdown();
            return;
        }
        CountDownLatch finished = new CountDownLatch(1);
        executor.execute(() -> {
            try { teardown(); }
            finally { finished.countDown(); }
        });
        executor.shutdown();
        try {
            if (!finished.await(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                throw new IllegalStateException("timed out closing experimental GLES thread");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
            throw new IllegalStateException("interrupted closing experimental GLES thread",
                                            interrupted);
        }
    }

    private void initialize() {
        try {
            host = factory.createAndLoad();
            if (host == null) throw new IllegalStateException("GLES host factory returned null");
            if (useCoreCadence)
                frameDelayNanos = frameDelayNanos(host.avInfo().framesPerSecond);
            ready = true;
            listener.onReady();
        } catch (Throwable failure) {
            reportError(failure);
        }
    }

    private void scheduleFrame() {
        if (closed || !ready || !resumeRequested || !surfaceAttached ||
                awaitingRecreate || frameScheduled) return;
        long delayNanos = 0L;
        if (frameDelayNanos > 0L) {
            long now = System.nanoTime();
            if (nextFrameDeadlineNanos == 0L ||
                    now - nextFrameDeadlineNanos > frameDelayNanos * 4L)
                nextFrameDeadlineNanos = now;
            delayNanos = Math.max(0L, nextFrameDeadlineNanos - now);
        }
        frameScheduled = true;
        executor.schedule(() -> {
            frameScheduled = false;
            if (closed || !resumeRequested || !surfaceAttached || awaitingRecreate) return;
            try {
                if (host.runAndPresent()) listener.onFramePresented();
            } catch (Throwable failure) {
                if (isContextLoss(failure)) {
                    awaitingRecreate = true;
                    try { host.pause(); } catch (Throwable ignored) {}
                    listener.onContextLost();
                    return;
                }
                resumeRequested = false;
                try { host.pause(); } catch (Throwable ignored) {}
                reportError(failure);
                return;
            }
            if (frameDelayNanos > 0L)
                nextFrameDeadlineNanos += frameDelayNanos;
            scheduleFrame();
        }, delayNanos, TimeUnit.NANOSECONDS);
    }

    /**
     * Frame deadlines are absolute.  Scheduling a full frame interval after
     * emulation/GPU work makes a nominal 60 Hz core run at
     * {@code 1 / (16.7 ms + work time)}, starving its real-time audio stream.
     */
    private void resetFrameClock() {
        nextFrameDeadlineNanos = System.nanoTime();
    }

    static long frameDelayNanos(double framesPerSecond) {
        if (!Double.isFinite(framesPerSecond) || framesPerSecond <= 1.0 ||
                framesPerSecond >= 1000.0)
            return DEFAULT_FRAME_DELAY_NANOS;
        return Math.max(1L, Math.round(1_000_000_000.0 / framesPerSecond));
    }

    private void teardown() {
        Host current = host;
        host = null;
        ready = false;
        resumeRequested = false;
        if (current == null) return;
        try { current.pause(); } catch (Throwable failure) { reportError(failure); }
        if (secondarySurfaceAttached) {
            try { current.detachSecondary(); }
            catch (Throwable failure) { reportError(failure); }
        }
        secondarySurfaceAttached = false;
        secondarySurface = null;
        if (surfaceAttached) {
            try { current.detach(); } catch (Throwable failure) { reportError(failure); }
        }
        surfaceAttached = false;
        awaitingRecreate = false;
        nextFrameDeadlineNanos = 0L;
        try { current.close(); } catch (Throwable failure) { reportError(failure); }
    }

    private void attachPendingSecondary() {
        if (!surfaceAttached || secondarySurfaceAttached || secondarySurface == null ||
                !secondarySurface.isValid()) return;
        host.attachSecondary(secondarySurface);
        secondarySurfaceAttached = true;
    }

    private void post(Runnable action) {
        if (closed) throw new IllegalStateException("experimental GLES loop is closed");
        executor.execute(() -> {
            try { action.run(); }
            catch (Throwable failure) { reportError(failure); }
        });
    }

    private <T> T call(Callable<T> action) {
        if (closed) throw new IllegalStateException("experimental GLES loop is closed");
        if (Thread.currentThread() == renderThread) {
            requireReady();
            try { return action.call(); }
            catch (RuntimeException | Error failure) { throw failure; }
            catch (Exception failure) {
                throw new IllegalStateException("experimental GLES call failed", failure);
            }
        }
        FutureTask<T> task = new FutureTask<>(() -> {
            requireReady();
            return action.call();
        });
        executor.execute(task);
        try {
            return task.get(10, TimeUnit.SECONDS);
        } catch (TimeoutException timeout) {
            task.cancel(false);
            throw new IllegalStateException("timed out waiting for experimental GLES thread",
                                            timeout);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting for experimental GLES thread",
                                            interrupted);
        } catch (java.util.concurrent.ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new IllegalStateException("experimental GLES call failed", cause);
        }
    }

    private void requireReady() {
        if (!ready || host == null)
            throw new IllegalStateException("experimental GLES host is not ready");
    }

    private static void requireSurface(Surface surface) {
        if (surface == null || !surface.isValid())
            throw new IllegalArgumentException("valid Android Surface is required");
    }

    private static boolean isContextLoss(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message == null) continue;
            String normalized = message.toLowerCase(Locale.US);
            if (normalized.contains("context lost") || normalized.contains("0x300e"))
                return true;
        }
        return false;
    }

    private void reportError(Throwable failure) {
        try { listener.onError(failure); } catch (Throwable ignored) {}
    }
}
