// Mini-bundler bez zależności: skleja moduły TS w kolejności zależności, zdejmuje typy
// (node:module.stripTypeScriptTypes) i wkleja wynik do szablonu HTML.
import { readFileSync, writeFileSync, mkdirSync } from "node:fs";
import { stripTypeScriptTypes } from "node:module";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = dirname(fileURLToPath(import.meta.url));
const src = (p) => readFileSync(join(root, "src", p), "utf8");
const IMPORT_RE = /^import\s+(?:type\s+)?[\s\S]*?\sfrom\s+["']\.\/([\w./-]+)["'];?\s*$/gm;

const seen = new Set();
const order = [];
const visit = (file) => {
  if (seen.has(file)) return;
  seen.add(file);
  const code = src(file);
  for (const m of code.matchAll(IMPORT_RE)) visit(m[1]);
  order.push(file);
};
visit("main.ts");

const bundled = order
  .map((f) => src(f).replace(IMPORT_RE, "").replace(/^export\s+(?=(const|let|function|interface|type|class)\b)/gm, ""))
  .join("\n");
if (/^\s*export\s/m.test(bundled)) throw new Error("Nieobsługiwany export w źródle");
const js = stripTypeScriptTypes(bundled, { mode: "strip" });

const html = readFileSync(join(root, "src", "index.html"), "utf8")
  .replace("/*CSS*/", () => {
    const dark = readFileSync(join(root, "src", "dark.css"), "utf8").trim();
    const darkBlocks = `@media (prefers-color-scheme:dark){:root:not([data-theme="light"]){${dark}}}\n:root[data-theme="dark"]{${dark}}`;
    return readFileSync(join(root, "src", "style.css"), "utf8").replace("/*DARK*/", () => darkBlocks);
  })
  .replace("/*JS*/", () => `(() => {\n"use strict";\n${js}\n})();`);
const out = resolve(root, "dist");
mkdirSync(out, { recursive: true });
writeFileSync(join(out, "cykl.html"), html);
console.log(`dist/cykl.html  ${(html.length / 1024).toFixed(1)} KB  (${order.length} modułów)`);
