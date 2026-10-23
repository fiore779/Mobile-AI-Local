//@ timeout=5000
let n = 0;
const id = setInterval(() => { n++; console.log("tick", n); if (n === 3) clearInterval(id); }, 100);
await sleep(600);
console.log("done, n =", n);
console.log(typeof queueMicrotask, 2n ** 64n, [1, {a: 2}], new Error("e").message, undefined, null);
try { console.log("dyn import:", typeof (await import("fs"))); } catch (e) { console.log("dyn import fails:", e.message); }
