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

function has(source, re) {
  return re.test(source);
}

function resolve(source, numericRe, referenceRe, targetValue) {
  const numeric = num(source, numericRe);
  if (numeric !== null) return numeric;
  if (referenceRe.test(source)) return targetValue;
  return null;
}

const target = {
  chromeMetallic: num(contract, /CHROME_METALLIC\s*=\s*([0-9.]+)f/),
  chromeRoughness: num(contract, /CHROME_ROUGHNESS\s*=\s*([0-9.]+)f/),
  chromeClearcoat: num(contract, /CHROME_CLEAR_COAT\s*=\s*([0-9.]+)f/),
  chromeClearcoatRoughness: num(contract, /CHROME_CLEAR_COAT_ROUGHNESS\s*=\s*([0-9.]+)f/),
  chromeNormalStrength: num(contract, /fun hydroChrome\([\s\S]*?normalStrength\s*=\s*([0-9.]+)f/),
  key: num(contract, /KEY_INTENSITY\s*=\s*([0-9.]+)f/),
  fill: num(contract, /FILL_INTENSITY\s*=\s*([0-9.]+)f/),
  rim: num(contract, /RIM_INTENSITY\s*=\s*([0-9.]+)f/),
  bloom: num(contract, /BLOOM\s*=\s*([0-9.]+)f/),
  ssao: num(contract, /SSAO\s*=\s*([0-9.]+)f/)
};

const actual = {
  chromeMetallic: num(
    renderer,
    /drawMesh\(roundedBox,die,floatArrayOf\(0\.98f,0\.99f,1\.0f,1f\),([0-9.]+)f,([0-9.]+)f/
  ),
  chromeRoughness: null,
  chromeClearcoat: resolve(
    renderer,
    /uClearCoat,when\(material\)\{4->([0-9.]+)f/,
    /uClearCoat,when\(material\)\{4->PremiumMaterialLighting\.Calibration\.CHROME_CLEAR_COAT/,
    target.chromeClearcoat
  ),
  chromeClearcoatRoughness: resolve(
    renderer,
    /uClearCoatRoughness,when\(material\)\{4->([0-9.]+)f/,
    /uClearCoatRoughness,when\(material\)\{4->PremiumMaterialLighting\.Calibration\.CHROME_CLEAR_COAT_ROUGHNESS/,
    target.chromeClearcoatRoughness
  ),
  chromeNormalStrength: resolve(
    renderer,
    /uNormalStrength,if\(material==4\)([0-9.]+)f/,
    /uNormalStrength,if\(material==4\)PremiumMaterialLighting\.die\(\)\.normalStrength/,
    target.chromeNormalStrength
  ),
  keyBound: has(renderer, /glUniform1f\(uKeyIntensity,renderer\.premiumLighting\.keyIntensity\)/),
  fillBound: has(renderer, /glUniform1f\(uFillIntensity,renderer\.premiumLighting\.fillIntensity\)/),
  rimBound: has(renderer, /glUniform1f\(uRimIntensity,renderer\.premiumLighting\.rimIntensity\)/),
  iblBound: has(renderer, /glUniform1f\(uIBLIntensity,renderer\.premiumLighting\.iblIntensity\)/),
  shadowBound: has(renderer, /glUniform1f\(uShadowBias,renderer\.premiumLighting\.shadowBias\)/),
  postBound: has(renderer, /premiumPost\.ssao,premiumPost\.bloom/),
  lightingProfileBound: has(renderer, /private val premiumLighting = PremiumMaterialLighting\.lighting/),
  clearcoatUniformsBound:
    has(renderer, /glUniform1f\(uClearCoat,/) &&
    has(renderer, /glUniform1f\(uClearCoatRoughness,/),
  gles2FallbackPresent: has(renderer, /class SafeSceneRenderer/)
};

actual.chromeRoughness = actual.chromeMetallic
  ? num(
      renderer,
      /drawMesh\(roundedBox,die,floatArrayOf\(0\.98f,0\.99f,1\.0f,1f\),([0-9.]+)f,[0-9.]+f/
    )
  : null;

const close = (a, b) => a !== null && b !== null && Math.abs(a - b) <= 0.00001;

const results = {
  chromeMetallic: close(actual.chromeMetallic, target.chromeMetallic),
  chromeRoughness: close(actual.chromeRoughness, target.chromeRoughness),
  chromeClearcoat: close(actual.chromeClearcoat, target.chromeClearcoat),
  chromeClearcoatRoughness: close(
    actual.chromeClearcoatRoughness,
    target.chromeClearcoatRoughness
  ),
  chromeNormalStrength: close(
    actual.chromeNormalStrength,
    target.chromeNormalStrength
  ),
  keyBound: actual.keyBound,
  fillBound: actual.fillBound,
  rimBound: actual.rimBound,
  iblBound: actual.iblBound,
  shadowBound: actual.shadowBound,
  postBound: actual.postBound,
  lightingProfileBound: actual.lightingProfileBound,
  clearcoatUniformsBound: actual.clearcoatUniformsBound,
  gles2FallbackPresent: actual.gles2FallbackPresent,

  // Legacy inline values must not remain in the active GLES3 material binding.
  legacyClearcoatRemoved: !/4->0\.22f/.test(renderer),
  legacyClearcoatRoughnessRemoved: !/4->0\.075f/.test(renderer),
  legacyNormalStrengthRemoved: !/if\(material==4\)0\.08f/.test(renderer)
};

const report = {
  mode: process.argv.includes("--strict") ? "strict" : "audit",
  sourceOfTruth:
    "PremiumMaterialLighting.Calibration + PremiumMaterialLighting.hydroChrome()",
  target,
  actual,
  results
};

console.log(JSON.stringify(report, null, 2));

if (process.argv.includes("--strict") && Object.values(results).some((v) => !v)) {
  process.exit(2);
}
