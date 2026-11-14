package com.ludomadagascar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RendererTelemetryAccessibilityTest {
    @Test
    fun defersAccessibilityPawnTargetUpdatesUntilAnimationEnds() {
        assertFalse(RendererTelemetryAccessibility.shouldPublishPawnTapTargets(animationActive = true))
        assertTrue(RendererTelemetryAccessibility.shouldPublishPawnTapTargets(animationActive = false))
    }

    private val frame = RenderFramePerfSnapshot(
        activeFrameSampleCount = 18,
        activeFrameFps = 5.14f,
        activeFrameIntervalP50Ms = 194.55f,
        activeFrameIntervalP95Ms = 218.20f,
        activeFrameIntervalMaxMs = 231.00f,
        glSubmissionSampleCount = 60,
        glSubmitP50Ms = 18.25f,
        glSubmitP95Ms = 28.50f,
        glSubmitMaxMs = 39.00f,
        afterCallbackGapP50Ms = 170.0f,
        afterCallbackGapP95Ms = 187.0f,
        animationFrameSampleCount = 42,
        animationFrameFps = 58.0f,
        animationFrameIntervalP50Ms = 16.67f,
        animationFrameIntervalP95Ms = 33.33f,
        animationAfterCallbackGapP50Ms = 3.5f,
        animationAfterCallbackGapP95Ms = 18.0f
    )
    private val pipeline = RenderPipelinePhaseSnapshot(
        sampleCount = 60,
        updateCpuP50Ms = 0.20f, updateCpuP95Ms = 0.80f,
        shadowCpuP50Ms = 2.10f, shadowCpuP95Ms = 5.40f,
        sceneCpuP50Ms = 21.10f, sceneCpuP95Ms = 79.85f,
        postCpuP50Ms = 0.06f, postCpuP95Ms = 0.17f,
        pawnTargetsCpuP50Ms = 0.15f, pawnTargetsCpuP95Ms = 0.50f
    )
    private val scene = RenderSceneBreakdownSnapshot(
        sampleCount = 60,
        setupCpuP50Ms = 0.2f, setupCpuP95Ms = 0.6f,
        backdropCpuP50Ms = 12.3f, backdropCpuP95Ms = 16.5f,
        boardCpuP50Ms = 1.1f, boardCpuP95Ms = 1.6f,
        pawnsCpuP50Ms = 4.2f, pawnsCpuP95Ms = 7.0f,
        diceCpuP50Ms = 0.9f, diceCpuP95Ms = 1.4f,
        shaderEndCpuP50Ms = 0.1f, shaderEndCpuP95Ms = 0.2f,
        particlesCpuP50Ms = 0.36f, particlesCpuP95Ms = 0.9f,
        particleBuildCpuP50Ms = 0.03f, particleBuildCpuP95Ms = 0.05f,
        particleBufferCopyCpuP50Ms = 0.01f, particleBufferCopyCpuP95Ms = 0.02f,
        particleGlStateCpuP50Ms = 0.02f, particleGlStateCpuP95Ms = 0.04f,
        particleBufferUploadCpuP50Ms = 0.23f, particleBufferUploadCpuP95Ms = 1.67f,
        particleDrawSubmitCpuP50Ms = 0.01f, particleDrawSubmitCpuP95Ms = 0.76f,
        particleCleanupCpuP50Ms = 0.01f, particleCleanupCpuP95Ms = 0.02f,
        activeParticlesP50 = 12, activeParticlesP95 = 18,
        backdropBatchCount = 2,
        backdropBatchCpuP95Ms = listOf(1.25f, 8.50f),
        backdropBatchVertexCounts = listOf(1_200, 9_000),
        backdropBatchIndexCounts = listOf(3_600, 27_000),
        backdropCullingQueryCpuP50Ms = 0.20f, backdropCullingQueryCpuP95Ms = 1.40f,
        backdropCullingTransitionCpuP50Ms = 0.01f, backdropCullingTransitionCpuP95Ms = 0.10f,
        backdropCullingRestoreCpuP50Ms = 0.01f, backdropCullingRestoreCpuP95Ms = 0.08f,
        backdropBatchTotalCpuP50Ms = 0.30f, backdropBatchTotalCpuP95Ms = 0.60f,
        backdropUnattributedCpuP50Ms = 11.78f, backdropUnattributedCpuP95Ms = 14.32f
    )

    @Test
    fun exposesRendererFpsFrameCadenceAndSceneBreakdownInOneAccessibilityPayload() {
        val label = RendererTelemetryAccessibility.format(
            deviceDiagnostic = "vendor=Google renderer=SwiftShader version=OpenGL ES 3.0",
            surfaceWidth = 1419,
            surfaceHeight = 424,
            frame = frame,
            pipeline = pipeline,
            scene = scene,
            preparedStaticMeshCount = 24,
            softwareFramePacingEnabled = true,
            eglSwapInterval0Status = "REQUEST_ACCEPTED",
            eglSwapSurfaceWidth = 177,
            eglSwapSurfaceHeight = 53,
            gpu = GpuTimerSnapshot(
                status = "MEASURED",
                sampleCount = 60,
                p50Ms = 8.50f,
                p95Ms = 15.20f,
                disjointCount = 0,
                skippedFrames = 2
            ),
            gpuFence = GpuFenceSnapshot(
                status = "MEASURED",
                sampleCount = 60,
                completionP50Ms = 12.0f,
                completionP95Ms = 84.0f,
                pendingCount = 1,
                oldestPendingMs = 16.0f,
                waitTimeouts = 3,
                waitFailures = 0,
                createFailures = 0,
                skippedFrames = 1
            )
        )

        assertNotNull(label)
        label!!
        assertTrue(label.contains("RENDER_TELEMETRY=1"))
        assertTrue(label.contains("GPU_VENDOR=Google"))
        assertTrue(label.contains("GPU_RENDERER=SwiftShader"))
        assertTrue(label.contains("GLES=OpenGL ES 3.0"))
        assertTrue(label.contains("SURFACE=1419x424"))
        assertTrue(label.contains("SOFTWARE_FRAME_PACING=ON"))
        assertTrue(label.contains("EGL_SWAP_INTERVAL0=REQUEST_ACCEPTED"))
        assertTrue(label.contains("EGL_SWAP_SURFACE=177x53"))
        assertTrue(label.contains("STATIC_MESH_VAO_COUNT=24"))
        assertTrue(label.contains("FPS=5.14"))
        assertTrue(label.contains("FRAME_MS=194.55/218.20"))
        assertTrue(label.contains("AFTER_CALLBACK_GAP_MS=170.00/187.00"))
        assertTrue(label.contains("ANIM_FPS=58.00"))
        assertTrue(label.contains("ANIM_FRAME_MS=16.67/33.33"))
        assertTrue(label.contains("ANIM_AFTER_CALLBACK_GAP_MS=3.50/18.00"))
        assertTrue(label.contains("ANIM_FRAME_SAMPLES=42"))
        assertTrue(label.contains("GL_CPU_MS=18.25/28.50"))
        assertTrue(label.contains("BACKDROP_CPU_MS=12.30/16.50"))
        assertTrue(label.contains("BACKDROP_BATCH_COUNT=2"))
        assertTrue(label.contains("BACKDROP_BATCH_CPU_P95_MS=1.25,8.50"))
        assertTrue(label.contains("BACKDROP_BATCH_VERTEX_COUNTS=1200,9000"))
        assertTrue(label.contains("BACKDROP_BATCH_INDEX_COUNTS=3600,27000"))
        assertTrue(label.contains("BACKDROP_CULL_QUERY_CPU_MS=0.20/1.40"))
        assertTrue(label.contains("BACKDROP_CULL_TRANSITION_CPU_MS=0.01/0.10"))
        assertTrue(label.contains("BACKDROP_CULL_RESTORE_CPU_MS=0.01/0.08"))
        assertTrue(label.contains("BACKDROP_BATCH_TOTAL_CPU_MS=0.30/0.60"))
        assertTrue(label.contains("BACKDROP_UNATTRIBUTED_CPU_MS=11.78/14.32"))
        assertTrue(label.contains("PAWNS_CPU_MS=4.20/7.00"))
        assertTrue(label.contains("PARTICLE_UPLOAD_CPU_MS=0.23/1.67"))
        assertTrue(label.contains("FRAME_SAMPLES=18"))
        assertTrue(label.contains("SCENE_SAMPLES=60"))
        assertTrue(label.contains("GPU_TIME=MEASURED"))
        assertTrue(label.contains("GPU_FRAME_MS=8.50/15.20"))
        assertTrue(label.contains("GPU_TIMER_SAMPLES=60"))
        assertTrue(label.contains("GPU_TIMER_DISJOINTS=0"))
        assertTrue(label.contains("GPU_TIMER_SKIPPED_FRAMES=2"))
        assertTrue(label.contains("GPU_FENCE_STATUS=MEASURED"))
        assertTrue(label.contains("GPU_FENCE_SIGNAL_DELAY_MS=12.00/84.00"))
        assertTrue(label.contains("GPU_FENCE_SAMPLES=60"))
        assertTrue(label.contains("GPU_FENCE_PENDING=1"))
        assertTrue(label.contains("GPU_FENCE_OLDEST_MS=16.00"))
        assertTrue(label.contains("GPU_FENCE_TIMEOUTS=3"))
        assertTrue(label.contains("GPU_FENCE_FAILURES=0"))
        assertTrue(label.contains("GPU_FENCE_SKIPS=1"))
        assertTrue(label.length < 1800)
    }

    @Test
    fun reportsFenceInsufficientWithoutInventingGpuExecutionTime() {
        val label = RendererTelemetryAccessibility.format(
            "vendor=Google renderer=SwiftShader version=OpenGL ES 3.0",
            720,
            320,
            frame,
            pipeline,
            scene,
            24,
            gpuFence = GpuFenceSnapshot(
                status = "INSUFFICIENT",
                sampleCount = 4,
                completionP50Ms = null,
                completionP95Ms = null,
                pendingCount = 2,
                oldestPendingMs = 50f,
                waitTimeouts = 8,
                waitFailures = 0,
                createFailures = 0,
                skippedFrames = 0
            )
        )
        assertNotNull(label)
        assertTrue(label!!.contains("GPU_FENCE_STATUS=INSUFFICIENT"))
        assertTrue(label.contains("GPU_FENCE_SIGNAL_DELAY_MS=NA/NA"))
        assertTrue(label.contains("GPU_FENCE_SAMPLES=4"))
        assertTrue(label.contains("GPU_FENCE_PENDING=2"))
    }

    @Test
    fun reportsGpuTimerUnsupportedWithoutInventingExecutionTime() {
        val label = RendererTelemetryAccessibility.format(
            "vendor=Google renderer=SwiftShader version=OpenGL ES 3.0",
            720,
            320,
            frame,
            pipeline,
            scene,
            24,
            GpuTimerSnapshot(
                status = "UNSUPPORTED",
                sampleCount = 0,
                p50Ms = null,
                p95Ms = null,
                disjointCount = 0,
                skippedFrames = 0
            )
        )
        assertNotNull(label)
        assertTrue(label!!.contains("GPU_TIME=UNSUPPORTED"))
        assertTrue(label.contains("GPU_FRAME_MS=NA/NA"))
        assertTrue(label.contains("GPU_TIMER_SAMPLES=0"))
    }

    @Test
    fun marksFpsUnavailableRatherThanHidingAnInsufficientSampleWindow() {
        val noIntervals = frame.copy(
            activeFrameSampleCount = 0,
            activeFrameFps = null,
            activeFrameIntervalP50Ms = null,
            activeFrameIntervalP95Ms = null,
            activeFrameIntervalMaxMs = null
        )
        val label = RendererTelemetryAccessibility.format(
            "vendor=Google renderer=SwiftShader version=OpenGL ES 3.0",
            720,
            320,
            noIntervals,
            pipeline,
            scene,
            24
        )
        assertNotNull(label)
        assertTrue(label!!.contains("FPS=INSUFFICIENT"))
        assertTrue(label.contains("FRAME_MS=NA/NA"))
    }

    @Test
    fun waitsUntilFramePipelineAndSceneSnapshotsAreAvailable() {
        assertNull(RendererTelemetryAccessibility.format("vendor=Google renderer=SwiftShader version=OpenGL ES 3.0", 100, 100, frame, null, scene, 24))
        assertNull(RendererTelemetryAccessibility.format("vendor=Google renderer=SwiftShader version=OpenGL ES 3.0", 100, 100, frame, pipeline, null, 24))
        assertNull(RendererTelemetryAccessibility.format("vendor=Google renderer=SwiftShader version=OpenGL ES 3.0", 100, 100, null, pipeline, scene, 24))
    }

    @Test
    fun refusesToPublishTelemetryUntilStaticMeshesArePreloaded() {
        assertNull(
            RendererTelemetryAccessibility.format(
                "vendor=Google renderer=SwiftShader version=OpenGL ES 3.0",
                100,
                100,
                frame,
                pipeline,
                scene,
                0
            )
        )
    }

    @Test
    fun rejectsMissingOrMalformedGpuIdentity() {
        assertNull(RendererTelemetryAccessibility.format("vendor= renderer=SwiftShader version=OpenGL ES 3.0", 100, 100, frame, pipeline, scene, 24))
        assertNull(RendererTelemetryAccessibility.format("unknown", 100, 100, frame, pipeline, scene, 24))
    }
}

