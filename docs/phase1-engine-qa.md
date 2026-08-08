# Phase 1 engine and fallback QA

Audit date: 2026-08-07

## Result

**OPERABILITY QUALIFICATION IS PARTIAL; BLOCKED for public release.** The
registry contains 26 engines covering 34 systems. The exact emulator core
matrix currently records 17 runnable systems and 17 explicit blocked systems;
no system is silently omitted. Source identity and Android ARM64 feasibility
pass the catalog audit, but no in-process engine is approved or shipped. Every
Phase 1B engine remains license-blocked, unqualified, non-reproducible, and
excluded from automatic selection. The unified Lucent build has no
external-emulator fallback: an engine that has not passed the internal release
gate remains unavailable.

SwanStation and melonDS DS now have exact, verifier-enforced reproducibility
locks. This closes their build-identity gate only; it does not close their
dependency/license, state-boundary, performance, or release-device gates.

This is an engineering gate, not a legal opinion. A dependency and corresponding-
source review is still required before any compatible candidate is distributed.

### Hard closure gates (2026-08-07 audit)

- Registry state is intentionally fail-closed: **0/26** engines are state
  qualified and **0/26** are shipped.
- Only **7/26** engines have byte-identical two-build evidence: ProSystem,
  SwanStation, melonDS DS, DOSBox Pure, Beetle PCE Fast, Beetle NeoPop, and
  Beetle Cygne. This evidence does not close their other gates.
- The qualification SBOM uses `licenseConcluded: NOASSERTION`; compatible
  candidate declarations are not a completed transitive dependency,
  corresponding-source, copyright, or notice audit.
- In the frozen `cb0ba813` APK, **13 of the 14 Phase 1 candidate ELFs** have
  4 KiB `PT_LOAD` alignment. Only DOSBox Pure has 16 KiB alignment. A public
  Android release requires every packaged native library to pass the 16 KiB
  page-size gate.
- The legal core probe is now **17 PASS / 0 FAIL / 17 BLOCKED**. DOSBox Pure
  additionally passes the real Lucent `MainActivity` route on the ARM64 Android
  emulator, including visible output, Quick Resume commit, and restore after
  process death. Emulator evidence does not close physical-device performance,
  controller, display timing, power-loss, or long-session gates.

None of these gates may be converted to an approval merely because a core
loads content or passes the callback harness.

### Final exact-artifact Phase 1 qualification

The frozen combined Phase 1/2 qualification APK is
`unified-android/build/lucent-3.2.15-production-cb0ba813.apk`, SHA-256
`cb0ba81349091191fb27d8390c0eb939bf0b0a941bf571003b474b2930f96536`.
Both signed-APK payload verifiers pass.

On the AYN Thor (`427c87b2`), the final non-destructive Phase 1 matrix passes
**32/32** with zero failures. It covers three signature-valid user titles each
for NES, SNES, Game Boy, Game Boy Color, Game Boy Advance, Game Gear,
PlayStation, and Nintendo DS, plus redistributable fixtures for SG-1000, Master
System, ZX Spectrum, Arcade, Neo Geo, PC Engine, Neo Geo Pocket, and WonderSwan
Color. Every case proves a core-originated visible frame, PCM audio, physical
controller input, target pacing within the strict tolerance, a single Lucent
`MainActivity`/task/process, lower-display blacking for single-screen games,
one-second physical Stop return, and Quick Resume restoration after process
death. Exact evidence is in
`unified-android/build/phase1-full-cb0ba813/results.json`.

Normal library routing is independently proven **3/3** with physical A-button
launches for NES/Mesen, Game Boy/SameBoy, and Game Gear/Gearsystem. The launch
migration covers the legacy `metadata-systems` tree as well as current metadata
directories, preventing old external-emulator routes from resurfacing. Exact
evidence is in
`unified-android/build/phase1-library-a-cb0ba813/results.json`.

| Audit lane | Result | Evidence |
|---|---|---|
| Exact source pin | **PASS** | All 26 repository/40-character commit pairs returned from the GitHub commit API on the audit date. Archive URLs are now validator-enforced to use the same repository and commit. Stella 2023 was corrected from the Stella 2014 repository to [libretro/stella2023](https://github.com/libretro/stella2023/tree/878a9c8d5f03ef0b7cd190b5713d6bf31c48df38); MAME was corrected from upstream MAME (which is not a libretro core) to [libretro/mame](https://github.com/libretro/mame/tree/85eaed9c22242206b68eaca8310cf0dbde331b43). |
| SPDX/dependency gate | **BLOCKED** | Tier 1B stays `license-blocked`; Tier 1A stays experimental. The authoritative policy is [registry.json](../engines/registry.json). |
| Android ARM64 feasibility | **PASS (feasibility only)** | A corresponding artifact exists in the official [Libretro Android ARM64 buildbot directory](https://buildbot.libretro.com/nightly/android/latest/arm64-v8a/) for every cataloged core. This does not prove Lucent reproducibility, performance, or device compatibility. Mupen uses the GLES3 artifact; MAME uses `mamearcade`; Mesen-S uses the hyphenated artifact name. |
| Serialization claim | **PASS (metadata claim only)** | Claims were reconciled with Libretro's checked-in [core info database](https://github.com/libretro/libretro-super/tree/master/dist/info). `unverified` is retained when metadata does not make a save-state claim. No entry is marked state-qualified. |
| Firmware semantics | **PASS (catalog semantics)** | Universal requirements are distinguished from optional and system-conditional requirements. Conditional systems must be a subset of the engine's mapped systems. User firmware is not bundled. |
| Release packaging | **PASS (deny-by-default)** | The [validator](../tools/validate_engine_registry.py) prevents Phase 1B approval/shipping. [InternalEngineCatalog.java](../unified-android/src/com/thorium/preview/game/InternalEngineCatalog.java) only release-selects approved, shipped, qualified, reproducible entries with a matching artifact identity. |
| Qualification packaging | **PASS (deny-by-default)** | [qualification-opt-in.json](../engines/qualification-opt-in.json) is qualification-only and `autoSelect: false`; the validator now rejects any checked-in default that auto-selects. |
| Qualification compliance payload | **PASS** | Packaging generates an exact source-to-binary SHA manifest, SPDX 2.3 SBOM, and human notices for all nine included candidates, then verifies the signed APK. Missing or mismatched identities stop the build. |
| Reproducible build identity | **PARTIAL** | SwanStation and melonDS DS have verifier-enforced, byte-identical two-build locks. Every other Phase 1A candidate remains `reproducible=false`. |
| One-app fail-closed routing | **PASS structurally; exact-artifact device regression pending** | Unified metadata routes only to Lucent's existing `MainActivity`. External-emulator and superseded game-Activity bridges are excluded from the APK. Unsupported engines remain unavailable. |

## Per-system disposition

The final column is retained only as historical research from the earlier
companion/theme architecture. It is **not** a route available in the unified
Lucent APK and cannot satisfy any current release gate.

Legacy legend:

- **PASS / external** means the internal candidate remains unavailable, but a
  selected direct-launch external fallback is present.
- **BLOCKED / unsupported** means Lucent deliberately exposes no automatic
  emulator install/launch until a maintained direct-launch fallback or an
  approved internal engine exists.
- Every row remains **BLOCKED / internal** for release until its engine satisfies
  the registry's license, archive, reproducibility, state, firmware, performance,
  and device gates.

| System | Tier / internal engine | Source, state, and firmware evidence | Historical external disposition (not packaged) |
|---|---|---|---|
| NES | 1A / Mesen | [pin](https://github.com/libretro/Mesen/tree/0102910c39ad1a62bc3f784466f3f67ca9eae335), [metadata](https://github.com/libretro/libretro-super/blob/master/dist/info/mesen_libretro.info): deterministic; no required firmware | **PASS / external:** NES.emu |
| Super Nintendo | 1A / Mesen-S | [pin](https://github.com/libretro/Mesen-S/tree/1d475abd174d16ecb1fb030961ff26076ab51ee6), [metadata](https://github.com/libretro/libretro-super/blob/master/dist/info/mesen-s_libretro.info): state claim unverified; no required firmware | **PASS / external:** Snes9x EX+ |
| Game Boy | 1A / SameBoy | [pin](https://github.com/LIJI32/SameBoy/tree/213a12ce93d66b105a113debd9396306066a7cfc), [metadata](https://github.com/libretro/libretro-super/blob/master/dist/info/sameboy_libretro.info): state claim unverified; boot ROM optional | **PASS / external:** GBC.emu |
| Game Boy Color | 1A / SameBoy | Same evidence as Game Boy | **PASS / external:** GBC.emu |
| Game Boy Advance | 1A / mGBA | [pin](https://github.com/mgba-emu/mgba/tree/afd6f14eaf8bd35214ed3fb9dc69a92bfc3877a9), [metadata](https://github.com/libretro/libretro-super/blob/master/dist/info/mgba_libretro.info): deterministic; no required firmware | **PASS / external:** GBA.emu |
| SG-1000 | 1A / Gearsystem | [pin](https://github.com/drhelius/Gearsystem/tree/3c4cfcf54dfe9e36070815e76ee2bab9e754104f), [metadata](https://github.com/libretro/libretro-super/blob/master/dist/info/gearsystem_libretro.info): deterministic; BIOS optional | **PASS / external:** MD.emu |
| Master System | 1A / Gearsystem | Same evidence as SG-1000 | **PASS / external:** MD.emu |
| Game Gear | 1A / Gearsystem | Same evidence as SG-1000 | **PASS / external:** MD.emu |
| ColecoVision | 1A / Gearcoleco | [pin](https://github.com/drhelius/Gearcoleco/tree/5eb0d5fa8865de28b16a304431ca1d4bd4dd4e96), [candidate minimal BIOS source](https://github.com/sehugg/8bitworkshop/tree/5391cefbc2a3af33e3283362ae854e3862059a07/meta/romsrc/coleco): technical probe passed, but the pinned source/build path did not reproduce the adjacent ROM; no firmware hash is accepted | **PASS / external:** ColEm |
| Intellivision | 1A / FreeIntv | [pin](https://github.com/libretro/FreeIntv/tree/428915baf2bfc032fc03e645f4f8f9c6c3144979): EXEC/GROM required; primary mini substitutes are binary-only, lack corresponding source/file-scoped license evidence, and are intentionally incomplete | **BLOCKED / unsupported** |
| PlayStation | 1A / SwanStation | [pin](https://github.com/libretro/swanstation/tree/5430a4a53b89fa5827c97b84ada29d23317245bc), [metadata](https://github.com/libretro/libretro-super/blob/master/dist/info/swanstation_libretro.info): basic states; external BIOS optional/HLE fallback | **PASS / external:** DuckStation |
| Nintendo DS | 1A / melonDS DS | [pin](https://github.com/JesseTG/melonds-ds/tree/2748dfb9409c94e6828d937d04e334f35127ba7c), [metadata](https://github.com/libretro/libretro-super/blob/master/dist/info/melondsds_libretro.info): serialized; firmware/BIOS/NAND metadata optional | **PASS / internal emulator probe:** nonzero video/audio/input plus 100 state cycles |
| ZX Spectrum | 1A / Fuse | [pin](https://github.com/libretro/fuse-libretro/tree/000a8ae51a5141d75c109a22150e37ba06d57910), [metadata](https://github.com/libretro/libretro-super/blob/master/dist/info/fuse_libretro.info): state claim unverified; model ROMs optional | **BLOCKED / unsupported** |
| Arcade | 1A / MAME | [pin](https://github.com/libretro/mame/tree/85eaed9c22242206b68eaca8310cf0dbde331b43), [metadata](https://github.com/libretro/libretro-super/blob/master/dist/info/mame_libretro.info): deterministic; SHA-256-pinned constrained ARM64 build and ROMless Pong probe passed nonzero video/audio/input plus 100 state cycles | **PASS / external:** MAME4droid Current |
| Neo Geo | 1A / MAME | Same source/build evidence as Arcade; pinned open ngdevkit nullbios and homebrew AES cartridge passed non-black video/audio/input plus 100 state cycles | **PASS / external:** NEO.emu |
| Neo Geo CD | 1A / MAME | Same source/build evidence as Arcade; internal gameplay QA remains blocked because no explicitly licensed open replacement for the distinct CD/CDZ main BIOS was found | **PASS / external:** NEO.emu |
| Atari 2600 | 1B / Stella 2023 | [pin](https://github.com/libretro/stella2023/tree/878a9c8d5f03ef0b7cd190b5713d6bf31c48df38), [metadata](https://github.com/libretro/libretro-super/blob/master/dist/info/stella2023_libretro.info): deterministic; no firmware | **PASS / external:** 2600.emu |
| Atari 5200 | 1B / Atari800 | [pin](https://github.com/libretro/libretro-atari800/tree/9d3bcf283502512052e21c6f1453fbdf7aa3122b), [metadata](https://github.com/libretro/libretro-super/blob/master/dist/info/atari800_libretro.info): state claim unverified; external OS/BIOS metadata optional | **PASS / external:** Colleen |
| Atari 8-bit | 1B / Atari800 | Same evidence as Atari 5200 | **PASS / external:** Colleen |
| Atari 7800 | 1B / ProSystem | [pin](https://github.com/libretro/prosystem-libretro/tree/363b6dfbd3e240762e022c2b4897b4fe55722be3), [metadata](https://github.com/libretro/libretro-super/blob/master/dist/info/prosystem_libretro.info): serialized; BIOS optional | **BLOCKED / unsupported** |
| Amstrad CPC | 1B / Caprice32 | [pin](https://github.com/libretro/libretro-cap32/tree/4abfb8be233bec630f369379fb6c1d92d31f1c7d), [metadata](https://github.com/libretro/libretro-super/blob/master/dist/info/cap32_libretro.info): serialized; no firmware | **BLOCKED / unsupported** |
| Atari ST | 1B / Hatari | [pin](https://github.com/libretro/hatari/tree/92e2874e664be40e1b51b97e0ff925a1aa917f22), [metadata](https://github.com/libretro/libretro-super/blob/master/dist/info/hatari_libretro.info): serialized; user-supplied `tos.img` required | **BLOCKED / unsupported** |
| PC Engine | 1A / Beetle PCE Fast | [pin](https://github.com/libretro/beetle-pce-fast-libretro/tree/b211204c7026dff6e86e79b00185512e2421fff8): byte-identical ARM64 build; original cc65 fixture passed video/audio, input-effect replay, writable save RAM, and 100 exact state cycles | **PASS / external:** PCE.emu |
| PC Engine CD | 1B / Beetle PCE Fast | Same evidence as PC Engine; system-card firmware is conditionally required for CD content | **PASS / external:** PCE.emu |
| Mega Drive / Genesis | 1A / BlastEm | [pin](https://github.com/libretro/blastem/tree/84e567e17b6fb3cea23146c4209c18d50d46cbf0): GPL-compatible Android ARM64 core, 16 KiB page-aligned; no BIOS for cartridge content | **QUALIFICATION-ONLY / internal:** physical menu-driven video/audio/input, state, lifecycle, and performance gates remain |
| Sega CD | 1B / PicoDrive | [pin](https://github.com/libretro/picodrive/tree/6248b51ffbe212ce441de023ccea6b10fa4d7082); regional Sega CD BIOS is conditionally required | **PASS / external:** MD.emu |
| Sega 32X | 1B / PicoDrive | Same pinned PicoDrive candidate; no required BIOS | **PASS / external:** MD.emu |
| Neo Geo Pocket / Color | 1A / Beetle NeoPop | [pin](https://github.com/libretro/beetle-ngp-libretro/tree/a50d5ac288a81f2104ddf43195a4efdd15c72227): byte-identical ARM64 build; MIT Stargunner passed video/audio, input-effect replay, and 100 exact state cycles | **PASS / external:** NGP.emu |
| WonderSwan | 1B / Beetle Cygne | The core is buildable, but monochrome WonderSwan is not advertised until an independently licensed `.ws` fixture qualifies it | **PASS / external:** Swan.emu |
| WonderSwan Color | 1A / Beetle Cygne | [pin](https://github.com/libretro/beetle-wswan-libretro/tree/4b01295838ea89e3f1355bbe4cb5cf98aa6108cd): byte-identical ARM64 build; MIT Bug Witch passed video/audio, input-effect replay, and 100 exact state cycles | **PASS / external:** Swan.emu |
| Virtual Boy | 1B / Beetle Virtual Boy | [pin](https://github.com/libretro/beetle-vb-libretro/tree/3f53a40bf8aa18777514fd4b220960427e312a3f), [metadata](https://github.com/libretro/libretro-super/blob/master/dist/info/mednafen_vb_libretro.info): state claim unverified; no firmware | **BLOCKED / unsupported** |
| Nintendo 64 | 1A / Mupen64Plus-Next | [pin](https://github.com/libretro/mupen64plus-libretro-nx/tree/f275caf4b2bfa1e6d1c51636746ea793f3d80320): two byte-identical pinned Android ARM64 GLES3 builds, 16 KiB page-aligned, AArch64 dynarec, GLideN64, no base-system firmware | **QUALIFICATION-ONLY / internal:** physical menu-driven video/audio/input, state, lifecycle, and performance gates remain |
| Commodore 64 | 1B / VICE x64sc | [pin](https://github.com/libretro/vice-libretro/tree/c8c242db75a559246d6d51017e6dd4ecd75d6a9f), [metadata](https://github.com/libretro/libretro-super/blob/master/dist/info/vice_x64sc_libretro.info): serialized; JiffyDOS optional | **PASS / external:** C64.emu |
| MSX | 1B / blueMSX | [pin](https://github.com/libretro/blueMSX-libretro/tree/0f32f52c48d3e772bfdf0379756f81f00b4e08bc), [metadata](https://github.com/libretro/libretro-super/blob/master/dist/info/bluemsx_libretro.info): serialized; Machines and Databases directories required | **PASS / external:** MSX.emu |
| Odyssey2 / Videopac | 1B / O2EM | [pin](https://github.com/libretro/libretro-o2em/tree/679d6fec04963f6e70a7ec217e3d0ebb1fe472fc), [metadata](https://github.com/libretro/libretro-super/blob/master/dist/info/o2em_libretro.info): basic states; compatible BIOS required | **BLOCKED / unsupported** |
| DOS | 1A / DOSBox Pure | [pin](https://github.com/schellingb/dosbox-pure/tree/7f6e8fb7385fa446d1444d671063268520bf9b54), [metadata](https://github.com/libretro/libretro-super/blob/master/dist/info/dosbox_pure_libretro.info): serialized; no core firmware; deterministic legal ZIP fixture passes 100 state cycles and the Lucent Activity path | Historical external route removed; emulator Activity evidence passes, while physical-device and release gates remain pending |

## Phase 1A build status

The following pinned candidates have an Android ARM64 recipe and have compiled,
but are still qualification-only: Mesen, Mesen-S, SameBoy, mGBA, Gearsystem,
Gearcoleco, FreeIntv, Fuse, SwanStation, and melonDS DS. MAME remains
experimental: its constrained, archive-hashed ARM64 recipe, ROMless Arcade
probe, and open Neo Geo AES probe pass. Its exact qualified binary now has a
source/recipe/NDK/output-SHA cache, but dependency/driver notices, an independent
repeat build, Neo Geo CD firmware, and device qualification remain. A buildbot binary proves
feasibility only and is not a distributable Lucent artifact.

### AYN Thor qualification build

On 2026-08-07, Lucent 3.2.15 (version code 89) was installed over the existing
app on an AYN Thor with the qualification-only core flag enabled. Each test
entered the real library, used the Thor controller's physical A-button event,
verified Lucent's existing `MainActivity` on display 0, and captured a visibly rendered
frame. No core was invoked through the qualification harness.

| Library | Engine | Result |
|---|---|---|
| NES | Mesen | PASS — rendered game content inside Lucent |
| Super Nintendo | Mesen-S | PASS — rendered game content inside Lucent |
| Game Boy | SameBoy | PASS — rendered game content inside Lucent |
| Game Boy Color | SameBoy | PASS — rendered game content inside Lucent |
| Game Boy Advance | mGBA | PASS — rendered game content inside Lucent |
| Game Gear | Gearsystem | PASS — rendered game content inside Lucent |
| PlayStation | SwanStation | PASS — rendered game content inside Lucent |
| Nintendo DS | melonDS DS | PASS — rendered game content inside Lucent |

The Thor library had no user ROMs for SG-1000, Master System, ZX Spectrum,
Arcade, Neo Geo, or Neo Geo CD, so those systems remain covered by the automated
activity fixture matrix rather than this user-ROM run. This evidence does not
change the deny-by-default public release gate above.

## Required release work

No engine may move to `approved` or `shipped` until all applicable items are
complete:

1. Resolve the license/distribution gate and produce notices/corresponding source.
2. Hash every source and dependency archive and prove a reproducible ARM64 build.
3. Qualify save/load across version, content, firmware, and lifecycle boundaries.
4. Record legal firmware identities where firmware is universal or conditional.
5. Pass performance, audio/video, input, lifecycle, and real-device qualification.
6. Add a signed artifact identity matching the exact registry source commit.

Until then, the release behavior is deliberately external or explicitly
unsupported; it cannot silently select an internal candidate.
