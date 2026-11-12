import os, sys, unittest
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import subprocess
from fizzbuzz import fizzbuzz
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
class T(unittest.TestCase):
    def test_list(self):
        self.assertEqual(fizzbuzz(15)[-1], "FizzBuzz")
        self.assertEqual(fizzbuzz(5), ["1", "2", "Fizz", "4", "Buzz"])
        self.assertEqual(fizzbuzz(0), [])
    def test_cli(self):
        r = subprocess.run([sys.executable, "fizzbuzz.py", "6"], cwd=ROOT, capture_output=True, text=True)
        self.assertEqual(r.returncode, 0); self.assertEqual(r.stdout.split(), ["1", "2", "Fizz", "4", "Buzz", "Fizz"])
    def test_usage(self):
        for args in ([], ["x"], ["1", "2"]):
            r = subprocess.run([sys.executable, "fizzbuzz.py", *args], cwd=ROOT, capture_output=True, text=True)
            self.assertEqual(r.returncode, 2); self.assertIn("usage", r.stderr.lower()); self.assertEqual(r.stdout, "")
