import { existsSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { createRendererTelemetryAccumulator, hasCompleteFramePacingReport, REQUIRED_HERO_PARTS } from "./ramus-log-telemetry.mjs";

function parseFramebufferReadback(line, expectedLabel) {
  if (typeof line !== "string") {
    return { ok: false, reason: "missing_readback" };
  }

  const match = line.match(
    /PBR_READBACK label=([a-z_]+) size=(\d+)x(\d+) brightPixels=(\d+)\/(\d+) maxChannel=(\d+) meanRGB=(\d+),(\d+),(\d+) centerRGB=(\d+),(\d+),(\d+) glError=0x([0-9a-f]+)\b/i
  );
  if (!match || match[1] !== expectedLabel) {
    return { ok: false, reason: "malformed_readback" };
  }

  const values = match.slice(2).map((value, index) =>
    index === 11 ? Number.parseInt(value, 16) : Number(value)
  );
  const [width, height, brightPixels, totalPixels, maxChannel,
    meanR, meanG, meanB, centerR, centerG, centerB, glError] = values;
  const channels = [meanR, meanG, meanB, centerR, centerG, centerB];
  const dimensionsValid = Number.isSafeInteger(width) && width > 0 &&
    Number.isSafeInteger(height) && height > 0 &&
    Number.isSafeInteger(totalPixels) && totalPixels === width * height;
  const countsValid = Number.isSafeInteger(brightPixels) &&
    brightPixels >= 0 && brightPixels <= totalPixels;
  const channelsValid = [maxChannel, ...channels].every((value) =>
    Number.isSafeInteger(value) && value >= 0 && value <= 255
  );
  const glErrorValid = Number.isSafeInteger(glError) && glError >= 0;

  if (!dimensionsValid || !countsValid || !channelsValid || !glErrorValid) {
    return { ok: false, reason: "invalid_readback_metrics" };
  }

  const minimumBrightPixels = Math.max(32, Math.ceil(totalPixels * 0.005));
  if (glError !== 0) {
    return { ok: false, reason: "gl_error", glError, brightPixels, totalPixels, maxChannel };
  }
  if (maxChannel <= 24 || brightPixels < minimumBrightPixels) {
    return {
      ok: false,
      reason: "insufficient_non_dark_pixels",
      brightPixels,
      totalPixels,
      minimumBrightPixels,
      maxChannel
    };
  }

  return {
    ok: true,
    label: expectedLabel,
    width,
    height,
    brightPixels,
    totalPixels,
    brightRatio: Number((brightPixels / totalPixels).toFixed(4)),
    maxChannel,
    meanRGB: [meanR, meanG, meanB],
    centerRGB: [centerR, centerG, centerB],
    glError
  };
}

function selfTestFramebufferReadback() {
  const scene = "PBR_READBACK label=scene_fbo size=650x1011 brightPixels=369149/657150 maxChannel=255 meanRGB=30,28,28 centerRGB=37,31,11 glError=0x0";
  const surface = "PBR_READBACK label=surface_backbuffer size=650x1011 brightPixels=657149/657150 maxChannel=234 meanRGB=97,94,93 centerRGB=127,114,57 glError=0x0";
  const black = "PBR_READBACK label=scene_fbo size=650x1011 brightPixels=0/657150 maxChannel=11 meanRGB=2,4,11 centerRGB=2,4,11 glError=0x0";
  const glFailure = scene.replace("glError=0x0", "glError=0x502");
  const sparse = scene.replace("brightPixels=369149/657150", "brightPixels=100/657150");
  const inconsistentSize = scene.replace("brightPixels=369149/657150", "brightPixels=369149/600000");
  const impossibleCount = scene.replace("brightPixels=369149/657150", "brightPixels=700000/657150");

  const cases = [
    ["real scene readback", parseFramebufferReadback(scene, "scene_fbo").ok],
    ["real surface readback", parseFramebufferReadback(surface, "surface_backbuffer").ok],
    ["black frame rejected", !parseFramebufferReadback(black, "scene_fbo").ok],
    ["GL error rejected", !parseFramebufferReadback(glFailure, "scene_fbo").ok],
    ["sparse pixels rejected", !parseFramebufferReadback(sparse, "scene_fbo").ok],
    ["inconsistent dimensions rejected", !parseFramebufferReadback(inconsistentSize, "scene_fbo").ok],
    ["pixel count overflow rejected", !parseFramebufferReadback(impossibleCount, "scene_fbo").ok],
    ["wrong label rejected", !parseFramebufferReadback(scene, "surface_backbuffer").ok],
    ["missing log rejected", !parseFramebufferReadback(null, "scene_fbo").ok]
  ];
  const failed = cases.filter(([, passed]) => !passed);
  for (const [name, passed] of cases) {
    console.log((passed ? "PASS " : "FAIL ") + name);
  }
  if (failed.length) {
    throw new Error("Framebuffer readback self-test failed: " + failed.map(([name]) => name).join(", "));
  }
  console.log("FRAMEBUFFER READBACK SELF-TEST PASS (" + cases.length + " cases)");
}

if (process.argv.includes("--test-framebuffer-readback")) {
  selfTestFramebufferReadback();
  process.exit(0);
}

const cli = process.env.RAMUS_CLI;
const apk = process.env.RAMUS_APK;
if (!cli || !apk) throw new Error("RAMUS_CLI and RAMUS_APK are required");
if (!existsSync(apk)) throw new Error(`APK not found: ${apk}`);

function run(...args) {
  const result = runCli(...args);
  if (!result.ok) throw new Error(JSON.stringify(result.value ?? { error: "unknown" }));
  return result.value;
}

function runAllowError(...args) {
  return runCli(...args);
}

function runCli(...args) {
  const command = String(args[0] || "");
  const waitIndex = args.indexOf("--timeout");
  const declaredWaitMs = waitIndex >= 0 ? Number(args[waitIndex + 1]) : 0;
  // Ramus RPCs can occasionally hang if the cloud accessibility stream is stale.
  // Bound every child process so retry/finally logic can recover and close our session.
  const timeout = command === "session"
    ? 45000
    : command === "screenshot"
      ? 30000
      : command === "logcat"
        ? 15000
        : command === "wait"
          ? Math.min(15000, Math.max(8000, (Number.isFinite(declaredWaitMs) ? declaredWaitMs : 0) + 5000))
          : 12000;
  const r = spawnSync(process.execPath, [cli, ...args], {
    encoding: "utf8",
    stdio: ["ignore", "pipe", "pipe"],
    timeout,
    maxBuffer: 4 * 1024 * 1024,
  });
  const out = (r.stdout || "").trim();
  const err = (r.stderr || "").trim();
  if (r.error) {
    return {
      ok: false,
      value: {
        error: r.error.code === "ETIMEDOUT" ? "timeout" : "spawn_error",
        message: r.error.message,
        command,
        timeoutMs: timeout,
      },
    };
  }
  if (!out) {
    return {
      ok: false,
      value: { error: "no_output", message: err || `ramus ${args.join(" ")} produced no output` },
    };
  }
  let value;
  try {
    value = JSON.parse(out);
  } catch {
    return {
      ok: false,
      value: { error: "non_json", message: out.slice(0, 1000) },
    };
  }
  return { ok: r.status === 0 && !value.error, value };
}

function waitForSix(timeoutMs) {
  const waited = runAllowError(
    "wait", "--text", "Dé: 6", "--timeout", String(timeoutMs), "--session", session
  );
  if (waited.ok) return true;
  const snapshot = runAllowError("snapshot", "--session", session, "--find", "Dé: 6");
  return snapshot.ok && snapshot.value.elements?.length > 0;
}

function waitStable(ms = 1200) {
  // A bounded local settle delay is safer than racing the remote accessibility tree.
  const sleepMs = Math.max(0, Math.min(Number(ms) || 0, 1800));
  spawnSync("sleep", [String(sleepMs / 1000)], {
    stdio: "ignore",
    timeout: 2500,
  });
}

function waitForSnapshot(findText, timeoutMs = 6000) {
  const deadline = Date.now() + timeoutMs;
  let last = null;

  while (Date.now() < deadline) {
    const snapshot = runAllowError("snapshot", "--session", session, "--find", findText);
    last = snapshot;
    if (snapshot.ok && snapshot.value.elements?.length) return snapshot.value;

    // UI transitions can lag behind session creation or an animation. Wait briefly
    // for the expected accessibility text, then re-snapshot within a fixed deadline.
    const remaining = deadline - Date.now();
    if (remaining <= 0) break;
    runAllowError(
      "wait", "--text", findText,
      "--timeout", String(Math.min(750, remaining)),
      "--session", session
    );
  }

  return last?.ok ? last.value : null;
}

function assertMenuButton(text) {
  const snapshot = waitForSnapshot(text, 5000);
  if (!snapshot?.elements?.some((e) => e.text === text)) {
    throw new Error(`Expected offline menu button after waiting: ${text}`);
  }
}

function assertTurn(text) {
  const snapshot = waitForSnapshot(text, 5000);
  if (!snapshot?.elements?.some((e) => e.text?.startsWith(text))) {
    throw new Error(`Expected turn state after waiting: ${text}`);
  }
}

function treeLabels(tree) {
  return (tree?.elements || []).map((e) =>
    [e.text, e.contentDesc, e.contentDescription, e.description, e.label]
      .filter((v) => typeof v === "string" && v.length > 0)
      .join(" ")
  );
}

function readTree() {
  const result = runAllowError("snapshot", "--session", session, "--include-offscreen");
  if (!result.ok || !result.value?.elements) return null;
  return result.value;
}

function treeHas(tree, value) {
  return treeLabels(tree).some((label) => label.includes(value));
}

function modePickerIsVisible(tree) {
  return treeHas(tree, "GAME_MODE_DIALOG") &&
    treeHas(tree, "MODE_OFFLINE") &&
    treeHas(tree, "MODE_ONLINE");
}

function tapTreeNode(tree, needle) {
  const node = (tree?.elements || []).find((e) =>
    [e.text, e.contentDesc, e.contentDescription, e.description, e.label]
      .some((value) => typeof value === "string" && value.includes(needle))
  );
  if (!node) throw new Error(`Accessibility node missing from fresh tree: ${needle}; labels=${JSON.stringify(treeLabels(tree))}`);
  const result = runAllowError("tap", node.ref, "--gen", String(tree.gen), "--session", session);
  if (!result.ok) throw new Error(`Could not tap accessibility node ${needle}: ${JSON.stringify(result.value)}`);
  return result;
}

function isPremiumHome(tree) {
  return treeHas(tree, "LudoMada Premium Home");
}

function returnToHome() {
  for (let attempt = 0; attempt < 3; attempt++) {
    let tree = readTree();
    if (isPremiumHome(tree)) return true;
    if (!treeHas(tree, "← Accueil")) return false;
    tapTreeNode(tree, "← Accueil");
    waitStable(650);
    tree = readTree();
    if (isPremiumHome(tree)) return true;
  }
  return isPremiumHome(readTree());
}

function openModePickerFromHome() {
  // The view scales a 390×710 canvas into Ramus's portrait surface. The true
  // JOUER hitbox is x=8..126 / y=110..158; these normalized points cover its
  // centre first, followed by small offsets and rotated/letterboxed fallbacks.
  const points = [
    "0.17,0.247", "0.17,0.24", "0.18,0.25", "0.16,0.25",
    "0.42,0.19", "0.42,0.21",
    "0.12,0.42", "0.14,0.46", "0.86,0.42"
  ];
  let lastLabels = [];

  for (const point of points) {
    let tree = readTree();
    if (!isPremiumHome(tree)) {
      if (!returnToHome()) {
        lastLabels = treeLabels(tree);
        continue;
      }
      tree = readTree();
    }
    if (!isPremiumHome(tree)) {
      lastLabels = treeLabels(tree);
      continue;
    }

    const tap = runAllowError("tap", point, "--session", session, "--force");
    waitStable(450);
    const afterTap = readTree();
    lastLabels = treeLabels(afterTap);

    // Require all three signals in the same fresh tree; never accept a stale
    // GAME_MODE_DIALOG marker if the app actually navigated to Events/Wallet.
    if (modePickerIsVisible(afterTap)) return afterTap;

    // A wrong coordinate may activate another Home card. Return explicitly to
    // Home before probing the next point, rather than cascading through screens.
    if (!isPremiumHome(afterTap) && treeHas(afterTap, "← Accueil")) {
      returnToHome();
    }
    if (!tap.ok) waitStable(200);
  }

  throw new Error("Could not open the Home JOUER mode picker using verified hitbox candidates. Last accessibility tree: " + JSON.stringify(lastLabels).slice(0, 7000));
}

function pendingDiceValue() {
  const snapshot = runAllowError("snapshot", "--session", session, "--find", "Dé:");
  if (!snapshot.ok) return { value: null, status: "" };
  const texts = (snapshot.value.elements || []).map((e) => e.text).filter((t) => typeof t === "string");
  const status = texts.find((t) => t.includes("Dé:")) || "";
  if (status.includes("Aucun coup")) return { value: null, status };
  const match = status.match(/Dé:\s*([1-6])/);
  return { value: match ? Number(match[1]) : null, status };
}

function moveAnyLegalPawnAfterRoll() {
  const { value: dice, status } = pendingDiceValue();
  if (dice === null || status.includes("Aucun coup")) return false;

  for (let index = 1; index <= 4; index++) {
    const pawn = runAllowError("snapshot", "--session", session, "--find", `Pion ${index}`);
    if (!pawn.ok || !pawn.value.elements?.length) continue;

    const ref = pawn.value.elements[0];
    runAllowError("tap", ref.ref, "--gen", String(pawn.value.gen), "--session", session);

    // A legal move resets the pending die to zero immediately. Illegal pawns
    // leave the die pending, so try the next token without throwing.
    const moved = runAllowError(
      "wait", "--text", "Dé: 0", "--timeout", "1200", "--session", session
    );
    if (moved.ok) return true;
  }

  throw new Error(`Dice ${dice} allowed a move but no pawn could be moved. Status: ${pendingDiceValue().status}`);
}

function advanceToTurn(targetTurn) {
  for (let i = 0; i < 12; i++) {
    if (runAllowError("snapshot", "--session", session, "--find", targetTurn).value?.elements?.length) {
      return;
    }

    const roll = runAllowError("snapshot", "--session", session, "--find", "Lancer le dé");
    if (!roll.ok || !roll.value.elements?.length) {
      throw new Error(`Dice button missing while waiting for ${targetTurn}`);
    }
    const ref = roll.value.elements[0];
    run("tap", ref.ref, "--gen", String(roll.value.gen), "--session", session);

    moveAnyLegalPawnAfterRoll();

    const waited = runAllowError(
      "wait", "--text", targetTurn, "--timeout", "2200", "--session", session
    );
    if (waited.ok) return;
  }
  throw new Error(`Turn did not reach ${targetTurn} within 12 rolls`);
}

let session = "";
let success = false;
let failureLogcatCaptured = false;

function normalizeLogcatRows(result) {
  const value = result?.value ?? {};
  const rows = Array.isArray(value.lines)
    ? value.lines
    : Array.isArray(value.logs)
      ? value.logs
      : [];
  return rows.map((entry) => {
    if (typeof entry === "string") {
      return { line: entry, tag: "", stream: "", priority: "", fields: [] };
    }
    if (!entry || typeof entry !== "object") {
      return { line: "", tag: "", stream: "", priority: "", fields: [] };
    }
    return {
      line: [entry.stamp, entry.stream, entry.priority, entry.tag, entry.text, entry.message]
        .filter((part) => part !== undefined && part !== null && part !== "")
        .join(" "),
      tag: typeof entry.tag === "string" ? entry.tag : "",
      stream: typeof entry.stream === "string" ? entry.stream : "",
      priority: typeof entry.priority === "string" ? entry.priority : "",
      fields: Object.keys(entry).sort()
    };
  }).filter((entry) => entry.line);
}

function redactLogLine(line) {
  return String(line).replace(
    /(authorization|access[_-]?token|refresh[_-]?token|password|client[_-]?secret|api[_-]?key)(["'\s:=]+)[^",\s}]+/gi,
    "$1$2[redacted]"
  );
}

function captureFailureLogcat() {
  if (!session || failureLogcatCaptured) return;
  failureLogcatCaptured = true;

  // Read the unfiltered guest tail once; package-filtered rows are collected separately.
  // Keeping the raw tail distinguishes "no guest logs" from an unavailable package stream.
  const broad = runAllowError("logcat", "--limit", "500", "--session", session);
  const entries = normalizeLogcatRows(broad);
  // Preserve the filtered tag response as independent evidence; some cloud guest
  // sessions expose only system-stream rows through the unfiltered endpoint.
  const filtered = runAllowError(
    "logcat", "--limit", "250", "--filter", "LudoMadaRender", "--session", session
  );
  const filteredEntries = normalizeLogcatRows(filtered);
  const combinedEntries = [...entries, ...filteredEntries];
  const relevantPattern = /FATAL EXCEPTION|AndroidRuntime|Process: com\.ludomadagascar|com\.ludomadagascar|LudoMada|GLSurfaceView|Fatal signal|SIGSEGV|EGL|OpenGL|GLES|ActivityManager|ActivityTaskManager|lmkd|LowMemoryKiller|am_(?:crash|kill|proc_died|finish_activity|pause_activity|resume_activity)|Killing .*com\.ludomadagascar|Force finishing .*com\.ludomadagascar/i;
  const rendererPattern = /Renderer=GLES3_PBR|PBR_SURFACE_CREATED|GLB_RUNTIME_LOAD_OK|BOARD_GLB_RUNTIME_LOAD_OK|PBR_READBACK|PBR_FIRST_FRAME_COMPLETE/i;
  console.error("RAMUS_LOGCAT_UNFILTERED_SUMMARY=" + JSON.stringify({
    ok: broad.ok,
    error: broad.ok ? null : String(broad.value?.error || "logcat_error").slice(0, 80),
    lineCount: entries.length,
    firstEntryFields: entries[0]?.fields ?? [],
    tailMetadata: entries.slice(-20).map((entry) => ({
      tag: entry.tag,
      stream: entry.stream,
      priority: entry.priority,
      lineLength: entry.line.length,
      appRelated: relevantPattern.test(entry.line),
      rendererRelated: rendererPattern.test(entry.line)
    }))
  }));
  console.error("RAMUS_LOGCAT_FILTERED_SUMMARY=" + JSON.stringify({
    ok: filtered.ok,
    lineCount: filteredEntries.length,
    rendererLines: filteredEntries.filter((entry) => rendererPattern.test(entry.line)).length,
    error: filtered.ok ? null : String(filtered.value?.error || "logcat_error").slice(0, 80)
  }));

  const relevant = combinedEntries
    .map((entry) => entry.line)
    .filter((line) => relevantPattern.test(line))
    .slice(-220)
    .map(redactLogLine);
  console.error("RAMUS_LOGCAT_FAILURE_DIAGNOSTICS_BEGIN");
  if (relevant.length === 0) {
    console.error("No app, activity, renderer or low-memory lines found in the unfiltered guest log tail.");
  } else {
    console.error(relevant.join("\n"));
  }
  console.error("RAMUS_LOGCAT_FAILURE_DIAGNOSTICS_END");
}

function readRendererLogLines({ forceRendererTagFilter = false } = {}) {
  // Ramus --filter follows Android logcat tag filtering. Query the known renderer tag,
  // then select startup, framebuffer and frame-pacing messages locally.
  const markers = /Renderer=GLES3_PBR|PBR_SURFACE_CREATED|GLB_RUNTIME_LOAD_OK|BOARD_GLB_RUNTIME_LOAD_OK|PBR_READBACK|PBR_FIRST_FRAME_COMPLETE|PBR_FRAME_PACING|PBR_DISPLAY_REFRESH_HZ|PBR_UI_VSYNC/i;
  const broad = runAllowError("logcat", "--limit", "500", "--session", session);
  const broadEntries = normalizeLogcatRows(broad);
  const broadLines = broadEntries.map((entry) => entry.line).filter((line) => markers.test(line));

  // A snapshot can contain recent framebuffer markers while older startup/GLB
  // markers are already outside the broad tail. Query the renderer log tag.
  const startupIncomplete =
    !broadLines.some((line) => line.includes("Renderer=GLES3_PBR")) ||
    !broadLines.some((line) => line.includes("PBR_SURFACE_CREATED")) ||
    !broadLines.some((line) => line.includes("GLB_RUNTIME_LOAD_OK parts=") &&
      !line.includes("BOARD_GLB_RUNTIME_LOAD_OK")) ||
    !broadLines.some((line) => line.includes("BOARD_GLB_RUNTIME_LOAD_OK parts="));
  let filtered = { ok: false, value: { error: "not_needed" } };
  let filteredEntries = [];
  if (startupIncomplete || forceRendererTagFilter) {
    filtered = runAllowError(
      "logcat", "--limit", "500", "--filter", "LudoMadaRender", "--session", session
    );
    filteredEntries = normalizeLogcatRows(filtered);
  }

  const filteredLines = filteredEntries.map((entry) => entry.line).filter((line) => markers.test(line));
  const lines = [...new Set([...broadLines, ...filteredLines])];
  return {
    ok: broad.ok || filtered.ok,
    broadOk: broad.ok,
    filteredRequested: startupIncomplete || forceRendererTagFilter,
    filteredOk: filtered.ok,
    totalLines: broadEntries.length,
    filteredLines: filteredEntries.length,
    filteredError: filtered.ok
      ? null
      : String(filtered.value?.error || "renderer_tag_filter_unavailable").slice(0, 120),
    error: broad.ok || filtered.ok
      ? null
      : String(filtered.value?.error || broad.value?.error || "logcat_error").slice(0, 80),
    lines
  };
}

function assertRuntimeGlbLoaded(timeoutMs = 16000, telemetry = createRendererTelemetryAccumulator()) {
  const deadline = Date.now() + timeoutMs;
  let lastLogcat = { ok: false, totalLines: 0, filteredLines: 0, error: "not_read", lines: [] };
  let result = telemetry.snapshot(parseFramebufferReadback);
  while (Date.now() < deadline) {
    lastLogcat = readRendererLogLines();
    telemetry.observe(lastLogcat);
    result = telemetry.snapshot(parseFramebufferReadback);
    const checks = result.checks;
    if (checks.gles3Pbr && checks.pbrSurfaceCreated &&
        checks.missingHeroParts.length === 0 && checks.boardParts > 0 &&
        checks.sceneReadback.ok && checks.surfaceReadback.ok && checks.firstFrameComplete) {
      console.log("GLES3 PBR RENDERER PASS");
      console.log("RUNTIME HERO GLB LOAD PASS parts=" + checks.heroParts.length);
      console.log("RUNTIME BOARD GLB LOAD PASS parts=" + checks.boardParts);
      console.log("PBR SCENE FBO READBACK PASS=" + JSON.stringify(checks.sceneReadback));
      console.log("PBR SURFACE READBACK PASS=" + JSON.stringify(checks.surfaceReadback));
      console.log("PBR FIRST FRAME COMPLETE");
      console.log("RENDERER TELEMETRY ACCUMULATED=" + JSON.stringify(result.evidence));
      return;
    }
    waitStable(Math.min(1800, Math.max(0, deadline - Date.now())));
  }
  throw new Error("Runtime GLB/frame verification failed: " +
    JSON.stringify({ checks: result.checks, telemetry: result.evidence, logcat: {
      ok: lastLogcat.ok,
      totalLines: lastLogcat.totalLines,
      filteredLines: lastLogcat.filteredLines,
      rendererLines: lastLogcat.lines.length,
      error: lastLogcat.error
    } }));
}

function safeRamusMetadata(value) {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    return { valueType: typeof value };
  }
  const summary = {};
  for (const [key, entry] of Object.entries(value)) {
    if (/token|secret|password|credential|api[_-]?key|authorization/i.test(key)) continue;
    if (/duration|expires|remaining|limit|status|state|mode|trial|plan|ttl|timeout|started|created|message/i.test(key) &&
        ["string", "number", "boolean"].includes(typeof entry)) {
      summary[key] = entry;
    }
  }
  summary.availableFields = Object.keys(value)
    .filter((key) => !/token|secret|password|credential|api[_-]?key|authorization/i.test(key))
    .slice(0, 30);
  return summary;
}

try {
  if (!process.env.RAMUS_API_KEY) {
    console.log("RAMUS_AUTH_MODE=trial");
    const trialInfo = run("trial");
    console.log("RAMUS_TRIAL_METADATA=" + JSON.stringify(safeRamusMetadata(trialInfo)));
  } else {
    console.log("RAMUS_AUTH_MODE=api_key");
  }
  const started = run("session", "start", "--apk", apk, "--wait");
  session = started.session;
  console.log("RAMUS_SESSION_STARTED");
  console.log("RAMUS_SESSION_METADATA=" + JSON.stringify(safeRamusMetadata(started)));

  let target = findRef("👥 Jouer hors-ligne");
  run("tap", target.ref, "--gen", String(target.gen),
      "--expect", "Ludo hors-ligne", "--session", session);
  console.log("LOGIN→OFFLINE PASS");

  target = findRef("👤 2 joueurs");
  run("tap", target.ref, "--gen", String(target.gen), "--session", session);
  console.log("2-PLAYER MENU PASS");
  const boardSurface = waitForSnapshot("LudoMada 3D Ludo board", 8000) ?? readTree();
  if (!treeHas(boardSurface, "LudoMada 3D Ludo board")) {
    console.error("3D_BOARD_ACCESSIBILITY_DIAGNOSTICS:" + JSON.stringify(treeLabels(boardSurface)).slice(0, 7000));
    captureFailureLogcat();
    const evidence = runAllowError("screenshot", "--session", session, "--out", "ramus-screen.jpg");
    if (evidence.ok) console.error("3D_BOARD_SCREENSHOT_CAPTURED");
    else console.error("3D_BOARD_SCREENSHOT_CAPTURE_FAILED:" + JSON.stringify(evidence.value));

    // Distinguish a retired/backgrounded Ramus session from an app process that
    // cannot relaunch after the offline-board transition. Keep the original
    // failure screenshot above before attempting this read-only recovery launch.
    const recovery = runAllowError("app", "launch", "--session", session);
    console.error("RAMUS_POST_FAILURE_APP_LAUNCH=" + JSON.stringify({
      ok: recovery.ok,
      error: typeof recovery.value?.error === "string" ? recovery.value.error.slice(0, 120) : null,
      status: typeof recovery.value?.status === "string" ? recovery.value.status.slice(0, 120) : null
    }));
    waitStable(1000);
    const recoveryTree = readTree();
    console.error("RAMUS_POST_FAILURE_TREE=" + JSON.stringify({
      screen: recoveryTree?.screen || null,
      labels: treeLabels(recoveryTree).slice(0, 30).map(label => label.slice(0, 120))
    }).slice(0, 5000));
    const recoveryShot = runAllowError("screenshot", "--session", session, "--out", "ramus-recovery-screen.jpg");
    console.error(recoveryShot.ok ? "RAMUS_POST_FAILURE_SCREENSHOT_CAPTURED" : "RAMUS_POST_FAILURE_SCREENSHOT_CAPTURE_FAILED");
    captureFailureLogcat();
    throw new Error("3D board accessibility surface missing after 8s: " + JSON.stringify(treeLabels(boardSurface)).slice(0, 5000));
  }
  console.log("3D BOARD SURFACE PASS");
  // Capture renderer startup and GLB markers immediately after the gameplay
  // surface appears, before screenshot I/O can push them out of logcat's tail.
  const rendererTelemetry = createRendererTelemetryAccumulator(REQUIRED_HERO_PARTS);
  const initialRendererSnapshot = readRendererLogLines();
  rendererTelemetry.observe(initialRendererSnapshot);
  console.log("RENDERER TELEMETRY INITIAL SNAPSHOT=" + JSON.stringify(
    rendererTelemetry.snapshot(parseFramebufferReadback).evidence
  ));
  const capturePbrRendererTelemetry = (stage, { waitForReportMs = 0 } = {}) => {
    const startedAt = Date.now();
    const deadline = startedAt + Math.max(0, Math.min(waitForReportMs, 90_000));
    let attempts = 0;
    let filteredOk = false;
    let filteredLineCount = 0;
    let filteredError = null;
    let evidence = rendererTelemetry.snapshot(parseFramebufferReadback).evidence;

    do {
      // The debug probe is bounded (80s) and needs at least 120 GL frame
      // intervals. A one-shot logcat read immediately after a dice tap is too
      // early on SwiftShader; poll the renderer tag until the full report exists.
      const filtered = runAllowError(
        "logcat", "--limit", "500", "--filter", "LudoMadaRender", "--session", session
      );
      attempts += 1;
      filteredOk = filtered.ok;
      filteredError = filtered.ok
        ? null
        : String(filtered.value?.error || "logcat_error").slice(0, 120);
      const entries = normalizeLogcatRows(filtered);
      filteredLineCount = entries.length;
      const lines = entries.map((entry) => entry.line)
        .filter((line) => line.includes("PBR_FRAME_PACING ") ||
          line.includes("PBR_FRAME_PACING_PROGRESS ") ||
          line.includes("PBR_DISPLAY_REFRESH_HZ=") ||
          line.includes("PBR_UI_VSYNC"));
      rendererTelemetry.observe({
        totalLines: 0,
        filteredLines: entries.length,
        lines
      });
      evidence = rendererTelemetry.snapshot(parseFramebufferReadback).evidence;

      if (hasCompleteFramePacingReport(evidence.framePacingLines, 120)) break;
      const remaining = deadline - Date.now();
      if (remaining <= 0) break;
      waitStable(Math.min(1500, remaining));
    } while (Date.now() < deadline);

    const captured = hasCompleteFramePacingReport(evidence.framePacingLines, 120);
    console.log("RAMUS_FRAME_PACING_CAPTURE_" + stage + "=" + JSON.stringify({
      status: captured ? "PASS" : "MISSING",
      requiredSamples: 120,
      waitBudgetMs: Math.max(0, Math.min(waitForReportMs, 90_000)),
      elapsedMs: Date.now() - startedAt,
      pollAttempts: attempts,
      filteredOk,
      filteredError,
      filteredLineCount,
      framePacingMarkers: evidence.framePacingLines,
      progressMarkers: evidence.framePacingProgressLines,
      displayRefreshMarkers: evidence.displayRefreshLines,
      uiVsyncMarkers: evidence.uiVsyncLines
    }));
    return captured;
  };

  // Preserve an early capture even if renderer validation fails, but keep it
  // separate: GLSurfaceView may still be loading assets at this point.
  const preBoardShot = runAllowError(
    "screenshot", "--session", session, "--out", "ramus-pre-assertion-screen.jpg"
  );
  if (preBoardShot.ok) console.log("3D BOARD PRE-ASSERTION SCREENSHOT CAPTURED");
  else console.error("3D BOARD PRE-ASSERTION SCREENSHOT FAILED");

  let rendererFailure = null;
  try {
    assertRuntimeGlbLoaded(16000, rendererTelemetry);
    console.log("3D BOARD RENDERER ASSERTIONS PASS");
  } catch (error) {
    rendererFailure = error;
    console.error("3D BOARD RENDERER ASSERTIONS FAILED:",
      String(error?.message || error).slice(0, 1600));
  }

  // The final gameplay screenshot must be taken after the renderer's bounded
  // GLB/readback wait, on both success and failure paths.
  const boardShot = runAllowError("screenshot", "--session", session, "--out", "ramus-screen.jpg");
  if (!boardShot.ok) {
    console.error("Could not capture post-render 3D board evidence: " +
      String(boardShot.value?.error || boardShot.value?.message || "unknown").slice(0, 240));
  } else {
    console.log("3D BOARD POST-RENDER SCREENSHOT CAPTURED");
  }
  if (rendererFailure) throw rendererFailure;
  if (!boardShot.ok) throw new Error("Could not capture post-render 3D board screenshot");

  // Verify the 4-player mode and its complete turn rotation.
  target = findRef("← Hors-ligne");
  run("tap", target.ref, "--gen", String(target.gen), "--session", session);
  assertMenuButton("👥 3 joueurs");
  assertMenuButton("👥 4 joueurs");
  target = findRef("👥 4 joueurs");
  run("tap", target.ref, "--gen", String(target.gen), "--session", session);
  assertTurn("Joueur 1/4");
  for (const nextTurn of ["Joueur 2/4", "Joueur 3/4", "Joueur 4/4", "Joueur 1/4"]) {
    advanceToTurn(nextTurn);
  }
  console.log("4-PLAYER TURN ROTATION PASS");

  // Verify the complete 3-player turn rotation, not only the menu entry.
  target = findRef("← Hors-ligne");
  run("tap", target.ref, "--gen", String(target.gen), "--session", session);
  assertMenuButton("👥 3 joueurs");
  target = findRef("👥 3 joueurs");
  run("tap", target.ref, "--gen", String(target.gen), "--session", session);
  assertTurn("Joueur 1/3");
  for (const nextTurn of ["Joueur 2/3", "Joueur 3/3", "Joueur 1/3"]) {
    advanceToTurn(nextTurn);
  }
  console.log("3-PLAYER TURN ROTATION PASS");

  // Return to the 2-player board for the dice/pawn validation.
  target = findRef("← Hors-ligne");
  run("tap", target.ref, "--gen", String(target.gen), "--session", session);
  target = findRef("👤 2 joueurs");
  run("tap", target.ref, "--gen", String(target.gen), "--session", session);
  assertTurn("Joueur 1/2");
  console.log("2-PLAYER REENTRY PASS");
  for (const nextTurn of ["Joueur 2/2", "Joueur 1/2"]) {
    advanceToTurn(nextTurn);
  }
  console.log("2-PLAYER TURN ROTATION PASS");

  // Reset to a fresh two-player game so the exact-six/pawn scenario starts
  // with all four pawns in home, independent of the rotation test above.
  target = findRef("← Hors-ligne");
  run("tap", target.ref, "--gen", String(target.gen), "--session", session);
  target = findRef("👤 2 joueurs");
  run("tap", target.ref, "--gen", String(target.gen), "--session", session);
  assertTurn("Joueur 1/2");
  console.log("2-PLAYER RESET PASS");

  let gotSix = false;
  for (let i = 0; i < 48; i++) {
    target = findRef("Lancer le dé");
    run("tap", target.ref, "--gen", String(target.gen), "--session", session);

    const six = waitForSix(2200);
    const diceStatus = pendingDiceValue().status;
    console.log("RAMUS_DICE_ROLL_ATTEMPT=" + (i + 1) + " STATUS=" + diceStatus.slice(0, 100));
    if (six) {
      gotSix = true;
      break;
    }

    // A slow animation must not fail the whole smoke test. Give the board
    // a little time to settle before the next observation.
    runAllowError("wait", "--stable", "--timeout", "1800", "--session", session);
  }
  if (!gotSix) throw new Error("No six within 48 cloud-emulator rolls; last status: " + pendingDiceValue().status);
  console.log("DICE SIX PASS");
  capturePbrRendererTelemetry("AFTER_DICE", { waitForReportMs: 90_000 });

  // A pending die must be immutable until the player chooses a pawn.
  runAllowError("wait", "--stable", "--timeout", "1800", "--session", session);
  target = findRef("Lancer le dé");
  run("tap", target.ref, "--gen", String(target.gen), "--session", session);
  const blockedReroll = runAllowError(
    "wait", "--text", "Jouez d'abord le pion", "--timeout", "1800", "--session", session
  );
  if (!blockedReroll.ok) throw new Error("A second dice roll was accepted before moving a pawn");
  const preservedSix = runAllowError("snapshot", "--session", session, "--find", "Dé: 6");
  if (!preservedSix.ok || !preservedSix.value.elements?.length) {
    throw new Error("The pending six was not preserved after a rejected reroll");
  }
  console.log("PENDING DICE REROLL BLOCK PASS");

  target = findRef("Pion 1");
  run("tap", target.ref, "--gen", String(target.gen),
      "--expect", "Rejouez", "--session", session);
  console.log("SIX→PAWN→REPLAY PASS");
  capturePbrRendererTelemetry("AFTER_PAWN_REPLAY");

  // Exercise the real Home -> JOUER canvas hitbox rather than only the
  // login-screen offline shortcut. The universal deferred handler prevents
  // replacing the content hierarchy during the original Home touch dispatch.
  target = findRef("← Hors-ligne");
  run("tap", target.ref, "--gen", String(target.gen),
      "--expect", "← Accueil", "--session", session);
  target = findRef("← Accueil");
  run("tap", target.ref, "--gen", String(target.gen),
      "--session", session);
  if (!waitForSnapshot("LudoMada Premium Home", 8000)) {
    throw new Error("Offline menu could not return to Premium Home");
  }
  console.log("OFFLINE MENU→HOME PASS");

  let modeTree = openModePickerFromHome();
  console.log("HOME→JOUER MODE OVERLAY PASS");

  tapTreeNode(modeTree, "MODE_ONLINE");
  waitStable(700);
  let lobbyTree = readTree();
  if (!treeHas(lobbyTree, "Choisir la mise") || !treeHas(lobbyTree, "Hiditra amin'ny room")) {
    throw new Error("Online mode did not open the lobby entry screen. Accessibility tree: " + JSON.stringify(treeLabels(lobbyTree)).slice(0, 5000));
  }
  console.log("JOUER→ONLINE LOBBY PASS");

  if (!returnToHome()) throw new Error("Could not return from online lobby to Premium Home");
  modeTree = openModePickerFromHome();
  tapTreeNode(modeTree, "MODE_OFFLINE");
  waitStable(700);
  let offlineMenuTree = readTree();
  for (const expected of ["👤 2 joueurs", "👥 3 joueurs", "👥 4 joueurs"]) {
    if (!treeHas(offlineMenuTree, expected)) {
      throw new Error(`Offline mode menu is missing ${expected}. Accessibility tree: ` + JSON.stringify(treeLabels(offlineMenuTree)).slice(0, 5000));
    }
  }
  console.log("JOUER→OFFLINE MENU PASS");

  if (!returnToHome()) throw new Error("Could not return from offline mode menu to Premium Home");
  // Query renderer message prefixes explicitly at the end. The broad guest tail
  // may retain startup markers while later system traffic rotates out frame samples.
  const finalRendererSnapshot = readRendererLogLines({ forceRendererTagFilter: true });
  rendererTelemetry.observe(finalRendererSnapshot);
  const finalEvidence = rendererTelemetry.snapshot(parseFramebufferReadback).evidence;
  const finalFramePacingLines = finalEvidence.framePacingLines;
  const framePacingComplete = hasCompleteFramePacingReport(finalFramePacingLines, 120);
  console.log("RAMUS_FRAME_PACING_METRICS=" + JSON.stringify(finalFramePacingLines));
  console.log("RAMUS_FRAME_PACING_PROGRESS=" + JSON.stringify(finalEvidence.framePacingProgressLines));
  console.log("RAMUS_FRAME_PACING_CAPTURE=" + (framePacingComplete ? "PASS" : "MISSING"));
  console.log("RAMUS_UI_VSYNC_DIAGNOSTICS=" + JSON.stringify(finalEvidence.uiVsyncLines));
  console.log("RAMUS_UI_VSYNC_METRICS=" + JSON.stringify(finalEvidence.uiVsyncLines.filter(line => line.includes("PBR_UI_VSYNC samples="))));
  if (!framePacingComplete) {
    throw new Error("Frame-pacing telemetry report missing or incomplete after bounded 90s capture; progress=" +
      JSON.stringify(finalEvidence.framePacingProgressLines).slice(0, 2400));
  }
  success = true;
  console.log("RAMUS_RENDERER_FINAL_CAPTURE=" + JSON.stringify({
    broadOk: finalRendererSnapshot.broadOk,
    filteredRequested: finalRendererSnapshot.filteredRequested,
    filteredOk: finalRendererSnapshot.filteredOk,
    totalLines: finalRendererSnapshot.totalLines,
    filteredLines: finalRendererSnapshot.filteredLines,
    filteredError: finalRendererSnapshot.filteredError,
    rendererLines: finalRendererSnapshot.lines.length,
    framePacingMarkers: finalRendererSnapshot.lines.filter(line => line.includes("PBR_FRAME_PACING ")),
    refreshRateMarkers: finalRendererSnapshot.lines.filter(line => line.includes("PBR_DISPLAY_REFRESH_HZ=")),
    uiVsyncMarkers: finalRendererSnapshot.lines.filter(line => line.includes("PBR_UI_VSYNC "))
  }));
  console.log("RAMUS_DISPLAY_REFRESH_RATE=" + JSON.stringify(
    rendererTelemetry.snapshot(parseFramebufferReadback).evidence.displayRefreshLines
  ));
  console.log("RAMUS_SMOKE_PASS");
} finally {
  if (session && !success && !failureLogcatCaptured) {
    captureFailureLogcat();
  }
  if (session) {
    try { run("session", "end", "--session", session); }
    catch (e) { console.error("session end:", e.message); }
  }
  if (!success) {
    console.error("RAMUS_SMOKE_FAIL");
    process.exitCode = 1;
  }
}

function findRef(findText) {
  const s = waitForSnapshot(findText, 8000);
  if (!s?.elements?.length) throw new Error(`UI element not found after bounded wait: ${findText}`);
  return { ref: s.elements[0].ref, gen: s.gen };
}
