const test = require('node:test'), assert = require('node:assert'), fs = require('node:fs'), path = require('node:path');
const { Emitter } = require('../emitter.js');
test('order and args', () => { const e = new Emitter(), got = []; e.on('a', x => got.push(1 + x)); e.on('a', x => got.push(2 + x)); assert.strictEqual(e.emit('a', 10), 2); assert.deepStrictEqual(got, [11, 12]); assert.strictEqual(e.emit('none'), 0); });
test('unsubscribe and off', () => { const e = new Emitter(); let n = 0; const f = () => n++; const un = e.on('a', f); e.emit('a'); un(); e.emit('a'); e.on('a', f); e.off('a', f); e.emit('a'); assert.strictEqual(n, 1); });
test('once', () => { const e = new Emitter(); let n = 0; const f = () => n++; e.once('a', f); e.emit('a'); e.emit('a'); assert.strictEqual(n, 1); e.once('b', f); e.off('b', f); e.emit('b'); assert.strictEqual(n, 1); });
test('mutation during emit', () => { const e = new Emitter(), got = []; const b = () => got.push('b'); e.on('a', () => { got.push('a'); e.off('a', b); e.on('a', () => got.push('c')); }); e.on('a', b); e.emit('a'); assert.deepStrictEqual(got, ['a', 'b']); });
test('no node:events', () => { assert.ok(!/require\(['"](node:)?events['"]\)/.test(fs.readFileSync(path.join(__dirname, '..', 'emitter.js'), 'utf8'))); });
