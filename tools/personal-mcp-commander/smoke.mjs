import { spawn } from "node:child_process";

const child = spawn(process.execPath, ["server.mjs"], { cwd: new URL(".", import.meta.url), stdio: ["pipe","pipe","pipe"] });
let buffer = "";
let stderr = "";
const responses = new Map();
child.stderr.on("data", d => { stderr += d.toString(); });
child.stdout.on("data", d => {
  buffer += d.toString();
  for (;;) {
    const nl = buffer.indexOf("\n");
    if (nl < 0) break;
    const line = buffer.slice(0, nl).trim();
    buffer = buffer.slice(nl + 1);
    if (!line) continue;
    try { const msg = JSON.parse(line); if (msg.id != null) responses.set(msg.id, msg); } catch {}
  }
});

function send(id, method, params) {
  child.stdin.write(JSON.stringify({ jsonrpc: "2.0", id, method, params }) + "\n");
}
function waitFor(id, timeout=10000) {
  const start = Date.now();
  return new Promise((resolve, reject) => {
    const tick = () => {
      if (responses.has(id)) return resolve(responses.get(id));
      if (Date.now() - start > timeout) return reject(new Error("timeout waiting for response "+id+" stderr="+stderr.slice(-2000)));
      setTimeout(tick, 20);
    };
    tick();
  });
}

try {
  send(1, "initialize", { protocolVersion: "2025-06-18", capabilities: {}, clientInfo: { name: "ludomada-smoke", version: "1.0.0" } });
  const init = await waitFor(1);
  if (init?.result?.serverInfo?.version !== "1.0.0") throw new Error("bad server version");
  send(2, "notifications/initialized", {});
  send(3, "tools/list", {});
  const listed = await waitFor(3);
  const tools = listed?.result?.tools || [];
  const names = new Set(tools.map(t => t.name));
  const required = ["health_check","audit_log","snapshot_file","rollback_snapshot","developer_job_start","developer_job_status","browser_status","browser_navigate","browser_evaluate","browser_screenshot"];
  const missing = required.filter(x => !names.has(x));
  if (missing.length) throw new Error("missing tools: "+missing.join(","));
  send(4, "tools/call", { name: "health_check", arguments: { project: process.env.LUDOMADA_PROJECT_DIR || new URL("../../", import.meta.url).pathname.replace(/\/$/,"") } });
  const health = await waitFor(4, 30000);
  if (!health?.result?.structuredContent?.healthy) throw new Error("health_check not healthy: "+JSON.stringify(health?.result||{}));
  console.log(JSON.stringify({ ok:true, server:init.result.serverInfo, toolCount:tools.length, health:health.result.structuredContent.checks }));
  child.kill("SIGTERM");
} catch (e) {
  console.error("SMOKE_FAIL:", e.message);
  try { child.kill("SIGTERM"); } catch {}
  process.exit(1);
}