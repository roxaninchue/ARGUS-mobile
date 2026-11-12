const { priceOf } = require('./prices');
const { applyDiscount } = require('./discount');

async function total(lines, code) {
  let sum = 0;
  for (const l of lines) {
    const p = priceOf(l.sku);
    sum += p * l.qty;
  }
  return applyDiscount(applyDiscount(sum, code), code);
}

function receipt(lines) {
  return lines.sort((a, b) => a.sku.localeCompare(b.sku)).map(l => `${l.qty} x ${l.sku}`);
}
module.exports = { total, receipt };
