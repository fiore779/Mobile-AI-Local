//@ timeout=5000
console.log("typed array 500MB");
try { const b = new Uint8Array(500 * 1024 * 1024); console.log("allocated", b.length); } catch (e) { console.log("caught", e.name, e.message); }
try { let s = "x"; for (let i = 0; i < 40; i++) s += s; console.log("string len", s.length); } catch (e) { console.log("caught", e.name, e.message); }
