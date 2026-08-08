package com.thorium.preview;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;

/**
 * Lifecycle-safe Java boundary for Lucent's independent libretro API host.
 *
 * Core binaries are never downloaded by this class. The caller must pass a
 * core under the app-private trusted directory after EngineRegistry has
 * approved its exact source/build identity.
 */
public final class LibretroHost implements Closeable {
    private static final int MAX_VIDEO_DIMENSION = 8192;
    private static final long MAX_VIDEO_BYTES = 128L * 1024L * 1024L;
    public static final class VideoFrame {
        public final int width;
        public final int height;
        public final int pitch;
        public final int pixelFormat;
        public final int sequence;
        public final byte[] pixels;

        VideoFrame(int[] info, byte[] pixels) {
            width = info[0];
            height = info[1];
            pitch = info[2];
            pixelFormat = info[3];
            sequence = info[5];
            this.pixels = pixels;
        }
    }

    public static final class AvInfo {
        public final double framesPerSecond;
        public final double sampleRate;
        public final float aspectRatio;
        public final int baseWidth;
        public final int baseHeight;

        AvInfo(double[] info) {
            framesPerSecond = info[0];
            sampleRate = info[1];
            aspectRatio = (float)info[2];
            baseWidth = (int)info[3];
            baseHeight = (int)info[4];
        }
    }

    static {
        System.loadLibrary("lucent_libretro_host");
    }

    private long handle;

    public LibretroHost(File core, File trustedCoreDirectory, File systemDirectory,
                        File saveDirectory) throws IOException {
        if (core == null || trustedCoreDirectory == null || systemDirectory == null ||
                saveDirectory == null) throw new IllegalArgumentException("all paths are required");
        File canonicalCore = core.getCanonicalFile();
        File canonicalRoot = trustedCoreDirectory.getCanonicalFile();
        if (!isInside(canonicalCore, canonicalRoot))
            throw new SecurityException("core must be in Lucent's app-private trusted directory");
        if (!systemDirectory.isDirectory() && !systemDirectory.mkdirs())
            throw new IOException("cannot create system directory");
        if (!saveDirectory.isDirectory() && !saveDirectory.mkdirs())
            throw new IOException("cannot create save directory");
        handle = nativeCreate(canonicalCore.getPath(), canonicalRoot.getPath(),
                systemDirectory.getCanonicalPath(), saveDirectory.getCanonicalPath());
    }

    public synchronized void loadGame(File game) throws IOException {
        checkOpen();
        if (game == null || !game.isFile()) throw new IOException("game content does not exist");
        nativeLoadGame(handle, game.getCanonicalPath());
    }

    /**
     * Selects the controller a core emulates on a port. Loading a game resets
     * port 0 to a plain RetroPad, so a system whose device is something else
     * must set it afterwards -- Dolphin only attaches a Wii Nunchuk for
     * RETRO_DEVICE_WIIMOTE_NC, and games that require the extension refuse
     * input without it.
     */
    public synchronized void setControllerPortDevice(int port, int device) {
        checkOpen();
        nativeSetControllerPortDevice(handle, port, device);
    }

    /**
     * Power-cycles the loaded content in place (libretro {@code retro_reset}).
     * Unlike an unload/load pair this keeps the core's battery-backed save RAM
     * in memory, so a reset behaves like the console's reset button rather
     * than pulling the cartridge.
     */
    public synchronized void reset() {
        checkOpen();
        nativeReset(handle);
    }

    /**
     * Unloads the active game. Cores with a Lucent exit-persistence extension
     * may reject this call; in that case the host and game remain open.
     */
    public synchronized void unloadGame() {
        checkOpen();
        nativeUnloadGame(handle);
    }

    public synchronized void runFrame() {
        checkOpen();
        nativeRunFrame(handle);
    }

    public synchronized void pause() {
        checkOpen();
        nativeSetPaused(handle, true);
    }

    public synchronized void resume() {
        checkOpen();
        nativeSetPaused(handle, false);
    }

    /** Uses libretro joypad IDs 0..15 after Lucent's canonical mapping. */
    public synchronized void setJoypadButton(int port, int retroJoypadId, boolean pressed) {
        checkOpen();
        nativeSetJoypadButton(handle, port, retroJoypadId, pressed);
    }

    /**
     * Sets one standard libretro analog axis from an Android-style normalized
     * value. Index 0 is the left stick, 1 is the right stick; IDs 0/1 are X/Y.
     */
    public synchronized void setAnalogAxis(int port, int retroAnalogIndex,
                                           int retroAnalogId, float normalizedValue) {
        checkOpen();
        if (Float.isNaN(normalizedValue)) normalizedValue = 0f;
        float clamped = Math.max(-1f, Math.min(1f, normalizedValue));
        int signedValue = clamped <= -1f ? Short.MIN_VALUE
                : Math.round(clamped * Short.MAX_VALUE);
        nativeSetAnalogAxis(handle, port, retroAnalogIndex, retroAnalogId, signedValue);
    }

    /** Sets an exact signed libretro axis value for tests and specialized input. */
    public synchronized void setAnalogAxisRaw(int port, int retroAnalogIndex,
                                              int retroAnalogId, short value) {
        checkOpen();
        nativeSetAnalogAxis(handle, port, retroAnalogIndex, retroAnalogId, value);
    }

    /** Sets one standard libretro pointer coordinate and contact state. */
    public synchronized void setPointer(int port, short x, short y, boolean pressed) {
        checkOpen();
        nativeSetPointer(handle, port, x, y, pressed);
    }

    /** Returns the newest tightly-copied software frame, or null before first video. */
    public synchronized VideoFrame latestVideoFrame() {
        checkOpen();
        int[] info = nativeVideoInfo(handle);
        if (info == null || info.length != 6) return null;
        long width = info[0];
        long height = info[1];
        long pitch = info[2];
        long byteSize = info[4];
        int bytesPerPixel = info[3] == 1 ? 4 : 2;
        if (info[3] < 0 || info[3] > 2 || width < 1 || height < 1 ||
                width > MAX_VIDEO_DIMENSION || height > MAX_VIDEO_DIMENSION ||
                pitch < width * bytesPerPixel || byteSize != pitch * height ||
                byteSize < 1 || byteSize > MAX_VIDEO_BYTES) return null;
        byte[] pixels = nativeCopyVideoFrame(handle, info[4]);
        return pixels == null ? null : new VideoFrame(info, pixels);
    }

    /** Returns interleaved signed 16-bit stereo PCM, up to maxFrames. */
    public synchronized short[] drainAudio(int maxFrames) {
        checkOpen();
        if (maxFrames <= 0) throw new IllegalArgumentException("maxFrames must be positive");
        return nativeDrainAudio(handle, maxFrames);
    }

    public synchronized AvInfo avInfo() {
        checkOpen();
        double[] info = nativeAvInfo(handle);
        if (info == null || info.length != 5)
            throw new IllegalStateException("AV timing is unavailable before loading a game");
        return new AvInfo(info);
    }

    public synchronized byte[] serialize() {
        checkOpen();
        return nativeSerialize(handle);
    }

    public synchronized void unserialize(byte[] state) {
        checkOpen();
        if (state == null || state.length == 0)
            throw new IllegalArgumentException("serialized state is required");
        nativeUnserialize(handle, state);
    }

    public synchronized String libraryName() {
        checkOpen();
        return nativeLibraryName(handle);
    }

    public synchronized String libraryVersion() {
        checkOpen();
        return nativeLibraryVersion(handle);
    }

    /** Returns a snapshot suitable for atomic persistence, or null when absent. */
    public synchronized byte[] readSaveRam() {
        checkOpen();
        return nativeReadSaveRam(handle);
    }

    public synchronized void writeSaveRam(byte[] saveRam) {
        checkOpen();
        if (saveRam == null) throw new IllegalArgumentException("save RAM is required");
        nativeWriteSaveRam(handle, saveRam);
    }

    @Override public synchronized void close() {
        if (handle == 0) return;
        nativeDestroy(handle);
        handle = 0;
    }

    private void checkOpen() {
        if (handle == 0) throw new IllegalStateException("core host is closed");
    }

    private static boolean isInside(File file, File directory) {
        String path = file.getPath();
        String root = directory.getPath();
        return path.startsWith(root + File.separator);
    }

    private static native long nativeCreate(String corePath, String trustedRoot,
                                             String systemDirectory, String saveDirectory);
    private static native void nativeLoadGame(long handle, String gamePath);
    private static native void nativeSetControllerPortDevice(
            long handle, int port, int device);
    private static native void nativeReset(long handle);
    private static native void nativeUnloadGame(long handle);
    private static native void nativeRunFrame(long handle);
    private static native void nativeSetPaused(long handle, boolean paused);
    private static native void nativeSetJoypadButton(long handle, int port, int button,
                                                     boolean pressed);
    private static native void nativeSetAnalogAxis(long handle, int port, int index,
                                                   int id, int value);
    private static native void nativeSetPointer(long handle, int port, int x, int y,
                                                boolean pressed);
    private static native int[] nativeVideoInfo(long handle);
    private static native byte[] nativeCopyVideoFrame(long handle, int size);
    private static native short[] nativeDrainAudio(long handle, int maxFrames);
    private static native double[] nativeAvInfo(long handle);
    private static native byte[] nativeSerialize(long handle);
    private static native void nativeUnserialize(long handle, byte[] state);
    private static native String nativeLibraryName(long handle);
    private static native String nativeLibraryVersion(long handle);
    private static native byte[] nativeReadSaveRam(long handle);
    private static native void nativeWriteSaveRam(long handle, byte[] saveRam);
    private static native void nativeDestroy(long handle);
}
