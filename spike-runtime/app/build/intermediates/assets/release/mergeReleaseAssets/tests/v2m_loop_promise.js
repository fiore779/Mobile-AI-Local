//@ timeout=2000
console.log("microtask infinite chain");
const f = () => Promise.resolve().then(f); f();
await sleep(100);
