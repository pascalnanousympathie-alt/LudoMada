package com.ludomadagascar

import android.content.Context
import android.graphics.*
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

class PremiumHeroViewV2(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val colors = intArrayOf(
        Color.rgb(225, 63, 70),
        Color.rgb(42, 170, 108),
        Color.rgb(243, 187, 43),
        Color.rgb(65, 112, 221)
    )
    private fun d(v: Float) = v * resources.displayMetrics.density
    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val w = width.toFloat()
        val h = height.toFloat()
        val r = d(26f)

        paint.shader = LinearGradient(
            0f, 0f, w, h,
            intArrayOf(Color.rgb(4, 22, 56), Color.rgb(20, 72, 158), Color.rgb(35, 126, 236)),
            null, Shader.TileMode.CLAMP
        )
        paint.setShadowLayer(d(12f), 0f, d(4f), 0x66000000)
        c.drawRoundRect(0f, 0f, w, h, r, r, paint)
        paint.clearShadowLayer()
        paint.shader = null

        paint.color = 0x18FFFFFF
        c.drawCircle(w * .86f, h * .10f, h * .48f, paint)
        paint.color = 0x12FFFFFF
        c.drawCircle(w * .13f, h * .95f, h * .30f, paint)
        paint.color = 0x0EFFFFFF
        c.drawCircle(w * .58f, h * .72f, h * .24f, paint)

        paint.shader = LinearGradient(0f, 0f, w * .82f, h * .36f,
            0x65FFFFFF, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawRoundRect(d(2f), d(2f), w * .90f, h * .36f, r, r, paint)
        paint.shader = null
        paint.color = Color.WHITE
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textSize = d(11f)
        c.drawText("LUDOMADA", d(20f), d(28f), paint)
        paint.textSize = d(23f)
        c.drawText("Joue. Gagne. Profite.", d(20f), d(62f), paint)
        paint.color = 0xD9FFFFFF.toInt()
        paint.textSize = d(11.5f)
        c.drawText("EXPÉRIENCE PREMIUM  •  2 À 4 JOUEURS", d(20f), d(86f), paint)

        paint.shader = LinearGradient(0f, d(103f), d(153f), d(139f),
            Color.WHITE, Color.rgb(218, 234, 255), Shader.TileMode.CLAMP)
        c.drawRoundRect(d(18f), d(103f), d(153f), d(139f), d(18f), d(18f), paint)
        paint.shader = null
        paint.color = Color.rgb(18, 63, 132)
        paint.textSize = d(10.5f)
        c.drawText("JOUER MAINTENANT", d(31f), d(126f), paint)

        paint.color = Color.rgb(31, 98, 216)
        c.drawCircle(d(136f), d(121f), d(7f), paint)
        paint.color = Color.WHITE
        val triangle = Path()
        triangle.moveTo(d(134f), d(117f))
        triangle.lineTo(d(134f), d(125f))
        triangle.lineTo(d(140f), d(121f))
        triangle.close()
        c.drawPath(triangle, paint)

        val rad = minOf(w, h) * .074f
        val bx = w * .76f
        val by = h * .65f
        for (i in colors.indices) {
            val a = Math.toRadians((i * 90 - 45).toDouble())
            val x = bx + (cos(a) * w * .15f).toFloat()
            val y = by + (sin(a) * h * .16f).toFloat()
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = d(1.3f)
            paint.color = 0x44FFFFFF
            c.drawCircle(x, y, rad * 1.38f, paint)
            paint.style = Paint.Style.FILL
            paint.color = 0x66000000
            paint.setShadowLayer(d(4f), 0f, d(3f), 0x77000000)
            c.drawCircle(x, y + rad * .16f, rad, paint)
            paint.clearShadowLayer()
            paint.shader = RadialGradient(
                x - rad * .34f, y - rad * .42f, rad * 1.12f,
                Color.WHITE, colors[i], Shader.TileMode.CLAMP
            )
            c.drawCircle(x, y, rad, paint)
            paint.shader = null
            paint.color = 0xBFFFFFFF.toInt()
            c.drawCircle(x - rad * .30f, y - rad * .34f, rad * .18f, paint)
        }
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(w * .65f, h * .10f, w * .95f, h * .43f, 0x24FFFFFF, 0x0FFFFFFF, Shader.TileMode.CLAMP)
        c.drawRoundRect(w * .65f, h * .10f, w * .95f, h * .43f, d(15f), d(15f), paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = d(2f)
        paint.color = Color.rgb(243, 193, 67)
        c.drawRoundRect(w * .65f, h * .10f, w * .95f, h * .43f, d(15f), d(15f), paint)
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(w * .80f, h * .23f, rad * 1.45f, Color.WHITE, Color.rgb(43, 126, 235), Shader.TileMode.CLAMP)
        c.drawCircle(w * .80f, h * .23f, rad * 1.05f, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = d(1.3f)
        paint.color = 0x88FFFFFF.toInt()
        c.drawCircle(w * .80f, h * .23f, rad * 1.05f, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = d(19f)
        c.drawText("LM", w * .80f, h * .255f, paint)
        paint.textSize = d(8.5f)
        paint.color = 0xD9FFFFFF.toInt()
        c.drawText("4 JOUEURS", w * .80f, h * .365f, paint)
        paint.textAlign = Paint.Align.LEFT
        paint.color = 0xCFFFFFFF.toInt()
        for (star in arrayOf(
            floatArrayOf(.56f, .19f, 2.8f),
            floatArrayOf(.92f, .64f, 2.2f),
            floatArrayOf(.52f, .74f, 2.4f)
        )) c.drawCircle(w * star[0], h * star[1], d(star[2]), paint)
    }
}
