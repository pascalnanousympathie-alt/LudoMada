#!/usr/bin/env node
import fs from "node:fs";
import path from "node:path";

const root = process.argv[2] || "android-app/app/src/main/assets/3d";
const manifestPath = path.join(root, "manifest.json");
const budgetPath = path.join(root, "optimization-budget.json");

function fail(code, ...parts) {
  console.error(code, ...parts);
  process.exit(1);
}

if (!fs.existsSync(manifestPath)) fail("3D_OPT_MANIFEST_MISSING", manifestPath);
if (!fs.existsSync(budgetPath)) fail("3D_OPT_BUDGET_MISSING", budgetPath);

const manifest = JSON.parse(fs.readFileSync(manifestPath, "utf8"));
const budget = JSON.parse(fs.readFileSync(budgetPath, "utf8"));

if (manifest.schema !== "ludomada-premium-3d-v2") fail("3D_OPT_MANIFEST_SCHEMA_FAIL", manifest.schema);
if (manifest.units !== "meters") fail("3D_OPT_UNITS_FAIL", manifest.units);
if (manifest.format !== "glb/gltf2") fail("3D_OPT_FORMAT_FAIL", manifest.format);
if (manifest.target !== "android-realtime") fail("3D_OPT_TARGET_FAIL", manifest.target);

const sourceScene = manifest.source_scene ?? {};
const budgetScene = budget.sourceScene ?? {};
const metadataPairs = [
  ["provider", budgetScene.provider, sourceScene.provider],
  ["projectId", budgetScene.projectId, sourceScene.project_id],
  ["revision", budgetScene.revision, sourceScene.revision],
  ["sourceGlbBytes", budgetScene.sourceGlbBytes, sourceScene.glb_bytes],
  ["materialCalibration", budgetScene.materialCalibration, sourceScene.material_calibration]
];
for (const [field, actual, expected] of metadataPairs) {
  if (actual !== expected) fail("3D_OPT_SOURCE_METADATA_MISMATCH", field, actual, expected);
}

const heroMax = Number(budget.glbBytes?.heroMax);
if (!Number.isFinite(heroMax) || heroMax <= 0) fail("3D_OPT_HERO_BUDGET_INVALID", heroMax);
if (heroMax !== manifest.mobile_budgets?.hero_glb_max_bytes) {
  fail("3D_OPT_HERO_BUDGET_MISMATCH", heroMax, manifest.mobile_budgets?.hero_glb_max_bytes);
}
const sourceBytes = Number(sourceScene.glb_bytes);
if (!Number.isFinite(sourceBytes)) fail("3D_OPT_SOURCE_BYTES_MISSING");
if (sourceBytes > heroMax) fail("3D_OPT_SOURCE_BUDGET_FAIL", sourceBytes, heroMax);

const bundled = fs.readdirSync(root)
  .filter((name) => /\.glb$/i.test(name))
  .map((name) => {
    const bytes = fs.statSync(path.join(root, name)).size;
    if (bytes > heroMax) fail("3D_OPT_BUNDLED_GLB_TOO_LARGE", name, bytes, heroMax);
    return { name, bytes };
  });


const homeEnvironment = manifest.home_environment ?? {};
const homeBudget = budget.homeEnvironment ?? {};
if (homeEnvironment.renderer !== "LudoBoardView.SceneRenderer.homePreview") {
  fail("3D_OPT_HOME_RENDERER_MISSING", homeEnvironment.renderer);
}
if (!homeEnvironment.scene_glb || !homeEnvironment.baobab_glb ||
    !Array.isArray(homeEnvironment.textures) || homeEnvironment.textures.length !== 10) {
  fail("3D_OPT_HOME_ASSET_INVENTORY_MISSING");
}
if (homeEnvironment.textures_max_dimension !== homeBudget.textureDimensionMax) {
  fail("3D_OPT_HOME_TEXTURE_DIMENSION_MISMATCH", homeEnvironment.textures_max_dimension, homeBudget.textureDimensionMax);
}
if (Number(homeEnvironment.scene_glb.bytes) > Number(homeBudget.environmentGlbMaxBytes)) {
  fail("3D_OPT_HOME_ENVIRONMENT_GLB_BUDGET_FAIL", homeEnvironment.scene_glb.bytes, homeBudget.environmentGlbMaxBytes);
}
if (Number(homeEnvironment.baobab_glb.bytes) > Number(homeBudget.baobabGlbMaxBytes)) {
  fail("3D_OPT_HOME_BAOBAB_GLB_BUDGET_FAIL", homeEnvironment.baobab_glb.bytes, homeBudget.baobabGlbMaxBytes);
}
const homeTextureBytes = homeEnvironment.textures.reduce((sum, asset) => sum + Number(asset.bytes || 0), 0);
if (!Number.isFinite(Number(homeBudget.textureBytesMax)) || homeTextureBytes > Number(homeBudget.textureBytesMax)) {
  fail("3D_OPT_HOME_TEXTURE_BUDGET_FAIL", homeTextureBytes, homeBudget.textureBytesMax);
}
const safe = budget.tiers?.MOBILE_SAFE;
const balanced = budget.tiers?.BALANCED;
const premium = budget.tiers?.PREMIUM;
if (!safe || !balanced || !premium) fail("3D_OPT_TIERS_MISSING");

if (!(safe.shadowMap <= balanced.shadowMap && balanced.shadowMap <= premium.shadowMap)) fail("3D_OPT_SHADOW_TIER_ORDER_FAIL");
if (!(safe.maxParticles <= balanced.maxParticles && balanced.maxParticles <= premium.maxParticles)) fail("3D_OPT_PARTICLE_TIER_ORDER_FAIL");
if (budget.runtimeTargets?.shadowMapDefault !== premium.shadowMap) fail("3D_OPT_RUNTIME_SHADOW_TARGET_MISMATCH");

console.log("3D_OPTIMIZATION_CONTRACT_PASS", JSON.stringify({
  sourceSceneBytes: sourceBytes,
  sourceSceneRevision: sourceScene.revision,
  bundledGlbs: bundled,
  runtimePackaged: manifest.runtime_packaged,
  tiers: budget.tiers
}));
