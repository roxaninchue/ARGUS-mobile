import os, sys, unittest
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import time
from lru import LRUCache
class T(unittest.TestCase):
    def test_evict(self):
        c = LRUCache(2); c.put("a", 1); c.put("b", 2); c.get("a"); c.put("c", 3)
        self.assertIsNone(c.get("b")); self.assertEqual(c.get("a"), 1); self.assertEqual(c.get("c"), 3); self.assertEqual(len(c), 2)
    def test_update(self):
        c = LRUCache(2); c.put("a", 1); c.put("b", 2); c.put("a", 9); c.put("c", 3)
        self.assertEqual(c.get("a"), 9); self.assertEqual(c.get("b", "gone"), "gone")
    def test_bad(self):
        with self.assertRaises(ValueError): LRUCache(0)
    def test_speed(self):
        c = LRUCache(50000); t = time.time()
        for i in range(200000): c.put(i, i); c.get(i - 10)
        self.assertLess(time.time() - t, 3.0)
    def test_no_functools(self):
        self.assertNotIn("lru_cache", open(os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "lru.py")).read())
