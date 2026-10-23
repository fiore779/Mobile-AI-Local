//@ timeout=10000
const names = ["require", "fetch", "XMLHttpRequest", "WebSocket", "process", "Buffer", "module", "exports", "setTimeout", "setInterval", "setImmediate", "queueMicrotask", "std", "os", "Deno", "window", "document", "navigator", "__dirname", "Java", "java", "android", "Android", "Function", "eval", "import", "performance", "crypto", "localStorage"];
for (const n of names) console.log(n, "->", typeof globalThis[n]);
try { console.log("dynamic import:", typeof (await import("fs"))); } catch (e) { console.log("dynamic import fs failed:", e.message); }
try { console.log(eval("1+1")); } catch (e) { console.log("eval failed", e.message); }
try { console.log(new Function("return 3")()); } catch (e) { console.log("Function failed", e.message); }
try { require("fs"); } catch (e) { console.log("require failed:", e.name, e.message); }
try { fetch("http://example.com"); } catch (e) { console.log("fetch failed:", e.name, e.message); }
