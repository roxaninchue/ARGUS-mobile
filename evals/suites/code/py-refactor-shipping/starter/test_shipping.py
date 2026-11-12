import unittest
from shipping import shipping_cost


class T(unittest.TestCase):
    def test_some(self):
        self.assertEqual(shipping_cost(0.5, "local", False, False, False), 20)
        self.assertEqual(shipping_cost(10, "national", True, True, True), 184.5)
        self.assertEqual(shipping_cost(25, "international", False, False, False), 540)


if __name__ == "__main__":
    unittest.main()
