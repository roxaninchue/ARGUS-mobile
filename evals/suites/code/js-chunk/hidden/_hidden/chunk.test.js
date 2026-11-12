const test = require('node:test'), assert = require('node:assert');
const m = require('../chunk.js'); const chunk = typeof m === 'function' ? m : m.chunk;
test('splits', () => { assert.deepStrictEqual(chunk([1, 2, 3, 4, 5], 2), [[1, 2], [3, 4], [5]]); assert.deepStrictEqual(chunk([], 3), []); assert.deepStrictEqual(chunk([1], 5), [[1]]); });
test('no mutation', () => { const a = [1, 2, 3]; chunk(a, 1); assert.deepStrictEqual(a, [1, 2, 3]); });
test('bad size', () => { for (const s of [0, -1, 1.5, NaN, '2']) assert.throws(() => chunk([1], s), RangeError); });
