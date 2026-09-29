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
  const execute = (argv, renewed = false) => {
    const isSnapshot=argv[0]==="snapshot";
    const isSessionStart=argv[0]==="session" && argv[1]==="start";
    const timeoutMs=isSnapshot ? 10000 : (isSessionStart ? 180000 : (argv[0]==="session" ? 60000 : 30000));
    const timeoutBin="/data/data/com.termux/files/usr/bin/timeout";
    const timeoutSec=Math.max(1,Math.ceil(timeoutMs/1000));
    let r;
    let out = "";
    let err = "";
    for (let attempt = 1; attempt <= 3; attempt++) {
      r = spawnSync(timeoutBin, ["-k", "2s", String(timeoutSec)+"s", process.execPath, cli, ...argv], {
        encoding: "utf8",
        stdio: ["ignore", "pipe", "pipe"],
      });
      out = (r.stdout || "").trim();
      err = (r.stderr || "").trim();
      const transientNetwork = /fetch failed|unreachable|ECONNRESET|ETIMEDOUT|ENOTFOUND|EAI_AGAIN|network/i.test(out+"\n"+err);
      if (!transientNetwork || attempt === 3) break;
      console.log(`Ramus transient network error; retry ${attempt + 1}/3`);
      spawnSync("/data/data/com.termux/files/usr/bin/sleep", ["2"], {stdio: "ignore"});
    }
    const authFailed = /no[_ ]api[_ ]key|api key.*expired|trial.*expired/i.test(out+"\n"+err);
    if (authFailed && !renewed && !process.env.RAMUS_API_KEY && argv[0] !== "trial") {
      const renewedResult = execute(["trial", "--force"], true);
      if (renewedResult.ok) return execute(argv, true);
    }
    if (!out) {
      return {
        ok: false,
        value: { error: "no_output", message: err || `ramus ${argv.join(" ")} produced no output` },
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
  };
  return execute(args);
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
  // Recover any stale/active Ramus sessions before creating a fresh smoke session.
  const preflight = runAllowError("session", "list");
  if (preflight.ok && Array.isArray(preflight.value?.sessions)) {
    for (const s of preflight.value.sessions) {
      const sid = s?.session || s?.id;
      if (sid && (s?.status === "active" || s?.state === "active")) {
        runAllowError("session", "end", "--session", sid);
      }
    }
  }
  if (!process.env.RAMUS_API_KEY) run("trial", "--force");
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

  // Music control: target the exact ON/OFF accessibility labels and verify a real state change.
  const onLabel="MUSIC_TOGGLE: activée";
  const offLabel="MUSIC_TOGGLE: désactivée";
  let music=runAllowError("snapshot","--session",session,"--find",onLabel);
  let beforeLabel=onLabel;
  let afterLabel=offLabel;
  if(!music.ok || !music.value.elements?.length){
    music=runAllowError("snapshot","--session",session,"--find",offLabel);
    beforeLabel=offLabel;
    afterLabel=onLabel;
  }
  let musicToggled=false;
  if(music.ok && music.value.elements?.length){
    const mb=music.value.elements.find(e=>e.clickable!==false) || music.value.elements[0];
    run("tap",mb.ref,"--gen",String(music.value.gen),"--session",session);
    runAllowError("wait","--stable","--timeout","1000","--session",session);
    const afterShot=runAllowError("snapshot","--session",session,"--find",afterLabel);
    musicToggled=afterShot.ok && afterShot.value.elements?.length>0;
    if(musicToggled){
      const after=afterShot.value.elements.find(e=>e.clickable!==false) || afterShot.value.elements[0];
      run("tap",after.ref,"--gen",String(afterShot.value.gen),"--session",session);
      runAllowError("wait","--stable","--timeout","500","--session",session);
      console.log(`LOBBY MUSIC STATE PASS: ${beforeLabel} → ${afterLabel}`);
    }
  }
  if(!musicToggled) throw new Error("Lobby music control could not be toggled");
  console.log("LOBBY MUSIC TOGGLE PASS");
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
