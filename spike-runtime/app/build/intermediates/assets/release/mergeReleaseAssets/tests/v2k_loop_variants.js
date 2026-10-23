//@ timeout=2000
console.log("tight loop in async fn");
(async () => { while (true) { } })();
await sleep(10);
