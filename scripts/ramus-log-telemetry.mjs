export function hasCompleteFramePacingReport(lines, minimumSamples = 120) {
  if (!Number.isSafeInteger(minimumSamples) || minimumSamples < 1) return false;
  return (Array.isArray(lines) ? lines : []).some((line) => {
    if (typeof line !== "string") return false;
    const match = line.match(/PBR_FRAME_PACING\s+samples=(\d+)\b/);
    if (!match) return false;
    const samples = Number.parseInt(match[1], 10);
    return Number.isSafeInteger(samples) && samples >= minimumSamples;
  });
}

export const REQUIRED_HERO_PARTS = Object.freeze([
  "LM_Pawn_Base_Weighted",
  "LM_Pawn_Collar",
  "LM_Pawn_Spherical_Head",
  "LM_Pawn_Tapered_Body",
  "LM_Pawn_Underside_Insert",
  "LM_Die_HydroChrome_Body"
]);

/**
 * Accumulates renderer evidence across bounded logcat snapshots.
 * Logcat is a rolling buffer: a later snapshot must not erase startup markers
 * observed in an earlier poll.
 */
export function createRendererTelemetryAccumulator(requiredHeroParts = REQUIRED_HERO_PARTS) {
  const required = [...new Set(requiredHeroParts)];
  const heroParts = new Set();
  const evidenceLines = new Set();
  let gles3Pbr = false;
  let pbrSurfaceCreated = false;
  let boardParts = 0;
  let sceneReadbackLine = null;
  let surfaceReadbackLine = null;
  let firstFrameComplete = false;
  let heroLoadFailureSeen = false;
  let boardLoadFailureSeen = false;
  let snapshotCount = 0;
  let totalLinesRead = 0;
  let filteredLinesRead = 0;
  const framePacingLines = new Set();
  const framePacingProgressLines = new Set();
  const displayRefreshLines = new Set();
  const uiVsyncLines = new Set();

  function observe(snapshot) {
    snapshotCount += 1;
    totalLinesRead += Number.isFinite(snapshot?.totalLines) ? snapshot.totalLines : 0;
    filteredLinesRead += Number.isFinite(snapshot?.filteredLines) ? snapshot.filteredLines : 0;

    for (const value of Array.isArray(snapshot?.lines) ? snapshot.lines : []) {
      if (typeof value !== "string" || value.length === 0) continue;
      evidenceLines.add(value);
      if (value.includes("PBR_FRAME_PACING ")) framePacingLines.add(value.slice(value.indexOf("PBR_FRAME_PACING ")));
      if (value.includes("PBR_FRAME_PACING_PROGRESS ")) framePacingProgressLines.add(value.slice(value.indexOf("PBR_FRAME_PACING_PROGRESS ")));
      if (value.includes("PBR_DISPLAY_REFRESH_HZ=")) displayRefreshLines.add(value.slice(value.indexOf("PBR_DISPLAY_REFRESH_HZ=")));
      if (value.includes("PBR_UI_VSYNC")) uiVsyncLines.add(value.slice(value.indexOf("PBR_UI_VSYNC")));

      if (value.includes("Renderer=GLES3_PBR")) gles3Pbr = true;
      if (value.includes("PBR_SURFACE_CREATED")) pbrSurfaceCreated = true;
      if (value.includes("PBR_FIRST_FRAME_COMPLETE")) firstFrameComplete = true;

      if (value.includes("GLB_RUNTIME_LOAD_FAILED")) heroLoadFailureSeen = true;
      if (value.includes("BOARD_GLB_RUNTIME_LOAD_FAILED")) boardLoadFailureSeen = true;

      if (value.includes("BOARD_GLB_RUNTIME_LOAD_OK parts=")) {
        const match = value.match(/BOARD_GLB_RUNTIME_LOAD_OK parts=(\d+)/);
        if (match) boardParts = Number.parseInt(match[1], 10);
      } else if (value.includes("GLB_RUNTIME_LOAD_OK parts=")) {
        const marker = "GLB_RUNTIME_LOAD_OK parts=";
        const markerIndex = value.indexOf(marker);
        const parsed = value.slice(markerIndex + marker.length)
          .split(",")
          .map((part) => part.trim())
          .filter(Boolean);
        if (parsed.length > 0) {
          heroParts.clear();
          for (const part of parsed) heroParts.add(part);
        }
      }

      if (value.includes("PBR_READBACK label=scene_fbo")) sceneReadbackLine = value;
      if (value.includes("PBR_READBACK label=surface_backbuffer")) surfaceReadbackLine = value;
    }
  }

  function snapshot(parseFramebufferReadback) {
    const currentHeroParts = [...heroParts];
    return {
      checks: {
        gles3Pbr,
        pbrSurfaceCreated,
        heroParts: currentHeroParts,
        missingHeroParts: required.filter((part) => !heroParts.has(part)),
        boardParts,
        sceneReadback: typeof parseFramebufferReadback === "function"
          ? parseFramebufferReadback(sceneReadbackLine, "scene_fbo")
          : { ok: false, reason: "readback_parser_missing" },
        surfaceReadback: typeof parseFramebufferReadback === "function"
          ? parseFramebufferReadback(surfaceReadbackLine, "surface_backbuffer")
          : { ok: false, reason: "readback_parser_missing" },
        firstFrameComplete
      },
      evidence: {
        snapshotCount,
        totalLinesRead,
        filteredLinesRead,
        distinctRendererLines: evidenceLines.size,
        heroLoadFailureSeen,
        boardLoadFailureSeen,
        framePacingLines: [...framePacingLines],
        framePacingProgressLines: [...framePacingProgressLines],
        displayRefreshLines: [...displayRefreshLines],
        uiVsyncLines: [...uiVsyncLines]
      }
    };
  }

  return { observe, snapshot };
}
