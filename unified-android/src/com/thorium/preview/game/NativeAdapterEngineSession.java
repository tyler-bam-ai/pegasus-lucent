package com.thorium.preview.game;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;

import com.thorium.lucent.input.CanonicalControl;
import com.thorium.lucent.input.DeviceCatalog;
import com.thorium.lucent.input.FileRemapStore;
import com.thorium.lucent.input.GamepadDescriptor;
import com.thorium.lucent.input.InputRouter;
import com.thorium.lucent.input.InputSignal;
import com.thorium.lucent.input.android.AndroidDeviceScanner;
import com.thorium.lucent.input.android.AndroidGamingDeviceDetector;
import com.thorium.preview.NativeAdapterHost;
import com.thorium.preview.SecondaryGameplaySurfaceRouter;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Drives a Phase 3 native-adapter engine in-process. Two are described by the
 * Phase 3 registry: Switch/Eden (the one with a staged adapter today) and
 * Wii U/Cemu (still unbuilt).
 *
 * It reuses the render-owner discipline proven by {@link PpssppGlesEngineSession}:
 * one dedicated thread owns every adapter call, the surface is quiesced before
 * detach, input maps to the {@code lucent_native_control} ordinals, and audio is
 * pulled into a Lucent-owned {@link AudioTrack}. Capabilities are reported
 * honestly: both adapters report {@code has_quick_resume=false}, so held-Stop
 * flushes normal saves and completes WITHOUT ever claiming a Quick Resume, and
 * {@code onRestoreAvailabilityChanged(false)} is emitted. If the adapter
 * {@code .so} is absent the session fails closed, and so does an adapter whose
 * user-supplied keys/firmware are missing (see
 * {@link NativeAdapterSystemDirectory}).
 *
 * Dual screen: the Wii U TV output uses display 0 (the primary surface) and the
 * GamePad view uses display 4 via {@link SecondaryGameplaySurfaceRouter}, the
 * same path DS/3DS use. Switch is single-screen and Eden reports
 * {@code dual_screen=false}, so it never requests the secondary display.
 */
final class NativeAdapterEngineSession implements EngineSession,
        SecondaryGameplaySurfaceRouter.Listener {
    private static final String TAG = "LucentPhase3Engine";
    private static final int OUTPUT_SAMPLE_RATE = 48_000;
    private static final float AXIS_DEAD_ZONE = 0.08f;

    /* lucent_native_control ordinals (stable across ABI v1). */
    private static final int PAD_A = 0, PAD_B = 1, PAD_X = 2, PAD_Y = 3;
    private static final int PAD_L = 4, PAD_R = 5, PAD_ZL = 6, PAD_ZR = 7;
    private static final int PAD_DPAD_UP = 8, PAD_DPAD_DOWN = 9;
    private static final int PAD_DPAD_LEFT = 10, PAD_DPAD_RIGHT = 11;
    private static final int PAD_START = 12, PAD_SELECT = 13, PAD_HOME = 14;
    private static final int PAD_LSTICK_X = 15, PAD_LSTICK_Y = 16;
    private static final int PAD_RSTICK_X = 17, PAD_RSTICK_Y = 18;

    private final Context context;
    private final Context appContext;
    private final NativeAdapterCatalog.Entry entry;
    private final ExecutorService lifecycle = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "lucent-phase3-lifecycle");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean stopping = new AtomicBoolean(false);
    private final AtomicBoolean released = new AtomicBoolean(false);
    private final AtomicBoolean audioFailureReported = new AtomicBoolean(false);

    private volatile Listener listener;
    private volatile NativeAdapterHost host;
    private volatile NativeAdapterHost.Capabilities capabilities;
    private volatile Surface surface;
    private volatile Surface secondarySurface;
    private volatile boolean secondaryDisplayRequested;
    private volatile boolean prepared;
    private volatile boolean started;
    private volatile boolean resumeRequested;
    private volatile Thread renderThread;
    private volatile AudioTrack audioTrack;
    private GameLaunchRequest request;
    private InputRouter inputRouter;
    private List<GamepadDescriptor> devices = new ArrayList<>();
    private File saveDirectory;
    private File saveRamFile;

    NativeAdapterEngineSession(Context context, NativeAdapterCatalog.Entry entry) {
        if (context == null || entry == null)
            throw new IllegalArgumentException("context and Phase 3 entry are required");
        this.context = context;
        this.appContext = context.getApplicationContext();
        this.entry = entry;
    }

    @Override public void prepare(GameLaunchRequest request, Listener callback) {
        if (request == null || callback == null)
            throw new IllegalArgumentException("launch request and listener are required");
        listener = callback;
        this.request = request;
        lifecycle.execute(() -> {
            try {
                if (!entry.id.equals(request.engineId) || !entry.supports(request.systemId))
                    throw new IllegalStateException("Phase 3 adapter does not support " +
                            request.systemId);
                if (stopping.get() || released.get()) return;
                File game = resolveGameFile(request.contentUri);
                File trusted = new File(appContext.getApplicationInfo().nativeLibraryDir)
                        .getCanonicalFile();
                // Fail closed: the adapter .so must be present and hash-verified.
                if (entry.coreFile == null || !entry.coreFile.isFile())
                    throw new IllegalStateException("Native adapter is not installed");
                saveDirectory = new File(appContext.getFilesDir(),
                        "engine-saves/" + entry.id + "/" + sha256Short(game));
                if (!saveDirectory.isDirectory() && !saveDirectory.mkdirs())
                    throw new IllegalStateException("Cannot create adapter save directory");
                saveRamFile = new File(saveDirectory, "adapter-save.bin");

                NativeAdapterHost created = new NativeAdapterHost(entry.coreFile, trusted);
                capabilities = created.describe();
                // The system directory is resolved from the adapter's OWN
                // capability report, so an engine that declares user-supplied
                // keys/firmware (Eden reports required_firmware=2) fails closed
                // here instead of booting into an undecryptable state. Nothing
                // is ever bundled: user files are copied into the app-private
                // per-engine root, exactly like LibretroEngineSpec.installSystem.
                File system = NativeAdapterSystemDirectory.resolve(appContext,
                        entry.id, request.systemId, capabilities.requiredFirmware);
                created.create();
                created.loadContent(system, saveDirectory, game.getPath());
                host = created;

                inputRouter = new InputRouter(DeviceCatalog.standard(),
                        new FileRemapStore(new File(appContext.getFilesDir(),
                                "controls/remaps.properties")),
                        AndroidGamingDeviceDetector.isKnownGamingHandheld());
                inputRouter.setGame(request.systemId, sha256Short(game));
                refreshDevices();

                if (isWiiUSystem(request.systemId) && capabilities.dualScreen) {
                    secondaryDisplayRequested = SecondaryGameplaySurfaceRouter.request(
                            appContext, request.systemId, this);
                    Log.i(TAG, "Secondary gameplay display requested engine=" +
                            entry.id + " accepted=" + secondaryDisplayRequested);
                }

                prepared = true;
                startRenderThread();
                // A false Quick Resume is never advertised for this engine.
                callback.onRestoreAvailabilityChanged(
                        capabilities.hasQuickResume && hasRestorableState());
                callback.onSessionReady();
                Log.i(TAG, "Adapter ready engine=" + entry.id + " system=" +
                        request.systemId + " quickResume=" +
                        capabilities.hasQuickResume + " persistentSave=" +
                        capabilities.hasPersistentSave + " dualScreen=" +
                        capabilities.dualScreen);
            } catch (Throwable failure) {
                releaseSecondaryDisplay();
                closeHost();
                Log.e(TAG, "Unable to prepare adapter engine=" + entry.id +
                        " system=" + request.systemId, failure);
                callback.onSessionError("Lucent could not start " + entry.id + ".", failure);
            }
        });
    }

    private void startRenderThread() {
        Thread thread = new Thread(this::renderLoop, "lucent-phase3-render");
        thread.setDaemon(true);
        renderThread = thread;
        thread.start();
    }

    /** The single render owner: every adapter surface/frame/audio call runs here. */
    private void renderLoop() {
        try {
            while (prepared && !stopping.get() && !released.get()) {
                Surface current = surface;
                if (!resumeRequested || current == null || !current.isValid()) {
                    sleepQuietly(8);
                    continue;
                }
                NativeAdapterHost active = host;
                if (active == null) break;
                if (!started) {
                    active.start(current, secondarySurface);
                    started = true;
                    ensureAudioTrack();
                }
                active.runFrame();
                drainAudioToTrack(active);
            }
        } catch (Throwable failure) {
            if (stopping.get() || released.get()) {
                Log.w(TAG, "Late render callback during quiesced stop engine=" +
                        entry.id, failure);
                return;
            }
            prepared = false;
            Listener callback = listener;
            releaseSecondaryDisplay();
            Log.e(TAG, "Adapter render loop stopped engine=" + entry.id, failure);
            if (callback != null)
                callback.onSessionError("The native adapter stopped.", failure);
        }
    }

    @Override public void attachSurface(Surface value, int width, int height) {
        voidDimensions(width, height);
        surface = value;
        NativeAdapterHost active = host;
        if (prepared && started && active != null && value != null && value.isValid())
            runOnRenderThread(() -> active.surfaceRecreated(value, secondarySurface));
    }

    @Override public void resizeSurface(int width, int height) {
        voidDimensions(width, height);
        Surface current = surface;
        NativeAdapterHost active = host;
        if (prepared && started && active != null && current != null && current.isValid())
            runOnRenderThread(() -> active.surfaceRecreated(current, secondarySurface));
    }

    @Override public void detachSurface() {
        surface = null;
    }

    @Override public void onSecondarySurfaceAvailable(Surface value, int width, int height) {
        voidDimensions(width, height);
        secondarySurface = value;
        NativeAdapterHost active = host;
        Surface current = surface;
        if (prepared && started && active != null && current != null && current.isValid())
            runOnRenderThread(() -> active.surfaceRecreated(current, value));
    }

    @Override public void onSecondarySurfaceDestroyed() {
        secondarySurface = null;
        NativeAdapterHost active = host;
        Surface current = surface;
        if (prepared && started && active != null && current != null && current.isValid())
            runOnRenderThread(() -> active.surfaceRecreated(current, null));
    }

    @Override public void onSecondaryTouch(float normalizedX, float normalizedY,
                                           boolean pressed) {
        NativeAdapterHost active = host;
        if (active == null || !started) return;
        active.setControl(19, normalizedX);       // LUCENT_PAD_TOUCH_X
        active.setControl(20, normalizedY);       // LUCENT_PAD_TOUCH_Y
        active.setControl(21, pressed ? 1f : 0f); // LUCENT_PAD_TOUCH_PRESSED
    }

    @Override public void resume() {
        resumeRequested = true;
        AudioTrack audio = audioTrack;
        if (audio != null) try { audio.play(); } catch (IllegalStateException ignored) {}
    }

    @Override public void pause(PauseReason reason) {
        resumeRequested = false;
        NativeAdapterHost active = host;
        if (started && active != null) runOnRenderThread(active::pause);
        AudioTrack audio = audioTrack;
        if (audio != null) try { audio.pause(); } catch (IllegalStateException ignored) {}
    }

    @Override public void quiesceForExit() {
        resumeRequested = false;
        NativeAdapterHost active = host;
        if (started && active != null) runOnRenderThread(active::pause);
        AudioTrack audio = audioTrack;
        if (audio != null) try { audio.pause(); } catch (IllegalStateException ignored) {}
        Log.i(TAG, "Render loop quiesced before library reveal engine=" + entry.id +
                " system=" + (request == null ? "" : request.systemId));
    }

    @Override public boolean openControls() {
        if (!(context instanceof Activity) || inputRouter == null) return false;
        Activity activity = (Activity) context;
        activity.runOnUiThread(() -> new AlertDialog.Builder(context)
                .setTitle("Controls")
                .setMessage("Controller mapping for " + entry.id + " uses Lucent's shared layout.")
                .setPositiveButton("Done", null).show());
        return true;
    }

    @Override public boolean openRestoreHistory() {
        // Honest capability report: no Quick Resume, no restore timeline.
        return false;
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        NativeAdapterHost active = host;
        if (!started || active == null || inputRouter == null || event == null ||
                (event.getAction() != KeyEvent.ACTION_DOWN &&
                 event.getAction() != KeyEvent.ACTION_UP)) return false;
        GamepadDescriptor pad = device(event.getDeviceId());
        if (pad == null) { refreshDevices(); pad = device(event.getDeviceId()); }
        if (pad == null) return false;
        try {
            CanonicalControl control = inputRouter.resolve(pad,
                    InputSignal.key(event.getKeyCode()));
            int ordinal = controlOrdinal(control);
            if (ordinal < 0) return false;
            boolean pressed = event.getAction() == KeyEvent.ACTION_DOWN;
            active.setControl(ordinal, pressed ? 1f : 0f);
            return true;
        } catch (Exception ignored) { return false; }
    }

    @Override public boolean dispatchGenericMotionEvent(MotionEvent event) {
        NativeAdapterHost active = host;
        InputDevice inputDevice = event == null ? null : event.getDevice();
        if (!started || active == null || event.getAction() != MotionEvent.ACTION_MOVE ||
                inputRouter == null || inputDevice == null) return false;
        active.setControl(PAD_LSTICK_X, normalizeAxis(event, MotionEvent.AXIS_X));
        active.setControl(PAD_LSTICK_Y, normalizeAxis(event, MotionEvent.AXIS_Y));
        int rightX = inputDevice.getMotionRange(MotionEvent.AXIS_Z, event.getSource()) != null
                ? MotionEvent.AXIS_Z : MotionEvent.AXIS_RX;
        int rightY = inputDevice.getMotionRange(MotionEvent.AXIS_RZ, event.getSource()) != null
                ? MotionEvent.AXIS_RZ : MotionEvent.AXIS_RY;
        active.setControl(PAD_RSTICK_X, normalizeAxis(event, rightX));
        active.setControl(PAD_RSTICK_Y, normalizeAxis(event, rightY));
        return true;
    }

    @Override public boolean shouldShowOnScreenControls() {
        return inputRouter != null && inputRouter.shouldShowOnScreenControls();
    }

    @Override public boolean dispatchVirtualControl(CanonicalControl control, boolean pressed) {
        NativeAdapterHost active = host;
        int ordinal = controlOrdinal(control);
        if (!started || active == null || ordinal < 0 || !shouldShowOnScreenControls())
            return false;
        active.setControl(ordinal, pressed ? 1f : 0f);
        return true;
    }

    @Override public void stop(StopReason reason, Completion completion) {
        if (completion == null) throw new IllegalArgumentException("completion required");
        if (!stopping.compareAndSet(false, true)) {
            completion.complete();
            return;
        }
        resumeRequested = false;
        lifecycle.execute(() -> {
            try {
                joinRenderThread();
                NativeAdapterHost active = host;
                // has_quick_resume=false: flush normal saves and complete WITHOUT
                // claiming a Quick Resume snapshot.
                if (active != null && capabilities != null &&
                        capabilities.hasPersistentSave) {
                    runFlushSave(active);
                }
                detachSecondaryBeforeBlank();
                releaseAudio();
                closeHost();
                prepared = false;
                Listener callback = listener;
                if (callback != null) callback.onRestoreAvailabilityChanged(false);
                Log.i(TAG, "Adapter save flushed and session stopped engine=" +
                        entry.id + " system=" +
                        (request == null ? "" : request.systemId));
                completion.complete();
            } catch (Throwable failure) {
                Log.e(TAG, "Adapter stop teardown failed engine=" + entry.id +
                        " marker=save-failure", failure);
                try { releaseAudio(); } catch (Throwable ignored) {}
                try { closeHost(); } catch (Throwable ignored) {}
                prepared = false;
                completion.complete();
            }
        });
    }

    @Override public void release() {
        if (!released.compareAndSet(false, true)) return;
        resumeRequested = false;
        lifecycle.execute(() -> {
            joinRenderThread();
            detachSecondaryBeforeBlank();
            releaseAudio();
            closeHost();
            prepared = false;
        });
        lifecycle.shutdown();
    }

    private void runFlushSave(NativeAdapterHost active) {
        try {
            active.flushSave();
            Log.i(TAG, "Adapter durable save committed engine=" + entry.id);
        } catch (Throwable failure) {
            Log.w(TAG, "Adapter save was not updated for " + entry.id +
                    " marker=save-failure", failure);
        }
    }

    private boolean hasRestorableState() {
        return saveRamFile != null && saveRamFile.isFile();
    }

    private void ensureAudioTrack() {
        if (audioTrack != null) return;
        AudioTrack track = createAudioTrack(OUTPUT_SAMPLE_RATE);
        if (track == null) {
            Log.w(TAG, "Adapter audio unavailable engine=" + entry.id);
            return;
        }
        audioTrack = track;
        if (resumeRequested) try { track.play(); } catch (IllegalStateException ignored) {}
    }

    private void drainAudioToTrack(NativeAdapterHost active) {
        AudioTrack audio = audioTrack;
        if (audio == null) return;
        short[] samples = active.drainAudio(2048);
        if (samples.length == 0) return;
        try {
            int written = Build.VERSION.SDK_INT >= 23
                    ? audio.write(samples, 0, samples.length, AudioTrack.WRITE_NON_BLOCKING)
                    : audio.write(samples, 0, samples.length);
            if (written < 0) throw new IllegalStateException(
                    "AudioTrack write failed with code " + written);
        } catch (Throwable failure) {
            if (audioFailureReported.compareAndSet(false, true)) {
                Listener callback = listener;
                if (callback != null) callback.onSessionError(
                        "Lucent lost audio output for this game.", failure);
            }
        }
    }

    @SuppressWarnings("deprecation")
    private AudioTrack createAudioTrack(int rate) {
        int minimum = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minimum <= 0) return null;
        int bufferBytes = Math.max(minimum, rate / 5 * 4);
        AudioTrack track = new AudioTrack(AudioManager.STREAM_MUSIC, rate,
                AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes, AudioTrack.MODE_STREAM);
        if (track.getState() == AudioTrack.STATE_INITIALIZED) return track;
        track.release();
        return null;
    }

    private void releaseAudio() {
        AudioTrack audio = audioTrack;
        audioTrack = null;
        audioFailureReported.set(false);
        if (audio == null) return;
        try { audio.stop(); } catch (IllegalStateException ignored) {}
        audio.release();
    }

    private void closeHost() {
        NativeAdapterHost active = host;
        host = null;
        started = false;
        if (active == null) return;
        try { active.stop(); } catch (Throwable ignored) {}
        try { active.close(); } catch (Throwable ignored) {}
    }

    private void joinRenderThread() {
        prepared = false;
        Thread thread = renderThread;
        renderThread = null;
        if (thread == null) return;
        try { thread.join(2_000L); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    private void runOnRenderThread(Runnable action) {
        // Surface changes are rare and must land on the render owner; when the
        // loop is idle-waiting this posts through the lifecycle executor, which
        // never races an in-flight frame because frames run on the render thread
        // only while resumeRequested and a valid surface are set.
        if (Thread.currentThread() == renderThread) { action.run(); return; }
        boolean wasResuming = resumeRequested;
        resumeRequested = false;
        try { action.run(); }
        catch (Throwable failure) { Log.w(TAG, "Adapter surface update failed", failure); }
        finally { resumeRequested = wasResuming; }
    }

    private void detachSecondaryBeforeBlank() {
        if (!secondaryDisplayRequested) return;
        secondarySurface = null;
        releaseSecondaryDisplay();
    }

    private void releaseSecondaryDisplay() {
        if (!secondaryDisplayRequested) return;
        secondaryDisplayRequested = false;
        secondarySurface = null;
        SecondaryGameplaySurfaceRouter.release(appContext, this);
    }

    private synchronized void refreshDevices() {
        devices = AndroidDeviceScanner.scan();
        if (inputRouter != null) inputRouter.updateDevices(devices);
    }

    private synchronized GamepadDescriptor device(int deviceId) {
        for (GamepadDescriptor descriptor : devices)
            if (descriptor.deviceId == deviceId) return descriptor;
        return null;
    }

    private static boolean isWiiUSystem(String systemId) {
        String value = systemId == null ? "" : systemId.trim().toLowerCase(Locale.US);
        return "wiiu".equals(value) || "wii-u".equals(value);
    }

    private static int controlOrdinal(CanonicalControl control) {
        if (control == null) return -1;
        switch (control) {
            case SOUTH: return PAD_A;
            case EAST: return PAD_B;
            case WEST: return PAD_X;
            case NORTH: return PAD_Y;
            case L1: return PAD_L;
            case R1: return PAD_R;
            case L2: return PAD_ZL;
            case R2: return PAD_ZR;
            case DPAD_UP: return PAD_DPAD_UP;
            case DPAD_DOWN: return PAD_DPAD_DOWN;
            case DPAD_LEFT: return PAD_DPAD_LEFT;
            case DPAD_RIGHT: return PAD_DPAD_RIGHT;
            case START: return PAD_START;
            case SELECT: return PAD_SELECT;
            case GUIDE: return PAD_HOME;
            default: return -1;
        }
    }

    private static float normalizeAxis(MotionEvent event, int axis) {
        float value = event.getAxisValue(axis);
        float magnitude = Math.abs(value);
        if (magnitude <= AXIS_DEAD_ZONE) return 0f;
        return Math.copySign(Math.min(1f,
                (magnitude - AXIS_DEAD_ZONE) / (1f - AXIS_DEAD_ZONE)), value);
    }

    private static void sleepQuietly(long millis) {
        try { Thread.sleep(millis); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    private static File resolveGameFile(Uri uri) throws Exception {
        if (uri == null) throw new IllegalArgumentException("content URI is missing");
        String raw = "file".equals(uri.getScheme()) ? uri.getPath() :
                uri.getQueryParameter("path");
        if (raw == null || raw.trim().isEmpty())
            throw new IllegalArgumentException("content URI does not expose a local file");
        File file = new File(raw).getCanonicalFile();
        String path = file.getPath();
        if (!(path.startsWith("/storage/") || path.startsWith("/mnt/media_rw/")) ||
                !file.isFile())
            throw new IllegalArgumentException("game is outside approved Android storage");
        return file;
    }

    private static String sha256Short(File file) throws Exception {
        java.security.MessageDigest digest =
                java.security.MessageDigest.getInstance("SHA-256");
        java.io.InputStream input = new java.io.FileInputStream(file);
        try {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0)
                if (count > 0) digest.update(buffer, 0, count);
        } finally { input.close(); }
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest())
            result.append(String.format(Locale.US, "%02x", value & 0xff));
        return result.substring(0, 32);
    }

    private static void voidDimensions(int width, int height) {
        if (width < 0 || height < 0)
            throw new IllegalArgumentException("surface dimensions cannot be negative");
    }
}
