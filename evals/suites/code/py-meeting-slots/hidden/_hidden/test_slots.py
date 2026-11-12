import os, sys, unittest
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from slots import free_slots as f
class T(unittest.TestCase):
    def test_basic(self):
        self.assertEqual(f([[("09:00", "10:00")], [("09:30", "11:00")]], "08:00", "12:00", 30), [("08:00", "09:00"), ("11:00", "12:00")])
    def test_outside_and_short(self):
        self.assertEqual(f([[("07:00", "08:20"), ("12:30", "13:00")], [("08:40", "12:10")]], "08:00", "12:30", 20), [("08:20", "08:40"), ("12:10", "12:30")])
        self.assertEqual(f([[("08:00", "12:00")]], "08:00", "12:00", 5), [])
        self.assertEqual(f([], "09:00", "10:00", 60), [("09:00", "10:00")])
        self.assertEqual(f([[("10:30", "11:00")]], "09:00", "10:00", 30), [("09:00", "10:00")])
    def test_bad(self):
        for args in [([[("9:00", "10:00")]], "08:00", "12:00", 30), ([], "12:00", "08:00", 30), ([[("10:00", "09:00")]], "08:00", "12:00", 30), ([], "08:00", "24:00", 30)]:
            with self.assertRaises(ValueError): f(*args)
