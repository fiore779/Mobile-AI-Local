//@ timeout=10000 repeat=2
console.log("leak before:", typeof globalThis.leak, "arr:", typeof Array.prototype.leakFn);
globalThis.leak = 123; Array.prototype.leakFn = () => 1; Math.PI = 3;
console.log("leak after set:", globalThis.leak, "fs file:", fs.exists("iso.txt"));
fs.writeFile("iso.txt", "persisted");
