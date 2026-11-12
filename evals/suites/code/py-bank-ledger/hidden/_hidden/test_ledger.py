import os, sys, unittest
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from decimal import Decimal
from ledger import Ledger, InsufficientFunds
class T(unittest.TestCase):
    def setUp(self):
        self.l = Ledger(); self.l.open("a"); self.l.open("b")
    def test_money(self):
        self.l.deposit("a", 0.1); self.l.deposit("a", "0.2"); self.assertEqual(self.l.balance("a"), Decimal("0.30"))
        self.l.withdraw("a", "0.05"); self.assertEqual(self.l.balance("a"), Decimal("0.25"))
    def test_transfer(self):
        self.l.deposit("a", 10); self.l.transfer("a", "b", 4)
        self.assertEqual((self.l.balance("a"), self.l.balance("b")), (Decimal("6.00"), Decimal("4.00")))
        self.assertEqual(self.l.history("b"), [("transfer_in", Decimal("4.00"))])
    def test_failures(self):
        self.l.deposit("a", 1)
        with self.assertRaises(InsufficientFunds): self.l.transfer("a", "b", 2)
        self.assertEqual((self.l.balance("a"), self.l.balance("b")), (Decimal("1.00"), Decimal("0.00"))); self.assertEqual(len(self.l.history("a")), 1)
        with self.assertRaises(KeyError): self.l.transfer("a", "zz", 1)
        self.assertEqual(self.l.balance("a"), Decimal("1.00"))
        for bad in (0, -1, "-0.01"):
            with self.assertRaises(ValueError): self.l.deposit("a", bad)
        with self.assertRaises(ValueError): self.l.open("a")
        with self.assertRaises(KeyError): self.l.balance("nope")
        self.assertTrue(issubclass(InsufficientFunds, Exception))
