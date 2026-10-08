import fs from "node:fs/promises";
import fsSync from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import { spawn } from "node:child_process";

const CONTROL_VERSION = "1.0.0";
const MAX_JOB_STEPS = 24;
const MAX_AUDIT_LINES = 500;
const MAX_SNAPSHOT_BYTES = 16 * 1024 * 1024;
const JOB_CONCURRENCY = 2;

function id(prefix) {
  return prefix + "_" + crypto.randomBytes(9).toString("hex");
}

function digest(value) {
  return crypto.createHash("sha256").update(String(value)).digest("hex");
}

function shellQuote(value) {
  return "'" + String(value).replaceAll("'", "'\\''") + "'";
}

async function exists(p) {
  return !!(await fs.stat(p).catch(() => null));
}

async function fetchText(url, timeoutMs = 2500) {
  try {
    const r = await fetch(url, { signal: AbortSignal.timeout(timeoutMs) });
    return { ok: r.ok, status: r.status, text: await r.text() };
  } catch (e) {
    return { ok: false, status: 0, text: "", error: String(e.message || e) };
  }
}

export function createControlPlane({ HOME, config, resolveProject, resolveSafe, runCommand, limitText, findApks }) {
  const STATE = path.join(HOME, ".ludomada-commander", "control-plane");
  const AUDIT = path.join(STATE, "audit.jsonl");
  const SNAPSHOTS = path.join(STATE, "snapshots");
  const JOBS = path.join(STATE, "jobs");
  const jobs = new Map();

  async function init() {
    await fs.mkdir(SNAPSHOTS, { recursive: true, mode: 0o700 });
    await fs.mkdir(JOBS, { recursive: true, mode: 0o700 });
    await fs.chmod(STATE, 0o700).catch(() => {});
  }

  async function audit(action, details = {}, outcome = "ok") {
    await init();
    const event = {
      id: id("audit"),
      timestamp: new Date().toISOString(),
      action,
      outcome,
      actor: "ludomada-personal-mcp",
      ...details
    };
    await fs.appendFile(AUDIT, JSON.stringify(event) + "\n", { encoding: "utf8", mode: 0o600 });
    await fs.chmod(AUDIT, 0o600).catch(() => {});
    return event;
  }

  async function auditRead(limit = 100) {
    await init();
    const n = Math.min(Math.max(Number(limit) || 100, 1), MAX_AUDIT_LINES);
    const text = await fs.readFile(AUDIT, "utf8").catch(() => "");
    const lines = text.split("\n").filter(Boolean).slice(-n);
    return lines.map(line => {
      try { return JSON.parse(line); } catch { return { raw: line }; }
    });
  }

  async function snapshotFile(file) {
    await init();
    const target = path.resolve(file);
    const st = await fs.lstat(target).catch(() => null);
    const snapshotId = id("snap");
    const record = {
      id: snapshotId,
      createdAt: new Date().toISOString(),
      path: target,
      existed: !!st,
      type: st ? (st.isFile() ? "file" : st.isDirectory() ? "dir" : "other") : "missing",
      mode: st ? (st.mode & 0o7777) : null,
      size: st?.size ?? 0,
      contentBase64: null
    };
    if (st?.isFile()) {
      if (st.size > MAX_SNAPSHOT_BYTES) throw new Error("snapshot exceeds safety size limit");
      record.contentBase64 = (await fs.readFile(target)).toString("base64");
    }
    const out = path.join(SNAPSHOTS, snapshotId + ".json");
    await fs.writeFile(out, JSON.stringify(record), { encoding: "utf8", mode: 0o600 });
    await audit("snapshot.create", {
      snapshotId,
      path: target,
      existed: record.existed,
      size: record.size
    });
    return snapshotId;
  }

  async function rollbackSnapshot(snapshotId) {
    await init();
    if (!/^snap_[a-f0-9]{18}$/.test(String(snapshotId))) throw new Error("invalid snapshot id");
    const file = path.join(SNAPSHOTS, snapshotId + ".json");
    const record = JSON.parse(await fs.readFile(file, "utf8"));
    const target = path.resolve(record.path);
    const parent = path.dirname(target);

    if (!record.existed) {
      await fs.rm(target, { recursive: true, force: true });
    } else if (record.type === "file") {
      await fs.mkdir(parent, { recursive: true });
      const tmp = target + ".restore-" + crypto.randomBytes(6).toString("hex");
      await fs.writeFile(tmp, Buffer.from(record.contentBase64, "base64"), { mode: record.mode || 0o600 });
      if (record.mode) await fs.chmod(tmp, record.mode).catch(() => {});
      await fs.rename(tmp, target);
    } else {
      throw new Error("rollback currently supports file/missing snapshots only");
    }

    await audit("snapshot.rollback", { snapshotId, path: target });
    return { snapshotId, path: target, restored: true };
  }

  async function writeAtomic(target, content) {
    const dir = path.dirname(target);
    await fs.mkdir(dir, { recursive: true });
    const st = await fs.stat(target).catch(() => null);
    const mode = st?.mode & 0o7777;
    const tmp = target + ".tmp-" + crypto.randomBytes(6).toString("hex");
    await fs.writeFile(tmp, content, "utf8");
    if (mode) await fs.chmod(tmp, mode).catch(() => {});
    await fs.rename(tmp, target);
  }

  async function securityStatus() {
    const rows = [];
    const paths = [
      [".config/openai/tunnel-client/runtime.key", path.join(HOME, ".config/openai/tunnel-client/runtime.key")],
      ["commander config", path.join(HOME, "ludomada-mcp-commander", "config.json")],
      ["audit log", AUDIT]
    ];
    for (const [name, p] of paths) {
      const st = await fs.stat(p).catch(() => null);
      rows.push({
        name,
        present: !!st,
        mode: st ? "0" + (st.mode & 0o777).toString(8) : null,
        private: st ? ((st.mode & 0o077) === 0) : true
      });
    }
    let root = false;
    try {
      const r = await new Promise(resolve => {
        const c = spawn("su", ["-c", "id"], { stdio: ["ignore", "pipe", "pipe"] });
        c.on("close", code => resolve(code === 0));
        c.on("error", () => resolve(false));
      });
      root = r === true;
    } catch {}
    return { rootAvailable: root, files: rows, stateDir: STATE };
  }

  async function browserStatus() {
    const binaries = [];
    for (const name of ["chromium", "chromium-browser", "google-chrome", "com.termux.x11"]) {
      const p = await new Promise(resolve => {
        const c = spawn("sh", ["-lc", "command -v " + shellQuote(name)], { stdio: ["ignore", "pipe", "ignore"] });
        let s = "";
        c.stdout.on("data", d => s += d);
        c.on("close", () => resolve(s.trim() || null));
        c.on("error", () => resolve(null));
      });
      if (p) binaries.push({ name, path: p });
    }
    const endpoints = [];
    for (const port of [9222, 9223, 9229]) {
      const version = await fetchText("http://127.0.0.1:" + port + "/json/version", 1200);
      if (version.ok) endpoints.push({ port, version: (() => { try { return JSON.parse(version.text); } catch { return version.text.slice(0, 1000); } })() });
    }
    let tabs = [];
    for (const ep of endpoints) {
      const list = await fetchText("http://127.0.0.1:" + ep.port + "/json/list", 1200);
      if (list.ok) {
        try { tabs.push({ port: ep.port, tabs: JSON.parse(list.text).slice(0, 50) }); } catch {}
      }
    }
    return { supported: true, binaries, cdpEndpoints: endpoints, tabs };
  }

  async function cdpTarget(targetId) {
    const list = await fetchText("http://127.0.0.1:9222/json/list", 2000);
    if (!list.ok) throw new Error("CDP endpoint unavailable");
    let tabs;
    try { tabs = JSON.parse(list.text); } catch { throw new Error("invalid CDP target list"); }
    const pages = tabs.filter(x => x.type === "page" && x.webSocketDebuggerUrl);
    const target = targetId ? pages.find(x => x.id === targetId) : pages[0];
    if (!target) throw new Error("no matching browser page target");
    return target;
  }

  async function cdpCall({ targetId, method, params = {}, timeoutMs = 10000 }) {
    const target = await cdpTarget(targetId);
    const ws = new WebSocket(target.webSocketDebuggerUrl);
    return await new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        try { ws.close(); } catch {}
        reject(new Error("CDP timeout"));
      }, Math.min(Math.max(Number(timeoutMs) || 10000, 1000), 30000));
      const finish = (fn, value) => { clearTimeout(timer); try { ws.close(); } catch {} fn(value); };
      ws.addEventListener("open", () => {
        ws.send(JSON.stringify({ id: 1, method, params }));
      });
      ws.addEventListener("message", event => {
        let msg;
        try { msg = JSON.parse(String(event.data)); } catch { return; }
        if (msg.id !== 1) return;
        if (msg.error) finish(reject, new Error(JSON.stringify(msg.error)));
        else finish(resolve, msg.result || {});
      });
      ws.addEventListener("error", () => finish(reject, new Error("CDP websocket error")));
    });
  }

  async function browserNavigate(url, targetId) {
    const parsed = new URL(url);
    if (!["http:", "https:"].includes(parsed.protocol)) throw new Error("only http/https browser URLs are allowed");
    const result = await cdpCall({ targetId, method: "Page.navigate", params: { url: parsed.toString() } });
    await audit("browser.navigate", { targetId: targetId || null, urlSha256: digest(parsed.toString()) });
    return { targetId: targetId || null, url: parsed.toString(), result };
  }

  async function browserEvaluate(expression, targetId) {
    if (String(expression).length > 20000) throw new Error("browser expression too large");
    const result = await cdpCall({
      targetId,
      method: "Runtime.evaluate",
      params: { expression: String(expression), returnByValue: true, awaitPromise: true, userGesture: true }
    });
    await audit("browser.evaluate", { targetId: targetId || null, expressionSha256: digest(expression) });
    return { targetId: targetId || null, result };
  }

  async function browserScreenshot(targetId) {
    const result = await cdpCall({ targetId, method: "Page.captureScreenshot", params: { format: "png" } });
    await audit("browser.screenshot", { targetId: targetId || null });
    return result?.data || "";
  }

  async function health(project) {
    const p = resolveProject(project);
    const git = await runCommand(
      `printf 'branch='; git branch --show-current; printf '\\nhead='; git rev-parse --short HEAD; printf '\\ngitDirty='; test -n "$(git status --porcelain)" && echo true || echo false; printf '\\n'; git diff --check`,
      p, 20000
    );
    const toolchain = await runCommand(
      "printf 'node='; node --version 2>/dev/null || echo missing; printf 'npm='; npm --version 2>/dev/null || echo missing; printf 'python='; python3 --version 2>/dev/null | tr -d '\\n' || echo missing; printf '\\ngit='; git --version 2>/dev/null | tr -d '\\n' || echo missing; printf '\\ngradle='; (command -v gradle || true); printf '\\nadb='; (command -v adb || true)",
      p, 15000
    );
    const tunnelHealth = await fetchText("http://127.0.0.1:" + (process.env.PERSONAL_MCP_HEALTH_PORT || "8790") + "/healthz");
    const webHealth = await fetchText("http://127.0.0.1:8788/health");
    const mcpHttp = await fetchText("http://127.0.0.1:" + (config.http?.port || 8787) + "/.well-known/oauth-protected-resource");
    const apk = await findApks(p);
    const browser = await browserStatus();
    const security = await securityStatus();
    const checks = {
      project: !!(await exists(p)),
      git: git.code === 0,
      gitDiffCheck: git.code === 0,
      tunnelHealth: tunnelHealth.ok,
      webPanel: webHealth.ok,
      localMcpHttp: mcpHttp.ok,
      toolchain: !toolchain.stdout.includes("node=missing") && !toolchain.stdout.includes("git=missing"),
      browserOptional: true,
      securityPrivate: security.files.every(x => x.private),
    };
    const required = ["project", "git", "gitDiffCheck", "toolchain", "securityPrivate"];
    const healthy = required.every(k => checks[k]);
    return {
      controlPlaneVersion: CONTROL_VERSION,
      healthy,
      timestamp: new Date().toISOString(),
      checks,
      git: git.stdout || git.stderr,
      toolchain: toolchain.stdout || toolchain.stderr,
      tunnel: { ok: tunnelHealth.ok, status: tunnelHealth.status || 0, body: tunnelHealth.text.slice(0, 4000) },
      webPanel: { ok: webHealth.ok, status: webHealth.status || 0 },
      localMcpHttp: { ok: mcpHttp.ok, status: mcpHttp.status || 0 },
      apkCandidates: apk.slice(-20),
      browser,
      security
    };
  }

  function profiles(project) {
    const gradle = fsSync.existsSync(path.join(project, "gradlew"));
    const node = fsSync.existsSync(path.join(project, "package.json"));
    const gradleTest = gradle ? "chmod +x ./gradlew && ./gradlew test" : "printf '%s\n' 'GRADLE_TEST_SKIPPED: gradlew not found'";
    const gradleBuild = gradle ? "chmod +x ./gradlew && ./gradlew assembleDebug" : "printf '%s\n' 'GRADLE_BUILD_SKIPPED: gradlew not found'";
    const npmTest = node ? "npm run test:fast" : "printf '%s\n' 'NODE_TEST_SKIPPED: package.json not found'";
    const npmBuild = node ? "npm run build" : "printf '%s\n' 'NODE_BUILD_SKIPPED: package.json not found'";
    const common = [
      { name: "git_status", command: "git status --short --branch && git rev-parse --short HEAD", timeoutMs: 20000 },
      { name: "git_diff_check", command: "git diff --check", timeoutMs: 20000 },
      { name: "toolchain", command: "node --version; npm --version; python3 --version; git --version; (./gradlew --version | head -4 || true)", timeoutMs: 30000 }
    ];
    return {
      diagnose: common,
      verify: [...common, { name: "npm_tests", command: npmTest, timeoutMs: 900000 }, { name: "npm_build", command: npmBuild, timeoutMs: 900000 }, { name: "gradle_tests", command: gradleTest, timeoutMs: 900000 }],
      build_debug: [...common, { name: "android_debug_apk", command: gradleBuild, timeoutMs: 900000 }, { name: "apk_inventory", command: "find . -type f -name '*.apk' -not -path './node_modules/*' | sort | tail -30", timeoutMs: 30000 }],
      production_preflight: [...common, { name: "npm_tests", command: npmTest, timeoutMs: 900000 }, { name: "npm_build", command: npmBuild, timeoutMs: 900000 }, { name: "gradle_tests", command: gradleTest, timeoutMs: 900000 }, { name: "apk_inventory", command: "find . -type f -name '*.apk' -not -path './node_modules/*' | sort | tail -30", timeoutMs: 30000 }],
      release_smoke: [...common, { name: "android_debug_apk", command: gradleBuild, timeoutMs: 900000 }, { name: "adb_devices", command: "adb devices -l 2>/dev/null || true", timeoutMs: 30000 }, { name: "apk_hashes", command: "find . -type f -name '*.apk' -print0 | xargs -0 -r sha256sum | tail -20", timeoutMs: 30000 }]
    };
  }

  async function persistJob(job) {
    await init();
    const safe = { ...job };
    delete safe.currentChild;
    if (Array.isArray(safe.plan)) {
      safe.plan = safe.plan.map(step => ({
        name: step.name,
        timeoutMs: step.timeoutMs,
        commandSha256: digest(step.command)
      }));
    }
    await fs.writeFile(path.join(JOBS, job.id + ".json"), JSON.stringify(safe, null, 2), { encoding: "utf8", mode: 0o600 });
  }

  async function appendJobLog(jobId, line) {
    await init();
    await fs.appendFile(path.join(JOBS, jobId + ".log"), line + "\n", { encoding: "utf8", mode: 0o600 });
  }

  async function executeStep(job, step, project) {
    return await new Promise(resolve => {
      const shell = process.env.SHELL || "/data/data/com.termux/files/usr/bin/bash";
      const timeoutMs = Math.min(Math.max(Number(step.timeoutMs) || 120000, 1000), 900000);
      const child = spawn(step.command, { cwd: project, shell, env: process.env });
      job.currentPid = child.pid;
      job.currentCommandSha256 = digest(step.command);
      let stdout = "";
      let stderr = "";
      let timedOut = false;
      const timer = setTimeout(() => {
        timedOut = true;
        try { child.kill("SIGTERM"); } catch {}
        setTimeout(() => { try { child.kill("SIGKILL"); } catch {} }, 1500);
      }, timeoutMs);
      child.stdout.on("data", d => { stdout += d.toString(); if (stdout.length > 120000) stdout = stdout.slice(-120000); });
      child.stderr.on("data", d => { stderr += d.toString(); if (stderr.length > 120000) stderr = stderr.slice(-120000); });
      job.currentChild = child;
      child.on("close", code => {
        clearTimeout(timer);
        job.currentChild = null;
        job.currentPid = null;
        job.currentCommandSha256 = null;
        resolve({
          name: step.name,
          code: code ?? -1,
          signal: timedOut ? "TIMEOUT" : null,
          stdout: limitText(stdout, 120000),
          stderr: limitText(stderr, 120000)
        });
      });
      child.on("error", err => {
        clearTimeout(timer);
        job.currentChild = null;
        job.currentPid = null;
        resolve({ name: step.name, code: -1, signal: null, stdout: "", stderr: err.message });
      });
    });
  }

  async function runJob(job) {
    job.status = "running";
    job.startedAt = new Date().toISOString();
    job.steps = [];
    await persistJob(job);
    await audit("job.start", { jobId: job.id, profile: job.profile, project: job.project });

    try {
      for (const step of job.plan) {
        if (job.cancelRequested) {
          job.status = "cancelled";
          break;
        }
        job.currentStep = step.name;
        await appendJobLog(job.id, JSON.stringify({ event: "step.start", at: new Date().toISOString(), name: step.name, commandSha256: digest(step.command) }));
        const result = await executeStep(job, step, job.project);
        job.steps.push({ ...result, finishedAt: new Date().toISOString(), commandSha256: digest(step.command) });
        await appendJobLog(job.id, JSON.stringify({ event: "step.finish", at: new Date().toISOString(), ...result, command: undefined }));
        if (result.code !== 0) {
          job.status = "failed";
          break;
        }
      }
      if (job.status === "running") job.status = "succeeded";
    } catch (e) {
      job.status = "failed";
      job.error = String(e.message || e);
    } finally {
      job.currentStep = null;
      job.currentPid = null;
      job.currentChild = undefined;
      job.finishedAt = new Date().toISOString();
      job.durationMs = new Date(job.finishedAt).getTime() - new Date(job.startedAt).getTime();
      const safeJob = { ...job };
      delete safeJob.currentChild;
      await persistJob(safeJob);
      await audit("job.finish", { jobId: job.id, profile: job.profile, status: job.status, durationMs: job.durationMs });
    }
  }

  async function startJob({ profile = "diagnose", project, steps }) {
    await init();
    const p = resolveProject(project);
    if ([...jobs.values()].filter(j => j.status === "running").length >= JOB_CONCURRENCY) {
      throw new Error("developer job concurrency limit reached");
    }
    const plan = steps?.length
      ? steps.map((s, i) => ({
          name: String(s.name || "step_" + (i + 1)).slice(0, 80),
          command: String(s.command || "").trim(),
          timeoutMs: Number(s.timeoutMs) || 120000
        }))
      : profiles(p)[profile];
    if (!plan) throw new Error("unknown developer job profile");
    if (!plan.length || plan.length > MAX_JOB_STEPS) throw new Error("invalid job plan");
    if (plan.some(s => !s.command)) throw new Error("job command missing");
    const job = {
      id: id("job"),
      profile: steps?.length ? "custom" : profile,
      project: p,
      status: "queued",
      createdAt: new Date().toISOString(),
      startedAt: null,
      finishedAt: null,
      durationMs: null,
      plan,
      steps: []
    };
    jobs.set(job.id, job);
    await persistJob(job);
    queueMicrotask(() => runJob(job));
    return { id: job.id, profile: job.profile, project: p, status: job.status, plan: plan.map(x => ({ name: x.name, timeoutMs: x.timeoutMs, commandSha256: digest(x.command) })) };
  }

  async function jobStatus(jobId) {
    const live = jobs.get(jobId);
    const source = live || (() => null)();
    if (source) {
      const safe = { ...source };
      delete safe.currentChild;
      if (Array.isArray(safe.plan)) {
        safe.plan = safe.plan.map(step => ({
          name: step.name,
          timeoutMs: step.timeoutMs,
          commandSha256: digest(step.command)
        }));
      }
      return safe;
    }
    const file = path.join(JOBS, jobId + ".json");
    const data = await fs.readFile(file, "utf8").catch(() => null);
    if (!data) throw new Error("job not found");
    return JSON.parse(data);
  }

  async function jobLogs(jobId, limit = 200) {
    const data = await fs.readFile(path.join(JOBS, jobId + ".log"), "utf8").catch(() => "");
    return data.split("\n").filter(Boolean).slice(-Math.min(Math.max(Number(limit) || 200, 1), 500)).map(x => {
      try { return JSON.parse(x); } catch { return { raw: x }; }
    });
  }

  async function cancelJob(jobId) {
    const job = jobs.get(jobId);
    if (!job) throw new Error("job not found or already finalized");
    job.cancelRequested = true;
    try { job.currentChild?.kill("SIGTERM"); } catch {}
    await audit("job.cancel", { jobId });
    return { id: jobId, status: job.currentChild ? "cancelling" : job.status };
  }

  async function jobsList(limit = 50) {
    await init();
    const files = (await fs.readdir(JOBS)).filter(x => x.endsWith(".json")).sort().slice(-Math.min(Math.max(Number(limit) || 50, 1), 100));
    const out = [];
    for (const name of files) {
      try { out.push(JSON.parse(await fs.readFile(path.join(JOBS, name), "utf8"))); } catch {}
    }
    return out.reverse();
  }

  return {
    version: CONTROL_VERSION,
    init,
    audit,
    auditRead,
    snapshotFile,
    rollbackSnapshot,
    writeAtomic,
    health,
    securityStatus,
    browserStatus,
    browserNavigate,
    browserEvaluate,
    browserScreenshot,
    startJob,
    jobStatus,
    jobLogs,
    cancelJob,
    jobsList
  };
}
