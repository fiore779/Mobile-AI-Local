//@ timeout=10000
console.log("hello js");
console.log(JSON.stringify({a: [1, 2, 3], b: {c: "日本語"}}));
console.log(Math.max(1, 5, 3), Math.PI.toFixed(4));
console.log(new Date(Date.UTC(2026, 0, 2, 3, 4, 5)).toISOString());
console.log([3, 1, 2].sort().map(x => x * 2).join(","));
console.log("regex", "a1b22c333".match(/\d+/g));
console.log(new Map([[1, 2]]).size, new Set([1, 1, 2]).size);
class A { constructor() { this.x = 1; } get y() { return this.x + 1; } }
console.log(new A().y);
const f = async () => { await null; return 42; };
console.log("typeof Promise", typeof Promise, "BigInt", 2n ** 64n);
console.log("typeof structuredClone", typeof structuredClone, "Intl", typeof Intl, "TextEncoder", typeof TextEncoder);
console.log("toLocaleString", (1234567.891).toLocaleString());
console.log("globalThis keys:", Object.getOwnPropertyNames(globalThis).filter(k => /^[a-z]/.test(k)).join(","));
console.warn("warn line"); console.error("error line");
let r = 0; for (let i = 0; i < 3000000; i++) r += i % 7;
console.log("loop result", r);
await f();
"last-expression-value"
