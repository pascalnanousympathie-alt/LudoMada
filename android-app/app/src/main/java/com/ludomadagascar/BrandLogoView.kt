package com.ludomadagascar
import android.content.Context
import android.graphics.*
import android.view.View
class BrandLogoView(context:Context):View(context){
 private val p=Paint(Paint.ANTI_ALIAS_FLAG)
 override fun onDraw(c:Canvas){
  val s=minOf(width,height).toFloat(); val x=(width-s)/2f; val y=(height-s)/2f
  p.setShader(LinearGradient(x,y,x+s,y+s,Color.rgb(40,132,255),Color.rgb(7,29,73),Shader.TileMode.CLAMP))
  p.setShadowLayer(s*.11f,0f,s*.045f,0x66000000); c.drawRoundRect(x,y,x+s,y+s,s*.23f,s*.23f,p)
  p.clearShadowLayer(); p.setStyle(Paint.Style.STROKE); p.strokeWidth=s*.018f
  p.setShader(null); p.color=0x66FFFFFF; c.drawRoundRect(x+s*.055f,y+s*.055f,x+s*.945f,y+s*.945f,s*.19f,s*.19f,p)
  p.style=Paint.Style.FILL
  val cx=x+s*.5f
  p.setShader(LinearGradient(0f,y+s*.34f,0f,y+s*.86f,Color.WHITE,Color.rgb(185,235,255),Shader.TileMode.CLAMP))
  p.setShadowLayer(s*.035f,0f,s*.02f,0x66000000)
  c.drawCircle(cx,y+s*.27f,s*.14f,p)
  val body=Path(); body.moveTo(cx-s*.14f,y+s*.39f); body.lineTo(cx+s*.14f,y+s*.39f); body.lineTo(cx+s*.20f,y+s*.79f); body.quadTo(cx,y+s*.88f,cx-s*.20f,y+s*.79f); body.close()
  c.drawPath(body,p); p.clearShadowLayer(); p.setShader(null)
  p.color=Color.rgb(35,163,255); c.drawCircle(cx,y+s*.27f,s*.062f,p)
  p.color=0x55FFFFFF; c.drawCircle(cx-s*.04f,y+s*.22f,s*.025f,p)
  p.color=Color.rgb(235,190,64)
  for(q in floatArrayOf(.22f,.78f)){ c.drawCircle(x+s*q,y+s*.72f,s*.045f,p); c.drawCircle(x+s*q,y+s*.84f,s*.045f,p) }
 }
}