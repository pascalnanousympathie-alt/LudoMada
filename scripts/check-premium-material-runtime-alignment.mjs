#!/usr/bin/env node
import fs from "node:fs";
import path from "node:path";

const root = process.cwd();
const materialFile = path.join(root, "android-app/app/src/main/java/com/ludomadagascar/PremiumMaterialLighting.kt");
const rendererFile = path.join(root, "android-app/app/src/main/java/com/ludomadagascar/LudoBoardView.kt");

function fail(message) {
  console.error("PREMIUM_MATERIAL_RUNTIME_ALIGNMENT_FAIL:", message);
  process.exit(1);
}
function read(file) {
  if (!fs.existsSync(file)) fail("missing " + path.relative(root, file));
  return fs.readFileSync(file, "utf8");
}

const material = read(materialFile);
const renderer = read(rendererFile);

const materialAssertions = [
  ["PremiumMaterial entry point", /data class PremiumMaterial/],
  ["hydro chrome metallic", /hydroChrome\([\s\S]*?metallic = 0\.95f/],
  ["hydro chrome roughness", /hydroChrome\([\s\S]*?roughness = 0\.08f/],
  ["hydro chrome clear coat", /hydroChrome\([\s\S]*?clearCoat = 0\.34f/],
  ["pawn lacquer metallic", /lacquer\(argb: Int\)[\s\S]*?metallic = 0\.06f/],
  ["pawn lacquer roughness", /lacquer\(argb: Int\)[\s\S]*?roughness = 0\.23f/],
  ["pawn clear coat", /lacquer\(argb: Int\)[\s\S]*?clearCoat = 0\.22f/],
  ["premium lighting rig", /fun mobilePremium\(\) = PremiumLightRig/],
  ["safe lighting rig", /fun safeFallback\(\) = PremiumLightRig/],
  ["high post process", /fun mobileHigh\(\) = PremiumPostProcess/],
  ["safe post process", /fun mobileSafe\(\) = PremiumPostProcess/],
  ["reflection helper", /fun reflectionStrength\(/]
];
for (const [label, pattern] of materialAssertions) {
  if (!pattern.test(material)) fail(label);
}

const rendererAssertions = [
  ["GLES3 PBR shader", /class PbrShader\(/],
  ["clear coat uniform", /uClearCoat/],
  ["clear coat roughness uniform", /uClearCoatRoughness/],
  ["clear coat binding", /glUniform1f\(uClearCoat,/],
  ["clear coat roughness binding", /glUniform1f\(uClearCoatRoughness,/],
  ["secondary cool fill light", /vec3 L2=normalize\(/],
  ["IBL cubemap", /samplerCube uEnvCube/],
  ["shadow map", /sampler2D uAlbedoTex,uNormalTex,uShadowMap/],
  ["post-process AO/bloom", /uSsao,uBloom/],
  ["GLES2 fallback", /SafeSceneRenderer/],
  ["runtime GLES selection", /setEGLContextClientVersion\(if \(gles3\) 3 else 2\)/]
];
for (const [label, pattern] of rendererAssertions) {
  if (!pattern.test(renderer)) fail(label);
}

console.log("PREMIUM_MATERIAL_RUNTIME_ALIGNMENT_PASS", JSON.stringify({
  material: "PremiumMaterialLighting.kt",
  renderer: "LudoBoardView.kt",
  verified: [
    "PBR material targets",
    "clear-coat controls",
    "secondary fill light",
    "IBL",
    "shadow map",
    "SSAO/bloom post-process",
    "GLES2 fallback"
  ]
}));
