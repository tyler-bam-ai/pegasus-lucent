# Phase 2 ARMSX2 integration checkpoint

Date: 2026-08-07

Status: **experimental, reproducibly buildable, qualification-packaged, not
release-qualified, not shipped**.

Lucent uses ARMSX2 as the current Vulkan-first in-process PlayStation 2
candidate. It runs inside Lucent's existing
`org.pegasus_frontend.android.MainActivity`; no ARMSX2 or external emulator
activity is launched.

## Exact build identity

- Upstream: <https://github.com/ARMSX2/ARMSX2>
- Commit: `788a59d641c777cb7f70726ea573d420508e0931`
- Source archive SHA-256:
  `bc2bb2f106ca499252e3bfe0a40366de5e9749fd84bebbbe80ed52c4b07abf63`
- Compile closure: `engines/armsx2-source-lock.json`
- Integration patch: `engines/patches/armsx2-libretro-android-build.patch`
- Recipe: `engines/build_core.sh armsx2`
- Final AArch64 ELF SHA-256:
  `14baf81c3df6be7e34b7461ee9557616c4996ed49f137a948efb13303af35a13`

The recipe pins NDK `27.0.12077973`, CMake `3.31.6-g38307f9`, Ninja `1.12.1`,
API 26, all seven shaderc dependencies, the Lucent integration patch, locale,
timezone, source epoch, path maps, and `--build-id=none`. Two isolated clean
source/build roots produced byte-identical stripped files (24,637,656 bytes)
with no GNU Build ID. Their 114-file runtime-asset trees also matched,
including `GameIndex.yaml` and `patches.zip`.

## AYN Thor checkpoint

The qualification APK launched the user's God of War image on the Thor's top
display with ARMSX2 in Lucent's Vulkan host. The exact APK run demonstrated:

- a visible game frame and live 48 kHz stereo audio;
- a physical A-button down/up delivered from the Odin Controller input node;
- current interval performance near 60 FPS after warm-up;
- Quick Resume commit before exit; and
- a forced process death followed by successful state restore and continued
  video/audio.

The runtime-asset revision fix also removed the previous `patches.zip` warning.
This is a one-title, one-device qualification checkpoint, not a compatibility
or release claim.

The current product-contract run used exact hardened APK SHA-256
`12e0e83494a75b0f709b93b2a0c4efed9810f7c1632dc5272f2e9278ead37006`.
God of War remained in the same `MainActivity`, task, window, and process;
display 4 was black with a measured visible fraction of `0.0`; physical A and
held Stop were delivered through the Odin Controller node; Quick Resume was
committed; and a forced process death restored live video and audio. Health was
59.52 FPS at 48 kHz before exit and 59.65 FPS after restore, with zero audio
underruns. Evidence is in
`unified-android/build/one-window-phase2-hardened-available-matrix/results.json`.

## Gates still closed

- Complete file-level dependency/license and notice audit.
- Legally redistributable PS2 fixture with a reproducible toolchain.
- User-BIOS identity/policy and a multi-region firmware matrix.
- 100 state cycles, ten-minute history, reboot, corruption, and migration QA.
- Broad title compatibility, normal memory-card saves, surface-loss recovery,
  sustained thermal/frame-pacing/audio testing, and single-screen devices.
- Thor dual-display behavior and 16 KiB-page Android compatibility.

The registry therefore keeps `reproducible=false` for the overall release
candidate and all distribution/runtime/device gates closed despite the exact
recipe's byte reproducibility and successful Thor checkpoint.
