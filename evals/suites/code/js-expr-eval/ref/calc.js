function tokenize(s) {
  const out = [], re = /\s*(?:(\d+(?:\.\d+)?|\.\d+)|(\S))/gy;
  let m;
  while (re.lastIndex < s.length && (m = re.exec(s))) {
    if (m[1]) out.push(Number(m[1]));
    else if (m[2] && '+-*/()'.includes(m[2])) out.push(m[2]);
    else if (m[2]) throw new Error('unexpected character ' + m[2]);
  }
  return out;
}

function evaluate(expr) {
  const t = tokenize(String(expr).trim());
  let i = 0;
  const peek = () => t[i];
  function primary() {
    const x = t[i++];
    if (x === '+' || x === '-') { const v = primary(); return x === '-' ? -v : v; }
    if (x === '(') { const v = sum(); if (t[i++] !== ')') throw new Error('missing )'); return v; }
    if (typeof x === 'number') return x;
    throw new Error('unexpected ' + (x === undefined ? 'end' : x));
  }
  function product() {
    let v = primary();
    while (peek() === '*' || peek() === '/') {
      const op = t[i++], r = primary();
      if (op === '/' && r === 0) throw new Error('division by zero');
      v = op === '*' ? v * r : v / r;
    }
    return v;
  }
  function sum() {
    let v = product();
    while (peek() === '+' || peek() === '-') v = t[i++] === '+' ? v + product() : v - product();
    return v;
  }
  const v = sum();
  if (i !== t.length) throw new Error('unexpected ' + t[i]);
  return v;
}
module.exports = { evaluate };
