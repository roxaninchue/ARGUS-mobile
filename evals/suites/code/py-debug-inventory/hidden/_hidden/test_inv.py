import os, sys, unittest
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from inventory.catalog import Catalog
from inventory.orders import order_total
class T(unittest.TestCase):
    def test_cents(self):
        c = Catalog(); c.add("a", "A", "0.29"); c.add("b", "B", 4.35); c.add("c", "C", "19.99")
        self.assertEqual(order_total(c, [("a", 1)]), "0.29"); self.assertEqual(order_total(c, [("b", 3), ("c", 1)]), "33.04")
    def test_pages(self):
        c = Catalog()
        for i in range(5): c.add(str(i), "n", 1)
        self.assertEqual([i["sku"] for i in c.page(1)], ["0", "1"]); self.assertEqual([i["sku"] for i in c.page(2)], ["2", "3"]); self.assertEqual([i["sku"] for i in c.page(3)], ["4"]); self.assertEqual(c.page(4), [])
        self.assertEqual([i["sku"] for i in c.page(2, size=3)], ["3", "4"])
    def test_isolation(self):
        a = Catalog(); a.add("x", "X", 1); b = Catalog(); self.assertEqual(b.items, [])
        seed = [{"sku": "s", "name": "S", "cents": 1}]; c = Catalog(seed); c.add("y", "Y", 1); self.assertEqual(len(seed), 1)
    def test_existing_suite(self):
        r = unittest.TextTestRunner(stream=open(os.devnull, "w")).run(unittest.defaultTestLoader.discover(os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "tests"), top_level_dir=os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
        self.assertTrue(r.wasSuccessful())
