package com.ludomadagascar

import org.junit.Assert.assertEquals
import org.junit.Test

class PbrShadowReceptionTest {
    @Test
    fun renderablesReceiveShadowsByDefault() {
        assertEquals(1f, shadowReceiverUniform(), 0f)
        assertEquals(1f, shadowReceiverUniform(receivesShadow = true), 0f)
    }

    @Test
    fun visualOnlyBackdropCanOptOutOfShadowReception() {
        assertEquals(0f, shadowReceiverUniform(receivesShadow = false), 0f)
    }
}
