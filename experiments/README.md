Physical experiments were performed on 2026-10-04; see the root README and observations.md. This file is the procedure for additional runs. Do not infer delivery reliability from compilation.

Record device model, Android version, app version, technique, condition, start/end times, expected event IDs, received IDs, duplicates, and notification outcomes per run. One-way latency requires a measured clock offset and uncertainty; until then report latency as unavailable. A successful NotificationManager call is not proof that a notification was visible.

Progression: T1 foreground → T2 background (Home) → T3 screen off → T4 Doze → T5 temporary Wi-Fi loss → T6 process termination → optional T7 reboot. Run each technique separately. Record failures before changing implementation. Preserve logs before clearing data.

For T6 distinguish `adb shell am kill dev.pushless` (background-process termination; verify the PID actually disappears) from `adb shell am force-stop dev.pushless` (package stopped state). They are separate conditions. Do not use force-stop as evidence for ordinary process death.

On this device the tested `am kill` attempts did not terminate the PID. Separate T6Direct runs use `adb shell run-as dev.pushless kill -9 PID` against the debug app's own UID and verify PID disappearance. These preserve the unsuccessful am-kill evidence; no automatic-restart workaround was added.

Run the full polling comparison by sending events **after** the initial job completes, recording job start timestamps, and leaving the periodic job scheduled for at least a full 15-minute interval plus a stated observation window. Repeat in background/screen-off/Doze without forcing job execution. A forced `cmd jobscheduler run` invocation would be a diagnostic smoke test, not natural scheduling evidence. These later periodic runs are not yet performed.

For T4 use an isolated test device: `adb shell dumpsys battery unplug`, `adb shell dumpsys deviceidle force-idle`, then verify `adb shell dumpsys deviceidle`. Always restore with `adb shell dumpsys deviceidle unforce` and `adb shell dumpsys battery reset`.

T5: manually disable Wi-Fi, send a known set of IDs, restore Wi-Fi, and record replay/reconnection/loss. T7: reboot manually, record whether the user reopened the app, and distinguish delivery before/after reopening.
