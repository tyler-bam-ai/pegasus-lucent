# External-emulator routing (Workstream H)

Design note for the per-system launch-route backend. The settings UI and the
`/route/*` HTTP surface named here are **out of scope** for this workstream
(another agent owns `PreviewService`); this document fixes the contract a future
picker will call.

## Product rule

- **Internal is the default** for every system that has a bundled,
  release-qualified in-process engine. Nothing selects external automatically for
  a system that can run internally.
- **External is a per-system user choice**, never a default.
- **Systems with no internal engine route external automatically** so their games
  still launch. Today that is PS3, Wii U, Switch and 3DS (not yet integrated
  internally). They boot the external emulator **directly into gameplay** — no
  emulator menu.
- Choosing or needing external **installs nothing silently**. When the chosen
  emulator is absent the launch opens its official install source and lets
  Android's package installer confirm.

## Components

| Piece | Responsibility |
| --- | --- |
| `EngineRouteStore` | Single source of truth. Persists the per-canonical-system route + chosen emulator id, resolves the effective route, and produces the metadata `launch:` command. |
| `EmulatorCatalog` | Ordered candidate emulators per canonical system, each with a direct-launch recipe. Builds the `am start` recipe and the install intent. |
| `RomLaunchActivity` + `RomFileProvider` | Lucent's own content-URI trampoline for scoped-storage emulators (Switch `.xci/.nsp`, Wii U `.wux/.wud`). Non-exported, own-uid gated, storage-confined, read-only. |
| `ImportManager.launchCommand` / `rewriteLaunchRoutes` | Emits the launch command per system via `EngineRouteStore`; re-emits every system's command after a route change. |
| `LaunchMetadataRouter` | Normalizes on-disk Pegasus metadata through the same `EngineRouteStore.launchCommand`. |

## Resolution (`EngineRouteStore.resolve`)

1. Explicit user **EXTERNAL** choice → EXTERNAL.
2. Explicit user **INTERNAL** choice → INTERNAL if an engine exists, else EXTERNAL
   (a stored preference never becomes unlaunchable).
3. No explicit choice → INTERNAL if an engine exists (`GameLaunchRouter.supportsSystem`,
   backed by `InternalEngineCatalog` / `Phase2QualificationCatalog`), else EXTERNAL.

`setRoute` refuses an INTERNAL choice with no engine, an EXTERNAL choice with no
catalog option, and an unknown emulator id, so an unlaunchable preference is
never persisted. A route change calls `ImportManager.rewriteLaunchRoutes`, which
regenerates fresh metadata, normalizes on-disk metadata, and requests a Pegasus
library reload so the new `launch:` lines take effect.

## Launch mechanism

Pegasus natively runs `am start` via `launchAmCommand`; Lucent only intercepts
its internal action. So the emitted command is:

- **Internal** → `MetadataGameLaunchCommand` (`am start -a <internal action> …`)
  which the patched `MainActivity` intercepts in-process.
- **External, path-accepting emulator** → `am start -n <pkg>/<activity> --es <ROM key> "{file.path}" --activity-clear-task`.
- **External, `ACTION_VIEW` emulator** → `am start … -d "file://{file.path}"`.
- **External, scoped-storage emulator** → `am start … -n com.thorium.preview/…RomLaunchActivity`,
  which grants a one-time read-only content URI and forwards into the emulator's
  gameplay Activity.
- **External but not installed** → `am start -a VIEW -d market://…` (or the
  release page) so the user can install; never a silent install.
- **No option at all** → empty command (fail closed).

## Per-system routing today

- **PS3** → no maintained Android emulator (RPCS3 has no Android port); stays
  explicitly optionless. It cannot launch on-device until an engine exists — an
  open product decision, not a routing bug.
- **Wii U** → external Cemu (content-URI trampoline).
- **Switch** → external Eden (content-URI trampoline).
- **3DS** → external Azahar (path-accepting) or RetroArch.

## Future `/route/*` endpoints (owned by PreviewService, not this workstream)

A settings picker will call, per canonical system:

- `GET /route/options?system=<id>` — ordered emulator candidates + install state
  (already backed by `EmulatorCatalog.statusJson`).
- `GET /route/resolve?system=<id>` — the effective route + chosen emulator.
- `POST /route/set` — `{system, route: internal|external, emulator?}`
  (`EngineRouteStore.setRoute`, then `ImportManager.rewriteLaunchRoutes`).
- `POST /route/clear` — return a system to its default (`EngineRouteStore.clearRoute`).

The picker should present **Internal (default)** first for any system with an
engine, then the external options in catalog order.

## Known tension: `verify_one_app_apk.py`

The one-app APK verifier (`unified-android/tools/verify_one_app_apk.py`) encodes
the **superseded** "internal is the only route" boundary. It will reject an APK
built with this workstream because:

- `RomLaunchActivity` is in its `FORBIDDEN_DEX_CLASSES` and is a 4th Activity
  outside its `ALLOWED_ACTIVITIES` whitelist, and
- `EmulatorCatalog` embeds external emulator package identities that appear in
  its `FORBIDDEN_DEX_IDENTITIES` list.

No manifest-attribute change can satisfy a name whitelist / identity blocklist.
Reconciling the verifier with the new authoritative product rule (updating
`ALLOWED_ACTIVITIES` to include the non-exported trampoline and narrowing the
identity ban to *launcher/home* components) is required before this ships, and is
out of scope here (the verifier must not be edited by this workstream).
