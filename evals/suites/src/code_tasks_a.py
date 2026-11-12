"""Code-quality tasks, part A (levels 1-3). Imported by code.py."""
HP = "import os, sys, unittest\nsys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))\n"
PYT = 'python3 -m unittest discover -s _hidden -p "test_*.py"'
JST = 'node --test _hidden/*.test.js'
T = []
def task(id, level, tags, prompt, ref, hidden, starter=None, test_cmd=PYT, verify='', **kw):
    T.append(dict(id=id, level=level, tags=tags, prompt=prompt, ref=ref, hidden=hidden, starter=starter or {}, test_cmd=test_cmd, verify=verify, **kw))

task('py-fizzbuzz-cli', 1, ['python', 'cli'],
 'In ~/proj write fizzbuzz.py: a function fizzbuzz(n) that returns the list of strings for 1..n (Fizz/Buzz/FizzBuzz rules), and a CLI: `python3 fizzbuzz.py 15` prints one item per line. A bad or missing argument prints a usage line to stderr and exits 2.',
 required=['fizzbuzz.py'], verify='python3 fizzbuzz.py 15 | tail -3; python3 fizzbuzz.py x; echo exit=$?',
 ref={'fizzbuzz.py': '''import sys


def fizzbuzz(n):
    out = []
    for i in range(1, n + 1):
        s = ("Fizz" if i % 3 == 0 else "") + ("Buzz" if i % 5 == 0 else "")
        out.append(s or str(i))
    return out


def main(argv):
    if len(argv) != 1 or not argv[0].isdigit():
        print("usage: fizzbuzz.py N", file=sys.stderr)
        return 2
    print("\\n".join(fizzbuzz(int(argv[0]))))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
'''},
 hidden={'_hidden/test_fb.py': HP + '''import subprocess
from fizzbuzz import fizzbuzz
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
class T(unittest.TestCase):
    def test_list(self):
        self.assertEqual(fizzbuzz(15)[-1], "FizzBuzz")
        self.assertEqual(fizzbuzz(5), ["1", "2", "Fizz", "4", "Buzz"])
        self.assertEqual(fizzbuzz(0), [])
    def test_cli(self):
        r = subprocess.run([sys.executable, "fizzbuzz.py", "6"], cwd=ROOT, capture_output=True, text=True)
        self.assertEqual(r.returncode, 0); self.assertEqual(r.stdout.split(), ["1", "2", "Fizz", "4", "Buzz", "Fizz"])
    def test_usage(self):
        for args in ([], ["x"], ["1", "2"]):
            r = subprocess.run([sys.executable, "fizzbuzz.py", *args], cwd=ROOT, capture_output=True, text=True)
            self.assertEqual(r.returncode, 2); self.assertIn("usage", r.stderr.lower()); self.assertEqual(r.stdout, "")
'''})

task('py-slugify', 1, ['python'],
 'In ~/proj/text.py write slugify(s): lowercase, accents removed (é -> e), anything not a-z/0-9 becomes a dash, no repeated/leading/trailing dashes. slugify("  Héllo, Wörld!! 2026 ") == "hello-world-2026".',
 required=['text.py'], verify='python3 -c "from text import slugify; print(slugify(\'  Héllo, Wörld!! 2026 \'))"',
 ref={'text.py': '''import re
import unicodedata


def slugify(s):
    s = unicodedata.normalize("NFKD", s).encode("ascii", "ignore").decode("ascii")
    return re.sub(r"[^a-z0-9]+", "-", s.lower()).strip("-")
'''},
 hidden={'_hidden/test_slug.py': HP + '''from text import slugify
class T(unittest.TestCase):
    def test_cases(self):
        self.assertEqual(slugify("  Héllo, Wörld!! 2026 "), "hello-world-2026")
        self.assertEqual(slugify("Ça va?"), "ca-va")
        self.assertEqual(slugify("---"), "")
        self.assertEqual(slugify(""), "")
        self.assertEqual(slugify("A_B--C"), "a-b-c")
        self.assertEqual(slugify("Lusaka 10101"), "lusaka-10101")
'''})

task('py-parse-duration', 2, ['python', 'parsing'],
 'In ~/proj/duration.py write parse_duration(s) -> int seconds for strings like "45s", "1h30m", "2d", "1d2h3m4s" (units d,h,m,s in that order, each at most once, case-insensitive, surrounding spaces ok). Anything else (empty, "5", "1m1h", "1x", "-3s", "1h 30m") raises ValueError.',
 required=['duration.py'], verify='python3 -c "from duration import parse_duration as p; print(p(\'1h30m\'))"',
 ref={'duration.py': '''import re

_RE = re.compile(r"(?:(\\d+)d)?(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s)?")
_MULT = (86400, 3600, 60, 1)


def parse_duration(s):
    if not isinstance(s, str):
        raise ValueError("duration must be a string")
    m = _RE.fullmatch(s.strip().lower())
    if not m or not any(m.groups()):
        raise ValueError(f"bad duration: {s!r}")
    return sum(int(g) * k for g, k in zip(m.groups(), _MULT) if g)
'''},
 hidden={'_hidden/test_dur.py': HP + '''from duration import parse_duration as p
class T(unittest.TestCase):
    def test_ok(self):
        self.assertEqual(p("45s"), 45); self.assertEqual(p("1h30m"), 5400); self.assertEqual(p("2d"), 172800)
        self.assertEqual(p("1d2h3m4s"), 93784); self.assertEqual(p(" 10M "), 600); self.assertEqual(p("0s"), 0)
    def test_bad(self):
        for s in ["", "5", "1m1h", "1x", "-3s", "1h 30m", "h", "1hh"]:
            with self.assertRaises(ValueError, msg=s): p(s)
'''})

task('js-chunk', 1, ['js'],
 'In ~/proj/chunk.js export (CommonJS) chunk(arr, size): splits an array into arrays of length size (last may be shorter). Throws a RangeError if size is not a positive integer. Must not modify the input.',
 required=['chunk.js'], test_cmd=JST, verify='node -e "console.log(JSON.stringify(require(\'./chunk\')([1,2,3,4,5],2)))"',
 ref={'chunk.js': '''function chunk(arr, size) {
  if (!Number.isInteger(size) || size < 1) throw new RangeError('size must be a positive integer');
  const out = [];
  for (let i = 0; i < arr.length; i += size) out.push(arr.slice(i, i + size));
  return out;
}
module.exports = chunk;
module.exports.chunk = chunk;
'''},
 hidden={'_hidden/chunk.test.js': '''const test = require('node:test'), assert = require('node:assert');
const m = require('../chunk.js'); const chunk = typeof m === 'function' ? m : m.chunk;
test('splits', () => { assert.deepStrictEqual(chunk([1, 2, 3, 4, 5], 2), [[1, 2], [3, 4], [5]]); assert.deepStrictEqual(chunk([], 3), []); assert.deepStrictEqual(chunk([1], 5), [[1]]); });
test('no mutation', () => { const a = [1, 2, 3]; chunk(a, 1); assert.deepStrictEqual(a, [1, 2, 3]); });
test('bad size', () => { for (const s of [0, -1, 1.5, NaN, '2']) assert.throws(() => chunk([1], s), RangeError); });
'''})

task('py-roman', 2, ['python'],
 'In ~/proj/roman.py write to_roman(n) for 1..3999 and from_roman(s) that accepts only canonical numerals (e.g. "IIII", "IC", "MMMM", "" are invalid). Both raise ValueError on bad input.',
 required=['roman.py'], verify='python3 -c "from roman import *; print(to_roman(1994), from_roman(\'MCMXCIV\'))"',
 ref={'roman.py': '''_VALS = [(1000, "M"), (900, "CM"), (500, "D"), (400, "CD"), (100, "C"), (90, "XC"),
         (50, "L"), (40, "XL"), (10, "X"), (9, "IX"), (5, "V"), (4, "IV"), (1, "I")]


def to_roman(n):
    if not isinstance(n, int) or isinstance(n, bool) or not 1 <= n <= 3999:
        raise ValueError("n must be an int in 1..3999")
    out = []
    for v, sym in _VALS:
        q, n = divmod(n, v)
        out.append(sym * q)
    return "".join(out)


_CANON = {to_roman(i): i for i in range(1, 4000)}


def from_roman(s):
    if not isinstance(s, str) or s not in _CANON:
        raise ValueError(f"not a canonical roman numeral: {s!r}")
    return _CANON[s]
'''},
 hidden={'_hidden/test_roman.py': HP + '''from roman import to_roman, from_roman
class T(unittest.TestCase):
    def test_round(self):
        for i in range(1, 4000): self.assertEqual(from_roman(to_roman(i)), i)
        self.assertEqual(to_roman(1994), "MCMXCIV"); self.assertEqual(to_roman(3999), "MMMCMXCIX")
    def test_bad(self):
        for n in [0, 4000, -1, 2.5, "5", True]:
            with self.assertRaises(ValueError): to_roman(n)
        for s in ["", "IIII", "IC", "MMMM", "abc", "VX", "iv", None]:
            with self.assertRaises(ValueError): from_roman(s)
'''})

task('js-deep-equal', 2, ['js'],
 'In ~/proj/equal.js export deepEqual(a, b): structural equality for primitives (NaN equals NaN), arrays, plain objects (key order ignored), Dates (same time), Maps and Sets. Arrays never equal objects. Handle cyclic references without crashing.',
 required=['equal.js'], test_cmd=JST, verify='node -e "const d=require(\'./equal\');console.log((d.deepEqual||d)({a:[1,{b:2}]},{a:[1,{b:2}]}))"',
 ref={'equal.js': '''function deepEqual(a, b, seen = new Map()) {
  if (Object.is(a, b)) return true;
  if (typeof a !== 'object' || typeof b !== 'object' || a === null || b === null) return false;
  if (Object.getPrototypeOf(a) !== Object.getPrototypeOf(b)) return false;
  if (seen.get(a) === b) return true;
  seen.set(a, b);
  if (a instanceof Date) return a.getTime() === b.getTime();
  if (a instanceof Map) return mapsEqual(a, b, seen);
  if (a instanceof Set) return a.size === b.size && [...a].every(x => [...b].some(y => deepEqual(x, y, seen)));
  const ka = Object.keys(a), kb = Object.keys(b);
  if (ka.length !== kb.length) return false;
  return ka.every(k => Object.prototype.hasOwnProperty.call(b, k) && deepEqual(a[k], b[k], seen));
}
function mapsEqual(a, b, seen) {
  if (a.size !== b.size) return false;
  for (const [k, v] of a) if (!b.has(k) || !deepEqual(v, b.get(k), seen)) return false;
  return true;
}
module.exports = deepEqual;
module.exports.deepEqual = deepEqual;
'''},
 hidden={'_hidden/equal.test.js': '''const test = require('node:test'), assert = require('node:assert');
const m = require('../equal.js'); const eq = typeof m === 'function' ? m : m.deepEqual;
test('basics', () => { assert.ok(eq(NaN, NaN)); assert.ok(eq({ a: 1, b: [1, 2] }, { b: [1, 2], a: 1 })); assert.ok(!eq([1], { 0: 1 })); assert.ok(!eq(1, '1')); assert.ok(!eq(null, {})); assert.ok(!eq({ a: undefined }, { b: undefined })); });
test('types', () => { assert.ok(eq(new Date(5), new Date(5))); assert.ok(!eq(new Date(5), new Date(6))); assert.ok(eq(new Map([[1, { x: 1 }]]), new Map([[1, { x: 1 }]]))); assert.ok(!eq(new Map([[1, 2]]), new Map([[1, 3]]))); assert.ok(eq(new Set([1, 2]), new Set([2, 1]))); assert.ok(!eq(new Set([1]), new Set([2]))); });
test('cycles', () => { const a = { n: 1 }; a.self = a; const b = { n: 1 }; b.self = b; assert.ok(eq(a, b)); const c = { n: 2 }; c.self = c; assert.ok(!eq(a, c)); });
'''})

task('py-word-freq', 2, ['python', 'text'],
 'In ~/proj/words.py write top_words(text, k) -> list of (word, count): case-insensitive, words are runs of letters/digits/apostrophes (strip leading/trailing apostrophes), sorted by count desc then word asc, at most k items (k <= 0 gives []).',
 required=['words.py'], verify='python3 -c "from words import top_words; print(top_words(\'a b a\', 2))"',
 ref={'words.py': '''import re
from collections import Counter


def top_words(text, k):
    if k <= 0:
        return []
    words = (w.strip("'") for w in re.findall(r"[a-z0-9']+", text.lower()))
    counts = Counter(w for w in words if w)
    return sorted(counts.items(), key=lambda kv: (-kv[1], kv[0]))[:k]
'''},
 hidden={'_hidden/test_words.py': HP + '''from words import top_words
class T(unittest.TestCase):
    def test_basic(self):
        self.assertEqual(top_words("The cat; the CAT! the dog.", 2), [("the", 3), ("cat", 2)])
        self.assertEqual(top_words("b a c b a", 5), [("a", 2), ("b", 2), ("c", 1)])
    def test_apostrophes(self):
        self.assertEqual(top_words("don't 'quote' don't", 2), [("don't", 2), ("quote", 1)])
    def test_edges(self):
        self.assertEqual(top_words("", 3), []); self.assertEqual(top_words("a", 0), []); self.assertEqual(top_words("''", 3), [])
'''})

task('py-csv-summary', 2, ['python', 'data'],
 'In ~/proj/summary.py write summarize(path) for a CSV with a header: return {column: mean} for every column whose non-empty values are all numbers (ints/floats, empty cells ignored, means rounded to 2 decimals). Columns with any non-numeric value or no values are left out.',
 required=['summary.py'], verify='printf "a,b\\n1,x\\n2,y\\n" > /tmp/s.csv && python3 -c "from summary import summarize; print(summarize(\'/tmp/s.csv\'))"',
 ref={'summary.py': '''import csv


def _num(v):
    try:
        return float(v)
    except ValueError:
        return None


def summarize(path):
    with open(path, newline="") as f:
        rows = list(csv.DictReader(f))
    if not rows:
        return {}
    out = {}
    for col in rows[0].keys():
        vals = [r[col].strip() for r in rows if r[col] is not None and r[col].strip() != ""]
        nums = [_num(v) for v in vals]
        if nums and all(n is not None for n in nums):
            out[col] = round(sum(nums) / len(nums), 2)
    return out
'''},
 hidden={'_hidden/test_sum.py': HP + '''import tempfile
from summary import summarize
def w(text):
    f = tempfile.NamedTemporaryFile("w", suffix=".csv", delete=False); f.write(text); f.close(); return f.name
class T(unittest.TestCase):
    def test_mixed(self):
        self.assertEqual(summarize(w("name,age,score,note\\nA,30,1.5,x\\nB,,2.5,y\\nC,40,3,\\n")), {"age": 35.0, "score": 2.33})
    def test_empty(self):
        self.assertEqual(summarize(w("a,b\\n")), {}); self.assertEqual(summarize(w("a,b\\n,\\n")), {})
    def test_quoted(self):
        self.assertEqual(summarize(w('x,y\\n"1,5",2\\n3,4\\n')), {"y": 3.0})
'''})

task('py-lru-cache', 3, ['python', 'datastructures'],
 'In ~/proj/lru.py write class LRUCache(capacity) with get(key, default=None) and put(key, value), O(1) each, evicting the least recently used item when full (get and put both count as use). len(cache) works and capacity < 1 raises ValueError. Do not use functools.lru_cache.',
 required=['lru.py'], verify='python3 -c "from lru import LRUCache; c=LRUCache(1); c.put(1,1); c.put(2,2); print(c.get(1), c.get(2))"',
 ref={'lru.py': '''from collections import OrderedDict


class LRUCache:
    def __init__(self, capacity):
        if capacity < 1:
            raise ValueError("capacity must be >= 1")
        self.capacity = capacity
        self._d = OrderedDict()

    def get(self, key, default=None):
        if key not in self._d:
            return default
        self._d.move_to_end(key)
        return self._d[key]

    def put(self, key, value):
        self._d[key] = value
        self._d.move_to_end(key)
        if len(self._d) > self.capacity:
            self._d.popitem(last=False)

    def __len__(self):
        return len(self._d)
'''},
 hidden={'_hidden/test_lru.py': HP + '''import time
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
'''})

task('js-event-emitter', 3, ['js'],
 'In ~/proj/emitter.js export class Emitter with on(event, fn) (returns an unsubscribe function), off(event, fn), once(event, fn) and emit(event, ...args) (returns how many listeners ran). Listeners run in registration order; adding/removing listeners during an emit must not affect that emit. Do not use node:events.',
 required=['emitter.js'], test_cmd=JST, verify='node -e "const {Emitter}=require(\'./emitter\');const e=new Emitter();e.on(\'x\',v=>console.log(v));e.emit(\'x\',1)"',
 ref={'emitter.js': '''class Emitter {
  constructor() { this.map = new Map(); }
  on(event, fn) {
    if (typeof fn !== 'function') throw new TypeError('listener must be a function');
    if (!this.map.has(event)) this.map.set(event, []);
    this.map.get(event).push(fn);
    return () => this.off(event, fn);
  }
  off(event, fn) {
    const list = this.map.get(event);
    if (!list) return;
    const i = list.findIndex(l => l === fn || l.orig === fn);
    if (i >= 0) list.splice(i, 1);
  }
  once(event, fn) {
    const w = (...args) => { this.off(event, w); return fn(...args); };
    w.orig = fn;
    return this.on(event, w);
  }
  emit(event, ...args) {
    const list = (this.map.get(event) || []).slice();
    for (const fn of list) fn(...args);
    return list.length;
  }
}
module.exports = { Emitter };
'''},
 hidden={'_hidden/emitter.test.js': '''const test = require('node:test'), assert = require('node:assert'), fs = require('node:fs'), path = require('node:path');
const { Emitter } = require('../emitter.js');
test('order and args', () => { const e = new Emitter(), got = []; e.on('a', x => got.push(1 + x)); e.on('a', x => got.push(2 + x)); assert.strictEqual(e.emit('a', 10), 2); assert.deepStrictEqual(got, [11, 12]); assert.strictEqual(e.emit('none'), 0); });
test('unsubscribe and off', () => { const e = new Emitter(); let n = 0; const f = () => n++; const un = e.on('a', f); e.emit('a'); un(); e.emit('a'); e.on('a', f); e.off('a', f); e.emit('a'); assert.strictEqual(n, 1); });
test('once', () => { const e = new Emitter(); let n = 0; const f = () => n++; e.once('a', f); e.emit('a'); e.emit('a'); assert.strictEqual(n, 1); e.once('b', f); e.off('b', f); e.emit('b'); assert.strictEqual(n, 1); });
test('mutation during emit', () => { const e = new Emitter(), got = []; const b = () => got.push('b'); e.on('a', () => { got.push('a'); e.off('a', b); e.on('a', () => got.push('c')); }); e.on('a', b); e.emit('a'); assert.deepStrictEqual(got, ['a', 'b']); });
test('no node:events', () => { assert.ok(!/require\\(['"](node:)?events['"]\\)/.test(fs.readFileSync(path.join(__dirname, '..', 'emitter.js'), 'utf8'))); });
'''})

task('py-interval-merge', 2, ['python', 'algorithms'],
 'In ~/proj/intervals.py write merge(intervals) that merges overlapping or touching [start, end] pairs and returns them sorted. Input may be unsorted, must not be modified, and an interval with start > end raises ValueError.',
 required=['intervals.py'], verify='python3 -c "from intervals import merge; print(merge([[5,6],[1,3],[2,4]]))"',
 ref={'intervals.py': '''def merge(intervals):
    items = sorted([list(iv) for iv in intervals])
    out = []
    for s, e in items:
        if s > e:
            raise ValueError(f"bad interval {[s, e]}")
        if out and s <= out[-1][1]:
            out[-1][1] = max(out[-1][1], e)
        else:
            out.append([s, e])
    return out
'''},
 hidden={'_hidden/test_iv.py': HP + '''from intervals import merge
class T(unittest.TestCase):
    def test_merge(self):
        self.assertEqual(merge([[5, 6], [1, 3], [2, 4]]), [[1, 4], [5, 6]])
        self.assertEqual(merge([[1, 2], [2, 3]]), [[1, 3]]); self.assertEqual(merge([]), [])
        self.assertEqual(merge([[1, 10], [2, 3], [4, 5]]), [[1, 10]])
    def test_no_mutation(self):
        x = [[3, 4], [1, 2]]; merge(x); self.assertEqual(x, [[3, 4], [1, 2]])
    def test_bad(self):
        with self.assertRaises(ValueError): merge([[3, 1]])
'''})

task('js-semver', 3, ['js', 'parsing'],
 'In ~/proj/semver.js export compare(a, b) (-1/0/1) and sortVersions(list) for semantic versions "MAJOR.MINOR.PATCH" with optional "-prerelease" (dot-separated identifiers; numeric ids compare numerically and sort before alphanumeric ones; a prerelease sorts before its release). An optional leading "v" is allowed. Invalid versions throw TypeError. sortVersions returns a new array.',
 required=['semver.js'], test_cmd=JST, verify='node -e "console.log(require(\'./semver\').sortVersions([\'1.10.0\',\'1.2.0\',\'1.2.0-rc.1\']))"',
 ref={'semver.js': '''const RE = /^v?(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?$/;
function parse(v) {
  const m = RE.exec(String(v));
  if (!m) throw new TypeError('invalid version: ' + v);
  return { nums: m.slice(1, 4).map(Number), pre: m[4] ? m[4].split('.') : [] };
}
const cmp = (x, y) => (x < y ? -1 : x > y ? 1 : 0);
function cmpId(a, b) {
  const na = /^\\d+$/.test(a), nb = /^\\d+$/.test(b);
  if (na && nb) return cmp(Number(a), Number(b));
  if (na !== nb) return na ? -1 : 1;
  return cmp(a, b);
}
function comparePre(a, b) {
  if (!a.length || !b.length) return cmp(b.length, a.length);
  for (let i = 0; i < Math.min(a.length, b.length); i++) { const c = cmpId(a[i], b[i]); if (c) return c; }
  return cmp(a.length, b.length);
}
function compare(a, b) {
  const x = parse(a), y = parse(b);
  for (let i = 0; i < 3; i++) if (x.nums[i] !== y.nums[i]) return cmp(x.nums[i], y.nums[i]);
  return comparePre(x.pre, y.pre);
}
const sortVersions = list => list.slice().sort(compare);
module.exports = { compare, sortVersions };
'''},
 hidden={'_hidden/semver.test.js': '''const test = require('node:test'), assert = require('node:assert');
const { compare, sortVersions } = require('../semver.js');
test('compare', () => { assert.strictEqual(compare('1.10.0', '1.2.0'), 1); assert.strictEqual(compare('v1.0.0', '1.0.0'), 0); assert.strictEqual(compare('1.0.0-alpha', '1.0.0'), -1); assert.strictEqual(compare('1.0.0-alpha.2', '1.0.0-alpha.10'), -1); assert.strictEqual(compare('1.0.0-1', '1.0.0-alpha'), -1); assert.strictEqual(compare('1.0.0-alpha', '1.0.0-alpha.1'), -1); });
test('sort', () => { const l = ['1.0.0', '1.0.0-rc.1', '0.9.9', '1.0.0-beta', '1.0.0-alpha.beta', '1.0.0-alpha', '1.0.0-alpha.1', '1.0.0-beta.2', '1.0.0-beta.11']; const s = sortVersions(l);
  assert.deepStrictEqual(s, ['0.9.9', '1.0.0-alpha', '1.0.0-alpha.1', '1.0.0-alpha.beta', '1.0.0-beta', '1.0.0-beta.2', '1.0.0-beta.11', '1.0.0-rc.1', '1.0.0']); assert.strictEqual(l[0], '1.0.0'); });
test('invalid', () => { for (const v of ['1.0', '01.0.0', '1.0.0-', 'x', '', '1.0.0-a..b']) assert.throws(() => compare(v, '1.0.0'), TypeError); });
'''})

task('py-rate-limiter', 3, ['python', 'design'],
 'In ~/proj/ratelimit.py write class TokenBucket(rate, capacity, clock=time.monotonic): allow(n=1) -> bool takes n tokens if available. It starts full, refills continuously at `rate` tokens/second up to capacity. The clock is injectable for tests. rate <= 0, capacity <= 0 or n <= 0 raise ValueError; n > capacity is simply False.',
 required=['ratelimit.py'], verify='python3 -c "from ratelimit import TokenBucket; b=TokenBucket(1,2); print([b.allow() for _ in range(3)])"',
 ref={'ratelimit.py': '''import time


class TokenBucket:
    def __init__(self, rate, capacity, clock=time.monotonic):
        if rate <= 0 or capacity <= 0:
            raise ValueError("rate and capacity must be > 0")
        self.rate, self.capacity, self.clock = rate, capacity, clock
        self.tokens = float(capacity)
        self.last = clock()

    def _refill(self):
        now = self.clock()
        self.tokens = min(self.capacity, self.tokens + (now - self.last) * self.rate)
        self.last = now

    def allow(self, n=1):
        if n <= 0:
            raise ValueError("n must be > 0")
        self._refill()
        if n <= self.tokens:
            self.tokens -= n
            return True
        return False
'''},
 hidden={'_hidden/test_rl.py': HP + '''from ratelimit import TokenBucket
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
'''})

task('py-json-path', 3, ['python', 'parsing'],
 'In ~/proj/jpath.py write get(obj, path, default=None) for paths like "a.b[0].c", "[2]", "users[1].tags[-1]", or "" (the object itself). Missing keys, out-of-range indexes or indexing the wrong type return default. A malformed path ("a..b", "a[", "a[x]", ".a") raises ValueError.',
 required=['jpath.py'], verify='python3 -c "from jpath import get; print(get({\'a\':[{\'b\':1}]}, \'a[0].b\'))"',
 ref={'jpath.py': '''import re

_TOKEN = re.compile(r"([A-Za-z_][\\w-]*)|\\[(-?\\d+)\\]")
_MISSING = object()


def _tokens(path):
    if path == "":
        return []
    out, pos = [], 0
    while pos < len(path):
        if out and path[pos] == "." and pos + 1 < len(path) and path[pos + 1] != "[":
            pos += 1
        m = _TOKEN.match(path, pos)
        if not m or (m.group(1) and out and path[pos - 1] != "."):
            raise ValueError(f"bad path: {path!r}")
        out.append(m.group(1) if m.group(1) else int(m.group(2)))
        pos = m.end()
    return out


def _step(cur, tok):
    if isinstance(tok, int):
        if isinstance(cur, list) and -len(cur) <= tok < len(cur):
            return cur[tok]
        return _MISSING
    if isinstance(cur, dict) and tok in cur:
        return cur[tok]
    return _MISSING


def get(obj, path, default=None):
    cur = obj
    for tok in _tokens(path):
        cur = _step(cur, tok)
        if cur is _MISSING:
            return default
    return cur
'''},
 hidden={'_hidden/test_jp.py': HP + '''from jpath import get
D = {"a": {"b": [{"c": 1}, {"c": 2}]}, "users": [{"tags": ["x"]}, {"tags": ["y", "z"]}], "n": None}
class T(unittest.TestCase):
    def test_get(self):
        self.assertEqual(get(D, "a.b[1].c"), 2); self.assertEqual(get(D, "users[1].tags[-1]"), "z"); self.assertEqual(get([5, 6, 7], "[2]"), 7)
        self.assertIs(get(D, ""), D); self.assertIsNone(get(D, "n", "dflt"))
    def test_missing(self):
        self.assertEqual(get(D, "a.x", 0), 0); self.assertEqual(get(D, "a.b[9].c", "d"), "d"); self.assertEqual(get(D, "a[0]", "d"), "d"); self.assertEqual(get(D, "users.tags", "d"), "d")
    def test_bad(self):
        for p in ["a..b", "a[", "a[x]", ".a", "a.", "a b"]:
            with self.assertRaises(ValueError, msg=p): get(D, p)
'''})
