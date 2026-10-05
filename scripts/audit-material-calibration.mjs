#!/usr/bin/env node
import fs from "node:fs";

const file = "android-app/app/src/main/java/com/ludomadagascar/LudoBoardView.kt";
if (!fs.existsSync(file)) {
  console.error("MATERIAL_CALIBRATION_AUDIT_FAIL missing", file);
  process.exit(1);
}
const src = fs.readFileSync(file, "utf8");

const checks = [
  ["hydro clearcoat", /uClearCoat,when\(material\)\{4->0\.72f/],
  ["hydro clearcoat roughness", /uClearCoatRoughness,when\(material\)\{4->0\.055f/],
  ["key intensity", /vec3\(1\.08[ f]?[,)]/],
  ["secondary fill", /\)\*nl2\*0\.22f/],
  ["shadow bias", /p\.z-0\.00135/],
];

const results = checks.map(([name, re]) => ({ name, pass: re.test(src) }));
console.log(JSON.stringify({
  mode: process.argv.includes("--strict") ? "strict" : "audit",
  expected: {
    clearcoat: 0.72,
    clearcoatRoughness: 0.055,
    key: 1.08,
    fill: 0.22,
    ambientIbl: 0.82,
    shadowBias: 0.00135,
    rim: 0.34
  },
  results
}, null, 2));

if (process.argv.includes("--strict") && results.some(x => !x.pass)) process.exit(2);
