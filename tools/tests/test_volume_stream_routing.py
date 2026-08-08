import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
APPLICATION = ROOT / "unified-android" / "src" / "com" / "thorium" / "preview" \
    / "LucentApplication.java"
PREVIEW_ACTIVITY = ROOT / "android-companion" / "src" / "com" / "thorium" \
    / "preview" / "PreviewActivity.java"
BROWSER_ACTIVITY = ROOT / "android-companion" / "src" / "com" / "thorium" \
    / "preview" / "BrowserActivity.java"
LIBRETRO_SESSION = ROOT / "unified-android" / "src" / "com" / "thorium" \
    / "preview" / "game" / "LibretroEngineSession.java"
PPSSPP_SESSION = ROOT / "unified-android" / "src" / "com" / "thorium" \
    / "preview" / "game" / "PpssppGlesEngineSession.java"
AUDIO_LOG = ROOT / "unified-android" / "src" / "com" / "thorium" / "preview" \
    / "game" / "EngineAudioLog.java"
MENU_SOUNDS = ROOT / "android-companion" / "src" / "com" / "thorium" \
    / "preview" / "MenuSoundPlayer.java"

CALL = "setVolumeControlStream(AudioManager.STREAM_MUSIC)"


class VolumeStreamRoutingTest(unittest.TestCase):
    """The hardware volume keys must drive the stream Lucent plays on.

    Without setVolumeControlStream an Activity hands the keys to the platform
    default rather than STREAM_MUSIC, so they move a stream nothing plays on
    while gameplay and preview audio keep whatever level STREAM_MUSIC was left
    at. That is the reported drift, and these tests keep the fix in place.
    """

    def test_application_binds_every_activity_to_the_music_stream(self):
        source = APPLICATION.read_text(encoding="utf-8")
        self.assertIn("import android.media.AudioManager;", source)
        self.assertIn(CALL, source)
        # The Qt MainActivity's Java class belongs to Pegasus and is only
        # reachable through smali patching, so the process-wide lifecycle
        # callback is what covers it. Both entry points matter: created covers
        # the first window, resumed covers a restored or recreated one.
        created = source.split("onActivityCreated", 1)[1].split("}", 1)[0]
        self.assertIn("routeVolumeKeysToMusicStream(activity)", created)
        resumed = source.split("onActivityResumed", 1)[1].split("}", 1)[0]
        self.assertIn("routeVolumeKeysToMusicStream(activity)", resumed)

    def test_preview_and_browser_activities_bind_it_directly(self):
        for path in (PREVIEW_ACTIVITY, BROWSER_ACTIVITY):
            source = path.read_text(encoding="utf-8")
            self.assertIn("import android.media.AudioManager;", source,
                          f"{path.name} does not import AudioManager")
            body = source.split("onCreate(Bundle", 1)[1]
            self.assertIn(CALL, body,
                          f"{path.name} does not bind the volume keys in onCreate")

    def test_every_audible_path_stays_on_one_stream(self):
        # A second stream would resurrect the same bug from the other side:
        # the keys would move STREAM_MUSIC while part of the app plays
        # somewhere else.
        preview = PREVIEW_ACTIVITY.read_text(encoding="utf-8")
        self.assertIn("AudioAttributes.USAGE_MEDIA", preview)
        self.assertNotIn("setAudioStreamType", preview)
        for path in (LIBRETRO_SESSION, PPSSPP_SESSION):
            source = path.read_text(encoding="utf-8")
            self.assertIn("new AudioTrack(AudioManager.STREAM_MUSIC", source,
                          f"{path.name} does not play on STREAM_MUSIC")
        sounds = MENU_SOUNDS.read_text(encoding="utf-8")
        self.assertIn("AudioAttributes.USAGE_MEDIA", sounds)

    def test_session_start_logs_the_resolved_stream_and_level(self):
        # A future "no sound anywhere" report has to be diagnosable from a
        # logcat capture alone, without asking the user to reproduce it.
        audit = AUDIO_LOG.read_text(encoding="utf-8")
        self.assertIn("track.getStreamType()", audit)
        self.assertIn("getStreamVolume(stream)", audit)
        self.assertIn("getStreamMaxVolume(stream)", audit)
        self.assertIn('MARKER = "audio-stream"', audit)
        for path in (LIBRETRO_SESSION, PPSSPP_SESSION):
            source = path.read_text(encoding="utf-8")
            self.assertIn("EngineAudioLog.logResolvedStream", source,
                          f"{path.name} never logs its resolved audio stream")


if __name__ == "__main__":
    unittest.main()
