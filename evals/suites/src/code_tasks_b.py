"""Code-quality tasks, part B (levels 3-5: multi-file, ambiguous specs, debugging unfamiliar repos, demos with
optional stretch features). Imported by code.py."""
from code_tasks_a import HP, PYT, JST, task, T  # noqa: F401  (shared helpers and list)

task('py-ini-parser', 3, ['python', 'parsing'],
 'In ~/proj/ini.py write parse_ini(text) -> dict of sections -> dict of key -> value. Keys before any section go in section "". Ignore blank lines and lines starting with ; or #. Strip whitespace around keys and values; "key = a = b" gives value "a = b". Duplicate keys: last wins. A line that is not a section, comment or key=value raises ValueError with the line number in the message.',
 required=['ini.py'], verify='python3 -c "from ini import parse_ini; print(parse_ini(\'[a]\\nx=1\'))"',
 ref={'ini.py': '''import re

_SECTION = re.compile(r"^\\[([^\\]]+)\\]$")


def parse_ini(text):
    data = {"": {}}
    cur = ""
    for no, raw in enumerate(text.splitlines(), 1):
        line = raw.strip()
        if not line or line[0] in ";#":
            continue
        m = _SECTION.match(line)
        if m:
            cur = m.group(1).strip()
            data.setdefault(cur, {})
        elif "=" in line and line.split("=", 1)[0].strip():
            key, val = line.split("=", 1)
            data[cur][key.strip()] = val.strip()
        else:
            raise ValueError(f"line {no}: cannot parse {raw!r}")
    if not data[""]:
        del data[""]
    return data
'''},
 hidden={'_hidden/test_ini.py': HP + '''from ini import parse_ini
class T(unittest.TestCase):
    def test_parse(self):
        self.assertEqual(parse_ini("top=1\\n[db]\\n host = x \\n; c\\n# c\\n\\nurl = a = b\\nhost=y\\n[ e ]\\n"), {"": {"top": "1"}, "db": {"host": "y", "url": "a = b"}, "e": {}})
        self.assertEqual(parse_ini(""), {})
    def test_error(self):
        with self.assertRaises(ValueError) as cm: parse_ini("[a]\\nok=1\\njunk line")
        self.assertIn("3", str(cm.exception))
        with self.assertRaises(ValueError): parse_ini("=novalue")
'''})

task('js-debounce', 3, ['js', 'async'],
 'In ~/proj/debounce.js export debounce(fn, ms) returning a debounced function with .cancel() and .flush(). Calls within ms of each other collapse into one trailing call with the latest arguments and `this`. flush() runs a pending call immediately (returns its result); cancel() drops it.',
 required=['debounce.js'], test_cmd=JST, verify='node -e "const d=require(\'./debounce\');const f=(d.debounce||d)(x=>console.log(x),10);f(1);f(2)"',
 ref={'debounce.js': '''function debounce(fn, ms) {
  let timer = null, pending = null;
  function run() {
    timer = null;
    const p = pending; pending = null;
    return fn.apply(p.self, p.args);
  }
  function debounced(...args) {
    pending = { self: this, args };
    if (timer) clearTimeout(timer);
    timer = setTimeout(run, ms);
  }
  debounced.cancel = () => { if (timer) clearTimeout(timer); timer = null; pending = null; };
  debounced.flush = () => {
    if (!pending) return undefined;
    clearTimeout(timer);
    return run();
  };
  return debounced;
}
module.exports = debounce;
module.exports.debounce = debounce;
'''},
 hidden={'_hidden/debounce.test.js': '''const test = require('node:test'), assert = require('node:assert');
const m = require('../debounce.js'); const debounce = typeof m === 'function' ? m : m.debounce;
test('trailing, latest args and this', (t) => { t.mock.timers.enable({ apis: ['setTimeout'] }); const calls = []; const o = { v: 7, f: debounce(function (x) { calls.push([this.v, x]); }, 100) };
  o.f(1); t.mock.timers.tick(50); o.f(2); t.mock.timers.tick(99); assert.deepStrictEqual(calls, []); t.mock.timers.tick(1); assert.deepStrictEqual(calls, [[7, 2]]); t.mock.timers.tick(500); assert.strictEqual(calls.length, 1); });
test('cancel and flush', (t) => { t.mock.timers.enable({ apis: ['setTimeout'] }); let n = 0; const f = debounce(x => { n += x; return n; }, 100);
  f(5); f.cancel(); t.mock.timers.tick(200); assert.strictEqual(n, 0); f(3); assert.strictEqual(f.flush(), 3); t.mock.timers.tick(200); assert.strictEqual(n, 3); assert.strictEqual(f.flush(), undefined); });
'''})

task('py-bank-ledger', 3, ['python', 'design'],
 'In ~/proj/ledger.py write class Ledger with open(name), deposit(name, amount), withdraw(name, amount), transfer(src, dst, amount), balance(name) and history(name) (list of (kind, amount) tuples, kinds "deposit", "withdraw", "transfer_in", "transfer_out"). Use Decimal and round to cents. Raise KeyError for unknown accounts, ValueError for non-positive amounts or duplicate open, and InsufficientFunds (your own exception, subclass of Exception) when a balance would go negative. A failed transfer must change nothing.',
 required=['ledger.py'], verify='python3 -c "from ledger import Ledger; l=Ledger(); l.open(\'a\'); l.deposit(\'a\', \'0.10\'); l.deposit(\'a\', 0.2); print(l.balance(\'a\'))"',
 ref={'ledger.py': '''from decimal import Decimal, ROUND_HALF_UP


class InsufficientFunds(Exception):
    pass


def _money(x):
    d = Decimal(str(x)).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)
    if d <= 0:
        raise ValueError("amount must be positive")
    return d


class Ledger:
    def __init__(self):
        self._bal, self._hist = {}, {}

    def open(self, name):
        if name in self._bal:
            raise ValueError(f"account {name} exists")
        self._bal[name], self._hist[name] = Decimal("0.00"), []

    def _acct(self, name):
        if name not in self._bal:
            raise KeyError(name)
        return name

    def deposit(self, name, amount):
        a = _money(amount)
        self._bal[self._acct(name)] += a
        self._hist[name].append(("deposit", a))

    def withdraw(self, name, amount):
        a = _money(amount)
        if self._bal[self._acct(name)] < a:
            raise InsufficientFunds(name)
        self._bal[name] -= a
        self._hist[name].append(("withdraw", a))

    def transfer(self, src, dst, amount):
        a = _money(amount)
        self._acct(src), self._acct(dst)
        if self._bal[src] < a:
            raise InsufficientFunds(src)
        self._bal[src] -= a
        self._bal[dst] += a
        self._hist[src].append(("transfer_out", a))
        self._hist[dst].append(("transfer_in", a))

    def balance(self, name):
        return self._bal[self._acct(name)]

    def history(self, name):
        return list(self._hist[self._acct(name)])
'''},
 hidden={'_hidden/test_ledger.py': HP + '''from decimal import Decimal
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
'''})

TODO_STORE = '''import json
import os


class Store:
    def __init__(self, path):
        self.path = path

    def load(self):
        if not os.path.exists(self.path):
            return []
        with open(self.path) as f:
            return json.load(f)

    def save(self, items):
        tmp = self.path + ".tmp"
        with open(tmp, "w") as f:
            json.dump(items, f, indent=1)
        os.replace(tmp, self.path)
'''
TODO_CLI = '''import argparse
import os
import sys

from .store import Store


def _parser():
    p = argparse.ArgumentParser(prog="todo")
    sub = p.add_subparsers(dest="cmd", required=True)
    a = sub.add_parser("add")
    a.add_argument("title", nargs="+")
    sub.add_parser("list")
    d = sub.add_parser("done")
    d.add_argument("id", type=int)
    return p


def main(argv=None):
    args = _parser().parse_args(argv)
    store = Store(os.environ.get("TODO_FILE", os.path.expanduser("~/.todo.json")))
    items = store.load()
    if args.cmd == "add":
        item = {"id": max([i["id"] for i in items], default=0) + 1, "title": " ".join(args.title), "done": False}
        items.append(item)
        store.save(items)
        print(f"added {item['id']}: {item['title']}")
    elif args.cmd == "list":
        for i in items:
            print(f"{i['id']}. [{'x' if i['done'] else ' '}] {i['title']}")
    else:
        match = [i for i in items if i["id"] == args.id]
        if not match:
            print(f"no todo {args.id}", file=sys.stderr)
            return 1
        match[0]["done"] = True
        store.save(items)
        print(f"done {args.id}")
    return 0
'''
task('py-todo-cli', 4, ['python', 'multi-file', 'demo', 'stretch'],
 'Build a small todo CLI as a package in ~/proj/todo/ runnable as `python3 -m todo`: `add <title...>` prints "added <id>: <title>", `list` prints lines like "1. [ ] buy milk" / "2. [x] call Mwila", `done <id>` prints "done <id>" (unknown id: message on stderr, exit 1). Data is a JSON file at $TODO_FILE (default ~/.todo.json); ids start at 1 and are never reused. Keep storage and CLI in separate modules. Optional if you have time: `list --pending` showing only open items. A small version that fully works beats a bigger broken one.',
 required=['todo/__main__.py', 'todo/__init__.py'],
 verify='export TODO_FILE=/tmp/t.json; rm -f $TODO_FILE; python3 -m todo add buy milk && python3 -m todo add call Mwila && python3 -m todo done 1 && python3 -m todo list; python3 -m todo done 9; echo exit=$?',
 stretch_marker='pending', stretch_cmd='cd /root/proj && export TODO_FILE=$(mktemp -u); python3 -m todo add a >/dev/null && python3 -m todo add b >/dev/null && python3 -m todo done 1 >/dev/null; out=$(python3 -m todo list --pending 2>&1); rc=$?; if [ $rc = 2 ] && echo "$out" | grep -qi "unrecognized\\|usage"; then exit 3; fi; [ "$out" = "2. [ ] b" ]',
 ref={'todo/__init__.py': '', 'todo/__main__.py': 'import sys\n\nfrom .cli import main\n\nsys.exit(main())\n', 'todo/store.py': TODO_STORE, 'todo/cli.py': TODO_CLI},
 hidden={'_hidden/test_todo.py': HP + '''import subprocess, tempfile
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
def run(*a, f):
    return subprocess.run([sys.executable, "-m", "todo", *a], cwd=ROOT, capture_output=True, text=True, env={**os.environ, "TODO_FILE": f})
class T(unittest.TestCase):
    def setUp(self):
        self.f = os.path.join(tempfile.mkdtemp(), "t.json")
    def test_flow(self):
        self.assertEqual(run("add", "buy", "milk", f=self.f).stdout.strip(), "added 1: buy milk")
        self.assertEqual(run("add", "call Mwila", f=self.f).stdout.strip(), "added 2: call Mwila")
        r = run("done", "1", f=self.f); self.assertEqual((r.returncode, r.stdout.strip()), (0, "done 1"))
        self.assertEqual(run("list", f=self.f).stdout.strip().splitlines(), ["1. [x] buy milk", "2. [ ] call Mwila"])
    def test_unknown(self):
        r = run("done", "5", f=self.f); self.assertEqual(r.returncode, 1); self.assertTrue(r.stderr.strip())
    def test_ids_sequential(self):
        run("add", "a", f=self.f); run("add", "b", f=self.f); run("done", "1", f=self.f)
        self.assertEqual(run("add", "c", f=self.f).stdout.strip(), "added 3: c")
    def test_empty_list(self):
        r = run("list", f=self.f); self.assertEqual((r.returncode, r.stdout), (0, ""))
    def test_separate_modules(self):
        mods = [f for f in os.listdir(os.path.join(ROOT, "todo")) if f.endswith(".py") and f not in ("__init__.py", "__main__.py")]
        self.assertGreaterEqual(len(mods), 2)
'''})

MD_REF = {'md/inline.js': '''const esc = s => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
function inline(text) {
  const codes = [];
  let s = esc(text).replace(/`([^`]+)`/g, (m, c) => { codes.push(c); return '\\u0000' + (codes.length - 1) + '\\u0000'; });
  s = s.replace(/\\[([^\\]]+)\\]\\(([^)\\s]+)\\)/g, (m, t, u) => /^(https?:|mailto:|\\/|#)/i.test(u) ? `<a href="${u}">${t}</a>` : t);
  s = s.replace(/\\*\\*([^*]+)\\*\\*/g, '<strong>$1</strong>').replace(/\\*([^*]+)\\*/g, '<em>$1</em>');
  return s.replace(/\\u0000(\\d+)\\u0000/g, (m, i) => `<code>${codes[i]}</code>`);
}
module.exports = { esc, inline };
''', 'md/index.js': '''const { esc, inline } = require('./inline');
const BLOCK_START = /^(```|#{1,6} |[-*] )/;

function fence(lines, i) {
  const body = [];
  i++;
  while (i < lines.length && !/^```/.test(lines[i])) body.push(lines[i++]);
  return [`<pre><code>${esc(body.join('\\n'))}</code></pre>`, i + 1];
}
function heading(lines, i) {
  const n = lines[i].indexOf(' ');
  return [`<h${n}>${inline(lines[i].slice(n + 1).trim())}</h${n}>`, i + 1];
}
function list(lines, i) {
  const items = [];
  while (i < lines.length && /^[-*] /.test(lines[i])) items.push(`<li>${inline(lines[i++].slice(2).trim())}</li>`);
  return [`<ul>${items.join('')}</ul>`, i];
}
function paragraph(lines, i) {
  const para = [];
  while (i < lines.length && lines[i].trim() && !BLOCK_START.test(lines[i])) para.push(lines[i++].trim());
  return [`<p>${inline(para.join(' '))}</p>`, i];
}
function blockAt(lines, i) {
  const l = lines[i];
  if (/^```/.test(l)) return fence(lines, i);
  if (/^#{1,6} /.test(l)) return heading(lines, i);
  if (/^[-*] /.test(l)) return list(lines, i);
  return paragraph(lines, i);
}
function render(src) {
  const lines = String(src).replace(/\\r\\n?/g, '\\n').split('\\n'), out = [];
  let i = 0;
  while (i < lines.length) {
    if (!lines[i].trim()) { i++; continue; }
    const [html, next] = blockAt(lines, i);
    out.push(html);
    i = next;
  }
  return out.join('\\n');
}
module.exports = { render };
'''}
task('js-markdown-mini', 4, ['js', 'multi-file', 'stretch'],
 'In ~/proj/md/ build a tiny markdown renderer: require("./md").render(text) -> HTML string. Support: "# " to "###### " headings, paragraphs (consecutive lines joined with a space), "- " or "* " bullet lists (<ul><li>), ``` fenced code blocks (<pre><code>, content escaped, no inline formatting), and inline **bold**, *italic*, `code` and [text](url) links (only http(s), mailto, / or # urls; others render as plain text). Escape &, <, >, " everywhere. Blocks are joined with "\\n". Put inline formatting in its own module. Optional: | tables |. Prefer a smaller version that fully works.',
 required=['md/index.js'], test_cmd=JST, verify='node -e "console.log(require(\'./md\').render(\'# Hi\\n\\nSome **bold** <b>\\n- a\\n- b\'))"',
 stretch_marker='table', stretch_cmd='''cd /root/proj && node -e "const h=require('./md').render('| a | b |\\n|---|---|\\n| 1 | 2 |'); if(!/<table/.test(h)) process.exit(3); process.exit(/<th>a<\\/th>/.test(h) && /<td>2<\\/td>/.test(h) ? 0 : 1)"''',
 ref=MD_REF,
 hidden={'_hidden/md.test.js': '''const test = require('node:test'), assert = require('node:assert'), fs = require('node:fs'), path = require('node:path');
const { render } = require('../md');
test('blocks', () => { assert.strictEqual(render('# Title\\n\\nline one\\nline two\\n\\n- a\\n* b'), '<h1>Title</h1>\\n<p>line one line two</p>\\n<ul><li>a</li><li>b</li></ul>'); assert.strictEqual(render('###### six'), '<h6>six</h6>'); assert.strictEqual(render('####### seven'), '<p>####### seven</p>'); });
test('inline', () => { assert.strictEqual(render('**b** and *i* and `x*y*`'), '<p><strong>b</strong> and <em>i</em> and <code>x*y*</code></p>'); assert.strictEqual(render('[ok](https://a.io) [bad](javascript:void)'), '<p><a href="https://a.io">ok</a> bad</p>'); });
test('escaping', () => { assert.strictEqual(render('a < b & "c" > d'), '<p>a &lt; b &amp; &quot;c&quot; &gt; d</p>'); assert.strictEqual(render('```\\n<script>**x**</script>\\n```'), '<pre><code>&lt;script&gt;**x**&lt;/script&gt;</code></pre>'); });
test('modules', () => { assert.ok(fs.readdirSync(path.join(__dirname, '..', 'md')).filter(f => f.endsWith('.js')).length >= 2); });
'''})

INV_STARTER = {
 'README.md': '# inventory\n\nTiny inventory service used by the shop bots. `python3 -m unittest` runs the tests.\n',
 'inventory/__init__.py': '',
 'inventory/money.py': '''def to_cents(amount):
    return int(float(amount) * 100)


def fmt(cents):
    return f"{cents // 100}.{cents % 100:02d}"
''',
 'inventory/catalog.py': '''from .money import to_cents


class Catalog:
    def __init__(self, items=[]):
        self.items = items

    def add(self, sku, name, price):
        self.items.append({"sku": sku, "name": name, "cents": to_cents(price)})

    def page(self, n, size=2):
        start = n * size
        return self.items[start - size:start + 1]
''',
 'inventory/orders.py': '''from .money import fmt


def order_total(catalog, lines):
    prices = {i["sku"]: i["cents"] for i in catalog.items}
    return fmt(sum(prices[sku] * qty for sku, qty in lines))
''',
 'tests/__init__.py': '',
 'tests/test_basic.py': '''import unittest
from inventory.catalog import Catalog


class Basic(unittest.TestCase):
    def test_add(self):
        c = Catalog()
        c.add("a", "Apple", "1.00")
        self.assertEqual(c.items[0]["sku"], "a")
'''}
task('py-debug-inventory', 5, ['python', 'debugging', 'unfamiliar-repo'],
 'The inventory service in ~/proj has bug reports: (1) order totals are sometimes a cent off, (2) page 2 of the catalog repeats items from page 1, (3) items show up in catalogs they were never added to. Find and fix the root causes. Keep the public API (Catalog(items), add, page(n, size) with pages numbered from 1, order_total -> "D.CC" string). Do not edit the existing tests.',
 starter=INV_STARTER, protected=['tests/test_basic.py'], required=['inventory/catalog.py'], verify='python3 -m unittest -q',
 ref={'inventory/money.py': '''from decimal import Decimal, ROUND_HALF_UP


def to_cents(amount):
    return int((Decimal(str(amount)) * 100).quantize(Decimal("1"), rounding=ROUND_HALF_UP))


def fmt(cents):
    return f"{cents // 100}.{cents % 100:02d}"
''', 'inventory/catalog.py': '''from .money import to_cents


class Catalog:
    def __init__(self, items=None):
        self.items = list(items) if items is not None else []

    def add(self, sku, name, price):
        self.items.append({"sku": sku, "name": name, "cents": to_cents(price)})

    def page(self, n, size=2):
        start = (n - 1) * size
        return self.items[start:start + size]
'''},
 hidden={'_hidden/test_inv.py': HP + '''from inventory.catalog import Catalog
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
'''})

CART_STARTER = {
 'package.json': '{"name":"cart","version":"1.0.0","private":true,"scripts":{"test":"node --test test/*.test.js"}}\n',
 'src/prices.js': '''// pretend this is a remote price service
async function priceOf(sku) {
  await new Promise(r => setTimeout(r, 5));
  const table = { tea: 450, mug: 1200, cake: 300 };
  if (!(sku in table)) throw new Error('unknown sku ' + sku);
  return table[sku];
}
module.exports = { priceOf };
''',
 'src/cart.js': '''const { priceOf } = require('./prices');
const { applyDiscount } = require('./discount');

async function total(lines, code) {
  let sum = 0;
  for (const l of lines) {
    const p = priceOf(l.sku);
    sum += p * l.qty;
  }
  return applyDiscount(applyDiscount(sum, code), code);
}

function receipt(lines) {
  return lines.sort((a, b) => a.sku.localeCompare(b.sku)).map(l => `${l.qty} x ${l.sku}`);
}
module.exports = { total, receipt };
''',
 'src/discount.js': '''const CODES = { TEN: 0.1, HALF: 0.5 };
function applyDiscount(cents, code) {
  if (!code) return cents;
  if (!(code in CODES)) throw new Error('bad code ' + code);
  return Math.round(cents * (1 - CODES[code]));
}
module.exports = { applyDiscount };
''',
 'test/discount.test.js': '''const test = require('node:test'), assert = require('node:assert');
const { applyDiscount } = require('../src/discount');
test('discount', () => { assert.strictEqual(applyDiscount(1000, 'TEN'), 900); assert.strictEqual(applyDiscount(1000), 1000); });
'''}
task('js-debug-cart', 4, ['js', 'debugging', 'unfamiliar-repo'],
 'The cart module in ~/proj gives wrong totals (sometimes "NaN"), discounts look too big, and printing a receipt reorders the customer\'s cart. Fix the root causes without changing the public functions (total(lines, code) async -> cents, receipt(lines) -> string[] sorted by sku). Unknown skus or codes must still reject/throw. Keep the existing test passing and do not edit it.',
 starter=CART_STARTER, protected=['test/discount.test.js'], required=['src/cart.js'], test_cmd='node --test _hidden/*.test.js test/*.test.js', verify='npm test',
 ref={'src/cart.js': '''const { priceOf } = require('./prices');
const { applyDiscount } = require('./discount');

async function total(lines, code) {
  let sum = 0;
  for (const l of lines) {
    const p = await priceOf(l.sku);
    sum += p * l.qty;
  }
  return applyDiscount(sum, code);
}

function receipt(lines) {
  return lines.slice().sort((a, b) => a.sku.localeCompare(b.sku)).map(l => `${l.qty} x ${l.sku}`);
}
module.exports = { total, receipt };
'''},
 hidden={'_hidden/cart.test.js': '''const test = require('node:test'), assert = require('node:assert');
const { total, receipt } = require('../src/cart');
test('total', async () => { assert.strictEqual(await total([{ sku: 'tea', qty: 2 }, { sku: 'mug', qty: 1 }]), 2100); assert.strictEqual(await total([{ sku: 'mug', qty: 1 }], 'TEN'), 1080); assert.strictEqual(await total([]), 0); });
test('errors', async () => { await assert.rejects(total([{ sku: 'nope', qty: 1 }])); await assert.rejects(total([{ sku: 'tea', qty: 1 }], 'FREE')); });
test('receipt keeps input', () => { const l = [{ sku: 'tea', qty: 1 }, { sku: 'cake', qty: 2 }]; assert.deepStrictEqual(receipt(l), ['2 x cake', '1 x tea']); assert.strictEqual(l[0].sku, 'tea'); });
'''})

task('py-dedupe-contacts', 4, ['python', 'ambiguous', 'data'],
 'Our contacts export ~/proj/contacts.csv has duplicates: the same person appears more than once, with the email typed in different case or with spaces. Write ~/proj/dedupe.py so `python3 dedupe.py contacts.csv` writes contacts.clean.csv next to the input with one row per person (keep the first occurrence and the original column order and header), and prints "removed N duplicates". Rows with an empty email are kept as they are. Use your judgement for anything not specified.',
 starter={'contacts.csv': 'name,email,phone\nMwila Banda,mwila@zamtel.zm,0977\nChanda P,chanda@x.io,\nMWILA B, Mwila@Zamtel.ZM ,0966\nNo Mail,,123\nOther,,\nchanda,CHANDA@x.io,1\n'},
 required=['dedupe.py'], verify='python3 dedupe.py contacts.csv && cat contacts.clean.csv',
 ref={'dedupe.py': '''import csv
import os
import sys


def dedupe(rows, email_col="email"):
    seen, out = set(), []
    for r in rows:
        key = (r.get(email_col) or "").strip().lower()
        if key and key in seen:
            continue
        if key:
            seen.add(key)
        out.append(r)
    return out


def main(argv):
    if len(argv) != 1:
        print("usage: dedupe.py FILE.csv", file=sys.stderr)
        return 2
    src = argv[0]
    with open(src, newline="") as f:
        reader = csv.DictReader(f)
        fields, rows = reader.fieldnames, list(reader)
    kept = dedupe(rows)
    base, ext = os.path.splitext(src)
    with open(base + ".clean" + (ext or ".csv"), "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=fields)
        w.writeheader()
        w.writerows(kept)
    print(f"removed {len(rows) - len(kept)} duplicates")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
'''},
 hidden={'_hidden/test_dedupe.py': HP + '''import subprocess, tempfile, shutil, csv
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
class T(unittest.TestCase):
    def run_on(self, text):
        d = tempfile.mkdtemp(); p = os.path.join(d, "c.csv"); open(p, "w").write(text)
        r = subprocess.run([sys.executable, os.path.join(ROOT, "dedupe.py"), p], capture_output=True, text=True, cwd=d)
        return r, list(csv.reader(open(os.path.join(d, "c.clean.csv"))))
    def test_given(self):
        r, rows = self.run_on(open(os.path.join(ROOT, "contacts.csv")).read())
        self.assertEqual(r.returncode, 0); self.assertIn("removed 2 duplicates", r.stdout)
        self.assertEqual([x[0] for x in rows], ["name", "Mwila Banda", "Chanda P", "No Mail", "Other"])
    def test_columns(self):
        r, rows = self.run_on("email,name\\nA@x.io,1\\na@x.io ,2\\n")
        self.assertEqual(rows, [["email", "name"], ["A@x.io", "1"]]); self.assertIn("removed 1 duplicates", r.stdout)
    def test_quotes(self):
        r, rows = self.run_on('name,email\\n"Banda, M",m@x.io\\nX,M@X.IO\\n')
        self.assertEqual(rows[1], ["Banda, M", "m@x.io"]); self.assertEqual(len(rows), 2)
'''})

task('py-slow-endpoints', 4, ['python', 'ambiguous', 'cli'],
 'I want to know which endpoints are slowest in ~/proj/access.log. Write ~/proj/slow.py so `python3 slow.py access.log` prints the 3 slowest paths by p95 latency, slowest first, one per line as "<path> <p95 in ms, integer>". Ignore query strings and malformed lines. p95 = nearest-rank method.',
 starter={'access.log': '\n'.join(
   [f'2026-11-08T10:{i:02d}:00Z GET /api/users?id={i} 200 {50 + i}ms' for i in range(20)] +
   [f'2026-11-08T11:{i:02d}:00Z POST /api/pay 200 {300 + 10 * i}ms' for i in range(10)] +
   ['garbage line', '2026-11-08T12:00:00Z GET /health 200 2ms', '2026-11-08T12:00:01Z GET /health 200 3ms',
    '2026-11-08T12:00:02Z GET /api/search?q=x 500 900ms', '2026-11-08T12:00:03Z GET /api/search 200 100ms', '2026-11-08T12:00:04Z GET /broken 200 xms']) + '\n'},
 required=['slow.py'], verify='python3 slow.py access.log',
 ref={'slow.py': '''import math
import re
import sys
from collections import defaultdict

LINE = re.compile(r"^\\S+ [A-Z]+ (/[^\\s?]*)(?:\\?\\S*)? \\d{3} (\\d+)ms$")


def p95(values):
    v = sorted(values)
    return v[max(0, math.ceil(0.95 * len(v)) - 1)]


def slowest(lines, k=3):
    by = defaultdict(list)
    for line in lines:
        m = LINE.match(line.strip())
        if m:
            by[m.group(1)].append(int(m.group(2)))
    ranked = sorted(((p95(v), path) for path, v in by.items()), key=lambda t: (-t[0], t[1]))
    return [(path, ms) for ms, path in ranked[:k]]


def main(argv):
    if len(argv) != 1:
        print("usage: slow.py ACCESS_LOG", file=sys.stderr)
        return 2
    with open(argv[0]) as f:
        for path, ms in slowest(f):
            print(f"{path} {ms}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
'''},
 hidden={'_hidden/test_slow.py': HP + '''import subprocess, tempfile
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
def run(path):
    return subprocess.run([sys.executable, os.path.join(ROOT, "slow.py"), path], capture_output=True, text=True)
class T(unittest.TestCase):
    def test_given(self):
        r = run(os.path.join(ROOT, "access.log")); self.assertEqual(r.returncode, 0)
        self.assertEqual(r.stdout.split("\\n")[:3], ["/api/search 900", "/api/pay 390", "/api/users 68"])
    def test_small(self):
        f = tempfile.NamedTemporaryFile("w", delete=False, suffix=".log")
        f.write("t GET /a 200 10ms\\nt GET /b 200 5ms\\nt GET /a?x=1 200 30ms\\nbad\\n"); f.close()
        self.assertEqual(run(f.name).stdout.strip().splitlines(), ["/a 30", "/b 5"])
'''})

task('py-order-states', 3, ['python', 'design'],
 'In ~/proj/orders.py write class Order with state starting at "new" and methods pay(), ship(), deliver(), cancel(). Allowed: new->paid->shipped->delivered; cancel from new or paid only. Anything else raises InvalidTransition (your exception) and leaves the state unchanged. order.history is the list of states visited, starting with "new".',
 required=['orders.py'], verify='python3 -c "from orders import Order; o=Order(); o.pay(); o.ship(); print(o.state, o.history)"',
 ref={'orders.py': '''class InvalidTransition(Exception):
    pass


_NEXT = {
    "pay": ({"new"}, "paid"),
    "ship": ({"paid"}, "shipped"),
    "deliver": ({"shipped"}, "delivered"),
    "cancel": ({"new", "paid"}, "cancelled"),
}


class Order:
    def __init__(self):
        self.state = "new"
        self.history = ["new"]

    def _go(self, action):
        allowed, target = _NEXT[action]
        if self.state not in allowed:
            raise InvalidTransition(f"cannot {action} when {self.state}")
        self.state = target
        self.history.append(target)

    def pay(self):
        self._go("pay")

    def ship(self):
        self._go("ship")

    def deliver(self):
        self._go("deliver")

    def cancel(self):
        self._go("cancel")
'''},
 hidden={'_hidden/test_orders.py': HP + '''from orders import Order, InvalidTransition
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
'''})

task('js-wc-cli', 3, ['js', 'cli', 'demo', 'stretch'],
 'Write ~/proj/wc.js: `node wc.js FILE` prints "<lines> <words> <bytes> FILE" like Unix wc (lines = number of \\n). `--json` prints {"lines":..,"words":..,"bytes":..,"file":..} instead. A missing file prints an error to stderr and exits 1; no file argument prints usage and exits 2. Optional: several files, followed by a "total" line. Ship the part you can fully finish.',
 required=['wc.js'], test_cmd=JST, verify='printf "a b\\nc\\n" > /tmp/w.txt && node wc.js /tmp/w.txt && node wc.js --json /tmp/w.txt; node wc.js; echo exit=$?',
 stretch_marker='total', stretch_cmd='cd /root/proj && printf "a\\n" > /tmp/s1 && printf "b c\\n" > /tmp/s2 && out=$(node wc.js /tmp/s1 /tmp/s2 2>&1); [ "$(echo "$out" | wc -l)" -lt 3 ] && exit 3; [ "$(echo "$out" | tail -1)" = "2 3 6 total" ]',
 ref={'wc.js': '''const fs = require('fs');

function count(buf) {
  const text = buf.toString('utf8');
  return { lines: (text.match(/\\n/g) || []).length, words: text.split(/\\s+/).filter(Boolean).length, bytes: buf.length };
}

function main(argv) {
  const json = argv.includes('--json');
  const files = argv.filter(a => a !== '--json');
  if (files.length !== 1) { process.stderr.write('usage: node wc.js [--json] FILE\\n'); return 2; }
  let buf;
  try { buf = fs.readFileSync(files[0]); } catch (e) { process.stderr.write(`wc: ${files[0]}: ${e.code === 'ENOENT' ? 'No such file' : e.message}\\n`); return 1; }
  const c = count(buf);
  process.stdout.write(json ? JSON.stringify({ ...c, file: files[0] }) + '\\n' : `${c.lines} ${c.words} ${c.bytes} ${files[0]}\\n`);
  return 0;
}

if (require.main === module) process.exitCode = main(process.argv.slice(2));
module.exports = { count, main };
'''},
 hidden={'_hidden/wc.test.js': '''const test = require('node:test'), assert = require('node:assert'), { spawnSync } = require('node:child_process'), fs = require('node:fs'), os = require('node:os'), path = require('node:path');
const WC = path.join(__dirname, '..', 'wc.js');
const run = (...a) => spawnSync(process.execPath, [WC, ...a], { encoding: 'utf8' });
const tmp = (s) => { const p = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'wc-')), 'f.txt'); fs.writeFileSync(p, s); return p; };
test('plain', () => { const f = tmp('hello world\\nsecond line here\\nno newline'); const r = run(f); assert.strictEqual(r.status, 0); assert.strictEqual(r.stdout, `2 7 39 ${f}\\n`); });
test('unicode bytes', () => { const f = tmp('héllo\\n'); assert.strictEqual(run(f).stdout, `1 1 7 ${f}\\n`); });
test('json', () => { const f = tmp('a b\\n'); assert.deepStrictEqual(JSON.parse(run('--json', f).stdout), { lines: 1, words: 2, bytes: 4, file: f }); });
test('errors', () => { const r = run('/nope/x'); assert.strictEqual(r.status, 1); assert.ok(r.stderr.length); assert.strictEqual(r.stdout, ''); assert.strictEqual(run().status, 2); });
'''})

KV_REF = {'kvserver.py': '''import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

STORE = {}


class Handler(BaseHTTPRequestHandler):
    def _key(self):
        if not self.path.startswith("/kv/") or len(self.path) <= 4:
            return None
        return self.path[4:]

    def _send(self, code, body=None):
        data = json.dumps(body).encode() if body is not None else b""
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        key = self._key()
        if key is None:
            return self._send(404, {"error": "not found"})
        if key not in STORE:
            return self._send(404, {"error": "no such key"})
        self._send(200, {"key": key, "value": STORE[key]})

    def do_PUT(self):
        key = self._key()
        if key is None:
            return self._send(404, {"error": "not found"})
        raw = self.rfile.read(int(self.headers.get("Content-Length") or 0))
        try:
            value = json.loads(raw)["value"]
        except (ValueError, KeyError, TypeError):
            return self._send(400, {"error": "body must be JSON with a value"})
        STORE[key] = value
        self._send(200, {"key": key, "value": value})

    def do_DELETE(self):
        key = self._key()
        if key is None or key not in STORE:
            return self._send(404, {"error": "no such key"})
        del STORE[key]
        self._send(204)

    def log_message(self, *args):
        pass


def serve(port):
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()


if __name__ == "__main__":
    serve(int(sys.argv[1]) if len(sys.argv) > 1 else 8080)
'''}
task('py-kv-server', 5, ['python', 'http', 'demo', 'stretch'],
 'Build a tiny in-memory key-value HTTP service in ~/proj/kvserver.py with only the standard library: `python3 kvserver.py PORT` listens on 127.0.0.1. PUT /kv/<key> with JSON {"value": ...} stores it (200, echoes {"key","value"}); bad JSON or no "value" -> 400. GET /kv/<key> -> 200 {"key","value"} or 404. DELETE /kv/<key> -> 204 or 404. Any other path -> 404. Responses are JSON with Content-Type application/json. Optional: a "ttl" seconds field in PUT. Make sure what you ship fully works; demo it with curl.',
 required=['kvserver.py'], timeout=60, verify='python3 kvserver.py 8099 & pid=$!; sleep 1; curl -s -X PUT -d \'{"value":1}\' localhost:8099/kv/a; curl -s localhost:8099/kv/a; kill $pid',
 stretch_marker='ttl', stretch_cmd='''cd /root/proj && python3 kvserver.py 8771 >/dev/null 2>&1 & pid=$!; sleep 1; c=$(curl -s -o /dev/null -w "%{http_code}" -X PUT -d '{"value":1,"ttl":1}' localhost:8771/kv/t); sleep 1.5; g=$(curl -s -o /dev/null -w "%{http_code}" localhost:8771/kv/t); kill $pid; [ "$c" = 200 ] || exit 1; [ "$g" = 200 ] && exit 3; [ "$g" = 404 ]''',
 ref=KV_REF,
 hidden={'_hidden/test_kv.py': HP + '''import json, socket, subprocess, time, urllib.request, urllib.error
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
def free_port():
    s = socket.socket(); s.bind(("127.0.0.1", 0)); p = s.getsockname()[1]; s.close(); return p
class T(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.port = free_port(); cls.p = subprocess.Popen([sys.executable, os.path.join(ROOT, "kvserver.py"), str(cls.port)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        for _ in range(50):
            try: socket.create_connection(("127.0.0.1", cls.port), 0.2).close(); break
            except OSError: time.sleep(0.1)
    @classmethod
    def tearDownClass(cls):
        cls.p.terminate(); cls.p.wait(5)
    def req(self, method, path, body=None):
        r = urllib.request.Request(f"http://127.0.0.1:{self.port}{path}", data=body, method=method)
        try:
            with urllib.request.urlopen(r, timeout=5) as resp: return resp.status, resp.headers.get("Content-Type", ""), resp.read()
        except urllib.error.HTTPError as e: return e.code, e.headers.get("Content-Type", ""), e.read()
    def test_crud(self):
        s, ct, b = self.req("PUT", "/kv/name", json.dumps({"value": {"n": [1, 2]}}).encode())
        self.assertEqual(s, 200); self.assertIn("application/json", ct); self.assertEqual(json.loads(b), {"key": "name", "value": {"n": [1, 2]}})
        s, ct, b = self.req("GET", "/kv/name"); self.assertEqual((s, json.loads(b)["value"]), (200, {"n": [1, 2]}))
        self.assertEqual(self.req("DELETE", "/kv/name")[0], 204); self.assertEqual(self.req("GET", "/kv/name")[0], 404); self.assertEqual(self.req("DELETE", "/kv/name")[0], 404)
    def test_bad(self):
        self.assertEqual(self.req("PUT", "/kv/x", b"not json")[0], 400); self.assertEqual(self.req("PUT", "/kv/x", b'{"v": 1}')[0], 400)
        self.assertEqual(self.req("GET", "/other")[0], 404); self.assertEqual(self.req("GET", "/kv/")[0], 404)
    def test_falsy_values(self):
        for v in (0, "", None, False):
            self.assertEqual(self.req("PUT", "/kv/f", json.dumps({"value": v}).encode())[0], 200); self.assertEqual(json.loads(self.req("GET", "/kv/f")[2])["value"], v)
'''})

SHIP_STARTER = {'shipping.py': '''def shipping_cost(weight_kg, zone, express, member, fragile):
    cost = 0
    if zone == "local":
        if weight_kg <= 1:
            cost = 20
        else:
            if weight_kg <= 5:
                cost = 35
            else:
                if weight_kg <= 20:
                    cost = 60
                else:
                    cost = 60 + (weight_kg - 20) * 3
    elif zone == "national":
        if weight_kg <= 1:
            cost = 40
        else:
            if weight_kg <= 5:
                cost = 70
            else:
                if weight_kg <= 20:
                    cost = 120
                else:
                    cost = 120 + (weight_kg - 20) * 5
    elif zone == "international":
        if weight_kg <= 1:
            cost = 150
        else:
            if weight_kg <= 5:
                cost = 260
            else:
                if weight_kg <= 20:
                    cost = 480
                else:
                    cost = 480 + (weight_kg - 20) * 12
    else:
        raise ValueError("unknown zone")
    if express:
        if zone == "international":
            cost = cost * 2
        else:
            cost = cost * 1.5
    if fragile:
        if weight_kg > 5:
            cost = cost + 25
        else:
            cost = cost + 10
    if member:
        if cost > 100:
            cost = cost * 0.9
        else:
            if cost > 50:
                cost = cost - 5
    return round(cost, 2)
''', 'test_shipping.py': '''import unittest
from shipping import shipping_cost


class T(unittest.TestCase):
    def test_some(self):
        self.assertEqual(shipping_cost(0.5, "local", False, False, False), 20)
        self.assertEqual(shipping_cost(10, "national", True, True, True), 184.5)
        self.assertEqual(shipping_cost(25, "international", False, False, False), 540)


if __name__ == "__main__":
    unittest.main()
'''}
task('py-refactor-shipping', 4, ['python', 'refactor'],
 'shipping_cost() in ~/proj/shipping.py works but is far too complex to maintain. Refactor it so every function stays under cyclomatic complexity 8 and 30 lines, without changing behaviour for any input (including errors). Do not edit test_shipping.py.',
 starter=SHIP_STARTER, protected=['test_shipping.py'], required=['shipping.py'], limits={'max_cc': 8, 'max_fn_lines': 30}, verify='python3 -m unittest -q test_shipping',
 ref={'shipping.py': '''RATES = {
    "local": (20, 35, 60, 3),
    "national": (40, 70, 120, 5),
    "international": (150, 260, 480, 12),
}


def _base(weight_kg, zone):
    if zone not in RATES:
        raise ValueError("unknown zone")
    small, medium, large, per_kg = RATES[zone]
    if weight_kg <= 1:
        return small
    if weight_kg <= 5:
        return medium
    return large + max(0, weight_kg - 20) * per_kg


def _member_discount(cost):
    if cost > 100:
        return cost * 0.9
    return cost - 5 if cost > 50 else cost


def shipping_cost(weight_kg, zone, express, member, fragile):
    cost = _base(weight_kg, zone)
    if express:
        cost *= 2 if zone == "international" else 1.5
    if fragile:
        cost += 25 if weight_kg > 5 else 10
    if member:
        cost = _member_discount(cost)
    return round(cost, 2)
'''},
 hidden={'_hidden/test_ship_equiv.py': HP + '''import itertools, importlib.util
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ORIG = """''' + SHIP_STARTER['shipping.py'].replace('\\', '\\\\') + '''"""
ns = {}; exec(ORIG, ns); orig = ns["shipping_cost"]
from shipping import shipping_cost
class T(unittest.TestCase):
    def test_equivalent(self):
        for w, z, e, m, f in itertools.product([0, 0.5, 1, 1.01, 3, 5, 5.5, 12, 20, 20.5, 33], ["local", "national", "international"], [0, 1], [0, 1], [0, 1]):
            self.assertEqual(shipping_cost(w, z, bool(e), bool(m), bool(f)), orig(w, z, bool(e), bool(m), bool(f)), (w, z, e, m, f))
    def test_error(self):
        with self.assertRaises(ValueError): shipping_cost(1, "mars", False, False, False)
'''})

task('js-expr-eval', 5, ['js', 'parsing'],
 'In ~/proj/calc.js export evaluate(expr) for arithmetic on numbers (ints and decimals) with + - * / and parentheses, normal precedence, left associativity, unary minus/plus (e.g. "-(2+3)*-2" = 10), and whitespace. Division by zero, empty input, unknown characters or unbalanced parentheses throw an Error. Do not use eval, Function or any library.',
 required=['calc.js'], test_cmd=JST, verify='node -e "console.log(require(\'./calc\').evaluate(\'-(2+3)*-2\'))"',
 ref={'calc.js': '''function tokenize(s) {
  const out = [], re = /\\s*(?:(\\d+(?:\\.\\d+)?|\\.\\d+)|(\\S))/gy;
  let m;
  while (re.lastIndex < s.length && (m = re.exec(s))) {
    if (m[1]) out.push(Number(m[1]));
    else if (m[2] && '+-*/()'.includes(m[2])) out.push(m[2]);
    else if (m[2]) throw new Error('unexpected character ' + m[2]);
  }
  return out;
}

function evaluate(expr) {
  const t = tokenize(String(expr).trim());
  let i = 0;
  const peek = () => t[i];
  function primary() {
    const x = t[i++];
    if (x === '+' || x === '-') { const v = primary(); return x === '-' ? -v : v; }
    if (x === '(') { const v = sum(); if (t[i++] !== ')') throw new Error('missing )'); return v; }
    if (typeof x === 'number') return x;
    throw new Error('unexpected ' + (x === undefined ? 'end' : x));
  }
  function product() {
    let v = primary();
    while (peek() === '*' || peek() === '/') {
      const op = t[i++], r = primary();
      if (op === '/' && r === 0) throw new Error('division by zero');
      v = op === '*' ? v * r : v / r;
    }
    return v;
  }
  function sum() {
    let v = product();
    while (peek() === '+' || peek() === '-') v = t[i++] === '+' ? v + product() : v - product();
    return v;
  }
  const v = sum();
  if (i !== t.length) throw new Error('unexpected ' + t[i]);
  return v;
}
module.exports = { evaluate };
'''},
 hidden={'_hidden/calc.test.js': '''const test = require('node:test'), assert = require('node:assert'), fs = require('node:fs'), path = require('node:path');
const { evaluate } = require('../calc.js');
test('values', () => { const c = { '1+2*3': 7, '(1+2)*3': 9, '10-4-3': 3, '8/4/2': 1, '-(2+3)*-2': 10, ' 2 * ( 3 + 4 ) ': 14, '1.5*2': 3, '-3': -3, '+4': 4, '2*-3': -6, '--2': 2, '((7))': 7, '.5+1': 1.5 };
  for (const [e, v] of Object.entries(c)) assert.strictEqual(evaluate(e), v, e); });
test('errors', () => { for (const e of ['', '1/0', '(1+2', '1+2)', '2^3', 'a+1', '1 2', '*3', '()']) assert.throws(() => evaluate(e), Error, e); });
test('no eval', () => { const s = fs.readFileSync(path.join(__dirname, '..', 'calc.js'), 'utf8'); assert.ok(!/\\beval\\s*\\(|new Function|Function\\s*\\(/.test(s)); });
'''})

task('py-meeting-slots', 4, ['python', 'ambiguous', 'algorithms'],
 'In ~/proj/slots.py write free_slots(busy, day_start, day_end, minutes) for finding meeting times. busy is a list of calendars, each a list of ("HH:MM", "HH:MM") busy ranges (may overlap, be unsorted, or extend outside the day). Return the list of ("HH:MM", "HH:MM") gaps inside the day when nobody is busy that are at least `minutes` long, in order. Bad times raise ValueError.',
 required=['slots.py'], verify='python3 -c "from slots import free_slots; print(free_slots([[(\'09:00\',\'10:00\')],[(\'09:30\',\'11:00\')]], \'08:00\', \'12:00\', 30))"',
 ref={'slots.py': '''import re

_HM = re.compile(r"^([01]\\d|2[0-3]):([0-5]\\d)$")


def _m(t):
    m = _HM.match(t) if isinstance(t, str) else None
    if not m:
        raise ValueError(f"bad time {t!r}")
    return int(m.group(1)) * 60 + int(m.group(2))


def _hm(x):
    return f"{x // 60:02d}:{x % 60:02d}"


def free_slots(busy, day_start, day_end, minutes):
    start, end = _m(day_start), _m(day_end)
    if start >= end or minutes <= 0:
        raise ValueError("bad day or duration")
    ranges = sorted((_m(a), _m(b)) for cal in busy for a, b in cal)
    out, cur = [], start
    for a, b in ranges:
        if a >= b:
            raise ValueError("busy range must end after it starts")
        stop = min(a, end)
        if stop - cur >= minutes:
            out.append((_hm(cur), _hm(stop)))
        cur = max(cur, b)
    if end - cur >= minutes:
        out.append((_hm(cur), _hm(end)))
    return out
'''},
 hidden={'_hidden/test_slots.py': HP + '''from slots import free_slots as f
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
'''})
