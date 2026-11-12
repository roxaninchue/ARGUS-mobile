const test = require('node:test'), assert = require('node:assert');
const { compare, sortVersions } = require('../semver.js');
test('compare', () => { assert.strictEqual(compare('1.10.0', '1.2.0'), 1); assert.strictEqual(compare('v1.0.0', '1.0.0'), 0); assert.strictEqual(compare('1.0.0-alpha', '1.0.0'), -1); assert.strictEqual(compare('1.0.0-alpha.2', '1.0.0-alpha.10'), -1); assert.strictEqual(compare('1.0.0-1', '1.0.0-alpha'), -1); assert.strictEqual(compare('1.0.0-alpha', '1.0.0-alpha.1'), -1); });
test('sort', () => { const l = ['1.0.0', '1.0.0-rc.1', '0.9.9', '1.0.0-beta', '1.0.0-alpha.beta', '1.0.0-alpha', '1.0.0-alpha.1', '1.0.0-beta.2', '1.0.0-beta.11']; const s = sortVersions(l);
  assert.deepStrictEqual(s, ['0.9.9', '1.0.0-alpha', '1.0.0-alpha.1', '1.0.0-alpha.beta', '1.0.0-beta', '1.0.0-beta.2', '1.0.0-beta.11', '1.0.0-rc.1', '1.0.0']); assert.strictEqual(l[0], '1.0.0'); });
test('invalid', () => { for (const v of ['1.0', '01.0.0', '1.0.0-', 'x', '', '1.0.0-a..b']) assert.throws(() => compare(v, '1.0.0'), TypeError); });
