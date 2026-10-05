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

function has(re) {
  return re.test(renderer);
}

const target = {
  chromeClearcoat: num(contract, /CHROME_CLEAR_COAT\s*=\s*([0-9.]+)f/),
  chromeClearcoatRoughness: num(contract, /CHROME_CLEAR_COAT_ROUGHNESS\s*=\s*([0-9.]+)f/),
  chromeMetallic: num(contract, /CHROME_METALLIC\s*=\s*([0-9.]+)f/),
  chromeRoughness: num(contract, /CHROME_ROUGHNESS\s*=\s*([0-9.]+)f/),
  key: num(contract, /KEY_INTENSITY\s*=\s*([0-9.]+)f/),
  fill: num(contract, /FILL_INTENSITY\s*=\s*([0-9.]+)f/),
  rim: num(contract, /RIM_INTENSITY\s*=\s*([0-9.]+)f/),
  ambientIbl: num(contract, /AMBIENT_IBL_INTENSITY\s*=\s*([0-9.]+)f/),
  shadowBias: num(contract, /SHADOW_BIAS\s*=\s*([0-9.]+)f/),
  bloom: num(contract, /BLOOM\s*=\s*([0-9.]+)f/),
  ssao: num(contract, /SSAO\s*=\s*([0-9.]+)f/)
};

const actual = {
  chromeMetallic: num(renderer, /drawMesh\(roundedBox,die,floatArrayOf\(0\.98f,0\.99f,1\.0f,1f\),([0-9.]+)f,([0-9.]+)f/),
  chromeRoughness: num(renderer, /drawMesh\(roundedBox,die,floatArrayOf\(0\.98f,0\.99f,1\.0f,1f\),([0-9.]+)f/),
  chromeClearcoat: num(renderer, /uClearCoat,when\(material\)\{4->([0-9.]+)f/),
  chromeClearcoatRoughness: num(renderer, /uClearCoatRoughness,when\(material\)\{4->([0-9.]+)f/)
};

const postCall = renderer.match(
  /postShader\.draw\(sceneColorTex,sceneDepthTex,sceneWidth,sceneHeight,premiumPost\.ssao,premiumPost\.bloom\)/
);

const results = {
  chromeMetallic: actual.chromeMetallic !== null && Math.abs(actual.chromeMetallic - target.chromeMetallic) < 0.00001,
  chromeRoughness: actual.chromeRoughness !== null && Math.abs(actual.chromeRoughness - target.chromeRoughness) < 0.00001,
  chromeClearcoat: actual.chromeClearcoat !== null && Math.abs(actual.chromeClearcoat - target.chromeClearcoat) < 0.00001,
  chromeClearcoatRoughness: actual.chromeClearcoatRoughness !== null && Math.abs(actual.chromeClearcoatRoughness - target.chromeClearcoatRoughness) < 0.00001,

  lightingContractBound: has(/private val premiumLighting = PremiumMaterialLighting\.lighting/),
  keyBound: has(/glUniform1f\(uKeyIntensity,renderer\.premiumLighting\.keyIntensity\)/),
  fillBound: has(/glUniform1f\(uFillIntensity,renderer\.premiumLighting\.fillIntensity\)/),
  rimBound: has(/glUniform1f\(uRimIntensity,renderer\.premiumLighting\.rimIntensity\)/),
  iblBound: has(/glUniform1f\(uIBLIntensity,renderer\.premiumLighting\.iblIntensity\)/),
  shadowBiasBound: has(/glUniform1f\(uShadowBias,renderer\.premiumLighting\.shadowBias\)/),

  postContractBound: has(/private val premiumPost = PremiumMaterialLighting\.post/),
  postBound: Boolean(postCall),

  clearcoatUniformBound: has(/glUniform1f\(uClearCoat,/),
  clearcoatRoughnessUniformBound: has(/glUniform1f\(uClearCoatRoughness,/),
  gles2FallbackPresent: has(/class SafeSceneRenderer/)
};

const report = {
  mode: process.argv.includes("--strict") ? "strict" : "audit",
  sourceOfTruth: "PremiumMaterialLighting.Calibration",
  target,
  actual,
  results
};

console.log(JSON.stringify(report, null, 2));

if (process.argv.includes("--strict") && Object.values(results).some((v) => !v)) {
  process.exit(2);
}
