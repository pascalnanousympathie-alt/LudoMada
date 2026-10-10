#!/usr/bin/env node
import assert from "node:assert/strict";

const BASE = new URL(process.env.MADA_PUBLIC_BASE_URL || "https://mada-commander-public-mcp.vercel.app");
const expectReady = process.argv.includes("--expect-ready");
assert.equal(BASE.protocol, "https:", "production smoke requires HTTPS");
assert.equal(BASE.pathname, "/", "base URL must be an origin, not a path");
const failures = [];
const passes = [];
async function check(label, fn) {
  try { await fn(); passes.push(label); console.log("PASS " + label); }
  catch (error) { failures.push(label + ": " + String(error?.message || error)); console.error("FAIL " + label + ": " + String(error?.message || error)); }
}
async function get(path) {
  return fetch(new URL(path, BASE), { redirect: "manual", cache: "no-store" });
}
async function checkHeaders(response, route) {
  for (const key of ["content-security-policy","x-content-type-options","referrer-policy","x-frame-options","permissions-policy","cache-control","strict-transport-security"]) {
    assert.ok(response.headers.get(key), route + " missing header " + key);
  }
}

await check("homepage returns HTTP 200 with fail-closed startup defaults", async () => {
  const response = await get("/");
  assert.equal(response.status, 200);
  const html = await response.text();
  assert.match(html, /id="readinessBadge">NOT READY<\/span>/);
  assert.match(html, /Sign-in is disabled until setup is complete/);
  assert.match(html, /aria-disabled="true"/);
  assert.match(html, /login\.tabIndex=ready\?0:-1/);
  await checkHeaders(response, "/");
});
for (const path of ["/privacy.html","/terms.html","/support.html"]) {
  await check(path + " returns HTTP 200 with baseline security headers", async () => {
    const response = await get(path);
    assert.equal(response.status, 200);
    await checkHeaders(response, path);
  });
}
for (const path of ["/.well-known/oauth-protected-resource","/.well-known/oauth-authorization-server"]) {
  await check(path + " returns valid JSON and baseline security headers", async () => {
    const response = await get(path);
    assert.equal(response.status, 200);
    await checkHeaders(response, path);
    const data = await response.json();
    assert.ok(data.issuer || data.resource);
    assert.equal((data.issuer || data.resource), BASE.origin);
  });
}
await check("health matches expected setup state", async () => {
  const response = await get("/api/health");
  await checkHeaders(response, "/api/health");
  const health = await response.json();
  if (expectReady) {
    assert.equal(response.status, 200);
    assert.equal(health.ready, true);
    assert.ok(Object.values(health.checks || {}).every(Boolean));
  } else {
    assert.equal(response.status, 503);
    assert.equal(health.ready, false);
    assert.ok(Object.values(health.checks || {}).some(value => value === false), "not-ready health must name at least one missing prerequisite");
  }
});
await check("domain challenge matches expected configuration state", async () => {
  const response = await get("/.well-known/openai-apps-challenge");
  await checkHeaders(response, "domain challenge");
  const body = (await response.text()).trim();
  if (expectReady) {
    assert.equal(response.status, 200);
    assert.ok(body.length > 0);
    assert.ok(!body.startsWith("{"), "challenge should be served as plain text");
  } else {
    assert.equal(response.status, 503);
    assert.match(body, /domain_challenge_not_configured/);
  }
});
const rpc = async (message) => fetch(new URL("/mcp", BASE), {
  method: "POST",
  headers: { "content-type": "application/json", accept: "application/json, text/event-stream" },
  body: JSON.stringify(message), cache: "no-store"
});
await check("MCP initialize succeeds", async () => {
  const response = await rpc({jsonrpc:"2.0",id:1,method:"initialize",params:{protocolVersion:"2025-06-18",capabilities:{},clientInfo:{name:"mada-production-smoke",version:"1.0"}}});
  assert.equal(response.status, 200);
  await checkHeaders(response, "/mcp");
  const body = await response.json();
  assert.equal(body.result?.serverInfo?.name, "mada-commander-public");
});
await check("MCP lists the five least-privilege tools with output schemas", async () => {
  const response = await rpc({jsonrpc:"2.0",id:2,method:"tools/list",params:{}});
  assert.equal(response.status, 200);
  const body = await response.json();
  const want = ["mada_agent_status","mada_list_projects","mada_run_diagnostics","mada_job_status","mada_connection_help"];
  assert.deepEqual(body.result?.tools?.map(t=>t.name), want);
  assert.ok(body.result.tools.every(t=>t.outputSchema?.type === "object"));
  assert.ok(body.result.tools.every(t=>["readOnlyHint","openWorldHint","destructiveHint"].every(k=>typeof t.annotations?.[k] === "boolean")));
});
await check("unauthenticated MCP tool calls are denied with HTTP 401", async () => {
  const response = await rpc({jsonrpc:"2.0",id:3,method:"tools/call",params:{name:"mada_list_projects",arguments:{}}});
  assert.equal(response.status, 401);
  const body = await response.json();
  assert.equal(body.error?.code, -32001);
});
await check("Google sign-in state matches service readiness", async () => {
  const response = await get("/oauth/google/start?purpose=dashboard");
  if (expectReady) {
    assert.ok([302,303,307,308].includes(response.status), "expected Google OAuth redirect, got " + response.status);
    const location = response.headers.get("location") || "";
    assert.match(location, /^https:\/\/accounts\.google\.com\//);
  } else {
    assert.equal(response.status, 503);
    assert.match(await response.text(), /service_not_configured/);
  }
});
console.log("\nPRODUCTION SMOKE SUMMARY: " + passes.length + " passed, " + failures.length + " failed; mode=" + (expectReady ? "ready" : "fail-closed"));
if (failures.length) process.exitCode = 1;
