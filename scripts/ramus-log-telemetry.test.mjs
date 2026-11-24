import test from "node:test";
import assert from "node:assert/strict";
import {
  createRendererTelemetryAccumulator,
  hasCompleteFramePacingReport,
  REQUIRED_HERO_PARTS
} from "./ramus-log-telemetry.mjs";

const scene = "PBR_READBACK label=scene_fbo size=650x1011 brightPixels=369149/657150 maxChannel=255 meanRGB=30,28,28 centerRGB=37,31,11 glError=0x0";
const surface = "PBR_READBACK label=surface_backbuffer size=650x1011 brightPixels=657149/657150 maxChannel=234 meanRGB=97,94,93 centerRGB=127,114,57 glError=0x0";

const READBACK_PATTERNS = Object.freeze({
  scene_fbo: /PBR_READBACK label=scene_fbo\s+size=\d+x\d+\s+brightPixels=\d+\/\d+\s+maxChannel=\d+/,
  surface_backbuffer: /PBR_READBACK label=surface_backbuffer\s+size=\d+x\d+\s+brightPixels=\d+\/\d+\s+maxChannel=\d+/
});

function parseReadback(line, label) {
  const pattern = READBACK_PATTERNS[label];
  const validReadback = typeof line === "string" && pattern instanceof RegExp && pattern.test(line);
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

test("rejects unknown framebuffer labels without constructing a dynamic regular expression", () => {
  assert.deepEqual(parseReadback(scene, "unexpected_label"), {
    ok: false,
    reason: "missing_or_malformed_readback"
  });
});

test("preserves the Ramus display refresh-rate marker even without a complete frame window", () => {
  const telemetry = completeTelemetry();
  telemetry.observe({
    lines: ["I/LudoMadaRender: PBR_DISPLAY_REFRESH_HZ=60.0"]
  });
  const result = telemetry.snapshot(parseReadback);
  assert.deepEqual(result.evidence.displayRefreshLines, ["PBR_DISPLAY_REFRESH_HZ=60.0"]);
  assert.deepEqual(result.evidence.framePacingLines, []);
});

test("preserves renderer frame-pacing summaries for the Ramus smoke report", () => {
  const telemetry = completeTelemetry();
  telemetry.observe({
    lines: [
      "I/LudoMadaRender: PBR_FRAME_PACING samples=120 spanMs=1000.00 refreshHz=120.00 avgFps=120.00 p50FrameMs=8.33 p95FrameMs=8.33 p99FrameMs=8.34 maxFrameMs=8.34 glWorkP95Ms=3.20 over120Budget=2/60 over60Budget=0/60"
    ]
  });
  const result = telemetry.snapshot(parseReadback);
  assert.equal(result.evidence.framePacingLines.length, 1);
  assert.match(result.evidence.framePacingLines[0], /avgFps=120.00/);
});

test("uses the latest framebuffer readback, so a later bad sample cannot inherit an earlier pass", () => {
  const telemetry = completeTelemetry();
  telemetry.observe({ lines: [scene] });
  telemetry.observe({ lines: ["PBR_READBACK label=scene_fbo malformed"] });

  const result = telemetry.snapshot(parseReadback);
  assert.equal(result.checks.sceneReadback.ok, false);
});

test("preserves UI Choreographer VSync diagnostics separately from GL frame pacing", () => {
  const telemetry = completeTelemetry();
  telemetry.observe({
    lines: [
      "I/LudoMadaRender: PBR_UI_VSYNC samples=120 refreshHz=60.00 avgFps=60.00 avgFrameMs=16.67 p95FrameMs=16.70 maxFrameMs=20.00"
    ]
  });
  const result = telemetry.snapshot(parseReadback);
  assert.equal(result.evidence.uiVsyncLines.length, 1);
  assert.match(result.evidence.uiVsyncLines[0], /avgFps=60.00/);
  assert.deepEqual(result.evidence.framePacingLines, []);
});

test("retains UI VSync probe started progress and incomplete markers", () => {
  const telemetry = completeTelemetry();
  telemetry.observe({
    lines: [
      "I/LudoMadaRender: PBR_UI_VSYNC_PROBE_STARTED thread=main main=true refreshHz=60.0",
      "I/LudoMadaRender: PBR_UI_VSYNC_PROGRESS intervals=30 callbacks=31 elapsedMs=500",
      "I/LudoMadaRender: PBR_UI_VSYNC_PROBE_ENDED status=INCOMPLETE intervals=30 callbacks=31 elapsedMs=80000"
    ]
  });
  const result = telemetry.snapshot(parseReadback);
  assert.equal(result.evidence.uiVsyncLines.length, 3);
  assert.match(result.evidence.uiVsyncLines[0], /PROBE_STARTED/);
  assert.match(result.evidence.uiVsyncLines[1], /intervals=30/);
  assert.match(result.evidence.uiVsyncLines[2], /status=INCOMPLETE/);
});

test("requires at least 120 real frame intervals before reporting a complete capture", () => {
  assert.equal(hasCompleteFramePacingReport([
    "I/LudoMadaRender: PBR_FRAME_PACING samples=90 spanMs=21000.00 avgFps=4.28"
  ]), false);
  assert.equal(hasCompleteFramePacingReport([
    "I/LudoMadaRender: PBR_FRAME_PACING samples=120 spanMs=28159.29 avgFps=4.26"
  ]), true);
  assert.equal(hasCompleteFramePacingReport([
    "I/LudoMadaRender: PBR_FRAME_PACING samples=119 spanMs=28000.00"
  ]), false);
  assert.equal(hasCompleteFramePacingReport([
    "I/LudoMadaRender: PBR_FRAME_PACING samples=not-a-number"
  ]), false);
});

test("retains bounded progress checkpoints before the final frame-pacing report", () => {
  const telemetry = completeTelemetry();
  telemetry.observe({
    lines: [
      "I/LudoMadaRender: PBR_FRAME_PACING_PROGRESS samples=30 activeSpanMs=6801.53",
      "I/LudoMadaRender: PBR_FRAME_PACING_PROGRESS samples=60 activeSpanMs=13612.23"
    ]
  });
  const result = telemetry.snapshot(parseReadback);
  assert.equal(result.evidence.framePacingProgressLines.length, 2);
  assert.match(result.evidence.framePacingProgressLines[1], /samples=60/);
});


test("preserves EGL surface, GL device, and scene-target diagnostics across snapshots", () => {
  const telemetry = completeTelemetry();
  const surface = "I/LudoMadaRender: PBR_SURFACE_DIMENSIONS egl=650x1011 view=720x1600 measured=720x1600 displayPx=720x1600 density=2.0";
  const device = "I/LudoMadaRender: PBR_GL_DEVICE vendor=fixture renderer=fixture-gles version=fixture-gl maxTexture=4096";
  const target = "I/LudoMadaRender: PBR_SCENE_TARGET_DIMENSIONS size=650x1011 framebufferStatus=0x8cd5 glError=0x0";

  telemetry.observe({ lines: [surface] });
  telemetry.observe({ lines: [device, target] });

  const evidence = telemetry.snapshot(parseReadback).evidence;
  assert.deepEqual(evidence.surfaceDimensionLines, [surface.slice(surface.indexOf("PBR_SURFACE_DIMENSIONS egl="))]);
  assert.deepEqual(evidence.glDeviceLines, [device.slice(device.indexOf("PBR_GL_DEVICE vendor="))]);
  assert.deepEqual(evidence.sceneTargetDimensionLines, [target.slice(target.indexOf("PBR_SCENE_TARGET_DIMENSIONS size="))]);
});
