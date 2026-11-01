package com.ludomadagascar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

class GameplayPawnGlbTransformTest {
    @Test
    fun authoredPawnPartsShareTheBottomCenterPivot() {
        val parts = listOf(
            "LM_Pawn_Base_Weighted" to 0.09f,
            "LM_Pawn_Tapered_Body" to 0.45f,
            "LM_Pawn_Collar" to 0.77f,
            "LM_Pawn_Spherical_Head" to 1.02f
        )

        for ((name, authoredY) in parts) {
            val source = nodeTransform(-0.85f, authoredY, -1.55f)
            val normalized = GameplayPawnGlbTransform.normalize(name, source)

            assertEquals("$name x pivot", 0f, normalized[12], 0.000001f)
            assertEquals("$name y spacing", authoredY, normalized[13], 0.000001f)
            assertEquals("$name z pivot", 0f, normalized[14], 0.000001f)
        }
    }

    @Test
    fun undersideInsertAlreadyAtOriginIsNotOffsetAgain() {
        val source = nodeTransform(0f, 0.012f, 0f)
        val normalized = GameplayPawnGlbTransform.normalize(
            "LM_Pawn_Underside_Insert",
            source
        )

        assertEquals(0f, normalized[12], 0f)
        assertEquals(0.012f, normalized[13], 0.000001f)
        assertEquals(0f, normalized[14], 0f)
    }

    @Test
    fun dieAndUnrelatedNodesKeepTheirAuthoredTranslations() {
        val source = nodeTransform(1.25f, 0.5f, -2.75f)
        val normalized = GameplayPawnGlbTransform.normalize(
            "LM_Die_HydroChrome_Body",
            source
        )

        assertEquals(1.25f, normalized[12], 0f)
        assertEquals(0.5f, normalized[13], 0f)
        assertEquals(-2.75f, normalized[14], 0f)
    }

    @Test
    fun normalizationReturnsACopyAndDoesNotMutateGlbTransform() {
        val source = nodeTransform(-0.85f, 0.45f, -1.55f)
        val normalized = GameplayPawnGlbTransform.normalize(
            "LM_Pawn_Tapered_Body",
            source
        )

        assertNotSame(source, normalized)
        assertEquals(-0.85f, source[12], 0f)
        assertEquals(-1.55f, source[14], 0f)
        assertEquals(0f, normalized[12], 0.000001f)
        assertEquals(0f, normalized[14], 0.000001f)
    }

    private fun nodeTransform(x: Float, y: Float, z: Float): FloatArray =
        FloatArray(16).apply {
            this[0] = 1f
            this[5] = 1f
            this[10] = 1f
            this[12] = x
            this[13] = y
            this[14] = z
            this[15] = 1f
        }
}
