const CODES = { TEN: 0.1, HALF: 0.5 };
function applyDiscount(cents, code) {
  if (!code) return cents;
  if (!(code in CODES)) throw new Error('bad code ' + code);
  return Math.round(cents * (1 - CODES[code]));
}
module.exports = { applyDiscount };
