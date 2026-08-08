package com.thorium.preview;

import android.os.Build;
import android.view.Surface;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;

/** Android Vulkan hardware-render libretro bridge owned entirely by Lucent. */
public final class ExperimentalVulkanLibretroHost implements Closeable {
    static {
        if (Build.VERSION.SDK_INT >= 24) System.loadLibrary("lucent_vulkan_host");
    }

    private long handle;

    public ExperimentalVulkanLibretroHost(File core, File trustedCoreDirectory,
                                          File systemDirectory,
                                          File saveDirectory) throws IOException {
        if (Build.VERSION.SDK_INT < 24)
            throw new UnsupportedOperationException("Vulkan requires Android 7.0+");
        if (core == null || trustedCoreDirectory == null ||
                systemDirectory == null || saveDirectory == null)
            throw new IllegalArgumentException("all paths are required");
        File canonicalCore = core.getCanonicalFile();
        File canonicalRoot = trustedCoreDirectory.getCanonicalFile();
        if (!canonicalCore.getPath().startsWith(
                canonicalRoot.getPath() + File.separator))
            throw new SecurityException(
                    "core must be in Lucent's app-private trusted directory");
        if (!systemDirectory.isDirectory() && !systemDirectory.mkdirs())
            throw new IOException("cannot create system directory");
        if (!saveDirectory.isDirectory() && !saveDirectory.mkdirs())
            throw new IOException("cannot create save directory");
        handle = nativeCreateVulkan(canonicalCore.getPath(),
                canonicalRoot.getPath(), systemDirectory.getCanonicalPath(),
                saveDirectory.getCanonicalPath());
        if (handle == 0)
            throw new IllegalStateException("native Vulkan session was not created");
    }

    public synchronized void loadGame(File game) throws IOException {
        checkOpen();
        if (game == null || !game.isFile())
            throw new IOException("game content does not exist");
        nativeLoadGameVulkan(handle, game.getCanonicalPath());
    }

    public synchronized void attachSurface(Surface surface) {
        checkSurface(surface);
        nativeAttachSurfaceVulkan(handle, surface);
    }

    public synchronized void recreateSurface(Surface surface) {
        checkSurface(surface);
        nativeRecreateSurfaceVulkan(handle, surface);
    }

    public synchronized boolean runFrameAndPresent() {
        checkOpen();
        return nativeRunAndPresentVulkan(handle);
    }

    public synchronized void detachSurface() {
        checkOpen();
        nativeDetachSurfaceVulkan(handle);
    }

    public synchronized void attachSecondarySurface(Surface surface) {
        checkSurface(surface);
        nativeAttachSecondarySurfaceVulkan(handle, surface);
    }

    public synchronized void detachSecondarySurface() {
        checkOpen();
        nativeDetachSecondarySurfaceVulkan(handle);
    }

    public synchronized void pause() {
        checkOpen();
        nativeSetPausedVulkan(handle, true);
    }

    public synchronized void resume() {
        checkOpen();
        nativeSetPausedVulkan(handle, false);
    }

    public synchronized void setJoypadButton(int port, int button,
                                              boolean pressed) {
        checkOpen();
        nativeSetJoypadButtonVulkan(handle, port, button, pressed);
    }

    public synchronized void setAnalogAxis(int port, int index, int id,
                                            float value) {
        checkOpen();
        if (Float.isNaN(value)) value = 0f;
        float clamped = Math.max(-1f, Math.min(1f, value));
        int signed = clamped <= -1f ? Short.MIN_VALUE :
                Math.round(clamped * Short.MAX_VALUE);
        nativeSetAnalogAxisVulkan(handle, port, index, id, signed);
    }

    public synchronized void setPointer(int port, short x, short y,
                                        boolean pressed) {
        checkOpen();
        nativeSetPointerVulkan(handle, port, x, y, pressed);
    }

    public synchronized short[] drainAudio(int maxFrames) {
        checkOpen();
        if (maxFrames <= 0) return new short[0];
        short[] samples = nativeDrainAudioVulkan(handle, maxFrames);
        return samples == null ? new short[0] : samples;
    }

    public synchronized ExperimentalGlesLibretroHost.AvInfo avInfo() {
        checkOpen();
        double[] values = nativeAvInfoVulkan(handle);
        if (values == null || values.length != 5)
            throw new IllegalStateException("AV timing is unavailable");
        return new ExperimentalGlesLibretroHost.AvInfo(values);
    }

    public synchronized byte[] serialize() {
        checkOpen();
        byte[] state = nativeSerializeVulkan(handle);
        if (state == null || state.length == 0)
            throw new IllegalStateException("core did not serialize a usable state");
        return state;
    }

    public synchronized boolean stateReady() {
        checkOpen();
        return nativeStateReadyVulkan(handle);
    }

    public synchronized void unserialize(byte[] state) {
        checkOpen();
        if (state == null || state.length == 0)
            throw new IllegalArgumentException("serialized state is required");
        nativeUnserializeVulkan(handle, state);
    }

    public synchronized byte[] readSaveRam() {
        checkOpen();
        return nativeReadSaveRamVulkan(handle);
    }

    public synchronized void writeSaveRam(byte[] saveRam) {
        checkOpen();
        if (saveRam == null) throw new IllegalArgumentException("save RAM is required");
        nativeWriteSaveRamVulkan(handle, saveRam);
    }

    @Override public synchronized void close() {
        if (handle == 0) return;
        long active = handle;
        handle = 0;
        nativeDestroyVulkan(active);
    }

    private void checkOpen() {
        if (handle == 0) throw new IllegalStateException("Vulkan host is closed");
    }

    private void checkSurface(Surface surface) {
        checkOpen();
        if (surface == null || !surface.isValid())
            throw new IllegalArgumentException("valid Android Surface is required");
    }

    private static native long nativeCreateVulkan(
            String core, String root, String system, String save);
    private static native void nativeLoadGameVulkan(long handle, String game);
    private static native void nativeAttachSurfaceVulkan(long handle, Surface surface);
    private static native void nativeRecreateSurfaceVulkan(long handle, Surface surface);
    private static native boolean nativeRunAndPresentVulkan(long handle);
    private static native void nativeDetachSurfaceVulkan(long handle);
    private static native void nativeAttachSecondarySurfaceVulkan(
            long handle, Surface surface);
    private static native void nativeDetachSecondarySurfaceVulkan(long handle);
    private static native void nativeSetPausedVulkan(long handle, boolean paused);
    private static native void nativeSetJoypadButtonVulkan(
            long handle, int port, int button, boolean pressed);
    private static native void nativeSetAnalogAxisVulkan(
            long handle, int port, int index, int id, int value);
    private static native void nativeSetPointerVulkan(
            long handle, int port, int x, int y, boolean pressed);
    private static native short[] nativeDrainAudioVulkan(long handle, int maxFrames);
    private static native double[] nativeAvInfoVulkan(long handle);
    private static native byte[] nativeSerializeVulkan(long handle);
    private static native boolean nativeStateReadyVulkan(long handle);
    private static native void nativeUnserializeVulkan(long handle, byte[] state);
    private static native byte[] nativeReadSaveRamVulkan(long handle);
    private static native void nativeWriteSaveRamVulkan(long handle, byte[] saveRam);
    private static native void nativeDestroyVulkan(long handle);
}
