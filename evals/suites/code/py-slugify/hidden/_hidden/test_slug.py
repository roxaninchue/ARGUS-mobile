import os, sys, unittest
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from text import slugify
class T(unittest.TestCase):
    def test_cases(self):
        self.assertEqual(slugify("  Héllo, Wörld!! 2026 "), "hello-world-2026")
        self.assertEqual(slugify("Ça va?"), "ca-va")
        self.assertEqual(slugify("---"), "")
        self.assertEqual(slugify(""), "")
        self.assertEqual(slugify("A_B--C"), "a-b-c")
        self.assertEqual(slugify("Lusaka 10101"), "lusaka-10101")
