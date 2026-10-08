import fs from "node:fs/promises";
import fsSync from "node:fs";
import path from "node:path";
import os from "node:os";
import crypto from "node:crypto";
import { spawn } from "node:child_process";
import { createServer as createHttpServer } from "node:http";
import { createMcpHandler, McpServer } from "@modelcontextprotocol/server";
import { serveStdio } from "@modelcontextprotocol/server/stdio";
import { toNodeHandler, localhostHostValidation, localhostOriginValidation } from "@modelcontextprotocol/node";
import * as z from "zod/v4";
import { createControlPlane } from "./control-plane.mjs";

const HOME = process.env.HOME || "/data/data/com.termux/files/home";
const ROOT = path.resolve(process.env.COMMANDER_HOME || path.join(HOME, "ludomada-mcp-commander"));
const CONFIG_PATH = path.join(ROOT, "config.json");
const DEFAULT_CONFIG = {
  workspaceRoots: [HOME],
  defaultProject: path.join(HOME, "LudoMada"),
  http: { host: "127.0.0.1", port: 8787, token: "" },
  exec: { timeoutMs: 120000, maxOutputBytes: 200000 },
  github: { repo: "pascalnanousympathie-alt/LudoMada" }
};

async function loadConfig() {
  try {
    const raw = JSON.parse(await fs.readFile(CONFIG_PATH, "utf8"));
    return {
      ...DEFAULT_CONFIG,
      ...raw,
      http: { ...DEFAULT_CONFIG.http, ...(raw.http || {}) },
      exec: { ...DEFAULT_CONFIG.exec, ...(raw.exec || {}) },
      workspaceRoots: Array.isArray(raw.workspaceRoots) && raw.workspaceRoots.length
        ? raw.workspaceRoots
        : DEFAULT_CONFIG.workspaceRoots
    };
  } catch {
    return DEFAULT_CONFIG;
  }
}

const config = await loadConfig();
const WIDGET_URI = "ui://ludomada-commander/dashboard.html";
const WIDGET_HTML = await fs.readFile(path.join(ROOT, "public", "commander-widget.html"), "utf8").catch(() => "<html><body><p>LudoMada Commander UI unavailable.</p></body></html>");
const sessions = new Map();

function roots() {
  return [...new Set(config.workspaceRoots.map(r => path.resolve(r)))];
}

function insideRoot(candidate, root) {
  const rel = path.relative(path.resolve(root), path.resolve(candidate));
  return rel === "" || (!rel.startsWith("..") && !path.isAbsolute(rel));
}

function realCandidate(candidate) {
  let probe = path.resolve(candidate);
  while (!fsSync.existsSync(probe) && path.dirname(probe) !== probe) probe = path.dirname(probe);
  const realProbe = fsSync.realpathSync.native(probe);
  if (fsSync.existsSync(candidate)) return fsSync.realpathSync.native(candidate);
  return path.resolve(realProbe, path.relative(probe, candidate));
}

function resolveSafe(inputPath, project) {
  const base = project ? path.resolve(project) : path.resolve(config.defaultProject);
  const candidate = path.isAbsolute(inputPath) ? path.resolve(inputPath) : path.resolve(base, inputPath);
  const real = realCandidate(candidate);
  if (!roots().some(r => insideRoot(real, realCandidate(r)))) {
    throw new Error("Path is outside configured workspaceRoots: " + candidate);
  }
  return real;
}

function resolveProject(project) {
  const p = path.resolve(project || config.defaultProject);
  const real = realCandidate(p);
  if (!roots().some(r => insideRoot(real, realCandidate(r)))) {
    throw new Error("Project is outside configured workspaceRoots: " + p);
  }
  return real;
}

function shellQuote(value) {
  return "'" + String(value).replaceAll("'", "'\''") + "'";
}

function limitText(text, maxBytes = config.exec.maxOutputBytes) {
  const buf = Buffer.from(text, "utf8");
  if (buf.length <= maxBytes) return text;
  return buf.subarray(0, maxBytes).toString("utf8") + "\n[OUTPUT TRUNCATED]";
}

async function runCommand(command, cwd, timeoutMs = config.exec.timeoutMs) {
  const project = resolveProject(cwd);
  return await new Promise((resolve) => {
    const shell = process.env.SHELL || "/data/data/com.termux/files/usr/bin/bash";
    const child = spawn(command, { cwd: project, shell, env: process.env });
    let stdout = "";
    let stderr = "";
    let killed = false;
    const timer = setTimeout(() => {
      killed = true;
      child.kill("SIGTERM");
      setTimeout(() => { try { child.kill("SIGKILL"); } catch {} }, 1500);
    }, Math.max(1000, timeoutMs));

    child.stdout.on("data", d => { stdout += d.toString(); });
    child.stderr.on("data", d => { stderr += d.toString(); });
    child.on("close", code => {
      clearTimeout(timer);
      resolve({
        code: code ?? -1,
        signal: killed ? "TIMEOUT" : null,
        cwd: project,
        stdout: limitText(stdout),
        stderr: limitText(stderr)
      });
    });
    child.on("error", err => {
      clearTimeout(timer);
      resolve({ code: -1, signal: null, cwd: project, stdout: "", stderr: err.message });
    });
  });
}

async function readJson(file) {
  return JSON.parse(await fs.readFile(file, "utf8"));
}

function result(text, data) {
  return {
    content: [{ type: "text", text }],
    ...(data === undefined ? {} : { structuredContent: data })
  };
}

function registerTools(server) {
  server.registerResource("commander-dashboard", WIDGET_URI, {
    title: "LudoMada Commander Dashboard",
    description: "Interactive status and development controls for the Termux Commander.",
    mimeType: "text/html;profile=mcp-app",
    _meta: { ui: { prefersBorder: true } }
  }, async (uri) => ({
    contents: [{ uri: uri.href, mimeType: "text/html;profile=mcp-app", text: WIDGET_HTML, _meta: { ui: { prefersBorder: true } } }]
  }));

  server.registerTool("commander_status", {
    title: "Commander status",
    description: "Return commander, Android/Termux, Node, Git and default project status.",
    inputSchema: z.object({}),
    _meta: {
      ui: { resourceUri: WIDGET_URI },
      "openai/toolInvocation/invoking": "Checking Commander…",
      "openai/toolInvocation/invoked": "Commander status ready."
    }
  }, async () => {
    const git = await runCommand("git rev-parse --show-toplevel && git rev-parse --short HEAD && git branch --show-current", config.defaultProject, 15000).catch(e => ({ stderr: e.message }));
    const text = [
      "LudoMada MCP Commander 0.1.0",
      "platform=" + process.platform + " " + process.arch,
      "node=" + process.version,
      "home=" + HOME,
      "root=" + ROOT,
      "defaultProject=" + config.defaultProject,
      "workspaceRoots=" + roots().join(", "),
      "git=" + (git.stdout || git.stderr || "unavailable").trim()
    ].join("\n");
    return result(text);
  });

  server.registerTool("list_dir", {
    description: "List files and directories in a configured workspace path.",
    inputSchema: z.object({
      path: z.string().default("."),
      project: z.string().optional(),
      depth: z.number().int().min(0).max(4).default(1)
    })
  }, async ({ path: p, project, depth }) => {
    const start = resolveSafe(p, project);
    const out = [];
    async function walk(dir, level, rel) {
      if (level > depth) return;
      const entries = await fs.readdir(dir, { withFileTypes: true });
      entries.sort((a,b) => a.name.localeCompare(b.name));
      for (const e of entries.slice(0, 200)) {
        const rp = path.join(rel, e.name);
        out.push((e.isDirectory() ? "[DIR] " : "[FILE] ") + rp);
        if (e.isDirectory() && level < depth) await walk(path.join(dir, e.name), level + 1, rp);
      }
      if (entries.length > 200) out.push("[TRUNCATED] " + (entries.length - 200) + " entries hidden in " + rel);
    }
    await walk(start, 0, ".");
    return result(out.join("\n") || "[EMPTY]");
  });

  server.registerTool("read_file", {
    description: "Read a UTF-8 text file inside configured workspace roots.",
    inputSchema: z.object({
      path: z.string(),
      project: z.string().optional(),
      startLine: z.number().int().min(1).default(1),
      maxLines: z.number().int().min(1).max(5000).default(500)
    })
  }, async ({ path: p, project, startLine, maxLines }) => {
    const file = resolveSafe(p, project);
    const lines = (await fs.readFile(file, "utf8")).split("\n");
    const slice = lines.slice(startLine - 1, startLine - 1 + maxLines);
    return result(slice.map((line,i) => String(startLine+i).padStart(6," ") + " | " + line).join("\n"));
  });

  server.registerTool("write_file", {
    description: "Create or replace a UTF-8 text file inside configured workspace roots.",
    inputSchema: z.object({
      path: z.string(),
      content: z.string(),
      project: z.string().optional(),
      mkdirs: z.boolean().default(true)
    })
  }, async ({ path: p, content, project, mkdirs }) => {
    const file = resolveSafe(p, project);
    if (mkdirs) await fs.mkdir(path.dirname(file), { recursive: true });
    const snapshotId = await control.snapshotFile(file);
    await control.writeAtomic(file, content);
    await control.audit("file.write", { path: file, bytes: Buffer.byteLength(content, "utf8"), snapshotId });
    return result("WROTE " + file + " (" + Buffer.byteLength(content) + " bytes) snapshot=" + snapshotId,
      { path: file, bytes: Buffer.byteLength(content, "utf8"), snapshotId });
  });

  server.registerTool("edit_file", {
    description: "Replace an exact text block atomically, with a rollback snapshot and audit trail.",
    inputSchema: z.object({
      path: z.string(),
      oldText: z.string(),
      newText: z.string(),
      count: z.number().int().min(1).default(1),
      project: z.string().optional()
    })
  }, async ({ path: p, oldText, newText, count, project }) => {
    const file = resolveSafe(p, project);
    const content = await fs.readFile(file, "utf8");
    const occurrences = content.split(oldText).length - 1;
    if (occurrences === 0) throw new Error("oldText not found");
    if (occurrences !== count) throw new Error("expected " + count + " occurrences, found " + occurrences);
    const snapshotId = await control.snapshotFile(file);
    await control.writeAtomic(file, content.replaceAll(oldText, newText));
    await control.audit("file.edit", { path: file, occurrences, snapshotId });
    return result(JSON.stringify({ path: file, occurrences, snapshotId }, null, 2), { path: file, occurrences, snapshotId });
  });

  server.registerTool("move_file", {
    description: "Move/rename a workspace path and keep a snapshot for existing files.",
    inputSchema: z.object({ source: z.string(), destination: z.string(), project: z.string().optional() })
  }, async ({ source, destination, project }) => {
    const src = resolveSafe(source, project);
    const dst = resolveSafe(destination, project);
    const srcStat = await fs.lstat(src);
    const sourceSnapshotId = srcStat.isFile() ? await control.snapshotFile(src) : null;
    const dstStat = await fs.lstat(dst).catch(() => null);
    const destinationSnapshotId = dstStat?.isFile() ? await control.snapshotFile(dst) : null;
    await fs.mkdir(path.dirname(dst), { recursive: true });
    await fs.rename(src, dst);
    await control.audit("file.move", { source: src, destination: dst, sourceSnapshotId, destinationSnapshotId });
    return result(JSON.stringify({ source: src, destination: dst, sourceSnapshotId, destinationSnapshotId }, null, 2));
  });

  server.registerTool("mkdir", {
    description: "Create a workspace directory tree and audit it.",
    inputSchema: z.object({ path: z.string(), project: z.string().optional() })
  }, async ({ path: p, project }) => {
    const dir = resolveSafe(p, project);
    await fs.mkdir(dir, { recursive: true });
    await control.audit("directory.create", { path: dir });
    return result("CREATED " + dir);
  });

  server.registerTool("search", {
    description: "Search text recursively in a project using ripgrep when available, otherwise grep.",
    inputSchema: z.object({
      pattern: z.string(),
      project: z.string().optional(),
      maxResults: z.number().int().min(1).max(500).default(100)
    })
  }, async ({ pattern, project, maxResults }) => {
    const cwd = resolveProject(project);
    const quoted = JSON.stringify(pattern);
    const cmd = "if command -v rg >/dev/null 2>&1; then rg -n --hidden --glob '!node_modules' --glob '!.git' --max-count 8 " + quoted + " . | head -n " + maxResults + "; else grep -RIn --exclude-dir=node_modules --exclude-dir=.git " + quoted + " . | head -n " + maxResults + "; fi";
    const r = await runCommand(cmd, cwd, 30000);
    return result((r.stdout || r.stderr || "").trim() || "[NO MATCHES]");
  });

  server.registerTool("exec", {
    description: "Run a shell command in a configured project. Local trusted mode: no Desktop Commander credit counter.",
    inputSchema: z.object({
      command: z.string().min(1),
      project: z.string().optional(),
      timeoutMs: z.number().int().min(1000).max(900000).default(config.exec.timeoutMs)
    })
  }, async ({ command, project, timeoutMs }) => {
    const cwd = resolveProject(project);
    const commandSha256 = crypto.createHash("sha256").update(command).digest("hex");
    await control.audit("shell.exec.start", { cwd, commandSha256, timeoutMs });
    const r = await runCommand(command, cwd, timeoutMs);
    await control.audit("shell.exec.finish", { cwd, commandSha256, code: r.code, signal: r.signal });
    return result(JSON.stringify(r, null, 2), r);
  });

  server.registerTool("control_plane_capabilities", {
    description: "Return production-control capabilities, workspace scope, security state and feature versions.",
    inputSchema: z.object({})
  }, async () => {
    const security = await control.securityStatus();
    const data = {
      controlPlane: control.version,
      server: "ludomada-mcp-commander",
      access: {
        workspaceRoots: roots(),
        defaultProject: config.defaultProject,
        arbitraryShell: true,
        audit: true,
        fileSnapshots: true,
        backgroundJobs: true,
        browserCDPProbe: true,
        githubCLI: true,
        androidADB: true
      },
      security
    };
    return result(JSON.stringify(data, null, 2), data);
  });

  server.registerTool("health_check", {
    description: "Run an end-to-end health and production preflight for the local developer plane.",
    inputSchema: z.object({ project: z.string().optional() })
  }, async ({ project }) => {
    const data = await control.health(project);
    return result(JSON.stringify(data, null, 2), data);
  });

  server.registerTool("audit_log", {
    description: "Read recent non-secret audit events.",
    inputSchema: z.object({ limit: z.number().int().min(1).max(500).default(100) })
  }, async ({ limit }) => {
    const data = await control.auditRead(limit);
    return result(JSON.stringify(data, null, 2), data);
  });

  server.registerTool("snapshot_file", {
    description: "Create an explicit file rollback snapshot before a high-risk edit.",
    inputSchema: z.object({ path: z.string(), project: z.string().optional() })
  }, async ({ path: p, project }) => {
    const file = resolveSafe(p, project);
    const snapshotId = await control.snapshotFile(file);
    return result(JSON.stringify({ snapshotId, path: file }, null, 2), { snapshotId, path: file });
  });

  server.registerTool("rollback_snapshot", {
    description: "Restore a previously created file snapshot.",
    inputSchema: z.object({ snapshotId: z.string() })
  }, async ({ snapshotId }) => {
    const data = await control.rollbackSnapshot(snapshotId);
    return result(JSON.stringify(data, null, 2), data);
  });

  server.registerTool("developer_job_start", {
    description: "Start an audited, cancellable developer workflow in the background.",
    inputSchema: z.object({
      profile: z.enum(["diagnose", "verify", "build_debug", "production_preflight", "release_smoke"]).default("diagnose"),
      project: z.string().optional(),
      steps: z.array(z.object({
        name: z.string().min(1).max(80),
        command: z.string().min(1).max(8000),
        timeoutMs: z.number().int().min(1000).max(900000).default(120000)
      })).max(24).optional()
    })
  }, async ({ profile, project, steps }) => {
    const data = await control.startJob({ profile, project, steps });
    return result(JSON.stringify(data, null, 2), data);
  });

  server.registerTool("developer_job_status", {
    description: "Read a developer job's current or persisted status.",
    inputSchema: z.object({ jobId: z.string() })
  }, async ({ jobId }) => {
    const data = await control.jobStatus(jobId);
    return result(JSON.stringify(data, null, 2), data);
  });

  server.registerTool("developer_job_logs", {
    description: "Read recent structured events for a developer job.",
    inputSchema: z.object({ jobId: z.string(), limit: z.number().int().min(1).max(500).default(200) })
  }, async ({ jobId, limit }) => {
    const data = await control.jobLogs(jobId, limit);
    return result(JSON.stringify(data, null, 2), data);
  });

  server.registerTool("developer_job_cancel", {
    description: "Cancel a running developer job and terminate its active step.",
    inputSchema: z.object({ jobId: z.string() })
  }, async ({ jobId }) => {
    const data = await control.cancelJob(jobId);
    return result(JSON.stringify(data, null, 2), data);
  });

  server.registerTool("developer_jobs", {
    description: "List recent persisted developer jobs.",
    inputSchema: z.object({ limit: z.number().int().min(1).max(100).default(50) })
  }, async ({ limit }) => {
    const data = await control.jobsList(limit);
    return result(JSON.stringify(data, null, 2), data);
  });

  server.registerTool("browser_status", {
    description: "Detect browser binaries and local Chrome DevTools Protocol endpoints/tabs.",
    inputSchema: z.object({})
  }, async () => {
    const data = await control.browserStatus();
    return result(JSON.stringify(data, null, 2), data);
  });

  server.registerTool("browser_navigate", {
    description: "Navigate a local Chromium/CDP page to an HTTP(S) URL.",
    inputSchema: z.object({
      url: z.string().url(),
      targetId: z.string().max(200).optional()
    })
  }, async ({ url, targetId }) => {
    const data = await control.browserNavigate(url, targetId);
    return result(JSON.stringify(data, null, 2), data);
  });

  server.registerTool("browser_evaluate", {
    description: "Evaluate JavaScript in a local Chromium/CDP page. The expression is hashed in the audit trail and not persisted.",
    inputSchema: z.object({
      expression: z.string().min(1).max(20000),
      targetId: z.string().max(200).optional()
    })
  }, async ({ expression, targetId }) => {
    const data = await control.browserEvaluate(expression, targetId);
    return result(JSON.stringify(data, null, 2), data);
  });

  server.registerTool("browser_screenshot", {
    description: "Capture a local Chromium/CDP page as PNG.",
    inputSchema: z.object({ targetId: z.string().max(200).optional() })
  }, async ({ targetId }) => {
    const data = await control.browserScreenshot(targetId);
    if (!data) throw new Error("CDP screenshot returned no image data");
    return {
      content: [{ type: "image", data, mimeType: "image/png" }],
      isError: false
    };
  });

  server.registerTool("android_install_apk", {
    description: "Install a workspace APK through a connected ADB device.",
    inputSchema: z.object({ apk: z.string(), project: z.string().optional() })
  }, async ({ apk, project }) => {
    const file = resolveSafe(apk, project);
    if (!file.endsWith(".apk")) throw new Error("APK path required");
    const r = await runCommand("adb get-state >/dev/null 2>&1 && adb install -r " + shellQuote(file), resolveProject(project), 120000);
    await control.audit("android.install", { apk: file, code: r.code });
    return result(JSON.stringify({ ...r, apk: file }, null, 2), { ...r, apk: file });
  });

  server.registerTool("android_launch", {
    description: "Launch an Android package through ADB.",
    inputSchema: z.object({ package: z.string().regex(/^[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+$/) })
  }, async ({ package: pkg }) => {
    const r = await runCommand("adb get-state >/dev/null 2>&1 && adb shell monkey -p " + shellQuote(pkg) + " 1", HOME, 30000);
    await control.audit("android.launch", { package: pkg, code: r.code });
    return result(r.stdout || r.stderr);
  });

  server.registerTool("github_status", {
    description: "Check local GitHub CLI authentication state without returning token material.",
    inputSchema: z.object({ project: z.string().optional() })
  }, async ({ project }) => {
    const r = await runCommand("gh auth status --hostname github.com", resolveProject(project), 30000);
    const output = (r.stdout || r.stderr).replace(/(token|password|secret)\s*[:=]\s*\S+/gi, "$1=[REDACTED]");
    return result(output);
  });

  server.registerTool("github_prs", {
    description: "List LudoMada pull requests through the local authenticated gh CLI.",
    inputSchema: z.object({ project: z.string().optional(), limit: z.number().int().min(1).max(50).default(30) })
  }, async ({ project, limit }) => {
    const args = ["pr","list","--repo",config.github.repo,"--limit",String(limit),"--json","number,title,state,isDraft,headRefName,baseRefName,mergeStateStatus,url"];
    const r = await runCommand("gh " + args.map(shellQuote).join(" "), resolveProject(project), 30000);
    return result(r.stdout || r.stderr);
  });

  server.registerTool("github_pr_review", {
    description: "Submit an explicit pull request review through gh.",
    inputSchema: z.object({
      number: z.number().int().min(1),
      action: z.enum(["approve","comment","request-changes"]),
      body: z.string().max(6500).default(""),
      project: z.string().optional()
    })
  }, async ({ number, action, body, project }) => {
    const flag = action === "approve" ? "--approve" : action === "request-changes" ? "--request-changes" : "--comment";
    const args = ["pr","review",String(number),"--repo",config.github.repo,flag];
    if (body) args.push("--body",body);
    const r = await runCommand("gh " + args.map(shellQuote).join(" "), resolveProject(project), 60000);
    await control.audit("github.pr.review", { number, action, bodySha256: crypto.createHash("sha256").update(body).digest("hex"), code: r.code });
    return result(r.stdout || r.stderr);
  });

  server.registerTool("github_pr_merge", {
    description: "Merge a pull request through gh and audit the result.",
    inputSchema: z.object({
      number: z.number().int().min(1),
      strategy: z.enum(["merge","squash","rebase"]).default("squash"),
      project: z.string().optional()
    })
  }, async ({ number, strategy, project }) => {
    const args = ["pr","merge",String(number),"--repo",config.github.repo,"--"+strategy];
    const r = await runCommand("gh " + args.map(shellQuote).join(" "), resolveProject(project), 120000);
    await control.audit("github.pr.merge", { number, strategy, code: r.code });
    return result(r.stdout || r.stderr);
  });

  server.registerTool("github_run", {
    description: "Inspect, rerun or cancel a GitHub Actions run.",
    inputSchema: z.object({
      id: z.number().int().min(1),
      action: z.enum(["view","rerun","cancel"]).default("view"),
      project: z.string().optional()
    })
  }, async ({ id: runId, action, project }) => {
    const args = action === "view"
      ? ["run","view",String(runId),"--repo",config.github.repo]
      : ["run",action,String(runId),"--repo",config.github.repo];
    const r = await runCommand("gh " + args.map(shellQuote).join(" "), resolveProject(project), 120000);
    await control.audit("github.run." + action, { runId, code: r.code });
    return result(r.stdout || r.stderr);
  });

  server.registerTool("start_process", {
    description: "Start a long-running shell process and keep its session inside this Commander instance.",
    inputSchema: z.object({
      command: z.string().min(1),
      project: z.string().optional()
    })
  }, async ({ command, project }) => {
    const cwd = resolveProject(project);
    const shell = process.env.SHELL || "/data/data/com.termux/files/usr/bin/bash";
    const child = spawn(command, { cwd, shell, env: process.env, detached: false });
    const id = crypto.randomUUID();
    const session = { id, pid: child.pid, child, output: "", cwd, command, startedAt: new Date().toISOString() };
    const append = d => { session.output += d.toString(); if (session.output.length > config.exec.maxOutputBytes) session.output = session.output.slice(-config.exec.maxOutputBytes); };
    child.stdout.on("data", append);
    child.stderr.on("data", append);
    sessions.set(id, session);
    child.on("close", code => {
      const s = sessions.get(id);
      if (s) { s.exitCode = code ?? -1; s.finishedAt = new Date().toISOString(); }
    });
    return result(JSON.stringify({ sessionId:id, pid:child.pid, cwd, command }, null, 2));
  });

  server.registerTool("process_output", {
    description: "Read output/status for a started process session.",
    inputSchema: z.object({
      sessionId: z.string(),
      clear: z.boolean().default(false)
    })
  }, async ({ sessionId, clear }) => {
    const s = sessions.get(sessionId);
    if (!s) return result("SESSION_NOT_FOUND");
    const out = {
      sessionId: s.id, pid: s.pid, command: s.command, cwd: s.cwd,
      running: s.exitCode === undefined,
      exitCode: s.exitCode ?? null,
      startedAt: s.startedAt, finishedAt: s.finishedAt ?? null,
      output: s.output
    };
    if (clear) s.output = "";
    return result(JSON.stringify(out, null, 2), out);
  });

  server.registerTool("kill_process", {
    description: "Terminate one of this Commander's running process sessions.",
    inputSchema: z.object({ sessionId: z.string() })
  }, async ({ sessionId }) => {
    const s = sessions.get(sessionId);
    if (!s) return result("SESSION_NOT_FOUND");
    try { s.child.kill("SIGTERM"); } catch {}
    return result("SIGTERM sent to session " + sessionId + " (pid " + s.pid + ")");
  });

  server.registerTool("git_status", {
    description: "Show concise Git status and current branch/HEAD for a project.",
    inputSchema: z.object({ project: z.string().optional() })
  }, async ({ project }) => {
    const r = await runCommand("git status --short --branch && printf '\\nHEAD: ' && git rev-parse --short HEAD", project, 20000);
    return result(r.stdout || r.stderr);
  });

  server.registerTool("git_diff", {
    description: "Show working-tree diff statistics and/or patch.",
    inputSchema: z.object({
      project: z.string().optional(),
      staged: z.boolean().default(false),
      statOnly: z.boolean().default(false)
    })
  }, async ({ project, staged, statOnly }) => {
    const args = staged ? "--cached" : "";
    const stat = statOnly ? "--stat" : "";
    const r = await runCommand("git diff " + args + " " + stat, project, 30000);
    return result(r.stdout || r.stderr);
  });

  server.registerTool("git_commit", {
    description: "Create a local Git commit. Push is deliberately a separate explicit exec operation.",
    inputSchema: z.object({
      message: z.string().min(1),
      project: z.string().optional()
    })
  }, async ({ message, project }) => {
    const safe = message.replace(/'/g, "'\\''");
    const r = await runCommand("git add -A && git commit -m '" + safe + "'", project, 60000);
    return result(r.stdout || r.stderr);
  });

  server.registerTool("project_scan", {
    description: "Detect the major technologies, package managers, Android/Gradle project, and Git state.",
    inputSchema: z.object({ project: z.string().optional() })
  }, async ({ project }) => {
    const p = resolveProject(project);
    const exists = async f => !!(await fs.stat(path.join(p,f)).catch(()=>null));
    const found = {
      project: p,
      git: await exists(".git"),
      gradle: await exists("gradlew") || await exists("build.gradle") || await exists("build.gradle.kts"),
      androidApp: await exists("android-app") || await exists("app/src/main/AndroidManifest.xml"),
      node: await exists("package.json"),
      python: await exists("pyproject.toml") || await exists("requirements.txt"),
      docker: await exists("Dockerfile"),
      github: await exists(".github")
    };
    if (found.git) {
      const g = await runCommand("git branch --show-current; git rev-parse --short HEAD; git status --porcelain | wc -l", p, 15000);
      found.gitState = g.stdout.trim().split("\n");
    }
    return result(JSON.stringify(found, null, 2), found);
  });

  server.registerTool("build_apk", {
    description: "Build an Android debug APK using the project's Gradle wrapper.",
    inputSchema: z.object({
      project: z.string().optional(),
      task: z.string().default("assembleDebug"),
      timeoutMs: z.number().int().min(10000).max(900000).default(600000)
    })
  }, async ({ project, task, timeoutMs }) => {
    const p = resolveProject(project);
    const gradlew = path.join(p, "gradlew");
    let command;
    if (fsSync.existsSync(gradlew)) command = "chmod +x ./gradlew && ./gradlew " + task;
    else command = "gradle " + task;
    const r = await runCommand(command, p, timeoutMs);
    return result(JSON.stringify({ ...r, apkCandidates: await findApks(p) }, null, 2));
  });

  server.registerTool("test_project", {
    description: "Run the project's most relevant local test suite (Gradle, npm, or pytest).",
    inputSchema: z.object({
      project: z.string().optional(),
      timeoutMs: z.number().int().min(10000).max(900000).default(600000),
      command: z.string().optional()
    })
  }, async ({ project, timeoutMs, command }) => {
    const p = resolveProject(project);
    let cmd = command;
    if (!cmd) {
      if (fsSync.existsSync(path.join(p,"gradlew"))) cmd = "chmod +x ./gradlew && ./gradlew test";
      else if (fsSync.existsSync(path.join(p,"package.json"))) cmd = "npm test";
      else if (fsSync.existsSync(path.join(p,"pyproject.toml")) || fsSync.existsSync(path.join(p,"pytest.ini"))) cmd = "pytest";
      else cmd = "git status --short";
    }
    const r = await runCommand(cmd, p, timeoutMs);
    return result(JSON.stringify(r, null, 2), r);
  });

  server.registerTool("android_devices", {
    description: "List ADB-visible Android devices/emulators.",
    inputSchema: z.object({})
  }, async () => {
    const r = await runCommand("command -v adb >/dev/null 2>&1 && adb devices -l || echo ADB_NOT_AVAILABLE", HOME, 15000);
    return result(r.stdout || r.stderr);
  });

  server.registerTool("android_logcat", {
    description: "Read Android logcat, optionally filtered by a pattern.",
    inputSchema: z.object({
      pattern: z.string().optional(),
      lines: z.number().int().min(1).max(1000).default(200)
    })
  }, async ({ pattern, lines }) => {
    const filter = pattern ? " | grep -Ei " + JSON.stringify(pattern) : "";
    const r = await runCommand("adb logcat -d -t " + lines + filter, HOME, 30000);
    return result(r.stdout || r.stderr);
  });

  server.registerTool("android_screenshot", {
    description: "Capture an ADB screenshot to a file inside the project.",
    inputSchema: z.object({
      output: z.string().default("artifacts/commander-screenshot.png"),
      project: z.string().optional()
    })
  }, async ({ output, project }) => {
    const file = resolveSafe(output, project);
    await fs.mkdir(path.dirname(file), { recursive: true });
    const r = await runCommand("adb exec-out screencap -p > " + JSON.stringify(file), project, 30000);
    return result(JSON.stringify({ ...r, file }, null, 2));
  });

  server.registerTool("ludomada_inventory", {
    description: "LudoMada-focused health inventory without changing authoritative logic.",
    inputSchema: z.object({ project: z.string().optional() })
  }, async ({ project }) => {
    const p = resolveProject(project || config.defaultProject);
    const r = await runCommand(
      "printf '%s\\n' '=== GIT ==='; git status --short --branch; git rev-parse --short HEAD; " +
      "printf '%s\\n' '=== CORE ==='; " +
      "(find android-app -type f -name '*.kt' | wc -l 2>/dev/null || true); " +
      "(find android-app -type f -name '*.glb' | wc -l 2>/dev/null || true); " +
      "(find . -maxdepth 1 -type f -name '*.native.test.mjs' | wc -l); " +
      "printf '%s\\n' '=== PATHS ==='; " +
      'for x in android-app .github ci .ludomada-agent; do [ -e "$x" ] && echo "$x"; done',
      p, 30000
    );
    return result(r.stdout || r.stderr);
  });

  server.registerTool("find_projects", {
    description: "Find Git repositories and common project roots under configured workspace roots.",
    inputSchema: z.object({
      maxDepth: z.number().int().min(1).max(5).default(3)
    })
  }, async ({ maxDepth }) => {
    const found = [];
    async function scan(dir, depth) {
      if (depth > maxDepth || found.length >= 100) return;
      let entries;
      try { entries = await fs.readdir(dir, { withFileTypes: true }); } catch { return; }
      for (const e of entries) {
        if (!e.isDirectory() || e.name === "node_modules" || e.name === ".git") continue;
        const full = path.join(dir, e.name);
        if (e.name === ".gradle" || e.name === "build") continue;
        if (await fs.stat(path.join(full, ".git")).catch(()=>null)) found.push(full);
        else await scan(full, depth + 1);
      }
    }
    for (const r of roots()) await scan(r, 0);
    return result(found.join("\n") || "[NO REPOSITORIES FOUND]");
  });
}

async function findApks(project) {
  const results = [];
  async function walk(dir, depth) {
    if (depth > 5 || results.length >= 50) return;
    let entries;
    try { entries = await fs.readdir(dir, { withFileTypes: true }); } catch { return; }
    for (const e of entries) {
      if (e.name === "node_modules" || e.name === ".git") continue;
      const full = path.join(dir, e.name);
      if (e.isDirectory()) await walk(full, depth + 1);
      else if (e.isFile() && e.name.endsWith(".apk")) results.push(full);
    }
  }
  await walk(project, 0);
  return results;
}

const control = createControlPlane({ HOME, config, resolveProject, resolveSafe, runCommand, limitText, findApks });
await control.init();

function buildServer() {
  const server = new McpServer({
    name: "ludomada-mcp-commander",
    version: "1.0.0"
  }, { capabilities: { tools: {} } });
  registerTools(server);
  return server;
}

async function doctor() {
  const checks = [];
  checks.push(["node", process.version]);
  checks.push(["root", ROOT]);
  checks.push(["config", fsSync.existsSync(CONFIG_PATH) ? "present" : "using defaults"]);
  checks.push(["defaultProject", fsSync.existsSync(config.defaultProject) ? "present" : "MISSING"]);
  const sdk = await runCommand("npm ls @modelcontextprotocol/server @modelcontextprotocol/node zod --depth=0", ROOT, 30000);
  checks.push(["dependencies", (sdk.stdout || sdk.stderr).trim()]);
  const r = await runCommand("git --version; command -v adb || true; command -v rg || true; command -v gradle || true", HOME, 10000);
  checks.push(["toolchain", r.stdout.trim()]);
  console.log(checks.map(([k,v]) => k + ": " + v).join("\n"));
}

async function httpMain() {
  const handler = createMcpHandler(buildServer, { legacy: "stateless" });
  const nodeHandler = toNodeHandler(handler);
  const validateHost = localhostHostValidation();
  const validateOrigin = localhostOriginValidation();
  const server = createHttpServer((req,res) => {
    if (!validateHost(req,res)) return;
    if (!validateOrigin(req,res)) return;

    // OAuth Protected Resource Metadata is public discovery metadata.
    // Keep the actual MCP endpoint protected by the Commander bearer token.
    const pathname = new URL(req.url || "/", "http://127.0.0.1").pathname;
    if (req.method === "GET" && (pathname === "/.well-known/oauth-protected-resource" || pathname === "/.well-known/oauth-protected-resource/mcp")) {
      res.writeHead(200, {"content-type":"application/json", "cache-control":"no-store"});
      res.end(JSON.stringify({resource: `http://${config.http.host}:${config.http.port}/mcp`}));
      return;
    }

    const token = config.http.token;
    if (token) {
      const got = String(req.headers.authorization || "");
      if (got !== "Bearer " + token) {
        res.writeHead(401, {"content-type":"application/json"});
        res.end(JSON.stringify({error:"Unauthorized"}));
        return;
      }
    }
    void nodeHandler(req,res);
  });
  await new Promise(resolve => server.listen(config.http.port, config.http.host, resolve));
  console.error("LudoMada MCP Commander listening on http://" + config.http.host + ":" + config.http.port + "/mcp");
  process.on("SIGINT", async () => { await handler.close(); server.close(); process.exit(0); });
  process.on("SIGTERM", async () => { await handler.close(); server.close(); process.exit(0); });
}

const mode = process.argv.includes("--http") ? "http" : process.argv.includes("--doctor") ? "doctor" : "stdio";
if (mode === "doctor") await doctor();
else if (mode === "http") await httpMain();
else await serveStdio(buildServer);
