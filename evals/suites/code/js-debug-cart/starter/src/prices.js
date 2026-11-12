// pretend this is a remote price service
async function priceOf(sku) {
  await new Promise(r => setTimeout(r, 5));
  const table = { tea: 450, mug: 1200, cake: 300 };
  if (!(sku in table)) throw new Error('unknown sku ' + sku);
  return table[sku];
}
module.exports = { priceOf };
