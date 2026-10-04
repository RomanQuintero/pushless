import json
import tempfile
import unittest
from pathlib import Path
from uuid import UUID

from aiohttp.test_utils import TestClient, TestServer
from server import Journal, create_app


class JournalTests(unittest.TestCase):
    def test_ids_serialization_and_replay_cursor(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'events.sqlite'
            journal = Journal(path)
            a = journal.send('hello é', 'a')
            b = journal.send('other run', 'b')
            c = journal.send('later', 'a')
            UUID(a['id'])
            self.assertEqual(len({a['id'], b['id'], c['id']}), 3)
            self.assertEqual(json.loads(json.dumps(a)), a)
            self.assertEqual(journal.poll(a['seq'], 'a'), [c])
            self.assertEqual(journal.poll(0, 'a'), [a, c])
            self.assertEqual(journal.poll(0, 'a'), [a, c])  # polling is nondestructive
            journal.db.close()
            reopened = Journal(path)
            self.assertEqual(reopened.poll(0, 'a'), [a, c])
            reopened.db.close()


class EndpointTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.journal = Journal(':memory:')
        self.client = TestClient(TestServer(create_app(self.journal)))
        await self.client.start_server()

    async def asyncTearDown(self):
        await self.client.close()
        self.journal.db.close()

    async def test_send_ws_poll_and_bad_cursor(self):
        socket = await self.client.ws_connect('/ws?run=unit')
        response = await self.client.post('/send', json={'payload': 'test', 'run': 'unit'})
        self.assertEqual(response.status, 200)
        event = (await response.json())['event']
        self.assertEqual(await socket.receive_json(timeout=2), event)
        self.assertEqual(await (await self.client.get('/events?run=unit')).json(), [event])
        self.assertEqual(await (await self.client.get(f'/events?run=unit&after={event["seq"]}')).json(), [])
        self.assertEqual((await self.client.get('/events?after=bad')).status, 400)
        self.assertEqual((await self.client.post('/send', json={'payload': 'missing run'})).status, 400)
        self.assertEqual((await self.client.post('/send', json=[])).status, 400)
        self.assertEqual((await self.client.post('/send', data='invalid', headers={'Content-Type': 'application/json'})).status, 400)
        await socket.close()


if __name__ == '__main__':
    unittest.main()
