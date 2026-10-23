//@ timeout=2000
console.log("catastrophic regex");
/(a+)+$/.test("a".repeat(40) + "b");
