package com.thorium.preview;

import android.view.Surface;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class ExperimentalGlesRenderLoopTest {
    private static class FakeHost implements ExperimentalGlesRenderLoop.Host {
        final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        Thread owner;
        int frames;
        int generation;
        byte[] state = new byte[] {1, 2, 3};
        byte[] saveRam = new byte[] {4, 5};

        protected void call(String value) {
            if (owner == null) owner = Thread.currentThread();
            if (owner != Thread.currentThread())
                throw new AssertionError("GLES call escaped dedicated render thread");
            calls.add(value);
        }

        @Override public void attach(Surface surface) { call("attach"); }
        @Override public void recreate(Surface surface) {
            call("recreate"); generation++;
        }
        @Override public boolean runAndPresent() {
            call("run-present");
            frames++;
            if (generation == 0 && frames == 2)
                throw new IllegalStateException("Android EGL context lost during swap");
            return true;
        }
        @Override public void detach() { call("detach"); }
        @Override public void attachSecondary(Surface surface) {
            call("attach-secondary");
        }
        @Override public void detachSecondary() { call("detach-secondary"); }
        @Override public void pause() { call("pause"); }
        @Override public void resume() { call("resume"); }
        @Override public void setJoypadButton(int port, int button, boolean pressed) {
            call("button");
        }
        @Override public void setAnalogAxis(int port, int index, int id, float value) {
            call("axis");
        }
        @Override public void setPointer(int port, short x, short y, boolean pressed) {
            call("pointer");
        }
        @Override public void setControllerPortDevice(int port, int device) {
            call("port-device:" + port + ":0x" + Integer.toHexString(device));
        }
        @Override public void reset() { call("reset"); }
        @Override public short[] drainAudio(int maxFrames) {
            call("audio"); return new short[] {7, -7};
        }
        @Override public ExperimentalGlesLibretroHost.AvInfo avInfo() {
            call("av");
            return new ExperimentalGlesLibretroHost.AvInfo(
                    new double[] {60.0, 48000.0, 1.0, 480.0, 272.0});
        }
        @Override public boolean stateReady() { call("state-ready"); return true; }
        @Override public byte[] serialize() {
            call("serialize"); return java.util.Arrays.copyOf(state, state.length);
        }
        @Override public void unserialize(byte[] value) {
            call("unserialize"); state = java.util.Arrays.copyOf(value, value.length);
        }
        @Override public byte[] readSaveRam() {
            call("read-save"); return java.util.Arrays.copyOf(saveRam, saveRam.length);
        }
        @Override public void writeSaveRam(byte[] value) {
            call("write-save"); saveRam = java.util.Arrays.copyOf(value, value.length);
        }
        @Override public void close() { call("close"); }
    }

    private static final class SlowHost extends FakeHost {
        final CountDownLatch fourFrames = new CountDownLatch(1);
        final List<Long> completions = Collections.synchronizedList(new ArrayList<>());

        @Override public boolean runAndPresent() {
            call("slow-run-present");
            try { Thread.sleep(50L); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            frames++;
            completions.add(System.nanoTime());
            if (frames >= 4) fourFrames.countDown();
            return true;
        }
    }

    public static void main(String[] args) throws Exception {
        check(ExperimentalGlesRenderLoop.frameDelayNanos(50.0) == 20_000_000L,
                "PAL cadence was not derived from core AV timing");
        check(ExperimentalGlesRenderLoop.frameDelayNanos(59.94) == 16_683_350L,
                "fractional NTSC cadence was rounded incorrectly");
        check(ExperimentalGlesRenderLoop.frameDelayNanos(Double.NaN) ==
                        1_000_000_000L / 60L,
                "invalid core cadence did not fall back safely");
        FakeHost host = new FakeHost();
        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch firstPresented = new CountDownLatch(1);
        CountDownLatch contextLost = new CountDownLatch(1);
        CountDownLatch recreatedPresented = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicReference<ExperimentalGlesRenderLoop> activeLoop = new AtomicReference<>();
        ExperimentalGlesRenderLoop loop = new ExperimentalGlesRenderLoop(
                () -> { host.call("create-load"); return host; },
                new ExperimentalGlesRenderLoop.Listener() {
                    int presented;
                    @Override public void onReady() { ready.countDown(); }
                    @Override public void onFramePresented() {
                        short[] callbackAudio = activeLoop.get().drainAudio(8);
                        if (!java.util.Arrays.equals(callbackAudio, new short[] {7, -7}))
                            throw new AssertionError("same-thread audio drain failed");
                        presented++;
                        if (presented == 1) firstPresented.countDown();
                        else recreatedPresented.countDown();
                    }
                    @Override public void onContextLost() { contextLost.countDown(); }
                    @Override public void onError(Throwable failure) {
                        error.compareAndSet(null, failure);
                    }
                }, 1_000_000L);
        activeLoop.set(loop);
        check(ready.await(2, TimeUnit.SECONDS), "render loop did not initialize");
        // Wii/GameCube run on this path: the Nunchuk device and the power
        // cycle must both be marshalled onto the render-owning thread, which
        // FakeHost.call() asserts for every entry point.
        loop.setControllerPortDevice(0, (3 << 8) | 1);
        loop.reset();
        check(loop.avInfo().sampleRate == 48000.0, "render-thread AV query failed");
        check(java.util.Arrays.equals(loop.drainAudio(8), new short[] {7, -7}),
                "render-thread audio drain failed");
        check(java.util.Arrays.equals(loop.serialize(), new byte[] {1, 2, 3}),
                "render-thread serialize failed");
        loop.unserialize(new byte[] {9, 8});
        check(java.util.Arrays.equals(loop.serialize(), new byte[] {9, 8}),
                "render-thread restore failed");
        check(java.util.Arrays.equals(loop.readSaveRam(), new byte[] {4, 5}),
                "render-thread save RAM read failed");
        loop.writeSaveRam(new byte[] {6, 7});
        check(java.util.Arrays.equals(loop.readSaveRam(), new byte[] {6, 7}),
                "render-thread save RAM write failed");
        Surface surface = new Surface(true);
        loop.attachSurface(surface);
        loop.resume();
        loop.setJoypadButton(0, 0, true);
        loop.setAnalogAxis(0, 0, 0, 0.5f);
        check(firstPresented.await(2, TimeUnit.SECONDS), "first GLES frame did not present");
        check(contextLost.await(2, TimeUnit.SECONDS), "context loss was not surfaced");
        loop.recreateSurface(surface);
        check(recreatedPresented.await(2, TimeUnit.SECONDS),
              "recreated GLES surface did not resume presentation");
        int callsBeforeExitBarrier = host.calls.size();
        loop.pauseAndWait();
        check(host.calls.size() > callsBeforeExitBarrier &&
                        "pause".equals(host.calls.get(host.calls.size() - 1)),
                "exit pause did not synchronously drain the render thread");
        int framesAfterExitBarrier = host.frames;
        Thread.sleep(40L);
        check(host.frames == framesAfterExitBarrier,
                "frame presented after synchronous exit barrier");
        loop.detachSurface();
        loop.close();
        loop.close();
        check(error.get() == null, "unexpected render-loop error: " + error.get());
        check(host.calls.contains("create-load") && host.calls.contains("attach") &&
              host.calls.contains("resume") && host.calls.contains("run-present") &&
              host.calls.contains("button") && host.calls.contains("axis") &&
              host.calls.contains("av") && host.calls.contains("audio") &&
              host.calls.contains("serialize") && host.calls.contains("unserialize") &&
              host.calls.contains("read-save") && host.calls.contains("write-save") &&
              host.calls.contains("recreate") && host.calls.contains("pause") &&
              host.calls.contains("detach") && host.calls.contains("close") &&
              host.calls.contains("port-device:0:0x301") &&
              host.calls.contains("reset"),
              "incomplete render lifecycle: " + host.calls);
        check(host.owner != Thread.currentThread(), "GLES work ran on caller thread");
        boolean closedRejected = false;
        try { loop.resume(); }
        catch (IllegalStateException expected) { closedRejected = true; }
        check(closedRejected, "closed render loop accepted new work");

        // Emulation/render work must consume the frame budget, not be followed
        // by another complete frame delay. With 50 ms work and a 50 ms target,
        // four old-style frames take about 350 ms including the first delay;
        // absolute deadlines complete the measured three intervals near 150 ms.
        SlowHost slow = new SlowHost();
        CountDownLatch pacingReady = new CountDownLatch(1);
        AtomicReference<Throwable> pacingError = new AtomicReference<>();
        ExperimentalGlesRenderLoop pacing = new ExperimentalGlesRenderLoop(
                () -> { slow.call("create-load"); return slow; },
                new ExperimentalGlesRenderLoop.Listener() {
                    @Override public void onReady() { pacingReady.countDown(); }
                    @Override public void onFramePresented() {}
                    @Override public void onContextLost() {
                        pacingError.compareAndSet(null,
                                new AssertionError("unexpected pacing context loss"));
                    }
                    @Override public void onError(Throwable failure) {
                        pacingError.compareAndSet(null, failure);
                    }
                }, 50_000_000L);
        check(pacingReady.await(2, TimeUnit.SECONDS), "pacing loop did not initialize");
        pacing.attachSurface(new Surface(true));
        pacing.resume();
        check(slow.fourFrames.await(2, TimeUnit.SECONDS), "pacing loop did not run four frames");
        pacing.pause();
        pacing.close();
        check(pacingError.get() == null, "unexpected pacing error: " + pacingError.get());
        long pacedMillis = TimeUnit.NANOSECONDS.toMillis(
                slow.completions.get(3) - slow.completions.get(0));
        check(pacedMillis < 240L,
                "frame work was added after, not included in, deadlines: " + pacedMillis + " ms");
        System.out.println("Experimental GLES dedicated render-loop probe passed");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
