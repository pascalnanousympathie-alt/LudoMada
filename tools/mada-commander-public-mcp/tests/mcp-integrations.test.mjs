import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import http from "node:http";
import {
  listMcpIntegrations,
  listAllowedMcpTools,
  callAllowedMcpTool
} from "../lib/mcp-integrations.mjs";
import { redactSensitiveStructured } from "../lib/security.mjs";

async function makeConfig(t, servers) {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), "mada-mcp-"));
  const file = path.join(root, "mcp-integrations.json");
  await fs.writeFile(file, JSON.stringify({ version: 1, servers }, null, 2), { mode: 0o600 });
  await fs.chmod(file, 0o600);
  t.after(() => fs.rm(root, { recursive: true, force: true }));
  return file;
}

const stdioServer = () => ({
  id: "fixture",
  label: "Fixture MCP",
  transport: "stdio",
  enabled: true,
  command: "node",
  args: [path.join(path.dirname(new URL(import.meta.url).pathname), "fixtures", "mock-mcp-server.mjs")],
  env: {},
  allowedTools: ["read_ok", "write_ok"],
  readOnlyTools: ["read_ok"],
  writeEnabled: false
});

test("local MCP registry reports status without ever revealing credential values", async t => {
  const oldToken = process.env.MCP_FIXTURE_SECRET;
  process.env.MCP_FIXTURE_SECRET = "never-return-this-token";
  t.after(() => {
    if (oldToken === undefined) delete process.env.MCP_FIXTURE_SECRET;
    else process.env.MCP_FIXTURE_SECRET = oldToken;
  });
  const server = { ...stdioServer(), enabled: false, env: { FIXTURE_TOKEN: "MCP_FIXTURE_SECRET" } };
  const file = await makeConfig(t, [server]);
  const result = await listMcpIntegrations(file);
  assert.equal(result.configured, true);
  assert.equal(result.servers[0].serverId, "fixture");
  assert.equal(result.servers[0].enabled, false);
  assert.equal(result.servers[0].ready, false);
  assert.deepEqual(result.servers[0].missingEnvironmentVariables, []);
  assert.ok(!JSON.stringify(result).includes("never-return-this-token"));
});

test("OAuth-backed stdio initialization allows delayed first-token negotiation", async () => {
  const source = await fs.readFile(new URL("../lib/mcp-integrations.mjs", import.meta.url), "utf8");
  assert.match(source, /const MCP_INITIALIZE_TIMEOUT_MS = 60_000;/);
  assert.match(source, /method: "initialize",[\s\S]{0,240}MCP_INITIALIZE_TIMEOUT_MS/);
});

test("stdio bridge exposes only the explicit read-only allowlist and calls only those tools", async t => {
  const file = await makeConfig(t, [stdioServer()]);
  const listed = await listAllowedMcpTools("fixture", file);
  assert.deepEqual(listed.tools.map(tool => tool.name), ["read_ok"]);
  assert.equal(listed.serverId, "fixture");

  await assert.rejects(
    () => callAllowedMcpTool("fixture", "write_ok", {}, file),
    /not explicitly allowlisted/
  );
});

test("stdio bridge accepts large upstream catalogs but exposes only allowlisted tools", async t => {
  const server = {
    ...stdioServer(),
    args: [path.join(path.dirname(new URL(import.meta.url).pathname), "fixtures", "mock-mcp-large-list-server.mjs")],
    allowedTools: ["read_ok"],
    readOnlyTools: ["read_ok"]
  };
  const file = await makeConfig(t, [server]);
  const listed = await listAllowedMcpTools("fixture", file);
  assert.deepEqual(listed.tools.map(tool => tool.name), ["read_ok"]);
});

test("stdio bridge runs an allowlisted call and returns bounded tool output", async t => {
  const file = await makeConfig(t, [stdioServer()]);
  const result = await callAllowedMcpTool("fixture", "read_ok", { query: "hello" }, file);
  assert.equal(result.serverId, "fixture");
  assert.equal(result.toolName, "read_ok");
  assert.equal(result.isError, false);
  assert.match(result.content[0].text, /fixture result: hello/);
  assert.deepEqual(result.structuredContent, { ok: true });
});

test("HTTP bridge negotiates an MCP session and sends session headers after initialize", async t => {
  const oldMode = process.env.NODE_ENV;
  process.env.NODE_ENV = "test";
  t.after(() => {
    if (oldMode === undefined) delete process.env.NODE_ENV;
    else process.env.NODE_ENV = oldMode;
  });

  const seen = [];
  const server = http.createServer(async (req, res) => {
    let text = "";
    for await (const chunk of req) text += chunk.toString();
    const msg = text ? JSON.parse(text) : {};
    seen.push({ method: msg.method, session: req.headers["mcp-session-id"], version: req.headers["mcp-protocol-version"], toolsets: req.headers["x-mcp-toolsets"] });
    if (msg.method === "notifications/initialized") {
      res.writeHead(202); res.end(); return;
    }
    res.setHeader("content-type", "application/json");
    if (msg.method === "initialize") {
      res.setHeader("mcp-session-id", "fixture-session");
      res.end(JSON.stringify({ jsonrpc: "2.0", id: msg.id, result: {
        protocolVersion: "2025-06-18", capabilities: { tools: {} }, serverInfo: { name: "http-fixture", version: "1.0" }
      }}));
    } else if (msg.method === "tools/list") {
      res.end(JSON.stringify({ jsonrpc: "2.0", id: msg.id, result: { tools: [
        { name: "http_read", description: "Read-only HTTP fixture", inputSchema: { type: "object", properties: {}, additionalProperties: false } }
      ] }}));
    } else if (msg.method === "tools/call") {
      res.end(JSON.stringify({ jsonrpc: "2.0", id: msg.id, result: { content: [{ type: "text", text: "http fixture passed" }] }}));
    } else {
      res.end(JSON.stringify({ jsonrpc: "2.0", id: msg.id, result: {} }));
    }
  });
  await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
  t.after(async () => new Promise(resolve => server.close(resolve)));
  const address = server.address();
  const file = await makeConfig(t, [{
    id: "http-fixture",
    label: "HTTP fixture",
    transport: "http",
    enabled: true,
    url: "http://127.0.0.1:" + address.port + "/mcp",
    allowedTools: ["http_read"],
    readOnlyTools: ["http_read"],
    requestHeaders: { "X-MCP-Toolsets": "repos,issues" },
    writeEnabled: false
  }]);
  const result = await callAllowedMcpTool("http-fixture", "http_read", {}, file);
  assert.match(result.content[0].text, /http fixture passed/);
  assert.ok(seen.some(item => item.method === "tools/list" && item.session === "fixture-session" && item.version === "2025-06-18"));
  assert.ok(seen.some(item => item.method === "initialize" && item.toolsets === "repos,issues"));

  const invalidHeaderFile = await makeConfig(t, [{
    id: "http-fixture", label: "HTTP fixture", transport: "http", enabled: true,
    url: "http://127.0.0.1:" + address.port + "/mcp", allowedTools: ["http_read"],
    readOnlyTools: ["http_read"], requestHeaders: { Authorization: "placeholder" }, writeEnabled: false
  }]);
  await assert.rejects(() => listAllowedMcpTools("http-fixture", invalidHeaderFile), /only support the X-MCP-Toolsets header/);
});


test("structured MCP redaction recursively removes secret fields and fails closed on oversized or deep values", () => {
  const original = {
    ok: true,
    nested: {
      SUPABASE_SERVICE_ROLE_KEY: "service-role-value-must-not-escape",
      refresh_token: "refresh-token-value-must-not-escape",
      private_key: "private-key-value-must-not-escape",
      custom_api_key: "custom-api-key-value-must-not-escape",
      label: "kept"
    },
    items: [{ GITHUB_TOKEN: "github-token-value-must-not-escape", count: 3 }]
  };
  const safe = redactSensitiveStructured(original);
  assert.equal(safe.ok, true);
  assert.equal(safe.nested.label, "kept");
  assert.equal(safe.nested.SUPABASE_SERVICE_ROLE_KEY, "[REDACTED]");
  assert.equal(safe.nested.refresh_token, "[REDACTED]");
  assert.equal(safe.nested.private_key, "[REDACTED]");
  assert.equal(safe.nested.custom_api_key, "[REDACTED]");
  assert.equal(safe.items[0].GITHUB_TOKEN, "[REDACTED]");
  assert.equal(safe.items[0].count, 3);
  assert.equal(original.nested.private_key, "private-key-value-must-not-escape");
  assert.deepEqual(redactSensitiveStructured({ level1: { level2: { value: "deep" } } }, { maxDepth: 1 }), {
    truncated: true,
    note: "External MCP result exceeded the redaction safety limits."
  });
  assert.deepEqual(redactSensitiveStructured({ payload: "x".repeat(1200) }, { maxBytes: 256 }), {
    truncated: true,
    note: "External MCP result exceeded the redaction safety limits."
  });
});

test("external MCP text and structured results never return nested secrets, JWTs, credentials, or PEM key blocks", async t => {
  const oldMode = process.env.NODE_ENV;
  process.env.NODE_ENV = "test";
  t.after(() => {
    if (oldMode === undefined) delete process.env.NODE_ENV;
    else process.env.NODE_ENV = oldMode;
  });

  const secrets = {
    serviceRole: "service-role-value-must-not-escape",
    refreshToken: "refresh-token-value-must-not-escape",
    privateMaterial: "FAKE_PRIVATE_KEY_MATERIAL_MUST_NOT_ESCAPE",
    apiKey: "custom-api-key-value-must-not-escape",
    githubToken: "ghp_0123456789abcdefghijklmnopqrstuv",
    supabaseToken: "sb_secret_0123456789abcdef012345",
    jwt: "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyIn0.signaturelong0123456789",
    dbPassword: "db-password-must-not-escape",
    httpsPassword: "url-password-must-not-escape",
    bearerToken: "BearerTokenValueMustNotEscape012345"
  };
  const dbUrl = "postgres://fixture:" + secrets.dbPassword + "@db.example.test:5432/app";
  const urlWithUserInfo = "https://alice:" + secrets.httpsPassword + "@example.test/path";
  const pem = "-----BEGIN RSA PRIVATE KEY-----\n" + secrets.privateMaterial + "\n-----END RSA PRIVATE KEY-----";
  const structured = {
    ok: true,
    note: "safe-field-is-preserved",
    settings: {
      SUPABASE_SERVICE_ROLE_KEY: secrets.serviceRole,
      refresh_token: secrets.refreshToken,
      private_key: pem,
      custom_api_key: secrets.apiKey,
      database_url: dbUrl
    },
    array: [{ GITHUB_TOKEN: secrets.githubToken, label: "safe-array-label" }]
  };
  const textResult = [
    "secret=" + secrets.serviceRole,
    "token=" + secrets.refreshToken,
    "privateKey=" + pem,
    "api_key=" + secrets.apiKey,
    "GitHub=" + secrets.githubToken,
    "Supabase=" + secrets.supabaseToken,
    "JWT=" + secrets.jwt,
    "database=" + dbUrl,
    "URL=" + urlWithUserInfo,
    "Authorization: Bearer " + secrets.bearerToken
  ].join("\n");

  const server = http.createServer(async (req, res) => {
    let body = "";
    for await (const chunk of req) body += chunk.toString();
    const msg = body ? JSON.parse(body) : {};
    if (msg.method === "notifications/initialized") { res.writeHead(202); res.end(); return; }
    res.setHeader("content-type", "application/json");
    if (msg.method === "initialize") {
      res.setHeader("mcp-session-id", "redaction-fixture-session");
      res.end(JSON.stringify({ jsonrpc: "2.0", id: msg.id, result: {
        protocolVersion: "2025-06-18", capabilities: { tools: {} }, serverInfo: { name: "redaction-fixture", version: "1.0" }
      }}));
    } else if (msg.method === "tools/list") {
      res.end(JSON.stringify({ jsonrpc: "2.0", id: msg.id, result: { tools: [
        { name: "redaction_read", description: "Return fixture data", inputSchema: { type: "object", properties: {}, additionalProperties: false } }
      ] }}));
    } else if (msg.method === "tools/call") {
      res.end(JSON.stringify({ jsonrpc: "2.0", id: msg.id, result: {
        content: [{ type: "text", text: textResult }],
        structuredContent: structured,
        isError: false
      }}));
    } else {
      res.end(JSON.stringify({ jsonrpc: "2.0", id: msg.id, result: {} }));
    }
  });
  await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
  t.after(async () => new Promise(resolve => server.close(resolve)));
  const address = server.address();
  const file = await makeConfig(t, [{
    id: "redaction-fixture",
    label: "Redaction fixture",
    transport: "http",
    enabled: true,
    url: "http://127.0.0.1:" + address.port + "/mcp",
    allowedTools: ["redaction_read"],
    readOnlyTools: ["redaction_read"],
    writeEnabled: false
  }]);

  const result = await callAllowedMcpTool("redaction-fixture", "redaction_read", {}, file);
  const serialized = JSON.stringify(result);
  for (const secret of Object.values(secrets)) assert.ok(!serialized.includes(secret), "secret leaked: " + secret.slice(0, 12));
  assert.ok(!serialized.includes(dbUrl));
  assert.ok(!serialized.includes(urlWithUserInfo));
  assert.ok(!serialized.includes(pem));
  assert.equal(result.structuredContent.note, "safe-field-is-preserved");
  assert.equal(result.structuredContent.settings.SUPABASE_SERVICE_ROLE_KEY, "[REDACTED]");
  assert.equal(result.structuredContent.credentials.refresh_token, "[REDACTED]");
  assert.equal(result.structuredContent.credentials.private_key, "[REDACTED]");
  assert.equal(result.structuredContent.credentials.custom_api_key, "[REDACTED]");
  assert.equal(result.structuredContent.credentials.database_url, "[REDACTED]");
  assert.equal(result.structuredContent.array[0].GITHUB_TOKEN, "[REDACTED]");
  assert.equal(result.structuredContent.array[0].label, "safe-array-label");
  assert.match(result.content[0].text, /REDACTED/);
});

test("configuration rejects shell commands and world-readable config files", async t => {
  const invalid = { ...stdioServer(), command: "sh" };
  const file = await makeConfig(t, [invalid]);
  await assert.rejects(() => listMcpIntegrations(file), /not in the local executable allowlist/);

  const file2 = await makeConfig(t, [stdioServer()]);
  await fs.chmod(file2, 0o644);
  await assert.rejects(() => listMcpIntegrations(file2), /permissions are too open/);
});
