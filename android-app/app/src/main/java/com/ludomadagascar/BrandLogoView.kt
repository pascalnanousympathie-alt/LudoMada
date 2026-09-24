package com.ludomadagascar
import android.content.Context
import android.graphics.*
import android.view.View
class BrandLogoView(context:Context):View(context){
 private val p=Paint(Paint.ANTI_ALIAS_FLAG)
 override fun onDraw(c:Canvas){
  val s=minOf(width,height).toFloat();val x=(width-s)/2f;val y=(height-s)/2f
  p.setColor(Color.rgb(15,79,199));p.setShadowLayer(10f,0f,4f,0x44000000);c.drawRoundRect(x,y,x+s,y+s,s*.24f,s*.24f,p);p.clearShadowLayer()
  p.setColor(Color.rgb(24,163,255));c.drawCircle(x+s*.50f,y+s*.29f,s*.15f,p);c.drawRoundRect(x+s*.36f,y+s*.39f,x+s*.64f,y+s*.84f,s*.08f,s*.08f,p)
  p.setColor(Color.WHITE);c.drawCircle(x+s*.50f,y+s*.29f,s*.08f,p)
  p.setColor(Color.rgb(255,200,61));c.drawCircle(x+s*.22f,y+s*.70f,s*.065f,p);c.drawCircle(x+s*.78f,y+s*.70f,s*.065f,p);c.drawCircle(x+s*.22f,y+s*.84f,s*.065f,p);c.drawCircle(x+s*.78f,y+s*.84f,s*.065f,p)
 }
}
