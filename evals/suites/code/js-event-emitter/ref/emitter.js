class Emitter {
  constructor() { this.map = new Map(); }
  on(event, fn) {
    if (typeof fn !== 'function') throw new TypeError('listener must be a function');
    if (!this.map.has(event)) this.map.set(event, []);
    this.map.get(event).push(fn);
    return () => this.off(event, fn);
  }
  off(event, fn) {
    const list = this.map.get(event);
    if (!list) return;
    const i = list.findIndex(l => l === fn || l.orig === fn);
    if (i >= 0) list.splice(i, 1);
  }
  once(event, fn) {
    const w = (...args) => { this.off(event, w); return fn(...args); };
    w.orig = fn;
    return this.on(event, w);
  }
  emit(event, ...args) {
    const list = (this.map.get(event) || []).slice();
    for (const fn of list) fn(...args);
    return list.length;
  }
}
module.exports = { Emitter };
