package com.ludomadagascar

import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Low-contrast, material-specific procedural albedo profiles for the mobile PBR board.
 * Only walnut receives directional grain; ivory, felt and metal stay visually quiet.
 */
internal object PbrMaterialTextures {
    private val tau = (2.0 * PI).toFloat()

    fun colorRgb(kind: Int, x: Int, y: Int, size: Int = 512): Int {
        require(size > 0) { "Texture size must be positive" }
        val safeX = x.coerceIn(0, size - 1)
        val safeY = y.coerceIn(0, size - 1)
        val u = (safeX + 0.5f) / size.toFloat()
        val v = (safeY + 0.5f) / size.toFloat()
        val rgb = when (kind) {
            // Walnut grain follows the board length, with restrained contrast and warped fibers.
            0 -> {
                val warp = (valueNoise(u, v, 3, 12) - 0.5f) * 1.1f
                val broad = sin((u * 8f + warp * 0.35f) * tau)
                val fine = sin((u * 29f + warp * 1.1f + sin(v * tau * 6f) * 0.08f) * tau)
                val pores = valueNoise(u, v, 56, 32) - 0.5f
                val tone = broad * 0.30f + fine * 0.10f + pores * 0.07f
                intArrayOf(
                    channel(95f + tone * 18f),
                    channel(47f + tone * 11f),
                    channel(24f + tone * 6f)
                )
            }
            // Ivory cells use soft, near-neutral mottling rather than the walnut grain profile.
            1 -> {
                val mottle = valueNoise(u, v, 22, 22) - 0.5f
                val micro = hash(safeX / 2, safeY / 2) - 0.5f
                intArrayOf(
                    channel(232f + mottle * 4.0f + micro),
                    channel(223f + mottle * 3.5f + micro * 0.8f),
                    channel(205f + mottle * 4.5f + micro * 0.8f)
                )
            }
            // Neutral felt is almost uniform; the underlying GLB material provides its finish.
            2 -> {
                val mottle = valueNoise(u, v, 16, 16) - 0.5f
                val micro = hash(safeX, safeY) - 0.5f
                intArrayOf(
                    channel(132f + mottle * 1.6f + micro * 0.7f),
                    channel(132f + mottle * 1.4f + micro * 0.7f),
                    channel(138f + mottle * 1.8f + micro * 0.7f)
                )
            }
            // Metal albedo remains smooth so its response comes from PBR reflections.
            else -> {
                val mottle = valueNoise(u, v, 12, 12) - 0.5f
                val micro = hash(safeX, safeY) - 0.5f
                intArrayOf(
                    channel(194f + mottle * 1.5f + micro * 0.6f),
                    channel(202f + mottle * 1.5f + micro * 0.6f),
                    channel(210f + mottle * 1.5f + micro * 0.6f)
                )
            }
        }
        return (rgb[0] shl 16) or (rgb[1] shl 8) or rgb[2]
    }

    private fun channel(value: Float): Int = value.roundToInt().coerceIn(0, 255)

    private fun valueNoise(u: Float, v: Float, columns: Int, rows: Int): Float {
        val px = u * columns
        val py = v * rows
        val x0 = floor(px).toInt()
        val y0 = floor(py).toInt()
        val tx = smooth(px - x0)
        val ty = smooth(py - y0)
        val top = lerp(hash(x0, y0), hash(x0 + 1, y0), tx)
        val bottom = lerp(hash(x0, y0 + 1), hash(x0 + 1, y0 + 1), tx)
        return lerp(top, bottom, ty)
    }

    private fun smooth(value: Float): Float = value * value * (3f - 2f * value)

    private fun lerp(start: Float, end: Float, amount: Float): Float =
        start + (end - start) * amount

    private fun hash(x: Int, y: Int): Float {
        var value = x * 374761393 + y * 668265263 + 1442695041
        value = (value xor (value ushr 13)) * 1274126177
        value = value xor (value ushr 16)
        return (value ushr 8).toFloat() / 16777215f
    }
}
