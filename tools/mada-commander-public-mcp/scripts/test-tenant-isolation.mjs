#!/usr/bin/env node
import crypto from "node:crypto";
import assert from "node:assert/strict";
import http from "node:http";
import { query, closePool, databaseConfigured } from "../lib/db.mjs";
import { hashToken, randomToken, REQUIRED_SCOPE } from "../lib/security.mjs";
import { handleRequest } from "../api/router.mjs";

if (!databaseConfigured) {
  console.error("Refusing tenant-isolation test: MADA_COMMANDER_DATABASE_URL is not configured.");
  process.exit(2);
}
if (process.env.MADA_COMMANDER_CONFIRM_TENANT_TEST !== "YES") {
  console.error("Refusing database writes without confirmation. Set MADA_COMMANDER_CONFIRM_TENANT_TEST=YES only on the dedicated Mada Commander test database.");
  process.exit(2);
}
if (process.env.NODE_ENV === "production" && process.env.MADA_COMMANDER_ALLOW_PRODUCTION_TEST !== "YES") {
  console.error("Refusing to run destructive integration tests against production. Use an isolated test database or explicitly review the production-test override.");
  process.exit(2);
}

const oldBase = process.env.MADA_PUBLIC_BASE_URL;
const oldMode = process.env.NODE_ENV;
const suffix = crypto.randomUUID();
const marker = "mcp-tenant-test:" + suffix;
const userA = crypto.randomUUID();
const userB = crypto.randomUUID();
const agentA = crypto.randomUUID();
const agentB = crypto.randomUUID();
const tokenA = randomToken(32);
const tokenB = randomToken(32);
const accessA = randomToken(32);
const accessB = randomToken(32);
const sessionA = randomToken(32);
const sessionB = randomToken(32);
const googleSubA = marker + ":A";
const googleSubB = marker + ":B";
const rateKeys = [
  "mcp-tool:" + userA, "mcp-tool:" + userB,
  "pair-code:" + userA, "pair-code:" + userB,
  "account-delete:" + userA, "account-delete:" + userB,
  "agent-heartbeat:" + agentA, "agent-heartbeat:" + agentB,
  "agent-poll:" + agentA, "agent-poll:" + agentB,
  "agent-complete:" + agentA, "agent-complete:" + agentB
];
let server;
let base = "";
let seeded = false;
const passed = [];

function pass(name) {
  passed.push(name);
  console.log("PASS " + name);
}

async function rpc(token, name, args = {}) {
  const response = await fetch(base + "/mcp", {
    method: "POST",
    headers: {
      authorization: "Bearer " + token,
      "content-type": "application/json",
      accept: "application/json, text/event-stream"
    },
    body: JSON.stringify({ jsonrpc: "2.0", id: crypto.randomUUID(), method: "tools/call", params: { name, arguments: args } }),
    signal: AbortSignal.timeout(5000)
  });
  const body = await response.json();
  return { status: response.status, body };
}

async function localApi(route, tokenOrCookie, payload, kind = "agent") {
  const headers = { "content-type": "application/json", origin: base };
  if (kind === "agent") headers.authorization = "Bearer " + tokenOrCookie;
  if (kind === "cookie") headers.cookie = "mada_commander_session=" + encodeURIComponent(tokenOrCookie);
  const response = await fetch(base + route, {
    method: "POST",
    headers,
    body: JSON.stringify(payload),
    signal: AbortSignal.timeout(5000)
  });
  const body = await response.json().catch(() => ({}));
  return { status: response.status, body };
}

try {
  // Confirm the dedicated schema exists before inserting any test records.
  const tables = await query(
    "SELECT to_regclass('public.mada_public_users') AS users, " +
    "to_regclass('public.mada_public_agents') AS agents, " +
    "to_regclass('public.mada_public_projects') AS projects, " +
    "to_regclass('public.mada_public_jobs') AS jobs, " +
    "to_regclass('public.mada_oauth_access_tokens') AS access_tokens, " +
    "to_regclass('public.mada_web_sessions') AS sessions"
  );
  assert.ok(Object.values(tables.rows[0] || {}).every(Boolean), "dedicated schema is incomplete");
  pass("dedicated schema is present");

  process.env.NODE_ENV = "test";
  server = http.createServer((req, res) => {
    handleRequest(req, res).catch(() => {
      if (!res.headersSent) { res.writeHead(500); res.end(); } else res.end();
    });
  });
  await new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", resolve);
  });
  const address = server.address();
  base = "http://127.0.0.1:" + address.port;
  process.env.MADA_PUBLIC_BASE_URL = base;

  await query(
    "INSERT INTO mada_public_users (id, google_sub, email, display_name, email_verified) VALUES " +
    "($1,$2,$3,$4,true),($5,$6,$7,$8,true)",
    [userA, googleSubA, "tenant-a-" + suffix + "@example.invalid", "Tenant test A",
     userB, googleSubB, "tenant-b-" + suffix + "@example.invalid", "Tenant test B"]
  );
  seeded = true;
  await query(
    "INSERT INTO mada_public_agents (id,user_id,name,token_hash) VALUES ($1,$2,$3,$4),($5,$6,$7,$8)",
    [agentA, userA, "Agent A test", hashToken(tokenA), agentB, userB, "Agent B test", hashToken(tokenB)]
  );
  await query(
    "INSERT INTO mada_public_projects (agent_id,project_id,label,branch,head) VALUES ($1,'shared','Project A','main','a1b2c3d'),($2,'shared','Project B','dev','d4e5f6a')",
    [agentA, agentB]
  );
  await query(
    "INSERT INTO mada_web_sessions (token_hash,user_id,expires_at) VALUES ($1,$2,now()+interval '1 day'),($3,$4,now()+interval '1 day')",
    [hashToken(sessionA), userA, hashToken(sessionB), userB]
  );
  await query(
    "INSERT INTO mada_oauth_access_tokens (token_hash,user_id,client_id,scope,resource,expires_at) VALUES ($1,$2,$3,$4,$5,now()+interval '10 minutes'),($6,$7,$8,$9,$10,now()+interval '10 minutes')",
    [hashToken(accessA), userA, "tenant-test-client", [REQUIRED_SCOPE], base,
     hashToken(accessB), userB, "tenant-test-client", [REQUIRED_SCOPE], base]
  );
  seeded = true;
  pass("isolated test users, agents, sessions, projects and scoped access tokens created");

  const agentsA = await rpc(accessA, "mada_agent_status");
  const agentsB = await rpc(accessB, "mada_agent_status");
  assert.equal(agentsA.status, 200);
  assert.deepEqual(agentsA.body.result.structuredContent.agents.map(a => a.name), ["Agent A test"]);
  assert.deepEqual(agentsB.body.result.structuredContent.agents.map(a => a.name), ["Agent B test"]);
  pass("agent listing is isolated by account");

  const projectsA = await rpc(accessA, "mada_list_projects");
  const projectsB = await rpc(accessB, "mada_list_projects");
  assert.deepEqual(projectsA.body.result.structuredContent.projects.map(p => p.name), ["Project A"]);
  assert.deepEqual(projectsB.body.result.structuredContent.projects.map(p => p.name), ["Project B"]);
  const projectA = projectsA.body.result.structuredContent.projects[0];
  pass("same project alias cannot expose another account's project");

  const crossJob = await rpc(accessB, "mada_run_diagnostics", {
    projectId: "shared", agentId: projectA.agentId, profile: "git_status"
  });
  assert.equal(crossJob.body.result.isError, true);
  assert.match(crossJob.body.result.content[0].text, /Project not found in this account/);
  pass("account B cannot queue a job on account A's agent");

  const ownJob = await rpc(accessA, "mada_run_diagnostics", {
    projectId: "shared", agentId: projectA.agentId, profile: "git_status"
  });
  assert.equal(ownJob.body.result.isError, false);
  assert.equal(ownJob.body.result.structuredContent.status, "queued");
  const jobId = ownJob.body.result.structuredContent.jobId;
  assert.match(jobId, /^[0-9a-f-]{36}$/i);
  const jobRow = await query("SELECT user_id,agent_id,status FROM mada_public_jobs WHERE id=$1", [jobId]);
  assert.equal(jobRow.rows[0].user_id, userA);
  assert.equal(jobRow.rows[0].agent_id, agentA);
  pass("queued diagnostic job is bound to the requesting user's own agent in the database");

  const foreignJob = await rpc(accessB, "mada_job_status", { jobId });
  assert.equal(foreignJob.body.result.isError, true);
  assert.equal(foreignJob.body.result.content[0].text, "Job not found.");
  const bPoll = await localApi("/api/agent/poll", tokenB, {});
  assert.equal(bPoll.status, 200);
  assert.equal(bPoll.body.job, null);
  pass("another account cannot read or poll account A's job");

  const aPoll = await localApi("/api/agent/poll", tokenA, {});
  assert.equal(aPoll.status, 200);
  assert.equal(aPoll.body.job.jobId, jobId);
  assert.equal(aPoll.body.job.profile, "git_status");
  const bComplete = await localApi("/api/agent/complete", tokenB, {
    jobId, status: "succeeded", result: { branch: "fake", head: "deadbeef", dirtyCount: 0 }
  });
  assert.equal(bComplete.status, 404);
  pass("another account's agent cannot complete account A's running job");

  const aComplete = await localApi("/api/agent/complete", tokenA, {
    jobId, status: "succeeded", result: { branch: "main", head: "a1b2c3d", dirtyCount: 0 }
  });
  assert.equal(aComplete.status, 200);
  const aStatus = await rpc(accessA, "mada_job_status", { jobId });
  assert.equal(aStatus.body.result.structuredContent.status, "succeeded");
  assert.equal(aStatus.body.result.structuredContent.result.branch, "main");
  const bStatus = await rpc(accessB, "mada_job_status", { jobId });
  assert.equal(bStatus.body.result.isError, true);
  pass("only the owning agent can complete a job and only its owner can read results");

  const revokeForeign = await localApi("/api/agent/revoke", sessionB, { agentId: agentA }, "cookie");
  assert.equal(revokeForeign.status, 404);
  pass("account B cannot revoke account A's agent");

  const revokeOwn = await localApi("/api/agent/revoke", sessionA, { agentId: agentA }, "cookie");
  assert.equal(revokeOwn.status, 200);
  const staleHeartbeat = await localApi("/api/agent/heartbeat", tokenA, { projects: [] });
  assert.equal(staleHeartbeat.status, 401);
  const visibleAfterRevoke = await rpc(accessA, "mada_list_projects");
  assert.equal(visibleAfterRevoke.body.result.structuredContent.projects.length, 0);
  pass("revocation invalidates the local agent token and hides its projects");

  const deleteAccount = await localApi("/api/account/delete", sessionA, { confirm: "DELETE" }, "cookie");
  assert.equal(deleteAccount.status, 200);
  assert.equal(deleteAccount.body.deleted, true);
  const staleMcp = await rpc(accessA, "mada_agent_status");
  assert.equal(staleMcp.status, 401);
  const deletedRows = await query(
    "SELECT (SELECT count(*) FROM mada_public_users WHERE id=$1) AS user_count, " +
    "(SELECT count(*) FROM mada_public_agents WHERE user_id=$1) AS agent_count, " +
    "(SELECT count(*) FROM mada_public_jobs WHERE user_id=$1) AS job_count, " +
    "(SELECT count(*) FROM mada_oauth_access_tokens WHERE user_id=$1) AS token_count",
    [userA]
  );
  assert.equal(Number(deletedRows.rows[0].user_count), 0);
  assert.equal(Number(deletedRows.rows[0].agent_count), 0);
  assert.equal(Number(deletedRows.rows[0].job_count), 0);
  assert.equal(Number(deletedRows.rows[0].token_count), 0);
  pass("account deletion cascades to public agents, jobs and OAuth tokens");

  console.log(JSON.stringify({ tenantIsolation: "PASS", checks: passed.length, failures: 0, checkNames: passed }, null, 2));
} finally {
  // Tests use only UUID-tagged rows and never touch any game/wallet tables or existing accounts.
  if (seeded) {
    try {
      await query("DELETE FROM mada_public_users WHERE google_sub = ANY($1::text[])", [[googleSubA, googleSubB]]);
    } catch (error) {
      console.error("Tenant-test cleanup by test marker failed:", String(error?.code || "CLEANUP_ERROR"));
    }
  }
  try {
    for (const key of rateKeys) await query("DELETE FROM mada_public_rate_limits WHERE key_hash=$1", [hashToken(key)]);
  } catch {}
  if (server) await new Promise(resolve => server.close(resolve));
  if (oldBase === undefined) delete process.env.MADA_PUBLIC_BASE_URL; else process.env.MADA_PUBLIC_BASE_URL = oldBase;
  if (oldMode === undefined) delete process.env.NODE_ENV; else process.env.NODE_ENV = oldMode;
  await closePool();
}
