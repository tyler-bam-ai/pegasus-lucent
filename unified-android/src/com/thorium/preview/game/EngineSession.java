package com.thorium.preview.game;

import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;

import com.thorium.lucent.input.CanonicalControl;

/** Runtime boundary shared by future libretro and native engine adapters. */
public interface EngineSession {
    enum PauseReason { LUCENT_MENU, ANDROID_BACKGROUND }
    enum StopReason { EXIT_TO_LUCENT, ACTIVITY_DESTROYED }

    interface Listener {
        void onSessionReady();
        void onSessionError(String message, Throwable cause);
        void onSessionStopRejected(String message, Throwable cause);
        void onRestoreAvailabilityChanged(boolean available);
    }

    interface Completion {
        void complete();
    }

    void prepare(GameLaunchRequest request, Listener listener);
    void attachSurface(Surface surface, int width, int height);
    void resizeSurface(int width, int height);
    void detachSurface();
    void resume();
    void pause(PauseReason reason);

    /**
     * Drains any in-flight frame and prevents another frame from being
     * scheduled before Lucent reveals its warm library UI. Hardware sessions
     * must complete this boundary while their Android Surface is still valid.
     */
    void quiesceForExit();

    /** Returns true when the engine displayed its Lucent-owned remapping UI. */
    boolean openControls();

    /** Returns true when the engine displayed a Lucent-owned checkpoint timeline. */
    boolean openRestoreHistory();

    /**
     * Restarts the running game from power-on, as the held Select+Start combo
     * requests.
     *
     * Fails closed: an adapter that cannot prove it reached a real reset must
     * return false so the host reports nothing happened rather than leaving the
     * player unsure whether their progress was discarded.
     */
    default boolean reset() { return false; }

    /** Receives gameplay keys after Lucent's pause/Stop-button handling. */
    boolean dispatchKeyEvent(KeyEvent event);

    /** Receives joystick axes after Lucent's shell-level gestures. */
    boolean dispatchGenericMotionEvent(MotionEvent event);

    /** True only on phones/tablets without a complete physical controller. */
    boolean shouldShowOnScreenControls();

    /** Input from Lucent's own optional touch overlay. */
    boolean dispatchVirtualControl(CanonicalControl control, boolean pressed);

    /** Implementations save Quick Resume before invoking completion. */
    void stop(StopReason reason, Completion completion);

    void release();
}
