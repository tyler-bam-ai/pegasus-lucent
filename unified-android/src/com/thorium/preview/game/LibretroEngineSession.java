package com.thorium.preview.game;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.Surface;

import com.thorium.lucent.input.CanonicalControl;
import com.thorium.lucent.input.DeviceCatalog;
import com.thorium.lucent.input.FileRemapStore;
import com.thorium.lucent.input.GamepadDescriptor;
import com.thorium.lucent.input.InputRouter;
import com.thorium.lucent.input.InputSignal;
import com.thorium.lucent.input.LibretroJoypadLayout;
import com.thorium.lucent.input.android.AndroidDeviceScanner;
import com.thorium.lucent.input.android.AndroidGamingDeviceDetector;
import com.thorium.lucent.state.CheckpointScheduler;
import com.thorium.lucent.state.DurableBlobStore;
import com.thorium.lucent.state.StateIdentity;
import com.thorium.lucent.state.StateLoadResult;
import com.thorium.lucent.state.StateSnapshot;
import com.thorium.lucent.state.StateVault;
import com.thorium.lucent.state.StateVaultWorker;
import com.thorium.lucent.timing.AbsoluteFramePacer;
import com.thorium.lucent.timing.LatestValueMailbox;
import com.thorium.lucent.video.DualScreenLayout;
import com.thorium.preview.LibretroHost;
import com.thorium.preview.SecondaryGameplaySurfaceRouter;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** One release-qualified libretro core behind Lucent's engine-neutral shell. */
public final class LibretroEngineSession implements EngineSession,
        SecondaryGameplaySurfaceRouter.Listener {
    private static final String TAG = "LucentEngine";
    private static final long FRAME_NS = 1_000_000_000L / 60L;
    private static final float AXIS_PRESS = 0.55f;
    private static final int MAX_VIDEO_DIMENSION = 8192;
    private static final long MAX_VIDEO_PIXELS = 16L * 1024L * 1024L;
    private static final int MAX_SAVE_RAM_BYTES = 64 * 1024 * 1024;
    private static final long QUALIFICATION_WARMUP_FRAMES = 120L;
    private static final long QUALIFICATION_MEASURED_FRAMES = 300L;

    private final Context context;
    private final Context appContext;
    private final LibretroEngineSpec entry;
    private final ExecutorService lifecycle = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "lucent-engine-lifecycle");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean stopping = new AtomicBoolean(false);
    private final AtomicBoolean released = new AtomicBoolean(false);
    private final Object runLock = new Object();
    private final Object lifecycleSubmissionLock = new Object();
    private final LatestValueMailbox<LibretroHost.VideoFrame> videoMailbox =
            new LatestValueMailbox<>();

    private volatile Listener listener;
    private volatile LibretroHost host;
    private volatile boolean running;
    private volatile boolean prepared;
    private volatile Thread frameThread;
    private volatile Thread renderThread;
    private volatile GameLaunchRequest request;
    private volatile Surface surface;
    private volatile int surfaceWidth;
    private volatile int surfaceHeight;
    private volatile Surface secondarySurface;
    private volatile int secondarySurfaceWidth;
    private volatile int secondarySurfaceHeight;
    private volatile boolean secondaryGameplayRequested;
    private StateVault vault;
    private StateVaultWorker vaultWorker;
    private StateIdentity identity;
    private CheckpointScheduler checkpointScheduler;
    private InputRouter inputRouter;
    private List<GamepadDescriptor> devices = new ArrayList<>();
    private long activeTickMillis;
    private long framePeriodNs = FRAME_NS;
    private int lastVideoSequence = -1;
    private boolean frameEvidenceLogged;
    private boolean lowerDrawEvidenceLogged;
    private Bitmap frameBitmap;
    private int[] frameColors;
    // Written on the lifecycle thread during prepare and read on the frame
    // thread. Thread.start() already publishes them, but volatile keeps any
    // future writer (audio recovery, restore) safe without re-auditing.
    private volatile AudioTrack audioTrack;
    private short[] pendingAudio;
    private int pendingAudioOffset;
    private int primedAudioSamples;
    private volatile int audioPrimeSamplesTarget;
    private volatile boolean audioStartedOnce;
    private boolean audioSilenceWarned;
    private long audioStartedAtMillis;
    private boolean steadyAudioBufferApplied;
    private File saveRamFile;
    private long qualificationFrameCount;
    private long qualificationStartedNanos;
    private long qualificationAudioFramesWritten;
    private long qualificationAudioFramesAtWindowStart;
    private boolean qualificationInputLogged;

    public LibretroEngineSession(Context context, InternalEngineCatalog.Entry entry) {
        this(context, LibretroEngineSpec.phaseOne(entry));
    }

    LibretroEngineSession(Context context, LibretroEngineSpec entry) {
        if (context == null || entry == null)
            throw new IllegalArgumentException("context and engine entry are required");
        this.context = context;
        this.appContext = context.getApplicationContext();
        this.entry = entry;
    }

    @Override public void prepare(GameLaunchRequest launch, Listener callback) {
        if (launch == null || callback == null) throw new IllegalArgumentException("launch required");
        listener = callback;
        request = launch;
        lifecycle.execute(() -> {
            try {
                if (!entry.id.equals(launch.engineId) || !entry.supports(launch.systemId))
                    throw new IllegalStateException("Engine is not approved for " + launch.systemId);
                if (!entry.isInstalled(appContext))
                    throw new IllegalStateException("Approved core is not present in this build");

                File game = resolveGameFile(launch.contentUri);
                // Content identity, rather than its current storage path, owns
                // saves, remaps, and restore history. Moving a ROM between
                // internal storage and an SD card must not orphan progress.
                String romSha = sha256(game);
                File system = entry.installSystem(appContext, launch.systemId);
                String saveRoot = entry.phaseTwoQualification &&
                        !launch.qualificationSession.isEmpty() ?
                        "engine-saves-qa/" + launch.qualificationSession :
                        "engine-saves";
                File saves = new File(appContext.getFilesDir(),
                        saveRoot + "/" + storageKey(romSha));
                if (!saves.isDirectory() && !saves.mkdirs())
                    throw new IllegalStateException("Cannot create game save directory");
                String firmware = firmwareFingerprint(system, entry.firmwareRequired,
                        entry.acceptedFirmwareHashes);
                LibretroHost opened = new LibretroHost(entry.coreFile,
                        entry.coreFile.getParentFile(), system, saves);
                try {
                    opened.loadGame(game);
                    saveRamFile = new File(saves, "save-ram.bin");
                    restoreSaveRam(opened, saveRamFile);
                    LibretroHost.AvInfo av = opened.avInfo();
                    if (av.framesPerSecond > 1.0 && av.framesPerSecond < 1000.0)
                        framePeriodNs = Math.max(1L,
                                (long) (1_000_000_000.0 / av.framesPerSecond));
                    audioTrack = createAudioTrack(av.sampleRate);
                    audioPrimeSamplesTarget = startupAudioBufferBytes(av.sampleRate) / 2;
                    if (audioTrack == null)
                        // A dead track must be loud in the log: every later
                        // write silently no-ops and the game plays mute.
                        Log.w(TAG, "Audio track unavailable engine=" + entry.id +
                                " rate=" + av.sampleRate);
                    else
                        Log.i(TAG, "Audio track ready engine=" + entry.id +
                                " rate=" + (int) Math.round(av.sampleRate) +
                                " primeTargetSamples=" + audioPrimeSamplesTarget);
                    long previousActive = 0L;
                    if (!"scummvm".equals(entry.id)) {
                        String engineIdentity = entry.sourceCommit + ":sha256:" +
                                entry.coreArtifactSha256;
                        if (entry.phaseTwoQualification &&
                                !launch.qualificationSession.isEmpty())
                            engineIdentity += ":qa:" + launch.qualificationSession;
                        identity = new StateIdentity(romSha, romSha, entry.id,
                                engineIdentity,
                                "serialize-v" + entry.stateCompatibilityVersion,
                                firmware);
                        vault = StateVault.shared(new File(appContext.getFilesDir(), "state-vault"));
                        vaultWorker = new StateVaultWorker(vault);
                        StateLoadResult quick = vault.loadQuickResume(identity);
                        if (quick.status == StateLoadResult.Status.OK) {
                            try {
                                opened.unserialize(quick.state);
                                previousActive = quick.snapshot.metadata.activePlayMillis;
                                Log.i(TAG, "Quick Resume restored engine=" + entry.id +
                                        " system=" + launch.systemId);
                            } catch (Throwable rejectedState) {
                                // A core can reject a state even after Lucent validates its
                                // identity and checksum. Boot normally and retain it for
                                // recovery instead of making the game unlaunchable.
                            }
                        }
                        checkpointScheduler = new CheckpointScheduler(
                                CheckpointScheduler.DEFAULT_INTERVAL_MILLIS, previousActive);
                    }
                    inputRouter = new InputRouter(DeviceCatalog.standard(),
                            new FileRemapStore(new File(appContext.getFilesDir(),
                                    "controls/remaps.properties")),
                            AndroidGamingDeviceDetector.isKnownGamingHandheld());
                    inputRouter.setGame(launch.systemId, romSha);
                    refreshDevices();
                    qualificationFrameCount = 0L;
                    qualificationStartedNanos = 0L;
                    qualificationAudioFramesWritten = 0L;
                    qualificationAudioFramesAtWindowStart = 0L;
                    qualificationInputLogged = false;
                    host = opened;
                    secondaryGameplayRequested = isDualScreenSystem(launch.systemId) &&
                            SecondaryGameplaySurfaceRouter.request(
                                    appContext, launch.systemId, this);
                    prepared = true;
                    startRenderThread();
                    startFrameThread();
                    notifyRestoreAvailability();
                    callback.onSessionReady();
                } catch (Throwable failure) {
                    opened.close();
                    throw failure;
                }
            } catch (Throwable failure) {
                SecondaryGameplaySurfaceRouter.release(appContext, this);
                Log.e(TAG, "Unable to prepare " + launch.engineId + " for "
                        + launch.systemId, failure);
                callback.onSessionError("Lucent could not start " +
                        (launch.gameTitle.isEmpty() ? "this game" : launch.gameTitle) + ".", failure);
            }
        });
    }

    @Override public void attachSurface(Surface surface, int width, int height) {
        this.surface = surface;
        surfaceWidth = width;
        surfaceHeight = height;
    }

    @Override public void resizeSurface(int width, int height) {
        surfaceWidth = width;
        surfaceHeight = height;
    }
    @Override public void detachSurface() { surface = null; }

    @Override public void onSecondarySurfaceAvailable(
            Surface next, int width, int height) {
        secondarySurface = next;
        secondarySurfaceWidth = width;
        secondarySurfaceHeight = height;
        Log.i(TAG, "LucentLowerScreen secondary surface available engine=" + entry.id +
                " system=" + (request == null ? "?" : request.systemId) +
                " valid=" + (next != null && next.isValid()) +
                " size=" + width + "x" + height +
                " secondaryGameplayRequested=" + secondaryGameplayRequested);
    }

    @Override public void onSecondarySurfaceDestroyed() {
        Log.i(TAG, "LucentLowerScreen secondary surface destroyed engine=" + entry.id +
                " system=" + (request == null ? "?" : request.systemId) +
                " secondaryGameplayRequested=" + secondaryGameplayRequested);
        secondarySurface = null;
        secondarySurfaceWidth = 0;
        secondarySurfaceHeight = 0;
        lowerDrawEvidenceLogged = false;
    }

    @Override public void onSecondaryTouch(
            float normalizedX, float normalizedY, boolean pressed) {
        LibretroHost active = host;
        if (!prepared || active == null || !secondaryGameplayRequested) return;
        active.setPointer(0, DualScreenLayout.pointerCoordinate(normalizedX),
                DualScreenLayout.lowerScreenPointerY(normalizedY), pressed);
    }

    @Override public void resume() {
        if (!prepared || stopping.get()) return;
        LibretroHost active = host;
        if (active != null) active.resume();
        AudioTrack audio = audioTrack;
        if (audio != null && audioStartedOnce)
            try { audio.play(); } catch (IllegalStateException ignored) {}
        synchronized (runLock) {
            activeTickMillis = SystemClock.elapsedRealtime();
            running = true;
            runLock.notifyAll();
        }
    }

    @Override public void pause(PauseReason reason) {
        synchronized (runLock) { running = false; }
        LibretroHost active = host;
        if (active != null) active.pause();
        AudioTrack audio = audioTrack;
        if (audio != null) try { audio.pause(); } catch (IllegalStateException ignored) {}
        if (reason == PauseReason.ANDROID_BACKGROUND && prepared && !stopping.get())
            saveQuickResume(false, null);
    }

    @Override public void quiesceForExit() {
        // LibretroHost serializes access to retro_run/pause, so this returns
        // only after any frame already in progress has left the core.
        pause(PauseReason.LUCENT_MENU);
    }

    @Override public boolean openControls() {
        if (!(context instanceof Activity) || inputRouter == null) return false;
        GamepadDescriptor pad = inputRouter.activeDevice();
        if (pad == null) {
            ((Activity) context).runOnUiThread(() -> new AlertDialog.Builder(context)
                    .setTitle("Controls")
                    .setMessage("No physical controller is connected. Lucent will use touch controls when available.")
                    .setPositiveButton("Done", null).show());
            return true;
        }
        ((Activity) context).runOnUiThread(() -> showControlsMenu(pad));
        return true;
    }

    @Override public boolean openRestoreHistory() {
        if (!(context instanceof Activity) || vault == null || identity == null) return false;
        final List<StateSnapshot> history = restorableHistory();
        if (history.isEmpty()) return false;
        final String[] labels = new String[history.size()];
        DateFormat formatter = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
        for (int i = 0; i < history.size(); i++) {
            StateSnapshot snapshot = history.get(i);
            labels[i] = formatter.format(new Date(snapshot.metadata.createdAtMillis)) +
                    "  •  " + formatPlayTime(snapshot.metadata.activePlayMillis);
        }
        ((Activity) context).runOnUiThread(() -> new AlertDialog.Builder(context)
                .setTitle("Restore earlier point")
                .setItems(labels, (dialog, which) -> restore(history.get(which)))
                .setNegativeButton("Cancel", null)
                .show());
        return true;
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (!prepared || inputRouter == null || event == null) return false;
        GamepadDescriptor device = device(event.getDeviceId());
        if (device == null) {
            refreshDevices();
            device = device(event.getDeviceId());
        }
        if (device == null) return false;
        try {
            CanonicalControl control = inputRouter.resolve(device,
                    InputSignal.key(event.getKeyCode()));
            int retroId = joypadId(control);
            LibretroHost active = host;
            if (retroId < 0 || active == null) return false;
            active.setJoypadButton(0, retroId, event.getAction() != KeyEvent.ACTION_UP);
            if (!qualificationInputLogged && event.getAction() == KeyEvent.ACTION_DOWN) {
                qualificationInputLogged = true;
                restartQualificationPacingWindow();
                Log.i(TAG, "Input consumed engine=" + entry.id +
                        " system=" + request.systemId + " control=" + control.name());
            }
            return true;
        } catch (Exception ignored) { return false; }
    }

    @Override public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (!prepared || inputRouter == null || event == null ||
                event.getAction() != MotionEvent.ACTION_MOVE) return false;
        GamepadDescriptor device = device(event.getDeviceId());
        if (device == null) {
            refreshDevices();
            device = device(event.getDeviceId());
        }
        if (device == null) return false;
        boolean consumed = false;
        LibretroHost active = host;
        if (active != null) {
            consumed |= dispatchAnalogStick(event, active, MotionEvent.AXIS_X,
                    MotionEvent.AXIS_Y, 0);
            // Android gamepads normally report the right stick as Z/RZ. RX/RY
            // is the established fallback; do not send both because an idle
            // duplicate pair would overwrite the active pair with zero.
            if (device.axes.contains(MotionEvent.AXIS_Z) &&
                    device.axes.contains(MotionEvent.AXIS_RZ)) {
                consumed |= dispatchAnalogStick(event, active, MotionEvent.AXIS_Z,
                        MotionEvent.AXIS_RZ, 1);
            } else {
                consumed |= dispatchAnalogStick(event, active, MotionEvent.AXIS_RX,
                        MotionEvent.AXIS_RY, 1);
            }
        }
        for (int axis : device.axes) {
            float value = event.getAxisValue(axis);
            consumed |= dispatchAxis(device, axis, -1, value <= -AXIS_PRESS);
            consumed |= dispatchAxis(device, axis, 1, value >= AXIS_PRESS);
        }
        return consumed;
    }

    private boolean dispatchAnalogStick(MotionEvent event, LibretroHost active,
                                        int xAxis, int yAxis, int retroIndex) {
        InputDevice inputDevice = event.getDevice();
        if (inputDevice == null ||
                inputDevice.getMotionRange(xAxis, event.getSource()) == null ||
                inputDevice.getMotionRange(yAxis, event.getSource()) == null) return false;
        active.setAnalogAxis(0, retroIndex, 0, normalizedAxis(event, xAxis));
        active.setAnalogAxis(0, retroIndex, 1, normalizedAxis(event, yAxis));
        return true;
    }

    private static float normalizedAxis(MotionEvent event, int axis) {
        float value = event.getAxisValue(axis);
        InputDevice device = event.getDevice();
        InputDevice.MotionRange range = device == null ? null
                : device.getMotionRange(axis, event.getSource());
        float flat = range == null ? 0.08f : Math.max(0f, range.getFlat());
        float magnitude = Math.abs(value);
        if (magnitude <= flat) return 0f;
        float extent = range == null ? 1f
                : value < 0f ? Math.abs(range.getMin()) : Math.abs(range.getMax());
        float normalized = (magnitude - flat) / Math.max(0.0001f, extent - flat);
        normalized = Math.max(0f, Math.min(1f, normalized));
        return Math.copySign(normalized, value);
    }

    @Override public boolean shouldShowOnScreenControls() {
        return inputRouter != null && inputRouter.shouldShowOnScreenControls();
    }

    @Override public boolean dispatchVirtualControl(CanonicalControl control, boolean pressed) {
        LibretroHost active = host;
        int id = joypadId(control);
        if (!prepared || active == null || id < 0 || !shouldShowOnScreenControls()) return false;
        active.setJoypadButton(0, id, pressed);
        return true;
    }

    @Override public void stop(StopReason reason, Completion completion) {
        if (completion == null) throw new IllegalArgumentException("completion required");
        synchronized (lifecycleSubmissionLock) {
            if (released.get() || !stopping.compareAndSet(false, true)) {
                completion.complete();
                return;
            }
            synchronized (runLock) { running = false; }
            LibretroHost active = host;
            if (active != null) active.pause();
            if ("scummvm".equals(entry.id)) {
                saveScummvmExit(completion);
                return;
            }
            saveQuickResume(true, completion);
        }
    }

    private void saveScummvmExit(Completion completion) {
        synchronized (lifecycleSubmissionLock) {
            if (released.get() || lifecycle.isShutdown()) return;
            try {
                lifecycle.execute(() -> {
                    try {
                        LibretroHost active = host;
                        if (active == null)
                            throw new IllegalStateException("ScummVM host is unavailable");
                        active.unloadGame();
                        prepared = false;
                        completion.complete();
                    } catch (Throwable failure) {
                        stopping.set(false);
                        LibretroHost active = host;
                        if (active != null) try { active.resume(); }
                        catch (Throwable ignored) {}
                        synchronized (runLock) {
                            running = true;
                            runLock.notifyAll();
                        }
                        Listener callback = listener;
                        if (callback != null) callback.onSessionStopRejected(
                                "ScummVM could not save at this point. The game is still running; " +
                                "try held-Stop again after returning to gameplay.", failure);
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException rejected) {
                stopping.set(false);
                Listener callback = listener;
                if (callback != null) callback.onSessionStopRejected(
                        "ScummVM could not start its exit save. The game remains open.", rejected);
            }
        }
    }

    @Override public void release() {
        synchronized (lifecycleSubmissionLock) {
            if (!released.compareAndSet(false, true)) return;
        }
        synchronized (runLock) {
            running = false;
            runLock.notifyAll();
        }
        SecondaryGameplaySurfaceRouter.release(appContext, this);
        secondaryGameplayRequested = false;
        secondarySurface = null;
        Thread frames = frameThread;
        if (frames != null) frames.interrupt();
        if (frames != null && frames != Thread.currentThread()) try { frames.join(2_000L); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        videoMailbox.close();
        Thread renderer = renderThread;
        if (renderer != null) renderer.interrupt();
        if (renderer != null && renderer != Thread.currentThread()) try {
            renderer.join(2_000L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        // This teardown is queued behind every lifecycle serialization task.
        // The host remains open until those tasks captured state and the vault
        // has atomically committed every queued write.
        synchronized (lifecycleSubmissionLock) {
            lifecycle.execute(() -> {
                StateVaultWorker worker = vaultWorker;
                if (worker != null) {
                    worker.close();
                    try { worker.awaitTermination(10L, TimeUnit.SECONDS); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                }
                AudioTrack audio = audioTrack;
                audioTrack = null;
                if (audio != null) {
                    try { audio.stop(); } catch (IllegalStateException ignored) {}
                    audio.release();
                }
                LibretroHost active = host;
                host = null;
                if (active != null) active.close();
            });
            lifecycle.shutdown();
        }
    }

    private void startFrameThread() {
        Thread thread = new Thread(() -> {
            AbsoluteFramePacer pacer = new AbsoluteFramePacer(framePeriodNs);
            boolean resetPacer = true;
            while (!released.get()) {
                synchronized (runLock) {
                    while (!running && !released.get()) {
                        resetPacer = true;
                        try { runLock.wait(); }
                        catch (InterruptedException interrupted) {
                            if (released.get()) return;
                        }
                    }
                }
                if (released.get()) return;
                if (resetPacer) {
                    pacer.reset(System.nanoTime());
                    resetPacer = false;
                }
                try {
                    LibretroHost active = host;
                    if (active != null) {
                        active.runFrame();
                        // Canvas submission and bitmap conversion can take several
                        // milliseconds on handheld displays. Keep them entirely off
                        // the emulation/audio clock. If rendering falls behind, skip
                        // an obsolete visual frame instead of slowing the game.
                        if (videoMailbox.isEmpty()) {
                            LibretroHost.VideoFrame newest = active.latestVideoFrame();
                            if (newest != null) videoMailbox.offer(newest);
                        }
                        presentAudio(pendingAudio == null ? active.drainAudio(2048) : null);
                        recordQualificationPacing();
                    }
                    long now = SystemClock.elapsedRealtime();
                    long elapsed = activeTickMillis == 0L ? 0L : Math.max(0L, now - activeTickMillis);
                    activeTickMillis = now;
                    if (checkpointScheduler != null &&
                            checkpointScheduler.advanceActivePlay(elapsed) > 0)
                        saveAutomatic();
                } catch (Throwable failure) {
                    running = false;
                    Listener callback = listener;
                    if (callback != null) callback.onSessionError(
                            "The internal engine stopped unexpectedly.", failure);
                }
                sleepUntilNextFrame(pacer.delayAfterFrame(System.nanoTime()));
            }
        }, "lucent-libretro-frames");
        thread.setDaemon(true);
        frameThread = thread;
        thread.start();
    }

    private void startRenderThread() {
        Thread thread = new Thread(() -> {
            while (!released.get()) {
                try {
                    LibretroHost.VideoFrame frame = videoMailbox.take();
                    if (frame == null) return;
                    presentVideo(frame);
                } catch (InterruptedException interrupted) {
                    if (released.get()) return;
                } catch (Throwable ignored) {
                    // A lost or replaced Surface may drop one visual frame. The
                    // core and audio clocks continue and the next frame retries.
                }
            }
        }, "lucent-libretro-render");
        thread.setDaemon(true);
        renderThread = thread;
        thread.start();
    }

    private static void sleepUntilNextFrame(long remainingNanos) {
        long deadline = System.nanoTime() + remainingNanos;
        while (remainingNanos > 0L) {
            try {
                Thread.sleep(remainingNanos / 1_000_000L,
                        (int) (remainingNanos % 1_000_000L));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            remainingNanos = deadline - System.nanoTime();
        }
    }

    private synchronized void restartQualificationPacingWindow() {
        qualificationFrameCount = 0L;
        qualificationStartedNanos = 0L;
        qualificationAudioFramesAtWindowStart = qualificationAudioFramesWritten;
    }

    private synchronized void recordQualificationPacing() {
        long now = System.nanoTime();
        ++qualificationFrameCount;
        if (qualificationFrameCount == QUALIFICATION_WARMUP_FRAMES) {
            qualificationStartedNanos = now;
            qualificationAudioFramesAtWindowStart = qualificationAudioFramesWritten;
            return;
        }
        if (qualificationFrameCount <
                QUALIFICATION_WARMUP_FRAMES + QUALIFICATION_MEASURED_FRAMES) return;
        long elapsed = Math.max(1L, now - qualificationStartedNanos);
        double measuredFps = QUALIFICATION_MEASURED_FRAMES *
                1_000_000_000.0 / elapsed;
        double targetFps = 1_000_000_000.0 / framePeriodNs;
        long measuredAudioFrames = Math.max(0L,
                qualificationAudioFramesWritten - qualificationAudioFramesAtWindowStart);
        // The playback head only advances when the mixer consumes PCM, so it
        // separates "samples written to a stalled track" from audible output.
        // Frames-written alone has already produced a false audio PASS.
        int audioHead = 0;
        AudioTrack audio = audioTrack;
        if (audio != null)
            try { audioHead = audio.getPlaybackHeadPosition(); }
            catch (IllegalStateException ignored) {}
        Log.i(TAG, String.format(Locale.ROOT,
                "Runtime telemetry engine=%s system=%s frames=%d elapsedMs=%d " +
                "measuredFps=%.3f targetFps=%.3f audioFrames=%d audioStarted=%s " +
                "audioHead=%d",
                entry.id, request.systemId, QUALIFICATION_MEASURED_FRAMES,
                elapsed / 1_000_000L, measuredFps, targetFps,
                measuredAudioFrames, audioStartedOnce, audioHead));
        if (!audioSilenceWarned && (!audioStartedOnce || audioHead == 0)) {
            audioSilenceWarned = true;
            Log.w(TAG, "Audio silent after measurement window engine=" + entry.id +
                    " trackPresent=" + (audio != null) +
                    " started=" + audioStartedOnce +
                    " primed=" + primedAudioSamples +
                    " primeTarget=" + audioPrimeSamplesTarget +
                    " head=" + audioHead);
        }
        // Keep emitting non-overlapping rolling windows. The physical gate may
        // reject a transiently interrupted first sample and require a later
        // sustained window without hiding persistently slow emulation.
        qualificationFrameCount = QUALIFICATION_WARMUP_FRAMES;
        qualificationStartedNanos = now;
        qualificationAudioFramesAtWindowStart = qualificationAudioFramesWritten;
    }

    private void saveAutomatic() {
        try {
            LibretroHost active = host;
            if (active == null || vaultWorker == null || identity == null) return;
            persistSaveRam(active);
            byte[] state = active.serialize();
            Future<StateSnapshot> write = vaultWorker.saveAutomatic(identity, state, null,
                    checkpointScheduler.totalActiveMillis());
            notifyWhenCommitted(write);
        } catch (Throwable ignored) {
            // A failed background checkpoint never interrupts gameplay. Quick
            // Resume will retry on background/exit and retains the last good state.
        }
    }

    private void saveQuickResume(boolean waitForCommit, Completion completion) {
        synchronized (lifecycleSubmissionLock) {
            if (released.get() || lifecycle.isShutdown()) {
                if (completion != null) completion.complete();
                return;
            }
            try {
                lifecycle.execute(() -> {
                    try {
                        LibretroHost active = host;
                        if (active != null && vaultWorker != null && identity != null) {
                            persistSaveRam(active);
                            byte[] state = active.serialize();
                            Future<StateSnapshot> write = vaultWorker.saveQuickResume(identity, state, null,
                                    checkpointScheduler == null ? 0L : checkpointScheduler.totalActiveMillis());
                            if (waitForCommit) {
                                write.get();
                                Log.i(TAG, "Quick Resume committed engine=" + entry.id +
                                        " system=" + request.systemId);
                            }
                            notifyRestoreAvailability();
                        }
                    } catch (Throwable failure) {
                        // Never strand the user in a game when a core cannot
                        // serialize; the previous verified Quick Resume remains
                        // untouched. But a swallowed failure must stay visible:
                        // runtime acceptance greps for this marker, and the
                        // listener surfaces it after the library returns.
                        Log.w(TAG, "Exit save failed engine=" + entry.id +
                                " system=" + request.systemId +
                                " marker=save-failure", failure);
                        Listener callback = listener;
                        if (waitForCommit && callback != null)
                            callback.onSessionStopRejected(
                                    "Lucent kept your previous Quick Resume point.",
                                    failure);
                    } finally {
                        if (completion != null) completion.complete();
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException rejected) {
                if (completion != null) completion.complete();
            }
        }
    }

    private void restore(StateSnapshot snapshot) {
        lifecycle.execute(() -> {
            StateLoadResult loaded = vault.loadSnapshot(identity, snapshot);
            if (loaded.status != StateLoadResult.Status.OK) return;
            LibretroHost active = host;
            if (active != null) {
                active.unserialize(loaded.state);
                flushAudioAfterRestore();
            }
        });
    }

    private void notifyWhenCommitted(final Future<StateSnapshot> write) {
        try {
            lifecycle.execute(() -> {
                try {
                    write.get();
                    notifyRestoreAvailability();
                } catch (Throwable ignored) {}
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {}
    }

    private List<StateSnapshot> restorableHistory() {
        List<StateSnapshot> all = vault.list(identity);
        List<StateSnapshot> history = new ArrayList<>();
        for (StateSnapshot snapshot : all)
            if (snapshot.metadata.kind != com.thorium.lucent.state.SnapshotKind.QUICK_RESUME)
                history.add(snapshot);
        return history;
    }

    private void notifyRestoreAvailability() {
        Listener callback = listener;
        if (callback != null && vault != null && identity != null)
            callback.onRestoreAvailabilityChanged(!restorableHistory().isEmpty());
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

    private boolean dispatchAxis(GamepadDescriptor device, int axis, int direction, boolean pressed) {
        try {
            CanonicalControl control = inputRouter.resolve(device, InputSignal.axis(axis, direction));
            int id = joypadId(control);
            LibretroHost active = host;
            if (id < 0 || active == null) return false;
            active.setJoypadButton(0, id, pressed);
            return true;
        } catch (Exception ignored) { return false; }
    }

    private void showControlsMenu(GamepadDescriptor pad) {
        String[] actions = {
                "Remap for " + request.systemId,
                "Remap for this game",
                "Reset " + request.systemId + " mapping",
                "Reset this game mapping"
        };
        new AlertDialog.Builder(context)
                .setTitle(pad.name)
                .setItems(actions, (dialog, which) -> {
                    if (which == 0 || which == 1) showControlPicker(pad, which == 1);
                    else try {
                        inputRouter.resetRemap(pad, which == 3);
                    } catch (Exception error) { showControlError(error); }
                })
                .setNegativeButton("Done", null)
                .show();
    }

    private void showControlPicker(GamepadDescriptor pad, boolean gameOnly) {
        final CanonicalControl[] controls = {
                CanonicalControl.DPAD_UP, CanonicalControl.DPAD_DOWN,
                CanonicalControl.DPAD_LEFT, CanonicalControl.DPAD_RIGHT,
                CanonicalControl.SOUTH, CanonicalControl.EAST,
                CanonicalControl.WEST, CanonicalControl.NORTH,
                CanonicalControl.L1, CanonicalControl.R1,
                CanonicalControl.L2, CanonicalControl.R2,
                CanonicalControl.START, CanonicalControl.SELECT
        };
        String[] labels = new String[controls.length];
        for (int i = 0; i < controls.length; i++)
            labels[i] = controls[i].name().replace('_', ' ');
        new AlertDialog.Builder(context)
                .setTitle("Choose a control")
                .setItems(labels, (dialog, which) -> captureControl(pad, controls[which], gameOnly))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void captureControl(GamepadDescriptor pad, CanonicalControl control, boolean gameOnly) {
        AlertDialog capture = new AlertDialog.Builder(context)
                .setTitle("Press a button for " + control.name().replace('_', ' '))
                .setMessage("The next controller button becomes this control.")
                .setNegativeButton("Cancel", null)
                .create();
        capture.setOnKeyListener((dialog, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() != 0)
                return true;
            try {
                java.util.EnumMap<CanonicalControl, InputSignal> mapping =
                        new java.util.EnumMap<>(CanonicalControl.class);
                mapping.putAll(gameOnly ? inputRouter.effectiveMapping(pad) :
                        inputRouter.effectiveSystemMapping(pad));
                mapping.put(control, InputSignal.key(keyCode));
                inputRouter.saveRemap(pad, gameOnly, mapping);
                dialog.dismiss();
            } catch (Exception error) {
                dialog.dismiss();
                showControlError(error);
            }
            return true;
        });
        capture.show();
    }

    private void showControlError(Throwable error) {
        new AlertDialog.Builder(context).setTitle("Controls")
                .setMessage("Lucent could not save this mapping.")
                .setPositiveButton("OK", null).show();
    }

    private void presentVideo(LibretroHost.VideoFrame frame) {
        Surface target = surface;
        if (frame == null || target == null || !target.isValid() ||
                frame.sequence == lastVideoSequence) return;
        int bytesPerPixel = frame.pixelFormat == 1 ? 4 : 2;
        long pixelCountLong = (long) frame.width * (long) frame.height;
        long minimumPitch = (long) frame.width * bytesPerPixel;
        long requiredBytes = (long) frame.pitch * (long) frame.height;
        if (frame.pixelFormat < 0 || frame.pixelFormat > 2 ||
                frame.width < 1 || frame.height < 1 ||
                frame.width > MAX_VIDEO_DIMENSION || frame.height > MAX_VIDEO_DIMENSION ||
                pixelCountLong > MAX_VIDEO_PIXELS || frame.pitch < minimumPitch ||
                requiredBytes < 1 || requiredBytes != frame.pixels.length) return;
        lastVideoSequence = frame.sequence;
        int pixelCount = (int) pixelCountLong;
        if (frameColors == null || frameColors.length != pixelCount)
            frameColors = new int[pixelCount];
        decodePixels(frame, frameColors);
        if (frameBitmap == null || frameBitmap.getWidth() != frame.width ||
                frameBitmap.getHeight() != frame.height) {
            if (frameBitmap != null) frameBitmap.recycle();
            frameBitmap = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888);
        }
        frameBitmap.setPixels(frameColors, 0, frame.width, 0, 0, frame.width, frame.height);
        boolean dualScreen = request != null && isDualScreenSystem(request.systemId);
        Rect primarySource = new Rect(0, 0, frame.width, frame.height);
        Rect secondarySource = null;
        if (dualScreen && frame.height >= 2 && (frame.height & 1) == 0) {
            DualScreenLayout.Region top = DualScreenLayout.top(frame.width, frame.height);
            DualScreenLayout.Region bottom = DualScreenLayout.bottom(frame.width, frame.height);
            primarySource = rectangle(top);
            secondarySource = rectangle(bottom);
        }
        boolean presented = drawFrame(target, surfaceWidth, surfaceHeight, primarySource);
        Surface lower = secondarySurface;
        if (secondarySource != null && lower != null && lower.isValid()) {
            boolean lowerPresented = drawFrame(
                    lower, secondarySurfaceWidth, secondarySurfaceHeight, secondarySource);
            if (!lowerDrawEvidenceLogged) {
                lowerDrawEvidenceLogged = true;
                Log.i(TAG, "LucentLowerScreen bottom crop drawn engine=" + entry.id +
                        " system=" + request.systemId +
                        " secondarySurfaceNotNull=" + true +
                        " isValid=" + lower.isValid() +
                        " secondarySurfaceSize=" + secondarySurfaceWidth + "x" +
                        secondarySurfaceHeight +
                        " crop=" + secondarySource.width() + "x" + secondarySource.height() +
                        " drawFrameReturned=" + lowerPresented);
            }
        } else if (dualScreen && !lowerDrawEvidenceLogged) {
            lowerDrawEvidenceLogged = true;
            String reason = secondarySource == null ? "secondarySource-null(frameHeightNotEvenPair)"
                    : lower == null ? "secondarySurface-null(never-attached)"
                    : "secondarySurface-invalid";
            Log.i(TAG, "LucentLowerScreen bottom crop SKIPPED engine=" + entry.id +
                    " system=" + request.systemId +
                    " reason=" + reason +
                    " secondarySurfaceNotNull=" + (lower != null) +
                    " isValid=" + (lower != null && lower.isValid()) +
                    " secondarySurfaceSize=" + secondarySurfaceWidth + "x" +
                    secondarySurfaceHeight +
                    " frameSize=" + frame.width + "x" + frame.height +
                    " secondaryGameplayRequested=" + secondaryGameplayRequested);
        }
        if (presented && !frameEvidenceLogged) {
            int nonblack = 0;
            for (int color : frameColors) if ((color & 0x00ffffff) != 0) ++nonblack;
            if (nonblack > 0) {
                frameEvidenceLogged = true;
                Log.i(TAG, "Core frame presented engine=" + entry.id +
                        " system=" + request.systemId + " sequence=" + frame.sequence +
                        " size=" + frame.width + "x" + frame.height +
                        " nonblack=" + nonblack);
            }
        }
    }

    private boolean drawFrame(Surface target, int requestedWidth, int requestedHeight,
                              Rect source) {
        Canvas canvas = null;
        boolean presented = false;
        try {
            canvas = target.lockCanvas(null);
            canvas.drawColor(android.graphics.Color.BLACK);
            int width = requestedWidth > 0 ? requestedWidth : canvas.getWidth();
            int height = requestedHeight > 0 ? requestedHeight : canvas.getHeight();
            float fit = Math.min((float) width / source.width(),
                    (float) height / source.height());
            boolean integerScale = fit >= 1f;
            float scale = integerScale ? Math.max(1f, (float)Math.floor(fit)) : fit;
            float drawWidth = source.width() * scale;
            float drawHeight = source.height() * scale;
            RectF destination = new RectF((width - drawWidth) / 2f,
                    (height - drawHeight) / 2f, (width + drawWidth) / 2f,
                    (height + drawHeight) / 2f);
            Paint paint = new Paint(Paint.DITHER_FLAG |
                    (integerScale ? 0 : Paint.FILTER_BITMAP_FLAG));
            canvas.drawBitmap(frameBitmap, source, destination, paint);
            presented = true;
        } catch (Throwable ignored) {
        } finally {
            if (canvas != null) try { target.unlockCanvasAndPost(canvas); }
            catch (Throwable ignored) { presented = false; }
        }
        return presented;
    }

    private static Rect rectangle(DualScreenLayout.Region region) {
        return new Rect(region.left, region.top, region.right, region.bottom);
    }

    private static boolean isDualScreenSystem(String systemId) {
        if (systemId == null) return false;
        String normalized = systemId.trim().toLowerCase(Locale.US);
        return "nds".equals(normalized) || "ds".equals(normalized);
    }

    private static void decodePixels(LibretroHost.VideoFrame frame, int[] output) {
        byte[] bytes = frame.pixels;
        int bytesPerPixel = frame.pixelFormat == 1 ? 4 : 2;
        for (int y = 0; y < frame.height; y++) {
            int source = y * frame.pitch;
            int target = y * frame.width;
            for (int x = 0; x < frame.width; x++, source += bytesPerPixel) {
                int red, green, blue;
                if (frame.pixelFormat == 1) { // RETRO_PIXEL_FORMAT_XRGB8888, little endian
                    blue = bytes[source] & 0xff;
                    green = bytes[source + 1] & 0xff;
                    red = bytes[source + 2] & 0xff;
                } else {
                    int value = (bytes[source] & 0xff) | ((bytes[source + 1] & 0xff) << 8);
                    if (frame.pixelFormat == 2) { // RGB565
                        red = ((value >> 11) & 31) * 255 / 31;
                        green = ((value >> 5) & 63) * 255 / 63;
                        blue = (value & 31) * 255 / 31;
                    } else { // 0RGB1555
                        red = ((value >> 10) & 31) * 255 / 31;
                        green = ((value >> 5) & 31) * 255 / 31;
                        blue = (value & 31) * 255 / 31;
                    }
                }
                output[target + x] = 0xff000000 | (red << 16) | (green << 8) | blue;
            }
        }
    }

    private void presentAudio(short[] samples) {
        AudioTrack audio = audioTrack;
        if (audio == null) return;
        if (pendingAudio == null && samples != null && samples.length > 0) {
            pendingAudio = samples;
            pendingAudioOffset = 0;
        }
        if (pendingAudio == null) return;
        try {
            int written = Build.VERSION.SDK_INT >= 23
                    ? audio.write(pendingAudio, pendingAudioOffset,
                            pendingAudio.length - pendingAudioOffset, AudioTrack.WRITE_NON_BLOCKING)
                    : audio.write(pendingAudio, pendingAudioOffset,
                            pendingAudio.length - pendingAudioOffset);
            if (written > 0) {
                pendingAudioOffset += written;
                qualificationAudioFramesWritten += written / 2L;
                if (!audioStartedOnce) primedAudioSamples += written;
            }
            if (pendingAudioOffset >= pendingAudio.length) {
                pendingAudio = null;
                pendingAudioOffset = 0;
            }
            if (!audioStartedOnce && running &&
                    primedAudioSamples >= audioPrimeSamplesTarget) {
                audio.play();
                audioStartedOnce = true;
                audioStartedAtMillis = SystemClock.elapsedRealtime();
                Log.i(TAG, "Audio playback started engine=" + entry.id +
                        " primedSamples=" + primedAudioSamples);
            }
            applySteadyAudioBuffer(audio);
        } catch (Throwable ignored) {}
    }

    private void applySteadyAudioBuffer(AudioTrack audio) {
        if (Build.VERSION.SDK_INT < 24 || !audioStartedOnce ||
                steadyAudioBufferApplied ||
                SystemClock.elapsedRealtime() - audioStartedAtMillis < 5_000L) return;
        int frames = steadyAudioBufferBytes(audio.getSampleRate()) / 4;
        if (frames > 0 && audio.setBufferSizeInFrames(frames) > 0)
            steadyAudioBufferApplied = true;
    }

    private void flushAudioAfterRestore() {
        pendingAudio = null;
        pendingAudioOffset = 0;
        primedAudioSamples = 0;
        audioStartedOnce = false;
        audioStartedAtMillis = 0L;
        steadyAudioBufferApplied = false;
        AudioTrack audio = audioTrack;
        if (audio == null) return;
        try {
            audio.pause();
            audio.flush();
            // Playback restarts only after fresh post-restore PCM is primed.
        } catch (Throwable ignored) {}
    }

    @SuppressWarnings("deprecation")
    private static AudioTrack createAudioTrack(double sampleRate) {
        int rate = (int) Math.round(sampleRate);
        if (rate < 8_000 || rate > 192_000) return null;
        int bufferBytes = startupAudioBufferBytes(sampleRate);
        if (bufferBytes <= 0) return null;
        AudioTrack track = new AudioTrack(AudioManager.STREAM_MUSIC, rate,
                AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
                // Start with a 200 ms warmup cushion, then reduce the effective
                // buffer to the platform minimum or roughly 50 ms.
                bufferBytes, AudioTrack.MODE_STREAM);
        return track.getState() == AudioTrack.STATE_INITIALIZED ? track : null;
    }

    private static int startupAudioBufferBytes(double sampleRate) {
        int rate = (int) Math.round(sampleRate);
        if (rate < 8_000 || rate > 192_000) return 0;
        int minimum = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT);
        return minimum <= 0 ? 0 : Math.max(minimum, rate / 5 * 4);
    }

    private static int steadyAudioBufferBytes(double sampleRate) {
        int rate = (int) Math.round(sampleRate);
        if (rate < 8_000 || rate > 192_000) return 0;
        int minimum = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT);
        return minimum <= 0 ? 0 : Math.max(minimum, rate / 20 * 4);
    }

    private void persistSaveRam(LibretroHost active) throws Exception {
        byte[] bytes = active.readSaveRam();
        if (bytes == null || saveRamFile == null) return;
        DurableBlobStore.write(saveRamFile, bytes, MAX_SAVE_RAM_BYTES);
    }

    private static void restoreSaveRam(LibretroHost active, File file) throws Exception {
        byte[] value = DurableBlobStore.read(file, MAX_SAVE_RAM_BYTES);
        if (value == null) return;
        // Some cores (notably mGBA) do not expose the cartridge's final save
        // memory type/size until emulation has begun. A stale early size must
        // never make a valid game unlaunchable or discard the saved bytes.
        // Prime only as many undisplayed frames as needed, then restore before
        // Quick Resume or the visible frame loop can start.
        for (int attempt = 0; attempt < 4; attempt++) {
            byte[] current = active.readSaveRam();
            if (current != null && current.length == value.length) {
                active.writeSaveRam(value);
                return;
            }
            active.runFrame();
        }
        Log.w(TAG, "Retaining save RAM for a future compatible core size; " +
                "storedBytes=" + value.length);
    }

    private int joypadId(CanonicalControl control) {
        return LibretroJoypadLayout.idFor(request.systemId, control);
    }

    private static File resolveGameFile(Uri uri) throws Exception {
        if (uri == null) throw new IllegalArgumentException("ROM URI is missing");
        String raw = "file".equals(uri.getScheme()) ? uri.getPath() : uri.getQueryParameter("path");
        if (raw == null || raw.trim().isEmpty())
            throw new IllegalArgumentException("ROM URI does not expose a local file");
        File file = new File(raw).getCanonicalFile();
        String path = file.getPath();
        if (!(path.startsWith("/storage/") || path.startsWith("/mnt/media_rw/")) || !file.isFile())
            throw new IllegalArgumentException("ROM is outside approved Android storage");
        return file;
    }

    private static String firmwareFingerprint(File directory, boolean required,
            List<String> acceptedHashes) throws Exception {
        File[] files = directory.listFiles(file -> file.isFile());
        if (acceptedHashes == null || acceptedHashes.isEmpty()) {
            if (required) throw new IllegalStateException(
                    "This engine has no audited firmware identities");
            return "firmware:none";
        }
        if (files == null || files.length == 0) {
            if (required) throw new IllegalStateException("This engine requires user-supplied firmware");
            return "firmware:none";
        }
        java.util.Arrays.sort(files, (left, right) -> left.getName().compareTo(right.getName()));
        MessageDigest identityDigest = MessageDigest.getInstance("SHA-256");
        int accepted = 0;
        for (File file : files) {
            String hash = sha256(file);
            if (!acceptedHashes.contains(hash)) continue;
            accepted++;
            identityDigest.update(file.getName().getBytes("UTF-8"));
            identityDigest.update(hash.getBytes("US-ASCII"));
        }
        if (required && accepted == 0)
            throw new IllegalStateException("Required firmware did not match an audited identity");
        return accepted == 0 ? "firmware:none" : "firmware:" + hex(identityDigest.digest());
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        InputStream input = new FileInputStream(file);
        try {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) if (count > 0)
                digest.update(buffer, 0, count);
        } finally { input.close(); }
        return hex(digest.digest());
    }

    private static String storageKey(String value) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(
                value.getBytes("UTF-8"))).substring(0, 32);
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(Locale.US, "%02x", value & 0xff));
        return result.toString();
    }

    private static String formatPlayTime(long millis) {
        long minutes = Math.max(0L, millis) / 60_000L;
        return minutes < 60 ? minutes + " min" : (minutes / 60) + "h " + (minutes % 60) + "m";
    }
}
