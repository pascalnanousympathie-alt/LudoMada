#!/usr/bin/env node

import fs from "node:fs";

const [,, glbPath = "android-app/app/src/main/assets/3d/ludomada-gameplay-rev5.glb",
  loaderPath = "android-app/app/src/main/java/com/ludomadagascar/GlbRuntimeLoader.kt"] = process.argv;

if (!fs.existsSync(glbPath)) throw new Error(`GLB not found: ${glbPath}`);
if (!fs.existsSync(loaderPath)) throw new Error(`Loader not found: ${loaderPath}`);

const bytes = fs.readFileSync(glbPath);
if (bytes.length < 20) throw new Error("GLB too small");

const header = Buffer.from(bytes.buffer, bytes.byteOffset, 12);
if (header.readUInt32LE(0) !== 0x46546c67) throw new Error("Invalid GLB magic");
if (header.readUInt32LE(4) !== 2) throw new Error("Unsupported GLB version");
if (header.readUInt32LE(8) !== bytes.length) {
  throw new Error(`GLB length mismatch: declared=${header.readUInt32LE(8)} actual=${bytes.length}`);
}

let cursor = 12;
let json = null;
while (cursor + 8 <= bytes.length) {
  const chunkLength = bytes.readUInt32LE(cursor);
  const chunkType = bytes.readUInt32LE(cursor + 4);
  cursor += 8;
  if (cursor + chunkLength > bytes.length) throw new Error("Invalid GLB chunk");
  if (chunkType === 0x4e4f534a) {
    json = JSON.parse(bytes.subarray(cursor, cursor + chunkLength).toString("utf8").replace(/\u0000+$/, "").trim());
    break;
  }
  cursor += chunkLength;
}

if (!json) throw new Error("GLB JSON chunk missing");

const die = (json.nodes ?? []).find(n => n?.name === "LM_Die_HydroChrome_Body");
if (!die) throw new Error("LM_Die_HydroChrome_Body node missing");

const hasMatrix = Array.isArray(die.matrix) && die.matrix.length === 16;
const hasRotation = Array.isArray(die.rotation) && die.rotation.length === 4;
if (!hasMatrix && !hasRotation) {
  throw new Error("Hydro-chrome die node has neither glTF matrix nor quaternion rotation");
}

const loader = fs.readFileSync(loaderPath, "utf8");
const loaderHandlesMatrix = loader.includes('optJSONArray("matrix")');
const loaderHandlesRotation = loader.includes('optJSONArray("rotation")');
const runtimeUsesThisAsset =
  loader.includes('loadParts("3d/ludomada-gameplay-rev5.glb")');
if (!runtimeUsesThisAsset) {
  throw new Error("GlbRuntimeLoader no longer routes gameplay hero/board parts through the active rev5 GLB");
}
if (!loaderHandlesMatrix || !loaderHandlesRotation) {
  throw new Error("GlbRuntimeLoader does not preserve both glTF matrix and quaternion rotation transforms");
}

const report = {
  activeRuntimeAsset: glbPath,
  glbBytes: bytes.length,
  dieNode: die.name,
  transformEncoding: hasMatrix ? "matrix" : "quaternion",
  loaderHandlesMatrix,
  loaderHandlesRotation,
  runtimeUsesThisAsset,
  expected: true
};

console.log(JSON.stringify(report, null, 2));
console.log("GLB_RUNTIME_TRANSFORM_CONTRACT_PASS");
