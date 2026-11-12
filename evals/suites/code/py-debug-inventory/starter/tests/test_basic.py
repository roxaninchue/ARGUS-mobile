import unittest
from inventory.catalog import Catalog


class Basic(unittest.TestCase):
    def test_add(self):
        c = Catalog()
        c.add("a", "Apple", "1.00")
        self.assertEqual(c.items[0]["sku"], "a")
