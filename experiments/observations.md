# Physical observations — 2026-10-04

Device: Samsung SM-F956B, Android 16/API 36. PC Ethernet 192.168.68.56; device Wi-Fi 192.168.68.54. App target SDK 35. Debug build. Small samples and brief conditions; no general reliability claim.

Local notification and the first LAN WebSocket notification were visually confirmed by the user. Later automated results count receipts and notification API submissions, not independently verified notification visibility.

Baseline T1: WebSocket, MQTT, scheduled polling, and foreground WebSocket each received 3/3 expected events; zero duplicate receipts; three notification submissions each. Polling's events were queued before scheduling and the initial job ran normally; this does not test a later 15-minute execution.

T2: WebSocket 3/3; foreground WebSocket 3/3. MQTT 2/3 with the same PID before/after Home. At device timestamp 1791121463843, the callback recorded `Software caused connection abort` in background, 10 ms before the server timestamp of the third event. Because clocks are uncalibrated, that cross-clock ordering is approximate. The broker also observed the connection close. This is a real partial-delivery observation. Exact Android/OEM/network cause remains unresolved. No workaround was added.

Clock observation: the first WebSocket receipt had raw device-minus-server time -12 ms. One-way latency is therefore intentionally null for all current runs. Raw clock deltas are diagnostic only.

Evidence directories use run labels under `experiments/results/`; `sent.jsonl` contains expected IDs, `results.jsonl` contains Android receipts/connection transitions, `state.json` records PID/reset/condition, and `summary.csv` reconciles expected and received IDs. The SQLite journal retains all expected events. Missed counts are **at export time**, not proof of permanent loss.

T3: WebSocket 2/3; MQTT 0/3; foreground WebSocket 3/3. Ordinary channels recorded socket-abort callbacks; a five-second reconnect attempt was logged for WebSocket. MQTT's screen-off connection aborted before its first event. The exact OEM/Android policy is not established.

T4: all three device-idle snapshots confirmed `mState=IDLE mLightState=OVERRIDE`. WebSocket and MQTT 0/3 during idle and five seconds after restoration; foreground WebSocket 3/3 during idle. These are forced, short idle runs, not overnight natural Doze or a universal exemption claim. All runs restored battery/idle simulation.

T6: original `am kill` attempts preserved the same PID, so all are invalid as process-death tests (even the foreground-service run that received 3/3). Separate T6Direct debug-app-UID SIGKILL runs verified each PID disappeared; all three channels received 0/3 afterward. None implements automatic restart. These are different from package force-stop.

T5: Wi-Fi snapshots confirmed disabled during loss and enabled/connected after restoration. WebSocket 0/5, MQTT 1/5 (pre-loss event), foreground WebSocket 2/5 (pre-loss and post-restoration). App state was background; ordinary sockets also exhibited earlier socket-abort behavior. These results combine background and network loss and do not isolate a network-only cause. Foreground WebSocket reconnected, delivered the fresh event, and did not replay offline events. No natural duplicates were observed.

Synthetic duplicate test: one MQTT event and two deliberate copies of the same ID yielded one unique receipt, two duplicate rows, and one notification submission on the physical device. The synthetic copies were not additional expected unique events.

Polling: only initial normally scheduled prequeued T1 delivery is measured. Later 15-minute, background, idle, process-death and reboot execution remain NOT TESTED. Reboot was not performed on the user's phone.
