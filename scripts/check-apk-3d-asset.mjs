#!/usr/bin/env node
import { execFileSync } from "node:child_process";
import fs from "node:fs";

const apk = process.argv[2];
if (!apk) { console.error("APK_3D_ASSET_CHECK_FAIL missing APK path"); process.exit(2); }
if (!fs.existsSync(apk) || fs.statSync(apk).size === 0) {
  console.error("APK_3D_ASSET_CHECK_FAIL APK missing or empty:", apk);
  process.exit(2);
}

// Keep this aligned with GlbRuntimeLoader and LudoBoardView's GLES3 Home-preview path.
const targets = [
  "assets/3d/ludomada-gameplay-environment-r7.glb",
  "assets/3d/ludomada-environment-final.glb",
  "assets/3d/environment/baobab-real.glb",
  "assets/environment/textures/bark_diff_2k.jpg",
  "assets/environment/textures/bark_nor_gl_2k.jpg",
  "assets/environment/textures/bark_rough_2k.jpg",
  "assets/environment/textures/dirt_diff_2k.jpg",
  "assets/environment/textures/dirt_nor_gl_2k.jpg",
  "assets/environment/textures/dirt_rough_2k.jpg",
  "assets/environment/textures/grass_diff_2k.jpg",
  "assets/environment/textures/grass_nor_gl_2k.jpg",
  "assets/environment/textures/grass_rough_2k.jpg",
  "assets/environment/textures/safari_sunset_2k.jpg"
];
const listing = execFileSync("unzip", ["-Z1", apk], { encoding: "utf8" });
const entries = listing.split(/\r?\n/).map((x) => x.trim()).filter(Boolean);
const missing = targets.filter((target) => !entries.includes(target));
console.log(JSON.stringify({ apk, targets, missing, entryCount: entries.length }, null, 2));
if (missing.length) {
  console.error("APK_3D_ASSET_CHECK_FAIL missing assets:", missing.join(", "));
  process.exit(3);
}
console.log("APK_3D_ASSET_CHECK_PASS");
