package com.thorium.preview;

import android.view.Surface;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;

/**
 * Explicitly experimental Phase 2 GLES bridge.
 *
 * <p>This class is not selected by Lucent's production engine catalog. A
 * qualification caller must keep attach, run/present, recreate, detach, and
 * close on one dedicated render thread. The ordinary {@link LibretroHost}
 * remains software-only and cannot accidentally enable this path.</p>
 */
public final class ExperimentalGlesLibretroHost implements Closeable {
    interface Bindings {
        long create(String core, String root, String system, String save,
                    int presentationPolicy);
        void loadGame(long handle, String game);
        void attach(long handle, Surface surface);
        void recreate(long handle, Surface surface);
        boolean runAndPresent(long handle);
        void detach(long handle);
        void setPaused(long handle, boolean paused);
        void setJoypadButton(long handle, int port, int button, boolean pressed);
        void setAnalogAxis(long handle, int port, int index, int id, int value);
        int[] hardwareInfo(long handle);
        short[] drainAudio(long handle, int maxFrames);
        double[] avInfo(long handle);
        boolean stateReady(long handle);
        byte[] serialize(long handle);
        void unserialize(long handle, byte[] state);
        byte[] readSaveRam(long handle);
        void writeSaveRam(long handle, byte[] saveRam);
        void destroy(long handle);
    }

    private static final class JniBindings implements Bindings {
        static { System.loadLibrary("lucent_libretro_host"); }
        static final JniBindings INSTANCE = new JniBindings();
        @Override public long create(String core, String root, String system, String save,
                                     int presentationPolicy) {
            return nativeCreateGles(core, root, system, save, presentationPolicy);
        }
        @Override public void loadGame(long handle, String game) {
            nativeLoadGameGles(handle, game);
        }
        @Override public void attach(long handle, Surface surface) {
            nativeAttachSurfaceGles(handle, surface);
        }
        @Override public void recreate(long handle, Surface surface) {
            nativeRecreateSurfaceGles(handle, surface);
        }
        @Override public boolean runAndPresent(long handle) {
            return nativeRunAndPresentGles(handle);
        }
        @Override public void detach(long handle) { nativeDetachSurfaceGles(handle); }
        @Override public void setPaused(long handle, boolean paused) {
            nativeSetPausedGles(handle, paused);
        }
        @Override public void setJoypadButton(long handle, int port, int button,
                                              boolean pressed) {
            nativeSetJoypadButtonGles(handle, port, button, pressed);
        }
        @Override public void setAnalogAxis(long handle, int port, int index,
                                            int id, int value) {
            nativeSetAnalogAxisGles(handle, port, index, id, value);
        }
        @Override public int[] hardwareInfo(long handle) {
            return nativeHardwareInfoGles(handle);
        }
        @Override public short[] drainAudio(long handle, int maxFrames) {
            return nativeDrainAudioGles(handle, maxFrames);
        }
        @Override public double[] avInfo(long handle) { return nativeAvInfoGles(handle); }
        @Override public boolean stateReady(long handle) { return nativeStateReadyGles(handle); }
        @Override public byte[] serialize(long handle) { return nativeSerializeGles(handle); }
        @Override public void unserialize(long handle, byte[] state) {
            nativeUnserializeGles(handle, state);
        }
        @Override public byte[] readSaveRam(long handle) {
            return nativeReadSaveRamGles(handle);
        }
        @Override public void writeSaveRam(long handle, byte[] saveRam) {
            nativeWriteSaveRamGles(handle, saveRam);
        }
        @Override public void destroy(long handle) { nativeDestroyGles(handle); }
    }

    public static final class AvInfo {
        public final double framesPerSecond;
        public final double sampleRate;
        public final float aspectRatio;
        public final int baseWidth;
        public final int baseHeight;

        AvInfo(double[] values) {
            framesPerSecond = values[0];
            sampleRate = values[1];
            aspectRatio = (float) values[2];
            baseWidth = (int) values[3];
            baseHeight = (int) values[4];
        }
    }

    public static final class HardwareInfo {
        public final boolean negotiated;
        public final boolean contextReady;
        public final int contextType;
        public final int majorVersion;
        public final int minorVersion;
        public final boolean bottomLeftOrigin;
        public final int frameSequence;

        HardwareInfo(int[] values) {
            negotiated = values[0] != 0;
            contextReady = values[1] != 0;
            contextType = values[2];
            majorVersion = values[3];
            minorVersion = values[4];
            bottomLeftOrigin = values[5] != 0;
            frameSequence = values[6];
        }
    }

    private final Bindings bindings;
    private long handle;

    public static final int PRESENT_AUTO = 0;
    public static final int PRESENT_FRONTEND_FBO = 1;
    public static final int PRESENT_DIRECT_WINDOW = 2;

    public ExperimentalGlesLibretroHost(File core, File trustedCoreDirectory,
                                        File systemDirectory, File saveDirectory)
            throws IOException {
        this(core, trustedCoreDirectory, systemDirectory, saveDirectory,
                PRESENT_AUTO, JniBindings.INSTANCE);
    }

    public ExperimentalGlesLibretroHost(File core, File trustedCoreDirectory,
                                        File systemDirectory, File saveDirectory,
                                        int presentationPolicy)
            throws IOException {
        this(core, trustedCoreDirectory, systemDirectory, saveDirectory,
                presentationPolicy, JniBindings.INSTANCE);
    }

    ExperimentalGlesLibretroHost(File core, File trustedCoreDirectory,
                                 File systemDirectory, File saveDirectory,
                                 Bindings bindings) throws IOException {
        this(core, trustedCoreDirectory, systemDirectory, saveDirectory,
                PRESENT_AUTO, bindings);
    }

    ExperimentalGlesLibretroHost(File core, File trustedCoreDirectory,
                                 File systemDirectory, File saveDirectory,
                                 int presentationPolicy,
                                 Bindings bindings) throws IOException {
        if (core == null || trustedCoreDirectory == null || systemDirectory == null ||
                saveDirectory == null) throw new IllegalArgumentException("all paths are required");
        if (bindings == null) throw new IllegalArgumentException("bindings are required");
        if (presentationPolicy < PRESENT_AUTO ||
                presentationPolicy > PRESENT_DIRECT_WINDOW)
            throw new IllegalArgumentException("unknown presentation policy");
        this.bindings = bindings;
        File canonicalCore = core.getCanonicalFile();
        File canonicalRoot = trustedCoreDirectory.getCanonicalFile();
        if (!canonicalCore.getPath().startsWith(canonicalRoot.getPath() + File.separator))
            throw new SecurityException("core must be in Lucent's app-private trusted directory");
        if (!systemDirectory.isDirectory() && !systemDirectory.mkdirs())
            throw new IOException("cannot create system directory");
        if (!saveDirectory.isDirectory() && !saveDirectory.mkdirs())
            throw new IOException("cannot create save directory");
        handle = bindings.create(canonicalCore.getPath(), canonicalRoot.getPath(),
                systemDirectory.getCanonicalPath(), saveDirectory.getCanonicalPath(),
                presentationPolicy);
        if (handle == 0) throw new IllegalStateException("native GLES session was not created");
    }

    public synchronized void loadGame(File game) throws IOException {
        checkOpen();
        if (game == null || !game.isFile()) throw new IOException("game content does not exist");
        bindings.loadGame(handle, game.getCanonicalPath());
    }

    public synchronized void attachSurface(Surface surface) {
        checkOpen();
        if (surface == null || !surface.isValid())
            throw new IllegalArgumentException("valid Android Surface is required");
        bindings.attach(handle, surface);
    }

    /** Rebuilds EGL after surface replacement or EGL_CONTEXT_LOST. */
    public synchronized void recreateSurface(Surface surface) {
        checkOpen();
        if (surface == null || !surface.isValid())
            throw new IllegalArgumentException("valid Android Surface is required");
        bindings.recreate(handle, surface);
    }

    /** Runs one core frame and returns true only when a new GPU frame swapped. */
    public synchronized boolean runFrameAndPresent() {
        checkOpen();
        return bindings.runAndPresent(handle);
    }

    public synchronized void detachSurface() {
        checkOpen();
        bindings.detach(handle);
    }

    public synchronized void pause() { checkOpen(); bindings.setPaused(handle, true); }
    public synchronized void resume() { checkOpen(); bindings.setPaused(handle, false); }

    public synchronized void setJoypadButton(int port, int button, boolean pressed) {
        checkOpen();
        bindings.setJoypadButton(handle, port, button, pressed);
    }

    public synchronized void setAnalogAxis(int port, int index, int id, float value) {
        checkOpen();
        if (Float.isNaN(value)) value = 0f;
        float clamped = Math.max(-1f, Math.min(1f, value));
        int signed = clamped <= -1f ? Short.MIN_VALUE :
                Math.round(clamped * Short.MAX_VALUE);
        bindings.setAnalogAxis(handle, port, index, id, signed);
    }

    public synchronized HardwareInfo hardwareInfo() {
        checkOpen();
        int[] values = bindings.hardwareInfo(handle);
        if (values == null || values.length != 7)
            throw new IllegalStateException("hardware negotiation is unavailable");
        return new HardwareInfo(values);
    }

    public synchronized short[] drainAudio(int maxFrames) {
        checkOpen();
        if (maxFrames <= 0) return new short[0];
        short[] samples = bindings.drainAudio(handle, maxFrames);
        return samples == null ? new short[0] : samples;
    }

    public synchronized AvInfo avInfo() {
        checkOpen();
        double[] values = bindings.avInfo(handle);
        if (values == null || values.length != 5)
            throw new IllegalStateException("AV timing is unavailable before loading a game");
        return new AvInfo(values);
    }

    public synchronized byte[] serialize() {
        checkOpen();
        byte[] state = bindings.serialize(handle);
        if (state == null || state.length == 0)
            throw new IllegalStateException("core did not serialize a usable state");
        return state;
    }

    public synchronized void unserialize(byte[] state) {
        checkOpen();
        if (state == null || state.length == 0)
            throw new IllegalArgumentException("serialized state is required");
        bindings.unserialize(handle, state);
    }

    public synchronized byte[] readSaveRam() {
        checkOpen();
        return bindings.readSaveRam(handle);
    }

    public synchronized void writeSaveRam(byte[] saveRam) {
        checkOpen();
        if (saveRam == null) throw new IllegalArgumentException("save RAM is required");
        bindings.writeSaveRam(handle, saveRam);
    }

    public synchronized boolean stateReady() {
        checkOpen();
        return bindings.stateReady(handle);
    }

    @Override public synchronized void close() {
        if (handle == 0) return;
        bindings.destroy(handle);
        handle = 0;
    }

    private void checkOpen() {
        if (handle == 0) throw new IllegalStateException("GLES core host is closed");
    }

    private static native long nativeCreateGles(String corePath, String trustedRoot,
                                                 String systemDirectory, String saveDirectory,
                                                 int presentationPolicy);
    private static native void nativeLoadGameGles(long handle, String gamePath);
    private static native void nativeAttachSurfaceGles(long handle, Surface surface);
    private static native void nativeRecreateSurfaceGles(long handle, Surface surface);
    private static native boolean nativeRunAndPresentGles(long handle);
    private static native void nativeDetachSurfaceGles(long handle);
    private static native void nativeSetPausedGles(long handle, boolean paused);
    private static native void nativeSetJoypadButtonGles(long handle, int port, int button,
                                                         boolean pressed);
    private static native void nativeSetAnalogAxisGles(long handle, int port, int index,
                                                       int id, int value);
    private static native int[] nativeHardwareInfoGles(long handle);
    private static native short[] nativeDrainAudioGles(long handle, int maxFrames);
    private static native double[] nativeAvInfoGles(long handle);
    private static native boolean nativeStateReadyGles(long handle);
    private static native byte[] nativeSerializeGles(long handle);
    private static native void nativeUnserializeGles(long handle, byte[] state);
    private static native byte[] nativeReadSaveRamGles(long handle);
    private static native void nativeWriteSaveRamGles(long handle, byte[] saveRam);
    private static native void nativeDestroyGles(long handle);
}
