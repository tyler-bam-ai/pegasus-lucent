# Phase 2 Azahar integration checkpoint

Date: 2026-08-07

Status: **one retail title passes the complete Thor activity/runtime gate;
multi-title, legal-fixture, source-reproduction, and release gates remain open;
not shipped**.

Lucent checksum-locks Azahar 2125.1.3 source and its official Android ARM64
libretro release ZIP. The SBOM represents the embedded core as
`EXTRACTED_FROM` that ZIP; it does not claim an upstream signature or a local
source-to-binary reproduction.

- Source commit: `b42d0916ba9799297ae0e27c07d56801da1b5de5`
- Source archive SHA-256:
  `8da46436e9d4cd937af2dba5ed39a33c2102e4f833c9051a9bb6e2711831e065`
- Release ZIP SHA-256:
  `4946db52ba9a559834cb3db075544480ac71fa4ae08b090a6708825a012a7b1b`
- Embedded core SHA-256:
  `d723066fa7c812618b5695d94b0fd85594e6c196e9e08ca9ba7134371a8da645`

The first-frame failure was a frontend fence-ordering deadlock. Lucent reset
the current Vulkan swapchain fence before `retro_run`; Azahar waited for that
same now-unsignalled fence while recording the frame, but the frontend could
only submit work that signalled it after `retro_run` returned. The host now
keeps the fence signalled while the core records and resets it immediately
before `vkQueueSubmit`. A source-order regression test protects this invariant.

The corrected route passed the full physical AYN Thor gate with the user-owned
retail image `The Legend of Zelda: A Link Between Worlds` on exact APK SHA-256
`da91c2e923f07e917e05347c858b2cda5f184095cda2d54ff3171933f465e033`.
Evidence in `unified-android/build/phase2-azahar-da91-rerun/results.json`
proves live Vulkan video; a stable 60.02 FPS rolling interval; continuous
32.728 kHz audio; physical A-button down/up with a visible frame response; held
Stop return to the same MainActivity/task; Quick Resume after process death;
one Lucent process/MainActivity/window identity; no external emulator; and a
fully black lower display during single-screen gameplay.

The runnable qualification scope is decrypted, user-owned CCI content. That
scope does not require external keys or system archives. Encrypted content
stays unavailable until Lucent defines and audits an exact user-file identity
policy; Lucent does not accept or distribute console keys.

Required follow-up includes at least two more retail-title runs, a pinned legal
homebrew fixture, 100-cycle state testing, firmware/system-data policy,
dual-screen titles, sustained thermal testing, source reproduction, notices,
and multi-device/16 KiB-page qualification. This single-title pass does not
promote Azahar to a release engine.
