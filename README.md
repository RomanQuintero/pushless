# PUSHLESS — project 16

**Research question:** How far can an ordinary Android app go with background notifications without Firebase Cloud Messaging or another push provider?

This is a reproducible local experiment, not a production push system. Kotlin Android app + Python server + a physical Samsung SM-F956B running Android 16/API 36. No root, privileged app, AOSP changes, wake locks, battery exemptions, push providers, or automatic lifecycle rescue were added.

## What actually works

The debug APK builds, installs, and runs. Local notification visibility and the first real LAN server → WebSocket → Android notification were confirmed by the user on 2026-10-04. All four techniques delivered 3/3 baseline events. Subsequent automated runs verify event receipts and NotificationManager submissions; they do **not** independently verify notification-drawer visibility.

The brief tests already show limits: ordinary socket connections aborted in background/screen-off conditions while their process remained alive. The foreground service performed better in those specific short runs. Verified process death stopped all three persistent-channel variants. See the evidence and limitations below before generalizing.

## Architecture and delivery semantics

```text
send.py / POST /send → SQLite event journal
                       ├─ live WebSocket → Activity-owned channel
                       ├─ MQTT QoS 1 → Activity-owned Paho client
                       ├─ HTTP /events?run=…&after=… → periodic JobScheduler job
                       └─ live WebSocket → explicit foreground dataSync service
Android receipts + connection transitions → private JSONL → adb export → reconciliation CSV
```

Each event has `id` (UUID), `seq` (journal cursor), `sent_at` (UTC Unix milliseconds), `payload`, and `run`. Use a **new run label for every experiment**. `/send` persists first and returns the exact expected event. All channels receive the same server event, with Android filtering by run.

* **WebSocket:** OkHttp, 30-second heartbeat, simple five-second reconnection while the process exists. Live delivery only; reconnect does not replay missed events. Activity destruction stops the ordinary channel.
* **MQTT:** bundled aMQTT Python broker and Paho clients, topic `pushless/events`, QoS 1 (at least once while connected). Duplicate IDs are suppressed and logged in Android preferences. Clean sessions and non-retained messages deliberately provide no disconnected-device backlog. Automatic reconnect uses Paho's normal behavior; initial connection failure requires pressing Start again. `/send` reports `mqtt_enqueued`, which is not proof of subscriber delivery.
* **Polling:** native Android **JobScheduler**, the normal scheduling alternative permitted by this experiment; no WorkManager dependency is needed. A periodic 15-minute network-constrained job fetches run-filtered journal events after a persisted cursor. Reads are nondestructive and survive server restarts. The cursor advances after each receipt. The job is not persisted across reboot. Initial prequeued delivery is tested; later periodic/background timing is not yet measured. Scheduling can be delayed by the OS. See [Android's periodic scheduling documentation](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work).
* **ForegroundWS:** the same live WebSocket in a user-started `dataSync` service with a persistent notification. `START_NOT_STICKY`; no boot receiver or process-death restart. Android's [foreground-service types and time limits](https://developer.android.com/develop/background-work/services/fgs/service-types) apply. This is a bounded data-sync experiment; target SDK 35 dataSync services have a six-hour background budget per 24 hours, not an indefinite channel. The timeout callback stops the service and logs it. That long-duration limit was not tested.

The server is intentionally anonymous, unencrypted, and local-network only. Ports 8080 (HTTP/WS) and 1883 (MQTT). Do not expose these experiment ports to the Internet. No accounts, fleet management, cloud deployment, or dashboard exists.

## Build and run on Windows

Existing PC tooling was sufficient: JDK 21.0.12.1, Android SDK command-line tools, platform 35, build-tools 34.0.0, and cached Gradle. Android Studio is not needed. Versions: Gradle 8.13, AGP 8.7.3, Kotlin 1.9.25, compile/target SDK 35, minimum SDK 26. Wrapper included. No large IDE/toolchain was installed.

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk'
$env:Path = "$env:LOCALAPPDATA\Android\Sdk\platform-tools;" + $env:Path
# Adapt this ignored machine-specific file to your SDK location:
$sdkPath = (Join-Path $env:LOCALAPPDATA 'Android\Sdk').Replace('\', '/')
Set-Content android/local.properties "sdk.dir=$sdkPath"
cd android
./gradlew.bat assembleDebug
cd ..
./scripts/android.ps1 devices
./scripts/android.ps1 install -Serial DEVICE_SERIAL
./scripts/android.ps1 launch -Serial DEVICE_SERIAL
./scripts/android.ps1 logs -Serial DEVICE_SERIAL
./scripts/android.ps1 export -Serial DEVICE_SERIAL
```

APK: `android/app/build/outputs/apk/debug/app-debug.apk`. To clear this app's experiment data deliberately: `./scripts/android.ps1 clear`. Preserve exports first.

Python 3.12+ is suitable. On a machine with Python installed:

```powershell
python -m venv .venv
./.venv/Scripts/python.exe -m pip install -r server/requirements.txt
./scripts/server.ps1
# In another terminal:
./.venv/Scripts/python.exe server/send.py 'hello' --run my-new-run
```

This PC's `py` launcher had no installed interpreter. We used the existing Codex bundled Python at the runtime path returned by Codex workspace dependency discovery to create `.venv` and its pip `--python .venv/Scripts/python.exe` option to install dependencies. No separate Python installation was needed.

Use the PC's LAN IP in the app, for example `http://PC_LAN_IP:8080`, not Android's `localhost`. Keep the phone on the same reachable LAN. If Windows Firewall blocks access, permit these ports only on the intended private network; no firewall changes were made during the successful LAN tests. MQTT derives the same hostname and uses port 1883. UI inputs: server URL, unique run label, condition label, technique; tap Start. Stop cancels the channel/service/job. Request notification permission and confirm notifications are enabled, following [Android's permission documentation](https://developer.android.com/develop/ui/views/notifications/notification-permission).

## Reproduce measurements

```powershell
./scripts/run-condition.ps1 -Technique WebSocket -Condition T1 -Serial DEVICE_SERIAL -Server http://PC_LAN_IP:8080
./scripts/run-condition.ps1 -Technique MQTT -Condition T2 -Serial DEVICE_SERIAL -Server http://PC_LAN_IP:8080
./scripts/run-condition.ps1 -Technique ForegroundWS -Condition T3 -Serial DEVICE_SERIAL -Server http://PC_LAN_IP:8080
./scripts/run-condition.ps1 -Technique WebSocket -Condition T6Direct -Serial DEVICE_SERIAL -Server http://PC_LAN_IP:8080
./scripts/doze.ps1 -Technique WebSocket -Serial DEVICE_SERIAL
./scripts/network-loss.ps1 -Technique WebSocket -Serial DEVICE_SERIAL
```

Pass your adb device serial and PC LAN URL explicitly; there are no machine-specific defaults. `run-condition.ps1` takes `-Serial` and `-Server`; the Doze/network scripts use the app's saved URL and local server send endpoint. Supported basic conditions: T1 foreground; T2 Home; T3 screen sleep; T6 `am kill`; T6Direct verified debug-app-UID SIGKILL. Technique names: `WebSocket`, `MQTT`, `Polling`, `ForegroundWS`.

Each basic run explicitly resets with `am start -S`, launches, waits five seconds, applies its condition, waits three seconds, sends three events about one second apart, then exports five seconds after the final send. The pre-run force-stop is only a reset, never the killed-condition evidence. Polling instead queues its events before scheduling to test normal initial execution; do not use this helper's short polling runs to claim later periodic/background reliability. T3 uses sleep/wake keycodes without unlocking or bypassing the lock screen.

Doze tests use Home, screen sleep, simulated battery unplug, forced idle, and save `dumpsys deviceidle`. They export during idle and five seconds after restoring. A `finally` block runs `deviceidle unforce` and `battery reset`. Network-loss tests disable only Wi-Fi, record its state, send three events during loss, restore Wi-Fi in `finally`, wait 12 seconds, then send a fresh event. Cellular connectivity is not disabled; the LAN endpoint should be unreachable through cellular, and the saved connection logs establish the actual outcome.

**Process death is different from force-stop.** The original `am kill` attempts left all tested PIDs alive; those runs are not killed-state evidence. T6Direct uses `adb shell run-as dev.pushless kill -9 PID` against this debuggable app's own UID, then verifies PID disappearance. It uses no root and does not set the package's stopped state. Force-stop separately uses `adb shell am force-stop dev.pushless`, and explicit launch ends that stopped state. Do not conflate them.

Optional reboot procedure, not performed here: record current run/journal, reboot manually, unlock without reopening the app, send known IDs and export if available, then reopen and repeat. No boot receiver exists. Reboot and long natural idle tests are left NOT TESTED. See [full test plan](experiments/README.md).

```powershell
./.venv/Scripts/python.exe experiments/summarize.py --journal server/data/events.sqlite --receipts experiments/results/results.jsonl --run my-new-run --technique WebSocket --csv summary.csv
```

## Results — actual observations

Cells count unique receipts within the short export window. PASS means all expected receipts and notification submissions; PARTIAL means some; FAIL means none. These labels do not prove notification visibility for every event or sustained reliability.

| Technique | Foreground | Background | Screen off | Doze | Network loss | Verified killed | Reboot |
|---|---|---|---|---|---|---|---|
| WebSocket | PASS 3/3 | PASS 3/3 | PARTIAL 2/3 | FAIL 0/3 | FAIL 0/5* | FAIL 0/3 | NOT TESTED |
| MQTT | PASS 3/3 | PARTIAL 2/3 | FAIL 0/3 | FAIL 0/3 | PARTIAL 1/5* | FAIL 0/3 | NOT TESTED |
| Polling/JobScheduler | PASS 3/3 queued | NOT TESTED | NOT TESTED | NOT TESTED | NOT TESTED | NOT TESTED | NOT TESTED |
| ForegroundWS | PASS 3/3 | PASS 3/3 | PASS 3/3 | PASS 3/3 | PARTIAL 2/5 | FAIL 0/3 | NOT TESTED |

*The Wi-Fi-loss tests were also in background state (keyguard/lifecycle); ordinary-channel failures cannot be isolated to Wi-Fi loss alone. ForegroundWS received one event before loss and a fresh event after restoration; none of the three offline events were replayed. Wi-Fi connection restoration was verified. Doze ordinary channels remained 0/3 five seconds after idle restoration; ForegroundWS was already 3/3 during idle.

Detailed runs: [observations](experiments/observations.md). Raw exports are retained locally under `experiments/results/`; curated [run summaries](experiments/runs.json), [Android measurements](experiments/measurements.jsonl), and [expected events](experiments/expected-events.jsonl) preserve reproducible evidence in the repository. Regenerate with `.venv/Scripts/python.exe experiments/collect_evidence.py`; Wi-Fi identifiers are omitted. The SQLite journal persists all expected IDs.

Synthetic duplicate validation: `./scripts/duplicate-check.ps1 -Serial DEVICE_SERIAL` delivered one MQTT event plus two deliberate payload-ID replays. Android logged two duplicates and submitted one notification. This validates ID-based suppression; it is not evidence that normal transport produced duplicates.

## Measurement limitations and interpretation

PC/device clocks are **not calibrated**. The first raw device-minus-server difference was -12 ms; other runs also show negative values. `delivery_latency_ms` is null, `latency_valid=false`; `raw_clock_delta_ms` is only a diagnostic. No averages or false millisecond precision are claimed. UTC Unix milliseconds are suitable once an independently measured clock offset/uncertainty is applied; `/clock` exists for a future bounded-RTT calibration experiment.

Receipts record technique, run, event ID, server timestamp, Android receipt timestamp, notification submission timestamp/outcome, clock validity, app/condition state, connection state, duplicate and transport success. Notification submission time is after `notify()` returns, not Android's actual display time. Permission denial, disabled app/channel notifications and posting exceptions are distinct from transport success. Missed IDs are reconciled against the server journal at an explicit export cutoff; delayed delivery can change a later export. Duplicate markers/cursors persist locally; clearing app data erases them. This small experiment reads its short log into the UI, not a scalable telemetry system.

**Expected:** persistent sockets should deliver promptly while available; scheduling and lifecycle restrictions may change reliability; a foreground notification may help keep a channel active. **Observed:** the particular short runs above, including background socket aborts while PIDs remained alive, and zero delivery after verified process termination. Socket-abort logs do not identify a specific OEM/Android policy as the cause. No lifecycle workaround was added after failures. No evidence supports equivalence to FCM or a perfect Firebase replacement.

## Automated validation

```powershell
./.venv/Scripts/python.exe -m unittest discover -s server -v
./.venv/Scripts/python.exe -m unittest discover -s experiments -v
```

Three focused tests pass: ID/serialization/persistent run-filtered cursor replay, HTTP/WebSocket endpoints including malformed requests, and reconciliation of duplicates/missed IDs. Android APK builds successfully; the initial APK signature was verified with `apksigner`. Physical results above are separate from unit/endpoint tests.
