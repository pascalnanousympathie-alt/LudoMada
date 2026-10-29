import { existsSync } from "node:fs";
import { spawnSync } from "node:child_process";

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
  const r = spawnSync(process.execPath, [cli, ...args], {
    encoding: "utf8",
    stdio: ["ignore", "pipe", "pipe"],
  });
  const out = (r.stdout || "").trim();
  const err = (r.stderr || "").trim();
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

function assertMenuButton(text) {
  const result = runAllowError("snapshot", "--session", session, "--find", text);
  if (!result.ok || !result.value.elements?.some((e) => e.text === text)) {
    throw new Error(`Expected offline menu button: ${text}`);
  }
}

function assertTurn(text) {
  const result = runAllowError("snapshot", "--session", session, "--find", text);
  if (!result.ok || !result.value.elements?.some((e) => e.text?.startsWith(text))) {
    throw new Error(`Expected turn state: ${text}`);
  }
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

    const waited = runAllowError(
      "wait", "--text", targetTurn, "--timeout", "2200", "--session", session
    );
    if (waited.ok) return;
  }
  throw new Error(`Turn did not reach ${targetTurn} within 12 rolls`);
}

let session = "";
let success = false;
try {
  if (!process.env.RAMUS_API_KEY) run("trial");
  const started = run("session", "start", "--apk", apk, "--wait");
  session = started.session;
  console.log(`Ramus session: ${session}`);

  let target = findRef("👥 Jouer hors-ligne");
  run("tap", target.ref, "--gen", String(target.gen),
      "--expect", "Ludo hors-ligne", "--session", session);
  console.log("LOGIN→OFFLINE PASS");

  target = findRef("👤 2 joueurs");
  run("tap", target.ref, "--gen", String(target.gen), "--session", session);
  console.log("2-PLAYER MENU PASS");

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

  let gotSix = false;
  for (let i = 0; i < 24; i++) {
    target = findRef("Lancer le dé");
    run("tap", target.ref, "--gen", String(target.gen), "--session", session);

    const six = waitForSix(3000);
    if (six) {
      gotSix = true;
      break;
    }

    // A slow animation must not fail the whole smoke test. Give the board
    // a little time to settle before the next observation.
    runAllowError("wait", "--stable", "--timeout", "1800", "--session", session);
  }
  if (!gotSix) throw new Error("No six within 24 cloud-emulator rolls");
  console.log("DICE SIX PASS");

  target = findRef("Pion 1");
  run("tap", target.ref, "--gen", String(target.gen),
      "--expect", "Rejouez", "--session", session);
  console.log("SIX→PAWN→REPLAY PASS");

  run("screenshot", "--session", session, "--out", "ramus-screen.jpg");
  success = true;
  console.log("RAMUS_SMOKE_PASS");
} finally {
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
  const s = run("snapshot", "--session", session, "--find", findText);
  if (!s.elements?.length) throw new Error(`UI element not found: ${findText}`);
  return { ref: s.elements[0].ref, gen: s.gen };
}
