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
        scene: RenderSceneBreakdownSnapshot?,
        preparedStaticMeshCount: Int,
        gpu: GpuTimerSnapshot? = null,
        gpuFence: GpuFenceSnapshot? = null,
        softwareFramePacingEnabled: Boolean = false,
        eglSwapInterval0Status: String = "NOT_REQUESTED",
        eglSwapSurfaceWidth: Int = 0,
        eglSwapSurfaceHeight: Int = 0
    ): String? {
        if (frame == null || pipeline == null || scene == null || preparedStaticMeshCount <= 0) return null

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
            "SOFTWARE_FRAME_PACING=" + (if (softwareFramePacingEnabled) "ON" else "OFF"),
            "EGL_SWAP_INTERVAL0=" + compact(eglSwapInterval0Status),
            "EGL_SWAP_SURFACE=" + if (eglSwapSurfaceWidth > 0 && eglSwapSurfaceHeight > 0) {
                "${eglSwapSurfaceWidth}x${eglSwapSurfaceHeight}"
            } else "UNAVAILABLE",
            "STATIC_MESH_VAO_COUNT=$preparedStaticMeshCount",
            "FPS=$fps",
            "FRAME_MS=" + nullablePair(frame.activeFrameIntervalP50Ms, frame.activeFrameIntervalP95Ms),
            "AFTER_CALLBACK_GAP_MS=" + nullablePair(frame.afterCallbackGapP50Ms, frame.afterCallbackGapP95Ms),
            "ANIM_FPS=" + (frame.animationFrameFps?.takeIf { it.isFinite() && it > 0f }?.let(::number) ?: "INSUFFICIENT"),
            "ANIM_FRAME_MS=" + nullablePair(frame.animationFrameIntervalP50Ms, frame.animationFrameIntervalP95Ms),
            "ANIM_AFTER_CALLBACK_GAP_MS=" + nullablePair(frame.animationAfterCallbackGapP50Ms, frame.animationAfterCallbackGapP95Ms),
            "ANIM_GL_THREAD_POST_CALLBACK_CPU_MS=" + nullablePair(frame.animationGlThreadPostCallbackCpuP50Ms, frame.animationGlThreadPostCallbackCpuP95Ms),
            "ANIM_FRAME_SAMPLES=${frame.animationFrameSampleCount}",
            "GL_CPU_MS=" + pair(frame.glSubmitP50Ms, frame.glSubmitP95Ms),
            "SCENE_CPU_MS=" + pair(pipeline.sceneCpuP50Ms, pipeline.sceneCpuP95Ms),
            "BACKDROP_CPU_MS=" + pair(scene.backdropCpuP50Ms, scene.backdropCpuP95Ms),
            "SHADER_END_CPU_MS=" + pair(scene.shaderEndCpuP50Ms, scene.shaderEndCpuP95Ms),
            "BACKDROP_CULL_QUERY_CPU_MS=" + pair(scene.backdropCullingQueryCpuP50Ms, scene.backdropCullingQueryCpuP95Ms),
            "BACKDROP_CULL_TRANSITION_CPU_MS=" + pair(scene.backdropCullingTransitionCpuP50Ms, scene.backdropCullingTransitionCpuP95Ms),
            "BACKDROP_CULL_RESTORE_CPU_MS=" + pair(scene.backdropCullingRestoreCpuP50Ms, scene.backdropCullingRestoreCpuP95Ms),
            "BACKDROP_BATCH_TOTAL_CPU_MS=" + pair(scene.backdropBatchTotalCpuP50Ms, scene.backdropBatchTotalCpuP95Ms),
            "BACKDROP_UNATTRIBUTED_CPU_MS=" + pair(scene.backdropUnattributedCpuP50Ms, scene.backdropUnattributedCpuP95Ms),
            "BACKDROP_BATCH_COUNT=${scene.backdropBatchCount}",
            "BACKDROP_BATCH_CPU_P95_MS=" + scene.backdropBatchCpuP95Ms.joinToString(",") { number(it) },
            "BACKDROP_BATCH_VERTEX_COUNTS=" + scene.backdropBatchVertexCounts.joinToString(","),
            "BACKDROP_BATCH_INDEX_COUNTS=" + scene.backdropBatchIndexCounts.joinToString(","),
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
            "GPU_TIME=" + (gpu?.status ?: "UNSUPPORTED"),
            "GPU_FRAME_MS=" + if (gpu?.p50Ms != null && gpu.p95Ms != null) pair(gpu.p50Ms, gpu.p95Ms) else "NA/NA",
            "GPU_TIMER_SAMPLES=${gpu?.sampleCount ?: 0}",
            "GPU_TIMER_DISJOINTS=${gpu?.disjointCount ?: 0}",
            "GPU_TIMER_SKIPPED_FRAMES=${gpu?.skippedFrames ?: 0}",
            "GPU_FENCE_STATUS=" + (gpuFence?.status ?: "UNSUPPORTED"),
            "GPU_FENCE_SIGNAL_DELAY_MS=" + if (gpuFence?.completionP50Ms != null && gpuFence.completionP95Ms != null) pair(gpuFence.completionP50Ms, gpuFence.completionP95Ms) else "NA/NA",
            "GPU_FENCE_SAMPLES=${gpuFence?.sampleCount ?: 0}",
            "GPU_FENCE_PENDING=${gpuFence?.pendingCount ?: 0}",
            "GPU_FENCE_OLDEST_MS=" + (gpuFence?.oldestPendingMs?.let(::number) ?: "NA"),
            "GPU_FENCE_TIMEOUTS=${gpuFence?.waitTimeouts ?: 0}",
            "GPU_FENCE_FAILURES=${(gpuFence?.waitFailures ?: 0) + (gpuFence?.createFailures ?: 0)}",
            "GPU_FENCE_SKIPS=${gpuFence?.skippedFrames ?: 0}"
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
