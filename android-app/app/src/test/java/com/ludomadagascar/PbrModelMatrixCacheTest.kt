package com.ludomadagascar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PbrModelMatrixCacheTest {
    private fun identity() = FloatArray(16).also {
        it[0] = 1f
        it[5] = 1f
        it[10] = 1f
        it[15] = 1f
    }

    @Test
    fun firstMatrixInAFrameMustBeUploaded() {
        val cache = PbrModelMatrixCache()
        assertTrue(cache.shouldUpload(identity()))
    }

    @Test
    fun consecutiveBatchesWithEqualMatrixSkipRedundantUploads() {
        val cache = PbrModelMatrixCache()
        val identity = identity()
        assertTrue(cache.shouldUpload(identity))
        assertFalse(cache.shouldUpload(identity.copyOf()))
        assertFalse(cache.shouldUpload(identity.copyOf()))
    }

    @Test
    fun cacheOwnsItsSnapshotAndDetectsCallerMutation() {
        val cache = PbrModelMatrixCache()
        val model = identity()
        assertTrue(cache.shouldUpload(model))
        assertFalse(cache.shouldUpload(model))
        model[12] = 2.5f
        assertTrue(cache.shouldUpload(model))
        assertFalse(cache.shouldUpload(model.copyOf()))
    }

    @Test
    fun changedTransformMustBeUploaded() {
        val cache = PbrModelMatrixCache()
        val first = identity()
        val second = identity().also { it[13] = 0.2f }
        assertTrue(cache.shouldUpload(first))
        assertTrue(cache.shouldUpload(second))
        assertFalse(cache.shouldUpload(second.copyOf()))
    }

    @Test
    fun resetInvalidatesTheFrameLocalMatrixCache() {
        val cache = PbrModelMatrixCache()
        val identity = identity()
        assertTrue(cache.shouldUpload(identity))
        assertFalse(cache.shouldUpload(identity.copyOf()))
        cache.reset()
        assertTrue(cache.shouldUpload(identity.copyOf()))
    }

    @Test
    fun rejectsMatricesWithTheWrongElementCount() {
        val cache = PbrModelMatrixCache()
        assertThrows(IllegalArgumentException::class.java) {
            cache.shouldUpload(FloatArray(15))
        }
    }
}
