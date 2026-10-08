const fs=require("node:fs");
const path=require("node:path");
const localtunnel=require(process.env.LMC_LOCALTUNNEL_PATH || "/data/data/com.termux/files/home/.npm/_npx/75ac80b86e83d4a2/node_modules/localtunnel");
const home=process.env.HOME || "/data/data/com.termux/files/home";
const state=path.join(home,".ludomada-commander");
fs.mkdirSync(state,{recursive:true});
localtunnel({port:8788,host:"https://localtunnel.me"}).then(tunnel=>{
  fs.writeFileSync(path.join(state,"web-tunnel.url"),tunnel.url+"\n");
  fs.writeFileSync(path.join(state,"web-tunnel.provider"),"localtunnel\n");
  console.log("WEB_TUNNEL_URL="+tunnel.url);
  tunnel.on("request",info=>process.stdout.write("REQUEST "+info.method+" "+info.path+"\n"));
  tunnel.on("error",err=>process.stderr.write("TUNNEL_ERROR="+err.message+"\n"));
  const close=()=>{try{tunnel.close()}catch{};process.exit(0)};
  process.on("SIGTERM",close); process.on("SIGINT",close);
}).catch(err=>{console.error("TUNNEL_START_ERROR="+err.message);process.exit(1)});
