#!/usr/bin/env node
import fs from "node:fs";

const rendererFile = "android-app/app/src/main/java/com/ludomadagascar/LudoBoardView.kt";
const contractFile = "android-app/app/src/main/java/com/ludomadagascar/PremiumMaterialLighting.kt";

for (const file of [rendererFile, contractFile]) {
  if (!fs.existsSync(file)) {
    console.error("MATERIAL_CALIBRATION_AUDIT_FAIL missing", file);
    process.exit(1);
  }
}

const renderer = fs.readFileSync(rendererFile, "utf8");
const contract = fs.readFileSync(contractFile, "utf8");

function num(source, re) {
  const m = source.match(re);
  return m ? Number(m[1]) : null;
}

const target = {
  chromeMetallic: num(contract, /CHROME_METALLIC\s*=\s*([0-9.]+)f/),
  chromeRoughness: num(contract, /CHROME_ROUGHNESS\s*=\s*([0-9.]+)f/),
  chromeClearcoat: num(contract, /CHROME_CLEAR_COAT\s*=\s*([0-9.]+)f/),
  chromeClearcoatRoughness: num(contract, /CHROME_CLEAR_COAT_ROUGHNESS\s*=\s*([0-9.]+)f/),
  chromeNormalStrength: num(contract, /HYDRO_CHROME.*?normalStrength\s*=\s*([0-9.]+)f/s),
  key: num(contract, /KEY_INTENSITY\s*=\s*([0-9.]+)f/),
  fill: num(contract, /FILL_INTENSITY\s*=\s*([0-9.]+)f/),
  rim: num(contract, /RIM_INTENSITY\s*=\s*([0-9.]+)f/),
  ibl: num(contract, /AMBIENT_IBL_INTENSITY\s*=\s*([0-9.]+)f/),
  shadowBias: num(contract, /SHADOW_BIAS\s*=\s*([0-9.]+)f/),
  bloom: num(contract, /BLOOM\s*=\s*([0-9.]+)f/),
  ssao: num(contract, /SSAO\s*=\s*([0-9.]+)f/)
};

const dieCall = renderer.match(
  /drawMesh\(roundedBox,die,floatArrayOf\(0\.98f,0\.99f,1\.0f,1f\),([0-9.]+)f,([0-9.]+)f/
);

const actual = {
  chromeRoughness: dieCall ? Number(dieCall[1]) : null,
  chromeMetallic: dieCall ? Number(dieCall[2]) : null,
  chromeClearcoat: num(renderer, /uClearCoat,when\(material\)\{4->([0-9.]+)f/),
  chromeClearcoatRoughness: num(renderer, /uClearCoatRoughness,when\(material\)\{4->([0-9.]+)f/),
  chromeNormalStrength: num(renderer, /uNormalStrength,if\(material==4\)([0-9.]+)f/),
  lightingProfileBound: /private val premiumLighting = PremiumMaterialLighting\.lighting/.test(renderer),
  keyBound: /glUniform1f\(uKeyIntensity,renderer\.premiumLighting\.keyIntensity\)/.test(renderer),
  fillBound: /glUniform1f\(uFillIntensity,renderer\.premiumLighting\.fillIntensity\)/.test(renderer),
  rimBound: /glUniform1f\(uRimIntensity,renderer\.premiumLighting\.rimIntensity\)/.test(renderer),
  iblBound: /glUniform1f\(uIBLIntensity,renderer\.premiumLighting\.iblIntensity\)/.test(renderer),
  shadowBound: /glUniform1f\(uShadowBias,renderer\.premiumLighting\.shadowBias\)/.test(renderer),
  postBound: /premiumPost\.ssao,premiumPost\.bloom/.test(renderer),
  clearcoatUniformsBound: /glUniform1f\(uClearCoat,/.test(renderer) && /glUniform1f\(uClearCoatRoughness,/.test(renderer),
  gles2FallbackPresent: /class SafeSceneRenderer/.test(renderer)
};

const close = (a, b) => a !== null && b !== null && Math.abs(a - b) <= 0.00001;

const results = {
  chromeMetallic: close(actual.chromeMetallic, target.chromeMetallic),
  chromeRoughness: close(actual.chromeRoughness, target.chromeRoughness),
  chromeClearcoat: close(actual.chromeClearcoat, target.chromeClearcoat),
  chromeClearcoatRoughness: close(actual.chromeClearcoatRoughness, target.chromeClearcoatRoughness),
  chromeNormalStrength: close(actual.chromeNormalStrength, target.chromeNormalStrength),
  lightingProfileBound: actual.lightingProfileBound,
  keyBound: actual.keyBound,
  fillBound: actual.fillBound,
  rimBound: actual.rimBound,
  iblBound: actual.iblBound,
  shadowBound: actual.shadowBound,
  postBound: actual.postBound,
  clearcoatUniformsBound: actual.clearcoatUniformsBound,
  gles2FallbackPresent: actual.gles2FallbackPresent
};

const report = {
  mode: process.argv.includes("--strict") ? "strict" : "audit",
  sourceOfTruth: "PremiumMaterialLighting.Calibration + PremiumMaterialLighting.hydroChrome()",
  target,
  actual,
  results
};

console.log(JSON.stringify(report, null, 2));

if (process.argv.includes("--strict") && Object.values(results).some((v) => !v)) {
  process.exit(2);
}
