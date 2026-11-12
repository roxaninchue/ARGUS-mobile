const test = require('node:test'), assert = require('node:assert');
const { total, receipt } = require('../src/cart');
test('total', async () => { assert.strictEqual(await total([{ sku: 'tea', qty: 2 }, { sku: 'mug', qty: 1 }]), 2100); assert.strictEqual(await total([{ sku: 'mug', qty: 1 }], 'TEN'), 1080); assert.strictEqual(await total([]), 0); });
test('errors', async () => { await assert.rejects(total([{ sku: 'nope', qty: 1 }])); await assert.rejects(total([{ sku: 'tea', qty: 1 }], 'FREE')); });
test('receipt keeps input', () => { const l = [{ sku: 'tea', qty: 1 }, { sku: 'cake', qty: 2 }]; assert.deepStrictEqual(receipt(l), ['2 x cake', '1 x tea']); assert.strictEqual(l[0].sku, 'tea'); });
