#!/usr/bin/env node
import fs from 'node:fs';
import path from 'node:path';

const root=process.argv[2]||'android-app/app/src/main/assets/3d';
const file=path.join(root,'manifest.json');
if(!fs.existsSync(file)){console.error('COSMETIC_MANIFEST_MISSING');process.exit(2)}
const m=JSON.parse(fs.readFileSync(file,'utf8'));const c=m.cosmetic_catalog;
if(!c||c.version!=='1'||c.renderer!=='procedural-fallback-v1'){console.error('COSMETIC_CATALOG_INVALID');process.exit(3)}
const groups={board:c.board_ids,dice:c.dice_ids,pawn:c.pawn_ids,vfx:c.vfx_ids};
for(const [name,ids] of Object.entries(groups)){if(!Array.isArray(ids)||ids.length<2||ids.some(id=>typeof id!=='string'||!id)){console.error('COSMETIC_GROUP_INVALID '+name);process.exit(4)}}
for(const k of ['board','dice','pawn','vfx']){if(c.selection_keys?.[k]!==k){console.error('COSMETIC_SELECTION_KEY_INVALID '+k);process.exit(5)}}
if(c.max_asset_bytes!==12582912){console.error('COSMETIC_SIZE_CONTRACT_INVALID');process.exit(6)}
const assetsDir=path.join(root,'cosmetics');let files=0;
if(fs.existsSync(assetsDir)){const walk=(dir)=>{for(const name of fs.readdirSync(dir)){const p=path.join(dir,name),st=fs.statSync(p);if(st.isDirectory())walk(p);else if(/\.(glb|gltf)$/i.test(name)){files++;if(st.size>c.max_asset_bytes){console.error('COSMETIC_ASSET_TOO_LARGE '+p);process.exit(7)}}}};walk(assetsDir)}
console.log('COSMETIC_ASSET_CONTRACT_OK boards='+c.board_ids.length+' dice='+c.dice_ids.length+' pawns='+c.pawn_ids.length+' vfx='+c.vfx_ids.length+' glb='+files);
