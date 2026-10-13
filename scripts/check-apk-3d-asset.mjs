#!/usr/bin/env node
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";

const apk = process.argv[2];
if (!apk) {
  console.error("APK_3D_ASSET_CHECK_FAIL missing APK path");
  process.exit(2);
}
if (!fs.existsSync(apk) || fs.statSync(apk).size === 0) {
  console.error("APK_3D_ASSET_CHECK_FAIL APK missing or empty:", apk);
  process.exit(2);
}

const manifestPath = process.env.LUDOMADA_3D_MANIFEST ||
  "android-app/app/src/main/assets/3d/manifest.json";
if (!fs.existsSync(manifestPath)) {
  console.error("APK_3D_ASSET_CHECK_FAIL source GLB manifest missing:", manifestPath);
  process.exit(2);
}
const manifest = JSON.parse(fs.readFileSync(manifestPath, "utf8"));
if (manifest.runtime_loader !== "GlbRuntimeLoader" || manifest.active_renderer !== "native-gles3-premium") {
  console.error("APK_3D_ASSET_CHECK_FAIL active renderer/runtime loader contract mismatch");
  process.exit(3);
}

const expected = [
  manifest.source_scene?.delivery?.path,
  manifest.market_library?.asset,
  "3d/ludomada-environment-final.glb",
  "3d/environment/baobab-real.glb",
  "environment/textures/bark_diff_2k.jpg",
  "environment/textures/bark_nor_gl_2k.jpg",
  "environment/textures/bark_rough_2k.jpg",
  "environment/textures/dirt_diff_2k.jpg",
  "environment/textures/dirt_nor_gl_2k.jpg",
  "environment/textures/dirt_rough_2k.jpg",
  "environment/textures/grass_diff_2k.jpg",
  "environment/textures/grass_nor_gl_2k.jpg",
  "environment/textures/grass_rough_2k.jpg",
  "environment/textures/safari_sunset_2k.jpg",
].filter(Boolean).map(p => "assets/" + p.replace(/^\/+/, ""));

const listing = execFileSync("unzip", ["-Z1", apk], { encoding: "utf8" });
const entries = new Set(listing.split(/\r?\n/).map(x => x.trim()).filter(Boolean));
const missing = [...new Set(expected)].filter(p => !entries.has(p));
const result = {
  apk,
  activeRenderer: manifest.active_renderer,
  runtimeLoader: manifest.runtime_loader,
  gameplayAsset: manifest.source_scene?.delivery?.path,
  marketAsset: manifest.market_library?.asset,
  expectedAssetCount: new Set(expected).size,
  entryCount: entries.size,
  missing
};
console.log(JSON.stringify(result, null, 2));
if (missing.length) {
  console.error("APK_3D_ASSET_CHECK_FAIL missing runtime asset(s)");
  process.exit(3);
}
console.log("APK_3D_ASSET_CHECK_PASS");
