#!/usr/bin/env node
import fs from "node:fs";
import path from "node:path";

const root=process.argv[2] || "android-app/app/src/main/assets/3d";
const cfgPath=path.join(root,"optimization-budget.json");
if(!fs.existsSync(cfgPath)){console.error("3D_OPT_BUDGET_MISSING");process.exit(2);}
const cfg=JSON.parse(fs.readFileSync(cfgPath,"utf8"));
const glbs=fs.existsSync(root) ? fs.readdirSync(root).filter(n=>/\.glb$/i.test(n)) : [];
for(const file of glbs){
  const bytes=fs.statSync(path.join(root,file)).size;
  if(bytes>cfg.glbBytes.heroMax){
    console.error("3D_OPT_BUDGET_FAIL",file,bytes,cfg.glbBytes.heroMax);
    process.exit(3);
  }
}
if(cfg.currentScene.glbBytes>cfg.glbBytes.heroMax){
  console.error("3D_OPT_SOURCE_BUDGET_FAIL",cfg.currentScene.glbBytes);
  process.exit(4);
}
console.log("3D_OPT_BUDGET_PASS",JSON.stringify({glbs,currentScene:cfg.currentScene,tiers:cfg.tiers}));
