import crypto from "node:crypto";
import fs from "node:fs/promises";
import path from "node:path";
import { spawn } from "node:child_process";
import { redactSensitive } from "./security.mjs";
import { listMcpIntegrations, listAllowedMcpTools, callAllowedMcpTool } from "./mcp-integrations.mjs";

const BLOCKED_DIRS = new Set([".git", ".config", ".ssh", ".vercel", "node_modules", "vendor", "build", "dist", "coverage", ".next", ".cache"]);
const BLOCKED_NAMES = /^(?:\.env(?:\..*)?|\.npmrc|\.git-credentials|credentials(?:\..*)?|secrets?(?:\..*)?|.*\.(?:pem|key|p12|pfx|jks|keystore))$/i;
const TEXT_EXTENSIONS = new Set([".mjs", ".cjs", ".js", ".jsx", ".ts", ".tsx", ".json", ".md", ".yaml", ".yml", ".html", ".css", ".scss", ".xml", ".sql", ".sh", ".py", ".kt", ".kts", ".java", ".gradle", ".properties", ".txt", ".toml", ".lock", ".svg", ".vue", ".svelte", ".go", ".rs", ".dart", ".gitignore", ".editorconfig"]);
const COMMAND_PROGRAMS = new Set([
  "git", "node", "npm", "npx", "python", "python3", "java", "javac", "gradle", "./gradlew", "adb",
  "ls", "cat", "mkdir", "cp", "mv", "rm", "touch", "find", "grep", "sed", "awk", "head", "tail",
  "pwd", "whoami", "uname", "env", "printenv", "curl", "wget", "jq", "tar", "zip", "unzip", "chmod",
  "stat", "du", "df", "ps", "pgrep", "pkill", "kill", "date", "printf", "true", "false", "test",
  "termux-info", "pkg"
]);
const INLINE_EVAL_FLAGS = {
  node: new Set(["-e", "--eval", "-p", "--print", "-r", "--require", "--import"]),
  python: new Set(["-c", "--command"]),
  python3: new Set(["-c", "--command"])
};
const TASKS = Object.freeze({
  git_status: ["git", ["status", "--short", "--branch"], 15000],
  git_diff_stat: ["git", ["diff", "--stat"], 15000],
  npm_test: ["npm", ["test"], 90000],
  npm_lint: ["npm", ["run", "lint"], 60000],
  npm_build: ["npm", ["run", "build"], 120000],
  android_assemble_debug: ["./gradlew", ["assembleDebug"], 180000],
  android_test: ["./gradlew", ["testDebugUnitTest"], 180000]
});

function partsFor(relative, allowRoot = false) {
  if (allowRoot && (relative === "" || relative === ".")) return [];
  if (typeof relative !== "string" || !relative || relative.length > 240 || relative.startsWith("/") || relative.includes("\\") || /[\u0000-\u001f\u007f]/.test(relative)) {
    throw new Error("Use a short project-relative path.");
  }
  const parts = relative.split("/");
  if (parts.some(p => !p || p === "." || p === ".." || p.length > 120 || BLOCKED_DIRS.has(p.toLowerCase()) || BLOCKED_NAMES.test(p))) {
    throw new Error("This path is invalid or blocked because it may escape the workspace or contain credentials/generated files.");
  }
  return parts;
}

async function resolveWorkspacePath(project, relative, { allowRoot = false, allowMissingLeaf = false } = {}) {
  const parts = partsFor(relative, allowRoot);
  const root = await fs.realpath(project.path);
  let current = root;
  for (let i = 0; i < parts.length; i++) {
    current = path.join(current, parts[i]);
    const leaf = i === parts.length - 1;
    const st = await fs.lstat(current).catch(error => {
      if (error?.code === "ENOENT" && leaf && allowMissingLeaf) return null;
      throw new Error("Project path does not exist or cannot be accessed.");
    });
    if (!st) break;
    if (st.isSymbolicLink()) throw new Error("Symbolic links are not allowed for remote workspace operations.");
    if (!leaf && !st.isDirectory()) throw new Error("A parent path component is not a directory.");
    if (leaf && !allowMissingLeaf && !st.isFile() && !(allowRoot && st.isDirectory())) throw new Error("The requested item is not a regular file or directory.");
  }
  const target = path.resolve(root, ...parts);
  if (target !== root && !target.startsWith(root + path.sep)) throw new Error("Project path escaped the registered workspace.");
  if (target !== root) {
    const parent = await fs.realpath(path.dirname(target));
    if (parent !== root && !parent.startsWith(root + path.sep)) throw new Error("Project path escaped the registered workspace.");
  }
  return { root, target, parts };
}

function isTextPath(target) {
  const ext = path.extname(target).toLowerCase();
  const base = path.basename(target).toLowerCase();
  return TEXT_EXTENSIONS.has(ext) || TEXT_EXTENSIONS.has(base) || ["makefile", "dockerfile", "license", "notice", "readme"].includes(base);
}

function looksLikeSecret(text) {
  return /-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----/.test(text) ||
    /\b(?:sk-[A-Za-z0-9_-]{24,}|gh[pousr]_[A-Za-z0-9_]{20,}|sb_secret_[A-Za-z0-9_-]{20,})\b/.test(text);
}

export async function workspaceList(project, relativeDirectory = "") {
  const { target, parts } = await resolveWorkspacePath(project, relativeDirectory, { allowRoot: true });
  if (!(await fs.stat(target)).isDirectory()) throw new Error("The selected workspace item is not a directory.");
  const all = await fs.readdir(target, { withFileTypes: true });
  const entries = all.filter(e => !e.isSymbolicLink() && !BLOCKED_DIRS.has(e.name.toLowerCase()) && !BLOCKED_NAMES.test(e.name))
    .sort((a,b) => a.name.localeCompare(b.name))
    .map(e => ({ name: e.name, type: e.isDirectory() ? "directory" : e.isFile() ? "file" : "other" }));
  return { relativeDirectory: parts.join("/"), entries: entries.slice(0,100), totalCount: entries.length, truncated: entries.length > 100 };
}

export async function workspaceRead(project, relativeFile) {
  const { target, parts } = await resolveWorkspacePath(project, relativeFile);
  if (!isTextPath(target)) throw new Error("Only recognised text/source files can be read.");
  const st = await fs.stat(target);
  if (st.size > 1_000_000) throw new Error("Files larger than 1 MB cannot be read through Mada Commander.");
  const raw = await fs.readFile(target);
  if (raw.includes(0)) throw new Error("Binary files cannot be read through this tool.");
  const full = raw.toString("utf8");
  return { relativeFile: parts.join("/"), content: redactSensitive(full.slice(0,40000)), bytes: raw.length, truncated: full.length > 40000 };
}

export async function workspaceWrite(project, relativeFile, content) {
  const { root, target, parts } = await resolveWorkspacePath(project, relativeFile, { allowMissingLeaf: true });
  if (!isTextPath(target)) throw new Error("Only recognised text/source files can be written.");
  if (typeof content !== "string" || content.length > 120000 || content.includes("\u0000")) throw new Error("Text content must be at most 120 KB.");
  if (looksLikeSecret(content)) throw new Error("Content resembles a private key or credential; write blocked.");
  const parent = path.dirname(target);
  const resolvedParent = await fs.realpath(parent);
  if (resolvedParent !== root && !resolvedParent.startsWith(root + path.sep)) throw new Error("Parent path escaped the registered workspace.");
  const previous = await fs.lstat(target).catch(error => error?.code === "ENOENT" ? null : Promise.reject(error));
  if (previous?.isSymbolicLink() || (previous && !previous.isFile())) throw new Error("Only regular files can be replaced.");
  const temporary = target + ".mada-tmp-" + crypto.randomBytes(6).toString("hex");
  try {
    await fs.writeFile(temporary, content, { encoding: "utf8", mode: previous ? previous.mode & 0o777 : 0o644, flag: "wx" });
    await fs.rename(temporary, target);
  } finally {
    await fs.rm(temporary, { force: true }).catch(() => {});
  }
  return { relativeFile: parts.join("/"), bytes: Buffer.byteLength(content), sha256: crypto.createHash("sha256").update(content).digest("hex"), created: !previous };
}

function runChild(program, args, cwd, timeoutMs) {
  return new Promise(resolve => {
    let stdout = "", stderr = "", done = false;
    const start = Date.now();
    const child = spawn(program, args, { cwd, shell: false, env: process.env, stdio: ["ignore","pipe","pipe"] });
    const finish = (code, timedOut = false) => {
      if (done) return;
      done = true; clearTimeout(timer);
      resolve({ exitCode: code, timedOut, durationMs: Date.now()-start, stdout: redactSensitive(stdout).slice(-6000), stderr: redactSensitive(stderr).slice(-2500) });
    };
    const timer = setTimeout(() => { try { child.kill("SIGTERM"); } catch {} finish(-1,true); }, timeoutMs);
    child.stdout.on("data", d => { stdout += d.toString(); if (stdout.length > 12000) stdout = stdout.slice(-12000); });
    child.stderr.on("data", d => { stderr += d.toString(); if (stderr.length > 5000) stderr = stderr.slice(-5000); });
    child.on("error", () => { stderr = "Approved executable unavailable."; finish(-1); });
    child.on("close", code => finish(code ?? -1));
  });
}

export async function runApprovedTask(project, task) {
  if (typeof task !== "string" || !Object.hasOwn(TASKS, task)) throw new Error("Task is not in the fixed allowlist.");
  const [program, args, timeoutMs] = TASKS[task];
  if (task.startsWith("npm_")) {
    const pkg = await fs.readFile(path.join(project.path,"package.json"),"utf8").then(JSON.parse).catch(() => null);
    if (!pkg) throw new Error("This workspace has no readable package.json.");
    const script = task.slice(4);
    if (script !== "test" && !Object.hasOwn(pkg.scripts || {}, script)) throw new Error("The requested npm script is not defined by this project.");
  }
  if (task.startsWith("android_")) {
    const wrapper = await fs.stat(path.join(project.path,"gradlew")).catch(() => null);
    if (!wrapper?.isFile()) throw new Error("This workspace has no Gradle wrapper.");
  }
  return { task, ...await runChild(program,args,project.path,timeoutMs) };
}

/**
 * Run a validated executable directly without shell parsing.
 * Arguments are passed as separate argv items; cwd is a project-relative start folder.
 */
export async function runCommand(project, programInput, argsInput = [], cwdInput = ".", timeoutInput = 120000) {
  if (typeof programInput !== "string" || !COMMAND_PROGRAMS.has(programInput)) {
    throw new Error("Executable is not in Mada Commander's approved command list.");
  }
  if (!Array.isArray(argsInput) || argsInput.length > 40 ||
      argsInput.some(arg => typeof arg !== "string" || arg.length > 2000 || arg.includes(String.fromCharCode(0))) ||
      Buffer.byteLength(JSON.stringify(argsInput), "utf8") > 32000) {
    throw new Error("Command arguments must be at most 40 strings, 2000 characters each, and 32 KB total.");
  }
  const blockedFlags = INLINE_EVAL_FLAGS[programInput];
  if (blockedFlags && argsInput.some(arg => blockedFlags.has(arg) || [...blockedFlags].some(flag => arg.startsWith(flag + "=")))) {
    throw new Error("Inline code-evaluation flags are blocked; run a reviewed script file instead.");
  }
  if (typeof cwdInput !== "string" || cwdInput.length > 240) {
    throw new Error("Working directory must be a project-relative path no longer than 240 characters.");
  }
  const timeoutMs = timeoutInput === undefined ? 120000 : timeoutInput;
  if (!Number.isInteger(timeoutMs) || timeoutMs < 1000 || timeoutMs > 900000) {
    throw new Error("Command timeout must be an integer between 1000 and 900000 milliseconds.");
  }
  const { target, parts } = await resolveWorkspacePath(project, cwdInput || ".", { allowRoot: true });
  if (!(await fs.stat(target)).isDirectory()) throw new Error("Command working directory must be a workspace directory.");
  const result = await runChild(programInput, argsInput, target, timeoutMs);
  return {
    program: programInput,
    args: argsInput.map(arg => redactSensitive(arg).slice(0, 2000)),
    cwd: parts.join("/") || ".",
    ...result
  };
}

function operaCdpPort() {
  const port = Number(process.env.MADA_OPERA_CDP_PORT || 9223);
  if (!Number.isInteger(port) || port < 1024 || port > 65535) throw new Error("Mada Commander Opera CDP port is invalid.");
  return port;
}

async function operaFetchJson(route) {
  const response = await fetch("http://127.0.0.1:" + operaCdpPort() + route, { signal: AbortSignal.timeout(10000) });
  if (!response.ok) throw new Error("Opera debugging endpoint returned HTTP " + response.status + ".");
  return response.json();
}

function safeOperaAddress(value) {
  try {
    const url = new URL(value);
    if (url.protocol === "opera:") return ("opera://" + url.host + url.pathname).slice(0, 500);
    if (url.protocol === "http:" || url.protocol === "https:") return (url.origin + url.pathname).slice(0, 500);
    return "";
  } catch { return ""; }
}

export async function operaStatus() {
  const version = await operaFetchJson("/json/version");
  const targets = await operaFetchJson("/json/list").catch(() => []);
  return {
    connected: true,
    browser: redactSensitive(String(version.Browser || "Opera")).slice(0, 120),
    protocolVersion: String(version["Protocol-Version"] || "").slice(0, 30),
    tabsCount: Array.isArray(targets) ? targets.filter(t => t.type === "page").length : 0
  };
}

export async function operaTabs() {
  const targets = await operaFetchJson("/json/list");
  const tabs = (Array.isArray(targets) ? targets : []).filter(t => t.type === "page");
  return {
    tabs: tabs.slice(0, 40).map(t => ({
      tabId: String(t.id || "").slice(0, 100),
      title: redactSensitive(String(t.title || "")).slice(0, 160),
      address: safeOperaAddress(String(t.url || ""))
    })),
    totalCount: tabs.length,
    truncated: tabs.length > 40
  };
}

function validOperaTabId(value) {
  if (typeof value !== "string" || !/^[A-Za-z0-9_-]{1,100}$/.test(value)) throw new Error("Choose a tabId returned by mada_opera_tabs.");
  return value;
}

export function validateOperaAddress(value) {
  if (typeof value !== "string" || !value || value.length > 2048 || /[\u0000-\u0020\u007f]/.test(value)) {
    throw new Error("Address must be a valid HTTP(S) URL or an Opera settings page.");
  }
  let url;
  try { url = new URL(value); } catch { throw new Error("Address is not a valid URL."); }
  if (url.username || url.password) throw new Error("Embedded credentials are not allowed in URLs.");
  if (url.protocol === "http:" || url.protocol === "https:") {
    if (!url.hostname) throw new Error("Address must include a hostname.");
    for (const [key] of url.searchParams) {
      if (/^(?:access[_-]?token|refresh[_-]?token|client[_-]?secret|password|authorization|api[_-]?key|code)$/i.test(key)) {
        throw new Error("Credential-like query parameters are not allowed.");
      }
    }
  } else if (url.protocol === "opera:") {
    if (!["settings","settings/search","about"].includes(url.host + url.pathname.replace(/^\//,"")) &&
        !["settings","about"].includes(url.host)) throw new Error("Only Opera settings and about pages are allowed.");
    if (url.search || url.hash) throw new Error("Opera internal pages cannot include query strings or fragments.");
  } else {
    throw new Error("Only HTTP, HTTPS, and approved Opera settings pages are allowed.");
  }
  return url.href;
}

async function operaCdpCall(tabIdInput, method, params = {}) {
  const tabId = validOperaTabId(tabIdInput);
  const targets = await operaFetchJson("/json/list");
  const target = (Array.isArray(targets) ? targets : []).find(t => t.id === tabId && t.type === "page");
  if (!target?.id) throw new Error("Selected Opera tab is no longer available.");

  // Use the browser-level WebSocket and a flattened CDP session. A per-page
  // socket can close during cross-origin navigation before Page.navigate's
  // acknowledgement is delivered, which looks like a timeout despite success.
  const version = await operaFetchJson("/json/version");
  if (typeof WebSocket !== "function") throw new Error("This Node runtime does not provide a WebSocket client.");
  let browserSocket;
  try { browserSocket = new URL(String(version.webSocketDebuggerUrl || "")); }
  catch { throw new Error("Opera did not provide a valid browser debugging endpoint."); }
  if (browserSocket.protocol !== "ws:" ||
      !["127.0.0.1","localhost","::1"].includes(browserSocket.hostname) ||
      Number(browserSocket.port) !== operaCdpPort() ||
      !browserSocket.pathname.startsWith("/devtools/browser/")) {
    throw new Error("Unexpected Opera browser debugging socket; connection refused.");
  }

  return await new Promise((resolve, reject) => {
    let settled = false;
    let attached = false;
    let stage = "opening browser socket";
    let nextId = 1;
    const pending = new Map();
    const socket = new WebSocket(browserSocket.href);
    const timer = setTimeout(() => finish(new Error("Opera command timed out at stage: " + stage + ".")), 12000);

    function finish(error, result) {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      pending.clear();
      try { socket.close(); } catch {}
      if (error) reject(error); else resolve(result || {});
    }
    function send(command, commandParams, sessionId) {
      const id = nextId++;
      pending.set(id, { command, sessionId: sessionId || null });
      const message = { id, method: command, params: commandParams };
      if (sessionId) message.sessionId = sessionId;
      socket.send(JSON.stringify(message));
    }

    socket.addEventListener("open", () => {
      stage = "attaching to target";
      send("Target.attachToTarget", { targetId: target.id, flatten: true });
    }, { once: true });

    socket.addEventListener("message", event => {
      let message;
      try { message = JSON.parse(String(event.data)); } catch { return; }
      const pendingCommand = pending.get(message.id);
      if (!pendingCommand) return;
      pending.delete(message.id);
      if (message.error) {
        finish(new Error("Opera rejected the requested browser action."));
        return;
      }
      if (!attached) {
        const sessionId = message.result?.sessionId;
        if (pendingCommand.command !== "Target.attachToTarget" || typeof sessionId !== "string" || !sessionId) {
          finish(new Error("Opera did not create a browser-control session."));
          return;
        }
        attached = true;
        stage = "awaiting " + method + " response";
        send(method, params, sessionId);
        return;
      }
      finish(null, { ...(message.result || {}), __targetUrl: target.url });
    });

    socket.addEventListener("error", () => finish(new Error("Could not connect to the Opera browser-control session.")), { once: true });
    socket.addEventListener("close", () => {
      if (!settled) finish(new Error("Opera browser-control session closed before replying."));
    }, { once: true });
  });
}

export async function operaNavigate(tabId, addressInput) {
  const address = validateOperaAddress(addressInput);
  await operaCdpCall(tabId, "Page.navigate", { url: address });
  return { tabId: validOperaTabId(tabId), address: safeOperaAddress(address), navigated: true };
}

export async function operaAction({ tabId: tabInput, action, selector = "", text = "", value = "", checked = false }) {
  const tabId = validOperaTabId(tabInput);
  const allowed = ["inspect", "click_text", "click_selector", "fill_selector", "select_value", "toggle_checked"];
  if (!allowed.includes(action)) throw new Error("Unsupported Opera action.");
  if (typeof selector !== "string" || selector.length > 300 || typeof text !== "string" || text.length > 120 ||
      typeof value !== "string" || value.length > 2048 || typeof checked !== "boolean") throw new Error("Opera action parameters exceed their limits.");
  if (/password|secret|token|credential|cookie|authorization/i.test(selector) || looksLikeSecret(value)) {
    throw new Error("Sensitive fields or credential-like values are not permitted.");
  }
  const params = { action, selector, text, value, checked };
  const expression = `(() => {
    const p = ${JSON.stringify(params)};
    const visible = el => !!(el && (el.getClientRects().length || el.offsetWidth || el.offsetHeight));
    const label = el => String(el?.innerText || el?.getAttribute?.("aria-label") || el?.title || el?.textContent || "").trim().slice(0,180);
    const find = selector => {
      try { return [...document.querySelectorAll(selector)].filter(visible).slice(0,80); } catch { return []; }
    };
    if (p.action === "inspect") {
      const controls = find("button,a[href],[role=button],input,select,textarea,[role=switch]").slice(0,60).map(el => ({
        tag: String(el.tagName || "").toLowerCase(),
        kind: String(el.type || el.getAttribute("role") || "").slice(0,40),
        label: label(el),
        disabled: Boolean(el.disabled),
        checked: typeof el.checked === "boolean" ? el.checked : undefined
      }));
      return { title: String(document.title || "").slice(0,160), readyState: document.readyState, controls };
    }
    let el;
    if (p.action === "click_text") {
      el = find("button,a[href],[role=button]").find(x => label(x).toLowerCase().includes(p.text.toLowerCase()));
      if (!el) return { ok:false, reason:"no_visible_matching_control" };
      if (/(delete|remove|clear all|factory reset|reset all|purchase|buy now|pay now|send|authorize|grant access|sign out|log out)/i.test(label(el))) return { ok:false, reason:"sensitive_action_blocked" };
      const clicked = label(el);
      // Return to CDP before controls that navigate/reload the page execute their click.
      setTimeout(() => { try { if (el.isConnected && !el.disabled) el.click(); } catch {} }, 750);
      return { ok:true, clicked, scheduled:true };
    }
    if (!p.selector) return { ok:false, reason:"selector_required" };
    el = find(p.selector)[0];
    if (!el) return { ok:false, reason:"no_visible_matching_element" };
    if (p.action === "click_selector") {
      if (el.disabled) return { ok:false, reason:"control_disabled" };
      if (/(delete|remove|clear all|factory reset|reset all|purchase|buy now|pay now|send|authorize|grant access|sign out|log out)/i.test(label(el))) return { ok:false, reason:"sensitive_action_blocked" };
      const clicked = label(el);
      // Defer the click so Runtime.evaluate can return before a navigation tears down the target.
      setTimeout(() => { try { if (el.isConnected && !el.disabled) el.click(); } catch {} }, 750);
      return { ok:true, clicked, scheduled:true };
    }
    if (p.action === "fill_selector") {
      const kind = String(el.type || "").toLowerCase();
      const identity = [el.name,el.id,el.getAttribute("autocomplete"),el.getAttribute("aria-label"),el.getAttribute("placeholder"),kind].join(" ").toLowerCase();
      if (!(el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement) ||
          ["password","hidden","file","email"].includes(kind) ||
          /password|secret|token|credential|email|username|one-time-code|current-password|new-password/.test(identity)) return { ok:false, reason:"field_type_not_allowed" };
      el.focus(); el.value=p.value;
      el.dispatchEvent(new Event("input",{bubbles:true})); el.dispatchEvent(new Event("change",{bubbles:true}));
      return { ok:true, updated:true };
    }
    if (p.action === "select_value") {
      if (!(el instanceof HTMLSelectElement) || ![...el.options].some(o => o.value === p.value)) return { ok:false, reason:"select_option_not_allowed" };
      el.value=p.value; el.dispatchEvent(new Event("input",{bubbles:true})); el.dispatchEvent(new Event("change",{bubbles:true}));
      return { ok:true, updated:true, selected:String(el.value).slice(0,100) };
    }
    if (p.action === "toggle_checked") {
      if (!(el instanceof HTMLInputElement) || !["checkbox","radio"].includes(String(el.type).toLowerCase())) return { ok:false, reason:"not_a_checkable_control" };
      el.checked=Boolean(p.checked); el.dispatchEvent(new Event("input",{bubbles:true})); el.dispatchEvent(new Event("change",{bubbles:true}));
      return { ok:true, checked:el.checked };
    }
    return { ok:false, reason:"unsupported_action" };
  })()`;
  const response = await operaCdpCall(tabId, "Runtime.evaluate", { expression, returnByValue: true, awaitPromise: true, userGesture: true });
  if (response.exceptionDetails) throw new Error("Opera DOM operation failed.");
  const result = response.result?.value || {};
  if (result.title) result.title = redactSensitive(result.title).slice(0,160);
  if (Array.isArray(result.controls)) result.controls = result.controls.map(c => ({ ...c, label: redactSensitive(c.label || "").slice(0,180) }));
  // Use the URL captured before Runtime.evaluate. A scheduled click may navigate immediately,
  // so querying /json/list again here can race with target teardown and turn a successful click into a timeout.
  return { tabId, action, address: safeOperaAddress(String(response.__targetUrl || "")), result };
}


/**
 * Optional local MCP bridge. The server can request only these fixed operations;
 * each target executable, endpoint, credential mapping and allowed tool name is
 * read from the owner's chmod-600 local integration file.
 */
export async function mcpIntegrationCatalog() {
  return await listMcpIntegrations();
}

export async function mcpIntegrationTools(serverId) {
  return await listAllowedMcpTools(serverId);
}

export async function mcpIntegrationCall(serverId, toolName, args) {
  return await callAllowedMcpTool(serverId, toolName, args);
}
