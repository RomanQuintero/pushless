"""Curate app experiment artifacts without Wi-Fi identifiers."""
import csv
import json
import sqlite3
from pathlib import Path

root = Path(__file__).resolve().parent
receipts = set()
runs = []
for directory in sorted((root / 'results').iterdir()):
    if not directory.is_dir():
        continue
    record = dict(run=directory.name)
    for filename in ('summary.csv', 'before.csv', 'after.csv'):
        path = directory / filename
        if path.exists():
            with path.open(encoding='utf-8-sig', newline='') as source:
                record[filename.removesuffix('.csv')] = next(csv.DictReader(source))
    state = directory / 'state.json'
    if state.exists():
        record['state'] = json.loads(state.read_text(encoding='utf-8-sig'))
    idle = directory / 'deviceidle.txt'
    if idle.exists():
        record['idle_state'] = [line.strip() for line in idle.read_text(encoding='utf-8-sig').splitlines() if 'mState=' in line]
    for name in ('wifi-disabled.txt', 'wifi-restored.txt'):
        path = directory / name
        if path.exists():
            lines = path.read_text(encoding='utf-8-sig').splitlines()
            record[name] = dict(enabled=any(line == 'Wifi is enabled' for line in lines), connected=any(line.startswith('Wifi is connected') for line in lines))
    runs.append(record)
    for name in ('results.jsonl', 'before-restore.jsonl', 'after-restore.jsonl'):
        path = directory / name
        if path.exists():
            for line in path.read_text(encoding='utf-8-sig').splitlines():
                if line.strip():
                    receipts.add(json.dumps(json.loads(line), ensure_ascii=False, sort_keys=True))
rows = sorted((json.loads(line) for line in receipts), key=lambda row: row['logged_at'])
(root / 'measurements.jsonl').write_text(''.join(json.dumps(row, ensure_ascii=False) + '\n' for row in rows), encoding='utf-8')
(root / 'runs.json').write_text(json.dumps(runs, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
with sqlite3.connect(root.parent / 'server/data/events.sqlite') as db:
    events = [json.loads(row[0]) for row in db.execute('SELECT body FROM events ORDER BY seq')]
(root / 'expected-events.jsonl').write_text(''.join(json.dumps(event, ensure_ascii=False) + '\n' for event in events), encoding='utf-8')
print(f'Curated {len(runs)} runs, {len(rows)} diagnostic rows, {len(events)} expected events')
