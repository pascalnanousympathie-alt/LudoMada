#!/usr/bin/env node
import fs from "node:fs";
import path from "node:path";

const root = process.cwd();
const materialPath = path.join(root, "android-app/app/src/main/java/com/ludomadagascar/PremiumMaterialLighting.kt");

function fail(message) {
  console.error("PREMIUM_MATERIAL_CONTRACT_FAIL:", message);
  process.exit(1);
}

function read(file) {
  if (!fs.existsSync(file)) fail("missing " + path.relative(root, file));
  return fs.readFileSync(file, "utf8");
}

const kt = read(materialPath);
if (!/object PremiumMaterialLighting/.test(kt)) fail("missing PremiumMaterialLighting entry point");
if (!/fun hydroChrome\(\)\s*=\s*PremiumMaterial\([^\n]*0\.95f, 0\.08f/.test(kt)) {
  fail("hydroChrome must remain metallic=0.95 and roughness=0.08");
}
if (!/fun lacquer\(argb: Int\)\s*=\s*PremiumMaterial\([^\n]*0\.06f, 0\.23f/.test(kt)) {
  fail("pawn lacquer baseline changed unexpectedly");
}
if (!/fun mobilePremium\(\)\s*=\s*PremiumLightRig\(/.test(kt)) fail("missing mobilePremium light rig");
if (!/fun safeFallback\(\)\s*=\s*PremiumLightRig\(/.test(kt)) fail("missing safeFallback light rig");
if (!/fun mobileHigh\(\)\s*=\s*PremiumPostProcess\(/.test(kt)) fail("missing mobileHigh post tier");
if (!/fun mobileSafe\(\)\s*=\s*PremiumPostProcess\(/.test(kt)) fail("missing mobileSafe post tier");

const die = manifest.assets?.die;
if (!die) fail("manifest.assets.die missing");
if (die.material !== "hydro-chrome") fail("manifest die material must be hydro-chrome");
if (Number(die.metallic) !== 0.95) fail("manifest die metallic must be 0.95");
if (Number(die.roughness) !== 0.08) fail("manifest die roughness must be 0.08");

const pawn = manifest.assets?.pawn;
if (!pawn || pawn.material !== "lacquer-pbr") fail("manifest pawn material must be lacquer-pbr");

const checks = {
  hydroChrome: "0.95 / 0.08",
  pawnLacquer: "0.06 / 0.23",
  tiers: "mobilePremium + safeFallback + mobileHigh + mobileSafe"
};

console.log("PREMIUM_MATERIAL_CONTRACT_PASS", JSON.stringify(checks));
