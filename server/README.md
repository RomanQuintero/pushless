# Local experiment server

From repository root: `.venv/Scripts/python.exe server/server.py`. HTTP/WebSocket port 8080, embedded aMQTT broker port 1883. Stop with Ctrl+C. See root README for dependency setup and LAN scope.

`send.py "hello" --run LABEL` creates a persistent UUID event and fans out through live WebSocket and MQTT. HTTP polling is nondestructive and run-filtered: `/events?run=LABEL&after=SEQ`. `/clock` returns UTC epoch milliseconds but does not itself calibrate Android's clock.

MQTT QoS1, clean subscriber sessions, no retained messages. Expected IDs persist in `server/data/events.sqlite`; subscriber delivery is established by Android receipts, not publisher enqueue status.
