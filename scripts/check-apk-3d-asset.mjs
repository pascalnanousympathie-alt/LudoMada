#!/usr/bin/env node
import { execFileSync } from "node:child_process";
import fs from "node:fs";

const apk = process.argv[2];
if (!apk) { console.error("APK_3D_ASSET_CHECK_FAIL missing APK path"); process.exit(2); }
if (!fs.existsSync(apk) || fs.statSync(apk).size === 0) {
  console.error("APK_3D_ASSET_CHECK_FAIL APK missing or empty:", apk);
  process.exit(2);
}

// Keep the APK assertion aligned with GlbRuntimeLoader.loadHeroParts/loadBoardParts
// and check-3d-assets.mjs: rev5 is the GLB actually consumed by the runtime.
const target = "assets/3d/ludomada-gameplay-rev5.glb";
const listing = execFileSync("unzip", ["-Z1", apk], { encoding: "utf8" });
const entries = listing.split(/\r?\n/).map((x) => x.trim()).filter(Boolean);
const present = entries.includes(target);
console.log(JSON.stringify({ apk, target, present, entryCount: entries.length }, null, 2));
if (!present) {
  console.error("APK_3D_ASSET_CHECK_FAIL target not packaged:", target);
  process.exit(3);
}
console.log("APK_3D_ASSET_CHECK_PASS");
