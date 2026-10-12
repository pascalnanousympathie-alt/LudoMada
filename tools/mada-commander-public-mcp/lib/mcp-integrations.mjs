import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { spawn } from "node:child_process";
import { createInterface } from "node:readline";
import { redactSensitive } from "./security.mjs";

const CONFIG_FILE = path.join(os.homedir(), ".config", "mada-commander", "mcp-integrations.json");
const ALLOWED_COMMANDS = new Set(["github-mcp-server", "npx", "node", "python", "python3", "uvx", "uv", "docker", "bun", "deno"]);
const MAX_CONFIG_BYTES = 128_000;
const MAX_TOOLS = 150;
const MAX_ARGUMENT_BYTES = 32_000;
const MAX_RESPONSE_BYTES = 500_000;
const SAFE_ID = /^[a-z][a-z0-9_-]{0,39}$/;
const SAFE_TOOL_NAME = /^[A-Za-z0-9_.-]{1,120}$/;
const ENV_NAME = /^[A-Z][A-Z0-9_]{0,127}$/;

function safeId(value) {
  return typeof value === "string" && SAFE_ID.test(value);
}

function safeToolName(value) {
  return typeof value === "string" && SAFE_TOOL_NAME.test(value);
}

function validateServer(server) {
  if (!server || typeof server !== "object" || Array.isArray(server)) throw new Error("Invalid MCP integration entry.");
  if (!safeId(server.id)) throw new Error("MCP integration IDs must be simple lowercase aliases.");
  if (typeof server.label !== "string" || !server.label.trim() || server.label.length > 100) throw new Error("MCP integration labels must be 1–100 characters.");
  if (server.enabled !== false && server.enabled !== true) throw new Error("Each MCP integration must set enabled to true or false.");
  if (server.transport !== "stdio" && server.transport !== "http") throw new Error("MCP integration transport must be stdio or http.");
  if (server.transport === "stdio") {
    if (typeof server.command !== "string" || !ALLOWED_COMMANDS.has(server.command)) throw new Error("MCP stdio command is not in the local executable allowlist.");
    if (!Array.isArray(server.args) || server.args.length > 40 || server.args.some(a => typeof a !== "string" || a.length > 1000 || /\u0000/.test(a))) {
      throw new Error("MCP stdio args must be a fixed list of at most 40 strings.");
    }
    if (server.env !== undefined && (!server.env || typeof server.env !== "object" || Array.isArray(server.env))) throw new Error("MCP stdio env must be an environment-name map.");
    for (const [target, source] of Object.entries(server.env || {})) {
      if (!ENV_NAME.test(target) || !ENV_NAME.test(source)) throw new Error("MCP environment mappings must use environment-variable names only; never put secret values in the config.");
    }
  } else {
    let endpoint;
    try { endpoint = new URL(server.url); } catch { throw new Error("MCP HTTP integration requires an absolute URL."); }
    const loopbackTest = process.env.NODE_ENV === "test" && ["localhost", "127.0.0.1", "::1"].includes(endpoint.hostname);
    if ((endpoint.protocol !== "https:" && !(loopbackTest && endpoint.protocol === "http:")) ||
        endpoint.username || endpoint.password || endpoint.hash) {
      throw new Error("MCP HTTP integrations must use HTTPS without embedded credentials or fragments.");
    }
    if (server.authEnv !== undefined && !ENV_NAME.test(server.authEnv)) throw new Error("MCP HTTP authEnv must name a local environment variable, not contain a token.");
  }
  const allowedTools = server.allowedTools;
  const readOnlyTools = server.readOnlyTools;
  if (!Array.isArray(allowedTools) || allowedTools.length > MAX_TOOLS || allowedTools.some(n => !safeToolName(n)) || new Set(allowedTools).size !== allowedTools.length) {
    throw new Error("allowedTools must be a unique list of exact MCP tool names.");
  }
  if (!Array.isArray(readOnlyTools) || readOnlyTools.length > allowedTools.length ||
      readOnlyTools.some(n => !safeToolName(n) || !allowedTools.includes(n)) || new Set(readOnlyTools).size !== readOnlyTools.length) {
    throw new Error("readOnlyTools must be a unique subset of allowedTools.");
  }
  if (server.writeEnabled !== true && server.writeEnabled !== false) throw new Error("Each MCP integration must explicitly set writeEnabled true or false.");
  if (server.writeEnabled && server.enabled !== true) throw new Error("A disabled MCP integration cannot enable write tools.");
  return server;
}

async function loadConfig(configPath = CONFIG_FILE) {
  let raw;
  try {
    const st = await fs.lstat(configPath);
    if (!st.isFile() || st.isSymbolicLink()) throw new Error("MCP integration config must be a regular file, not a symlink.");
    if (st.size > MAX_CONFIG_BYTES) throw new Error("MCP integration config exceeds 128 KB.");
    if ((st.mode & 0o077) !== 0) throw new Error("MCP integration config permissions are too open. Run chmod 600 ~/.config/mada-commander/mcp-integrations.json.");
    raw = await fs.readFile(configPath, "utf8");
  } catch (error) {
    if (error?.code === "ENOENT") return { configured: false, servers: [] };
    throw error;
  }
  let config;
  try { config = JSON.parse(raw); } catch { throw new Error("MCP integration config is not valid JSON."); }
  if (config?.version !== 1 || !Array.isArray(config.servers) || config.servers.length > 12) {
    throw new Error("MCP integration config must use version 1 and contain at most 12 server entries.");
  }
  const ids = new Set();
  for (const server of config.servers) {
    validateServer(server);
    if (ids.has(server.id)) throw new Error("MCP integration IDs must be unique.");
    ids.add(server.id);
  }
  return { configured: true, servers: config.servers };
}

function requiredEnvNames(server) {
  return server.transport === "stdio"
    ? Object.values(server.env || {})
    : (server.authEnv ? [server.authEnv] : []);
}

function serverStatus(server) {
  const missing = [...new Set(requiredEnvNames(server).filter(name => !process.env[name]))];
  return {
    serverId: server.id,
    label: server.label,
    transport: server.transport,
    enabled: server.enabled,
    ready: server.enabled && missing.length === 0,
    writeEnabled: server.writeEnabled,
    allowedToolCount: server.allowedTools.length,
    readOnlyToolCount: server.readOnlyTools.length,
    missingEnvironmentVariables: missing
  };
}

export async function listMcpIntegrations(configPath = CONFIG_FILE) {
  const config = await loadConfig(configPath);
  return {
    configured: config.configured,
    servers: config.servers.map(serverStatus),
    note: config.configured
      ? "Only explicitly allowlisted tools are callable. Write-capable tools remain hidden unless writeEnabled is true in the local config."
      : "No local MCP integration config is installed. Copy mcp-integrations.example.json to ~/.config/mada-commander/mcp-integrations.json, edit it locally, set chmod 600, and restart the agent."
  };
}

async function withStdio(server, fn) {
  const env = {
    PATH: process.env.PATH || "",
    HOME: os.homedir(),
    TMPDIR: os.tmpdir(),
    LANG: process.env.LANG || "C.UTF-8"
  };
  for (const [target, source] of Object.entries(server.env || {})) {
    if (process.env[source]) env[target] = process.env[source];
  }
  const child = spawn(server.command, server.args, { shell: false, windowsHide: true, env, stdio: ["pipe", "pipe", "ignore"] });
  const pending = new Map();
  let nextId = 1;
  let closed = false;
  const closeWithError = () => {
    closed = true;
    for (const { reject, timer } of pending.values()) {
      clearTimeout(timer);
      reject(new Error("MCP stdio process exited before replying."));
    }
    pending.clear();
  };
  const lines = createInterface({ input: child.stdout, crlfDelay: Infinity });
  lines.on("line", line => {
    if (!line || Buffer.byteLength(line, "utf8") > MAX_RESPONSE_BYTES) return;
    let message;
    try { message = JSON.parse(line); } catch { return; }
    if (message?.id === undefined || message?.id === null) return;
    const entry = pending.get(String(message.id));
    if (!entry) return;
    pending.delete(String(message.id));
    clearTimeout(entry.timer);
    if (message.error) entry.reject(new Error("MCP stdio request failed (JSON-RPC " + String(message.error.code || "error") + ")."));
    else entry.resolve(message.result || {});
  });
  child.once("error", () => closeWithError());
  child.once("close", () => closeWithError());

  function sendMessage(message, timeoutMs = 20_000) {
    if (closed || !child.stdin.writable) return Promise.reject(new Error("MCP stdio process is not available."));
    const id = message.id;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        pending.delete(String(id));
        reject(new Error("MCP stdio response timed out."));
      }, timeoutMs);
      pending.set(String(id), { resolve, reject, timer });
      child.stdin.write(JSON.stringify(message) + "\n", error => {
        if (error) {
          const entry = pending.get(String(id));
          if (entry) {
            pending.delete(String(id));
            clearTimeout(entry.timer);
            reject(new Error("MCP stdio write failed."));
          }
        }
      });
    });
  }

  function notify(method, params = {}) {
    if (!closed && child.stdin.writable) child.stdin.write(JSON.stringify({ jsonrpc: "2.0", method, params }) + "\n");
  }

  try {
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error("MCP stdio process did not start.")), 8_000);
      child.once("spawn", () => { clearTimeout(timer); resolve(); });
      child.once("error", () => { clearTimeout(timer); reject(new Error("MCP stdio command could not be started. Check whether it is installed.")); });
    });
    const init = await sendMessage({
      jsonrpc: "2.0", id: nextId++, method: "initialize",
      params: { protocolVersion: "2025-06-18", capabilities: {}, clientInfo: { name: "mada-commander-local-bridge", version: "0.2.0" } }
    });
    if (!init.protocolVersion || !init.capabilities) throw new Error("MCP stdio server returned an invalid initialize response.");
    notify("notifications/initialized", {});
    await fn({
      request: async (method, params = {}, timeoutMs = 45_000) => sendMessage({ jsonrpc: "2.0", id: nextId++, method, params }, timeoutMs)
    });
  } finally {
    try { child.stdin.end(); } catch {}
    try { child.kill("SIGTERM"); } catch {}
    lines.close();
  }
}

async function parseHttpResult(response, maxBytes = MAX_RESPONSE_BYTES) {
  const body = await response.text();
  if (Buffer.byteLength(body, "utf8") > maxBytes) throw new Error("MCP HTTP response exceeds the safety limit.");
  if (!response.ok) throw new Error("MCP HTTP request failed (HTTP " + response.status + ").");
  if (!body.trim()) return {};
  const contentType = response.headers.get("content-type") || "";
  if (contentType.includes("text/event-stream")) {
    const messages = [];
    let data = [];
    for (const line of body.split(/\r?\n/)) {
      if (line === "") {
        if (data.length) {
          const joined = data.join("\n");
          if (joined !== "[DONE]") { try { messages.push(JSON.parse(joined)); } catch {} }
          data = [];
        }
      } else if (line.startsWith("data:")) data.push(line.slice(5).trimStart());
    }
    if (data.length) { try { messages.push(JSON.parse(data.join("\n"))); } catch {} }
    return messages.findLast ? (messages.findLast(m => m && typeof m === "object" && ("result" in m || "error" in m)) || {}) : (messages.reverse().find(m => m && typeof m === "object" && ("result" in m || "error" in m)) || {});
  }
  try { return JSON.parse(body); } catch { throw new Error("MCP HTTP server returned an unsupported response format."); }
}

async function withHttp(server, fn) {
  const headers = { accept: "application/json, text/event-stream", "content-type": "application/json" };
  if (server.authEnv && process.env[server.authEnv]) headers.authorization = "Bearer " + process.env[server.authEnv];
  let sessionId = "";
  let protocolVersion = "2025-06-18";
  let initialized = false;
  async function request(message, timeoutMs = 45_000) {
    const outgoing = { ...headers };
    if (sessionId) outgoing["mcp-session-id"] = sessionId;
    if (initialized && protocolVersion) outgoing["mcp-protocol-version"] = protocolVersion;
    const response = await fetch(server.url, {
      method: "POST",
      headers: outgoing,
      body: JSON.stringify(message),
      redirect: "error",
      signal: AbortSignal.timeout(timeoutMs)
    });
    const nextSession = response.headers.get("mcp-session-id");
    if (nextSession) sessionId = nextSession;
    const selectedVersion = response.headers.get("mcp-protocol-version");
    if (selectedVersion) protocolVersion = selectedVersion;
    const result = await parseHttpResult(response);
    if (result.error) throw new Error("MCP HTTP request failed (JSON-RPC " + String(result.error.code || "error") + ").");
    return result.result || {};
  }
  const init = await request({
    jsonrpc: "2.0", id: 1, method: "initialize",
    params: { protocolVersion, capabilities: {}, clientInfo: { name: "mada-commander-local-bridge", version: "0.2.0" } }
  });
  if (!init.protocolVersion || !init.capabilities) throw new Error("MCP HTTP server returned an invalid initialize response.");
  protocolVersion = init.protocolVersion;
  initialized = true;
  await fetch(server.url, {
    method: "POST",
    headers: { ...headers, ...(sessionId ? { "mcp-session-id": sessionId } : {}), "mcp-protocol-version": protocolVersion },
    body: JSON.stringify({ jsonrpc: "2.0", method: "notifications/initialized" }),
    redirect: "error",
    signal: AbortSignal.timeout(10_000)
  }).catch(() => {});
  await fn({ request });
}

function allowedNames(server) {
  return new Set(server.writeEnabled ? server.allowedTools : server.readOnlyTools);
}

function normalizeTool(tool) {
  if (!tool || typeof tool.name !== "string" || !safeToolName(tool.name)) return null;
  let inputSchema = tool.inputSchema;
  if (!inputSchema || typeof inputSchema !== "object" || Array.isArray(inputSchema)) inputSchema = { type: "object", properties: {}, additionalProperties: false };
  const serialized = JSON.stringify(inputSchema);
  if (Buffer.byteLength(serialized, "utf8") > 16_000) return null;
  return {
    name: tool.name,
    description: redactSensitive(String(tool.description || "").slice(0, 2000)),
    inputSchema
  };
}

async function callConfiguredServer(server, action, toolName, args) {
  let output;
  const callback = async client => {
    if (action === "list") {
      const listed = await client.request("tools/list", {});
      const upstream = Array.isArray(listed.tools) ? listed.tools.slice(0, MAX_TOOLS) : [];
      const allow = allowedNames(server);
      const tools = upstream.map(normalizeTool).filter(t => t && allow.has(t.name));
      output = { serverId: server.id, label: server.label, tools };
      return;
    }
    const listed = await client.request("tools/list", {});
    const upstream = Array.isArray(listed.tools) ? listed.tools.find(t => t?.name === toolName) : null;
    if (!upstream || !allowedNames(server).has(toolName)) throw new Error("That MCP tool is not in the local allowlist.");
    const input = args && typeof args === "object" && !Array.isArray(args) ? args : {};
    if (Buffer.byteLength(JSON.stringify(input), "utf8") > MAX_ARGUMENT_BYTES) throw new Error("MCP tool arguments exceed 32 KB.");
    const response = await client.request("tools/call", { name: toolName, arguments: input }, 90_000);
    const safe = {};
    if (Array.isArray(response.content)) {
      safe.content = response.content.slice(0, 30).filter(item => item && item.type === "text")
        .map(item => ({ type: "text", text: redactSensitive(String(item.text || "").slice(0, 20_000)) }));
    } else safe.content = [];
    safe.isError = response.isError === true;
    if (response.structuredContent !== undefined) {
      const structured = redactSensitive(JSON.stringify(response.structuredContent));
      if (Buffer.byteLength(structured, "utf8") <= MAX_RESPONSE_BYTES) {
        try { safe.structuredContent = JSON.parse(structured); } catch { safe.structuredContent = { text: structured.slice(0, 20_000) }; }
      } else safe.structuredContent = { truncated: true, note: "External MCP result exceeded the safety limit." };
    }
    output = { serverId: server.id, toolName, isError: safe.isError, content: safe.content, ...(safe.structuredContent === undefined ? {} : { structuredContent: safe.structuredContent }) };
  };
  if (server.transport === "stdio") await withStdio(server, callback);
  else await withHttp(server, callback);
  return output;
}

async function selectServer(serverId, configPath) {
  if (!safeId(serverId)) throw new Error("Invalid MCP server alias.");
  const config = await loadConfig(configPath);
  const server = config.servers.find(s => s.id === serverId);
  if (!server || !server.enabled) throw new Error("MCP server is not configured or not enabled in the local config.");
  const status = serverStatus(server);
  if (!status.ready) throw new Error("MCP server is missing local credentials: " + status.missingEnvironmentVariables.join(", "));
  return server;
}

export async function listAllowedMcpTools(serverId, configPath = CONFIG_FILE) {
  const server = await selectServer(serverId, configPath);
  let output;
  await callConfiguredServer(server, "list", "", {}).then(result => { output = result; });
  return output;
}

export async function callAllowedMcpTool(serverId, toolName, args, configPath = CONFIG_FILE) {
  if (!safeToolName(toolName)) throw new Error("Invalid MCP tool name.");
  const server = await selectServer(serverId, configPath);
  if (!allowedNames(server).has(toolName)) throw new Error("MCP tool is not explicitly allowlisted in the local config.");
  return await callConfiguredServer(server, "call", toolName, args);
}
