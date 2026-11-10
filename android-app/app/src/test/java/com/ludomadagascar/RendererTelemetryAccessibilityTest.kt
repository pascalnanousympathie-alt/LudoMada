package com.ludomadagascar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RendererTelemetryAccessibilityTest {
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
        backdropBatchIndexCounts = listOf(3_600, 27_000)
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
            preparedStaticMeshCount = 24
        )

        assertNotNull(label)
        label!!
        assertTrue(label.contains("RENDER_TELEMETRY=1"))
        assertTrue(label.contains("GPU_VENDOR=Google"))
        assertTrue(label.contains("GPU_RENDERER=SwiftShader"))
        assertTrue(label.contains("GLES=OpenGL ES 3.0"))
        assertTrue(label.contains("SURFACE=1419x424"))
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
        assertTrue(label.contains("PAWNS_CPU_MS=4.20/7.00"))
        assertTrue(label.contains("PARTICLE_UPLOAD_CPU_MS=0.23/1.67"))
        assertTrue(label.contains("FRAME_SAMPLES=18"))
        assertTrue(label.contains("SCENE_SAMPLES=60"))
        assertTrue(label.contains("GPU_TIME=NOT_MEASURED"))
        assertTrue(label.length < 1100)
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
