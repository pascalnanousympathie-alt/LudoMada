import test from "node:test";
import assert from "node:assert/strict";
import {
  createRendererTelemetryAccumulator,
  REQUIRED_HERO_PARTS
} from "./ramus-log-telemetry.mjs";

const scene = "PBR_READBACK label=scene_fbo size=650x1011 brightPixels=369149/657150 maxChannel=255 meanRGB=30,28,28 centerRGB=37,31,11 glError=0x0";
const surface = "PBR_READBACK label=surface_backbuffer size=650x1011 brightPixels=657149/657150 maxChannel=234 meanRGB=97,94,93 centerRGB=127,114,57 glError=0x0";

function parseReadback(line, label) {
  const validReadback = typeof line === "string" &&
    new RegExp("PBR_READBACK label=" + label + "\\s+size=\\d+x\\d+\\s+brightPixels=\\d+/\\d+\\s+maxChannel=\\d+").test(line);
  return validReadback
    ? { ok: true, label }
    : { ok: false, reason: "missing_or_malformed_readback" };
}

function completeTelemetry() {
  return createRendererTelemetryAccumulator(REQUIRED_HERO_PARTS);
}

test("retains startup and GLB markers when later logcat snapshots rotate them out", () => {
  const telemetry = completeTelemetry();
  telemetry.observe({
    totalLines: 500,
    filteredLines: 6,
    lines: [
      "I/LudoMadaRender: Renderer=GLES3_PBR",
      "I/LudoMadaRender: PBR_SURFACE_CREATED",
      "I/LudoMadaRender: GLB_RUNTIME_LOAD_OK parts=" + REQUIRED_HERO_PARTS.join(","),
      "I/LudoMadaRender: BOARD_GLB_RUNTIME_LOAD_OK parts=95"
    ]
  });
  telemetry.observe({
    totalLines: 500,
    filteredLines: 2,
    lines: [
      scene,
      surface,
      "I/LudoMadaRender: PBR_FIRST_FRAME_COMPLETE"
    ]
  });

  const result = telemetry.snapshot(parseReadback);
  assert.equal(result.checks.gles3Pbr, true);
  assert.equal(result.checks.pbrSurfaceCreated, true);
  assert.deepEqual(result.checks.missingHeroParts, []);
  assert.equal(result.checks.boardParts, 95);
  assert.equal(result.checks.sceneReadback.ok, true);
  assert.equal(result.checks.surfaceReadback.ok, true);
  assert.equal(result.checks.firstFrameComplete, true);
  assert.equal(result.evidence.snapshotCount, 2);
  assert.equal(result.evidence.totalLinesRead, 1000);
});

test("combines markers that arrive in separate polling snapshots", () => {
  const telemetry = completeTelemetry();
  telemetry.observe({ lines: ["I/LudoMadaRender: Renderer=GLES3_PBR"] });
  telemetry.observe({ lines: ["I/LudoMadaRender: PBR_SURFACE_CREATED"] });
  telemetry.observe({
    lines: [
      "I/LudoMadaRender: GLB_RUNTIME_LOAD_OK parts=" + REQUIRED_HERO_PARTS.join(","),
      "I/LudoMadaRender: BOARD_GLB_RUNTIME_LOAD_OK parts=95",
      scene,
      surface,
      "I/LudoMadaRender: PBR_FIRST_FRAME_COMPLETE"
    ]
  });

  const result = telemetry.snapshot(parseReadback);
  assert.equal(result.checks.gles3Pbr && result.checks.pbrSurfaceCreated, true);
  assert.equal(result.checks.missingHeroParts.length, 0);
  assert.equal(result.checks.boardParts, 95);
  assert.equal(result.checks.sceneReadback.ok && result.checks.surfaceReadback.ok, true);
  assert.equal(result.checks.firstFrameComplete, true);
});

test("does not pass when any required hero part is missing", () => {
  const telemetry = completeTelemetry();
  telemetry.observe({
    lines: [
      "I/LudoMadaRender: Renderer=GLES3_PBR",
      "I/LudoMadaRender: PBR_SURFACE_CREATED",
      "I/LudoMadaRender: GLB_RUNTIME_LOAD_OK parts=" + REQUIRED_HERO_PARTS.slice(1).join(","),
      "I/LudoMadaRender: BOARD_GLB_RUNTIME_LOAD_OK parts=95",
      scene,
      surface,
      "I/LudoMadaRender: PBR_FIRST_FRAME_COMPLETE"
    ]
  });

  const result = telemetry.snapshot(parseReadback);
  assert.deepEqual(result.checks.missingHeroParts, [REQUIRED_HERO_PARTS[0]]);
});

test("does not infer successful GLB loading from failure markers", () => {
  const telemetry = completeTelemetry();
  telemetry.observe({
    lines: [
      "I/LudoMadaRender: GLB_RUNTIME_LOAD_FAILED java.lang.IllegalStateException",
      "I/LudoMadaRender: BOARD_GLB_RUNTIME_LOAD_FAILED java.lang.IllegalStateException"
    ]
  });

  const result = telemetry.snapshot(parseReadback);
  assert.equal(result.evidence.heroLoadFailureSeen, true);
  assert.equal(result.evidence.boardLoadFailureSeen, true);
  assert.equal(result.checks.heroParts.length, 0);
  assert.equal(result.checks.boardParts, 0);
});

test("uses the latest framebuffer readback, so a later bad sample cannot inherit an earlier pass", () => {
  const telemetry = completeTelemetry();
  telemetry.observe({ lines: [scene] });
  telemetry.observe({ lines: ["PBR_READBACK label=scene_fbo malformed"] });

  const result = telemetry.snapshot(parseReadback);
  assert.equal(result.checks.sceneReadback.ok, false);
});
