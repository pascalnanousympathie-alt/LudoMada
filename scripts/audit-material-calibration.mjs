#!/usr/bin/env node
import fs from "node:fs";

const rendererFile = "android-app/app/src/main/java/com/ludomadagascar/LudoBoardView.kt";
const materialFile = "android-app/app/src/main/java/com/ludomadagascar/PremiumMaterialLighting.kt";

for (const file of [rendererFile, materialFile]) {
  if (!fs.existsSync(file)) {
    console.error("MATERIAL_CALIBRATION_AUDIT_FAIL missing", file);
    process.exit(1);
  }
}

const renderer = fs.readFileSync(rendererFile, "utf8");
const material = fs.readFileSync(materialFile, "utf8");

const checks = [
  ["chrome metallic source", renderer.includes("PremiumMaterialLighting.Calibration.CHROME_METALLIC")],
  ["chrome roughness source", renderer.includes("PremiumMaterialLighting.Calibration.CHROME_ROUGHNESS")],
  ["chrome clearcoat source", renderer.includes("PremiumMaterialLighting.Calibration.CHROME_CLEAR_COAT")],
  ["chrome clearcoat roughness source", renderer.includes("PremiumMaterialLighting.Calibration.CHROME_CLEAR_COAT_ROUGHNESS")],
  ["chrome normal source", renderer.includes("PremiumMaterialLighting.die().normalStrength")],
  ["key intensity source", renderer.includes("premiumLighting.keyIntensity") || renderer.includes("PremiumMaterialLighting.Calibration.KEY_INTENSITY")],
  ["fill intensity source", renderer.includes("premiumLighting.fillIntensity") || renderer.includes("PremiumMaterialLighting.Calibration.FILL_INTENSITY")],
  ["rim intensity source", renderer.includes("premiumLighting.rimIntensity") || renderer.includes("PremiumMaterialLighting.Calibration.RIM_INTENSITY")],
  ["IBL intensity source", renderer.includes("premiumLighting.iblIntensity") || renderer.includes("PremiumMaterialLighting.Calibration.AMBIENT_IBL_INTENSITY")],
  ["shadow bias source", renderer.includes("premiumLighting.shadowBias") || renderer.includes("PremiumMaterialLighting.Calibration.SHADOW_BIAS")],
  ["post SSAO source", renderer.includes("premiumPost.ssao")],
  ["post bloom source", renderer.includes("premiumPost.bloom")],
  ["shadow size source", renderer.includes("premiumLighting.shadowMapResolution")],
  ["pawn landing source", renderer.includes("PremiumVisualMotion.landingBounce")],
  ["die spin source", renderer.includes("PremiumVisualMotion.dieSpin")],
  ["volumetric die pip source", renderer.includes("drawMesh(pips,pipModel") && renderer.includes("Volumetric pip")],
  ["canonical chrome clearcoat value", /CHROME_CLEAR_COAT\s*=\s*0\.72f/.test(material)],
  ["canonical chrome roughness value", /CHROME_ROUGHNESS\s*=\s*0\.055f/.test(material)],
  ["canonical chrome normal value", /CHROME_NORMAL_STRENGTH\s*=\s*0\.045f/.test(material)],
  ["canonical key value", /KEY_INTENSITY\s*=\s*1\.08f/.test(material)],
  ["canonical fill value", /FILL_INTENSITY\s*=\s*0\.22f/.test(material)],
  ["canonical rim value", /RIM_INTENSITY\s*=\s*0\.34f/.test(material)],
  ["canonical IBL value", /AMBIENT_IBL_INTENSITY\s*=\s*0\.82f/.test(material)],
  ["canonical shadow value", /SHADOW_BIAS\s*=\s*0\.00135f/.test(material)],
  ["canonical bloom value", /BLOOM\s*=\s*0\.10f/.test(material)],
  ["canonical SSAO value", /SSAO\s*=\s*0\.28f/.test(material)]
];

const legacy = [
  ["legacy chrome clearcoat", /uClearCoat,when\(material\)\{4->0\.22f/],
  ["legacy chrome clearcoat roughness", /uClearCoatRoughness,when\(material\)\{4->0\.075f/],
  ["legacy chrome normal", /uNormalStrength,if\(material==4\)0\.08f/],
  ["legacy key literal", /vec3\(1\.05,0\.98,0\.84\)\*nl/],
  ["legacy fill literal", /nl2\*0\.42/],
  ["legacy shadow literal", /p\.z-0\.0018/]
];

for (const [name, re] of legacy) checks.push([name + " removed", !re.test(renderer)]);

const failed = checks.filter(([, pass]) => !pass);
console.log(JSON.stringify({
  mode: process.argv.includes("--strict") ? "strict" : "audit",
  passed: checks.length - failed.length,
  total: checks.length,
  failures: failed.map(([name]) => name)
}, null, 2));

if (process.argv.includes("--strict") && failed.length) process.exit(2);