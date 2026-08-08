# Lucent launch/return acceptance

The host's `latencyMs` log is diagnostic evidence, not proof of a successful
return. Release acceptance is based on the user's visible and interactive
result.

## Required evidence per tested game

1. Launch the game with physical A from the normal Lucent menu. Direct intents
   and qualification activities do not count.
2. Preserve a raw logcat trace covering menu selection, gameplay, held Stop,
   restored menu input, and background checkpoint completion.
3. Preserve the original Activity token, task ID, window, and PID across both
   transitions.
4. Capture launch frames for at least 1.2 seconds and return frames beginning
   before the one-second Stop threshold through at least 500 ms afterward.
5. OCR every captured transition frame. `Lucent`, `Pegasus`, `Preparing`,
   `Saving and returning`, branding, or progress UI is an unconditional fail.
6. The exact prior view, system, sort, and game selection must be visible no
   later than 500 ms after the Stop threshold. It must accept a physical menu
   movement immediately; a visually similar but input-blocking splash fails.
7. The background Quick Resume commit must complete without delaying the menu.

## Lifecycle rejection

A normal menu game launch must be intercepted inside the already-live
MainActivity's `launchAmCommand` bridge. Pegasus accepts its normal `am start`
metadata syntax, but the exact internal Lucent action is attached directly to
the live window before `startActivity` and before any Activity lifecycle can
run. Any `ActivityTaskManager START` for that action is a release failure, even
if the Activity token and PID remain unchanged. On the Thor, starting the
existing `singleTask` Activity can still drive `onPause`/`onResume`; Qt then
recreates its Surface and exposes the startup splash when the gameplay overlay
is removed.

The pinned Android frontend also replaces Pegasus's external-process success
subscriber. It completes API/provider play bookkeeping and launcher cleanup
without deleting the `QQmlApplicationEngine` or stopping the gamepad. The
exact APK verifier rejects a frontend that lacks this byte-locked patch.

An Activity launch followed by `performResumeActivity` or a new
`QtActivityDelegate.createSurface` during a menu game transition is additional
proof of this failure mode.

Run the offline evidence gate with:

```sh
python3 unified-android/tools/verify_runtime_return_evidence.py \
  unified-android/build/runtime-acceptance-<candidate>
```

The verifier fails closed when raw lifecycle logs are absent, and it ignores a
nominal `PASS` in `results.json` if stored OCR or screenshots show a reset.
