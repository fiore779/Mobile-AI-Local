package com.mobileailocal.runner

/**
 * JS 側の初期化コード(console・タイマー)。`__log` と `__sleep` は Kotlin 側のホスト関数。
 * `__log` / `__sleep` は prelude のクロージャが使うので、prelude の後でも消さない。
 */
object Prelude {
    const val SOURCE = """
const __fmt = (v) => {
  if (typeof v === 'string') return v;
  if (typeof v === 'bigint') return v.toString() + 'n';
  if (typeof v === 'function') return String(v);
  if (typeof v === 'symbol') return v.toString();
  if (v instanceof Error) return v.stack || (v.name + ': ' + v.message);
  if (v && typeof v === 'object') { try { return JSON.stringify(v, (k, x) => typeof x === 'bigint' ? x.toString() + 'n' : x); } catch (e) { return String(v); } }
  return String(v);
};
const __out = (...a) => __log(a.map(__fmt).join(' '));
globalThis.console = { log: __out, info: __out, warn: __out, error: __out, debug: __out };
const __timers = new Map(); let __tid = 0;
globalThis.setTimeout = (fn, ms = 0, ...a) => { const id = ++__tid; __timers.set(id, 1); __sleep(ms).then(() => { if (__timers.delete(id)) fn(...a); }); return id; };
globalThis.setInterval = (fn, ms = 0, ...a) => { const id = ++__tid; __timers.set(id, 1); const tick = () => __sleep(Math.max(ms, 1)).then(() => { if (__timers.has(id)) { fn(...a); tick(); } }); tick(); return id; };
globalThis.clearTimeout = globalThis.clearInterval = (id) => { __timers.delete(id); };
globalThis.queueMicrotask = (fn) => Promise.resolve().then(fn);
globalThis.sleep = __sleep;
"""
}
