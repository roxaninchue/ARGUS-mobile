function deepEqual(a, b, seen = new Map()) {
  if (Object.is(a, b)) return true;
  if (typeof a !== 'object' || typeof b !== 'object' || a === null || b === null) return false;
  if (Object.getPrototypeOf(a) !== Object.getPrototypeOf(b)) return false;
  if (seen.get(a) === b) return true;
  seen.set(a, b);
  if (a instanceof Date) return a.getTime() === b.getTime();
  if (a instanceof Map) return mapsEqual(a, b, seen);
  if (a instanceof Set) return a.size === b.size && [...a].every(x => [...b].some(y => deepEqual(x, y, seen)));
  const ka = Object.keys(a), kb = Object.keys(b);
  if (ka.length !== kb.length) return false;
  return ka.every(k => Object.prototype.hasOwnProperty.call(b, k) && deepEqual(a[k], b[k], seen));
}
function mapsEqual(a, b, seen) {
  if (a.size !== b.size) return false;
  for (const [k, v] of a) if (!b.has(k) || !deepEqual(v, b.get(k), seen)) return false;
  return true;
}
module.exports = deepEqual;
module.exports.deepEqual = deepEqual;
