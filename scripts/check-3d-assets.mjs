#!/usr/bin/env node
import fs from "node:fs";
import path from "node:path";
import { createHash } from "node:crypto";

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
const packagedFiles = fs.readdirSync(root).filter((name) => /\.(glb|gltf)$/i.test(name));
const expectedAsset = path.join(root, "ludomada-premium-3d-rev3.glb");
const packagedBytes = fs.existsSync(expectedAsset) ? fs.statSync(expectedAsset).size : 0;
const packagedSha256 = fs.existsSync(expectedAsset)
  ? createHash("sha256").update(fs.readFileSync(expectedAsset)).digest("hex")
  : "";
checks.push(["source tree bundle flag", manifest.asset_bundled_in_source_tree === true]);
checks.push(["runtime loader explicit", manifest.runtime_loader === "GlbRuntimeLoader"]);
checks.push(["runtime packaged", manifest.runtime_packaged === true]);
checks.push(["runtime hero parts", Array.isArray(manifest.runtime_hero_parts) && manifest.runtime_hero_parts.includes("LM_Pawn_Tapered_Body") && manifest.runtime_hero_parts.includes("LM_Pawn_Spherical_Head") && manifest.runtime_hero_parts.includes("LM_Die_HydroChrome_Body")]);
checks.push(["rev3 asset file present", fs.existsSync(expectedAsset)]);
checks.push(["rev3 asset size", packagedBytes === 846104]);
checks.push(["rev3 asset sha256", packagedSha256 === "765646c3b285eff4dc50594a2a6f1705b62e5d60c79ab8a31dec3de0105de027"]);
const failed = checks.filter(([, ok]) => !ok);
const oversized = packagedFiles.filter((name) =>
  fs.statSync(path.join(root, name)).size > manifest.mobile_budgets.hero_glb_max_bytes
);
console.log(JSON.stringify({
  schema: manifest.schema,
  runtimePackaged: manifest.runtime_packaged,
  sourceScene: manifest.source_scene,
  board: manifest.board_geometry,
  packagedAssets: packagedFiles,
  rev3Asset: { present: fs.existsSync(expectedAsset), bytes: packagedBytes, sha256: packagedSha256 },
  oversizedAssets: oversized,
  passed: checks.length - failed.length,
  total: checks.length,
  failures: failed.map(([name]) => name)
}, null, 2));
if (failed.length || oversized.length) process.exit(3);