import { existsSync, readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";

function parseRendererReadiness(label) {
  if (typeof label !== "string") return null;
  const match = label.match(
    String.raw`RENDER_PASS=(GLES2_GLB|GLES3_PBR)\s*\|\s*HERO_PARTS=(\d+)\s*\|\s*BOARD_PARTS=(\d+)\s*\|\s*ENV_PARTS=(\d+)\s*\|\s*ENV_R7_COMPLETE=(0|1)`
  );
  if (!match) return null;
  const [heroPartCount, boardPartCount, environmentPartCount, revision7Complete] =
    match.slice(2).map(Number);
  if (![heroPartCount, boardPartCount, environmentPartCount].every(Number.isSafeInteger) ||
      heroPartCount < 0 || boardPartCount < 0 || environmentPartCount < 0) {
    return null;
  }
  return {
    rendererName: match[1],
    heroPartCount,
    boardPartCount,
    environmentPartCount,
    revision7EnvironmentReady: revision7Complete === 1
  };
}

function rendererReadinessFailureReason(readiness) {
  if (!readiness) return "readiness_marker_missing";
  if (readiness.heroPartCount < 6) return "incomplete_hero_glb";
  if (readiness.boardPartCount <= 0) return "missing_board_glb";
  // GLES2 compatibility renderer intentionally uses a procedural tabletop.
  if (readiness.rendererName === "GLES2_GLB") return null;
  if (readiness.environmentPartCount <= 0) return "gameplay_environment_missing";
  if (!readiness.revision7EnvironmentReady) return "legacy_environment_not_revision7";
  return null;
}

function rendererReadinessPassed(readiness) {
  return rendererReadinessFailureReason(readiness) === null;
}

function parseBoardViewport(tree) {
  const marker = treeLabels(tree).find(label => label.includes("RENDER_PASS=") && label.includes("BOARD_VIEWPORT=")) || "";
  const match = marker.match(/BOARD_VIEWPORT=(\d+)x(\d+)/);
  if (!match) return null;
  const width = Number(match[1]);
  const height = Number(match[2]);
  if (!Number.isSafeInteger(width) || !Number.isSafeInteger(height) || width <= 0 || height <= 0) return null;
  return { width, height };
}

function readJpegDimensions(path) {
  if (!existsSync(path)) return null;
  const bytes = readFileSync(path);
  if (bytes.length < 4 || bytes[0] !== 0xff || bytes[1] !== 0xd8) return null;
  const sofMarkers = new Set([0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf]);
  let offset = 2;
  while (offset + 4 <= bytes.length) {
    if (bytes[offset] !== 0xff) return null;
    while (offset < bytes.length && bytes[offset] === 0xff) offset++;
    if (offset >= bytes.length) return null;
    const marker = bytes[offset++];
    if (marker === 0xd9 || marker === 0xda) return null;
    if (marker >= 0xd0 && marker <= 0xd7) continue;
    if (offset + 2 > bytes.length) return null;
    const segmentLength = bytes.readUInt16BE(offset);
    if (segmentLength < 2 || offset + segmentLength > bytes.length) return null;
    if (sofMarkers.has(marker)) {
      if (segmentLength < 7) return null;
      return {
        height: bytes.readUInt16BE(offset + 3),
        width: bytes.readUInt16BE(offset + 5)
      };
    }
    offset += segmentLength;
  }
  return null;
}

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

  const pbrReady = parseRendererReadiness(
    "LudoMada 3D Ludo board | RENDER_PASS=GLES3_PBR | HERO_PARTS=6 | BOARD_PARTS=95 | ENV_PARTS=13 | ENV_R7_COMPLETE=1"
  );
  const pbrLegacyEnvironment = parseRendererReadiness(
    "LudoMada 3D Ludo board | RENDER_PASS=GLES3_PBR | HERO_PARTS=6 | BOARD_PARTS=95 | ENV_PARTS=13 | ENV_R7_COMPLETE=0"
  );
  const pbrWithoutEnvironment = parseRendererReadiness(
    "LudoMada 3D Ludo board | RENDER_PASS=GLES3_PBR | HERO_PARTS=6 | BOARD_PARTS=95 | ENV_PARTS=0 | ENV_R7_COMPLETE=0"
  );
  const safeFallback = parseRendererReadiness(
    "LudoMada 3D Ludo board | RENDER_PASS=GLES2_GLB | HERO_PARTS=6 | BOARD_PARTS=95 | ENV_PARTS=0 | ENV_R7_COMPLETE=0"
  );

  const cases = [
    ["GLES3 PBR readiness accepts a prepared gameplay environment", rendererReadinessPassed(pbrReady)],
    ["GLES3 PBR readiness rejects a missing gameplay environment", !rendererReadinessPassed(pbrWithoutEnvironment)],
    ["missing PBR environment reports a specific reason", rendererReadinessFailureReason(pbrWithoutEnvironment) === "gameplay_environment_missing"],
    ["GLES3 PBR readiness rejects legacy non-batched Blender parts", !rendererReadinessPassed(pbrLegacyEnvironment)],
    ["legacy Blender environment reports a revision mismatch", rendererReadinessFailureReason(pbrLegacyEnvironment) === "legacy_environment_not_revision7"],
    ["GLES2 fallback remains eligible with its procedural tabletop", rendererReadinessPassed(safeFallback)],
    ["GLES2 fallback has no readiness rejection reason", rendererReadinessFailureReason(safeFallback) === null],
    ["missing environment count marker rejected", parseRendererReadiness("RENDER_PASS=GLES3_PBR | HERO_PARTS=6 | BOARD_PARTS=95") === null],
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
  const referenceHomeTree = { elements: [{ contentDesc:
    "LudoMada Premium Home | HOME_IMAGE_FRAME_COMPLETE | IMAGE_SOURCE=LOBBY_REFERENCE_4K | IMAGE_WIDTH=2048 | IMAGE_HEIGHT=1152 | IMAGE_CONFIG=RGB_565"
  }] };
  const undersizedHomeTree = { elements: [{ contentDesc:
    "LudoMada Premium Home | HOME_IMAGE_FRAME_COMPLETE | IMAGE_SOURCE=LOBBY_REFERENCE_4K | IMAGE_WIDTH=512 | IMAGE_HEIGHT=288"
  }] };
  const wrongHomeSourceTree = { elements: [{ contentDesc:
    "LudoMada Premium Home | HOME_IMAGE_FRAME_COMPLETE | IMAGE_SOURCE=UNKNOWN | IMAGE_WIDTH=2048 | IMAGE_HEIGHT=1152"
  }] };
  const immersivePromptTree = { elements: [
    { text: "Viewing full screen" },
    { text: "To exit, swipe down from the top of your screen" },
    { text: "Got it" }
  ] };
  const ordinaryTree = { elements: [
    { text: "Jouer" },
    { contentDesc: "LudoMada Premium Home" }
  ] };
  const accessibleHomeTree = { elements: [{ contentDesc: "HOME_ACTION_JOUER", ref: "node-home-play" }] };
  const inaccessibleHomeTree = { elements: [{ contentDesc: "LudoMada Premium Home" }] };
  const readinessMarkedHomeTree = { elements: [
    { contentDesc: "HOME_IMAGE_FRAME_COMPLETE | IMAGE_SOURCE=LOBBY_REFERENCE_4K" },
    { contentDesc: "HOME_ACTION_JOUER", ref: "node-home-play" }
  ] };
  cases.push(
    ["Home JOUER accessibility target detected", hasHomeJouerTarget(accessibleHomeTree)],
    ["Home with current image-readiness label recognized", isPremiumHome(readinessMarkedHomeTree)],
    ["Home without JOUER target rejected", !hasHomeJouerTarget(inaccessibleHomeTree)],
    ["decoded lightweight 4K Home reference accepted", parseHomeImageReadiness(referenceHomeTree).ok],
    ["undersized Home image rejected", !parseHomeImageReadiness(undersizedHomeTree).ok],
    ["wrong Home image source rejected", !parseHomeImageReadiness(wrongHomeSourceTree).ok],
    ["missing Home image readiness marker rejected", !parseHomeImageReadiness({ elements: [] }).ok],
    ["Android immersive confirmation prompt detected", isAndroidImmersivePrompt(immersivePromptTree)],
    ["ordinary app screen is not treated as system prompt", !isAndroidImmersivePrompt(ordinaryTree)]
  );
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

function isAndroidImmersivePrompt(tree) {
  const labels = treeLabels(tree);
  return labels.some(label => label.includes("Viewing full screen")) &&
    labels.some(label => label.includes("To exit, swipe down from the top of your screen")) &&
    labels.some(label => label === "Got it" || label.endsWith(" Got it"));
}

function readTree() {
  const result = runAllowError("snapshot", "--session", session, "--include-offscreen");
  if (!result.ok || !Array.isArray(result.value?.elements) || result.value.elements.length === 0) return null;

  // Android 8.1 / cloud AVDs can show the platform's one-time immersive-mode
  // confirmation after the app switches to fullscreen. It is a system window,
  // not an app screen, and blocks Home accessibility hitboxes until dismissed.
  // Only tap "Got it" when all three known system-prompt labels are present.
  if (isAndroidImmersivePrompt(result.value)) {
    const gotItNode = result.value.elements.find(node =>
      node.text === "Got it" || node.contentDesc === "Got it" ||
      node.contentDescription === "Got it" || node.label === "Got it"
    );
    if (gotItNode?.ref) {
      const dismissed = runAllowError(
        "tap", gotItNode.ref, "--gen", String(result.value.gen), "--session", session
      );
      if (dismissed.ok) {
        waitStable(250);
        const fresh = runAllowError("snapshot", "--session", session, "--include-offscreen");
        if (fresh.ok && Array.isArray(fresh.value?.elements) && fresh.value.elements.length > 0) {
          return fresh.value;
        }
      }
    }
  }
  return result.value;
}

function treeHas(tree, value) {
  return treeLabels(tree).some((label) => label.includes(value));
}

function scrollToVisibleText(value, maxSwipes = 4) {
  let tree = readTree();
  if (treeHas(tree, value)) return tree;

  for (let attempt = 0; attempt < maxSwipes; attempt++) {
    // The main screens use a ScrollView. In landscape the online lobby content
    // can extend below the 720px viewport; swipe upward to reveal its lower
    // controls, then reacquire the accessibility tree after every gesture.
    const swipe = runAllowError(
      "swipe", "0.5,0.82", "0.5,0.22", "--session", session
    );
    if (!swipe.ok) {
      throw new Error(
        "Could not scroll to visible UI text " + value + ": " +
        JSON.stringify(swipe.value).slice(0, 1200)
      );
    }
    waitStable(350);
    tree = readTree();
    if (treeHas(tree, value)) return tree;
  }
  return tree;
}

function parseHomeImageReadiness(tree) {
  const marker = treeLabels(tree).find((label) => label.includes("HOME_IMAGE_FRAME_COMPLETE"));
  if (!marker) return { ok: false, reason: "missing_home_image_frame_marker" };
  const source = marker.match(/IMAGE_SOURCE=([A-Z0-9_]+)/)?.[1] || "";
  const width = Number(marker.match(/IMAGE_WIDTH=(\d+)/)?.[1] || 0);
  const height = Number(marker.match(/IMAGE_HEIGHT=(\d+)/)?.[1] || 0);
  const config = marker.match(/IMAGE_CONFIG=([A-Z0-9_]+)/)?.[1] || "";
  const valid = source === "LOBBY_REFERENCE_4K" &&
    config === "RGB_565" &&
    Number.isSafeInteger(width) && width >= 960 &&
    Number.isSafeInteger(height) && height >= 540;
  return {
    ok: valid,
    source,
    config,
    width,
    height,
    reason: valid ? null : "invalid_or_undersized_home_reference"
  };
}

function waitForTreeMarker(value, timeoutMs = 6000) {
  const deadline = Date.now() + timeoutMs;
  let last = null;
  while (Date.now() < deadline) {
    last = readTree();
    if (treeHas(last, value)) return last;
    waitStable(250);
  }
  throw new Error("Accessibility tree marker missing after " + timeoutMs + "ms: " + value +
    "; lastLabels=" + JSON.stringify(treeLabels(last)));
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

function selectAndAssertHomeStake(amount) {
  const tree = readTree();
  const targetName = `HOME_STAKE_${amount}`;
  if (!treeHas(tree, targetName)) {
    throw new Error(`Home stake accessibility target missing: ${targetName}; labels=${JSON.stringify(treeLabels(tree)).slice(0, 5000)}`);
  }
  tapTreeNode(tree, targetName);
  const selectedTree = waitForTreeMarker(`HOME_SELECTED_STAKE=${amount}`, 4500);
  const marker = treeLabels(selectedTree).find(label => label.includes("HOME_SELECTED_STAKE=")) || "";
  const selected = Number(marker.match(/HOME_SELECTED_STAKE=(\d+)/)?.[1] || 0);
  if (selected !== amount) {
    throw new Error(`Home stake selection mismatch: expected ${amount}, observed ${selected}; marker=${marker}`);
  }
  console.log(`HOME STAKE ${amount} AR SELECT PASS`);
}

function isPremiumHome(tree) {
  // The image host publishes HOME_IMAGE_FRAME_COMPLETE as its current
  // contentDescription; Android may expose that node without the FrameLayout
  // root's older "LudoMada Premium Home" label. Accept either verified marker.
  return treeHas(tree, "LudoMada Premium Home") ||
    treeHas(tree, "HOME_IMAGE_FRAME_COMPLETE");
}

function hasHomeJouerTarget(tree) {
  return (tree?.elements || []).some(node =>
    [node.text, node.contentDesc, node.contentDescription, node.description, node.label]
      .some(value => value === "HOME_ACTION_JOUER")
  );
}

function returnToHome() {
  for (let attempt = 0; attempt < 3; attempt++) {
    let tree = readTree();
    if (isPremiumHome(tree)) return true;
    if (!treeHas(tree, "← Accueil")) {
      tree = scrollToVisibleText("← Accueil", 4);
    }
    if (!treeHas(tree, "← Accueil")) return false;
    tapTreeNode(tree, "← Accueil");
    waitStable(650);
    tree = readTree();
    if (isPremiumHome(tree)) return true;
  }
  return isPremiumHome(readTree());
}

function openModePickerFromHome() {
  // Prefer the real semantic hitbox. Coordinate probes remain a fallback for
  // older APKs that do not yet expose HOME_ACTION_JOUER.
  const points = [
    "0.86,0.575", "0.84,0.575", "0.88,0.575",
    "0.86,0.55", "0.86,0.60", "0.82,0.55", "0.90,0.60"
  ];
  let lastLabels = [];
  let accessibleTargetAttempted = false;

  let tree = readTree();
  if (!isPremiumHome(tree) && returnToHome()) tree = readTree();
  if (isPremiumHome(tree) && hasHomeJouerTarget(tree)) {
    accessibleTargetAttempted = true;
    tapTreeNode(tree, "HOME_ACTION_JOUER");
    waitStable(500);
    const afterAccessibleTap = readTree();
    lastLabels = treeLabels(afterAccessibleTap);
    if (modePickerIsVisible(afterAccessibleTap)) return afterAccessibleTap;
  }

  for (const point of points) {
    tree = readTree();
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
    if (modePickerIsVisible(afterTap)) return afterTap;

    if (!isPremiumHome(afterTap) && treeHas(afterTap, "← Accueil")) {
      returnToHome();
    }
    if (!tap.ok) waitStable(200);
  }

  const diagnosticShot = runAllowError(
    "screenshot", "--session", session, "--out", "ramus-home-jouer-failure.jpg"
  );
  console.error("RAMUS_HOME_JOUER_DIAGNOSTIC=" + JSON.stringify({
    accessibleTargetAttempted,
    lastLabels,
    screenshotCaptured: diagnosticShot.ok
  }).slice(0, 5000));
  throw new Error("Could not open the Home JOUER mode picker after semantic activation and coordinate fallback. Last accessibility tree: " + JSON.stringify(lastLabels).slice(0, 7000));
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

function pawnTapTargets(tree = readTree()) {
  const label = treeLabels(tree).find((entry) =>
    entry.includes("RENDER_PASS=") && entry.includes("PAWN_TAP_TARGETS=")
  ) || "";
  const match = label.match(/PAWN_TAP_TARGETS=([^|]*)/);
  if (!match) return [];
  return match[1].split(";").map((entry) => {
    const point = entry.trim().match(/^([0-3])@((?:0|1|0?\.\d+)),((?:0|1|0?\.\d+))$/);
    return point ? { index: Number(point[1]), point: point[2] + "," + point[3] } : null;
  }).filter(Boolean);
}

function assertNumberedPawnControlsAbsent(tree = readTree()) {
  const labels = treeLabels(tree);
  const visible = labels.filter((label) => /\bPion\s+[1-4]\b/i.test(label));
  if (visible.length) {
    throw new Error("Numbered pawn controls must stay removed: " + JSON.stringify(visible.slice(0, 8)));
  }
  console.log("NUMBERED PAWN CONTROLS ABSENT PASS");
}

function moveAnyLegalPawnAfterRoll() {
  const { value: dice, status } = pendingDiceValue();
  if (dice === null || status.includes("Aucun coup")) return false;

  const deadline = Date.now() + 6500;
  let targets = [];
  let lastLabels = [];
  while (Date.now() < deadline) {
    const tree = readTree();
    lastLabels = treeLabels(tree);
    targets = pawnTapTargets(tree);
    if (targets.length) break;
    waitStable(250);
  }
  if (!targets.length) {
    throw new Error(
      "No projected legal 3D pawn tap targets after Dé " + dice +
      "; labels=" + JSON.stringify(lastLabels.slice(0, 40))
    );
  }

  for (const target of targets) {
    const tap = runAllowError("tap", target.point, "--session", session, "--force");
    if (!tap.ok) continue;
    // A legal board hit clears the pending die. Illegal or stale coordinates
    // leave it unchanged, allowing the next projected pawn coordinate to be tried.
    const moved = runAllowError(
      "wait", "--text", "Dé: 0", "--timeout", "1600", "--session", session
    );
    if (moved.ok) {
      console.log("3D PAWN COORDINATE TAP PASS index=" + target.index);
      return true;
    }
    waitStable(250);
  }

  throw new Error(
    "Dice " + dice + " exposed " + targets.length +
    " projected pawn targets but none completed a legal move. Status: " +
    pendingDiceValue().status
  );
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

  // Read the unfiltered guest tail once. Ramus applies --filter to text/stream;
  // keeping the raw tail lets us distinguish "no guest logs" from a filter mismatch.
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
  const rendererPattern = /Renderer=GLES3_PBR|PBR_SURFACE_CREATED|GLB_RUNTIME_LOAD_OK|BOARD_GLB_RUNTIME_LOAD_OK|ENV_GLB_RUNTIME_LOAD_OK|BAOBAB_REAL_GLB_LOAD_OK|PBR_READBACK|PBR_FIRST_FRAME_COMPLETE|HOME_PBR_FIRST_FRAME_COMPLETE|HOME_3D_FALLBACK_FRAME_COMPLETE/i;
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

function readRendererLogLines() {
  // Some Ramus sessions expose only system rows in the broad tail. Read both app
  // tags directly so the GLES2 fallback and its acceptance markers are visible too.
  const markers = /Renderer=GLES[23]_(?:PBR|3D_FALLBACK)|PBR_SURFACE_CREATED|SAFE_SURFACE_CREATED|GLB_RUNTIME_LOAD_OK|BOARD_GLB_RUNTIME_LOAD_OK|ENV_GLB_RUNTIME_LOAD_OK|BAOBAB_REAL_GLB_LOAD_OK|BOARD_DRAW_BATCH_READY|HERO_PAWN_BATCH_READY|PERF_SCENE_BREAKDOWN|PBR_READBACK|SAFE_READBACK|PBR_FIRST_FRAME_COMPLETE|SAFE_FIRST_FRAME_COMPLETE|HOME_PBR_FIRST_FRAME_COMPLETE|HOME_3D_FALLBACK_FRAME_COMPLETE|BOARD_MARKER=/i;
  const broad = runAllowError("logcat", "--limit", "500", "--session", session);
  const broadEntries = normalizeLogcatRows(broad);
  let lines = broadEntries.map((entry) => entry.line).filter((line) => markers.test(line));
  let filteredEntries = [];
  const filteredResults = [];
  if (lines.length === 0 ||
      !lines.some((line) => /Renderer=GLES[23]_/i.test(line)) ||
      !lines.some((line) => /BOARD_DRAW_BATCH_READY|HERO_PAWN_BATCH_READY/i.test(line))) {
    for (const tag of ["LudoMadaRender", "LudoMadaSmoke"]) {
      const filtered = runAllowError("logcat", "--limit", "500", "--filter", tag, "--session", session);
      filteredResults.push(filtered);
      const entries = normalizeLogcatRows(filtered);
      filteredEntries.push(...entries);
    }
    const extra = filteredEntries.map((entry) => entry.line).filter((line) => markers.test(line));
    lines = [...new Set([...lines, ...extra])];
  }
  return {
    ok: broad.ok || filteredResults.some((result) => result.ok),
    totalLines: broadEntries.length,
    filteredLines: filteredEntries.length,
    error: broad.ok || filteredResults.some((result) => result.ok)
      ? null
      : String(filteredResults[0]?.value?.error || broad.value?.error || "logcat_error").slice(0, 80),
    lines
  };
}

function parseSafeReadback(line) {
  if (typeof line !== "string") return { ok: false, reason: "missing_safe_readback" };
  const match = line.match(/SAFE_READBACK label=surface size=(\d+)x(\d+) brightPixels=(\d+)\/(\d+) maxChannel=(\d+) glError=0x([0-9a-f]+)/i);
  if (!match) return { ok: false, reason: "malformed_safe_readback" };
  const [width, height, brightPixels, totalPixels, maxChannel] = match.slice(1, 6).map(Number);
  const glError = Number.parseInt(match[6], 16);
  const dimensionsValid = Number.isSafeInteger(width) && width > 0 &&
    Number.isSafeInteger(height) && height > 0 && totalPixels === width * height;
  const countsValid = Number.isSafeInteger(brightPixels) && brightPixels >= 0 &&
    brightPixels <= totalPixels && Number.isSafeInteger(maxChannel) &&
    maxChannel >= 0 && maxChannel <= 255;
  if (!dimensionsValid || !countsValid) return { ok: false, reason: "invalid_safe_metrics" };
  if (glError !== 0) return { ok: false, reason: "gl_error", glError, brightPixels, totalPixels, maxChannel };
  const minimumBrightPixels = Math.max(32, Math.ceil(totalPixels * 0.005));
  const ok = brightPixels >= minimumBrightPixels && maxChannel > 24;
  return { ok, reason: ok ? undefined : "insufficient_visible_pixels", width, height, brightPixels, totalPixels, maxChannel, glError };
}

function assertBoardDrawBatches(timeoutMs = 6000) {
  const deadline = Date.now() + timeoutMs;
  let lastDiagnostics = { ok: false, lines: [], error: "not_read" };
  while (Date.now() < deadline) {
    lastDiagnostics = readRendererLogLines();
    const line = lastDiagnostics.lines.find(entry => entry.includes("BOARD_DRAW_BATCH_READY")) || "";
    const match = line.match(/BOARD_DRAW_BATCH_READY sourceParts=(\d+) pbrBatches=(\d+) shadowBatches=(\d+)/);
    if (match) {
      const sourceParts = Number(match[1]);
      const pbrBatches = Number(match[2]);
      const shadowBatches = Number(match[3]);
      if (!Number.isSafeInteger(sourceParts) || !Number.isSafeInteger(pbrBatches) ||
          !Number.isSafeInteger(shadowBatches) || sourceParts < 80 || pbrBatches < 1 ||
          pbrBatches > 16 || shadowBatches < 1 || shadowBatches > 4 || pbrBatches >= sourceParts) {
        throw new Error("Static board geometry was not reduced to compact draw batches: " +
          JSON.stringify({ sourceParts, pbrBatches, shadowBatches, line }));
      }
      const reduction = Math.round((1 - pbrBatches / sourceParts) * 100);
      console.log(`BOARD_DRAW_BATCH_PASS sourceParts=${sourceParts} pbrBatches=${pbrBatches} shadowBatches=${shadowBatches} colourDrawReduction=${reduction}%`);
      return { sourceParts, pbrBatches, shadowBatches };
    }
    waitStable(Math.min(250, Math.max(0, deadline - Date.now())));
  }
  throw new Error("PBR board geometry did not publish BOARD_DRAW_BATCH_READY: " +
    JSON.stringify({ logcatOk: lastDiagnostics.ok, lineCount: lastDiagnostics.lines.length,
      error: lastDiagnostics.error, relevant: lastDiagnostics.lines.slice(-25) }));
}

function assertHeroPawnBatches(timeoutMs = 5000) {
  const deadline = Date.now() + timeoutMs;
  let lastDiagnostics = { ok: false, lines: [], error: "not_read" };
  while (Date.now() < deadline) {
    lastDiagnostics = readRendererLogLines();
    const line = lastDiagnostics.lines.find(entry => entry.includes("HERO_PAWN_BATCH_READY")) || "";
    const match = line.match(/HERO_PAWN_BATCH_READY sourceParts=(\d+) drawBatches=(\d+)/);
    if (match) {
      const sourceParts = Number(match[1]);
      const drawBatches = Number(match[2]);
      if (sourceParts !== 5 || !Number.isSafeInteger(drawBatches) || drawBatches < 1 || drawBatches > 2) {
        throw new Error("The five authored pawn parts were not reduced to one or two mesh batches: " +
          JSON.stringify({ sourceParts, drawBatches, line }));
      }
      const reduction = Math.round((1 - drawBatches / sourceParts) * 100);
      console.log(`HERO_PAWN_BATCH_PASS sourceParts=${sourceParts} drawBatches=${drawBatches} colourDrawReduction=${reduction}%`);
      return { sourceParts, drawBatches };
    }
    waitStable(Math.min(250, Math.max(0, deadline - Date.now())));
  }
  throw new Error("PBR renderer did not publish HERO_PAWN_BATCH_READY: " +
    JSON.stringify({ logcatOk: lastDiagnostics.ok, lineCount: lastDiagnostics.lines.length,
      error: lastDiagnostics.error, relevant: lastDiagnostics.lines.slice(-25) }));
}

function assertRuntimeGlbLoaded(timeoutMs = 16000) {
  const deadline = Date.now() + timeoutMs;
  const requiredHeroParts = [
    "LM_Pawn_Base_Weighted",
    "LM_Pawn_Collar",
    "LM_Pawn_Spherical_Head",
    "LM_Pawn_Tapered_Body",
    "LM_Pawn_Underside_Insert",
    "LM_Die_HydroChrome_Body"
  ];
  let lastLogcat = { ok: false, totalLines: 0, error: "not_read" };
  let lastReadinessDiagnostic = {
    label: "",
    parsed: null,
    reason: "readiness_marker_missing",
    accessibilityLabels: []
  };
  let readinessRejectionLogged = false;
  let checks = {
    gles3Pbr: false,
    gles2Fallback: false,
    pbrSurfaceCreated: false,
    safeSurfaceCreated: false,
    heroParts: [],
    missingHeroParts: requiredHeroParts,
    boardParts: 0,
    sceneReadback: false,
    surfaceReadback: false,
    safeReadback: false,
    firstFrameComplete: false,
    safeFirstFrameComplete: false
  };
  while (Date.now() < deadline) {
    // Trial sessions expose UI snapshots even when device logcat contains only
    // system rows. The app publishes this marker only after real GL readPixels,
    // GL_NO_ERROR, complete hero GLB parts and a non-empty board GLB are verified.
    const accessibilityTree = readTree();
    const readyLabel = treeLabels(accessibilityTree).find((label) =>
      label.includes("RENDER_PASS=") &&
      label.includes("LudoMada 3D Ludo board")
    ) || "";
    const readiness = parseRendererReadiness(readyLabel);
    lastReadinessDiagnostic = {
      label: readyLabel.slice(0, 1200),
      parsed: readiness,
      reason: rendererReadinessFailureReason(readiness),
      accessibilityLabels: treeLabels(accessibilityTree).slice(0, 36).map(label => label.slice(0, 180))
    };
    if (readiness && !readinessRejectionLogged && lastReadinessDiagnostic.reason !== null) {
      console.error("RUNTIME_READINESS_REJECTED=" + JSON.stringify(lastReadinessDiagnostic).slice(0, 5000));
      readinessRejectionLogged = true;
    }
    if (rendererReadinessPassed(readiness)) {
      const { rendererName, heroPartCount, boardPartCount, environmentPartCount } = readiness;
      console.log(rendererName + " ACCESSIBILITY RENDER PASS");
      console.log("RUNTIME HERO GLB LOAD PASS parts=" + heroPartCount);
      console.log("RUNTIME BOARD GLB LOAD PASS parts=" + boardPartCount);
      if (rendererName === "GLES3_PBR") {
        console.log("RUNTIME GAMEPLAY ENVIRONMENT PASS parts=" + environmentPartCount);
        console.log("RUNTIME BLENDER REVISION 7 BATCH/TRAY CONTRACT PASS");
      } else {
        console.log("RUNTIME GAMEPLAY ENVIRONMENT FALLBACK PASS proceduralTabletop=true");
      }
      console.log("GL FRAMEBUFFER READBACK PASS (app-certified GL_NO_ERROR + visible pixel threshold)");
      return;
    }

    lastLogcat = readRendererLogLines();
    const lines = lastLogcat.lines;
    const heroLine = lines.find((line) =>
      line.includes("GLB_RUNTIME_LOAD_OK parts=") &&
      !line.includes("BOARD_GLB_RUNTIME_LOAD_OK")
    );
    const heroText = heroLine?.split("GLB_RUNTIME_LOAD_OK parts=")[1] || "";
    const heroParts = heroText.split(",").map((part) => part.trim()).filter(Boolean);
    const boardLine = lines.find((line) => line.includes("BOARD_GLB_RUNTIME_LOAD_OK parts="));
    const boardText = boardLine?.split("BOARD_GLB_RUNTIME_LOAD_OK parts=")[1] || "";
    const boardCount = Number.parseInt(boardText, 10);
    const environmentLine = lines.find((line) =>
      line.includes("GAMEPLAY_ENVIRONMENT_PREPARED drawParts=")
    );
    const environmentText = environmentLine?.split("GAMEPLAY_ENVIRONMENT_PREPARED drawParts=")[1] || "";
    const environmentCount = Number.parseInt(environmentText, 10);
    const revision7EnvironmentReady = /revision7Complete=true/i.test(environmentLine || "");
    const sceneReadbackLine = lines.find((line) => line.includes("PBR_READBACK label=scene_fbo"));
    const surfaceReadbackLine = lines.find((line) => line.includes("PBR_READBACK label=surface_backbuffer"));
    const safeReadbackLine = lines.find((line) => line.includes("SAFE_READBACK label=surface"));
    checks = {
      gles3Pbr: lines.some((line) => line.includes("Renderer=GLES3_PBR")),
      gles2Fallback: lines.some((line) => line.includes("Renderer=GLES2_3D_FALLBACK")),
      pbrSurfaceCreated: lines.some((line) => line.includes("PBR_SURFACE_CREATED")),
      safeSurfaceCreated: lines.some((line) => line.includes("SAFE_SURFACE_CREATED")),
      heroParts,
      missingHeroParts: requiredHeroParts.filter((part) => !heroParts.includes(part)),
      boardParts: Number.isFinite(boardCount) ? boardCount : 0,
      environmentParts: Number.isFinite(environmentCount) ? environmentCount : 0,
      revision7EnvironmentReady,
      sceneReadback: parseFramebufferReadback(sceneReadbackLine, "scene_fbo"),
      surfaceReadback: parseFramebufferReadback(surfaceReadbackLine, "surface_backbuffer"),
      safeReadback: parseSafeReadback(safeReadbackLine),
      firstFrameComplete: lines.some((line) => line.includes("PBR_FIRST_FRAME_COMPLETE")),
      safeFirstFrameComplete: lines.some((line) => line.includes("SAFE_FIRST_FRAME_COMPLETE"))
    };
    if (checks.gles3Pbr && checks.pbrSurfaceCreated &&
        checks.missingHeroParts.length === 0 && checks.boardParts > 0 &&
        checks.environmentParts > 0 && checks.revision7EnvironmentReady &&
        checks.sceneReadback.ok && checks.surfaceReadback.ok && checks.firstFrameComplete) {
      console.log("GLES3 PBR RENDERER PASS");
      console.log("RUNTIME HERO GLB LOAD PASS parts=" + checks.heroParts.length);
      console.log("RUNTIME BOARD GLB LOAD PASS parts=" + checks.boardParts);
      console.log("RUNTIME GAMEPLAY ENVIRONMENT PASS parts=" + checks.environmentParts);
      console.log("RUNTIME BLENDER REVISION 7 BATCH/TRAY CONTRACT PASS");
      console.log("PBR SCENE FBO READBACK PASS=" + JSON.stringify(checks.sceneReadback));
      console.log("PBR SURFACE READBACK PASS=" + JSON.stringify(checks.surfaceReadback));
      console.log("PBR FIRST FRAME COMPLETE");
      return;
    }
    if (checks.gles2Fallback && checks.safeSurfaceCreated &&
        checks.missingHeroParts.length === 0 && checks.boardParts > 0 &&
        checks.safeReadback.ok && checks.safeFirstFrameComplete) {
      console.log("GLES2 GLB FALLBACK RENDERER PASS");
      console.log("RUNTIME HERO GLB LOAD PASS parts=" + checks.heroParts.length);
      console.log("RUNTIME BOARD GLB LOAD PASS parts=" + checks.boardParts);
      console.log("GLES2 SURFACE READBACK PASS=" + JSON.stringify(checks.safeReadback));
      console.log("GLES2 FIRST FRAME COMPLETE");
      return;
    }
    waitStable(Math.min(1800, Math.max(0, deadline - Date.now())));
  }
  throw new Error("Runtime GLB/frame verification failed: " +
    JSON.stringify({ readiness: lastReadinessDiagnostic, checks, logcat: {
      ok: lastLogcat.ok,
      totalLines: lastLogcat.totalLines,
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
  // Preserve an early capture even if renderer validation fails, but keep it
  // separate: GLSurfaceView may still be loading assets at this point.
  const preBoardShot = runAllowError(
    "screenshot", "--session", session, "--out", "ramus-pre-assertion-screen.jpg"
  );
  if (preBoardShot.ok) console.log("3D BOARD PRE-ASSERTION SCREENSHOT CAPTURED");
  else console.error("3D BOARD PRE-ASSERTION SCREENSHOT FAILED");

  let rendererFailure = null;
  try {
    assertRuntimeGlbLoaded();
    console.log("3D BOARD RENDERER ASSERTIONS PASS");
  assertNumberedPawnControlsAbsent(readTree());
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

  if (!moveAnyLegalPawnAfterRoll()) {
    throw new Error("Pending six had no legal 3D pawn target");
  }
  const replay = runAllowError(
    "wait", "--text", "Rejouez", "--timeout", "2500", "--session", session
  );
  if (!replay.ok) throw new Error("Six-to-pawn move did not preserve the player's replay");
  console.log("SIX→3D PAWN TAP→REPLAY PASS");

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
  if (!treeHas(lobbyTree, "Choisir la mise")) {
    throw new Error("Online mode did not open the stake-selection lobby. Accessibility tree: " + JSON.stringify(treeLabels(lobbyTree)).slice(0, 5000));
  }
  for (const option of ["500 Ar", "1 000 Ar", "2 000 Ar", "5 000 Ar", "10 000 Ar", "20 000 Ar"]) {
    if (!treeHas(lobbyTree, option)) {
      throw new Error(`Online lobby is missing stake option ${option}. Accessibility tree: ` + JSON.stringify(treeLabels(lobbyTree)).slice(0, 5000));
    }
  }
  console.log("ONLINE LOBBY SIX STAKE OPTIONS PASS");
  lobbyTree = scrollToVisibleText("Hiditra amin'ny room", 4);
  if (!treeHas(lobbyTree, "Hiditra amin'ny room")) {
    throw new Error("Online lobby opened, but its room-entry action did not become visible after scrolling. Accessibility tree: " + JSON.stringify(treeLabels(lobbyTree)).slice(0, 5000));
  }
  console.log("JOUER→ONLINE LOBBY PASS (room-entry action verified after scroll)");

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

  // Home is intentionally a lightweight static 4K reference. Gameplay remains
  // the only screen required to create and validate a real GLES3/PBR environment.
  const homeReadyTree=waitForTreeMarker("HOME_IMAGE_FRAME_COMPLETE",5000);
  const homeReadiness=parseHomeImageReadiness(homeReadyTree);
  if (!homeReadiness.ok) {
    throw new Error("Static Home image failed acceptance: " +
      JSON.stringify({reason:homeReadiness.reason,tree:treeLabels(homeReadyTree)}));
  }
  console.log("HOME_STATIC_LOBBY_4K_IMAGE PASS " + JSON.stringify(homeReadiness));
  // Exercise every native Home stake hitbox and verify the live value reported
  // by the actual image view after each tap. Restore 500 Ar for the final capture.
  for (const amount of [1000, 2000, 5000, 10000, 20000, 500]) {
    selectAndAssertHomeStake(amount);
  }
  const homeShot=runAllowError("screenshot","--session",session,"--out","ramus-home-screen.jpg");
  if (!homeShot.ok) throw new Error("Could not capture the static Home reference image");
  console.log("HOME_STATIC_IMAGE_SCREENSHOT_CAPTURED");

  // Verify the actual player entry path, not only the login-screen offline
  // shortcut above. Home requests landscape; the gameplay surface must inherit it.
  modeTree = openModePickerFromHome();
  tapTreeNode(modeTree, "MODE_OFFLINE");
  waitStable(700);
  offlineMenuTree = readTree();
  for (const expected of ["👤 2 joueurs", "👥 3 joueurs", "👥 4 joueurs"]) {
    if (!treeHas(offlineMenuTree, expected)) {
      throw new Error(`Landscape offline menu is missing ${expected}. Accessibility tree: ` + JSON.stringify(treeLabels(offlineMenuTree)).slice(0, 5000));
    }
  }
  tapTreeNode(offlineMenuTree, "👥 4 joueurs");
  const landscapeBoard = waitForSnapshot("LudoMada 3D Ludo board", 8000) ?? readTree();
  if (!treeHas(landscapeBoard, "LudoMada 3D Ludo board")) {
    throw new Error("Home → JOUER → Hors-ligne did not open the 4-player gameplay board: " + JSON.stringify(treeLabels(landscapeBoard)).slice(0, 5000));
  }
  assertTurn("Joueur 1/4");
  assertRuntimeGlbLoaded(18000);
  assertBoardDrawBatches(6000);
  assertHeroPawnBatches(5000);
  const landscapeReadyTree = readTree();
  const landscapeViewport = parseBoardViewport(landscapeReadyTree);
  if (!landscapeViewport) {
    throw new Error("Landscape 3D board did not publish measurable BOARD_VIEWPORT dimensions: " + JSON.stringify(treeLabels(landscapeReadyTree).slice(0, 30)));
  }
  if (landscapeViewport.width <= landscapeViewport.height || landscapeViewport.height < 140 ||
      landscapeViewport.width / landscapeViewport.height > 8) {
    throw new Error("Landscape 3D board surface is too short or has an invalid aspect ratio: " + JSON.stringify(landscapeViewport));
  }
  console.log(`LANDSCAPE BOARD VIEWPORT PASS ${landscapeViewport.width}x${landscapeViewport.height}`);
  const landscapeShot = runAllowError("screenshot", "--session", session, "--out", "ramus-landscape-gameplay-screen.jpg");
  if (!landscapeShot.ok) throw new Error("Could not capture gameplay from the real landscape Home entry path");
  const landscapeDimensions = readJpegDimensions("ramus-landscape-gameplay-screen.jpg");
  if (!landscapeDimensions || landscapeDimensions.width <= landscapeDimensions.height) {
    throw new Error("Home gameplay must remain landscape; screenshot dimensions=" + JSON.stringify(landscapeDimensions));
  }
  console.log(`LANDSCAPE GAMEPLAY SCREENSHOT PASS ${landscapeDimensions.width}x${landscapeDimensions.height}`);
  console.log("HOME→JOUER→OFFLINE→4-PLAYER LANDSCAPE GAMEPLAY PASS");

  let landscapeGotSix = false;
  for (let i = 0; i < 36; i++) {
    target = findRef("Lancer le dé");
    run("tap", target.ref, "--gen", String(target.gen), "--session", session);
    const six = waitForSix(2200);
    const diceStatus = pendingDiceValue().status;
    console.log("RAMUS_LANDSCAPE_DICE_ROLL_ATTEMPT=" + (i + 1) + " STATUS=" + diceStatus.slice(0, 100));
    if (six) {
      landscapeGotSix = true;
      break;
    }
    runAllowError("wait", "--stable", "--timeout", "1800", "--session", session);
  }
  if (!landscapeGotSix) throw new Error("Landscape 4-player flow did not roll a six within 36 attempts: " + pendingDiceValue().status);
  console.log("LANDSCAPE DICE SIX PASS");

  target = findRef("Lancer le dé");
  const landscapeReroll = runAllowError("tap", target.ref, "--gen", String(target.gen), "--session", session);
  const landscapeRerollBlocked = runAllowError(
    "wait", "--text", "Jouez d'abord le pion", "--timeout", "1800", "--session", session
  );
  const landscapeSixPreserved = runAllowError("snapshot", "--session", session, "--find", "Dé: 6");
  if (!landscapeReroll.ok || !landscapeRerollBlocked.ok || !landscapeSixPreserved.ok || !landscapeSixPreserved.value.elements?.length) {
    throw new Error("Landscape pending six was not protected against a second roll: " + JSON.stringify({reroll:landscapeReroll.value,blocked:landscapeRerollBlocked.value,preserved:landscapeSixPreserved.value}));
  }
  console.log("LANDSCAPE PENDING DICE REROLL BLOCK PASS");
  if (!moveAnyLegalPawnAfterRoll()) throw new Error("Landscape pending six had no legal 3D pawn target");
  const landscapeReplay = runAllowError("wait", "--text", "Rejouez", "--timeout", "2500", "--session", session);
  if (!landscapeReplay.ok) throw new Error("Landscape six-to-pawn movement did not preserve the replay");
  console.log("LANDSCAPE SIX→3D PAWN TAP→REPLAY PASS");

  // Capture renderer telemetry when Ramus exposes the Android app log stream.
  // GPU execution time remains explicitly unmeasured by the current renderer.
  const rendererLogs = runAllowError("logcat", "--limit", "500", "--filter", "LudoMadaRender", "--session", session);
  const rendererLines = normalizeLogcatRows(rendererLogs).map(entry => entry.line);
  const perfLines = rendererLines.filter(line => line.includes("PERF_FRAME_STATS"));
  const phaseLines = rendererLines.filter(line => line.includes("PERF_PIPELINE_PHASES"));
  const sceneBreakdownLines = rendererLines.filter(line => line.includes("PERF_SCENE_BREAKDOWN"));
  const sceneReadbacks = rendererLines.filter(line => line.includes("PBR_READBACK label=scene_fbo"));
  if (sceneReadbacks.length) {
    const lastReadback = sceneReadbacks[sceneReadbacks.length - 1];
    const match = lastReadback.match(/size=(\d+)x(\d+).*glError=0x([0-9a-f]+)/i);
    if (match) {
      const width = Number(match[1]);
      const height = Number(match[2]);
      const glError = Number.parseInt(match[3], 16);
      console.log(`RAMUS_LANDSCAPE_GL_READBACK ${width}x${height} glError=0x${glError.toString(16)}`);
      if (width <= height || glError !== 0) {
        throw new Error("Landscape gameplay GL readback has invalid orientation or GL error: " + lastReadback);
      }
    }
  } else {
    console.log("RAMUS_RENDERER_LOGCAT_UNAVAILABLE trial mode did not expose app-tag readback rows");
  }
  if (perfLines.length) {
    const latestPerf = perfLines[perfLines.length - 1];
    const perfSurface = latestPerf.match(/surface=(\d+)x(\d+)/);
    if (perfSurface) {
      const perfWidth = Number(perfSurface[1]);
      const perfHeight = Number(perfSurface[2]);
      console.log(`RAMUS_RENDER_SURFACE ${perfWidth}x${perfHeight}`);
      if (perfWidth <= perfHeight || perfHeight < 140 || perfWidth / perfHeight > 8) {
        throw new Error("Renderer telemetry reports a collapsed landscape surface: " + latestPerf.slice(0, 1200));
      }
    }
    for (const line of perfLines.slice(-3)) console.log("RAMUS_RENDER_PERF " + line.slice(0, 1200));
  } else {
    console.log("RAMUS_PERF_METRICS_UNAVAILABLE no PERF_FRAME_STATS rows exposed by Ramus logcat");
  }
  if (phaseLines.length) {
    for (const line of phaseLines.slice(-3)) console.log("RAMUS_RENDER_PHASES " + line.slice(0, 1600));
  } else {
    console.log("RAMUS_RENDER_PHASES_UNAVAILABLE no PERF_PIPELINE_PHASES rows exposed by Ramus logcat");
  }
  if (sceneBreakdownLines.length) {
    // Keep per-group CPU timings separate from the overall pipeline phases.
    // These are CPU submission timings, not GPU execution measurements.
    for (const line of sceneBreakdownLines.slice(-3)) {
      console.log("RAMUS_RENDER_SCENE_BREAKDOWN " + line.slice(0, 2000));
    }
  } else {
    console.log("RAMUS_RENDER_SCENE_BREAKDOWN_UNAVAILABLE no PERF_SCENE_BREAKDOWN rows exposed by Ramus logcat");
  }

  success = true;
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
