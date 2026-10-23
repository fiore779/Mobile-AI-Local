//@ timeout=10000
const t = (n, f) => { try { console.log("OK     ", n, "->", JSON.stringify(f())); } catch (e) { console.log("THROWN ", n, "->", e.name + ": " + e.message); } };
t("write", () => fs.writeFile("a.txt", "こんにちは"));
t("read", () => fs.readFile("a.txt"));
t("write subdir", () => fs.writeFile("sub/dir/b.txt", "x"));
t("list", () => fs.list("."));
t("list sub", () => fs.list("sub"));
t("exists", () => fs.exists("a.txt"));
t("read missing", () => fs.readFile("nope.txt"));
t("traversal ../", () => fs.readFile("../x"));
t("traversal sub/../../", () => fs.writeFile("sub/../../leak.txt", "x"));
t("absolute /etc/hosts", () => fs.readFile("/etc/hosts"));
t("absolute /data/data", () => fs.list("/data/data"));
t("big file 2MB", () => fs.writeFile("big.txt", "x".repeat(2000000)));
t("remove", () => fs.remove("a.txt"));
t("many files", () => { for (let i = 0; i < 150; i++) fs.writeFile("m/f" + i + ".txt", "x"); });
t("count after", () => fs.list("m").length);
for (let i = 0; i < 150; i++) { try { fs.remove("m/f" + i + ".txt"); } catch (e) {} }
