const test = require('node:test'), assert = require('node:assert');
const { applyDiscount } = require('../src/discount');
test('discount', () => { assert.strictEqual(applyDiscount(1000, 'TEN'), 900); assert.strictEqual(applyDiscount(1000), 1000); });
