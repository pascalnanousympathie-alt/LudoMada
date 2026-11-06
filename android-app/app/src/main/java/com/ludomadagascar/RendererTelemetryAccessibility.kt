package com.ludomadagascar

import java.util.Locale

/** Compact machine-readable renderer telemetry embedded in the gameplay accessibility label. */
internal object RendererTelemetryAccessibility {
    fun format(
        deviceDiagnostic: String,
        surfaceWidth: Int,
        surfaceHeight: Int,
        frame: RenderFramePerfSnapshot?,
        pipeline: RenderPipelinePhaseSnapshot?,
        scene: RenderSceneBreakdownSnapshot?
    ): String? {
        if (frame == null || pipeline == null || scene == null) return null

        val vendor = deviceField(deviceDiagnostic, "vendor", "renderer")
        val renderer = deviceField(deviceDiagnostic, "renderer", "version")
        val gles = deviceDiagnostic.substringAfter("version=", "").trim()
        if (vendor.isEmpty() || renderer.isEmpty() || gles.isEmpty()) return null

        val fps = frame.activeFrameFps?.takeIf { it.isFinite() && it > 0f }
            ?.let(::number) ?: "INSUFFICIENT"
        return listOf(
            "RENDER_TELEMETRY=1",
            "GPU_VENDOR=" + compact(vendor),
            "GPU_RENDERER=" + compact(renderer),
            "GLES=" + compact(gles),
            "SURFACE=${surfaceWidth.coerceAtLeast(1)}x${surfaceHeight.coerceAtLeast(1)}",
            "FPS=$fps",
            "FRAME_MS=" + nullablePair(frame.activeFrameIntervalP50Ms, frame.activeFrameIntervalP95Ms),
            "GL_CPU_MS=" + pair(frame.glSubmitP50Ms, frame.glSubmitP95Ms),
            "SCENE_CPU_MS=" + pair(pipeline.sceneCpuP50Ms, pipeline.sceneCpuP95Ms),
            "BACKDROP_CPU_MS=" + pair(scene.backdropCpuP50Ms, scene.backdropCpuP95Ms),
            "BOARD_CPU_MS=" + pair(scene.boardCpuP50Ms, scene.boardCpuP95Ms),
            "PAWNS_CPU_MS=" + pair(scene.pawnsCpuP50Ms, scene.pawnsCpuP95Ms),
            "DICE_CPU_MS=" + pair(scene.diceCpuP50Ms, scene.diceCpuP95Ms),
            "PARTICLES_CPU_MS=" + pair(scene.particlesCpuP50Ms, scene.particlesCpuP95Ms),
            "PARTICLE_UPLOAD_CPU_MS=" + pair(scene.particleBufferUploadCpuP50Ms, scene.particleBufferUploadCpuP95Ms),
            "SHADOW_CPU_MS=" + pair(pipeline.shadowCpuP50Ms, pipeline.shadowCpuP95Ms),
            "POST_CPU_MS=" + pair(pipeline.postCpuP50Ms, pipeline.postCpuP95Ms),
            "PAWN_TARGETS_CPU_MS=" + pair(pipeline.pawnTargetsCpuP50Ms, pipeline.pawnTargetsCpuP95Ms),
            "FRAME_SAMPLES=${frame.activeFrameSampleCount}",
            "GL_SAMPLES=${frame.glSubmissionSampleCount}",
            "SCENE_SAMPLES=${scene.sampleCount}",
            "PIPELINE_SAMPLES=${pipeline.sampleCount}",
            "GPU_TIME=NOT_MEASURED"
        ).joinToString(" | ")
    }

    private fun deviceField(source: String, field: String, nextField: String): String =
        source.substringAfter("$field=", "").substringBefore(" $nextField=").trim()

    private fun compact(value: String): String = value
        .replace('|', '/')
        .replace('\n', ' ')
        .replace('\r', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(80)

    private fun nullablePair(p50: Float?, p95: Float?): String =
        if (p50 == null || p95 == null) "NA/NA" else pair(p50, p95)

    private fun pair(p50: Float, p95: Float): String = "${number(p50)}/${number(p95)}"

    private fun number(value: Float): String = String.format(Locale.US, "%.2f", value)
}
