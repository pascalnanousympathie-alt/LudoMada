import { readFileSync } from "node:fs";
import assert from "node:assert/strict";

const workflowPath = new URL("../.github/workflows/release-gate.yml", import.meta.url);
const workflow = readFileSync(workflowPath, "utf8");
const lines = workflow.split(/\r?\n/);
const androidJobIndex = lines.findIndex((line) => line === "  android-final:");
assert.notEqual(androidJobIndex, -1, "android-final job must exist");
const jobEnd = lines.findIndex((line, index) => index > androidJobIndex && /^  [a-zA-Z0-9_-]+:$/.test(line));
const androidJob = lines.slice(androidJobIndex, jobEnd === -1 ? lines.length : jobEnd);
const stepsIndex = androidJob.findIndex((line) => line === "    steps:");
assert.notEqual(stepsIndex, -1, "android-final steps must exist");
const jobEnvironment = androidJob.slice(0, stepsIndex).join("\n");
assert.doesNotMatch(jobEnvironment, /ANDROID_KEYSTORE_(?:BASE64|PASSWORD)|ANDROID_KEY_ALIAS|ANDROID_KEY_PASSWORD/,
  "Android signing secrets must never be job-scoped");

assert.match(workflow, /sign_release:[\s\S]*?default: false[\s\S]*?type: boolean/,
  "manual signing input must default to false");
const approvalGate = /github\.event_name == 'workflow_dispatch' && github\.ref == 'refs\/heads\/main' && inputs\.sign_release/;
const gatedSteps = [
  "Prepare release signing (manual main-branch request only)",
  "Build signed release APK (manual main-branch request only)",
  "Verify signed release APK",
  "Upload signed release APK (manual main-branch request only)"
];
for (const name of gatedSteps) {
  const start = lines.findIndex((line) => line.trim() === `- name: ${name}`);
  assert.notEqual(start, -1, `missing signing boundary step: ${name}`);
  const next = lines.findIndex((line, index) => index > start && line.startsWith("      - name:"));
  const block = lines.slice(start, next === -1 ? lines.length : next).join("\n");
  assert.match(block, approvalGate, `${name} must require manual workflow_dispatch on main with sign_release=true`);
}


const unsignedBuild = lines.findIndex((line) => line.trim() === "- name: Build unsigned release APK (standard CI)");
assert.notEqual(unsignedBuild, -1, "standard CI must build an unsigned release APK");
const nextStep = lines.findIndex((line, index) => index > unsignedBuild && line.startsWith("      - name:"));
const unsignedBlock = lines.slice(unsignedBuild, nextStep === -1 ? lines.length : nextStep).join("\n");
assert.match(unsignedBlock, /github\.event_name != 'workflow_dispatch' \|\| github\.ref != 'refs\/heads\/main' \|\| !inputs\.sign_release/,
  "unsigned release build must cover standard CI runs");
assert.doesNotMatch(unsignedBlock, /ANDROID_KEYSTORE_|LUDOMADA_SIGNING_STORE_PASSWORD|LUDOMADA_SIGNING_KEY_PASSWORD/,
  "unsigned release build must never receive signing credentials");

for (let index = 0; index < lines.length; index++) {
  if (!/\$\{\{\s*secrets\.ANDROID_(?:KEYSTORE_BASE64|KEYSTORE_PASSWORD|KEY_ALIAS|KEY_PASSWORD)\s*\}\}/.test(lines[index])) continue;
  const priorStep = lines.slice(0, index).filter((line) => line.startsWith("      - name:")).at(-1) ?? "";
  assert.match(priorStep,
    /Prepare release signing|Build signed release APK/,
    `signing secret reference outside a dedicated signing step at line ${index + 1}`);
}

console.log("RELEASE_SIGNING_BOUNDARY_PASS: standard CI builds unsigned; Android signing secrets are step-scoped and require explicit manual main dispatch");
