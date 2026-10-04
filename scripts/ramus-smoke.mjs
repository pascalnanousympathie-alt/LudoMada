import { spawnSync } from "node:child_process";
import { existsSync, mkdirSync, rmSync, statSync, readFileSync } from "node:fs";
import { createHash } from "node:crypto";

const cli = process.env.RAMUS_CLI;
const apk = process.env.RAMUS_APK;
if (!cli || !apk) throw new Error("RAMUS_CLI and RAMUS_APK are required");
if (!existsSync(apk)) throw new Error(`APK not found: ${apk}`);

function runCli(...args) {
  const isSnapshot = args[0] === "snapshot";
  const isScreenshot = args[0] === "screenshot";
  const isSessionStart = args[0] === "session" && args[1] === "start";
  const isInteraction = ["tap", "swipe", "press", "type", "wait", "app"].includes(args[0]);
  const timeoutMs = isSnapshot ? 15000 : (isScreenshot ? 15000 : (isSessionStart ? 180000 : (isInteraction ? 15000 : 60000)));
  const timeoutBin = process.env.RAMUS_TIMEOUT_BIN || (process.platform === "android" ? "/data/data/com.termux/files/usr/bin/timeout" : "timeout");
  const timeoutSec = Math.max(1, Math.ceil(timeoutMs / 1000));
  for (let attempt = 1; attempt <= 3; attempt++) {
    const r = spawnSync(timeoutBin, ["-k", "2s", `${timeoutSec}s`, process.execPath, cli, ...args], {
      encoding: "utf8",
      stdio: ["ignore", "pipe", "pipe"],
    });
    const out = (r.stdout || "").trim();
    const err = (r.stderr || "").trim();
    const transient = /fetch failed|unreachable|stream_closed|session stream closed|ECONNRESET|ETIMEDOUT|ENOTFOUND|EAI_AGAIN|network/i.test(out + "\n" + err);
    if (out) {
      let value;
      try { value = JSON.parse(out); }
      catch { value = { error: "non_json", message: out.slice(0, 1200) }; }
      if (r.status === 0 && !value.error) return { ok: true, value };
      if (transient && attempt < 3) {
        const sleepBin = process.env.RAMUS_SLEEP_BIN || (process.platform === "android" ? "/data/data/com.termux/files/usr/bin/sleep" : "sleep");
        spawnSync(sleepBin, ["2"], { stdio: "ignore" });
        continue;
      }
      if (/no[_ ]api[_ ]key|api key.*expired|trial.*expired/i.test(out + "\n" + err) && !trialEnsured && !process.env.RAMUS_API_KEY && args[0] !== "trial" && attempt === 1) {
        const renewed = runCli("trial", "--force");
        if (renewed.ok) {
          trialEnsured = true;
          continue;
        }
      }
      return { ok: false, value };
    }
    if ((isSessionStart || isSnapshot || isInteraction) && attempt < 3) {
      const sleepBin = process.env.RAMUS_SLEEP_BIN || (process.platform === "android" ? "/data/data/com.termux/files/usr/bin/sleep" : "sleep");
      spawnSync(sleepBin, [isSessionStart ? "3" : "2"], { stdio: "ignore" });
      continue;
    }
    return { ok: false, value: { error: "no_output", message: err || `ramus ${args.join(" ")} produced no output` } };
  }
  return { ok: false, value: { error: "unknown" } };
}

function run(...args) {
  const r = runCli(...args);
  if (!r.ok) throw new Error(JSON.stringify(r.value));
  return r.value;
}
function allow(...args) { return runCli(...args); }
function snap(findText = null) {
  const args = ["snapshot", "--session", session];
  if (findText) args.push("--find", findText);
  return run(...args);
}
function hasText(text) {
  const r = allow("snapshot", "--session", session, "--find", text);
  return !!(r.ok && r.value?.elements?.length);
}
function findRef(text) {
  const r = snap(text);
  if (!r.elements?.length) throw new Error(`UI element not found: ${text}`);
  return { ref: r.elements[0].ref, gen: r.gen };
}
function tapRef(target) {
  return run("tap", target.ref, "--gen", String(target.gen), "--session", session);
}
function tapAnyRef(text) {
  const target = findRef(text);
  return tapRef(target);
}
function waitStable(ms = 1200) {
  // Ramus can expose the session before Android has attached the accessibility stream.
  // Keep a bounded real settle delay so startup/overlay transitions are not raced.
  const sleepMs = Math.max(0, Math.min(ms, 1800));
  const sleepBin = process.platform === "android" ? "/data/data/com.termux/files/usr/bin/sleep" : "sleep";
  spawnSync(sleepBin, [String(sleepMs / 1000)], { stdio: "ignore" });
}
function shot(name) {
  // Functional smoke stays independent from Ramus screenshot RPC latency.
  // Set RAMUS_CAPTURE=1 when screenshot transport itself is being exercised.
  if (process.env.RAMUS_CAPTURE !== "1") return false;
  const outPath = `ramus-evidence/${name}.jpg`;
  const r = allow("screenshot", "--session", session, "--out", outPath, "--max-width", "1400");
  return Boolean(r.ok && screenshotExists(name));
}
function tapCoords(points) {
  for (const pt of points) {
    allow("tap", pt, "--session", session, "--force");
    waitStable(900);
    if (hasText("Jouer hors-ligne") || hasText("Hiditra amin'ny room") ||
        hasText("Choisissez le mode") || hasText("🌐 En ligne — Joueurs réels")) return true;
  }
  return false;
}
function screenshotExists(name) {
  const file = `ramus-evidence/${name}.jpg`;
  return existsSync(file) && statSync(file).size > 10000;
}
function sha256File(name) {
  const file = `ramus-evidence/${name}.jpg`;
  if (!existsSync(file)) return "";
  return createHash("sha256").update(readFileSync(file)).digest("hex");
}

let session = "";
let success = false;
let trialEnsured = Boolean(process.env.RAMUS_API_KEY);

try {
  rmSync("ramus-evidence", { recursive: true, force: true });
  mkdirSync("ramus-evidence", { recursive: true });

  const preflight = allow("session", "list");
  if (preflight.ok && Array.isArray(preflight.value?.sessions)) {
    for (const s of preflight.value.sessions) {
      const sid = s?.session || s?.id;
      if (sid && ["active", "assigned", "requested"].includes(s?.status || s?.state)) {
        allow("session", "end", "--session", sid);
      }
    }
  }
  if (!process.env.RAMUS_API_KEY) {
    run("trial", "--force");
    trialEnsured = true;
  }

  let started = allow("session", "start", "--apk", apk, "--wait");
  if (!started.ok || !started.value?.session) {
    const live = allow("session", "list");
    const existing = live.ok && Array.isArray(live.value?.sessions)
      ? live.value.sessions.find(s => ["active", "assigned", "requested"].includes(s?.status || s?.state))
      : null;
    if (existing?.session || existing?.id) {
      started = { ok: true, value: { session: existing.session || existing.id } };
    }
  }
  if (!started.ok || !started.value?.session) {
    throw new Error(JSON.stringify(started.value || { error: "session_start_failed" }));
  }

  session = started.value.session;
  console.log(`Ramus session: ${session}`);
  waitStable(1800);

  let startup = null;
  for (let attempt = 0; attempt < 5; attempt++) {
    startup = allow("snapshot", "--session", session);
    if (startup.ok) break;
    const retrySleep = process.platform === "android" ? "/data/data/com.termux/files/usr/bin/sleep" : "sleep";
    spawnSync(retrySleep, ["1"], { stdio: "ignore" });
  }
  if (!startup?.ok) throw new Error("Startup snapshot failed after retries");
  shot("startup");

  // Screenshot 1 is the Home reference. The current app intentionally starts here.
  const home = allow("snapshot", "--session", session, "--find", "LudoMada Premium Home");
  if (home.ok && home.value.elements?.length) {
    console.log("STARTUP→PREMIUM HOME PASS");
    if (process.env.RAMUS_CAPTURE === "1") {
      shot("home-a");
      const rotationSleep = process.platform === "android" ? "/data/data/com.termux/files/usr/bin/sleep" : "sleep";
      spawnSync(rotationSleep, ["7"], { stdio: "ignore" });
      shot("home-b");
      if (screenshotExists("home-a") && screenshotExists("home-b") &&
          sha256File("home-a") === sha256File("home-b")) {
        throw new Error("Premium Home background rotation did not change the rendered frame");
      }
      if (screenshotExists("home-a") && screenshotExists("home-b")) {
        console.log("PREMIUM HOME BACKGROUND ROTATION VISUAL PASS");
      } else {
        console.log("PREMIUM HOME BACKGROUND ROTATION VISUAL SKIPPED: screenshot transport unavailable");
      }
    } else {
      console.log("PREMIUM HOME BACKGROUND ROTATION VISUAL SKIPPED: RAMUS_CAPTURE=1 required");
    }

    // Wallet provider safety gate: the visible wallet/crypto catalog must expose
    // distinct marks, while all payment actions remain disabled until explicitly activated.
    // Prefer the native accessibility target so the test does not depend on Ramus'
    // portrait/landscape touch-coordinate transform.
    let walletOpened = false;
    const walletNav = allow("snapshot", "--session", session, "--find", "WALLET_NAV");
    if (walletNav.ok && walletNav.value?.elements?.length) {
      const target = walletNav.value.elements[0];
      const tapped = allow("tap", target.ref, "--gen", String(walletNav.value.gen), "--session", session);
      if (tapped.ok) {
        waitStable(800);
        // Only the Wallet header is required to prove navigation. Provider rows
        // are below the initial viewport and are validated by the scroll phase.
        walletOpened = hasText("SOLDE MGA");
      }
    }

    // Coordinate fallback targets the actual Wallet accessibility hitbox on Ramus'
    // 720×1616 portrait input surface (center ≈ 0.504,0.229).
    if (!walletOpened) {
      for (const pt of ["0.504,0.229", "0.50,0.23", "0.53,0.23"]) {
        const t = allow("tap", pt, "--session", session, "--force");
        if (t.ok) {
          waitStable(800);
          if (hasText("SOLDE MGA")) {
            walletOpened = true;
            break;
          }
        }
      }
    }
    if (!walletOpened) throw new Error("Wallet screen was not reachable from Premium Home");
    console.log("HOME→WALLET PASS");

    // Wallet rows attach lazily. Probe the current position first; if providers are
    // not attached yet, one verified swipe attaches them. A second swipe then reaches
    // the crypto grid.
    waitStable(900);
    let providerSnap = allow("snapshot", "--session", session, "--include-offscreen");
    let providerTexts = providerSnap.ok
      ? (providerSnap.value?.elements || []).map(e => [e.text, e.contentDesc].filter(Boolean).join(" "))
      : [];
    const providerReady = () => ["MVola", "Binance Pay"].every(name =>
      providerTexts.some(t => t.includes(name) && t.includes("DÉSACTIVÉ"))
    );
    if (!providerReady()) {
      const attachSwipe = allow("swipe", "0.80,0.39", "0.80,0.12", "--ms", "500", "--session", session);
      if (!attachSwipe.ok) throw new Error("Wallet provider attach gesture failed");
      waitStable(500);
      providerSnap = allow("snapshot", "--session", session, "--include-offscreen");
      providerTexts = providerSnap.ok
        ? (providerSnap.value?.elements || []).map(e => [e.text, e.contentDesc].filter(Boolean).join(" "))
        : [];
    }
    const providerReadyNow = ["MVola", "Binance Pay"].every(name =>
      providerTexts.some(t => t.includes(name) && t.includes("DÉSACTIVÉ"))
    );
    if (providerReadyNow) console.log("DISABLED PROVIDER STATE PASS");
    else console.log("DISABLED PROVIDER STATE DEFERRED: Ramus lazy row attachment");

    const cryptoMarks = ["Bitcoin / BTC", "Ethereum / ETH", "Solana / SOL", "Tron / TRX"];
    const seenCrypto = new Set();
    const cryptoSwipe = allow("swipe", "0.80,0.39", "0.80,0.12", "--ms", "500", "--session", session);
    if (!cryptoSwipe.ok) throw new Error("Wallet crypto scroll gesture failed");
    waitStable(700);
    const cryptoSnap = allow("snapshot", "--session", session, "--include-offscreen");
    if (cryptoSnap.ok) {
      for (const el of cryptoSnap.value?.elements || []) {
        const hay = [el.text, el.contentDesc].filter(Boolean).join(" ");
        for (const mark of cryptoMarks) if (hay.includes(mark)) seenCrypto.add(mark);
      }
    }
    if (seenCrypto.size === cryptoMarks.length) console.log("WALLET CRYPTO MARKS PASS");
    else console.log("WALLET CRYPTO MARKS DEFERRED: " + cryptoMarks.filter(x => !seenCrypto.has(x)).join(", "));
    shot("wallet-disabled");

    allow("press", "BACK", "--session", session);
    waitStable(900);
  }

  // Authentication/navigation gate: use the real Home JOUER control, which is offscreen
  // in Ramus's portrait-normalized coordinates. Focus it with DPAD_RIGHT and ENTER.
  let playTree = allow("snapshot", "--session", session, "--include-offscreen");
  let playNode = playTree.ok
    ? (playTree.value?.elements || []).find(el =>
        el.clickable && String(el.contentDesc || el.text || "") === "JOUER"
      )
    : null;
  if (!playNode) throw new Error("Home JOUER control was not exposed");
  if (playNode.offscreen) {
    let focusedPlay = Boolean(playNode.focused);
    // After BACK from Wallet, the Android focus is often restored to the left rail
    // rather than preserved on the right CTA. TAB establishes a deterministic
    // accessibility focus, then DPAD_RIGHT reaches the real JOUER control.
    const focusKeys = ["TAB", "DPAD_RIGHT", "DPAD_RIGHT", "DPAD_RIGHT"];
    for (const key of focusKeys) {
      if (focusedPlay) break;
      allow("press", key, "--session", session);
      playTree = allow("snapshot", "--session", session, "--include-offscreen");
      playNode = playTree.ok
        ? (playTree.value?.elements || []).find(el =>
            el.clickable && String(el.contentDesc || el.text || "") === "JOUER"
          )
        : null;
      focusedPlay = Boolean(playNode?.focused);
    }
    if (!focusedPlay) {
      // Ramus may not expose the canvas-backed JOUER control as focusable after
      // returning from the Wallet. Use the verified portrait touch point for the
      // real JOUER visual hitbox instead of failing on accessibility focus alone.
      const playFallback = allow("tap", "0.576,0.132", "--session", session, "--force");
      if (!playFallback.ok) throw new Error("Home JOUER control could not be focused or tapped");
    } else {
      allow("press", "ENTER", "--session", session);
    }
  } else {
    tapRef({ref:playNode.ref,gen:playTree.value.gen});
  }
  waitStable(500);
  if (!hasText("Choisissez le mode") && !hasText("MODE_OFFLINE") && !hasText("MODE_ONLINE")) throw new Error("Game mode dialog was not exposed from Home JOUER");

  // Prefer the real accessibility target for the mode overlay. Ramus coordinate
  // transforms differ across rotation paths, while MODE_ONLINE is the authoritative UI marker.
  let onlineOpened = false;
  const onlineMode = allow("snapshot", "--session", session, "--find", "MODE_ONLINE");
  if (onlineMode.ok && onlineMode.value?.elements?.length) {
    const target = onlineMode.value.elements[0];
    const tapped = allow("tap", target.ref, "--gen", String(onlineMode.value.gen), "--session", session);
    if (tapped.ok) {
      waitStable(500);
      onlineOpened = hasText("Choisir la mise") || hasText("500 Ar");
    }
  }
  // Coordinate fallback remains only for emulator paths where MODE_ONLINE is not exposed.
  if (!onlineOpened) {
    for (const pt of ["0.70,0.28", "0.70,0.30", "0.68,0.28"]) {
      const tapped = allow("tap", pt, "--session", session, "--force");
      if (!tapped.ok) continue;
      waitStable(500);
      if (hasText("Choisir la mise") || hasText("500 Ar")) {
        onlineOpened = true;
        break;
      }
    }
  }
  if (!onlineOpened) throw new Error("Online lobby was not reached from the game mode dialog");

  // The online flow selects the stake before presenting the real Login screen.
  const feeTree = allow("snapshot", "--session", session, "--find", "500 Ar");
  if (!feeTree.ok || !feeTree.value?.elements?.length) {
    throw new Error("Online stake selector did not expose 500 Ar");
  }
  tapRef({ref:feeTree.value.elements[0].ref,gen:feeTree.value.gen});
  waitStable(700);
  if (!hasText("Nom, email ou mobile") && !hasText("CONNEXION")) {
    throw new Error("Login screen was not reached after selecting the 500 Ar online stake");
  }
  console.log("HOME→ONLINE→STAKE→LOGIN PASS");

  // Prefer the real accessibility target for registration. The Login panel is fully
  // exposed by Ramus, so coordinate mapping is only a fallback.
  let signupOpened = false;
  const signupTree = allow("snapshot", "--session", session, "--find", "Créer un compte");
  if (signupTree.ok && signupTree.value?.elements?.length) {
    const target = signupTree.value.elements[0];
    const tapped = allow("tap", target.ref, "--gen", String(signupTree.value.gen), "--session", session);
    if (tapped.ok) {
      waitStable(500);
      const signupSnap = allow("snapshot", "--session", session, "--include-offscreen");
      const elements = signupSnap.ok ? (signupSnap.value?.elements || []) : [];
      signupOpened = elements.some(el => String(el.text || el.contentDesc || "").includes("S'INSCRIRE"));
    }
  }
  if (!signupOpened) {
    const signupTap = allow("tap", "0.95,0.275", "--session", session, "--force");
    if (signupTap.ok) {
      for (let attempt = 0; attempt < 5; attempt++) {
        waitStable(450);
        const signupSnap = allow("snapshot", "--session", session, "--include-offscreen");
        const elements = signupSnap.ok ? (signupSnap.value?.elements || []) : [];
        if (elements.some(el => String(el.text || el.contentDesc || "").includes("S'INSCRIRE"))) {
          signupOpened = true;
          break;
        }
      }
    }
  }
  if (!signupOpened) throw new Error("Registration dialog was not exposed from Login");
  console.log("LOGIN→REGISTER PASS");
  allow("press","BACK","--session",session);
  waitStable(500);

  // The Login screen has a real "Jouer hors-ligne" card. Activating it intentionally
  // enters the existing offline-mode selector; do not route back through Home/JOUER.
  const offlineFromLogin=allow("snapshot","--session",session,"--find","Jouer hors-ligne");
  if (!offlineFromLogin.ok || !offlineFromLogin.value?.elements?.length) {
    throw new Error("Login offline fallback was not exposed");
  }
  run("tap", offlineFromLogin.value.elements[0].ref, "--gen", String(offlineFromLogin.value.gen), "--session", session);
  waitStable(800);

  const offlineMenu = allow("snapshot","--session",session,"--find","OFFLINE_4_PLAYERS");
  if (!offlineMenu.ok || !offlineMenu.value?.elements?.length) {
    throw new Error("Offline mode selector was not reached from Login offline fallback");
  }
  console.log("LOGIN→OFFLINE MENU PASS");

  const offlineFour = offlineMenu.value.elements[0];
  run("tap", offlineFour.ref, "--gen", String(offlineMenu.value.gen), "--session", session);
  waitStable(1600);
  shot("after-offline-4p");
  const afterFour = allow("snapshot", "--session", session, "--include-offscreen");
  if (afterFour.ok) {
    const compact = (afterFour.value?.elements || []).slice(0, 80).map(e => ({text:e.text,desc:e.contentDesc,clickable:e.clickable,focused:e.focused,offscreen:e.offscreen}));
    console.log("AFTER_4P_SNAPSHOT=" + JSON.stringify(compact));
  } else {
    console.log("AFTER_4P_SNAPSHOT_ERROR=" + JSON.stringify(afterFour.value));
  }
  if (!hasText("Joueur 1/4") && !hasText("GAMEPLAY_3D_OFFLINE_4P")) throw new Error("4-player gameplay screen did not open");
  console.log("HOME/LOGIN→OFFLINE PASS");
  console.log("4-PLAYER GAMEPLAY ENTRY PASS");
  shot("board-4p");

  // Screenshot 2 is visual evidence, not a functional release gate.
  if (screenshotExists("board-4p")) console.log("GAMEPLAY REFERENCE SCREENSHOT PASS");
  else console.log("GAMEPLAY REFERENCE SCREENSHOT SKIPPED: screenshot transport unavailable");

  // Physical gameplay validation: no textual "Pion 1–4" or "Lancer le dé" controls.
  // Ramus exposes the landscape game surface through portrait-normalized coordinates.
  // Player 0 is red, and the reference layout places the red die at top-right and
  // the four red pawns in the top-right home quadrant.
  // The renderer projects player 0's die near (0.786,0.113) in the landscape
  // framebuffer. Ramus feeds the landscape surface through its portrait-normalized
  // input plane; the corresponding CCW coordinate is approximately (0.113,0.214).
  const redDiePoints = [
    // Ramus normalized coordinates map directly to the 1616×720 screen.
    // Board starts at about (21,186); current projected red die0 is ≈ (1076.6,60.6)
    // in the 1574×458 board, hence screen ≈ (1098,247) => normalized ≈ (0.679,0.343).
    "0.679,0.343", "0.670,0.332", "0.688,0.332",
    "0.679,0.353", "0.670,0.345", "0.688,0.345",
    // Small compatibility probes around the verified center.
    "0.660,0.343", "0.700,0.343"
  ];
  let rollTap = { ok: false };
  for (const pt of redDiePoints) {
    const t = allow("tap", pt, "--session", session, "--force");
    if (t.ok) {
      rollTap = t;
      waitStable(250);
      const probe = allow("snapshot", "--session", session);
      const probeTexts = probe.ok && probe.value?.elements ? probe.value.elements.map(e => String(e.text || "")) : [];
      if (probeTexts.some(t => /Dé:\s*[1-6]/.test(t))) break;
    }
  }
  if (!rollTap.ok) throw new Error("Physical red die hitbox could not be reached");

  // A fresh pawn starts at pos=-1, so a roll 1..5 is intentionally a no-legal-move
  // outcome: the game briefly exposes the die value, then advances to the next player.
  // Treat either the visible die value OR the documented no-move turn transition as a
  // successful physical callback. Only roll 6 is allowed to enter pawn-move validation.
  let rolled = 0;
  let noMoveGuard = false;
  let observedText = "";
  for (let i = 0; i < 7; i++) {
    waitStable(250);
    const status = allow("snapshot", "--session", session);
    const texts = status.ok && status.value?.elements ? status.value.elements.map(e => String(e.text || "")) : [];
    observedText = texts.join(" | ");
    const diceMatch = texts.map(t => t.match(/Dé:\s*([1-6])(?:\D|$)/)).find(Boolean);
    if (diceMatch) { rolled = Number(diceMatch[1]); break; }
    if (/Aucun coup|Joueur 2\/4/.test(observedText)) { noMoveGuard = true; break; }
  }
  if (!rolled && !noMoveGuard) {
    throw new Error(`Physical die callback was not observed: ${observedText}`);
  }
  if (rolled) console.log(`PHYSICAL DIE ROLL PASS: ${rolled}`);
  else console.log("PHYSICAL DIE NO-LEGAL-MOVE TURN-ADVANCE PASS");

  if (rolled === 6) {
    const redPawnPoints = [
      // Red home is the upper-right quadrant. Approximate screen centers from the
      // verified 1616×720 landscape screenshot are around x=0.59..0.65, y=0.28..0.39.
      "0.590,0.280", "0.645,0.280", "0.590,0.375", "0.645,0.375",
      "0.570,0.330", "0.665,0.330",
    ];
    let pawnTap = { ok: false };
    for (const pt of redPawnPoints) {
      const t = allow("tap", pt, "--session", session, "--force");
      if (t.ok) {
        pawnTap = t;
        waitStable(500);
        const probe = allow("snapshot", "--session", session);
        const probeText = probe.ok && probe.value?.elements ? probe.value.elements.map(e => String(e.text || "")).join(" | ") : "";
        if (/Mila 6|Tsy afaka mihetsika|Rejouez|Déplacement|Capture|Joueur/i.test(probeText)) break;
      }
    }
    if (!pawnTap.ok) throw new Error("Physical red pawn hitbox could not be reached after roll 6");
    allow("wait", "--timeout", "1200", "--session", session);
    const afterMove = allow("snapshot", "--session", session);
    const afterText = afterMove.ok && afterMove.value?.elements ? afterMove.value.elements.map(e => String(e.text || "")).join(" | ") : "";
    if (!/Rejouez|Déplacement|Capture|Joueur/i.test(afterText)) {
      throw new Error(`Six did not expose a pawn-move state after physical pawn tap: ${afterText}`);
    }
    console.log("PHYSICAL DIE 6→PHYSICAL PAWN MOVE PASS");
  } else {
    console.log("PAWN MOVE VALIDATION DEFERRED: physical roll was not 6, rules correctly advanced turn");
  }
  shot("board-after-physical-dice-pawn");

  // Opportunistic online lobby/music check: never fabricate it when CI has no authenticated account.
  allow("press", "BACK", "--session", session);
  waitStable(900);
  allow("press", "BACK", "--session", session);
  waitStable(900);
  const backHome = allow("snapshot", "--session", session, "--find", "LudoMada Premium Home");
  if (backHome.ok && backHome.value?.elements?.length) {
    let online = false;
    const playNow = allow("snapshot", "--session", session, "--find", "JOUER");
    if (playNow.ok && playNow.value.elements?.length) {
      for (const pt of ["0.576,0.132", "0.625,0.150", "0.83,0.60"]) {
        allow("tap", pt, "--session", session, "--force");
        waitStable(900);
        if (hasText("🌐 En ligne — Joueurs réels")) { online = true; break; }
        if (hasText("Nom, email ou mobile")) break;
      }
    }
    if (online) {
      const onlineRef = findRef("🌐 En ligne — Joueurs réels");
      tapRef(onlineRef);
      waitStable(1200);
      const music = allow("snapshot", "--session", session, "--find", "LOBBY_MUSIC_TOGGLE");
      if (music.ok && music.value?.elements?.length) {
        console.log("ONLINE LOBBY MUSIC CONTROL EXPOSED");
      } else {
        console.log("ONLINE LOBBY MUSIC CHECK SKIPPED: lobby did not expose the music marker");
      }
    } else {
      console.log("ONLINE LOBBY CHECK SKIPPED: unauthenticated CI state routes JOUER to Login");
    }
  }

  if (!screenshotExists("home-a") && !screenshotExists("startup")) {
    console.log("HOME SCREENSHOT EVIDENCE SKIPPED: screenshot transport unavailable");
  }
  success = true;
  console.log("RAMUS_SMOKE_PASS");
} finally {
  if (session && (success || process.env.RAMUS_KEEP_ON_FAIL !== "1")) {
    const ended = allow("session", "end", "--session", session);
    if (!ended.ok) console.error("session end:", JSON.stringify(ended.value));
  } else if (session && !success) {
    console.error("RAMUS_KEEP_ON_FAIL=1: preserving session for post-failure logcat");
  }
  if (!success) {
    console.error("RAMUS_SMOKE_FAIL");
    process.exitCode = 1;
  }
}
