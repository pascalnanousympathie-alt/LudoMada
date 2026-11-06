#!/usr/bin/env node
import fs from "node:fs";
import path from "node:path";
import os from "node:os";
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
  ["source revision", manifest.source_scene?.revision === 7],
  ["source GLB size recorded", manifest.source_scene?.glb_bytes === 2011296],
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
const expectedPartCount = manifest.gameplay_environment?.source_archive_part_count ?? 35;
const r7PartsDirectory = path.resolve(root, "../../../../asset-sources/r7-parts");
const r7PartNames = fs.existsSync(r7PartsDirectory)
  ? fs.readdirSync(r7PartsDirectory).filter((name) => /^r7-[0-9]{3}\.bin$/.test(name)).sort()
  : [];
const r7PartBuffers = r7PartNames.map((name, index) => {
  const expectedName = `r7-${index.toString().padStart(3, "0")}.bin`;
  const part = fs.readFileSync(path.join(r7PartsDirectory, name));
  const expectedBytes = index === expectedPartCount - 1 ? 3612 : 13500;
  checks.push(["r7 archive part order: " + name, name === expectedName]);
  checks.push(["r7 archive part size: " + name, part.length === expectedBytes]);
  return part;
});
checks.push(["r7 archive part inventory", r7PartNames.length === expectedPartCount]);
let r7ArchiveBytes = 0;
let r7ArchiveSha256 = "";
let r7GlbBuffer = null;
let r7ExtractError = "";
if (r7PartBuffers.length === expectedPartCount) {
  const archiveBuffer = Buffer.concat(r7PartBuffers);
  r7ArchiveBytes = archiveBuffer.length;
  r7ArchiveSha256 = createHash("sha256").update(archiveBuffer).digest("hex");
  const tempDirectory = fs.mkdtempSync(path.join(os.tmpdir(), "ludomada-r7-check-"));
  try {
    const archivePath = path.join(tempDirectory, "ludomada-r7.zip");
    fs.writeFileSync(archivePath, archiveBuffer);
    execFileSync("unzip", ["-tq", archivePath], { stdio: "pipe" });
    r7GlbBuffer = execFileSync(
      "unzip",
      ["-p", archivePath, "ludomada-gameplay-environment-r7.glb"],
      { maxBuffer: 16 * 1024 * 1024 }
    );
  } catch (error) {
    r7ExtractError = error?.message || String(error);
  } finally {
    fs.rmSync(tempDirectory, { recursive: true, force: true });
  }
}
const packagedBytes = r7GlbBuffer?.length ?? 0;
const packagedSha256 = r7GlbBuffer
  ? createHash("sha256").update(r7GlbBuffer).digest("hex")
  : "";
checks.push(["r7 archive size", r7ArchiveBytes === 462612]);
checks.push(["r7 archive SHA-256", r7ArchiveSha256 === "de848f44936bf555c4210c8b9b7e7559eeeaa5a2fb14de795c9fa62cd58f4dcf"]);
checks.push(["r7 archive extracted", r7GlbBuffer !== null && !r7ExtractError]);
checks.push(["active r7 GLB size", packagedBytes === 2011296]);
checks.push(["active r7 GLB SHA-256", packagedSha256 === "1973b119d4ad02647c9cce9f193a37c25b0ae0ca778507f20b7078276f6d9cba"]);
checks.push(["r7 environment mesh count", manifest.gameplay_environment?.environment_mesh_count === 13]);
checks.push(["r7 dice tray count", manifest.gameplay_environment?.dice_trays === 4]);

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

  runtimeR7Asset: { archivePartCount: r7PartNames.length, archiveBytes: r7ArchiveBytes, archiveSha256: r7ArchiveSha256, glbBytes: packagedBytes, glbSha256: packagedSha256, extractionError: r7ExtractError },
  homeEnvironment: { renderer: homeEnvironment.renderer, assets: homeAssetResults, textureBytes: homeTextureBytes },
  oversizedAssets: oversized,
  passed: checks.length - failed.length,
  total: checks.length,
  failures: failed.map(([name]) => name)
}, null, 2));
if (failed.length || oversized.length) process.exit(3);
