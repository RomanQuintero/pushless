"""Reconcile server journal with exported Android receipts; never infer UI visibility."""
import argparse
import csv
import json
import sqlite3
from pathlib import Path


def summarize(events, receipts, run, technique):
    expected = {e['id'] for e in events if e['run'] == run}
    relevant = [r for r in receipts if r.get('kind') == 'receipt' and r.get('run') == run and r.get('technique') == technique]
    received = {r['event_id'] for r in relevant}
    return dict(run=run, technique=technique, expected=len(expected), received=len(expected & received),
                missed_at_export=len(expected - received), duplicates=len(relevant) - len(received),
                notification_submitted=sum(r.get('notification_outcome') == 'notification_submitted' for r in relevant),
                valid_latency_samples=sum(r.get('latency_valid') is True for r in relevant))


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('--journal', type=Path, required=True)
    p.add_argument('--receipts', type=Path, required=True)
    p.add_argument('--run', required=True)
    p.add_argument('--technique', required=True)
    p.add_argument('--csv', type=Path)
    a = p.parse_args()
    with sqlite3.connect(a.journal) as db:
        events = [json.loads(row[0]) for row in db.execute('SELECT body FROM events')]
    receipts = [json.loads(line) for line in a.receipts.read_text(encoding='utf-8-sig').splitlines() if line.strip()]
    result = summarize(events, receipts, a.run, a.technique)
    print(json.dumps(result, indent=2))
    if a.csv:
        with a.csv.open('w', newline='', encoding='utf-8') as out:
            writer = csv.DictWriter(out, fieldnames=result.keys())
            writer.writeheader()
            writer.writerow(result)
