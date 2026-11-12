const test = require('node:test'), assert = require('node:assert');
const m = require('../debounce.js'); const debounce = typeof m === 'function' ? m : m.debounce;
test('trailing, latest args and this', (t) => { t.mock.timers.enable({ apis: ['setTimeout'] }); const calls = []; const o = { v: 7, f: debounce(function (x) { calls.push([this.v, x]); }, 100) };
  o.f(1); t.mock.timers.tick(50); o.f(2); t.mock.timers.tick(99); assert.deepStrictEqual(calls, []); t.mock.timers.tick(1); assert.deepStrictEqual(calls, [[7, 2]]); t.mock.timers.tick(500); assert.strictEqual(calls.length, 1); });
test('cancel and flush', (t) => { t.mock.timers.enable({ apis: ['setTimeout'] }); let n = 0; const f = debounce(x => { n += x; return n; }, 100);
  f(5); f.cancel(); t.mock.timers.tick(200); assert.strictEqual(n, 0); f(3); assert.strictEqual(f.flush(), 3); t.mock.timers.tick(200); assert.strictEqual(n, 3); assert.strictEqual(f.flush(), undefined); });
