import os, sys, unittest
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import tempfile
from summary import summarize
def w(text):
    f = tempfile.NamedTemporaryFile("w", suffix=".csv", delete=False); f.write(text); f.close(); return f.name
class T(unittest.TestCase):
    def test_mixed(self):
        self.assertEqual(summarize(w("name,age,score,note\nA,30,1.5,x\nB,,2.5,y\nC,40,3,\n")), {"age": 35.0, "score": 2.33})
    def test_empty(self):
        self.assertEqual(summarize(w("a,b\n")), {}); self.assertEqual(summarize(w("a,b\n,\n")), {})
    def test_quoted(self):
        self.assertEqual(summarize(w('x,y\n"1,5",2\n3,4\n')), {"y": 3.0})
