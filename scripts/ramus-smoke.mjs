import { existsSync } from "node:fs";
import { spawnSync } from "node:child_process";

const cli = process.env.RAMUS_CLI;
const apk = process.env.RAMUS_APK;
if (!cli || !apk) throw new Error("RAMUS_CLI and RAMUS_APK are required");
if (!existsSync(apk)) throw new Error(`APK not found: ${apk}`);

function run(...args) {
  const r = spawnSync(process.execPath, [cli, ...args], {
    encoding: "utf8",
    stdio: ["ignore", "pipe", "pipe"],
  });
  const out = (r.stdout || "").trim();
  const err = (r.stderr || "").trim();
  if (!out) throw new Error(`ramus ${args.join(" ")} produced no output: ${err}`);
  let value;
  try { value = JSON.parse(out); } catch {
    throw new Error(`ramus ${args.join(" ")} returned non-JSON: ${out.slice(0, 1000)}`);
  }
  if (r.status !== 0 || value.error) throw new Error(JSON.stringify(value));
  return value;
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

  let gotSix = false;
  for (let i = 0; i < 24; i++) {
    target = findRef("Lancer le dé");
    run("tap", target.ref, "--gen", String(target.gen), "--session", session);
    run("wait", "--text", "Dé:", "--timeout", "1200", "--session", session);
    const six = run("snapshot", "--session", session, "--find", "Dé: 6");
    if (six.elements?.length) { gotSix = true; break; }
    run("wait", "--stable", "--timeout", "1000", "--session", session);
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
