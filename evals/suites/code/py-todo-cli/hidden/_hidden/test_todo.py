import os, sys, unittest
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import subprocess, tempfile
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
def run(*a, f):
    return subprocess.run([sys.executable, "-m", "todo", *a], cwd=ROOT, capture_output=True, text=True, env={**os.environ, "TODO_FILE": f})
class T(unittest.TestCase):
    def setUp(self):
        self.f = os.path.join(tempfile.mkdtemp(), "t.json")
    def test_flow(self):
        self.assertEqual(run("add", "buy", "milk", f=self.f).stdout.strip(), "added 1: buy milk")
        self.assertEqual(run("add", "call Mwila", f=self.f).stdout.strip(), "added 2: call Mwila")
        r = run("done", "1", f=self.f); self.assertEqual((r.returncode, r.stdout.strip()), (0, "done 1"))
        self.assertEqual(run("list", f=self.f).stdout.strip().splitlines(), ["1. [x] buy milk", "2. [ ] call Mwila"])
    def test_unknown(self):
        r = run("done", "5", f=self.f); self.assertEqual(r.returncode, 1); self.assertTrue(r.stderr.strip())
    def test_ids_sequential(self):
        run("add", "a", f=self.f); run("add", "b", f=self.f); run("done", "1", f=self.f)
        self.assertEqual(run("add", "c", f=self.f).stdout.strip(), "added 3: c")
    def test_empty_list(self):
        r = run("list", f=self.f); self.assertEqual((r.returncode, r.stdout), (0, ""))
    def test_separate_modules(self):
        mods = [f for f in os.listdir(os.path.join(ROOT, "todo")) if f.endswith(".py") and f not in ("__init__.py", "__main__.py")]
        self.assertGreaterEqual(len(mods), 2)
