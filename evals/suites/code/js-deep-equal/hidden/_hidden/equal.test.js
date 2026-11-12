const test = require('node:test'), assert = require('node:assert');
const m = require('../equal.js'); const eq = typeof m === 'function' ? m : m.deepEqual;
test('basics', () => { assert.ok(eq(NaN, NaN)); assert.ok(eq({ a: 1, b: [1, 2] }, { b: [1, 2], a: 1 })); assert.ok(!eq([1], { 0: 1 })); assert.ok(!eq(1, '1')); assert.ok(!eq(null, {})); assert.ok(!eq({ a: undefined }, { b: undefined })); });
test('types', () => { assert.ok(eq(new Date(5), new Date(5))); assert.ok(!eq(new Date(5), new Date(6))); assert.ok(eq(new Map([[1, { x: 1 }]]), new Map([[1, { x: 1 }]]))); assert.ok(!eq(new Map([[1, 2]]), new Map([[1, 3]]))); assert.ok(eq(new Set([1, 2]), new Set([2, 1]))); assert.ok(!eq(new Set([1]), new Set([2]))); });
test('cycles', () => { const a = { n: 1 }; a.self = a; const b = { n: 1 }; b.self = b; assert.ok(eq(a, b)); const c = { n: 2 }; c.self = c; assert.ok(!eq(a, c)); });
