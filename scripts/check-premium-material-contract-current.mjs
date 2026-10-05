#!/usr/bin/env node
import fs from "node:fs";
import path from "node:path";

const root = process.cwd();
const materialPath = path.join(
  root,
  "android-app/app/src/main/java/com/ludomadagascar/PremiumMaterialLighting.kt"
);

function fail(message) {
  console.error("PREMIUM_MATERIAL_CONTRACT_FAIL:", message);
  process.exit(1);
}

if (!fs.existsSync(materialPath)) fail("missing PremiumMaterialLighting.kt");

const kt = fs.readFileSync(materialPath, "utf8");

const assertions = [
  ["entry point", /object PremiumMaterialLighting/],
  ["hydro chrome 0.95 / 0.08", /hydroChrome\(\)[\s\S]*?metallic = 0\.95f,[\s\S]*?roughness = 0\.08f/],
  ["hydro chrome clear coat", /hydroChrome\(\)[\s\S]*?clearCoat = 0\.34f,[\s\S]*?clearCoatRoughness = 0\.060f/],
  ["pawn lacquer 0.06 / 0.23", /lacquer\(argb: Int\)[\s\S]*?metallic = 0\.06f,[\s\S]*?roughness = 0\.23f/],
  ["pawn clear coat", /lacquer\(argb: Int\)[\s\S]*?clearCoat = 0\.22f/],
  ["premium light rig", /fun mobilePremium\(\) = PremiumLightRig/],
  ["safe light rig", /fun safeFallback\(\) = PremiumLightRig/],
  ["high post tier", /fun mobileHigh\(\) = PremiumPostProcess/],
  ["safe post tier", /fun mobileSafe\(\) = PremiumPostProcess/],
  ["reflection helper", /fun reflectionStrength\(/]
];

for (const [label, pattern] of assertions) {
  if (!pattern.test(kt)) fail(label);
}

console.log("PREMIUM_MATERIAL_CONTRACT_PASS");
