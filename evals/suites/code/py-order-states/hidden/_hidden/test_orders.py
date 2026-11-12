import os, sys, unittest
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from orders import Order, InvalidTransition
class T(unittest.TestCase):
    def test_happy(self):
        o = Order(); o.pay(); o.ship(); o.deliver(); self.assertEqual(o.history, ["new", "paid", "shipped", "delivered"])
    def test_cancel(self):
        o = Order(); o.pay(); o.cancel(); self.assertEqual(o.state, "cancelled")
        o = Order(); o.pay(); o.ship()
        with self.assertRaises(InvalidTransition): o.cancel()
        self.assertEqual(o.state, "shipped"); self.assertEqual(o.history, ["new", "paid", "shipped"])
    def test_invalid(self):
        o = Order()
        for f in (o.ship, o.deliver):
            with self.assertRaises(InvalidTransition): f()
        self.assertEqual(o.history, ["new"]); o.cancel()
        with self.assertRaises(InvalidTransition): o.pay()
