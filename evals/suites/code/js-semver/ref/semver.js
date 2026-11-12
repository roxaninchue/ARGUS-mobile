const RE = /^v?(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?$/;
function parse(v) {
  const m = RE.exec(String(v));
  if (!m) throw new TypeError('invalid version: ' + v);
  return { nums: m.slice(1, 4).map(Number), pre: m[4] ? m[4].split('.') : [] };
}
const cmp = (x, y) => (x < y ? -1 : x > y ? 1 : 0);
function cmpId(a, b) {
  const na = /^\d+$/.test(a), nb = /^\d+$/.test(b);
  if (na && nb) return cmp(Number(a), Number(b));
  if (na !== nb) return na ? -1 : 1;
  return cmp(a, b);
}
function comparePre(a, b) {
  if (!a.length || !b.length) return cmp(b.length, a.length);
  for (let i = 0; i < Math.min(a.length, b.length); i++) { const c = cmpId(a[i], b[i]); if (c) return c; }
  return cmp(a.length, b.length);
}
function compare(a, b) {
  const x = parse(a), y = parse(b);
  for (let i = 0; i < 3; i++) if (x.nums[i] !== y.nums[i]) return cmp(x.nums[i], y.nums[i]);
  return comparePre(x.pre, y.pre);
}
const sortVersions = list => list.slice().sort(compare);
module.exports = { compare, sortVersions };
