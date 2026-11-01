package com.ludomadagascar

import org.junit.Assert.assertEquals
import org.junit.Test

class PbrShadowReceptionTest {
    @Test
    fun receiversKeepShadowComparisonEnabledByDefault() {
        assertEquals(1f, PbrShadowReception.uniformValue(true), 0f)
    }

    @Test
    fun explicitlyExcludedBackdropSkipsShadowComparison() {
        assertEquals(0f, PbrShadowReception.uniformValue(false), 0f)
    }
}
