#!/usr/bin/env node
import fs from "node:fs";
import path from "node:path";

const root = process.argv[2] || "android-app/app/src/main/assets/3d/tripo/batch-a";
const manifestPath = process.argv[3] || "docs/tripo/batch-a-manifest.json";

if (!fs.existsSync(manifestPath)) {
  console.error("TRIPO_BATCH_A_MANIFEST_MISSING", manifestPath);
  process.exit(2);
}

const manifest = JSON.parse(fs.readFileSync(manifestPath, "utf8"));

function readGlbJson(file) {
  const bytes = fs.readFileSync(file);
  if (bytes.length < 20) throw new Error("GLB too small");
  const magic = bytes.readUInt32LE(0);
  const version = bytes.readUInt32LE(4);
  const declaredLength = bytes.readUInt32LE(8);
  if (magic !== 0x46546c67) throw new Error("invalid GLB magic");
  if (version !== 2) throw new Error("unsupported GLB version");
  if (declaredLength !== bytes.length) throw new Error("GLB length mismatch");

  let cursor = 12;
  let json = null;
  while (cursor + 8 <= bytes.length) {
    const length = bytes.readUInt32LE(cursor);
    const type = bytes.readUInt32LE(cursor + 4);
    cursor += 8;
    if (cursor + length > bytes.length) throw new Error("invalid GLB chunk");
    const payload = bytes.subarray(cursor, cursor + length);
    cursor += length;
    if (type === 0x4e4f534a) {
      json = JSON.parse(payload.toString("utf8").replace(/\u0000+$/g, "").trim());
    }
  }
  if (!json) throw new Error("GLB JSON chunk missing");
  return json;
}

function textureKinds(json) {
  const materials = json.materials || [];
  const textures = json.textures || [];
  const out = new Set();

  for (const material of materials) {
    const pbr = material.pbrMetallicRoughness || {};
    if (pbr.baseColorTexture) out.add("baseColor");
    if (pbr.metallicRoughnessTexture) {
      out.add("metallic");
      out.add("roughness");
    }
    if (material.normalTexture) out.add("normal");
  }
  return [...out];
}

function triangleCount(json) {
  const accessors = json.accessors || [];
  const meshes = json.meshes || [];
  let triangles = 0;

  for (const mesh of meshes) {
    for (const primitive of mesh.primitives || []) {
      if (primitive.mode != null && primitive.mode !== 4) continue;
      if (primitive.indices != null) {
        triangles += Math.floor((accessors[primitive.indices]?.count || 0) / 3);
      } else {
        const posAccessor = (primitive.attributes || {}).POSITION;
        triangles += Math.floor((accessors[posAccessor]?.count || 0) / 3);
      }
    }
  }
  return triangles;
}

const results = [];
let failed = false;

for (const asset of manifest.assets || []) {
  if (asset.priority === "P0-optional" && !fs.existsSync(path.join(root, asset.file))) {
    results.push({ file: asset.file, status: "OPTIONAL_MISSING" });
    continue;
  }

  const file = path.join(root, asset.file);
  const row = { file: asset.file, status: "PASS" };

  if (!fs.existsSync(file)) {
    row.status = "MISSING";
    failed = true;
    results.push(row);
    continue;
  }

  try {
    const json = readGlbJson(file);
    const triangles = triangleCount(json);
    const maps = textureKinds(json);
    row.triangles = triangles;
    row.max_triangles = asset.max_triangles;
    row.maps = maps;
    row.material_count = (json.materials || []).length;
    row.mesh_count = (json.meshes || []).length;
    row.over_budget = triangles > asset.max_triangles;

    const required = new Set(asset.required_maps || []);
    const mapsOk = [...required].every((m) => maps.includes(m));
    if (row.over_budget || !mapsOk) {
      row.status = "FAIL";
      row.missing_maps = [...required].filter((m) => !maps.includes(m));
      failed = true;
    }
  } catch (error) {
    row.status = "FAIL";
    row.error = error?.message || String(error);
    failed = true;
  }

  results.push(row);
}

console.log(JSON.stringify({
  schema: manifest.schema,
  root,
  total: results.length,
  passed: results.filter((x) => x.status === "PASS").length,
  optional_missing: results.filter((x) => x.status === "OPTIONAL_MISSING").length,
  failed: results.filter((x) => x.status === "FAIL" || x.status === "MISSING").length,
  results
}, null, 2));

process.exit(failed ? 3 : 0);
