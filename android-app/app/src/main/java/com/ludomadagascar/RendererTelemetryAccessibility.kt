package com.ludomadagascar

import java.util.Locale

/**
 * Compact, machine-readable renderer telemetry exposed via the gameplay board's
 * accessibility description. This is deliberately published only at the existing
 * five-second telemetry boundary, never on every render frame.
 */
internal object RendererTelemetryAccessibility {
    fun format(
        deviceDiagnostic: String,
        surfaceWidth: Int,
        surfaceHeight: Int,
        frame: RenderFramePerfSnapshot?,
        pipeline: RenderPipelinePhaseSnapshot?,
        scene: RenderSceneBreakdownSnapshot?
    ): String? {
        if (surfaceWidth <= 0 || surfaceHeight <= 0) return null
        if (frame == null || pipeline == null || scene == null) return null
        val fps = frame.activeFrameFps?.takeIf { it.isFinite() && it > 0f } ?: return null
        val frameP50 = frame.activeFrameIntervalP50Ms ?: return null
        val frameP95 = frame.activeFrameIntervalP95Ms ?: return null
        if (!frameP50.isFinite() || !frameP95.isFinite() || frameP50 <= 0f || frameP95 <= 0f) return null

        val vendor = deviceField(deviceDiagnostic, "vendor", "renderer")
        val renderer = deviceField(deviceDiagnostic, "renderer", "version")
        val version = deviceDiagnostic.substringAfter("version=", "").trim()
        if (vendor.isEmpty() || renderer.isEmpty() || version.isEmpty() ||
            vendor.equals("unknown", ignoreCase = true) ||
            renderer.equals("unknown", ignoreCase = true) ||
            version.equals("unknown", ignoreCase = true) ||
            vendor.equals("unavailable", ignoreCase = true) ||
            renderer.equals("unavailable", ignoreCase = true) ||
            version.equals("unavailable", ignoreCase = true)
        ) return null

        return listOf(
            "RENDER_TELEMETRY=1",
            "SURFACE=${surfaceWidth}x$surfaceHeight",
            "GPU_VENDOR=" + compact(vendor),
            "GPU_RENDERER=" + compact(renderer),
            "GLES=" + compact(version),
            "FPS=" + number(fps),
            "FRAME_MS=" + pair(frameP50, frameP95),
            "GL_CPU_MS=" + pair(frame.glSubmitP50Ms, frame.glSubmitP95Ms),
            "SCENE_CPU_MS=" + pair(pipeline.sceneCpuP50Ms, pipeline.sceneCpuP95Ms),
            "BACKDROP_CPU_MS=" + pair(scene.backdropCpuP50Ms, scene.backdropCpuP95Ms),
            "BOARD_CPU_MS=" + pair(scene.boardCpuP50Ms, scene.boardCpuP95Ms),
            "PAWNS_CPU_MS=" + pair(scene.pawnsCpuP50Ms, scene.pawnsCpuP95Ms),
            "DICE_CPU_MS=" + pair(scene.diceCpuP50Ms, scene.diceCpuP95Ms),
            "SHADOW_CPU_MS=" + pair(pipeline.shadowCpuP50Ms, pipeline.shadowCpuP95Ms),
            "POST_CPU_MS=" + pair(pipeline.postCpuP50Ms, pipeline.postCpuP95Ms),
            "FRAME_SAMPLES=" + frame.activeFrameSampleCount,
            "GL_SAMPLES=" + frame.glSubmissionSampleCount,
            "GPU_TIME=NOT_MEASURED"
        ).joinToString(" | ")
    }

    private fun deviceField(source: String, field: String, nextField: String): String =
        source.substringAfter("$field=", "").substringBefore(" $nextField=").trim()

    private fun compact(value: String): String = value
        .replace('|', ' ')
        .replace('\n', ' ')
        .replace('\r', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(80)

    private fun number(value: Float): String = String.format(Locale.US, "%.2f", value)

    private fun pair(p50: Float, p95: Float): String = number(p50) + "/" + number(p95)
}
