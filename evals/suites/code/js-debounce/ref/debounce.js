function debounce(fn, ms) {
  let timer = null, pending = null;
  function run() {
    timer = null;
    const p = pending; pending = null;
    return fn.apply(p.self, p.args);
  }
  function debounced(...args) {
    pending = { self: this, args };
    if (timer) clearTimeout(timer);
    timer = setTimeout(run, ms);
  }
  debounced.cancel = () => { if (timer) clearTimeout(timer); timer = null; pending = null; };
  debounced.flush = () => {
    if (!pending) return undefined;
    clearTimeout(timer);
    return run();
  };
  return debounced;
}
module.exports = debounce;
module.exports.debounce = debounce;
