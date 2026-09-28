import { spawnSync } from "node:child_process";
import { existsSync, mkdirSync, rmSync } from "node:fs";

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
  const isSnapshot=args[0]==="snapshot";
  const timeoutMs=isSnapshot ? 12000 : (args[0]==="session" ? 60000 : 30000);
  const r = spawnSync(process.execPath, [cli, ...args], {
    encoding: "utf8",
    stdio: ["ignore", "pipe", "pipe"],
    timeout: timeoutMs,
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
  rmSync("ramus-evidence", { recursive: true, force: true });
  mkdirSync("ramus-evidence", { recursive: true });
  // Recover the known stale session left by the previous failed smoke run.
  runAllowError("session", "end", "--session", "11a9fbfd-7117-48af-8466-6cab1fa8132e");
  if (!process.env.RAMUS_API_KEY) run("trial");
  let startedResult = runAllowError("session", "start", "--apk", apk, "--wait");
  if (!startedResult.ok || !startedResult.value?.session) {
    // A transient no-output response can happen even when Ramus has not created a session.
    // Check server-side live sessions before retrying to avoid duplicate-session races.
    const live = runAllowError("session", "list");
    const existing = live.ok && Array.isArray(live.value?.sessions)
      ? live.value.sessions.find(s => s?.status === "active" || s?.state === "active")
      : null;
    if (existing?.session || existing?.id) {
      startedResult = { ok: true, value: { session: existing.session || existing.id } };
    } else {
      startedResult = runAllowError("session", "start", "--apk", apk, "--wait");
    }
  }
  if (!startedResult.ok || !startedResult.value?.session) {
    throw new Error(JSON.stringify(startedResult.value ?? { error: "session_start_failed" }));
  }
  session = startedResult.value.session;
  console.log(`Ramus session: ${session}`);
  runAllowError("wait", "--stable", "--timeout", "1800", "--session", session);
  run("screenshot", "--session", session, "--out", "ramus-evidence/login.jpg");

  let target = findRef("👥 Jouer hors-ligne");
  run("tap", target.ref, "--gen", String(target.gen),
      "--expect", "Ludo hors-ligne", "--session", session);
  console.log("LOGIN→OFFLINE PASS");
  runAllowError("wait", "--stable", "--timeout", "1200", "--session", session);
  run("screenshot", "--session", session, "--out", "ramus-evidence/offline-menu.jpg");

  // Offline → Home: use Android BACK so Ramus is not affected by the portrait accessibility viewport.
  run("press", "BACK", "--session", session);
  runAllowError("wait", "--stable", "--timeout", "1200", "--session", session);
  const homeMarker = runAllowError("snapshot", "--session", session, "--find", "LudoMada Premium Home");
  if (!homeMarker.ok || !homeMarker.value.elements?.length) {
    throw new Error("Premium Home was not reached after Offline → BACK");
  }
  console.log("OFFLINE→HOME PASS");
  run("screenshot", "--session", session, "--out", "ramus-evidence/home-a.jpg");
  runAllowError("wait", "--timeout", "6200", "--session", session);
  run("screenshot", "--session", session, "--out", "ramus-evidence/home-b.jpg");

  // Home is a custom Canvas with a native transparent JOUER hit-target for deterministic automation.
  // Prefer its semantic ref; keep the legacy coordinate fallback for compatibility.
  const playSemantic = runAllowError("snapshot", "--session", session, "--find", "JOUER");
  if (playSemantic.ok && playSemantic.value.elements?.length) {
    target = { ref: playSemantic.value.elements[0].ref, gen: playSemantic.value.gen };
    run("tap", target.ref, "--gen", String(target.gen), "--session", session);
  } else {
    run("tap", "0.83,0.58", "--session", session);
  }
  runAllowError("wait", "--stable", "--timeout", "1200", "--session", session);
  const onlineMode = runAllowError("snapshot", "--session", session, "--find", "🌐 En ligne — Joueurs réels");
  if (onlineMode.ok && onlineMode.value.elements?.length) {
    target = { ref: onlineMode.value.elements[0].ref, gen: onlineMode.value.gen };
    run("tap", target.ref, "--gen", String(target.gen), "--session", session);
  } else {
    // AlertDialog first item fallback when the accessibility tree omits the emoji label.
    run("tap", "0.50,0.47", "--session", session);
  }
  runAllowError("wait", "--stable", "--timeout", "1200", "--session", session);
  run("screenshot", "--session", session, "--out", "ramus-evidence/lobby.jpg");

  let musicOn = runAllowError("snapshot", "--session", session, "--find", "Musique activée");
  let musicOff = runAllowError("snapshot", "--session", session, "--find", "Musique désactivée");
  let musicToggled=false;
  // Persisted preference can leave the lobby music OFF from a previous run.
  // Accept either initial state, toggle once, then verify the opposite state.
  if (musicOn.ok && musicOn.value.elements?.length) {
    const mb = musicOn.value.elements.find(e=>e.clickable!==false) || musicOn.value.elements[0];
    run("tap", mb.ref, "--gen", String(musicOn.value.gen), "--session", session);
    runAllowError("wait", "--stable", "--timeout", "700", "--session", session);
    musicOff = runAllowError("snapshot", "--session", session, "--find", "Musique désactivée");
    musicToggled = !!(musicOff.ok && musicOff.value.elements?.length);
    if (musicToggled) {
      const mbOff = musicOff.value.elements.find(e=>e.clickable!==false) || musicOff.value.elements[0];
      run("tap", mbOff.ref, "--gen", String(musicOff.value.gen), "--session", session);
      runAllowError("wait", "--stable", "--timeout", "700", "--session", session);
      musicOn = runAllowError("snapshot", "--session", session, "--find", "Musique activée");
      musicToggled = !!(musicOn.ok && musicOn.value.elements?.length);
    }
  } else if (musicOff.ok && musicOff.value.elements?.length) {
    const mbOff = musicOff.value.elements.find(e=>e.clickable!==false) || musicOff.value.elements[0];
    run("tap", mbOff.ref, "--gen", String(musicOff.value.gen), "--session", session);
    runAllowError("wait", "--stable", "--timeout", "700", "--session", session);
    musicOn = runAllowError("snapshot", "--session", session, "--find", "Musique activée");
    musicToggled = !!(musicOn.ok && musicOn.value.elements?.length);
  } else {
    // Last-resort visual fallback for the rotated accessibility viewport.
    for (const pt of ["0.93,0.16","0.95,0.18","0.90,0.18"]) {
      run("tap", pt, "--session", session);
      runAllowError("wait", "--stable", "--timeout", "700", "--session", session);
      musicOn = runAllowError("snapshot", "--session", session, "--find", "Musique activée");
      musicOff = runAllowError("snapshot", "--session", session, "--find", "Musique désactivée");
      if ((musicOn.ok && musicOn.value.elements?.length) || (musicOff.ok && musicOff.value.elements?.length)) {
        musicToggled=true;
        break;
      }
    }
  }
  if (!musicToggled) throw new Error("Lobby music control could not be toggled in either initial state");
  console.log("LOBBY MUSIC TOGGLE PASS");
  const musicBack = runAllowError("snapshot", "--session", session, "--find", "Musique désactivée");
  if (musicBack.ok && musicBack.value.elements?.length) {
    const mbOff = musicBack.value.elements.find(e=>e.clickable!==false) || musicBack.value.elements[0];
    run("tap", mbOff.ref, "--gen", String(musicBack.value.gen), "--session", session);
  } else {
    // Visual fallback: return the control to ON for the rest of the smoke.
    run("tap", "0.93,0.16", "--session", session);
  }
  runAllowError("wait", "--stable", "--timeout", "300", "--session", session);

  // Return from the lobby using its native accessible Accueil button; Android BACK remains the fallback.
  let lobbyHome = runAllowError("snapshot", "--session", session, "--find", "Accueil");
  if (lobbyHome.ok && lobbyHome.value.elements?.length) {
    const homeTarget=lobbyHome.value.elements.find(e=>e.clickable!==false) || lobbyHome.value.elements[0];
    run("tap", homeTarget.ref, "--gen", String(lobbyHome.value.gen), "--session", session);
  } else {
    run("press", "BACK", "--session", session);
  }
  runAllowError("wait", "--stable", "--timeout", "1800", "--session", session);
  let homeAfterLobby = runAllowError("snapshot", "--session", session, "--find", "LudoMada Premium Home");
  if (!homeAfterLobby.ok || !homeAfterLobby.value.elements?.length) {
    // One deterministic retry using the native Back action.
    run("press", "BACK", "--session", session);
    runAllowError("wait", "--stable", "--timeout", "1200", "--session", session);
    homeAfterLobby = runAllowError("snapshot", "--session", session, "--find", "LudoMada Premium Home");
  }
  if (!homeAfterLobby.ok || !homeAfterLobby.value.elements?.length) {
    throw new Error("Premium Home was not reached after Lobby → Home");
  }

  // Re-enter the offline flow for the gameplay board.
  run("tap", "0.83,0.58", "--session", session);
  runAllowError("wait", "--stable", "--timeout", "900", "--session", session);
  target = findRef("👥 Hors-ligne — 2 à 4 joueurs");
  run("tap", target.ref, "--gen", String(target.gen), "--session", session);
  runAllowError("wait", "--stable", "--timeout", "900", "--session", session);
  target = findRef("👤 2 joueurs");
  run("tap", target.ref, "--gen", String(target.gen), "--session", session);
  console.log("2-PLAYER MENU PASS");

  // Verify the 4-player mode and its complete turn rotation.
  run("press", "BACK", "--session", session);
  runAllowError("wait", "--stable", "--timeout", "900", "--session", session);
  assertMenuButton("👥 3 joueurs");
  assertMenuButton("👥 4 joueurs");
  target = findRef("👥 4 joueurs");
  run("tap", target.ref, "--gen", String(target.gen), "--session", session);
  runAllowError("wait", "--stable", "--timeout", "1200", "--session", session);
  run("screenshot", "--session", session, "--out", "ramus-evidence/board-4p.jpg");
  assertTurn("Joueur 1/4");
  for (const nextTurn of ["Joueur 2/4", "Joueur 3/4", "Joueur 4/4", "Joueur 1/4"]) {
    advanceToTurn(nextTurn);
  }
  console.log("4-PLAYER TURN ROTATION PASS");

  // Return to the 2-player board for the dice/pawn validation.
  const backFrom4p = runAllowError("snapshot", "--session", session, "--find", "← Hors-ligne");
  if (backFrom4p.ok && backFrom4p.value.elements?.length) {
    run("tap", backFrom4p.value.elements[0].ref, "--gen", String(backFrom4p.value.gen), "--session", session);
  } else {
    run("tap", "0.50,0.90", "--session", session);
  }
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

  runAllowError("wait", "--stable", "--timeout", "1400", "--session", session);
  run("screenshot", "--session", session, "--out", "ramus-evidence/board-2p-after-move.jpg");
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
