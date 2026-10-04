"""Explicit duplicate-ID injection for instrumentation validation, not delivery recovery."""
import argparse
import json
import sqlite3
from pathlib import Path
import paho.mqtt.client as mqtt

if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('event_id')
    p.add_argument('--journal', type=Path, default=Path(__file__).parent / 'data/events.sqlite')
    a = p.parse_args()
    with sqlite3.connect(a.journal) as db:
        row = db.execute('SELECT body FROM events WHERE id=?', (a.event_id,)).fetchone()
    if row is None:
        raise SystemExit('Unknown event ID')
    client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id='pushless-duplicate-test')
    client.connect('127.0.0.1', 1883)
    client.loop_start()
    try:
        for _ in range(2):
            info = client.publish('pushless/events', row[0], qos=1, retain=False)
            info.wait_for_publish(timeout=5)
            if not info.is_published():
                raise RuntimeError('Broker publication acknowledgement timed out')
        print(json.dumps(dict(event_id=a.event_id, injected_duplicate_copies=2)))
    finally:
        client.disconnect()
        client.loop_stop()
