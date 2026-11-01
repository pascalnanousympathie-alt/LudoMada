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
  ["source revision", manifest.source_scene?.revision === 5],
  ["source GLB size recorded", manifest.source_scene?.glb_bytes === 867800],
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
const expectedAsset = path.join(root, "ludomada-gameplay-rev5.glb");
const packagedBytes = fs.existsSync(expectedAsset) ? fs.statSync(expectedAsset).size : 0;
const packagedSha256 = fs.existsSync(expectedAsset)
  ? createHash("sha256").update(fs.readFileSync(expectedAsset)).digest("hex")
  : "";
checks.push(["source tree bundle flag", manifest.asset_bundled_in_source_tree === true]);
checks.push(["runtime loader explicit", manifest.runtime_loader === "GlbRuntimeLoader"]);
checks.push(["runtime packaged", manifest.runtime_packaged === true]);
checks.push(["runtime hero parts", Array.isArray(manifest.runtime_hero_parts) && manifest.runtime_hero_parts.includes("LM_Pawn_Tapered_Body") && manifest.runtime_hero_parts.includes("LM_Pawn_Spherical_Head") && manifest.runtime_hero_parts.includes("LM_Die_HydroChrome_Body")]);
checks.push(["active rev5 asset file present", fs.existsSync(expectedAsset)]);
checks.push(["active rev5 asset size", packagedBytes === 867800]);
checks.push(["active rev5 asset sha256", packagedSha256 === "bd648fd8b2a265e26220ce1eed3f8a7d60f8507345290cea6e19fba56f401e0f"]);

function jpegDimensions(filePath) {
  const bytes = fs.readFileSync(filePath);
  if (bytes.length < 4 || bytes[0] !== 0xff || bytes[1] !== 0xd8) return null;
  const startOfFrame = new Set([0xc0,0xc1,0xc2,0xc3,0xc5,0xc6,0xc7,0xc9,0xca,0xcb,0xcd,0xce,0xcf]);
  let offset = 2;
  while (offset + 4 < bytes.length) {
    if (bytes[offset] !== 0xff) { offset += 1; continue; }
    while (bytes[offset] === 0xff) offset += 1;
    const marker = bytes[offset++];
    if (marker === 0xd9 || marker === 0xda) break;
    if (marker === 0x01 || (marker >= 0xd0 && marker <= 0xd7)) continue;
    if (offset + 2 > bytes.length) return null;
    const segmentLength = bytes.readUInt16BE(offset);
    if (segmentLength < 2 || offset + segmentLength > bytes.length) return null;
    if (startOfFrame.has(marker)) {
      return {
        height: bytes.readUInt16BE(offset + 3),
        width: bytes.readUInt16BE(offset + 5)
      };
    }
    offset += segmentLength;
  }
  return null;
}

const assetsRoot = path.resolve(root, "..");
const homeEnvironment = manifest.home_environment ?? {};
checks.push(["Home preview renderer declared", homeEnvironment.renderer === "LudoBoardView.SceneRenderer.homePreview"]);
checks.push(["Home preview texture dimension declared", homeEnvironment.textures_max_dimension === 2048]);
const homeAssets = [
  homeEnvironment.scene_glb,
  homeEnvironment.baobab_glb,
  ...(Array.isArray(homeEnvironment.textures) ? homeEnvironment.textures : [])
].filter(Boolean);
const expectedHomeAssetCount = 12;
checks.push(["Home environment asset inventory complete", homeAssets.length === expectedHomeAssetCount]);
const homeAssetResults = homeAssets.map((asset) => {
  const targetPath = typeof asset.path === "string" ? path.resolve(assetsRoot, asset.path) : "";
  const pathSafe = targetPath.startsWith(assetsRoot + path.sep);
  const present = pathSafe && fs.existsSync(targetPath) && fs.statSync(targetPath).isFile();
  const actualBytes = present ? fs.statSync(targetPath).size : 0;
  checks.push(["Home asset path safe: " + String(asset.path), pathSafe]);
  checks.push(["Home asset present: " + String(asset.path), present]);
  checks.push(["Home asset size: " + String(asset.path), present && actualBytes === asset.bytes]);
  const isHomeTexture = String(asset.path).startsWith("environment/textures/");
  const dimensions = present && isHomeTexture ? jpegDimensions(targetPath) : null;
  if (isHomeTexture) {
    checks.push(["Home texture is valid JPEG: " + String(asset.path), dimensions !== null]);
    checks.push(["Home texture fits 2K cap: " + String(asset.path),
      dimensions !== null && dimensions.width <= homeEnvironment.textures_max_dimension &&
      dimensions.height <= homeEnvironment.textures_max_dimension]);
  }
  return { path: asset.path, present, bytes: actualBytes, expectedBytes: asset.bytes, dimensions };
});
const homeTextureBytes = homeAssets
  .filter((asset) => String(asset.path).startsWith("environment/textures/"))
  .reduce((sum, asset) => sum + Number(asset.bytes || 0), 0);
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

  runtimeRev5Asset: { present: fs.existsSync(expectedAsset), bytes: packagedBytes, sha256: packagedSha256 },
  homeEnvironment: { renderer: homeEnvironment.renderer, assets: homeAssetResults, textureBytes: homeTextureBytes },
  oversizedAssets: oversized,
  passed: checks.length - failed.length,
  total: checks.length,
  failures: failed.map(([name]) => name)
}, null, 2));
if (failed.length || oversized.length) process.exit(3);
