import os, sys, unittest
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import subprocess, tempfile, shutil, csv
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
class T(unittest.TestCase):
    def run_on(self, text):
        d = tempfile.mkdtemp(); p = os.path.join(d, "c.csv"); open(p, "w").write(text)
        r = subprocess.run([sys.executable, os.path.join(ROOT, "dedupe.py"), p], capture_output=True, text=True, cwd=d)
        return r, list(csv.reader(open(os.path.join(d, "c.clean.csv"))))
    def test_given(self):
        r, rows = self.run_on(open(os.path.join(ROOT, "contacts.csv")).read())
        self.assertEqual(r.returncode, 0); self.assertIn("removed 2 duplicates", r.stdout)
        self.assertEqual([x[0] for x in rows], ["name", "Mwila Banda", "Chanda P", "No Mail", "Other"])
    def test_columns(self):
        r, rows = self.run_on("email,name\nA@x.io,1\na@x.io ,2\n")
        self.assertEqual(rows, [["email", "name"], ["A@x.io", "1"]]); self.assertIn("removed 1 duplicates", r.stdout)
    def test_quotes(self):
        r, rows = self.run_on('name,email\n"Banda, M",m@x.io\nX,M@X.IO\n')
        self.assertEqual(rows[1], ["Banda, M", "m@x.io"]); self.assertEqual(len(rows), 2)
