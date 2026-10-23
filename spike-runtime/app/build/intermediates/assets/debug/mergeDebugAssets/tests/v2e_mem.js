//@ timeout=10000
console.log("alloc test");
try {
  const a = [];
  for (let i = 0; i < 1e9; i++) a.push("x".repeat(1000) + i);
  console.log("allocated", a.length);
} catch (e) { console.log("caught:", e.name, e.message); }
