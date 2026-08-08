# Lucent unified Android package

This build combines the official 64-bit Pegasus Android runtime, the Lucent
theme, preview player, library importer, media enrichment, updater, ROM launch
bridges into one APK. During gameplay the Thor Stop button is handled directly
inside Lucent's existing MainActivity, so no accessibility service or external
emulator task participates in launch, play, save, or return.

The application id intentionally remains `com.thorium.preview`, so it upgrades
the earlier Lucent companion in place and retains its updater state. The
embedded Pegasus Java/JNI class names stay unchanged for binary compatibility.

The base runtime is Pegasus Frontend `alpha16-105-g6b322063`, built from
[`mmatyas/pegasus-frontend`](https://github.com/mmatyas/pegasus-frontend) and
licensed under GPLv3. Lucent is a modified distribution and must be distributed
with corresponding source and the GPLv3 license. Product logos and trademarks
remain the property of their respective owners.

Run `./build.sh` to create `build/lucent-3.2.15.apk`.

## Phase 1 native engine foundation

The default APK now contains Lucent's independent `libretro` API host and the
checked-in engine registry, but no emulator core binaries. The host loads only a core from
an explicitly supplied app-private trusted directory, validates the complete
mandatory API v1 symbol set, and exposes load, run, pause, serialize, restore,
and unload through `LibretroHost`. Hardware-rendered cores are deliberately
rejected until the gated native surface path is connected to `GameSurface`.

For software-rendered cores, the host transports bounded pitched video frames,
interleaved stereo PCM, mapped joypad/trigger/HAT state, signed dual-stick
analog input, AV timing, serialized states, and crash-safe save RAM. A private
Lucent game activity supplies integer-scaled rendering, automatic controller
profiles, touch fallback when no gamepad exists, a minimal pause menu, Quick
Resume, and ten-minute checkpoint history. Save identity follows ROM content,
so moving a game between internal and removable storage does not orphan it.
The native mock-core test asserts every callback and round trip rather than
testing interfaces in isolation.

Run `native/run_tests.sh` for the host lifecycle/state proof and
`../tools/validate_engine_registry.py` for source/license gate validation.
Pinned development builds for mGBA and Mesen are available through
`../engines/build_core.sh`; their outputs are neither committed nor packaged.

For an emulator-only qualification APK, set
`LUCENT_INCLUDE_EXPERIMENTAL_CORES=1`. This compiles the pinned, source-audited
Mesen, Mesen-S, SameBoy, mGBA, Gearsystem, SwanStation, melonDS DS, Fuse, and
MAME candidates. The flag is off by default, qualification cores are absent
from the release APK, and they are not selected automatically. Gearcoleco and
its ColecoVision BIOS are deliberately excluded because the pinned upstream
source/build path does not reproduce the candidate BIOS byte-for-byte. Add
`LUCENT_AUTOSELECT_EXPERIMENTAL_CORES=1` only for an explicitly labeled
qualification build; that second flag is rejected unless the first is also
present and can never affect a default build.

`tools/run_phase1a_activity_qa.py` defaults to an ARM64 emulator and permits an
explicitly verified AYN Thor only with `--allow-physical-thor`. It launches every
runnable candidate directly into Lucent's one `MainActivity` on display 0. The
Thor gate permits the same-package, excluded-from-recents preview Activity only
on display 4 and proves that lower display stays black during single-screen
gameplay. It also requires a core-originated RGB frame, visible screenshot,
same-task return, and Quick Resume commit/restoration.

`tools/run_phase1_physical_library_qa.py` is the final, non-destructive hardware
matrix. Given one explicit APK, it selects up to three signature-valid existing
Thor titles per available Phase 1 system, records only opaque IDs publicly, and
fills absent systems with redistributable fixtures. Every case proves the same
APK hash, physical controller consumption, audio and pacing telemetry, the
one-second Stop/Select return, and state restoration after process death. It
never clears app data or copies, renames, or deletes user content.

The independent host also contains a fail-closed Phase 2 hardware-negotiation
foundation for GLES and Vulkan. The production JNI still creates a
software-only host, so this does not enable or ship hardware cores. See
[`../docs/phase2-hardware-render-host.md`](../docs/phase2-hardware-render-host.md)
for the implemented ABI boundary and the exact Android surface/context work
that remains.

The ARM64 host library now also compiles an Android-only EGL/GLES owner. It
probes supported GLES versions/configs, owns an `ANativeWindow` surface,
enforces render-thread lifecycle ordering, presents only newly submitted
hardware frames, and distinguishes orderly detach from `EGL_CONTEXT_LOST`.
It remains disconnected from production JNI, and it does not advertise Vulkan.

An isolated `ExperimentalGlesLibretroHost` wrapper is compiled for off-device
Phase 2 qualification. It can attach an Android `Surface`, run and present a
new hardware frame, detach, and recreate after context loss, but no production
engine or catalog entry references it. `native/verify_android_gles_jni.sh`
checks its generated Java signatures against the ARM64 JNI exports.
That gate also runs the deterministic qualification lifecycle through the Java
wrapper without loading a native library or touching an Android device.

`ExperimentalGlesRenderLoop` adds a dedicated, paced render-thread owner around
that wrapper for qualification. It serializes Surface lifecycle, forwards PSP
controller input on that same thread, and pauses on context loss until explicit
recreation. `Phase2QualificationCatalog` and `PpssppGlesEngineSession` connect
this path only when a specially built APK contains the exact signed registry,
opt-in, artifact identity, core, and runtime-asset payload. The ordinary engine
catalog never auto-selects it.

Build that isolated multi-core package with
`LUCENT_INCLUDE_PHASE2_PPSSPP=1 LUCENT_REUSE_PHASE2_PPSSPP=1 ./build.sh` after
all six pinned Phase 2 artifacts exist. It packages PPSSPP, Play!, ARMSX2,
Flycast, Azahar, and Virtual Jaguar; each binary remains explicitly
qualification-only and non-auto-selected. It produces
`build/lucent-3.2.15-phase2-qualification.apk`, verifies the signed APK payload,
and leaves the normal `lucent-3.2.15.apk` untouched. The qualification session
now includes PCM audio, durable save RAM, and Quick Resume keyed by the ROM and
their exact source/artifact locks. This is not a production package: checkpoint history,
100-cycle/process-death/version-migration state QA, FFmpeg, Vulkan, legal
runnable test content, performance, and physical-device evidence remain open.
