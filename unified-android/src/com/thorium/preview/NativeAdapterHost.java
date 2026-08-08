package com.thorium.preview;

import android.view.Surface;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;

/**
 * Lifecycle-safe Java boundary for Lucent's Phase 3 native-adapter host.
 *
 * The adapter shared library is never downloaded by this class. The caller must
 * pass an adapter under the app-private trusted directory only after
 * NativeAdapterCatalog has approved its exact source/build identity. Every call
 * for one session must run on the single render-owner thread, matching the C
 * host's serialization contract.
 */
public final class NativeAdapterHost implements Closeable {

    /** Honest capability report copied from the adapter's describe(). */
    public static final class Capabilities {
        public final int abiVersion;
        public final boolean hasQuickResume;
        public final boolean hasPersistentSave;
        public final boolean dualScreen;
        public final int requiredFirmware;
        public final String engineId;

        Capabilities(int[] values, String engineId) {
            abiVersion = values[0];
            hasQuickResume = values[1] != 0;
            hasPersistentSave = values[2] != 0;
            dualScreen = values[3] != 0;
            requiredFirmware = values[4];
            this.engineId = engineId == null ? "" : engineId;
        }
    }

    static {
        System.loadLibrary("lucent_native_adapter_host");
    }

    private long handle;

    public NativeAdapterHost(File adapter, File trustedDirectory) throws IOException {
        if (adapter == null || trustedDirectory == null)
            throw new IllegalArgumentException("adapter and trusted directory are required");
        File canonicalAdapter = adapter.getCanonicalFile();
        File canonicalRoot = trustedDirectory.getCanonicalFile();
        if (!isInside(canonicalAdapter, canonicalRoot))
            throw new SecurityException(
                    "adapter must be in Lucent's app-private trusted directory");
        handle = nativeOpen(canonicalAdapter.getPath(), canonicalRoot.getPath());
        if (handle == 0L) throw new IOException("adapter host could not be opened");
    }

    public Capabilities describe() {
        return new Capabilities(nativeDescribe(handle), nativeEngineId(handle));
    }

    public void create() { nativeCreate(handle); }

    public void loadContent(File systemDirectory, File saveDirectory, String contentPath) {
        if (contentPath == null || contentPath.isEmpty())
            throw new IllegalArgumentException("content path is required");
        nativeLoadContent(handle,
                systemDirectory == null ? null : systemDirectory.getPath(),
                saveDirectory == null ? null : saveDirectory.getPath(),
                contentPath);
    }

    public void start(Surface primary, Surface lower) {
        nativeStart(handle, primary, lower);
    }

    public void surfaceRecreated(Surface primary, Surface lower) {
        nativeSurfaceRecreated(handle, primary, lower);
    }

    public void runFrame() { nativeRunFrame(handle); }

    public void setControl(int controlOrdinal, float value) {
        nativeSetControl(handle, controlOrdinal, value);
    }

    public void pause() { nativePause(handle); }

    public void resume() { nativeResume(handle); }

    public void flushSave() { nativeFlushSave(handle); }

    /** Drains up to maxFrames interleaved stereo s16 frames; never null. */
    public short[] drainAudio(int maxFrames) {
        short[] samples = nativeDrainAudio(handle, maxFrames);
        return samples == null ? new short[0] : samples;
    }

    /** Returns null when the adapter has no Quick Resume, not a fake snapshot. */
    public byte[] serialize() { return nativeSerialize(handle); }

    public boolean unserialize(byte[] state) { return nativeUnserialize(handle, state); }

    public void stop() { nativeStop(handle); }

    @Override public void close() {
        if (handle != 0L) {
            nativeDestroy(handle);
            handle = 0L;
        }
    }

    private static boolean isInside(File candidate, File root) {
        for (File parent = candidate.getParentFile(); parent != null;
             parent = parent.getParentFile())
            if (parent.equals(root)) return true;
        return false;
    }

    private static native long nativeOpen(String adapterPath, String trustedRoot);
    private static native int[] nativeDescribe(long handle);
    private static native String nativeEngineId(long handle);
    private static native void nativeCreate(long handle);
    private static native void nativeLoadContent(long handle, String systemDirectory,
            String saveDirectory, String contentPath);
    private static native void nativeStart(long handle, Surface primary, Surface lower);
    private static native void nativeSurfaceRecreated(long handle, Surface primary,
            Surface lower);
    private static native void nativeRunFrame(long handle);
    private static native void nativeSetControl(long handle, int control, float value);
    private static native void nativePause(long handle);
    private static native void nativeResume(long handle);
    private static native void nativeFlushSave(long handle);
    private static native short[] nativeDrainAudio(long handle, int maxFrames);
    private static native byte[] nativeSerialize(long handle);
    private static native boolean nativeUnserialize(long handle, byte[] state);
    private static native void nativeStop(long handle);
    private static native void nativeDestroy(long handle);
}
