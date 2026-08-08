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
import com.thorium.lucent.input.LibretroJoypadLayout;
import com.thorium.lucent.input.android.AndroidDeviceScanner;
import com.thorium.lucent.input.android.AndroidGamingDeviceDetector;
import com.thorium.lucent.state.CheckpointScheduler;
import com.thorium.lucent.state.DurableBlobStore;
import com.thorium.lucent.state.SnapshotKind;
import com.thorium.lucent.state.QuickResumePolicy;
import com.thorium.lucent.state.StateIdentity;
import com.thorium.lucent.state.StateLoadResult;
import com.thorium.lucent.state.StateSnapshot;
import com.thorium.lucent.state.StateVault;
import com.thorium.lucent.video.DualScreenLayout;
import com.thorium.preview.ExperimentalGlesRenderLoop;
import com.thorium.preview.SecondaryGameplaySurfaceRouter;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit, qualification-only hardware-capable libretro route. */
final class PpssppGlesEngineSession implements EngineSession,
        SecondaryGameplaySurfaceRouter.Listener {
    private static final String TAG = "LucentPhase2Engine";
    private static final float AXIS_DEAD_ZONE = 0.08f;
    private static final float AXIS_PRESS = 0.55f;
    private static final int MAX_SAVE_RAM_BYTES = 64 * 1024 * 1024;

    private final Context context;
    private final Context appContext;
    private final LibretroEngineSpec entry;
    private final ExecutorService lifecycle = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "lucent-phase2-lifecycle");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean stopping = new AtomicBoolean(false);
    private final AtomicBoolean released = new AtomicBoolean(false);
    private final AtomicBoolean audioFailureReported = new AtomicBoolean(false);

    private volatile Listener listener;
    private volatile ExperimentalGlesRenderLoop renderLoop;
    private volatile Surface surface;
    private volatile Surface secondarySurface;
    private volatile boolean secondaryDisplayRequested;
    private volatile boolean prepared;
    private volatile boolean resumeRequested;
    private volatile AudioTrack audioTrack;
    private volatile byte[] pendingQuickResume;
    private final AtomicBoolean quickResumeApplied = new AtomicBoolean(false);
    private StateVault vault;
    private StateIdentity identity;
    private GameLaunchRequest request;
    private CheckpointScheduler checkpointScheduler;
    private InputRouter inputRouter;
    private List<GamepadDescriptor> devices = new ArrayList<>();
    private final AtomicBoolean checkpointPending = new AtomicBoolean(false);
    private File saveRamFile;
    private long activePlayMillis;
    private long activeStartedMillis;
    private long activeFrameTickMillis;
    private short[] pendingAudio;
    private int pendingAudioOffset;
    private int primedAudioSamples;
    private int audioPrimeSamplesTarget;
    private volatile boolean audioStartedOnce;
    private long audioStartedAtMillis;
    private boolean steadyAudioBufferApplied;
    private boolean audioSilenceWarned;
    private long presentedFrames;
    private long frameSampleStartedAtMillis;
    private long receivedAudioFrames;
    private long writtenAudioFrames;

    PpssppGlesEngineSession(Context context, Phase2QualificationCatalog.Entry entry) {
        this(context, LibretroEngineSpec.phaseTwo(entry));
    }

    PpssppGlesEngineSession(Context context, InternalEngineCatalog.Entry entry) {
        this(context, LibretroEngineSpec.phaseOne(entry));
    }

    private PpssppGlesEngineSession(Context context, LibretroEngineSpec entry) {
        if (context == null || entry == null)
            throw new IllegalArgumentException("context and Phase 2 entry are required");
        this.context = context;
        appContext = context.getApplicationContext();
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
                    throw new IllegalStateException("Phase 2 engine does not support " +
                            request.systemId);
                if (!entry.isInstalled(appContext))
                    throw new IllegalStateException("Internal engine identity changed");
                if (stopping.get() || released.get()) return;
                File game = resolveGameFile(request.contentUri);
                LibretroEngineSpec.SystemInstallation runtime =
                        entry.installRuntime(appContext, request.systemId);
                File system = runtime.directory;
                String gameHash = sha256(game);
                String saveRoot = request.qualificationSession.isEmpty() ?
                        "engine-saves" :
                        "engine-saves-qa/" + request.qualificationSession;
                File saves = new File(appContext.getFilesDir(),
                        saveRoot + "/" + entry.id + "/" +
                                gameHash.substring(0, 32));
                if (!saves.isDirectory() && !saves.mkdirs())
                    throw new IllegalStateException("Cannot create engine save directory");
                if (!request.qualificationSession.isEmpty())
                    Log.i(TAG, "Isolated qualification runtime state engine=" +
                            entry.id + " namespace=" + request.qualificationSession);
                String engineIdentity = entry.sourceCommit + ":sha256:" +
                        entry.coreArtifactSha256;
                if (!request.qualificationSession.isEmpty())
                    engineIdentity += ":qa:" + request.qualificationSession;
                identity = new StateIdentity(gameHash, gameHash, entry.id,
                        engineIdentity,
                        "phase2-libretro-serialize-v" +
                                entry.stateCompatibilityVersion,
                        runtime.firmwareIdentity);
                vault = StateVault.shared(new File(appContext.getFilesDir(),
                        "phase2-state-vault"));
                saveRamFile = new File(saves, "save-ram.bin");
                StateLoadResult quick = vault.loadQuickResume(identity);
                if (quick.status == StateLoadResult.Status.OK) {
                    pendingQuickResume = quick.state;
                    activePlayMillis = quick.snapshot.metadata.activePlayMillis;
                } else if (quick.status != StateLoadResult.Status.NOT_FOUND) {
                    Log.w(TAG, "Ignoring incompatible or invalid Quick Resume: " +
                            quick.status + " " + quick.detail);
                }
                checkpointScheduler = new CheckpointScheduler(
                        CheckpointScheduler.DEFAULT_INTERVAL_MILLIS, activePlayMillis);
                inputRouter = new InputRouter(DeviceCatalog.standard(),
                        new FileRemapStore(new File(appContext.getFilesDir(),
                                "controls/remaps.properties")),
                        AndroidGamingDeviceDetector.isKnownGamingHandheld());
                inputRouter.setGame(request.systemId, gameHash);
                refreshDevices();
                ExperimentalGlesRenderLoop.Listener renderListener =
                        new ExperimentalGlesRenderLoop.Listener() {
                            @Override public void onReady() {
                                lifecycle.execute(() -> handleReady(callback));
                            }

                            @Override public void onFramePresented() {
                                ExperimentalGlesRenderLoop active = renderLoop;
                                if (active != null) {
                                    // Hardware cores can defer their real game
                                    // boot until the first retro_run after a GL
                                    // context exists (Play! does). Restoring in
                                    // attachSurface lets that lazy boot erase a
                                    // successfully accepted snapshot. Apply it
                                    // at this first post-boot frame boundary.
                                    // PPSSPP's retro_serialize_size() pauses
                                    // its GLES emulation thread by design.
                                    // Probe readiness only while a snapshot is
                                    // genuinely pending; probing every frame
                                    // after the one-shot restore would pause
                                    // the core again with no unserialize call
                                    // left to restart it.
                                    if (pendingQuickResume != null &&
                                            active.stateReady())
                                        restoreQuickResume(active);
                                    presentAudio(pendingAudio == null
                                            ? active.drainAudio(2048) : null);
                                    recordFrameHealth();
                                    recordActiveProgress(active);
                                }
                            }

                            @Override public void onContextLost() {
                                Surface current = surface;
                                ExperimentalGlesRenderLoop active = renderLoop;
                                if (active != null && current != null && current.isValid())
                                    active.recreateSurface(current);
                            }

                            @Override public void onError(Throwable failure) {
                                if (stopping.get() || released.get()) {
                                    // Exit owns a render-thread quiescence barrier
                                    // before the UI transition. A callback already
                                    // in flight must not invalidate the prepared
                                    // state needed for the mandatory final snapshot;
                                    // serialize/commit still fails closed on its own.
                                    Log.w(TAG, "Late renderer callback during quiesced stop " +
                                            "engine=" + entry.id, failure);
                                    return;
                                }
                                prepared = false;
                                releaseSecondaryDisplay();
                                Log.e(TAG, "Renderer stopped engine=" + entry.id +
                                        " system=" + request.systemId, failure);
                                callback.onSessionError(
                                        "The experimental renderer stopped.", failure);
                            }
                        };
                // GLideN64 honors get_current_framebuffer(), so N64 must use
                // the frontend FBO path: direct-window presentation let the
                // core draw through its own 2880x2880 viewport into the
                // 1920x1080 window, pushing gameplay off-center to the left
                // with the right edge cropped (818adab8 N64 evidence).
                final int presentationPolicy = ("flycast".equals(entry.id) ||
                        "ppsspp".equals(entry.id) ||
                        "mupen64plus-next".equals(entry.id) ||
                        "dolphin".equals(entry.id)) ?
                        com.thorium.preview.ExperimentalGlesLibretroHost
                                .PRESENT_FRONTEND_FBO :
                        com.thorium.preview.ExperimentalGlesLibretroHost
                                .PRESENT_DIRECT_WINDOW;
                Log.i(TAG, "Renderer policy engine=" + entry.id +
                        " policy=" + (presentationPolicy ==
                                com.thorium.preview.ExperimentalGlesLibretroHost
                                        .PRESENT_DIRECT_WINDOW ?
                                "direct-window" : "frontend-fbo"));
                final ExperimentalGlesRenderLoop loop =
                        "vulkan-libretro".equals(entry.runtime) ?
                        ExperimentalGlesRenderLoop.createVulkan(
                                entry.coreFile, entry.coreFile.getParentFile(),
                                system, saves, game, renderListener) :
                        new ExperimentalGlesRenderLoop(
                                entry.coreFile, entry.coreFile.getParentFile(),
                                system, saves, game,
                                presentationPolicy,
                                renderListener);
                renderLoop = loop;
                if (isThreeDsSystem(request.systemId)) {
                    secondaryDisplayRequested = SecondaryGameplaySurfaceRouter.request(
                            appContext, request.systemId, this);
                    Log.i(TAG, "Secondary gameplay display requested engine=" +
                            entry.id + " accepted=" + secondaryDisplayRequested);
                }
            } catch (Throwable failure) {
                releaseSecondaryDisplay();
                Log.e(TAG, "Unable to prepare engine=" + entry.id +
                        " system=" + request.systemId, failure);
                callback.onSessionError("Lucent could not start experimental " +
                        entry.id + ".", failure);
            }
        });
    }

    @Override public void attachSurface(Surface value, int width, int height) {
        voidDimensions(width, height);
        surface = value;
        Log.i(TAG, "Surface available engine=" + entry.id + " size=" +
                width + "x" + height + " valid=" +
                (value != null && value.isValid()) + " prepared=" + prepared);
        ExperimentalGlesRenderLoop active = renderLoop;
        if (prepared && active != null && value != null && value.isValid()) {
            active.attachSurface(value);
        }
    }

    @Override public void resizeSurface(int width, int height) {
        voidDimensions(width, height);
        Log.i(TAG, "Surface resized engine=" + entry.id + " size=" +
                width + "x" + height + " prepared=" + prepared);
        Surface current = surface;
        ExperimentalGlesRenderLoop active = renderLoop;
        if (prepared && active != null && current != null && current.isValid())
            active.recreateSurface(current);
    }

    @Override public void detachSurface() {
        Log.i(TAG, "Surface detached engine=" + entry.id +
                " prepared=" + prepared);
        surface = null;
        ExperimentalGlesRenderLoop active = renderLoop;
        if (prepared && active != null) active.detachSurface();
    }

    /**
     * Bounded synchronous variant of {@link #detachSurface()} for the
     * TextureView destroy callback: Android releases that Surface as soon as
     * the callback returns, so the render thread must stop targeting it first
     * or give up within the UI-safe bound and finish detaching asynchronously.
     */
    void detachSurfaceAndWait() {
        surface = null;
        ExperimentalGlesRenderLoop active = renderLoop;
        boolean detached = true;
        if (prepared && active != null) {
            try {
                detached = active.detachSurfaceAndWait();
            } catch (RuntimeException failure) {
                Log.w(TAG, "Bounded surface detach failed engine=" + entry.id, failure);
            }
        }
        if (!detached)
            Log.w(TAG, "Surface detach exceeded its UI bound; completing " +
                    "asynchronously engine=" + entry.id);
        Log.i(TAG, "Surface detached engine=" + entry.id +
                " prepared=" + prepared + " waited=" + detached);
    }

    @Override public void onSecondarySurfaceAvailable(
            Surface value, int width, int height) {
        voidDimensions(width, height);
        secondarySurface = value;
        ExperimentalGlesRenderLoop active = renderLoop;
        if (active != null && value != null && value.isValid())
            active.attachSecondarySurface(value);
    }

    @Override public void onSecondarySurfaceDestroyed() {
        secondarySurface = null;
        ExperimentalGlesRenderLoop active = renderLoop;
        if (active == null) return;
        try {
            // The lower-display SurfaceView is removed as soon as this
            // notification returns; the swapchain detach must complete (or
            // give up within the UI bound) before that removal.
            if (!active.detachSecondarySurfaceAndWaitBounded())
                Log.w(TAG, "Lower-display detach exceeded its UI bound engine=" +
                        entry.id);
        } catch (RuntimeException failure) {
            Log.w(TAG, "Lower-display detach failed engine=" + entry.id, failure);
        }
    }

    @Override public void onSecondaryTouch(
            float normalizedX, float normalizedY, boolean pressed) {
        ExperimentalGlesRenderLoop active = renderLoop;
        if (active == null || !prepared) return;
        active.setPointer(0, DualScreenLayout.pointerCoordinate(normalizedX),
                DualScreenLayout.lowerScreenPointerY(normalizedY), pressed);
    }

    @Override public void resume() {
        resumeRequested = true;
        ExperimentalGlesRenderLoop active = renderLoop;
        if (prepared && active != null) {
            AudioTrack audio = audioTrack;
            if (audio != null && audioStartedOnce)
                try { audio.play(); } catch (IllegalStateException ignored) {}
            synchronized (this) {
                long now = SystemClock.elapsedRealtime();
                if (activeStartedMillis == 0L) activeStartedMillis = now;
                activeFrameTickMillis = now;
            }
            active.resume();
        }
    }

    @Override public void pause(PauseReason reason) {
        resumeRequested = false;
        synchronized (this) { activeFrameTickMillis = 0L; }
        finishActiveInterval();
        ExperimentalGlesRenderLoop active = renderLoop;
        if (prepared && active != null) active.pause();
        AudioTrack audio = audioTrack;
        if (audio != null) try { audio.pause(); } catch (IllegalStateException ignored) {}
        if (reason == PauseReason.ANDROID_BACKGROUND && prepared && !stopping.get())
            lifecycle.execute(() -> saveQuickResume(false));
    }

    @Override public void quiesceForExit() {
        resumeRequested = false;
        synchronized (this) { activeFrameTickMillis = 0L; }
        finishActiveInterval();
        ExperimentalGlesRenderLoop active = renderLoop;
        if (prepared && active != null) active.pauseAndWait();
        AudioTrack audio = audioTrack;
        if (audio != null) try { audio.pause(); } catch (IllegalStateException ignored) {}
        Log.i(TAG, "Render loop quiesced before library reveal engine=" + entry.id +
                " system=" + (request == null ? "" : request.systemId));
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        ExperimentalGlesRenderLoop active = renderLoop;
        if (!prepared || active == null || inputRouter == null || event == null ||
                (event.getAction() != KeyEvent.ACTION_DOWN &&
                 event.getAction() != KeyEvent.ACTION_UP)) return false;
        GamepadDescriptor pad = device(event.getDeviceId());
        if (pad == null) { refreshDevices(); pad = device(event.getDeviceId()); }
        if (pad == null) return false;
        try {
            CanonicalControl control = inputRouter.resolve(pad,
                    InputSignal.key(event.getKeyCode()));
            int button = canonicalButton(control);
            if (button < 0) return false;
            boolean pressed = event.getAction() == KeyEvent.ACTION_DOWN;
            active.setJoypadButton(0, button, pressed);
            Log.i(TAG, "Physical input dispatched engine=" + entry.id +
                    " key=" + event.getKeyCode() + " control=" + control +
                    " button=" + button + " pressed=" + pressed);
            return true;
        } catch (Exception ignored) { return false; }
    }

    @Override public boolean dispatchGenericMotionEvent(MotionEvent event) {
        ExperimentalGlesRenderLoop active = renderLoop;
        InputDevice inputDevice = event == null ? null : event.getDevice();
        if (!prepared || active == null || event.getAction() != MotionEvent.ACTION_MOVE ||
                inputRouter == null || inputDevice == null ||
                inputDevice.getMotionRange(MotionEvent.AXIS_X,
                        event.getSource()) == null ||
                inputDevice.getMotionRange(MotionEvent.AXIS_Y, event.getSource()) == null)
            return false;
        GamepadDescriptor pad = device(event.getDeviceId());
        if (pad == null) { refreshDevices(); pad = device(event.getDeviceId()); }
        if (pad == null) return false;
        active.setAnalogAxis(0, 0, 0, normalizeAxis(event, MotionEvent.AXIS_X));
        active.setAnalogAxis(0, 0, 1, normalizeAxis(event, MotionEvent.AXIS_Y));
        int rightX = inputDevice.getMotionRange(MotionEvent.AXIS_Z, event.getSource()) != null
                ? MotionEvent.AXIS_Z : MotionEvent.AXIS_RX;
        int rightY = inputDevice.getMotionRange(MotionEvent.AXIS_RZ, event.getSource()) != null
                ? MotionEvent.AXIS_RZ : MotionEvent.AXIS_RY;
        if (inputDevice.getMotionRange(rightX, event.getSource()) != null)
            active.setAnalogAxis(0, 1, 0, normalizeAxis(event, rightX));
        if (inputDevice.getMotionRange(rightY, event.getSource()) != null)
            active.setAnalogAxis(0, 1, 1, normalizeAxis(event, rightY));
        boolean consumed = true;
        for (int axis : pad.axes) {
            float value = event.getAxisValue(axis);
            consumed |= dispatchAxis(pad, axis, -1, value <= -AXIS_PRESS);
            consumed |= dispatchAxis(pad, axis, 1, value >= AXIS_PRESS);
        }
        return consumed;
    }

    @Override public boolean openControls() {
        if (!(context instanceof Activity) || inputRouter == null) return false;
        GamepadDescriptor pad = inputRouter.activeDevice();
        Activity activity = (Activity) context;
        if (pad == null) {
            activity.runOnUiThread(() -> new AlertDialog.Builder(context)
                    .setTitle("Controls")
                    .setMessage("No physical controller is connected. Lucent will use touch controls when available.")
                    .setPositiveButton("Done", null).show());
        } else {
            activity.runOnUiThread(() -> showControlsMenu(pad));
        }
        return true;
    }

    @Override public boolean openRestoreHistory() {
        if (!(context instanceof Activity) || vault == null || identity == null) return false;
        List<StateSnapshot> history = restorableHistory();
        if (history.isEmpty()) return false;
        String[] labels = new String[history.size()];
        DateFormat formatter = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
        for (int index = 0; index < history.size(); index++) {
            StateSnapshot snapshot = history.get(index);
            labels[index] = formatter.format(new Date(snapshot.metadata.createdAtMillis)) +
                    "  •  " + formatPlayTime(snapshot.metadata.activePlayMillis);
        }
        ((Activity) context).runOnUiThread(() -> new AlertDialog.Builder(context)
                .setTitle("Restore earlier point")
                .setItems(labels, (dialog, which) -> restore(history.get(which)))
                .setNegativeButton("Cancel", null).show());
        return true;
    }

    @Override public boolean shouldShowOnScreenControls() {
        return inputRouter != null && inputRouter.shouldShowOnScreenControls();
    }
    @Override public boolean dispatchVirtualControl(CanonicalControl control, boolean pressed) {
        int button = canonicalButton(control);
        ExperimentalGlesRenderLoop active = renderLoop;
        if (!prepared || active == null || button < 0 ||
                !shouldShowOnScreenControls()) return false;
        active.setJoypadButton(0, button, pressed);
        return true;
    }

    @Override public void stop(StopReason reason, Completion completion) {
        if (completion == null) throw new IllegalArgumentException("completion required");
        if (!stopping.compareAndSet(false, true)) {
            completion.complete();
            return;
        }
        resumeRequested = false;
        finishActiveInterval();
        lifecycle.execute(() -> {
            try {
                ExperimentalGlesRenderLoop active = renderLoop;
                if (active != null) active.pauseAndWait();
                Throwable saveFailure = saveQuickResume(true);
                if (!QuickResumePolicy.mayCompleteStop(
                        reason == StopReason.EXIT_TO_LUCENT, saveFailure == null)) {
                    stopping.set(false);
                    Listener callback = listener;
                    // The rejection channel keeps gameplay retained and lets
                    // the host reset its exit latch; onSessionError would show
                    // the fatal overlay over a still-playable game.
                    if (callback != null) callback.onSessionStopRejected(
                            "Lucent could not protect Quick Resume. The game is still open; " +
                                    "return only if you accept losing this session.", saveFailure);
                    return;
                }
                detachSecondaryBeforeBlank(active);
                releaseAudio();
                loopClose(renderLoop);
                renderLoop = null;
                prepared = false;
                completion.complete();
            } catch (Throwable failure) {
                // A wedged or already-closed render loop must never strand the
                // session: without completion the host keeps it in the retiring
                // set forever and the gameplay root and Activity leak. The
                // previous verified Quick Resume remains on disk.
                Log.e(TAG, "Stop teardown failed engine=" + entry.id +
                        " marker=save-failure", failure);
                try { releaseAudio(); } catch (Throwable ignored) {}
                try { loopClose(renderLoop); } catch (Throwable ignored) {}
                renderLoop = null;
                prepared = false;
                completion.complete();
            }
        });
    }

    @Override public void release() {
        if (!released.compareAndSet(false, true)) return;
        resumeRequested = false;
        finishActiveInterval();
        lifecycle.execute(() -> {
            saveQuickResume(false);
            detachSecondaryBeforeBlank(renderLoop);
            releaseAudio();
            loopClose(renderLoop);
            renderLoop = null;
            prepared = false;
        });
        lifecycle.shutdown();
    }

    private static void loopClose(ExperimentalGlesRenderLoop loop) {
        if (loop != null) try { loop.close(); } catch (Throwable ignored) {}
    }

    private void releaseSecondaryDisplay() {
        if (!secondaryDisplayRequested) return;
        secondaryDisplayRequested = false;
        secondarySurface = null;
        SecondaryGameplaySurfaceRouter.release(appContext, this);
    }

    private void detachSecondaryBeforeBlank(ExperimentalGlesRenderLoop active) {
        if (!secondaryDisplayRequested) return;
        if (active != null) {
            try {
                active.detachSecondarySurfaceAndWait();
            } catch (Throwable failure) {
                Log.w(TAG, "Lower-display swapchain detach failed before blank", failure);
            }
        }
        releaseSecondaryDisplay();
    }

    private static boolean isThreeDsSystem(String systemId) {
        return "3ds".equalsIgnoreCase(systemId) ||
                "n3ds".equalsIgnoreCase(systemId);
    }

    private void handleReady(Listener callback) {
        ExperimentalGlesRenderLoop active = renderLoop;
        if (active == null) {
            callback.onSessionError("The experimental renderer was unavailable.", null);
            return;
        }
        if (stopping.get() || released.get()) {
            loopClose(active);
            return;
        }
        prepared = true;
        try {
            restoreSaveRam(active);
            double sampleRate = active.avInfo().sampleRate;
            audioTrack = createAudioTrack(sampleRate);
            if (audioTrack == null)
                throw new IllegalStateException("Android could not initialize stereo PCM output");
            // Fill the actual streaming buffer before the first play(). A
            // A half-full start can underrun while a heavy core warms its JIT.
            audioPrimeSamplesTarget = startupAudioBufferBytes(sampleRate) / 2;
        } catch (Throwable failure) {
            loopClose(active);
            prepared = false;
            callback.onSessionError("Lucent could not initialize engine audio or saves.", failure);
            return;
        }
        Surface current = surface;
        Log.i(TAG, "Engine ready engine=" + entry.id + " surfaceValid=" +
                (current != null && current.isValid()));
        if (current != null && current.isValid()) {
            active.attachSurface(current);
        }
        if (resumeRequested) {
            AudioTrack audio = audioTrack;
            if (audio != null && audioStartedOnce)
                try { audio.play(); } catch (IllegalStateException ignored) {}
            synchronized (this) {
                long now = SystemClock.elapsedRealtime();
                if (activeStartedMillis == 0L) activeStartedMillis = now;
                activeFrameTickMillis = now;
            }
            active.resume();
        }
        notifyRestoreAvailability();
        callback.onSessionReady();
    }

    private void restoreQuickResume(ExperimentalGlesRenderLoop active) {
        byte[] state = pendingQuickResume;
        if (state == null || !quickResumeApplied.compareAndSet(false, true)) return;
        try {
            active.unserialize(state);
            flushAudioAfterRestore();
            pendingQuickResume = null;
            Log.i(TAG, "Restored Quick Resume engine=" + entry.id +
                    " commit=" + entry.sourceCommit);
        } catch (Throwable rejected) {
            // Keep the verified snapshot on disk. Boot normally when the core
            // rejects it despite matching ROM and pinned engine identity.
            pendingQuickResume = null;
            Log.w(TAG, "Engine rejected a matching Quick Resume; booting normally", rejected);
        }
    }

    /** Returns the failure so explicit exits cannot falsely claim a protected save. */
    private Throwable saveQuickResume(boolean requireCommit) {
        try {
            ExperimentalGlesRenderLoop active = renderLoop;
            if (!prepared || active == null || vault == null || identity == null) {
                return requireCommit
                        ? new IllegalStateException("engine state vault is not ready") : null;
            }
            persistSaveRam(active);
            byte[] state = active.serialize();
            /* Loading the previous snapshot duplicates both states in the
             * Java heap. Dolphin states can exceed 140 MiB, so that recovery
             * convenience would OOM after a successful new serialization and
             * correctly prevent held-Stop from returning. The quick-save
             * publication is already atomic; retain the older snapshot on
             * disk and avoid an in-memory recovery copy for large cores. */
            if (QuickResumePolicy.shouldCopyPreviousToRecovery(state.length)) {
                StateLoadResult previous = vault.loadQuickResume(identity);
                if (previous.status == StateLoadResult.Status.OK)
                    vault.saveRecovery(identity, previous.state, null,
                            previous.snapshot.metadata.activePlayMillis);
            } else {
                Log.i(TAG, "Skipped in-memory recovery copy for large state engine=" +
                        entry.id + " bytes=" + state.length);
            }
            vault.saveQuickResume(identity, state, null, currentActivePlayMillis());
            notifyRestoreAvailability();
            if (requireCommit)
                Log.i(TAG, "Committed Quick Resume before stop engine=" + entry.id +
                        " commit=" + entry.sourceCommit);
            return null;
        } catch (Throwable failure) {
            // A failed state must not replace the last verified snapshot or
            // strand the user while stopping qualification content. The
            // stable marker keeps the swallowed failure greppable by the
            // runtime evidence verifiers.
            Log.w(TAG, "Quick Resume was not updated for " + entry.id +
                    " marker=save-failure", failure);
            return failure;
        }
    }

    private void recordActiveProgress(ExperimentalGlesRenderLoop active) {
        if (!resumeRequested || checkpointScheduler == null || active == null) return;
        long elapsed;
        synchronized (this) {
            long now = SystemClock.elapsedRealtime();
            elapsed = activeFrameTickMillis == 0L ? 0L
                    : Math.max(0L, now - activeFrameTickMillis);
            activeFrameTickMillis = now;
        }
        if (checkpointScheduler.advanceActivePlay(elapsed) > 0)
            saveAutomatic();
    }

    private void saveAutomatic() {
        if (!checkpointPending.compareAndSet(false, true)) return;
        try {
            lifecycle.execute(() -> {
                try {
                    ExperimentalGlesRenderLoop active = renderLoop;
                    if (!prepared || active == null || vault == null || identity == null) return;
                    persistSaveRam(active);
                    vault.saveAutomatic(identity, active.serialize(), null,
                            checkpointScheduler == null ? currentActivePlayMillis()
                                    : checkpointScheduler.totalActiveMillis());
                    notifyRestoreAvailability();
                } catch (Throwable failure) {
                    Log.w(TAG, "Automatic checkpoint failed for " + entry.id, failure);
                } finally {
                    checkpointPending.set(false);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            checkpointPending.set(false);
        }
    }

    private List<StateSnapshot> restorableHistory() {
        List<StateSnapshot> history = new ArrayList<>();
        if (vault == null || identity == null) return history;
        for (StateSnapshot snapshot : vault.list(identity))
            if (snapshot.metadata.kind != SnapshotKind.QUICK_RESUME)
                history.add(snapshot);
        return history;
    }

    private void notifyRestoreAvailability() {
        Listener callback = listener;
        if (callback != null)
            callback.onRestoreAvailabilityChanged(!restorableHistory().isEmpty());
    }

    private void restore(StateSnapshot snapshot) {
        lifecycle.execute(() -> {
            try {
                ExperimentalGlesRenderLoop active = renderLoop;
                if (!prepared || active == null || vault == null || identity == null) return;
                // Preserve the point being left so a timeline restore is reversible.
                vault.saveRecovery(identity, active.serialize(), null,
                        currentActivePlayMillis());
                StateLoadResult loaded = vault.loadSnapshot(identity, snapshot);
                if (loaded.status != StateLoadResult.Status.OK)
                    throw new IllegalStateException("snapshot failed validation: " + loaded.detail);
                active.unserialize(loaded.state);
                flushAudioAfterRestore();
                notifyRestoreAvailability();
            } catch (Throwable failure) {
                Listener callback = listener;
                if (callback != null) callback.onSessionError(
                        "Lucent could not restore that earlier point.", failure);
            }
        });
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

    private boolean dispatchAxis(GamepadDescriptor pad, int axis, int direction,
                                 boolean pressed) {
        try {
            int button = canonicalButton(inputRouter.resolve(pad,
                    InputSignal.axis(axis, direction)));
            ExperimentalGlesRenderLoop active = renderLoop;
            if (button < 0 || active == null) return false;
            active.setJoypadButton(0, button, pressed);
            return true;
        } catch (Exception ignored) { return false; }
    }

    private void showControlsMenu(GamepadDescriptor pad) {
        String systemId = request == null ? "system" : request.systemId;
        String[] actions = {
                "Remap for " + systemId,
                "Remap for this game",
                "Reset " + systemId + " mapping",
                "Reset this game mapping"
        };
        new AlertDialog.Builder(context).setTitle(pad.name)
                .setItems(actions, (dialog, which) -> {
                    if (which < 2) showControlPicker(pad, which == 1);
                    else try { inputRouter.resetRemap(pad, which == 3); }
                    catch (Exception error) { showControlError(); }
                }).setNegativeButton("Done", null).show();
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
        for (int index = 0; index < controls.length; index++)
            labels[index] = controls[index].name().replace('_', ' ');
        new AlertDialog.Builder(context).setTitle("Choose a control")
                .setItems(labels, (dialog, which) ->
                        captureControl(pad, controls[which], gameOnly))
                .setNegativeButton("Cancel", null).show();
    }

    private void captureControl(GamepadDescriptor pad, CanonicalControl control,
                                boolean gameOnly) {
        AlertDialog capture = new AlertDialog.Builder(context)
                .setTitle("Press a button for " + control.name().replace('_', ' '))
                .setMessage("The next controller button becomes this control.")
                .setNegativeButton("Cancel", null).create();
        capture.setOnKeyListener((dialog, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() != 0)
                return true;
            try {
                EnumMap<CanonicalControl, InputSignal> mapping =
                        new EnumMap<>(CanonicalControl.class);
                mapping.putAll(gameOnly ? inputRouter.effectiveMapping(pad)
                        : inputRouter.effectiveSystemMapping(pad));
                mapping.put(control, InputSignal.key(keyCode));
                inputRouter.saveRemap(pad, gameOnly, mapping);
                dialog.dismiss();
            } catch (Exception error) {
                dialog.dismiss();
                showControlError();
            }
            return true;
        });
        capture.show();
    }

    private void showControlError() {
        new AlertDialog.Builder(context).setTitle("Controls")
                .setMessage("Lucent could not save this mapping.")
                .setPositiveButton("OK", null).show();
    }

    private void restoreSaveRam(ExperimentalGlesRenderLoop active) throws Exception {
        byte[] saved = DurableBlobStore.read(saveRamFile, MAX_SAVE_RAM_BYTES);
        if (saved != null) active.writeSaveRam(saved);
    }

    private void persistSaveRam(ExperimentalGlesRenderLoop active) throws Exception {
        byte[] value = active.readSaveRam();
        if (value != null && value.length > 0)
            DurableBlobStore.write(saveRamFile, value, MAX_SAVE_RAM_BYTES);
    }

    private synchronized void finishActiveInterval() {
        if (activeStartedMillis == 0L) return;
        activePlayMillis += Math.max(0L,
                SystemClock.elapsedRealtime() - activeStartedMillis);
        activeStartedMillis = 0L;
    }

    private synchronized long currentActivePlayMillis() {
        if (activeStartedMillis == 0L) return activePlayMillis;
        return activePlayMillis + Math.max(0L,
                SystemClock.elapsedRealtime() - activeStartedMillis);
    }

    private void presentAudio(short[] samples) {
        AudioTrack audio = audioTrack;
        if (audio == null) return;
        if (pendingAudio == null && samples != null && samples.length > 0) {
            pendingAudio = samples;
            pendingAudioOffset = 0;
            receivedAudioFrames += samples.length / 2L;
        }
        if (pendingAudio == null) return;
        try {
            int written = Build.VERSION.SDK_INT >= 23
                    ? audio.write(pendingAudio, pendingAudioOffset,
                            pendingAudio.length - pendingAudioOffset,
                            AudioTrack.WRITE_NON_BLOCKING)
                    : audio.write(pendingAudio, pendingAudioOffset,
                            pendingAudio.length - pendingAudioOffset);
            if (written < 0)
                throw new IllegalStateException("AudioTrack write failed with code " + written);
            if (written > 0) {
                pendingAudioOffset += written;
                writtenAudioFrames += written / 2L;
                if (!audioStartedOnce) primedAudioSamples += written;
            }
            if (pendingAudioOffset >= pendingAudio.length) {
                pendingAudio = null;
                pendingAudioOffset = 0;
            }
            if (!audioStartedOnce && resumeRequested &&
                    primedAudioSamples >= audioPrimeSamplesTarget) {
                audio.play();
                audioStartedOnce = true;
                audioStartedAtMillis = SystemClock.elapsedRealtime();
                Log.i(TAG, "Audio playback started engine=" + entry.id +
                        " primedSamples=" + primedAudioSamples);
            }
            applySteadyAudioBuffer(audio);
        } catch (Throwable failure) {
            Log.e(TAG, "Phase 2 audio output failed engine=" + entry.id, failure);
            if (audioFailureReported.compareAndSet(false, true)) {
                Listener callback = listener;
                if (callback != null) callback.onSessionError(
                        "Lucent lost audio output for this game.", failure);
            }
        }
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
        audioFailureReported.set(false);
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
    private AudioTrack createAudioTrack(double sampleRate) {
        int rate = (int) Math.round(sampleRate);
        if (rate < 8_000 || rate > 192_000) return null;
        int bufferBytes = startupAudioBufferBytes(sampleRate);
        if (bufferBytes <= 0) return null;
        AudioTrack track = new AudioTrack(AudioManager.STREAM_MUSIC, rate,
                AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
                // Start with a 200 ms JIT-warmup cushion, then reduce the
                // effective buffer to the platform minimum or roughly 50 ms.
                bufferBytes, AudioTrack.MODE_STREAM);
        if (track.getState() == AudioTrack.STATE_INITIALIZED) return track;
        track.release();
        return null;
    }

    private int startupAudioBufferBytes(double sampleRate) {
        int rate = (int) Math.round(sampleRate);
        if (rate < 8_000 || rate > 192_000) return 0;
        int minimum = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minimum <= 0) return 0;
        // Play's EE JIT exhibits isolated >200 ms compilation stalls during
        // game/menu transitions on the Thor. Allocate and fully prime a 500
        // ms queue for Play so those stalls do not disable AudioTrack. Other
        // Phase 2 engines retain the proven 200 ms startup queue.
        int targetDivisor = "play".equals(entry.id) ? 2 : 5;
        return Math.max(minimum, rate / targetDivisor * 4);
    }

    private int steadyAudioBufferBytes(double sampleRate) {
        int rate = (int) Math.round(sampleRate);
        if (rate < 8_000 || rate > 192_000) return 0;
        int minimum = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minimum <= 0) return 0;
        // Play's EE JIT can briefly stop producing PCM while compiling a new
        // block. Reducing its queue to the 50 ms low-latency target used by
        // PPSSPP caused Android to disable/restart AudioTrack after underruns
        // on the Thor. Keep the proven 200 ms warm-up cushion for Play; this
        // affects latency only, never guest timing or generated samples.
        int targetDivisor = "play".equals(entry.id) ? 2 : 20;
        return Math.max(minimum, rate / targetDivisor * 4);
    }

    private void recordFrameHealth() {
        long now = SystemClock.elapsedRealtime();
        if (frameSampleStartedAtMillis == 0L) frameSampleStartedAtMillis = now;
        presentedFrames++;
        if (presentedFrames % 300L != 0L) return;
        long elapsed = Math.max(1L, now - frameSampleStartedAtMillis);
        float fps = presentedFrames * 1_000f / elapsed;
        AudioTrack audio = audioTrack;
        int underruns = Build.VERSION.SDK_INT >= 24 && audio != null
                ? audio.getUnderrunCount() : -1;
        // The playback head only advances when the mixer consumes PCM, so it
        // separates "samples written to a stalled track" from audible output.
        int audioHead = 0;
        if (audio != null)
            try { audioHead = audio.getPlaybackHeadPosition(); }
            catch (IllegalStateException ignored) {}
        Log.i(TAG, "Health engine=" + entry.id + " fps=" +
                String.format(Locale.US, "%.2f", fps) + " frames=" + presentedFrames +
                " audioUnderruns=" + underruns + " audioReceived=" +
                receivedAudioFrames + " audioWritten=" + writtenAudioFrames +
                " audioRate=" + (audio == null ? 0 : audio.getSampleRate()) +
                " audioStarted=" + audioStartedOnce + " audioHead=" + audioHead);
        if (!audioSilenceWarned && (audio == null || !audioStartedOnce || audioHead == 0)) {
            audioSilenceWarned = true;
            Log.w(TAG, "Audio silent after health window engine=" + entry.id +
                    " trackPresent=" + (audio != null) +
                    " started=" + audioStartedOnce +
                    " primed=" + primedAudioSamples +
                    " primeTarget=" + audioPrimeSamplesTarget +
                    " head=" + audioHead);
        }
    }

    private void releaseAudio() {
        pendingAudio = null;
        pendingAudioOffset = 0;
        primedAudioSamples = 0;
        audioPrimeSamplesTarget = 0;
        audioStartedOnce = false;
        audioStartedAtMillis = 0L;
        steadyAudioBufferApplied = false;
        audioSilenceWarned = false;
        presentedFrames = 0L;
        frameSampleStartedAtMillis = 0L;
        receivedAudioFrames = 0L;
        writtenAudioFrames = 0L;
        AudioTrack audio = audioTrack;
        audioTrack = null;
        if (audio == null) return;
        try { audio.stop(); } catch (IllegalStateException ignored) {}
        audio.release();
    }

    private static File resolveGameFile(Uri uri) throws Exception {
        if (uri == null) throw new IllegalArgumentException("PSP URI is missing");
        String raw = "file".equals(uri.getScheme()) ? uri.getPath() :
                uri.getQueryParameter("path");
        if (raw == null || raw.trim().isEmpty())
            throw new IllegalArgumentException("PSP URI does not expose a local file");
        File file = new File(raw).getCanonicalFile();
        String path = file.getPath();
        if (!(path.startsWith("/storage/") || path.startsWith("/mnt/media_rw/")) ||
                !file.isFile())
            throw new IllegalArgumentException("PSP game is outside approved Android storage");
        return file;
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        InputStream input = new FileInputStream(file);
        try {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0)
                if (count > 0) digest.update(buffer, 0, count);
        } finally { input.close(); }
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest())
            result.append(String.format(Locale.US, "%02x", value & 0xff));
        return result.toString();
    }

    private static int retroButton(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A: return 0;
            case KeyEvent.KEYCODE_BUTTON_X: return 1;
            case KeyEvent.KEYCODE_BUTTON_SELECT: return 2;
            case KeyEvent.KEYCODE_BUTTON_START: return 3;
            case KeyEvent.KEYCODE_DPAD_UP: return 4;
            case KeyEvent.KEYCODE_DPAD_DOWN: return 5;
            case KeyEvent.KEYCODE_DPAD_LEFT: return 6;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return 7;
            case KeyEvent.KEYCODE_BUTTON_B: return 8;
            case KeyEvent.KEYCODE_BUTTON_Y: return 9;
            case KeyEvent.KEYCODE_BUTTON_L1: return 10;
            case KeyEvent.KEYCODE_BUTTON_R1: return 11;
            case KeyEvent.KEYCODE_BUTTON_L2: return 12;
            case KeyEvent.KEYCODE_BUTTON_R2: return 13;
            case KeyEvent.KEYCODE_BUTTON_THUMBL: return 14;
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return 15;
            default: return -1;
        }
    }

    private int canonicalButton(CanonicalControl control) {
        return LibretroJoypadLayout.idFor(request.systemId, control);
    }

    private static float normalizeAxis(MotionEvent event, int axis) {
        float value = event.getAxisValue(axis);
        float magnitude = Math.abs(value);
        if (magnitude <= AXIS_DEAD_ZONE) return 0f;
        return Math.copySign(Math.min(1f,
                (magnitude - AXIS_DEAD_ZONE) / (1f - AXIS_DEAD_ZONE)), value);
    }

    private static void voidDimensions(int width, int height) {
        if (width < 0 || height < 0)
            throw new IllegalArgumentException("surface dimensions cannot be negative");
    }

    private static String formatPlayTime(long millis) {
        long minutes = Math.max(0L, millis) / 60_000L;
        return minutes < 60 ? minutes + " min"
                : (minutes / 60) + "h " + (minutes % 60) + "m";
    }
}
