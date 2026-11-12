import os, sys, unittest
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import subprocess, tempfile
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
def run(path):
    return subprocess.run([sys.executable, os.path.join(ROOT, "slow.py"), path], capture_output=True, text=True)
class T(unittest.TestCase):
    def test_given(self):
        r = run(os.path.join(ROOT, "access.log")); self.assertEqual(r.returncode, 0)
        self.assertEqual(r.stdout.split("\n")[:3], ["/api/search 900", "/api/pay 390", "/api/users 68"])
    def test_small(self):
        f = tempfile.NamedTemporaryFile("w", delete=False, suffix=".log")
        f.write("t GET /a 200 10ms\nt GET /b 200 5ms\nt GET /a?x=1 200 30ms\nbad\n"); f.close()
        self.assertEqual(run(f.name).stdout.strip().splitlines(), ["/a 30", "/b 5"])
