const test = require('node:test'), assert = require('node:assert'), fs = require('node:fs'), path = require('node:path');
const { evaluate } = require('../calc.js');
test('values', () => { const c = { '1+2*3': 7, '(1+2)*3': 9, '10-4-3': 3, '8/4/2': 1, '-(2+3)*-2': 10, ' 2 * ( 3 + 4 ) ': 14, '1.5*2': 3, '-3': -3, '+4': 4, '2*-3': -6, '--2': 2, '((7))': 7, '.5+1': 1.5 };
  for (const [e, v] of Object.entries(c)) assert.strictEqual(evaluate(e), v, e); });
test('errors', () => { for (const e of ['', '1/0', '(1+2', '1+2)', '2^3', 'a+1', '1 2', '*3', '()']) assert.throws(() => evaluate(e), Error, e); });
test('no eval', () => { const s = fs.readFileSync(path.join(__dirname, '..', 'calc.js'), 'utf8'); assert.ok(!/\beval\s*\(|new Function|Function\s*\(/.test(s)); });
