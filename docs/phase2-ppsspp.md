# Phase 2 PPSSPP integration checkpoint

Status: **experimental, buildable, qualification-packaged, not release-qualified,
not shipped**.

This checkpoint establishes a pinned Android ARM64 compiler/linker source-build
proof for the official PPSSPP libretro target. The limited FFmpeg-off profile
reproduces byte-for-byte; the production candidate remains unqualified and
`build.reproducible=false` until the remaining gates pass. Lucent hosts that
target through its own libretro ABI implementation. No RetroArch frontend
source, package, configuration, menu, or runtime is used.

## Pinned upstream identity

- Repository: <https://github.com/hrydgard/ppsspp>
- Release tag: `v1.20.4`
- Annotated tag object: `3a31057b7e44270b4d5cef8c31b6559d51802a3b`
- Peeled source commit: `fa50bb1976065c4f8b1b47af227d367fe9771555`
- Source archive SHA-256:
  `9054138072d49c306d65c17059bd85662b4ff46abe1ea6bb53d854dc80592ea6`
- Android target: `arm64-v8a`, API 23, NDK `27.0.12077973`

`engines/build_core.sh ppsspp` verifies every archive before extracting it,
stages the exact compile-input closure for this Android ARM64,
`LIBRETRO=ON`, `USE_FFMPEG=OFF` profile into a clean source tree, and builds
`ppsspp_libretro_android.so` with CMake/Ninja. This wording is intentionally
configuration-specific: it does not claim every unused upstream gitlink is
part of the staged closure. The recipe does not trust floating branches or
live submodule checkouts.

The recipe ignores generic CMake, Ninja, NDK-version, and NDK-root overrides
for this proof and verifies the locked Darwin binaries/metadata before fetching
source. Builds sharing one `BUILD_ROOT` are serialized by an atomic lock;
callers may instead use a distinct `LUCENT_ENGINE_BUILD_DIR`. The checked-in
version patch is SHA-256 verified before application.

The archive has no `.git` directory, so Lucent applies a narrow checked-in
patch that fixes PPSSPP's reported build version to `v1.20.4-fa50bb1` instead
of allowing it to become host-dependent or `unknown`. The final staged ELF is
stripped with the pinned NDK's `llvm-strip --strip-unneeded`. Compile steps set
`SOURCE_DATE_EPOCH=1778934711`, the pinned commit timestamp, so embedded C
`__DATE__`/`__TIME__` strings cannot vary with the local build clock. The same
steps force `LC_ALL=C` and `TZ=UTC` to remove locale and timezone drift. Clang
file/debug/macro prefix maps replace repository and staging paths so the ELF
does not retain the builder's absolute workspace path.

## Limited-profile reproducibility evidence

Two sequential builds each re-extracted all checksum-verified inputs, restaged
the source tree, compiled all targets, and stripped the final ELF. The outputs
were byte-identical:

- Artifact: `engines/build/arm64-v8a/ppsspp_libretro.so`
- SHA-256: `734ba9e0c1e7040b16b0a1e6d2183914e8f9e203b9c3102899427425a925c3ba`
- GNU Build ID: `f791f34db0009b16b9a1b0e6713c90c115db10a1`
- Absolute builder workspace/SDK path matches: `0`

This evidence applies only to the declared Android ARM64 compiler/linker proof
with `USE_FFMPEG=OFF`. It does not qualify PSP gameplay, video playback,
renderer lifecycle, save states, performance, hardware, packaging, or
distribution, and therefore does not open any release gate.

## First build scope

The first source-build proof uses:

- `LIBRETRO=ON`
- OpenGL ES and Vulkan compilation enabled by upstream's Android target
- `USE_FFMPEG=OFF`
- `OPENXR=OFF`
- `USE_MINIUPNPC=OFF`
- Discord, tests, Atlas tool, and ccache disabled

OpenXR-SDK and miniupnp remain in the source closure even with their runtime
features disabled because PPSSPP still compiles translation units that include
their headers. Omitting either makes the clean build fail closed.

The resulting first proof is an ELF64 AArch64 shared object and exports the
required `retro_init`, `retro_load_game`, `retro_run`, `retro_serialize`, and
`retro_unserialize` entry points. Its dynamic dependencies are Android platform
libraries only (`log`, `OpenSLES`, `android`, `GLESv2`, `EGL`, `dl`, `m`, and
`c`). That is a compiler/linker proof, not a gameplay qualification.

FFmpeg is disabled because PPSSPP's pinned FFmpeg submodule is a very large,
separate source/distribution closure. PSP video and game compatibility can be
incomplete without it. Lucent must audit, pin, build, and test FFmpeg before a
candidate can advance; the FFmpeg-off artifact must never be represented as a
production core.

## Qualification-only Android package

Lucent can now package the exact compiler proof behind the explicit
`LUCENT_INCLUDE_PHASE2_PPSSPP=1` build flag. The package includes a separate
Phase 2 registry and fail-closed opt-in (`autoSelect=false`), verifies the core
hash again inside the signed APK, installs the pinned `PPSSPP` runtime asset
tree into app-private storage, and routes an explicit `engine_id=ppsspp`
request through Lucent's own GLES surface and controller path. Normal Lucent
builds contain none of these assets and do not select this route.

This is a packaging and renderer qualification milestone, not a release gate.
The qualification session has PCM transport, durable save RAM, failure-honest
Quick Resume, ten-minute active-play checkpoints with restore history,
controller remapping and dual-stick input, phone touch fallback, and a
Lucent-owned GLES surface. Native host calls remain render-thread-affine, and
state identity includes both the ROM SHA-256 and the exact pinned PPSSPP commit.
FFmpeg, Vulkan, checkpoint screenshots, a legal runnable PSP fixture, repeated
state cycles, sustained profiling, and broad device coverage remain absent.

## AYN Thor runtime checkpoint (2026-08-07)

Lucent's combined qualification APK packaged the exact PPSSPP artifact together
with the Phase 1 cores. Both signed-APK payload verifiers passed before install.
The final exact run used APK SHA-256
`12e0e83494a75b0f709b93b2a0c4efed9810f7c1632dc5272f2e9278ead37006`.
Gameplay remained in `org.pegasus_frontend.android.MainActivity` in Lucent's
single process/task/window on the upper 1920x1080 display; no PPSSPP
application, external emulator activity, RetroArch frontend, or on-screen
controls appeared. The private, excluded-from-recents `PreviewActivity` was
confined to display 4 and rendered black throughout single-screen gameplay.

Four user-supplied PSP images available on the device reached visible boot or
game content inside Lucent:

- LittleBigPlanet
- Castlevania: The Dracula X Chronicles
- God of War: Chains of Olympus
- God of War: Ghost of Sparta

This commercial-content smoke run is compatibility evidence only. It is not a
redistributable fixture and does not close the `legalContent` gate.

The first physical run exposed two ABI crashes in Lucent's host and one timing
defect, all now covered by host regressions or the render-loop contract:

1. `SET_INPUT_DESCRIPTORS` and `SET_PERFORMANCE_LEVEL` were incorrectly parsed
   as `retro_variable` arrays. They now use their actual payload types.
2. PPSSPP requires `GET_LOG_INTERFACE`; Lucent now supplies the libretro log
   callback instead of allowing a null call target.
3. The render loop used to wait a full frame interval *after* emulation and GPU
   work, making a nominal 60 Hz core run below real time. It now schedules
   absolute frame deadlines. Repeated AudioFlinger samples on the Thor then
   stayed at zero underruns instead of accumulating thousands of missing frames
   per second. The streaming buffer was reduced from roughly 200 ms to the
   platform minimum or 50 ms, whichever is larger.

The final automated gate dispatched physical A down/up through the Odin
Controller node, observed 1,246,166 changed upper-display pixels, and measured
60.44 FPS with 219,264 received/written stereo frames and no AudioTrack
underruns. Holding the physical Thor Stop/Select button for 1.15 seconds
returned to the same Lucent `MainActivity` only after the state commit
completed. A forced process death then restored Quick Resume with live video
and audio at 60.42 FPS. The lower panel capture contained zero visible pixels.
The machine-readable evidence is
`unified-android/build/one-window-phase2-psp-hardened-stop-final2/results.json`.

These results pass the exact one-window physical GLES, input, audio, lower-panel
blanking, Stop-return, and one process-death restore checkpoint,
but do not change `shipped=false` or any aggregate registry gate. One device,
four titles, one exact state journey, and a short audio run do not satisfy the
20-title/100-cycle, Vulkan, single-screen-device, remapping, migration, thermal,
or sustained frame-pacing requirements.

The same build generates and packages a deterministic SPDX 2.3 document at
`assets/phase2-sbom.spdx.json`. It identifies the exact PPSSPP source archive,
the packaged Android ARM64 binary hash, and every staged dependency archive in
`engines/ppsspp-source-lock.json`. Unknown dependency conclusions remain
`NOASSERTION`; the SBOM is provenance evidence, not a substitute for the open
file-level license audit.

## Firmware and assets

PPSSPP is an HLE emulator and upstream explicitly states that it requires no
PSP BIOS. It does require PPSSPP's own redistributable runtime assets under the
frontend system directory (`PPSSPP/compat.ini`, fonts, HLE flash files, and
related data). The recipe stages the pinned upstream `assets` tree separately
as `ppsspp-system/PPSSPP`; these assets are not Sony PSP firmware.

Commercial games, decrypted modules, keys, Sony firmware, and PSP SDK content
are never fetched or distributed by Lucent.

## Dependency and license checkpoint

The build pins the following staged compile-input closure, including the two
nested dependencies needed by those inputs. Archive hashes are embedded in the
recipe. This is not a claim that unused upstream gitlinks are enumerated.

| Path | Commit | Preliminary license evidence |
| --- | --- | --- |
| `ext/SPIRV-Cross` | `4212eef67ed0ca048cb726a6767185504e7695e5` | Apache-2.0 |
| `ext/aemu_postoffice` | `530fee545c27ffb8524a8f496cbbcfdb687fe8c5` | GPL-3.0-only file present |
| `ext/armips` | `a8d71f0f279eb0d30ecf6af51473b66ae0cf8e8d` | MIT |
| `ext/armips/ext/filesystem` | `3f1c185ab414e764c694b8171d1c4d8c5c437517` | MIT |
| `ext/cpu_features` | `fd4ffc1632db7b4e763bd28ffa6fc9d761cf3587` | Apache-2.0 |
| `ext/glslang` | `50e0708ec3a5c16020c4f845c654b80b8edb80bd` | mixed permissive notices; file-level audit required |
| `ext/libadrenotools` | `8fae8ce254dfc1344527e05301e43f37dea2df80` | BSD-2-Clause |
| `ext/libadrenotools/lib/linkernsbypass` | `aa3975893d83ef1bc84c321ec60c65fbf1287887` | BSD-2-Clause |
| `ext/libchdr` | `8bba7745d758627258b315997a860039244cedaf` | BSD-3-Clause-style notice |
| `ext/lua` | `7648485f14e8e5ee45e8e39b1eb4d3206dbd405a` | MIT text in `lua.h` |
| `ext/miniupnp` | `27d13ca9beeb5541f5fbf11959dced03dac39972` | BSD-3-Clause |
| `ext/naett` | `5f695cfa9fcbf30668a4d3ac4b4abf1cd89a1302` | MIT |
| `ext/OpenXR-SDK` | `be392bf6949adeeabad5082aa79d12aacbda781f` | Apache-2.0 plus file-scoped notices |
| `ext/rapidjson` | `73063f5002612c6bf64fe24f851cd5cc0d83eef9` | MIT plus bundled third-party notices |
| `ext/rcheevos` | `ebfe8ca1bf944358e27200d66964fcb4e00e2487` | MIT |
| `ext/zstd` | `f8745da6ff1ad1e7bab384bd1f9d742439278e99` | BSD-3-Clause |
| `libretro/libretro-common` | `76a3d54feb0ee0ce9d59b90aa24694f3782063d3` | file-scoped permissive/public-domain notices |

PPSSPP itself states GPL-2.0-or-later and its top-level license also includes
PSPSDK-derived BSD-compatible notices. This preliminary mapping indicates a
plausible GPLv3-compatible combination, but it is not final legal approval.
Every compiled file and staged asset still needs a generated corresponding-
source manifest and notice review before distribution.

## Legal qualification fixture

PPSSPP's pinned `pspautotests` submodule includes useful binaries but its
`LICENSE.txt` literally contains an unfilled license placeholder. Lucent will
not copy, execute as a release fixture, or redistribute those binaries.

Instead, `engines/qa/fixtures/ppsspp-minimal` contains Lucent-owned CC0 source
for a minimal PSP homebrew screen. No PBP is checked in. Producing one is gated
on a deterministic, source-built, fully noticed PSPDEV/PSPSDK toolchain. This
keeps the candidate fail-closed rather than asserting rights that are not
documented.

## Gates before approval

1. Add the exact FFmpeg source and license closure, then rebuild twice and
   compare stripped artifacts/build IDs.
2. Complete file-level notices for the core, all dependencies, and assets.
3. Build and hash the CC0 homebrew fixture using a pinned source toolchain.
4. Run Lucent's callback, video/audio, input, GLES, Vulkan, suspend/resume, and
   100-cycle serialize/unserialize tests with legal content.
5. Validate Quick Resume migration across PPSSPP versions and reject stale
   states safely.
6. Profile representative games on supported Android hardware.
7. Keep `shipped=false` and out of APK packaging until every gate passes.
