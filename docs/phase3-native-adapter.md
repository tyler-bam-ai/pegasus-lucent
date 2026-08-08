# Phase 3 native-adapter architecture (Wii U first)

## Why

PS3 (RPCSX/aPS3e), Wii U (Cemu), and Switch (Yuzu-derived: Citron/Sudachi/Suyu;
Ryujinx is C#/.NET and not in-process embeddable on Android) have **no libretro
core**. Each is a standalone C++/Vulkan engine with an Android JNI wrapper. To
emulate them INSIDE Lucent — one app, one display-0 window, Lucent-owned
surfaces/input/audio/save, no external Activity — each engine is built as a
shared library implementing the Lucent native-adapter ABI and driven in-process.

This supersedes, for the END GOAL, the interim "route Phase 3 external" behavior;
external routing stays as the fallback until an internal adapter is qualified.

## The contract

`unified-android/native/include/lucent_native_adapter.h` defines the C ABI: an
adapter `.so` exports one symbol, `lucent_native_adapter_entry()`, returning a
const vtable (`describe/create/load/start/run_frame/set_control/pause/resume/
flush_save/serialize*/surface_recreated/stop/destroy`) plus an honest
`lucent_native_capabilities` (has_quick_resume, has_persistent_save, dual_screen,
required_firmware). Lucent verifies `abi_version` before any call and fails
closed on any missing capability — never faking a Quick Resume the engine can't
guarantee.

## Components (this milestone)

1. `include/lucent_native_adapter.h` — ABI (DONE).
2. `lucent_native_adapter_host.c` — loads an adapter `.so` by pinned path+hash,
   validates the vtable, and exposes a thin C driver used by JNI.
3. `NativeAdapterEngineSession.java` — implements `EngineSession`; owns the
   Android Surface(s), runs the frame loop on one render-owner thread (reusing
   the quiesce/detach discipline from `ExperimentalGlesRenderLoop`), maps
   `InputRouter` controls to `lucent_native_control`, wires audio to an
   `AudioTrack`, and reports capabilities to Lucent honestly (Wii U initially
   `has_quick_resume=false` → held-Stop flushes saves and exits, no fake QR).
3b. Dual screen: Wii U TV → display 0, GamePad → display 4 via the existing
    `SecondaryGameplaySurfaceRouter`, same path as DS/3DS.
4. `NativeAdapterCatalog.java` — registers native-adapter engines (Cemu) from
   the signed `engines/phase3-registry.json` + a `reproducibility-lock` artifact
   hash; fail-closed and absent unless the core `.so` is present in the APK.
5. `engines/cemu-source-lock.json` — pins Cemu source/deps (repo+commit already
   in `phase3-registry.json`).
6. Build recipe skeleton (`engines/build_native_adapter.sh` or a `build_core.sh`
   branch): reproducible Android ARM64 build of the Cemu engine as an adapter
   `.so`. **This is the multi-week heavy lift and is NOT done here.**
7. Routing: Wii U resolves INTERNAL (this adapter) when the core is present and
   qualified, else EXTERNAL (Cemu app) — handled in `EngineRouteStore` /
   `GameLaunchRouter` with a fail-closed default.

## Testing without the real core

A mock adapter (`native/tests/mock_native_adapter.c`) implements the vtable with
a deterministic checkerboard + tone, so `native/run_tests.sh` proves the host,
vtable validation, capability gating, load/start/run/stop lifecycle, and
fail-closed paths under ASan/UBSan — exactly as `mock_core.c` does for libretro.
The real Cemu core plugs into the same host with no Java/host changes.

## Firmware/keys (legal gate)

Wii U requires user-supplied OTP/keys + system content. Lucent validates hashes
and passes read-only paths (`lucent_native_load_request`); it never bundles or
downloads them. `load()` fails closed when they are absent/invalid.

## Definition of done (per handover §8.6)

Same one-app, real-menu, controller, save, return, legal, reproducibility, and
exact-artifact gates as Phases 1/2. "The emulator exists on Android" is not
sufficient; the adapter must run several Wii U titles at full speed with sound,
correct dual-screen routing, and a clean held-Stop return.
