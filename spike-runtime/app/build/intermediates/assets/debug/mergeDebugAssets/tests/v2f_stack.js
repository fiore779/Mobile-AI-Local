//@ timeout=10000
function r(n) { return r(n + 1) + 1; }
try { r(0); } catch (e) { console.log("caught:", e.name, e.message); }
const big = []; for (let i = 0; i < 100000; i++) console.log("line " + i);
