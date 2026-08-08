# Dolphin Phase 2 in-process candidate

Status: **qualification-packaged; release gates remain fail-closed**.

Lucent pins the maintained Dolphin libretro fork at commit
`0ff12a5a2835762e0665afe6a161a648b433f996` and the exact 30-input source
closure in
[`engines/dolphin-source-lock.json`](../engines/dolphin-source-lock.json).
The lock fixes Android API 26, NDK 27.0.12077973, CMake 3.22.1, Ninja 1.10.2,
and the Android ARM64 libretro profile. Build it with:

```sh
engines/build_core.sh dolphin
```

Two isolated builds differed only in the linker-generated
`.note.gnu.build-id`. Removing that non-runtime note produced the same stripped,
16 KiB-aligned AArch64 core:

- artifact: `engines/build/arm64-v8a/dolphin_libretro.so`
- normalized SHA-256: `c071d3810f74db7a38c499a9725021b62992417177c80a7b6074692b14864ced` (confirmed by two clean staged builds)

The candidate runs through Lucent's existing in-process GLES libretro host; it
does not launch Dolphin's Android Activity or Java/JNI frontend. GameCube and
Wii are qualification routes only. Lucent installs the pinned `Data/Sys` tree
into app-private storage and selects a 2x EFB profile (1280x1056), synchronous
shader compilation, and wait-for-shaders. The 2x profile is the highest integer
scale whose entire core viewport fits a 1920x1080 presentation target.

This closes the source, Android compiler/linker, packaging, and in-process host
integration work. The complete dependency-license audit, legal distributable
test content, save-state compatibility, repeated process-death recovery,
surface lifecycle, sustained performance, and three-title-per-system physical
device matrices remain independent release gates and must fail closed until
their recorded QA passes on the exact release APK.
