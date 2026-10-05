#!/usr/bin/env node
import fs from "node:fs";

const file = "android-app/app/src/main/java/com/ludomadagascar/LudoBoardView.kt";
if (!fs.existsSync(file)) {
  console.error("MATERIAL_CALIBRATION_AUDIT_FAIL missing", file);
  process.exit(1);
}
const src = fs.readFileSync(file, "utf8");

function readNumber(label, re) {
  const m = src.match(re);
  return m ? Number(m[1]) : null;
}

const actual = {
  chromeClearcoat: readNumber("chromeClearcoat", /uClearCoat,when\(material\)\{4->([0-9.]+)f/),
  chromeClearcoatRoughness: readNumber("chromeClearcoatRoughness", /uClearCoatRoughness,when\(material\)\{4->([0-9.]+)f/),
  key: readNumber("key", /vec3\(([0-9.]+),0\.98,0\.84\)/),
  fill: readNumber("fill", /nl2\*([0-9.]+)/),
  shadowBias: readNumber("shadowBias", /p\.z-([0-9.]+)/),
  bloom: readNumber("bloom", /postShader\.draw\(sceneColorTex,sceneDepthTex,sceneWidth,sceneHeight,([0-9.]+),([0-9.]+)\)/),
};

const target = {
  chromeClearcoat: 0.72,
  chromeClearcoatRoughness: 0.055,
  key: 1.08,
  fill: 0.22,
  shadowBias: 0.00135,
  bloom: 0.10,
  ssao: 0.28,
  ambientIbl: 0.82,
  rim: 0.34
};

const tolerance = 0.00001;
const compare = (name) => actual[name] !== null && Math.abs(actual[name] - target[name]) <= tolerance;

const results = {
  chromeClearcoat: compare("chromeClearcoat"),
  chromeClearcoatRoughness: compare("chromeClearcoatRoughness"),
  key: compare("key"),
  fill: compare("fill"),
  shadowBias: compare("shadowBias"),
  bloom: compare("bloom")
};

console.log(JSON.stringify({
  mode: process.argv.includes("--strict") ? "strict" : "audit",
  actual,
  target,
  expectedManualIntegration: {
    ambientIbl: target.ambientIbl,
    rim: target.rim,
    ssao: target.ssao
  },
  results
}, null, 2));

if (process.argv.includes("--strict") && Object.values(results).some((v) => !v)) {
  process.exit(2);
}
