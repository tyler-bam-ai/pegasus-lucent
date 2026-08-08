import hashlib
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
HOST = ROOT / "unified-android" / "src" / "com" / "thorium" / "preview" / "game" / "InWindowGameHost.java"
THEME = ROOT / "theme" / "theme.qml"
COMMAND = ROOT / "unified-android" / "src" / "com" / "thorium" / "lucent" / "metadata" / "MetadataGameLaunchCommand.java"
BRIDGE = ROOT / "android-companion" / "src" / "com" / "thorium" / "preview" / "InProcessGameLaunchCommand.java"
PATCHER = ROOT / "unified-android" / "tools" / "patch_main_activity_right_stick.py"
GLES_SESSION = ROOT / "unified-android" / "src" / "com" / "thorium" / "preview" / "game" / "PpssppGlesEngineSession.java"
GLES_LOOP = ROOT / "unified-android" / "src" / "com" / "thorium" / "preview" / "ExperimentalGlesRenderLoop.java"


class InWindowInstantReturnTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = HOST.read_text(encoding="utf-8")

    def method(self, start: str, end: str) -> str:
        return self.source.split(start, 1)[1].split(end, 1)[0]

    def test_theme_is_frozen_while_runtime_return_is_changed(self):
        self.assertEqual(
            hashlib.sha256(THEME.read_bytes()).hexdigest(),
            "8578d2c16f750913c2af0edc7bc81f9bd7885a6a9222b178821a2ca8a365538a",
        )

    def test_no_preparing_or_saving_interstitial_is_visible(self):
        self.assertNotIn('setText("Preparing ', self.source)
        self.assertNotIn('setText("Saving and returning', self.source)
        build = self.method("private void buildUi()", "private FrameLayout.LayoutParams match()")
        self.assertIn("status.setVisibility(View.GONE);", build)

    def test_library_returns_before_background_stop_commit(self):
        exit_method = self.method(
            "private void exitToLibrary(String reason)", "private void returnToLibraryUi(long"
        )
        self.assertLess(
            exit_method.index("RETIRING_SESSIONS.add(ending)"),
            exit_method.index("returnToLibraryUi(returnStartedUptimeMs);"),
        )
        self.assertLess(
            exit_method.index("ending.quiesceForExit();"),
            exit_method.index("returnToLibraryUi(returnStartedUptimeMs);"),
        )
        self.assertLess(
            exit_method.index("returnToLibraryUi(returnStartedUptimeMs);"),
            exit_method.index("ending.stop("),
        )
        restore = self.method(
            "private void returnToLibraryUi(long", "private synchronized void finishRetiringSession"
        )
        self.assertIn("revealLibraryOverRetiringSurface();", restore)
        self.assertNotIn("detachViews();", restore)
        self.assertIn("if (active == this) active = null;", restore)

    def test_retiring_surface_stays_valid_until_background_checkpoint(self):
        reveal = self.method(
            "private void revealLibraryOverRetiringSurface()", "@Override public void onSurfaceAvailable"
        )
        self.assertIn("child.bringToFront();", reveal)
        self.assertNotIn("content.removeView(root)", reveal)
        finish = self.method(
            "private synchronized void finishRetiringSession", "private void destroyNow()"
        )
        self.assertIn("mainHandler.post", finish)
        self.assertIn("detachViews();", finish)

    def test_gles_exit_barrier_precedes_snapshot_and_late_error_cannot_poison_it(self):
        session = GLES_SESSION.read_text(encoding="utf-8")
        loop = GLES_LOOP.read_text(encoding="utf-8")
        quiesce = session.split(
            "@Override public void quiesceForExit()", 1
        )[1].split("@Override public boolean dispatchKeyEvent", 1)[0]
        self.assertIn("active.pauseAndWait();", quiesce)
        stop = session.split("@Override public void stop", 1)[1].split(
            "@Override public void release", 1
        )[0]
        self.assertLess(
            stop.index("active.pauseAndWait();"),
            stop.index("saveQuickResume(true)"),
        )
        error = session.split("@Override public void onError", 1)[1].split(
            "prepared = false;", 1
        )[0]
        self.assertIn("if (stopping.get() || released.get())", error)
        barrier = loop.split("public void pauseAndWait()", 1)[1].split(
            "public void setJoypadButton", 1
        )[0]
        self.assertIn("call(() ->", barrier)
        self.assertIn("resumeRequested = false;", barrier)
        self.assertIn("host.pause();", barrier)

    def test_qt_library_stays_warm_and_is_not_lifecycle_restarted(self):
        self.assertNotIn("invokeQtDelegateLifecycle", self.source)
        self.assertNotIn("suspendQtRenderer", self.source)
        self.assertNotIn("resumeQtRenderer", self.source)
        attach = self.method("private void attach()", "private boolean sameRequest")
        self.assertNotIn("child.setVisibility(View.INVISIBLE);", attach)
        self.assertIn("Keep the Qt SurfaceView attached, visible", attach)
        build = self.method("private void buildUi()", "private FrameLayout.LayoutParams match()")
        self.assertIn("root.setBackgroundColor(Color.BLACK);", build)
        detach = self.method("private void detachViews()", "private void onSurfaceAvailable")
        self.assertIn("child.setVisibility(libraryVisibility.get(index));", detach)

    def test_real_menu_launch_does_not_restart_the_qt_activity_lifecycle(self):
        command = COMMAND.read_text(encoding="utf-8")
        bridge = BRIDGE.read_text(encoding="utf-8")
        patcher = PATCHER.read_text(encoding="utf-8")
        self.assertIn('return "am start -a', command)
        self.assertNotIn('return "am broadcast', command)
        self.assertIn("org.pegasus_frontend.android.MainActivity", command)
        self.assertIn("LucentApplication.currentMainActivity()", bridge)
        self.assertIn("activity.runOnUiThread", bridge)
        self.assertIn("InWindowGameHost.handleIntent(activity, request)", bridge)
        self.assertNotIn("startActivity(", bridge)
        self.assertIn("InProcessGameLaunchCommand;->tryLaunch", patcher)

    def test_retiring_session_has_strong_owner_and_native_release_is_off_ui(self):
        self.assertIn("private static final Set<EngineSession> RETIRING_SESSIONS", self.source)
        self.assertIn("private static final ExecutorService RETIREMENT_RELEASES", self.source)
        finish = self.method(
            "private synchronized void finishRetiringSession", "private void destroyNow()"
        )
        self.assertIn("RETIRING_SESSIONS.remove(ending);", finish)
        self.assertIn("RETIREMENT_RELEASES.execute(ending::release);", finish)
        self.assertNotIn("activity.runOnUiThread", finish)

    def test_late_save_failures_cleanup_without_reopening_game_overlay(self):
        error = self.method(
            "@Override public void onSessionError", "@Override public void onSessionStopRejected"
        )
        rejected = self.method(
            "@Override public void onSessionStopRejected", "@Override public void onRestoreAvailabilityChanged"
        )
        for callback in (error, rejected):
            self.assertIn("if (libraryReturned)", callback)
            self.assertIn("finishRetiringSession(retiringSession", callback)
            before_return = callback.split("return;", 1)[0]
            self.assertNotIn("status.setVisibility(View.VISIBLE)", before_return)

    def test_launch_a_up_cannot_dismiss_a_prepare_failure(self):
        handler = self.method("private boolean handleKeyEvent", "private boolean handleMotionEvent")
        fatal = handler.split("if (fatalErrorVisible)", 1)[1].split("return true;", 1)[0]
        self.assertNotIn("KEYCODE_BUTTON_A", fatal)
        self.assertNotIn("KEYCODE_DPAD_CENTER", fatal)
        self.assertIn('exitToLibrary("fatal-back-key")', handler)
        self.assertIn('new Throwable("Lucent exit origin")', self.source)
        self.assertIn('Log.e(TAG, "Engine session error engine="', self.source)

    def test_stale_menu_metadata_cannot_route_to_an_unpackaged_core(self):
        request = self.method("private static GameLaunchRequest requestFrom", "private void attach()")
        self.assertIn("InternalEngineCatalog.byId(activity, engine)", request)
        self.assertIn("Phase2QualificationCatalog.byId(activity, engine)", request)
        self.assertIn("phaseOne != null && phaseOne.supports(system)", request)
        self.assertIn("phaseTwo != null && phaseTwo.supports(system)", request)
        self.assertIn("if (!approvedPhaseOne && !approvedPhaseTwo)", request)
        self.assertIn("Rejected stale/unpackaged engine route", request)

    def test_every_teardown_route_quiesces_before_surface_detach(self):
        # The 818adab8 stop race was fixed in exitToLibrary alone; destroyNow
        # and replaceWith kept the original surface-detach-before-pause
        # ordering. Every teardown owner must quiesce first, and release()
        # (which joins engine threads) must stay off the UI thread.
        destroy = self.method("private void destroyNow()", "private void detachViews()")
        self.assertIn("ending.quiesceForExit();", destroy)
        self.assertLess(
            destroy.index("ending.quiesceForExit();"),
            destroy.index("detachViews();"),
        )
        self.assertNotIn("ending.release();", destroy)
        self.assertIn("RETIREMENT_RELEASES.execute(ending::release)", destroy)
        replace = self.method(
            "private void replaceWith(GameLaunchRequest next)", "private void buildUi()"
        )
        self.assertIn("ending.quiesceForExit();", replace)
        self.assertNotIn("ending.release();", replace)
        self.assertIn("RETIREMENT_RELEASES.execute(ending::release)", replace)

    def test_tap_select_press_is_latched_across_frame_polls(self):
        # Cores sample the joypad once per retro_run; a same-timestamp
        # down/up pair is invisible to them and breaks the tap-vs-hold
        # contract for the Thor Stop/Select button.
        stop = self.method("private boolean handleStopButton", "private void showPauseMenu()")
        self.assertIn("TAP_SELECT_HOLD_MS", stop)
        self.assertIn("mainHandler.postDelayed", stop)
        self.assertIn("if (session == target) target.dispatchKeyEvent(up);", stop)
        self.assertIn("private static final long TAP_SELECT_HOLD_MS", self.source)

    def test_gles_stop_lambda_cannot_strand_a_retiring_session(self):
        session = GLES_SESSION.read_text(encoding="utf-8")
        stop = session.split("@Override public void stop(StopReason reason", 1)[1]
        stop = stop.split("@Override public void release()", 1)[0]
        # pauseAndWait/loop teardown can throw; the completion must still run
        # or the session, gameplay root, and Activity leak in RETIRING_SESSIONS.
        self.assertIn("} catch (Throwable failure) {", stop)
        catch_block = stop.split("} catch (Throwable failure) {", 1)[1]
        self.assertIn("completion.complete();", catch_block)
        self.assertIn("onSessionStopRejected(", stop)

    def test_phase1_exit_save_failure_is_visible_not_swallowed(self):
        session = (ROOT / "unified-android" / "src" / "com" / "thorium" /
                   "preview" / "game" / "LibretroEngineSession.java").read_text(encoding="utf-8")
        save = session.split("private void saveQuickResume(boolean waitForCommit", 1)[1]
        save = save.split("private void restore(", 1)[0]
        self.assertNotIn("catch (Throwable ignored)", save)
        self.assertIn("marker=save-failure", save)
        self.assertIn("onSessionStopRejected(", save)


if __name__ == "__main__":
    unittest.main()
