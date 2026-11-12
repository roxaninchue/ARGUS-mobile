import os, sys, unittest
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from words import top_words
class T(unittest.TestCase):
    def test_basic(self):
        self.assertEqual(top_words("The cat; the CAT! the dog.", 2), [("the", 3), ("cat", 2)])
        self.assertEqual(top_words("b a c b a", 5), [("a", 2), ("b", 2), ("c", 1)])
    def test_apostrophes(self):
        self.assertEqual(top_words("don't 'quote' don't", 2), [("don't", 2), ("quote", 1)])
    def test_edges(self):
        self.assertEqual(top_words("", 3), []); self.assertEqual(top_words("a", 0), []); self.assertEqual(top_words("''", 3), [])
