import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, mkdir, rm } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { runCommand } from "../lib/agent-operations.mjs";

test("runCommand executes an allowlisted program with separate argv", async t => {
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
