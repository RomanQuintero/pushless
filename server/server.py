"""LAN-only experimental event journal; no authentication or cloud services."""
import argparse
import asyncio
import json
import logging
import sqlite3
import time
import uuid
from pathlib import Path

from aiohttp import web


class Journal:
    def __init__(self, path):
        self.db = sqlite3.connect(path)
        self.db.execute('CREATE TABLE IF NOT EXISTS events (seq INTEGER PRIMARY KEY, id TEXT UNIQUE, body TEXT)')

    def send(self, payload, run):
        event = dict(id=str(uuid.uuid4()), sent_at=int(time.time() * 1000), payload=payload, run=run)
        cursor = self.db.execute('INSERT INTO events(id,body) VALUES (?,?)', (event['id'], json.dumps(event)))
        event['seq'] = cursor.lastrowid
        self.db.execute('UPDATE events SET body=? WHERE seq=?', (json.dumps(event), event['seq']))
        self.db.commit()
        return event

    def poll(self, after, run):
        rows = self.db.execute('SELECT body FROM events WHERE seq>? ORDER BY seq', (after,)).fetchall()
        return [e for row in rows if (e := json.loads(row[0]))['run'] == run]


def create_app(journal, mqtt=None):
    app = web.Application(client_max_size=16 * 1024)
    sockets = set()

    async def send(request):
        try:
            body = await request.json()
        except (json.JSONDecodeError, UnicodeDecodeError):
            raise web.HTTPBadRequest(text='invalid JSON')
        if not isinstance(body, dict) or not isinstance(body.get('payload'), str) or not isinstance(body.get('run'), str) or not body['run']:
            raise web.HTTPBadRequest(text='payload and nonempty run must be strings')
        event = journal.send(body['payload'], body['run'])
        failures = 0
        for socket, run in list(sockets):
            if run == event['run']:
                try:
                    await socket.send_json(event)
                except (ConnectionError, RuntimeError):
                    failures += 1
        published = False
        if mqtt is not None:
            published = mqtt.publish('pushless/events', json.dumps(event), qos=1, retain=False).rc == 0
        logging.info('event %s ws_failures=%s mqtt_enqueued=%s', event['id'], failures, published)
        return web.json_response(dict(event=event, mqtt_enqueued=published))

    async def poll(request):
        try:
            after = int(request.query.get('after', '0'))
            if after < 0:
                raise ValueError()
        except ValueError:
            raise web.HTTPBadRequest(text='after must be a nonnegative integer')
        return web.json_response(journal.poll(after, request.query.get('run', '')))

    async def ws(request):
        run = request.query.get('run', '')
        socket = web.WebSocketResponse(heartbeat=30)
        await socket.prepare(request)
        sockets.add((socket, run))
        logging.info('ws connected run=%s', run)
        try:
            async for _ in socket:
                pass
        finally:
            sockets.discard((socket, run))
            logging.info('ws disconnected run=%s', run)
        return socket

    async def clock(request):
        return web.json_response(dict(server_ms=int(time.time() * 1000)))

    app.add_routes([web.post('/send', send), web.get('/events', poll), web.get('/ws', ws), web.get('/clock', clock)])
    return app


async def serve(args):
    from amqtt.broker import Broker
    import paho.mqtt.client as mqtt
    broker = Broker({'listeners': {'default': {'type': 'tcp', 'bind': f'{args.host}:{args.mqtt_port}'}},
                     'plugins': {'amqtt.plugins.authentication.AnonymousAuthPlugin': {'allow_anonymous': True}}})
    await broker.start()
    publisher = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id='pushless-server')
    publisher.connect('127.0.0.1' if args.host == '0.0.0.0' else args.host, args.mqtt_port)
    publisher.loop_start()
    args.data.mkdir(parents=True, exist_ok=True)
    journal = Journal(args.data / 'events.sqlite')
    runner = web.AppRunner(create_app(journal, publisher))
    await runner.setup()
    await web.TCPSite(runner, args.host, args.port).start()
    logging.info('HTTP/WS %s:%s MQTT :%s', args.host, args.port, args.mqtt_port)
    try:
        await asyncio.Event().wait()
    finally:
        await runner.cleanup()
        publisher.disconnect()
        publisher.loop_stop()
        await broker.shutdown()
        journal.db.close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--host', default='0.0.0.0')
    parser.add_argument('--port', type=int, default=8080)
    parser.add_argument('--mqtt-port', type=int, default=1883)
    parser.add_argument('--data', type=Path, default=Path(__file__).parent / 'data')
    logging.basicConfig(level=logging.INFO, format='%(asctime)s %(levelname)s %(message)s')
    asyncio.run(serve(parser.parse_args()))
