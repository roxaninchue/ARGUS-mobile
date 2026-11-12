import os, sys, unittest
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from ini import parse_ini
class T(unittest.TestCase):
    def test_parse(self):
        self.assertEqual(parse_ini("top=1\n[db]\n host = x \n; c\n# c\n\nurl = a = b\nhost=y\n[ e ]\n"), {"": {"top": "1"}, "db": {"host": "y", "url": "a = b"}, "e": {}})
        self.assertEqual(parse_ini(""), {})
    def test_error(self):
        with self.assertRaises(ValueError) as cm: parse_ini("[a]\nok=1\njunk line")
        self.assertIn("3", str(cm.exception))
        with self.assertRaises(ValueError): parse_ini("=novalue")
