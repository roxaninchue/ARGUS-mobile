const test = require('node:test'), assert = require('node:assert'), { spawnSync } = require('node:child_process'), fs = require('node:fs'), os = require('node:os'), path = require('node:path');
const WC = path.join(__dirname, '..', 'wc.js');
const run = (...a) => spawnSync(process.execPath, [WC, ...a], { encoding: 'utf8' });
const tmp = (s) => { const p = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'wc-')), 'f.txt'); fs.writeFileSync(p, s); return p; };
test('plain', () => { const f = tmp('hello world\nsecond line here\nno newline'); const r = run(f); assert.strictEqual(r.status, 0); assert.strictEqual(r.stdout, `2 7 39 ${f}\n`); });
test('unicode bytes', () => { const f = tmp('héllo\n'); assert.strictEqual(run(f).stdout, `1 1 7 ${f}\n`); });
test('json', () => { const f = tmp('a b\n'); assert.deepStrictEqual(JSON.parse(run('--json', f).stdout), { lines: 1, words: 2, bytes: 4, file: f }); });
test('errors', () => { const r = run('/nope/x'); assert.strictEqual(r.status, 1); assert.ok(r.stderr.length); assert.strictEqual(r.stdout, ''); assert.strictEqual(run().status, 2); });
