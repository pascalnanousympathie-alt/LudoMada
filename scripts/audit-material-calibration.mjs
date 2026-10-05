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

function num(source, re) {
  const m = source.match(re);
  return m ? Number(m[1]) : null;
}

const target = {
  chromeClearcoat: num(material, /CHROME_CLEAR_COAT\s*=\s*([0-9.]+)f/),
  chromeClearcoatRoughness: num(material, /CHROME_CLEAR_COAT_ROUGHNESS\s*=\s*([0-9.]+)f/),
  chromeMetallic: num(material, /CHROME_METALLIC\s*=\s*([0-9.]+)f/),
  chromeRoughness: num(material, /CHROME_ROUGHNESS\s*=\s*([0-9.]+)f/),
  key: num(material, /KEY_INTENSITY\s*=\s*([0-9.]+)f/),
  fill: num(material, /FILL_INTENSITY\s*=\s*([0-9.]+)f/),
  rim: num(material, /RIM_INTENSITY\s*=\s*([0-9.]+)f/),
  ambientIbl: num(material, /AMBIENT_IBL_INTENSITY\s*=\s*([0-9.]+)f/),
  shadowBias: num(material, /SHADOW_BIAS\s*=\s*([0-9.]+)f/),
  bloom: num(material, /BLOOM\s*=\s*([0-9.]+)f/),
  ssao: num(material, /SSAO\s*=\s*([0-9.]+)f/)
};

const actual = {
  chromeClearcoat: num(renderer, /uClearCoat,when\(material\)\{4->([0-9.]+)f/),
  chromeClearcoatRoughness: num(renderer, /uClearCoatRoughness,when\(material\)\{4->([0-9.]+)f/),
  chromeMetallic: num(renderer, /drawMesh\(roundedBox,die,floatArrayOf\([^;]+?\),0\.055f,1\.0f/),
  key: num(renderer, /direct=\(kd\*albedo\/PI\+spec\)\*vec3\(([0-9.]+),0\.98,0\.84\)/),
  fill: num(renderer, /\*nl2\*([0-9.]+)f/),
  shadowBias: num(renderer, /p\.z-([0-9.]+),s=0\.0/),
  bloom: num(renderer, /postShader\.draw\(sceneColorTex,sceneDepthTex,sceneWidth,sceneHeight,([0-9.]+)f,([0-9.]+)f\)/),
  ssao: num(renderer, /postShader\.draw\(sceneColorTex,sceneDepthTex,sceneWidth,sceneHeight,([0-9.]+)f,([0-9.]+)f\)/),
  ambientIblShaderScalar: num(renderer, /envD\*albedo\*kd\*([0-9.]+)\+/),
  rimPresent: /rimIntensity|RIM_INTENSITY|rim contribution|Rim/.test(renderer)
};

const post = renderer.match(/postShader\.draw\(sceneColorTex,sceneDepthTex,sceneWidth,sceneHeight,([0-9.]+)f,([0-9.]+)f\)/);
if (post) {
  actual.ssao = Number(post[1]);
  actual.bloom = Number(post[2]);
}

function equal(a, b) {
  return a !== null && b !== null && Math.abs(a - b) <= 0.00001;
}

const results = {
  chromeClearcoat: equal(actual.chromeClearcoat, target.chromeClearcoat),
  chromeClearcoatRoughness: equal(actual.chromeClearcoatRoughness, target.chromeClearcoatRoughness),
  chromeMetallic: renderer.includes("0.055f,1.0f,1f,0.045f,4,false"),
  key: equal(actual.key, target.key),
  fill: equal(actual.fill, target.fill),
  shadowBias: equal(actual.shadowBias, target.shadowBias),
  bloom: equal(actual.bloom, target.bloom),
  ssao: equal(actual.ssao, target.ssao),
  ambientIbl: actual.ambientIblShaderScalar !== null &&
    Math.abs(actual.ambientIblShaderScalar - target.ambientIbl) <= 0.00001,
  rim: actual.rimPresent
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
