const { priceOf } = require('./prices');
const { applyDiscount } = require('./discount');

async function total(lines, code) {
  let sum = 0;
  for (const l of lines) {
    const p = await priceOf(l.sku);
    sum += p * l.qty;
  }
  return applyDiscount(sum, code);
}

function receipt(lines) {
  return lines.slice().sort((a, b) => a.sku.localeCompare(b.sku)).map(l => `${l.qty} x ${l.sku}`);
}
module.exports = { total, receipt };
