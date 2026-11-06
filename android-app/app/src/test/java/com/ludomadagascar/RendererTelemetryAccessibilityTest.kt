package com.ludomadagascar

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RendererTelemetryAccessibilityTest {
    private val frame = RenderFramePerfSnapshot(
        activeFrameSampleCount = 17, activeFrameFps = 5.14f,
        activeFrameIntervalP50Ms = 194.26f, activeFrameIntervalP95Ms = 239.90f,
        activeFrameIntervalMaxMs = 239.90f, glSubmissionSampleCount = 52,
        glSubmitP50Ms = 26.05f, glSubmitP95Ms = 76.78f, glSubmitMaxMs = 78.09f
    )
    private val pipeline = RenderPipelinePhaseSnapshot(
        sampleCount = 52,
        updateCpuP50Ms = 0.14f, updateCpuP95Ms = 0.26f,
        shadowCpuP50Ms = 2.13f, shadowCpuP95Ms = 6.36f,
        sceneCpuP50Ms = 23.27f, sceneCpuP95Ms = 72.11f,
        postCpuP50Ms = 0.03f, postCpuP95Ms = 0.07f,
        pawnTargetsCpuP50Ms = 0.04f, pawnTargetsCpuP95Ms = 0.31f
    )
    private val scene = RenderSceneBreakdownSnapshot(
        sampleCount = 52,
        setupCpuP50Ms = 0.03f, setupCpuP95Ms = 0.06f,
        backdropCpuP50Ms = 13.01f, backdropCpuP95Ms = 63.43f,
        boardCpuP50Ms = 1.15f, boardCpuP95Ms = 1.58f,
        pawnsCpuP50Ms = 4.22f, pawnsCpuP95Ms = 10.37f,
        diceCpuP50Ms = 0.95f, diceCpuP95Ms = 3.81f,
        shaderEndCpuP50Ms = 0.002f, shaderEndCpuP95Ms = 0.003f,
        particlesCpuP50Ms = 0.44f, particlesCpuP95Ms = 2.77f,
        particleBuildCpuP50Ms = 0.03f, particleBuildCpuP95Ms = 0.04f,
        particleBufferCopyCpuP50Ms = 0.007f, particleBufferCopyCpuP95Ms = 0.01f,
        particleGlStateCpuP50Ms = 0.08f, particleGlStateCpuP95Ms = 1.51f,
        particleBufferUploadCpuP50Ms = 0.24f, particleBufferUploadCpuP95Ms = 1.37f,
        particleDrawSubmitCpuP50Ms = 0.003f, particleDrawSubmitCpuP95Ms = 0.01f,
        particleCleanupCpuP50Ms = 0.007f, particleCleanupCpuP95Ms = 0.01f,
        activeParticlesP50 = 14, activeParticlesP95 = 16
    )

    @Test
    fun publishesDeviceIdentityAndFrameAndSceneTimingsInOneAccessibilityLabel() {
        val label = RendererTelemetryAccessibility.format(
            "vendor=Google (Google Inc.) renderer=Android Emulator OpenGL ES Translator (Google SwiftShader) version=OpenGL ES 3.0 SwiftShader",
            1419, 424, frame, pipeline, scene
        )
        assertNotNull(label)
        assertTrue(label!!.contains("RENDER_TELEMETRY=1"))
        assertTrue(label.contains("SURFACE=1419x424"))
        assertTrue(label.contains("GPU_VENDOR=Google (Google Inc.)"))
        assertTrue(label.contains("GPU_RENDERER=Android Emulator OpenGL ES Translator (Google SwiftShader)"))
        assertTrue(label.contains("GLES=OpenGL ES 3.0 SwiftShader"))
        assertTrue(label.contains("FPS=5.14"))
        assertTrue(label.contains("FRAME_MS=194.26/239.90"))
        assertTrue(label.contains("GL_CPU_MS=26.05/76.78"))
        assertTrue(label.contains("BACKDROP_CPU_MS=13.01/63.43"))
        assertTrue(label.contains("PAWNS_CPU_MS=4.22/10.37"))
        assertTrue(label.contains("GPU_TIME=NOT_MEASURED"))
        assertTrue(label.length < 1200)
    }

    @Test
    fun doesNotPublishTelemetryWithoutACompleteDeviceAndTimingSnapshot() {
        assertNull(RendererTelemetryAccessibility.format("unavailable", frame, pipeline, scene))
        assertNull(RendererTelemetryAccessibility.format("vendor=Google renderer=SwiftShader version=OpenGL ES 3.0", null, pipeline, scene))
    }

    @Test
    fun doesNotPublishAnInvalidOrUnavailableActiveFps() {
        assertNull(RendererTelemetryAccessibility.format("vendor=Google renderer=SwiftShader version=OpenGL ES 3.0", 1419, 424, frame.copy(activeFrameFps = null), pipeline, scene))
        assertNull(RendererTelemetryAccessibility.format("vendor=Google renderer=SwiftShader version=OpenGL ES 3.0", 1419, 424, frame.copy(activeFrameFps = 0f), pipeline, scene))
        assertNull(RendererTelemetryAccessibility.format("vendor=unknown renderer=unknown version=unknown", 1419, 424, frame, pipeline, scene))
    }
}
