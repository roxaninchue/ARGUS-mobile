import os, sys, unittest
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import json, socket, subprocess, time, urllib.request, urllib.error
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
def free_port():
    s = socket.socket(); s.bind(("127.0.0.1", 0)); p = s.getsockname()[1]; s.close(); return p
class T(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.port = free_port(); cls.p = subprocess.Popen([sys.executable, os.path.join(ROOT, "kvserver.py"), str(cls.port)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        for _ in range(50):
            try: socket.create_connection(("127.0.0.1", cls.port), 0.2).close(); break
            except OSError: time.sleep(0.1)
    @classmethod
    def tearDownClass(cls):
        cls.p.terminate(); cls.p.wait(5)
    def req(self, method, path, body=None):
        r = urllib.request.Request(f"http://127.0.0.1:{self.port}{path}", data=body, method=method)
        try:
            with urllib.request.urlopen(r, timeout=5) as resp: return resp.status, resp.headers.get("Content-Type", ""), resp.read()
        except urllib.error.HTTPError as e: return e.code, e.headers.get("Content-Type", ""), e.read()
    def test_crud(self):
        s, ct, b = self.req("PUT", "/kv/name", json.dumps({"value": {"n": [1, 2]}}).encode())
        self.assertEqual(s, 200); self.assertIn("application/json", ct); self.assertEqual(json.loads(b), {"key": "name", "value": {"n": [1, 2]}})
        s, ct, b = self.req("GET", "/kv/name"); self.assertEqual((s, json.loads(b)["value"]), (200, {"n": [1, 2]}))
        self.assertEqual(self.req("DELETE", "/kv/name")[0], 204); self.assertEqual(self.req("GET", "/kv/name")[0], 404); self.assertEqual(self.req("DELETE", "/kv/name")[0], 404)
    def test_bad(self):
        self.assertEqual(self.req("PUT", "/kv/x", b"not json")[0], 400); self.assertEqual(self.req("PUT", "/kv/x", b'{"v": 1}')[0], 400)
        self.assertEqual(self.req("GET", "/other")[0], 404); self.assertEqual(self.req("GET", "/kv/")[0], 404)
    def test_falsy_values(self):
        for v in (0, "", None, False):
            self.assertEqual(self.req("PUT", "/kv/f", json.dumps({"value": v}).encode())[0], 200); self.assertEqual(json.loads(self.req("GET", "/kv/f")[2])["value"], v)
