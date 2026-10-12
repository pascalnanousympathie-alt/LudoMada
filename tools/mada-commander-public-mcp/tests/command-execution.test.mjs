import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, mkdir, rm } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { runCommand, runShellCommand } from "../lib/agent-operations.mjs";
import { redactSensitive } from "../lib/security.mjs";

test("runCommand executes an approved program with separate argv", async t => {
  const root = await mkdtemp(path.join(os.tmpdir(), "mada-command-"));
  t.after(() => rm(root, { recursive: true, force: true }));
  const result = await runCommand({ path: root }, "printf", ["MADA_COMMAND_OK"], ".", 10000);
  assert.equal(result.exitCode, 0);
  assert.equal(result.timedOut, false);
  assert.equal(result.stdout, "MADA_COMMAND_OK");
  assert.equal(result.cwd, ".");
  assert.equal(result.program, "printf");
});

test("runCommand rejects traversal, unknown executables and inline evaluation", async t => {
  const root = await mkdtemp(path.join(os.tmpdir(), "mada-command-"));
  t.after(() => rm(root, { recursive: true, force: true }));
  await assert.rejects(() => runCommand({ path: root }, "pwd", [], "../outside", 10000), /invalid or blocked|project-relative/i);
  await assert.rejects(() => runCommand({ path: root }, "not-approved", [], ".", 10000), /approved command list/i);
  await assert.rejects(() => runCommand({ path: root }, "env", [], ".", 10000), /approved command list/i);
  await assert.rejects(() => runCommand({ path: root }, "printenv", [], ".", 10000), /approved command list/i);
  await assert.rejects(() => runCommand({ path: root }, "node", ["-e", "process.exit(0)"], ".", 10000), /inline code-evaluation flags/i);
  await assert.rejects(() => runCommand({ path: root }, "pwd", [], ".", 50), /timeout/i);
});

test("runCommand uses a selected nested workspace directory", async t => {
  const root = await mkdtemp(path.join(os.tmpdir(), "mada-command-"));
  t.after(() => rm(root, { recursive: true, force: true }));
  await mkdir(path.join(root, "subdir"));
  const result = await runCommand({ path: root }, "pwd", [], "subdir", 10000);
  assert.equal(result.exitCode, 0);
  assert.equal(path.resolve(result.stdout.trim()), path.resolve(root, "subdir"));
});

test("sensitive values are redacted from structured and environment-style output", () => {
  const json = redactSensitive(JSON.stringify({
    database_url: "postgresql://owner:db-secret@db.example.test/app",
    api_key: "super-secret-api-value",
    client_secret: "oauth-secret-value"
  }));
  assert.doesNotMatch(json, /db-secret|super-secret-api-value|oauth-secret-value/);
  assert.match(json, /REDACTED/);
  const env = redactSensitive("MADA_COMMANDER_DATABASE_URL=postgresql://owner:db-secret@db.example.test/app\nSUPABASE_SECRET_KEY=sb_secret_12345678901234567890");
  assert.doesNotMatch(env, /db-secret|sb_secret_12345678901234567890/);
  assert.match(env, /REDACTED/);
});


test("runShellCommand supports pipes and command chaining", async t => {
  const root = await mkdtemp(path.join(os.tmpdir(), "mada-shell-"));
  t.after(() => rm(root, { recursive: true, force: true }));
  const command = "printf 'ALPHA\\nBETA\\n' | grep BETA && printf DONE";
  const result = await runShellCommand({ path: root }, command, ".", 10000);
  assert.equal(result.exitCode, 0);
  assert.equal(result.timedOut, false);
  assert.equal(result.stdout, "BETA\nDONE");
  assert.equal(result.cwd, ".");
});

test("runShellCommand rejects empty commands, traversal and excessive timeouts", async t => {
  const root = await mkdtemp(path.join(os.tmpdir(), "mada-shell-"));
  t.after(() => rm(root, { recursive: true, force: true }));
  await assert.rejects(() => runShellCommand({ path: root }, " ", ".", 10000), /non-empty/i);
  await assert.rejects(() => runShellCommand({ path: root }, "pwd", "../outside", 10000), /invalid or blocked|project-relative/i);
  await assert.rejects(() => runShellCommand({ path: root }, "pwd", ".", 900001), /timeout/i);
});
