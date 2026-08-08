# Phase 2 Flycast integration checkpoint

Date: 2026-08-07

Status: **Dreamcast one-window checkpoint passed; Naomi and Atomiswave content
blocked; not release-qualified or shipped**.

Lucent packages the pinned Flycast Android ARM64 core only in the explicit
Phase 2 qualification payload. The artifact SHA-256 is
`9f463fd96331dcaf507a853de5c501b06fa2abd5084ebdff5a869f6cce2ad0d5`.
Normal release routing remains fail-closed and no RetroArch frontend is used.

## Hardened AYN Thor checkpoint

Exact APK SHA-256
`12e0e83494a75b0f709b93b2a0c4efed9810f7c1632dc5272f2e9278ead37006`
ran the user's 240pSuite Dreamcast image inside the existing
`org.pegasus_frontend.android.MainActivity` on display 0. There was one Lucent
process, one visible Lucent recents identity, no external emulator activity,
and the private display-4 preview surface was completely black.

The gate measured 60.64 FPS, 44.1 kHz stereo, 225,639 received/written audio
frames, and zero underruns. Physical A input produced a visible response.
Holding the Thor Stop/Select button committed Quick Resume and returned to the
same activity. After forced process death, Flycast restored live video and
audio at 60.59 FPS. Evidence is in
`unified-android/build/one-window-phase2-hardened-available-matrix/results.json`.

## Remaining gates

- The dependency and file-level license audit is incomplete.
- Naomi and Atomiswave have no authorized fixture on the connected Thor and
  their firmware identities remain blocked; Dreamcast evidence must not be
  generalized to those systems.
- Vulkan, repeated context loss, 100 state cycles, migration, broad title
  compatibility, sustained thermal behavior, and additional devices remain
  open.
- The KallistiOS fixture/toolchain must be pinned and its built artifact rights
  recorded before it can become a distributed release fixture.

The registry therefore remains `shipped=false` and does not auto-select the
qualification core in production.
