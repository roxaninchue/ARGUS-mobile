import os, sys, unittest
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from ratelimit import TokenBucket
class Clock:
    def __init__(self): self.t = 100.0
    def __call__(self): return self.t
class T(unittest.TestCase):
    def test_burst_refill(self):
        c = Clock(); b = TokenBucket(2, 3, clock=c)
        self.assertEqual([b.allow() for _ in range(4)], [True, True, True, False])
        c.t += 0.5; self.assertTrue(b.allow()); self.assertFalse(b.allow())
        c.t += 100; self.assertTrue(b.allow(3)); self.assertFalse(b.allow())
    def test_n(self):
        c = Clock(); b = TokenBucket(1, 2, clock=c)
        self.assertFalse(b.allow(3)); self.assertTrue(b.allow(2))
        with self.assertRaises(ValueError): b.allow(0)
    def test_bad(self):
        for a in [(0, 1), (1, 0), (-1, 5)]:
            with self.assertRaises(ValueError): TokenBucket(*a)
