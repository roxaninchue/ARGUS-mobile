import os, sys, unittest
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from roman import to_roman, from_roman
class T(unittest.TestCase):
    def test_round(self):
        for i in range(1, 4000): self.assertEqual(from_roman(to_roman(i)), i)
        self.assertEqual(to_roman(1994), "MCMXCIV"); self.assertEqual(to_roman(3999), "MMMCMXCIX")
    def test_bad(self):
        for n in [0, 4000, -1, 2.5, "5", True]:
            with self.assertRaises(ValueError): to_roman(n)
        for s in ["", "IIII", "IC", "MMMM", "abc", "VX", "iv", None]:
            with self.assertRaises(ValueError): from_roman(s)
