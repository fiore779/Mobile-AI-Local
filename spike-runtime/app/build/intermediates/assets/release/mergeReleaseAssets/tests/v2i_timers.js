//@ timeout=10000
console.log("typeof setTimeout (our shim)", typeof setTimeout, typeof setInterval, typeof queueMicrotask);
setTimeout(() => console.log("timer 200ms fired (not awaited)"), 200);
const p = new Promise(r => setTimeout(() => r("promise-via-timeout"), 100));
console.log(await p);
async function work() { await sleep(300); console.log("async fn finished"); return 7; }
const r = await work();
console.log("result", r);
Promise.resolve().then(() => console.log("microtask ran"));
(async () => { await sleep(500); console.log("floating async at 500ms"); })();
console.log("end of script");
