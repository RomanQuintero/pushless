import argparse
import json
from urllib.request import Request, urlopen

if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('payload')
    p.add_argument('--run', required=True)
    p.add_argument('--server', default='http://127.0.0.1:8080')
    a = p.parse_args()
    request = Request(a.server.rstrip('/') + '/send', json.dumps(dict(payload=a.payload, run=a.run)).encode(), {'Content-Type': 'application/json'})
    with urlopen(request, timeout=10) as response:
        print(response.read().decode())
