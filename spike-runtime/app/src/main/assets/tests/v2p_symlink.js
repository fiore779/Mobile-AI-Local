//@ timeout=5000
const t = (n, f) => { try { console.log("OK     ", n, "->", JSON.stringify(f())); } catch (e) { console.log("THROWN ", n, "->", e.name + ": " + e.message); } };
t("symlink dir list", () => fs.list("lnkdir"));
t("symlink dir read hosts", () => fs.readFile("lnkdir/hosts"));
t("symlink file read", () => fs.readFile("lnkfile"));
t("write via symlink dir", () => fs.writeFile("lnkdir/evil.txt", "x"));
t("remove via symlink file", () => fs.remove("lnkfile"));
