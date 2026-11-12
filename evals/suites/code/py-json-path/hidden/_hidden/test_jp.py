import os, sys, unittest
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from jpath import get
D = {"a": {"b": [{"c": 1}, {"c": 2}]}, "users": [{"tags": ["x"]}, {"tags": ["y", "z"]}], "n": None}
class T(unittest.TestCase):
    def test_get(self):
        self.assertEqual(get(D, "a.b[1].c"), 2); self.assertEqual(get(D, "users[1].tags[-1]"), "z"); self.assertEqual(get([5, 6, 7], "[2]"), 7)
        self.assertIs(get(D, ""), D); self.assertIsNone(get(D, "n", "dflt"))
    def test_missing(self):
        self.assertEqual(get(D, "a.x", 0), 0); self.assertEqual(get(D, "a.b[9].c", "d"), "d"); self.assertEqual(get(D, "a[0]", "d"), "d"); self.assertEqual(get(D, "users.tags", "d"), "d")
    def test_bad(self):
        for p in ["a..b", "a[", "a[x]", ".a", "a.", "a b"]:
            with self.assertRaises(ValueError, msg=p): get(D, p)
