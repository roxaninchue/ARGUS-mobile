import os, sys, unittest
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from duration import parse_duration as p
class T(unittest.TestCase):
    def test_ok(self):
        self.assertEqual(p("45s"), 45); self.assertEqual(p("1h30m"), 5400); self.assertEqual(p("2d"), 172800)
        self.assertEqual(p("1d2h3m4s"), 93784); self.assertEqual(p(" 10M "), 600); self.assertEqual(p("0s"), 0)
    def test_bad(self):
        for s in ["", "5", "1m1h", "1x", "-3s", "1h 30m", "h", "1hh"]:
            with self.assertRaises(ValueError, msg=s): p(s)
