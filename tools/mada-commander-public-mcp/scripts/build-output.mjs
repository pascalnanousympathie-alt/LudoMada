#!/usr/bin/env node
import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const OUTPUT = path.join(ROOT, "public");
const PAGES = ["index.html", "privacy.html", "support.html", "terms.html"];

await fs.mkdir(OUTPUT, { recursive: true });
for (const page of PAGES) {
  await fs.copyFile(path.join(ROOT, page), path.join(OUTPUT, page));
}
console.log(`Prepared ${PAGES.length} public HTML pages for the Vercel output directory.`);
