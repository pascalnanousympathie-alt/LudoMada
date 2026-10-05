#!/usr/bin/env node
/**
 * LudoMada 3D asset contract checker.
 * Intentionally dependency-light: validates file naming and metadata contract.
 * Geometry/material validation is performed in Blender/3D Jutsu before import.
 */
import fs from 'node:fs';
import path from 'node:path';

const root = process.argv[2] || 'android-app/app/src/main/assets/3d';
const manifestPath = path.join(root, 'manifest.json');
if (!fs.existsSync(manifestPath)) {
  console.error('3D_ASSET_MANIFEST_MISSING', manifestPath);
  process.exit(2);
}
const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
const required = [
  ['schema', 'ludomada-premium-3d-v1'],
  ['units', 'meters'],
  ['format', 'glb/gltf2'],
  ['target', 'android-realtime']
];
for (const [field, expected] of required) {
  if (manifest[field] !== expected) {
    console.error('3D_ASSET_CONTRACT_INVALID ' + field + ': expected ' + expected + ', got ' + manifest[field]);
    process.exit(3);
  }
}
const files = fs.readdirSync(root).filter((name) => /.(glb|gltf)$/i.test(name));
for (const file of files) {
  const size = fs.statSync(path.join(root, file)).size;
  if (size > 12 * 1024 * 1024) {
    console.error('3D_ASSET_TOO_LARGE ' + file + ': ' + size + ' bytes');
    process.exit(4);
  }
}
console.log('3D_ASSET_CONTRACT_OK assets=' + files.length);
