#!/usr/bin/env node
import fs from "node:fs";
const p="android-app/app/src/main/assets/3d/manifest.json";
if(!fs.existsSync(p)){console.error("3D_SCENE_MANIFEST_MISSING");process.exit(2);}
const m=JSON.parse(fs.readFileSync(p,"utf8"));
const checks=[
  ["schema",m.schema==="ludomada-premium-3d-v2"],
  ["revision",m.source?.revision===2],
  ["glb-size",m.source?.committedGlbBytes===846100],
  ["path-cells",m.board?.pathCells===52],
  ["finish-cells",m.board?.finishCells===24],
  ["homes",m.board?.homePlinths===4],
  ["center-wedges",m.board?.centerWedges===4],
  ["metres",m.units==="meters"]
];
for(const [n,ok] of checks) if(!ok){console.error("3D_SCENE_CONTRACT_FAIL",n);process.exit(3);}
console.log("3D_SCENE_CONTRACT_PASS",JSON.stringify(m.board));
