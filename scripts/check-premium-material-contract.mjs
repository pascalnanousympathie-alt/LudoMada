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
  ["PremiumMaterialLighting entry point", /object PremiumMaterialLighting/],
  ["hydroChrome metallic=0.95 roughness=0.08",
    /fun hydroChrome\(\)\s*=\s*PremiumMaterial\([^\n]*0\.95f, 0\.08f/],
  ["pawn lacquer metallic=0.06 roughness=0.23",
    /fun lacquer\(argb: Int\)\s*=\s*PremiumMaterial\([^\n]*0\.06f, 0\.23f/],
  ["mobilePremium light rig", /fun mobilePremium\(\)\s*=\s*PremiumLightRig\(/],
  ["safeFallback light rig", /fun safeFallback\(\)\s*=\s*PremiumLightRig\(/],
  ["mobileHigh post tier", /fun mobileHigh\(\)\s*=\s*PremiumPostProcess\(/],
  ["mobileSafe post tier", /fun mobileSafe\(\)\s*=\s*PremiumPostProcess\(/]
];

for (const [label, pattern] of assertions) {
  if (!pattern.test(kt)) fail(label);
}

console.log("PREMIUM_MATERIAL_CONTRACT_PASS", JSON.stringify({
  hydroChrome: { metallic: 0.95, roughness: 0.08 },
  pawnLacquer: { metallic: 0.06, roughness: 0.23 },
  lightingTiers: ["mobilePremium", "safeFallback"],
  postTiers: ["mobileHigh", "mobileSafe"]
}));
