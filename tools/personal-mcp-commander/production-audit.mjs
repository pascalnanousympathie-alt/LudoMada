import { spawn } from "node:child_process";
import fs from "node:fs/promises";
import path from "node:path";

const ROOT = new URL(".", import.meta.url).pathname.replace(/\/$/, "");
const PROJECT = path.resolve(ROOT, "../..");
const CONFIG_PATH = path.join(process.env.HOME || "/data/data/com.termux/files/home", "ludomada-mcp-commander", "config.json");
const fail = msg => { throw new Error(msg); };

function rawLines() {
  let buffer = "";
  const messages = new Map();
  return {
    messages,
    feed(chunk) {
      buffer += chunk;
      for (;;) {
        const i = buffer.indexOf("\n");
        if (i < 0) break;
        const line = buffer.slice(0, i).trim();
        buffer = buffer.slice(i + 1);
        if (!line) continue;
        try { const m = JSON.parse(line); if (m.id != null) messages.set(m.id, m); } catch {}
      }
    }
  };
}
function stdioClient() {
  const child = spawn(process.execPath, ["server.mjs"], { cwd: ROOT, stdio: ["pipe","pipe","pipe"] });
  const parser = rawLines();
  let stderr = "";
  child.stdout.on("data", d => parser.feed(d.toString()));
  child.stderr.on("data", d => stderr += d.toString());
  function send(id, method, params = {}) { child.stdin.write(JSON.stringify({jsonrpc:"2.0",id,method,params})+"\n"); }
  async function wait(id, timeout=15000) {
    const start=Date.now();
    while (Date.now()-start < timeout) {
      if (parser.messages.has(id)) return parser.messages.get(id);
      await new Promise(r=>setTimeout(r,25));
    }
    fail("stdio timeout id="+id+" stderr="+stderr.slice(-3000));
  }
  async function call(id,name,args={}) {
    send(id,"tools/call",{name,arguments:args});
    const m=await wait(id,30000);
    if (m.error) fail("tool "+name+" error: "+JSON.stringify(m.error));
    if (m.result?.isError) fail("tool "+name+" returned isError");
    return m.result || {};
  }
  return {child,send,wait,call};
}
function resultText(result) { return result?.content?.find(x=>x.type==="text")?.text || ""; }
async function main() {
  const c = stdioClient();
  c.send(1,"initialize",{protocolVersion:"2025-06-18",capabilities:{},clientInfo:{name:"production-audit",version:"1"}});
  const init=await c.wait(1);
  if (init?.result?.serverInfo?.version !== "1.0.0") fail("bad server version");
  c.send(2,"notifications/initialized",{});
  c.send(3,"tools/list",{});
  const list=await c.wait(3);
  const tools=list?.result?.tools || [];
  const names=new Set(tools.map(t=>t.name));
  const required=["health_check","control_plane_capabilities","audit_log","snapshot_file","rollback_snapshot","developer_job_start","developer_job_status","browser_status","browser_navigate","browser_evaluate","browser_screenshot","github_status","android_install_apk"];
  const missing=required.filter(x=>!names.has(x));
  if (missing.length) fail("missing tools: "+missing.join(","));

  const health=await c.call(4,"health_check",{project:PROJECT});
  const hs=JSON.parse(resultText(health));
  if (!hs.healthy) fail("health_check says unhealthy: "+JSON.stringify(hs.checks));
  if (!Object.values(hs.checks).every(Boolean)) fail("health checks not all true: "+JSON.stringify(hs.checks));

  const caps=await c.call(5,"control_plane_capabilities",{});
  const cs=JSON.parse(resultText(caps));
  if (cs.security?.files?.some(x=>x.present && x.private===false)) fail("private file mode failure");
  if (!cs.access?.fileSnapshots || !cs.access?.backgroundJobs || !cs.access?.browserCDPProbe) fail("capability regression");

  const outside=await c.call(6,"read_file",{path:"/etc/passwd",project:PROJECT}).catch(e=>({__error:String(e.message||e)}));
  if (!outside.__error && !outside.isError) fail("path confinement failed for /etc/passwd");
  const symlinkRel = ".tmp/production-audit-link-"+Date.now();
  await fs.mkdir(path.join(PROJECT,".tmp"),{recursive:true});
  await fs.symlink("/etc", path.join(PROJECT,symlinkRel));
  const symlinkProbe = await c.call(61,"read_file",{path:symlinkRel+"/passwd",project:PROJECT}).catch(e=>({__error:String(e.message||e)}));
  await fs.rm(path.join(PROJECT,symlinkRel),{recursive:true,force:true});
  if (!symlinkProbe.__error && !symlinkProbe.isError) fail("symlink escape path confinement failed");

  const rel=".tmp/production-audit-"+Date.now()+".txt";
  const absolute=path.join(PROJECT,rel);
  await c.call(7,"write_file",{path:rel,project:PROJECT,content:"BASELINE\n"});
  const snap=JSON.parse(resultText(await c.call(8,"snapshot_file",{path:rel,project:PROJECT}))).snapshotId;
  if (!/^snap_[a-f0-9]{18}$/.test(snap)) fail("invalid snapshot id");
  await c.call(9,"write_file",{path:rel,project:PROJECT,content:"MUTATED\n"});
  await c.call(10,"rollback_snapshot",{snapshotId:snap});
  const restored=resultText(await c.call(11,"read_file",{path:rel,project:PROJECT}));
  if (!restored.includes("BASELINE")) fail("rollback content mismatch");
  await c.call(12,"exec",{command:"rm -f "+JSON.stringify(rel),project:PROJECT});

  const browser=JSON.parse(resultText(await c.call(13,"browser_status",{})));
  if (!browser.supported) fail("browser capability unexpectedly unsupported");
  const evalRes=await c.call(14,"browser_evaluate",{expression:"1+1"});
  const evalJson=JSON.parse(resultText(evalRes));
  if (evalJson.result?.result?.value !== 2) fail("browser evaluate mismatch");

  const job=JSON.parse(resultText(await c.call(15,"developer_job_start",{profile:"diagnose",project:PROJECT})));
  if (!job.id) fail("developer job missing id");
  let status=null;
  for (let i=0;i<50;i++) {
    status=JSON.parse(resultText(await c.call(16+i,"developer_job_status",{jobId:job.id})));
    if (["succeeded","failed","cancelled"].includes(status.status)) break;
    await new Promise(r=>setTimeout(r,100));
  }
  if (status?.status !== "succeeded") fail("diagnose job did not succeed: "+JSON.stringify(status));

  const httpConfig=JSON.parse(await fs.readFile(CONFIG_PATH,"utf8"));
  const token=httpConfig.http?.token;
  if (!token) fail("HTTP MCP token missing");
  const disc=await fetch("http://127.0.0.1:8787/.well-known/oauth-protected-resource");
  if (disc.status!==200) fail("HTTP MCP discovery status "+disc.status);
  async function httpPost(payload, sessionId="") {
    const headers={authorization:"Bearer "+token,"content-type":"application/json","accept":"application/json, text/event-stream"};
    if(sessionId) headers["mcp-session-id"]=sessionId;
    const r=await fetch("http://127.0.0.1:8787/mcp",{method:"POST",headers,body:JSON.stringify(payload)});
    const raw=await r.text();
    let parsed=null;
    if(raw){ try{parsed=JSON.parse(raw);}catch{const line=raw.split("\n").find(x=>x.startsWith("data:")); if(line){try{parsed=JSON.parse(line.slice(5).trim())}catch{}}} }
    return {r,raw,parsed,session:r.headers.get("mcp-session-id")||sessionId};
  }
  const hi=await httpPost({jsonrpc:"2.0",id:1,method:"initialize",params:{protocolVersion:"2025-06-18",capabilities:{},clientInfo:{name:"production-audit-http",version:"1"}}});
  if(!hi.r.ok || !hi.parsed?.result) fail("HTTP MCP initialize failed status="+hi.r.status+" body="+hi.raw.slice(0,1000));
  const ht=await httpPost({jsonrpc:"2.0",id:2,method:"tools/list",params:{}},hi.session);
  const httpTools=ht.parsed?.result?.tools||[];
  if(!ht.r.ok || httpTools.length < required.length) fail("HTTP MCP tools/list failed/count="+httpTools.length+" status="+ht.r.status);

  await c.call(17,"audit_log",{limit:100});
  c.child.kill("SIGTERM");
  console.log(JSON.stringify({
    ok:true,
    stdio:{protocol:init.result.protocolVersion,toolCount:tools.length,health:hs.checks},
    capabilities:{snapshots:!!cs.access.fileSnapshots,jobs:!!cs.access.backgroundJobs,browser:!!cs.access.browserCDPProbe},
    browser:{cdp:browser.cdpEndpoints?.length||0,tabs:browser.tabs?.reduce((n,x)=>n+(x.tabs?.length||0),0)||0},
    developerJob:{id:job.id,status:status.status,durationMs:status.durationMs},
    http:{discovery:disc.status,initialize:hi.r.status,tools:httpTools.length},
    confinementTest:"PASS",rollbackTest:"PASS"
  }));
}
main().catch(e=>{ console.error("AUDIT_FAIL:",e.message||e); process.exit(1); });