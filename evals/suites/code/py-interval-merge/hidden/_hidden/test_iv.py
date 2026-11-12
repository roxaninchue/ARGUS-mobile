import os, sys, unittest
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from intervals import merge
class T(unittest.TestCase):
    def test_merge(self):
        self.assertEqual(merge([[5, 6], [1, 3], [2, 4]]), [[1, 4], [5, 6]])
        self.assertEqual(merge([[1, 2], [2, 3]]), [[1, 3]]); self.assertEqual(merge([]), [])
        self.assertEqual(merge([[1, 10], [2, 3], [4, 5]]), [[1, 10]])
    def test_no_mutation(self):
        x = [[3, 4], [1, 2]]; merge(x); self.assertEqual(x, [[3, 4], [1, 2]])
    def test_bad(self):
        with self.assertRaises(ValueError): merge([[3, 1]])
