import http from "node:http";
import fs from "node:fs/promises";
import fsSync from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import { spawn } from "node:child_process";

const HOME = process.env.HOME || "/data/data/com.termux/files/home";
const ROOT = path.resolve(process.env.COMMANDER_HOME || path.join(HOME, "ludomada-mcp-commander"));
const CONFIG = path.join(ROOT, "config.json");
const UI = path.join(ROOT, "public", "index.html");
const PORT = Number(process.env.COMMANDER_UI_PORT || 8788);

async function loadConfig() {
  let c = {};
  try { c = JSON.parse(await fs.readFile(CONFIG, "utf8")); } catch {}
  c.workspaceRoots = Array.isArray(c.workspaceRoots) && c.workspaceRoots.length ? c.workspaceRoots : [HOME];
  c.defaultProject ||= path.join(HOME, "LudoMada");
  c.http ||= { host: "127.0.0.1", port: 8787, token: "" };
  c.exec ||= { timeoutMs: 120000, maxOutputBytes: 200000 };
  c.web ||= {};
  if (!c.web.pairingCode) {
    c.web.pairingCode = crypto.randomBytes(9).toString("hex");
    await fs.writeFile(CONFIG, JSON.stringify(c, null, 2) + "\n");
  }
  if (!c.http.token) {
    c.http.token = crypto.randomBytes(32).toString("hex");
    await fs.writeFile(CONFIG, JSON.stringify(c, null, 2) + "\n");
  }
  await fs.chmod(CONFIG, 0o600).catch(() => {});
  return c;
}
const config = await loadConfig();
const MCP_URL = `http://127.0.0.1:${config.http.port}/mcp`;
const HEALTH_URL = "http://127.0.0.1:" + (process.env.PERSONAL_MCP_HEALTH_PORT || "8790") + "/health?details=true";
const WEB_COOKIE = "commander_web_session";
const WEB_SESSION_TTL_MS = 8 * 60 * 60 * 1000;
const webSessions = new Map();
const loginAttempts = new Map();
const LOGIN_WINDOW_MS = 5 * 60 * 1000;
const LOGIN_MAX_ATTEMPTS = 5;

function pruneAuthState() {
  const now = Date.now();
  for (const [k, expiresAt] of webSessions) if (expiresAt <= now) webSessions.delete(k);
  for (const [k, v] of loginAttempts) if (now - v.windowStart > LOGIN_WINDOW_MS) loginAttempts.delete(k);
}

function loginAllowed(req) {
  pruneAuthState();
  const ip = String(req.socket?.remoteAddress || "unknown");
  const current = loginAttempts.get(ip);
  if (!current || Date.now() - current.windowStart > LOGIN_WINDOW_MS) {
    loginAttempts.set(ip, { windowStart: Date.now(), count: 1 });
    return true;
  }
  if (current.count >= LOGIN_MAX_ATTEMPTS) return false;
  current.count += 1;
  return true;
}

function constantTimeEqual(a, b) {
  const aa = Buffer.from(String(a));
  const bb = Buffer.from(String(b));
  return aa.length === bb.length && crypto.timingSafeEqual(aa, bb);
}
const WEB_TUNNEL_PID = path.join(HOME, ".ludomada-commander", "web-tunnel.pid");
const WEB_TUNNEL_CLOUD_PID = path.join(HOME, ".ludomada-commander", "web-tunnel-cloudflared.pid");
const WEB_TUNNEL_LOG = path.join(HOME, ".ludomada-commander", "web-tunnel.log");
const WEB_TUNNEL_SCRIPT = path.join(ROOT, "tunnel", "run-web-tunnel-localtunnel.cjs");
const WEB_TUNNEL_URL = path.join(HOME, ".ludomada-commander", "web-tunnel.url");
const WEB_TUNNEL_PROVIDER = path.join(HOME, ".ludomada-commander", "web-tunnel.provider");

function projectPath(p) {
  const candidate = path.resolve(p || config.defaultProject);
  const ok = config.workspaceRoots.some(r => {
    const rr = path.resolve(r);
    const rel = path.relative(rr, candidate);
    return rel === "" || (!rel.startsWith("..") && !path.isAbsolute(rel));
  });
  if (!ok) throw new Error("Project outside workspaceRoots: " + candidate);
  return candidate;
}

const resolveProject = projectPath;
function realCandidate(candidate) {
  let probe = path.resolve(candidate);
  while (!fsSync.existsSync(probe) && path.dirname(probe) !== probe) probe = path.dirname(probe);
  const realProbe = fsSync.realpathSync.native(probe);
  if (fsSync.existsSync(candidate)) return fsSync.realpathSync.native(candidate);
  return path.resolve(realProbe, path.relative(probe, candidate));
}
function resolveSafe(inputPath, project) {
  const base = projectPath(project);
  const candidate = path.isAbsolute(inputPath) ? path.resolve(inputPath) : path.resolve(base, inputPath);
  const real = realCandidate(candidate);
  const allowed = config.workspaceRoots.some(r => {
    const rr=realCandidate(path.resolve(r)), rel=path.relative(rr,real);
    return rel==="" || (!rel.startsWith("..") && !path.isAbsolute(rel));
  });
  if (!allowed) throw new Error("Path is outside configured workspaceRoots: "+candidate);
  return real;
}

function auth(req) {
  pruneAuthState();
  if (String(req.headers.authorization || "") === "Bearer " + config.http.token) return true;
  const cookies = String(req.headers.cookie || "");
  const pairs = cookies.split(";").map(x => x.trim());
  return pairs.some(x => {
    if (!x.startsWith(WEB_COOKIE + "=")) return false;
    const token = x.slice((WEB_COOKIE + "=").length);
    return webSessions.has(token);
  });
}
function isLocalRequest(req) {
  const h = String(req.headers.host || "");
  return h.startsWith("127.0.0.1:") || h.startsWith("localhost:");
}
function loginPage() {
  return '<!doctype html><html><meta name="viewport" content="width=device-width,initial-scale=1"><title>LudoMada Commander Login</title><body style="margin:0;background:#071019;color:#edf6ff;font:16px system-ui"><main style="max-width:420px;margin:15vh auto;padding:24px;background:#0e1925;border:1px solid #29465c;border-radius:16px"><h2>⚙ LudoMada Commander</h2><p style="color:#8ea5b8">Remote HTTPS access</p><input id="c" type="password" placeholder="Pairing code" style="width:100%;padding:12px;box-sizing:border-box;background:#09131d;color:white;border:1px solid #29465c;border-radius:9px"><button onclick="go()" style="width:100%;margin-top:9px;padding:12px;border:0;border-radius:9px;background:#b3873b;font-weight:700">Connect</button><p id="e" style="color:#ff6876"></p><script>async function go(){let r=await fetch("/api/login",{method:"POST",headers:{"content-type":"application/json"},body:JSON.stringify({code:document.getElementById("c").value})});if(r.ok)location.href="/";else document.getElementById("e").textContent=(await r.text())||"Access denied"}</script></main></body></html>';
}

function send(res, status, body, type="application/json") {
  res.writeHead(status, {
    "content-type": type,
    "cache-control": "no-store",
    "x-content-type-options": "nosniff",
    "referrer-policy": "no-referrer",
    "content-security-policy": "default-src 'self'; img-src 'self' data: blob:; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'"
  });
  res.end(type === "application/json" ? JSON.stringify(body) : body);
}

async function body(req) {
  return await new Promise((resolve, reject) => {
    let s = "";
    req.on("data", d => {
      s += d;
      if (s.length > 500000) reject(new Error("body too large"));
    });
    req.on("end", () => {
      try { resolve(s ? JSON.parse(s) : {}); }
      catch { reject(new Error("invalid JSON")); }
    });
    req.on("error", reject);
  });
}

async function run(command, cwd, timeoutMs=120000) {
  cwd = projectPath(cwd);
  return await new Promise(resolve => {
    const shell = process.env.SHELL || "/data/data/com.termux/files/usr/bin/bash";
    const child = spawn(command, { cwd, shell, env: process.env });
    let stdout = "", stderr = "", timedOut = false;
    const timer = setTimeout(() => {
      timedOut = true;
      try { child.kill("SIGTERM"); } catch {}
    }, Math.min(Math.max(timeoutMs, 1000), 900000));
    child.stdout.on("data", d => stdout += d);
    child.stderr.on("data", d => stderr += d);
    child.on("close", code => {
      clearTimeout(timer);
      resolve({
        code: code ?? -1,
        timedOut,
        cwd,
        stdout: stdout.slice(-200000),
        stderr: stderr.slice(-200000)
      });
    });
    child.on("error", e => {
      clearTimeout(timer);
      resolve({ code: -1, timedOut, cwd, stdout: "", stderr: e.message });
    });
  });
}

async function runLocal(command, timeoutMs=20000) {
  return await new Promise(resolve => {
    const shell = process.env.SHELL || "/data/data/com.termux/files/usr/bin/bash";
    const child = spawn(command, { cwd: ROOT, shell, env: process.env });
    let stdout = "", stderr = "", timedOut = false;
    const timer = setTimeout(() => {
      timedOut = true;
      try { child.kill("SIGTERM"); } catch {}
    }, Math.min(Math.max(timeoutMs, 1000), 120000));
    child.stdout.on("data", d => stdout += d);
    child.stderr.on("data", d => stderr += d);
    child.on("close", code => {
      clearTimeout(timer);
      resolve({ code: code ?? -1, timedOut, stdout: stdout.slice(-50000), stderr: stderr.slice(-50000) });
    });
    child.on("error", e => {
      clearTimeout(timer);
      resolve({ code: -1, timedOut, stdout: "", stderr: e.message });
    });
  });
}

async function projects() {
  const out = [];
  async function scan(dir, depth) {
    if (depth > 3 || out.length >= 100) return;
    let es = [];
    try { es = await fs.readdir(dir, { withFileTypes: true }); } catch { return; }
    for (const e of es) {
      if (!e.isDirectory() || [".git", "node_modules", "build", ".gradle"].includes(e.name)) continue;
      const full = path.join(dir, e.name);
      if (await fs.stat(path.join(full, ".git")).catch(() => null)) out.push(full);
      else await scan(full, depth + 1);
    }
  }
  for (const r of config.workspaceRoots) await scan(path.resolve(r), 0);
  return out;
}

async function getMcpHealth() {
  try {
    const r = await fetch(HEALTH_URL, { signal: AbortSignal.timeout(5000) });
    return await r.json();
  } catch (e) {
    return { live: false, ready: false, error: String(e.message || e) };
  }
}

async function mcpPost(payload, sessionId="") {
  const headers = {
    authorization: "Bearer " + config.http.token,
    "content-type": "application/json",
    accept: "application/json, text/event-stream"
  };
  if (sessionId) headers["mcp-session-id"] = sessionId;
  const r = await fetch(MCP_URL, {
    method: "POST",
    headers,
    body: JSON.stringify(payload),
    signal: AbortSignal.timeout(10000)
  });
  const session = r.headers.get("mcp-session-id") || "";
  const type = r.headers.get("content-type") || "";
  const raw = await r.text();
  let parsed = null;
  if (raw) {
    if (type.includes("application/json")) {
      try { parsed = JSON.parse(raw); } catch {}
    } else {
      const line = raw.split("\n").find(x => x.startsWith("data:"));
      if (line) {
        try { parsed = JSON.parse(line.slice(5).trim()); } catch {}
      }
    }
  }
  return { status: r.status, ok: r.ok, sessionId: session || sessionId, type, raw: raw.slice(-50000), parsed };
}

async function listMcpTools() {
  const initPayload = {
    jsonrpc: "2.0",
    id: 1,
    method: "initialize",
    params: {
      protocolVersion: "2025-06-18",
      capabilities: {},
      clientInfo: { name: "ludomada-commander-web", version: "0.2.0" }
    }
  };
  let init = await mcpPost(initPayload);
  if (!init.ok && init.status >= 400) {
    init = await mcpPost({
      ...initPayload,
      params: { ...initPayload.params, protocolVersion: "2024-11-05" }
    });
  }
  if (!init.ok) return { ok: false, initialize: init };
  const tools = await mcpPost({
    jsonrpc: "2.0",
    id: 2,
    method: "tools/list",
    params: {}
  }, init.sessionId);
  return {
    ok: tools.ok,
    sessionId: tools.sessionId,
    initialize: init.parsed,
    tools: tools.parsed?.result?.tools || [],
    raw: tools.raw
  };
}


async function listDirectory(dir, depth=0, maxItems=250) {
  const out=[];
  async function walk(cur, level, rel) {
    if (level > depth || out.length >= maxItems) return;
    let entries=[]; try { entries=await fs.readdir(cur,{withFileTypes:true}); } catch { throw new Error("Cannot read directory: "+cur); }
    entries.sort((a,b)=>a.name.localeCompare(b.name));
    for (const e of entries) {
      if (["node_modules",".git",".gradle","build"].includes(e.name)) continue;
      const full=path.join(cur,e.name), rp=path.join(rel,e.name);
      let size=0,mtime=null; try { const st=await fs.stat(full); size=st.size;mtime=st.mtime.toISOString(); } catch {}
      out.push({name:e.name,path:rp,type:e.isDirectory()?"dir":"file",size,mtime});
      if (e.isDirectory() && level < depth) await walk(full,level+1,rp);
      if (out.length >= maxItems) break;
    }
  }
  await walk(dir,0,"."); return out;
}
async function gh(args, project) {
  return await new Promise(resolve=>{
    const child=spawn("gh",args,{cwd:projectPath(project),shell:false,env:process.env});
    let stdout="",stderr=""; child.stdout.on("data",d=>stdout+=d); child.stderr.on("data",d=>stderr+=d);
    child.on("close",code=>resolve({code:code??-1,stdout:stdout.slice(-100000),stderr:stderr.slice(-30000)}));
    child.on("error",e=>resolve({code:-1,stdout:"",stderr:e.message}));
  });
}
async function psList() {
  const r=await runLocal("ps -A -o pid=,ppid=,user=,state=,etime=,args=",10000);
  return (r.stdout||"").split("\n").map(x=>x.trim()).filter(Boolean).map(line=>{
    const m=line.match(/^(\d+)\s+(\d+)\s+(\S+)\s+(\S+)\s+(\S+)\s+(.*)$/);
    return m?{pid:Number(m[1]),ppid:Number(m[2]),user:m[3],state:m[4],etime:m[5],command:m[6]}:{raw:line};
  }).slice(0,500);
}

const server = http.createServer(async (req, res) => {
  try {
    const u = new URL(req.url || "/", "http://127.0.0.1");

    if (req.method === "GET" && u.pathname === "/") {
      if (!isLocalRequest(req) && !auth(req)) return send(res,401,loginPage(),"text/html; charset=utf-8");
      pruneAuthState();
      const cookies = String(req.headers.cookie || "").split(";").map(x => x.trim());
      let session = cookies.map(x => x.startsWith(WEB_COOKIE + "=") ? x.slice((WEB_COOKIE + "=").length) : "").find(x => x && webSessions.has(x));
      if (!session) session = crypto.randomBytes(24).toString("hex");
      webSessions.set(session, Date.now() + WEB_SESSION_TTL_MS);
      const ck=WEB_COOKIE+"="+session+"; Max-Age=28800; HttpOnly; SameSite=Strict; Path=/"+(!isLocalRequest(req)?"; Secure":"");
      res.writeHead(200,{"content-type":"text/html; charset=utf-8","cache-control":"no-store","content-security-policy":"default-src 'self'; img-src 'self' data: blob:; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'","set-cookie":ck});
      res.end(await fs.readFile(UI,"utf8"));
      return;
    }
    if (req.method === "POST" && u.pathname === "/api/login") {
      if (!loginAllowed(req)) return send(res,429,{error:"too many login attempts; retry later"});
      const x=await body(req);
      if(!constantTimeEqual(x.code||"", config.web?.pairingCode||"")) return send(res,401,{error:"invalid pairing code"});
      const session=crypto.randomBytes(24).toString("hex");
      webSessions.set(session, Date.now() + WEB_SESSION_TTL_MS);
      res.writeHead(200,{"content-type":"application/json","cache-control":"no-store","set-cookie":WEB_COOKIE+"="+session+"; Max-Age=28800; HttpOnly; SameSite=Strict; Secure; Path=/"});
      res.end(JSON.stringify({ok:true}));
      return;
    }
    if (req.method === "GET" && u.pathname === "/health") {
      return send(res, 200, { ok: true, service: "ludomada-commander-web", port: PORT });
    }
    if (!auth(req)) return send(res, 401, { error: "unauthorized" });

    if (req.method === "GET" && u.pathname === "/api/config") {
      return send(res, 200, {
        defaultProject: config.defaultProject,
        workspaceRoots: config.workspaceRoots,
        mcp: MCP_URL,
        ui: "http://127.0.0.1:" + PORT
      });
    }
    if (req.method === "GET" && u.pathname === "/api/projects") {
      return send(res, 200, { projects: await projects() });
    }
    if (req.method === "GET" && u.pathname === "/api/remote/status") {
      const pid=Number((await fs.readFile(WEB_TUNNEL_PID,"utf8").catch(()=>"" )).trim()||0);
      let running=false; if(pid>1){try{process.kill(pid,0);running=true}catch{}}
      const url=(await fs.readFile(WEB_TUNNEL_URL,"utf8").catch(()=> "")).trim();
      const provider=(await fs.readFile(WEB_TUNNEL_PROVIDER,"utf8").catch(()=> "localtunnel")).trim();
      return send(res,200,{running,pid,url:url||"pending",provider,pairingRequired:true,pairingCode:isLocalRequest(req)?config.web?.pairingCode:undefined});
    }
    if (req.method === "POST" && u.pathname === "/api/remote/start") {
      const pid=Number((await fs.readFile(WEB_TUNNEL_PID,"utf8").catch(()=>"")).trim()||0);
      if(pid>1){
        try{
          process.kill(pid,0);
          const url=(await fs.readFile(WEB_TUNNEL_URL,"utf8").catch(()=>"")).trim();
          return send(res,200,{running:true,pid,url:url||"pending",provider:"localtunnel"});
        }catch{}
      }
      await fs.mkdir(path.dirname(WEB_TUNNEL_LOG),{recursive:true});
      const fd=fsSync.openSync(WEB_TUNNEL_LOG,"w");
      const wrapper=spawn(process.execPath,[WEB_TUNNEL_SCRIPT],{cwd:ROOT,env:process.env,detached:true,stdio:["ignore",fd,fd]});
      wrapper.unref();
      fsSync.closeSync(fd);
      await fs.writeFile(WEB_TUNNEL_PID,String(wrapper.pid)+"\n");
      await new Promise(r=>setTimeout(r,5000));
      const url=(await fs.readFile(WEB_TUNNEL_URL,"utf8").catch(()=>"")).trim();
      return send(res,200,{running:true,pid:wrapper.pid,url:url||"pending",provider:"localtunnel"});
    }
    if (req.method === "POST" && u.pathname === "/api/remote/stop") {
      const pid=Number((await fs.readFile(WEB_TUNNEL_PID,"utf8").catch(()=>"")).trim()||0);
      let ok=false;
      if(pid>1){try{process.kill(pid,"SIGTERM");ok=true}catch{}}
      await fs.rm(WEB_TUNNEL_PID,{force:true});
      await fs.rm(WEB_TUNNEL_URL,{force:true});
      await fs.rm(WEB_TUNNEL_PROVIDER,{force:true});
      return send(res,200,{ok,pid});
    }

    if (req.method === "GET" && u.pathname === "/api/files") {
      const p=resolveProject(u.searchParams.get("project"));
      const rel=u.searchParams.get("path") || ".";
      const target=resolveSafe(rel,p);
      const st=await fs.stat(target);
      if (!st.isDirectory()) return send(res,400,{error:"path is not a directory"});
      return send(res,200,{path:target,entries:await listDirectory(target,Number(u.searchParams.get("depth")||0))});
    }
    if (req.method === "GET" && u.pathname === "/api/file") {
      const target=resolveSafe(u.searchParams.get("path")||".",u.searchParams.get("project"));
      const st=await fs.stat(target);
      if (!st.isFile()) return send(res,400,{error:"path is not a file"});
      if (st.size > 800000) return send(res,413,{error:"file too large for web editor"});
      return send(res,200,{path:target,size:st.size,content:await fs.readFile(target,"utf8")});
    }
    if (req.method === "POST" && u.pathname === "/api/file") {
      const x=await body(req);
      if (typeof x.content !== "string") return send(res,400,{error:"content required"});
      if (x.content.length > 800000) return send(res,413,{error:"file too large"});
      const mcp = await mcpPost({
        jsonrpc:"2.0",
        id:Date.now(),
        method:"tools/call",
        params:{name:"write_file",arguments:{path:String(x.path||""),project:x.project,content:x.content}}
      });
      return send(res,mcp.status,mcp.parsed || {ok:mcp.ok,raw:mcp.raw});
    }
    if (req.method === "GET" && u.pathname === "/api/processes") {
      return send(res,200,{processes:await psList()});
    }
    if (req.method === "POST" && u.pathname === "/api/process/kill") {
      const x=await body(req); const pid=Number(x.pid);
      if (!Number.isInteger(pid)||pid<=1) return send(res,400,{error:"valid pid required"});
      try { process.kill(pid,String(x.signal||"SIGTERM")); return send(res,200,{ok:true,pid,signal:x.signal||"SIGTERM"}); }
      catch(e){ return send(res,200,{ok:false,pid,error:String(e.message||e)}); }
    }
    if (req.method === "GET" && u.pathname === "/api/android/devices") {
      return send(res,200,await runLocal("adb devices -l",12000));
    }
    if (req.method === "GET" && u.pathname === "/api/android/logcat") {
      const lines=Math.min(Math.max(Number(u.searchParams.get("lines")||250),20),1200);
      const filter=String(u.searchParams.get("filter")||"").replace(/[^A-Za-z0-9_.:-]/g,"");
      const cmd="logcat -d -t "+lines+(filter ? " "+filter+":V *:S" : "");
      return send(res,200,await runLocal(cmd,15000));
    }
    if (req.method === "GET" && u.pathname === "/api/android/screenshot") {
      const temp=path.join(HOME,".ludomada-commander","commander-screen.png");
      const cmd="if command -v adb >/dev/null 2>&1 && adb get-state >/dev/null 2>&1; then adb exec-out screencap -p > "+JSON.stringify(temp)+"; else if command -v screencap >/dev/null 2>&1; then screencap -p > "+JSON.stringify(temp)+"; else exit 127; fi; fi";
      const r=await runLocal(cmd,12000);
      if(r.code!==0) return send(res,503,{error:(r.stderr||"No Android screen capture available").trim(),stdout:r.stdout});
      const data=await fs.readFile(temp).catch(()=>null);
      if(!data) return send(res,503,{error:"screenshot not produced"});
      res.writeHead(200,{"content-type":"image/png","cache-control":"no-store","x-content-type-options":"nosniff"});
      res.end(data); return;
    }
    if (req.method === "GET" && u.pathname === "/api/github/prs") {
      return send(res,200,await gh(["pr","list","--repo","pascalnanousympathie-alt/LudoMada","--limit","30","--json","number,title,state,isDraft,headRefName,baseRefName,mergeStateStatus,url"],config.defaultProject));
    }
    if (req.method === "GET" && u.pathname === "/api/github/issues") {
      return send(res,200,await gh(["issue","list","--repo","pascalnanousympathie-alt/LudoMada","--limit","30","--state","open","--json","number,title,state,author,url,labels"],config.defaultProject));
    }
    if (req.method === "GET" && u.pathname === "/api/github/runs") {
      return send(res,200,await gh(["run","list","--repo","pascalnanousympathie-alt/LudoMada","--limit","20","--json","databaseId,name,status,conclusion,headBranch,event,url,createdAt"],config.defaultProject));
    }
    if (req.method === "POST" && u.pathname === "/api/github/comment") {
      const x=await body(req); const issue=Number(x.number); const comment=String(x.body||"").trim();
      if(!Number.isInteger(issue)||issue<1||!comment) return send(res,400,{error:"issue number and body required"});
      return send(res,200,await gh(["issue","comment",String(issue),"--repo","pascalnanousympathie-alt/LudoMada","--body",comment.slice(0,6500)],config.defaultProject));
    }
    if (req.method === "POST" && u.pathname === "/api/github/checkout") {
      const x=await body(req); const ref=String(x.ref||"").trim();
      if(!/^[A-Za-z0-9._\/-]+$/.test(ref)) return send(res,400,{error:"invalid git ref"});
      return send(res,200,await gh(["pr","checkout",ref,"--repo","pascalnanousympathie-alt/LudoMada"],x.project||config.defaultProject));
    }
    if (req.method === "GET" && u.pathname === "/api/overview") {
      const p = projectPath(u.searchParams.get("project") || config.defaultProject);
      const [mcp, tunnel, git] = await Promise.all([
        getMcpHealth(),
        runLocal("./tunnel/status.sh", 10000),
        run("git status --short --branch; printf '\\nHEAD='; git rev-parse --short HEAD", p, 10000)
      ]);
      return send(res, 200, { mcp, tunnel, git, project: p, at: new Date().toISOString() });
    }
    if (req.method === "GET" && u.pathname === "/api/tools") {
      return send(res, 200, await listMcpTools());
    }
    if (req.method === "GET" && u.pathname === "/api/status") {
      const p = projectPath(u.searchParams.get("project") || config.defaultProject);
      return send(res, 200, await run(
        "git status --short --branch; printf '\\nHEAD='; git rev-parse --short HEAD; printf '\\nNODE='; node --version; printf '\\nGIT='; git --version",
        p, 20000
      ));
    }
    if (req.method === "POST" && u.pathname === "/api/exec") {
      const x = await body(req);
      if (!x.command) return send(res, 400, { error: "command required" });
      return send(res, 200, await run(x.command, x.project, x.timeoutMs));
    }
    if (req.method === "POST" && u.pathname === "/api/test") {
      const x = await body(req);
      const p = projectPath(x.project);
      const hasGradle = await fs.stat(path.join(p, "gradlew")).catch(() => null);
      const hasNode = await fs.stat(path.join(p, "package.json")).catch(() => null);
      const cmd = x.command || (hasGradle ? "chmod +x ./gradlew && ./gradlew test" : hasNode ? "npm test" : "git status --short");
      return send(res, 200, await run(cmd, p, x.timeoutMs || 600000));
    }
    if (req.method === "POST" && u.pathname === "/api/build") {
      const x = await body(req);
      const p = projectPath(x.project);
      const has = await fs.stat(path.join(p, "gradlew")).catch(() => null);
      const cmd = x.command || (has ? "chmod +x ./gradlew && ./gradlew " + (x.task || "assembleDebug") : "npm run build");
      const r = await run(cmd, p, x.timeoutMs || 900000);
      const aps = [];
      async function walk(d, depth) {
        if (depth > 5 || aps.length >= 20) return;
        let es = [];
        try { es = await fs.readdir(d, { withFileTypes: true }); } catch { return; }
        for (const e of es) {
          if (["node_modules", ".git"].includes(e.name)) continue;
          const f = path.join(d, e.name);
          if (e.isDirectory()) await walk(f, depth + 1);
          else if (e.name.endsWith(".apk")) aps.push(f);
        }
      }
      await walk(p, 0);
      r.apkCandidates = aps;
      return send(res, 200, r);
    }
    if (req.method === "POST" && u.pathname === "/api/android/install") {
      const x=await body(req); const apk=resolveSafe(String(x.apk||""),x.project);
      const st=await fs.stat(apk).catch(()=>null);
      if(!st?.isFile() || !apk.endsWith(".apk")) return send(res,400,{error:"valid APK required"});
      const r=await runLocal("adb get-state >/dev/null 2>&1 && adb install -r "+JSON.stringify(apk),120000);
      return send(res,r.code===0?200:503,r);
    }
    if (req.method === "POST" && u.pathname === "/api/android/launch") {
      const x=await body(req); const pkg=String(x.package||"").trim();
      if(!/^[A-Za-z0-9_.]+$/.test(pkg)) return send(res,400,{error:"invalid package name"});
      const r=await runLocal("adb get-state >/dev/null 2>&1 && adb shell monkey -p "+pkg+" 1",30000);
      return send(res,r.code===0?200:503,r);
    }
    if (req.method === "POST" && u.pathname === "/api/github/merge") {
      const x=await body(req); const n=Number(x.number); const strategy=String(x.strategy||"squash");
      if(!Number.isInteger(n)||!["merge","squash","rebase"].includes(strategy)) return send(res,400,{error:"valid PR and strategy required"});
      return send(res,200,await gh(["pr","merge",String(n),"--repo","pascalnanousympathie-alt/LudoMada","--"+strategy],config.defaultProject));
    }
    if (req.method === "POST" && u.pathname === "/api/github/review") {
      const x=await body(req); const n=Number(x.number); const action=String(x.action||"comment"); const bodyText=String(x.body||"");
      if(!Number.isInteger(n)||!["approve","request-changes","comment"].includes(action)) return send(res,400,{error:"valid review action required"});
      const flag=action==="approve"?"--approve":action==="request-changes"?"--request-changes":"--comment";
      const args=["pr","review",String(n),"--repo","pascalnanousympathie-alt/LudoMada",flag]; if(bodyText) args.push("--body",bodyText.slice(0,6500));
      return send(res,200,await gh(args,config.defaultProject));
    }
    if (req.method === "POST" && u.pathname === "/api/github/run") {
      const x=await body(req); const id=Number(x.id); const action=String(x.action||"view");
      if(!Number.isInteger(id)||!["view","rerun","cancel"].includes(action)) return send(res,400,{error:"valid run action required"});
      const args=action==="view"?["run","view",String(id),"--repo","pascalnanousympathie-alt/LudoMada"]:["run",action,String(id),"--repo","pascalnanousympathie-alt/LudoMada"];
      return send(res,200,await gh(args,config.defaultProject));
    }
    if (req.method === "POST" && u.pathname === "/api/tunnel") {
      const x = await body(req);
      const action = String(x.action || "status");
      const script = action === "start" ? "bash scripts/ludomada-connect.sh"
        : action === "stop" ? "bash scripts/ludomada-stop.sh"
        : action === "restart" ? "bash scripts/ludomada-stop.sh && sleep 1 && bash scripts/ludomada-connect.sh"
        : "bash scripts/ludomada-status.sh";
      return send(res, 200, await run(script, config.defaultProject, 60000));
    }
    if (req.method === "POST" && u.pathname === "/api/git-commit") {
      const x = await body(req);
      const paths = Array.isArray(x.paths) ? x.paths.filter(v => typeof v === "string" && v.trim()).slice(0,100) : [];
      if (!paths.length) return send(res,400,{error:"explicit paths required; refusing implicit git add -A"});
      const msg = String(x.message || "Commander checkpoint").replaceAll("'", "'\\''");
      const quoted = paths.map(v => "'" + v.replaceAll("'","'\\''") + "'").join(" ");
      return send(res, 200, await run("git add -- " + quoted + " && git commit -m '" + msg + "'", x.project, 60000));
    }
    return send(res, 404, { error: "not found" });
  } catch (e) {
    return send(res, 500, { error: String(e.message || e) });
  }
});

server.listen(PORT, "127.0.0.1", () => {
  console.log("Commander Web UI: http://127.0.0.1:" + PORT);
});
