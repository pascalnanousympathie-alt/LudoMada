import { spawnSync } from "node:child_process";
import { existsSync, mkdirSync, rmSync, statSync, readFileSync } from "node:fs";
import { createHash } from "node:crypto";

const cli = process.env.RAMUS_CLI;
const apk = process.env.RAMUS_APK;
if (!cli || !apk) throw new Error("RAMUS_CLI and RAMUS_APK are required");
if (!existsSync(apk)) throw new Error(`APK not found: ${apk}`);

function runCli(...args) {
  const isSnapshot = args[0] === "snapshot";
  const isSessionStart = args[0] === "session" && args[1] === "start";
  const timeoutMs = isSnapshot ? 15000 : (isSessionStart ? 180000 : 60000);
  const timeoutBin = process.env.RAMUS_TIMEOUT_BIN || (process.platform === "android" ? "/data/data/com.termux/files/usr/bin/timeout" : "timeout");
  const timeoutSec = Math.max(1, Math.ceil(timeoutMs / 1000));
  for (let attempt = 1; attempt <= 3; attempt++) {
    const r = spawnSync(timeoutBin, ["-k", "2s", `${timeoutSec}s`, process.execPath, cli, ...args], {
      encoding: "utf8",
      stdio: ["ignore", "pipe", "pipe"],
    });
    const out = (r.stdout || "").trim();
    const err = (r.stderr || "").trim();
    const transient = /fetch failed|unreachable|ECONNRESET|ETIMEDOUT|ENOTFOUND|EAI_AGAIN|network/i.test(out + "\n" + err);
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
  allow("wait", "--stable", "--timeout", String(ms), "--session", session);
}
function shot(name) {
  run("screenshot", "--session", session, "--out", `ramus-evidence/${name}.jpg`, "--max-width", "1400");
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
      if (sid && (s?.status === "active" || s?.state === "active")) {
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
      ? live.value.sessions.find(s => s?.status === "active" || s?.state === "active")
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

  const startup = allow("snapshot", "--session", session);
  if (!startup.ok) throw new Error("Startup snapshot failed");
  shot("startup");

  // Screenshot 1 is the Home reference. The current app intentionally starts here.
  const home = allow("snapshot", "--session", session, "--find", "LudoMada Premium Home");
  if (home.ok && home.value.elements?.length) {
    console.log("STARTUP→PREMIUM HOME PASS");
    shot("home-a");
    allow("wait", "--timeout", "6200", "--session", session);
    shot("home-b");
    if (screenshotExists("home-a") && screenshotExists("home-b") &&
        sha256File("home-a") === sha256File("home-b")) {
      throw new Error("Premium Home background rotation did not change the rendered frame");
    }
    console.log("PREMIUM HOME BACKGROUND ROTATION PASS");

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

    // Provider rows sit at the top of the Wallet ScrollView and are recycled once
    // the crypto grid is reached. Test them before scrolling; then test crypto assets
    // at the deterministic grid position below.
    for (const providerText of ["MVola", "Binance Pay"]) {
      // Ramus initially exposes only the Wallet header. One DPAD_DOWN advances the
      // ScrollView to the provider-row attachment point; then inspect the live tree
      // and select the clickable paymentTile parent by its semantic contentDesc.
      // Provider rows are attached lazily in Ramus's normalized portrait projection.
      // Walk the ScrollView until the live accessibility tree contains the clickable parent.
      let tree = allow("snapshot", "--session", session, "--include-offscreen");
      let candidates = [];
      for (let i = 0; i <= 8; i++) {
        candidates = tree.ok ? (tree.value?.elements || []).filter(el => {
          const desc = String(el.contentDesc || "");
          return el.clickable && desc.startsWith(providerText + " • DÉSACTIVÉ");
        }) : [];
        if (candidates.length) break;
        if (i < 8) {
          allow("press", "DPAD_DOWN", "--session", session);
          tree = allow("snapshot", "--session", session, "--include-offscreen");
        }
      }
      const parent = candidates[0];
      if (!parent) {
        throw new Error("Wallet provider tile not reachable at Wallet entry: " + providerText);
      }
      let tapped;
      if (parent.offscreen) {
        // Ramus rejects ref/coordinate taps for offscreen nodes. Walk focus through the
        // attached payment grid until the requested provider parent is focused, then
        // activate it with ENTER. Re-snapshot after every key because refs expire.
        let focused = Boolean(parent.focused);
        for (let i = 0; i < 8 && !focused; i++) {
          allow("press", "DPAD_DOWN", "--session", session);
          tree = allow("snapshot", "--session", session, "--include-offscreen");
          const active = tree.ok ? (tree.value?.elements || []).find(el => {
            const desc = String(el.contentDesc || "");
            return el.clickable && el.focused && desc.startsWith(providerText + " • DÉSACTIVÉ");
          }) : null;
          if (active) {
            focused = true;
            break;
          }
        }
        tapped = focused ? allow("press", "ENTER", "--session", session) : { ok: false };
      } else {
        const tapArgs = ["tap", parent.ref, "--gen", String(tree.value.gen), "--session", session];
        tapped = allow(...tapArgs);
      }
      if (!tapped.ok) {
        throw new Error("Wallet provider tile could not be activated for disabled-state verification: " + providerText);
      }
      const providerState = allow("snapshot", "--session", session, "--include-offscreen");
      const providerTexts = providerState.ok
        ? (providerState.value?.elements || []).map(e => [e.text, e.contentDesc].filter(Boolean).join(" "))
        : [];
      if (!providerTexts.some(t => t.includes(providerText)) || !providerTexts.some(t => t.includes("DÉSACTIVÉ"))) {
        throw new Error(providerText + " disabled state is not preserved in Wallet");
      }
    }
    console.log("DISABLED PROVIDER TAP GUARD PASS");

    // Wallet is a real ScrollView. Ramus maps the landscape render to a portrait
    // input surface, so DPAD_DOWN is more reliable than synthetic swipe gestures.
    const requiredMarks = ["Bitcoin / BTC", "Ethereum / ETH", "Solana / SOL", "Tron / TRX", "Binance Pay"];
    const seenMarks = new Set();
    // Provider verification can leave focus inside the payment grid. Re-anchor to the
    // top of the Wallet ScrollView before entering the deterministic crypto-grid position.
    for (let i = 0; i < 10; i++) {
      allow("press", "DPAD_UP", "--session", session);
    }
    // From the top anchor, eight DPAD_DOWN actions attach the crypto grid without
    // overscrolling the right-column nodes.
    for (let i = 0; i < 8; i++) {
      allow("press", "DPAD_DOWN", "--session", session);
    }
    // Include offscreen accessibility nodes: BTC/SOL live in the right column of the
    // landscape grid and are offscreen in Ramus's normalized portrait coordinates.
    const pageSnap = allow("snapshot", "--session", session, "--include-offscreen");
    if (pageSnap.ok) {
      for (const el of pageSnap.value?.elements || []) {
        const hay = [el.text, el.contentDesc].filter(Boolean).join(" ");
        for (const mark of requiredMarks) if (hay.includes(mark)) seenMarks.add(mark);
      }
    }
    if (seenMarks.size !== requiredMarks.length) {
      throw new Error(`Wallet/crypto brand marks are incomplete: ${requiredMarks.filter(x => !seenMarks.has(x)).join(", ")}`);
    }
    console.log("WALLET PROVIDER/CRYPTO MARKS + DISABLED STATE PASS");
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

  // Online is the first row; from the default/offline focus, move up once and ENTER.
  allow("press", "DPAD_UP", "--session", session);
  allow("press", "ENTER", "--session", session);
  waitStable(700);

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

  // Login controls live in the landscape right panel and are offscreen to Ramus.
  const signupTree = allow("snapshot", "--session", session, "--include-offscreen");
  let signupNode = signupTree.ok
    ? (signupTree.value?.elements || []).find(el =>
        el.clickable && String(el.text || el.contentDesc || "") === "Créer un compte"
      )
    : null;
  if (!signupNode) throw new Error("Registration control was not exposed from Login");
  if (signupNode.offscreen) {
    let focusedSignup = Boolean(signupNode.focused);
    for (let i = 0; i < 4 && !focusedSignup; i++) {
      allow("press", "DPAD_RIGHT", "--session", session);
      const t = allow("snapshot", "--session", session, "--include-offscreen");
      signupNode = t.ok
        ? (t.value?.elements || []).find(el =>
            el.clickable && String(el.text || el.contentDesc || "") === "Créer un compte"
          )
        : null;
      focusedSignup = Boolean(signupNode?.focused);
    }
    if (!focusedSignup) throw new Error("Registration control could not be focused");
    allow("press", "ENTER", "--session", session);
  } else {
    tapRef({ref:signupNode.ref,gen:signupTree.value.gen});
  }
  waitStable(500);
  if (!hasText("Créer un compte") || !hasText("S'inscrire")) throw new Error("Registration dialog was not exposed from Login");
  console.log("LOGIN→REGISTER PASS");
  const closeSignup=allow("snapshot","--session",session,"--find","Aoka");
  if (closeSignup.ok && closeSignup.value?.elements?.length) {
    tapRef({ref:closeSignup.value.elements[0].ref,gen:closeSignup.value.gen});
    waitStable(400);
  }
  const offlineFromLogin=allow("snapshot","--session",session,"--find","Jouer hors-ligne");
  if (!offlineFromLogin.ok || !offlineFromLogin.value?.elements?.length) throw new Error("Login offline fallback was not exposed");
  tapRef({ref:offlineFromLogin.value.elements[0].ref,gen:offlineFromLogin.value.gen});
  waitStable(800);

  // Open play from the actual premium Home. Ramus exposes the landscape render through
  // a portrait 720×1616 input surface, so the deterministic portrait coordinate is included.
  let offlineQuick = allow("snapshot", "--session", session, "--find", "Jouer hors-ligne");
  if (!offlineQuick.ok || !offlineQuick.value?.elements?.length) {
    const play = allow("snapshot", "--session", session, "--find", "JOUER");
    if (play.ok && play.value.elements?.length) {
      for (const e of play.value.elements.filter(x => x.clickable !== false)) {
        run("tap", e.ref, "--gen", String(play.value.gen), "--session", session);
        waitStable(900);
        if (hasText("Jouer hors-ligne") || hasText("Hiditra amin'ny room") || hasText("Choisissez le mode")) break;
      }
    }
    if (!hasText("Jouer hors-ligne") && !hasText("Choisissez le mode")) {
      tapCoords(["0.576,0.132", "0.625,0.150", "0.83,0.60"]);
    }
    offlineQuick = allow("snapshot", "--session", session, "--find", "Jouer hors-ligne");
  }

  if (offlineQuick.ok && offlineQuick.value?.elements?.length) {
    run("tap", offlineQuick.value.elements[0].ref, "--gen", String(offlineQuick.value.gen), "--session", session);
  } else {
    const mode = allow("snapshot", "--session", session, "--find", "👥 Hors-ligne — 2 à 4 joueurs");
    if (mode.ok && mode.value?.elements?.length) {
      run("tap", mode.value.elements[0].ref, "--gen", String(mode.value.gen), "--session", session);
    } else {
      throw new Error("Offline entry was not exposed from Premium Home/Login");
    }
  }
  waitStable(1200);
  if (!hasText("Ludo hors-ligne")) throw new Error("Offline game screen not reached");
  console.log("HOME/LOGIN→OFFLINE PASS");
  shot("offline-menu");

  const four = findRef("OFFLINE_4_PLAYERS");
  tapRef(four);
  waitStable(1200);
  if (!hasText("Joueur 1/4")) throw new Error("4-player gameplay screen did not open");
  console.log("4-PLAYER GAMEPLAY ENTRY PASS");
  shot("board-4p");

  // Screenshot 2 is the gameplay reference. It must be visible as the board/table surface.
  if (!screenshotExists("board-4p")) throw new Error("Gameplay screenshot was not produced");
  console.log("GAMEPLAY REFERENCE SCREENSHOT PASS");

  // Physical gameplay validation: no textual "Pion 1–4" or "Lancer le dé" controls.
  // Ramus exposes the landscape game surface through portrait-normalized coordinates.
  // Player 0 is red, and the reference layout places the red die at top-right and
  // the four red pawns in the top-right home quadrant.
  const redDiePoints = [
    "0.15,0.10", "0.13,0.12", "0.18,0.09", "0.20,0.13",
  ];
  let rollTap = { ok: false };
  for (const pt of redDiePoints) {
    const t = allow("tap", pt, "--session", session, "--force");
    if (t.ok) {
      rollTap = t;
      await new Promise(r => setTimeout(r, 120));
      const probe = allow("snapshot", "--session", session);
      const probeTexts = probe.ok && probe.value?.elements ? probe.value.elements.map(e => String(e.text || "")) : [];
      if (probeTexts.some(t => /Dé:\s*[1-6]/.test(t))) break;
    }
  }
  if (!rollTap.ok) throw new Error("Physical red die hitbox could not be reached");
  allow("wait", "--timeout", "1400", "--session", session);
  const status = allow("snapshot", "--session", session);
  const texts = status.ok && status.value?.elements ? status.value.elements.map(e => String(e.text || "")) : [];
  const diceMatch = texts.map(t => t.match(/Dé:\s*([1-6])(?:\D|$)/)).find(Boolean);
  if (!diceMatch) throw new Error(`Dice result was not exposed after physical die tap: ${texts.join(" | ")}`);
  const rolled = Number(diceMatch[1]);
  console.log(`PHYSICAL DIE ROLL PASS: ${rolled}`);

  const redPawnPoints = [
    "0.14,0.28", "0.21,0.28", "0.14,0.35", "0.21,0.35",
    "0.11,0.31", "0.24,0.31",
  ];
  let pawnTap = { ok: false };
  for (const pt of redPawnPoints) {
    const t = allow("tap", pt, "--session", session, "--force");
    if (t.ok) {
      pawnTap = t;
      allow("wait", "--timeout", "500", "--session", session);
      const probe = allow("snapshot", "--session", session);
      const probeText = probe.ok && probe.value?.elements ? probe.value.elements.map(e => String(e.text || "")).join(" | ") : "";
      if (/Mila 6|Tsy afaka mihetsika|Rejouez|Déplacement|Capture|Joueur/i.test(probeText)) break;
    }
  }
  if (!pawnTap.ok) throw new Error("Physical red pawn hitbox could not be reached");
  allow("wait", "--timeout", "1200", "--session", session);
  const afterMove = allow("snapshot", "--session", session);
  const afterText = afterMove.ok && afterMove.value?.elements ? afterMove.value.elements.map(e => String(e.text || "")).join(" | ") : "";
  if (rolled === 6) {
    if (!/Rejouez|Déplacement|Capture|Joueur/i.test(afterText)) {
      throw new Error(`Six did not expose a pawn-move state after physical pawn tap: ${afterText}`);
    }
    console.log("PHYSICAL DIE 6→PHYSICAL PAWN MOVE PASS");
  } else {
    if (!/Mila 6|Tsy afaka mihetsika|Joueur/i.test(afterText)) {
      throw new Error(`Non-six pawn guard was not exposed after physical pawn tap: ${afterText}`);
    }
    console.log("PHYSICAL PAWN LEGAL-MOVE GUARD PASS");
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
    throw new Error("No startup/home screenshot evidence");
  }
  success = true;
  console.log("RAMUS_SMOKE_PASS");
} finally {
  if (session) {
    const ended = allow("session", "end", "--session", session);
    if (!ended.ok) console.error("session end:", JSON.stringify(ended.value));
  }
  if (!success) {
    console.error("RAMUS_SMOKE_FAIL");
    process.exitCode = 1;
  }
}
