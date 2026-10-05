#!/usr/bin/env node
import fs from "node:fs";
import path from "node:path";

const root = process.argv[2] || "android-app/app/src/main/assets/3d";
const manifestPath = path.join(root, "manifest.json");
if (!fs.existsSync(manifestPath)) {
  console.error("3D_ASSET_MANIFEST_MISSING", manifestPath);
  process.exit(2);
}
const manifest = JSON.parse(fs.readFileSync(manifestPath, "utf8"));
const checks = [
  ["schema", manifest.schema === "ludomada-premium-3d-v2"],
  ["units", manifest.units === "meters"],
  ["format", manifest.format === "glb/gltf2"],
  ["target", manifest.target === "android-realtime"],
  ["source provider", manifest.source_scene?.provider === "Higgsfield 3D Jutsu"],
  ["source revision", manifest.source_scene?.revision === 3],
  ["source GLB size recorded", manifest.source_scene?.glb_bytes === 846104],
  ["material calibration", manifest.source_scene?.material_calibration === "PremiumMaterialLighting.Calibration"],
  ["die metallic", manifest.assets?.die?.authoring_metallic === 1],
  ["die roughness", manifest.assets?.die?.authoring_roughness === 0.055],
  ["die clear coat", manifest.assets?.die?.clear_coat === 0.72],
  ["die clear coat roughness", manifest.assets?.die?.clear_coat_roughness === 0.055],
  ["board path cells", manifest.board_geometry?.path_cells === 52],
  ["finish cells", manifest.board_geometry?.finish_cells === 24],
  ["homes", manifest.board_geometry?.home_plinths === 4],
  ["center wedges", manifest.board_geometry?.center_wedges === 4],
  ["hero budget", manifest.mobile_budgets?.hero_glb_max_bytes === 8388608]
];
const failed = checks.filter(([, ok]) => !ok);
const packagedFiles = fs.readdirSync(root).filter((name) => /\.(glb|gltf)$/i.test(name));
const oversized = packagedFiles.filter((name) =>
  fs.statSync(path.join(root, name)).size > manifest.mobile_budgets.hero_glb_max_bytes
);
console.log(JSON.stringify({
  schema: manifest.schema,
  runtimePackaged: manifest.runtime_packaged,
  sourceScene: manifest.source_scene,
  board: manifest.board_geometry,
  packagedAssets: packagedFiles,
  oversizedAssets: oversized,
  passed: checks.length - failed.length,
  total: checks.length,
  failures: failed.map(([name]) => name)
}, null, 2));
if (failed.length || oversized.length) process.exit(3);