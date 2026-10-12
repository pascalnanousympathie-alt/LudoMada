#!/usr/bin/env node
import crypto from "node:crypto";
import fs from "node:fs/promises";
import path from "node:path";
import os from "node:os";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import { workspaceList, workspaceRead, workspaceWrite, runApprovedTask, runCommand, operaStatus, operaTabs, operaNavigate, operaAction, mcpIntegrationCatalog, mcpIntegrationTools, mcpIntegrationCall } from "./lib/agent-operations.mjs";

const HOME = os.homedir();
const STATE_DIR = path.join(HOME, ".config", "mada-commander");
const CONFIG_FILE = path.join(STATE_DIR, "agent.json");
const THIS_DIR = path.dirname(fileURLToPath(import.meta.url));
const MCP_CONFIG_FILE = path.join(STATE_DIR, "mcp-integrations.json");
const MCP_CONFIG_TEMPLATE = path.join(THIS_DIR, "mcp-integrations.example.json");
const ALLOWED_PROFILES = new Set(["git_status", "toolchain", "inventory"]);
const ALLOWED_OPERATIONS = new Set(["workspace_list", "workspace_read", "workspace_write", "run_task", "run_command", "opera_status", "opera_tabs", "opera_navigate", "opera_action", "mcp_integrations", "mcp_tools", "mcp_call"]);

function usage() {
  console.log(
    "Mada Commander local agent (paired workspace and Opera bridge)\n" +
    "  node agent.mjs pair <https-origin> <one-time-pair-code> [agent-name]\n" +
    "  node agent.mjs project-add <id> <label> <absolute-project-path>\n" +
    "  node agent.mjs project-list\n" +
    "  node agent.mjs run [--once]\n" +
    "  node agent.mjs status\n" +
    "  node agent.mjs integration-init | integration-list | integration-tools <server-id>\n\n" +
    "Mada Commander supports a broad allowlist of local command executables (Git, Node/npm, Python scripts, Gradle/Java, file utilities, and Termux package tools). Arguments are passed directly without shell parsing; inline Node/Python code-evaluation flags are blocked. Commands still run with the local agent OS user's permissions. Use only on your own device and revoke pairing when no longer trusted."
  );
}

async function readConfig() {
  try { return JSON.parse(await fs.readFile(CONFIG_FILE, "utf8")); }
  catch { throw new Error("Agent is not paired. Run the pair command first."); }
}

async function writeConfig(config) {
  await fs.mkdir(STATE_DIR, { recursive: true, mode: 0o700 });
  await fs.chmod(STATE_DIR, 0o700);
  const tmp = CONFIG_FILE + ".tmp-" + crypto.randomBytes(6).toString("hex");
  await fs.writeFile(tmp, JSON.stringify(config, null, 2) + "\n", { mode: 0o600 });
  await fs.rename(tmp, CONFIG_FILE);
  await fs.chmod(CONFIG_FILE, 0o600);
}

function validateBase(input) {
  const u = new URL(input);
  if (u.pathname !== "/" || u.search || u.hash || u.username || u.password) throw new Error("Server URL must be an origin without path/query.");
  if (u.protocol !== "https:" && !["localhost", "127.0.0.1"].includes(u.hostname)) throw new Error("The public service must use HTTPS.");
  return u.origin;
}

async function api(base, route, token, payload) {
  const headers = { accept: "application/json" };
  if (payload !== undefined) headers["content-type"] = "application/json";
  if (token) headers.authorization = "Bearer " + token;
  const response = await fetch(base + route, {
    method: payload === undefined ? "GET" : "POST",
    headers,
    body: payload === undefined ? undefined : JSON.stringify(payload),
    signal: AbortSignal.timeout(12_000)
  });
  const text = await response.text();
  let body = {};
  try { body = text ? JSON.parse(text) : {}; } catch {}
  if (!response.ok) {
    const message = response.status === 401 ? "Agent token rejected or revoked."
      : response.status === 429 ? "Server rate limit reached; retry later."
      : "Mada Commander public request failed (HTTP " + response.status + ").";
    throw new Error(message);
  }
  return body;
}

function validateProjectId(id) {
  if (typeof id !== "string" || !/^[a-z][a-z0-9_-]{0,39}$/.test(id)) throw new Error("Project ID must match [a-z][a-z0-9_-]{0,39}.");
}

function validateLabel(label) {
  if (typeof label !== "string" || !label.trim() || label.trim().length > 80 || /[\u0000-\u001f\u007f]/.test(label)) throw new Error("Project label must be 1-80 printable characters.");
}

async function addProject(id, label, inputPath) {
  validateProjectId(id);
  validateLabel(label);
  if (!path.isAbsolute(inputPath || "")) throw new Error("Use an absolute local path.");
  const resolved = path.resolve(inputPath);
  const st = await fs.stat(resolved).catch(() => null);
  if (!st?.isDirectory()) throw new Error("The project path must be an existing directory.");
  const config = await readConfig();
  const existing = config.projects.find(p => p.projectId === id);
  const project = { projectId: id, label: label.trim(), path: resolved };
  if (existing) Object.assign(existing, project); else config.projects.push(project);
  await writeConfig(config);
  console.log("Registered local project alias: " + id + " (" + label.trim() + "). Absolute path remains on this device.");
}

async function runFile(program, args, cwd, timeoutMs = 5000) {
  return await new Promise(resolve => {
    const child = spawn(program, args, { cwd, shell: false, env: process.env, stdio: ["ignore", "pipe", "pipe"] });
    let stdout = "", stderr = "", done = false;
    const finish = value => { if (done) return; done = true; clearTimeout(timer); resolve(value); };
    const timer = setTimeout(() => {
      try { child.kill("SIGTERM"); } catch {}
      finish({ ok: false, code: -1, stdout: stdout.slice(-3000), stderr: "timeout" });
    }, timeoutMs);
    child.stdout.on("data", d => { stdout += d.toString(); if (stdout.length > 8000) stdout = stdout.slice(-8000); });
    child.stderr.on("data", d => { stderr += d.toString(); if (stderr.length > 2000) stderr = stderr.slice(-2000); });
    child.on("error", () => finish({ ok: false, code: -1, stdout: "", stderr: "command_unavailable" }));
    child.on("close", code => finish({ ok: code === 0, code: code ?? -1, stdout: stdout.trim(), stderr: stderr.trim() }));
  });
}

async function collectProjectSummary(project) {
  const isGit = Boolean(await fs.stat(path.join(project.path, ".git")).catch(() => null));
  let branch = "", head = "";
  if (isGit) {
    const b = await runFile("git", ["branch", "--show-current"], project.path, 2500);
    const h = await runFile("git", ["rev-parse", "--short", "HEAD"], project.path, 2500);
    if (b.ok) branch = b.stdout.slice(0, 100);
    if (h.ok) head = h.stdout.replace(/[^a-fA-F0-9]/g, "").slice(0, 40);
  }
  return { projectId: project.projectId, label: project.label, branch, head };
}

async function runDiagnostic(profile, project) {
  if (!ALLOWED_PROFILES.has(profile)) throw new Error("The requested diagnostic profile is not allowed.");
  const pathValue = project.path;
  if (profile === "git_status") {
    const status = await runFile("git", ["status", "--short", "--branch"], pathValue, 7000);
    const head = await runFile("git", ["rev-parse", "--short", "HEAD"], pathValue, 3000);
    const branch = await runFile("git", ["branch", "--show-current"], pathValue, 3000);
    if (!status.ok) throw new Error("git status failed or this project is not a Git worktree.");
    // Count changed paths locally. Do not upload file names, which may expose private project structure.
    const lines = status.stdout.split("\n").filter(Boolean);
    return {
      branch: branch.ok ? branch.stdout.slice(0, 100) : "",
      head: head.ok ? head.stdout.replace(/[^a-fA-F0-9]/g, "").slice(0, 40) : "",
      dirtyCount: lines.filter(line => !line.startsWith("## ")).length
    };
  }
  if (profile === "toolchain") {
    const node = await runFile(process.execPath, ["--version"], pathValue, 3000);
    const npm = await runFile("npm", ["--version"], pathValue, 3000);
    const git = await runFile("git", ["--version"], pathValue, 3000);
    const java = await runFile("java", ["-version"], pathValue, 3000);
    return {
      node: node.ok ? node.stdout : "unavailable",
      npm: npm.ok ? npm.stdout : "unavailable",
      git: git.ok ? git.stdout : "unavailable",
      javaAvailable: Boolean(java.ok || /version/i.test(java.stderr || "")),
      gradleWrapperPresent: Boolean(await fs.stat(path.join(pathValue, "gradlew")).catch(() => null))
    };
  }
  const entries = await fs.readdir(pathValue, { withFileTypes: true });
  const names = new Set(entries.map(e => e.name.toLowerCase()));
  return {
    hasGit: Boolean(names.has(".git")),
    hasPackageJson: Boolean(names.has("package.json")),
    hasGradleWrapper: Boolean(names.has("gradlew")),
    hasReadme: Boolean([...names].some(n => n === "readme" || n.startsWith("readme."))),
    topLevelFileCount: entries.length
  };
}

async function heartbeat(config) {
  const projects = [];
  for (const p of (config.projects || []).slice(0, 40)) {
    projects.push(await collectProjectSummary(p).catch(() => ({ projectId: p.projectId, label: p.label, branch: "", head: "" })));
  }
  await api(config.serverBaseUrl, "/api/agent/heartbeat", config.agentToken, { projects });
}

async function processOneJob(config) {
  const answer = await api(config.serverBaseUrl, "/api/agent/poll", config.agentToken, {});
  if (!answer.job) return false;
  const job = answer.job;
  // Resolve project aliases locally. The public server cannot pass arbitrary paths or commands.
  const project = (config.projects || []).find(p => p.projectId === job.projectId);
  let status = "failed", result = null;
  if (project && (ALLOWED_PROFILES.has(job.profile) || ALLOWED_OPERATIONS.has(job.profile))) {
    try {
      const request = job.request && typeof job.request === "object" && !Array.isArray(job.request) ? job.request : {};
      if (ALLOWED_PROFILES.has(job.profile)) result = await runDiagnostic(job.profile, project);
      else if (job.profile === "workspace_list") result = await workspaceList(project, request.relativeDirectory || "");
      else if (job.profile === "workspace_read") result = await workspaceRead(project, request.relativeFile);
      else if (job.profile === "workspace_write") result = await workspaceWrite(project, request.relativeFile, request.content);
      else if (job.profile === "run_task" && request.kind === "command") result = await runCommand(project, request.program, request.args || [], request.cwd || ".", request.timeoutMs);
      else if (job.profile === "run_task") result = await runApprovedTask(project, request.task);
      else if (job.profile === "run_command") result = await runCommand(project, request.program, request.args || [], request.cwd || ".", request.timeoutMs);
      else if (job.profile === "opera_status") result = await operaStatus();
      else if (job.profile === "opera_tabs") result = await operaTabs();
      else if (job.profile === "opera_navigate") result = await operaNavigate(request.tabId, request.address);
      else if (job.profile === "opera_action") result = await operaAction(request);
      else if (job.profile === "mcp_integrations") result = await mcpIntegrationCatalog();
      else if (job.profile === "mcp_tools") result = await mcpIntegrationTools(request.serverId);
      else if (job.profile === "mcp_call") result = await mcpIntegrationCall(request.serverId, request.toolName, request.arguments);
      status = "succeeded";
    } catch (error) {
      result = { error: String(error?.message || "The approved operation failed.").slice(0, 500) };
      status = "failed";
    }
  }
  await api(config.serverBaseUrl, "/api/agent/complete", config.agentToken, { jobId: job.jobId, status, result });
  console.log("Finished approved diagnostic job " + job.jobId + " (" + status + ").");
  return true;
}

async function pair(baseArg, code, nameArg) {
  const base = validateBase(baseArg);
  if (!code || code.length < 12) throw new Error("Provide the one-time pairing code from the signed-in Mada Commander website.");
  await fs.mkdir(STATE_DIR, { recursive: true, mode: 0o700 });
  const pairResponse = await api(base, "/api/agent/enroll", null, { pairCode: code, name: String(nameArg || "Mada Commander agent").slice(0, 80) });
  const existing = await fs.readFile(CONFIG_FILE, "utf8").then(x => JSON.parse(x)).catch(() => null);
  const config = {
    serverBaseUrl: base,
    agentId: pairResponse.agentId,
    agentToken: pairResponse.agentToken,
    projects: Array.isArray(existing?.projects) ? existing.projects : []
  };
  await writeConfig(config);
  console.log("Paired successfully. Agent ID: " + config.agentId);
  console.log("Token saved locally with owner-only permissions; it was not printed.");
  console.log("Add project aliases, then run: node " + THIS_DIR + "/agent.mjs run");
}

async function initMcpIntegrationConfig() {
  await fs.mkdir(STATE_DIR, { recursive: true, mode: 0o700 });
  await fs.chmod(STATE_DIR, 0o700);
  const existing = await fs.lstat(MCP_CONFIG_FILE).catch(error => error?.code === "ENOENT" ? null : Promise.reject(error));
  if (existing) throw new Error("MCP integration config already exists; refusing to overwrite it.");
  const template = await fs.readFile(MCP_CONFIG_TEMPLATE, "utf8");
  await fs.writeFile(MCP_CONFIG_FILE, template, { encoding: "utf8", mode: 0o600, flag: "wx" });
  await fs.chmod(MCP_CONFIG_FILE, 0o600);
  console.log("Created local MCP integration config with all optional servers disabled.");
  console.log("Edit only this local file, set credentials in the agent environment, and keep permissions at 600.");
  console.log("Config location: ~/.config/mada-commander/mcp-integrations.json");
}

async function main() {
  const [command, ...args] = process.argv.slice(2);
  if (!command || command === "help" || command === "--help") return usage();
  if (command === "pair") return pair(args[0], args[1], args[2]);
  if (command === "project-add") return addProject(args[0], args[1], args.slice(2).join(" "));
  if (command === "integration-init") return initMcpIntegrationConfig();
  if (command === "integration-list") {
    console.log(JSON.stringify(await mcpIntegrationCatalog(), null, 2));
    return;
  }
  if (command === "integration-tools") {
    if (!args[0]) throw new Error("Provide a server ID shown by integration-list.");
    console.log(JSON.stringify(await mcpIntegrationTools(args[0]), null, 2));
    return;
  }
  if (command === "project-list") {
    const config = await readConfig();
    console.log(JSON.stringify((config.projects || []).map(p => ({ projectId: p.projectId, label: p.label, configured: Boolean(p.path) })), null, 2));
    return;
  }
  if (command === "status") {
    const config = await readConfig();
    const r = await api(config.serverBaseUrl, "/api/agent/heartbeat", config.agentToken, {
      projects: await Promise.all((config.projects || []).map(collectProjectSummary))
    });
    console.log(JSON.stringify({ paired: true, agentId: config.agentId, server: config.serverBaseUrl, registeredProjects: r.registeredProjects }, null, 2));
    return;
  }
  if (command === "run") {
    const config = await readConfig();
    const once = args.includes("--once");
    console.log("Mada Commander agent started. Allowed profiles: git_status, toolchain, inventory. Press Ctrl+C to stop.");
    let lastHeartbeat = 0;
    do {
      try {
        if (Date.now() - lastHeartbeat > 30_000) {
          await heartbeat(config);
          lastHeartbeat = Date.now();
        }
        await processOneJob(config);
      } catch (error) {
        console.error("Agent loop error:", error.message || "network_error");
        if (String(error.message || "").includes("Agent token rejected or revoked")) {
          console.error("This agent was revoked. Stop here and remove the local agent configuration if you no longer need it.");
          break;
        }
      }
      if (once) break;
      await new Promise(resolve => setTimeout(resolve, 15_000));
    } while (true);
    return;
  }
  usage();
  process.exitCode = 2;
}

main().catch(error => {
  console.error("Mada Commander agent:", error.message || "failed");
  process.exitCode = 1;
});
