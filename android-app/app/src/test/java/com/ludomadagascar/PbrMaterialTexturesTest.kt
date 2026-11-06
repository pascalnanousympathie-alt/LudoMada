package com.ludomadagascar

import org.junit.Assert.assertTrue
import org.junit.Test

class PbrMaterialTexturesTest {
    @Test
    fun ivoryFeltAndMetalProfilesStayLowContrast() {
        for (kind in 1..4) {
            val redRange = channelRange(kind, 16)
            val greenRange = channelRange(kind, 8)
            val blueRange = channelRange(kind, 0)
            assertTrue("kind=$kind red range=$redRange", redRange <= 8)
            assertTrue("kind=$kind green range=$greenRange", greenRange <= 8)
            assertTrue("kind=$kind blue range=$blueRange", blueRange <= 8)
        }
    }

    @Test
    fun walnutRetainsVisibleButRestrainedGrain() {
        val redRange = channelRange(0, 16)
        assertTrue("walnut grain range=$redRange", redRange in 10..24)
    }

    private fun channelRange(kind: Int, shift: Int): Int {
        var minimum = 255
        var maximum = 0
        for (y in 0 until 512 step 16) {
            for (x in 0 until 512 step 16) {
                val channel = (PbrMaterialTextures.colorRgb(kind, x, y) ushr shift) and 0xff
                minimum = minOf(minimum, channel)
                maximum = maxOf(maximum, channel)
            }
        }
        return maximum - minimum
    }
}
