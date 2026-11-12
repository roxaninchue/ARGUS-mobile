import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

STORE = {}


class Handler(BaseHTTPRequestHandler):
    def _key(self):
        if not self.path.startswith("/kv/") or len(self.path) <= 4:
            return None
        return self.path[4:]

    def _send(self, code, body=None):
        data = json.dumps(body).encode() if body is not None else b""
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        key = self._key()
        if key is None:
            return self._send(404, {"error": "not found"})
        if key not in STORE:
            return self._send(404, {"error": "no such key"})
        self._send(200, {"key": key, "value": STORE[key]})

    def do_PUT(self):
        key = self._key()
        if key is None:
            return self._send(404, {"error": "not found"})
        raw = self.rfile.read(int(self.headers.get("Content-Length") or 0))
        try:
            value = json.loads(raw)["value"]
        except (ValueError, KeyError, TypeError):
            return self._send(400, {"error": "body must be JSON with a value"})
        STORE[key] = value
        self._send(200, {"key": key, "value": value})

    def do_DELETE(self):
        key = self._key()
        if key is None or key not in STORE:
            return self._send(404, {"error": "no such key"})
        del STORE[key]
        self._send(204)

    def log_message(self, *args):
        pass


def serve(port):
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()


if __name__ == "__main__":
    serve(int(sys.argv[1]) if len(sys.argv) > 1 else 8080)
