package com.ludomadagascar;

import android.app.Activity;
import android.content.Context;
import android.graphics.*;
import com.ludomadagascar.R;
import android.view.*;
import android.widget.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class PremiumHeroViewV2 extends View {
  private final HomeView home;
  private final Runnable homeBgFlip=new Runnable(){
    @Override public void run(){
      home.flipBackground();invalidate();postDelayed(this,6000L);
    }
  };
  public PremiumHeroViewV2(Context c){super(c);setLayerType(LAYER_TYPE_HARDWARE,null);home=new HomeView(c);}
  @Override protected void onAttachedToWindow(){
    super.onAttachedToWindow();postDelayed(homeBgFlip,6000L);invalidate();
  }
  @Override protected void onDetachedFromWindow(){
    removeCallbacks(homeBgFlip);super.onDetachedFromWindow();
  }
  @Override protected void onDraw(Canvas c){
    setContentDescription("LudoMada Premium Home | Player profile | coins | gems | Joue. Gagne. Grandis | En ligne | Hors-ligne | Wallet");
    home.layout(0,0,getWidth(),getHeight());
    home.draw(c);
  }
  @Override public boolean onTouchEvent(MotionEvent e){return home.onTouchEvent(e);}
  private void install() {
    if(!(getParent() instanceof ViewGroup))return;
    Activity a=(Activity)getContext(); ViewGroup host=(ViewGroup)getParent();
    host.removeAllViews();
    FrameLayout f=new FrameLayout(a); f.setBackgroundColor(Color.rgb(7,8,37));
    host.addView(f,new ViewGroup.LayoutParams(-1,-1));
    f.addView(home,new FrameLayout.LayoutParams(-1,-1));
  }
  private void call(String n){
    try{Method m=getContext().getClass().getDeclaredMethod(n);m.setAccessible(true);m.invoke(getContext());}catch(Throwable ignored){}
  }
  private void set(String n,Object v){
    try{Field f=getContext().getClass().getDeclaredField(n);f.setAccessible(true);f.set(getContext(),v);}catch(Throwable ignored){}
  }
  private String str(String n){
    try{Field f=getContext().getClass().getDeclaredField(n);f.setAccessible(true);Object v=f.get(getContext());return v==null?"":String.valueOf(v);}catch(Throwable e){return "";}
  }
  private long lng(String n){
    try{Field f=getContext().getClass().getDeclaredField(n);f.setAccessible(true);Object v=f.get(getContext());return v instanceof Number?((Number)v).longValue():0;}catch(Throwable e){return 0;}
  }

  private class HomeView extends View {
    Paint p=new Paint(3); float s=1f; int fee=500;
    int gold=Color.rgb(247,190,56), gold2=Color.rgb(255,224,122);
    int red=Color.rgb(225,62,71),green=Color.rgb(35,173,104),blue=Color.rgb(50,112,226),yellow=Color.rgb(242,183,40);
    Bitmap bgA,bgB;
    boolean bgBActive=false;
    long bgSwitchMs=0L;
    HomeView(Context c){
      super(c);fee=(int)lng("entryFee");if(fee==0)fee=500;setLayerType(LAYER_TYPE_HARDWARE,null);
      bgA=BitmapFactory.decodeResource(getResources(),R.drawable.bg_baobab);
      bgB=BitmapFactory.decodeResource(getResources(),R.drawable.bg_alt);
      p.setFilterBitmap(true);
    }
    void flipBackground(){
      bgBActive=!bgBActive;bgSwitchMs=System.currentTimeMillis();invalidate();
    }
    float d(float v){return v*getResources().getDisplayMetrics().density*s;}
    void t(Canvas c,String z,float x,float y,float size,int col,boolean b,boolean center){
      p.setShader(null);p.setStyle(Paint.Style.FILL);p.setColor(col);p.setTextSize(d(size));p.setTypeface(b?Typeface.DEFAULT_BOLD:Typeface.DEFAULT);
      p.setTextAlign(center?Paint.Align.CENTER:Paint.Align.LEFT);c.drawText(z,d(x),d(y),p);p.setTextAlign(Paint.Align.LEFT);
    }
    void box(Canvas c,float l,float y,float r,float b,float rad,int fill,int stroke){
      p.setShader(null);p.setStyle(Paint.Style.FILL);p.setColor(fill);c.drawRoundRect(d(l),d(y),d(r),d(b),d(rad),d(rad),p);
      if(stroke!=0){p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(d(1.2f));p.setColor(stroke);c.drawRoundRect(d(l),d(y),d(r),d(b),d(rad),d(rad),p);p.setStyle(Paint.Style.FILL);}
    }
    void pawn(Canvas c,float x,float y,float r,int col){
      p.setShadowLayer(d(4),0,d(2),0xAA000000);p.setShader(new RadialGradient(d(x-r*.35f),d(y-r*.45f),d(r*1.3f),Color.WHITE,col,Shader.TileMode.CLAMP));
      c.drawCircle(d(x),d(y),d(r),p);p.setShader(null);p.clearShadowLayer();p.setColor(0xBFFFFFFF);c.drawCircle(d(x-r*.28f),d(y-r*.34f),d(r*.18f),p);
    }
    void boardPawn(Canvas c,float x,float y,float r,int col){
      p.setShadowLayer(d(6),0,d(4),0xAA000000);
      Path q=new Path();
      q.moveTo(d(x-r*.9f),d(y+r*.65f));
      q.cubicTo(d(x-r*.72f),d(y+r*.95f),d(x+r*.72f),d(y+r*.95f),d(x+r*.9f),d(y+r*.65f));
      q.cubicTo(d(x+r*.55f),d(y+r*.35f),d(x+r*.46f),d(y+r*.15f),d(x+r*.38f),d(y-r*.05f));
      q.cubicTo(d(x+r*.68f),d(y-r*.28f),d(x+r*.55f),d(y-r*.8f),d(x),d(y-r*.92f));
      q.cubicTo(d(x-r*.55f),d(y-r*.8f),d(x-r*.68f),d(y-r*.28f),d(x-r*.38f),d(y-r*.05f));
      q.cubicTo(d(x-r*.46f),d(y+r*.15f),d(x-r*.55f),d(y+r*.35f),d(x-r*.9f),d(y+r*.65f));q.close();
      int hi=Color.rgb(Math.min(255,Color.red(col)+90),Math.min(255,Color.green(col)+90),Math.min(255,Color.blue(col)+90));
      p.setShader(new LinearGradient(d(x-r),d(y-r),d(x+r*.7f),d(y+r),hi,col,Shader.TileMode.CLAMP));
      c.drawPath(q,p);p.setShader(null);p.clearShadowLayer();
      p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(d(1));p.setColor(0x80FFFFFF);c.drawPath(q,p);p.setStyle(Paint.Style.FILL);
      p.setColor(0xBFFFFFFF);c.drawCircle(d(x-r*.25f),d(y-r*.48f),d(r*.16f),p);
    }
    void menu(Canvas c,float l,float y,float r,float b,int fill,int stroke,String icon,String title,String sub){
      p.setShader(null);p.setStyle(Paint.Style.FILL);p.setColor(fill);p.setShadowLayer(d(7),0,d(4),0x88000000);
      c.drawRoundRect(d(l),d(y),d(r),d(b),d(16),d(16),p);p.clearShadowLayer();
      p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(d(1.2f));p.setColor(stroke);c.drawRoundRect(d(l),d(y),d(r),d(b),d(16),d(16),p);p.setStyle(Paint.Style.FILL);
      p.setShader(new LinearGradient(d(l),d(y),d(l),d(b),0x55FFFFFF,0x00000000,Shader.TileMode.CLAMP));c.drawRoundRect(d(l+1),d(y+1),d(r-1),d((y+b)/2f),d(15),d(15),p);p.setShader(null);
      p.setShadowLayer(d(4),0,d(2),0x66000000);p.setShader(new LinearGradient(d(l+3),d(y+3),d(l+30),d(b-3),Color.WHITE,fill,Shader.TileMode.CLAMP));c.drawCircle(d(l+17),d((y+b)/2f),d(12.5f),p);p.setShader(null);p.clearShadowLayer();
      t(c,icon,l+17,(y+b)/2f+2,15,gold2,true,true);t(c,title,l+34,y+20,10.2f,Color.WHITE,true,false);t(c,sub,l+34,y+36,7.1f,0xE4FFFFFF,false,false);
    }
    void drawCover(Canvas c,Bitmap bm,float alpha,float W,float H){
      if(bm==null)return;
      p.setShader(null);p.setAlpha((int)(255*alpha));
      float bw=bm.getWidth(),bh=bm.getHeight(),scale=Math.max(W/bw,H/bh);
      float dw=bw*scale,dh=bh*scale,left=(W-dw)/2f,top=(H-dh)/2f;
      c.drawBitmap(bm,null,new RectF(left,top,left+dw,top+dh),p);p.setAlpha(255);
    }
    void background(Canvas c,float W,float H){
      float q=Math.min(1f,Math.max(0f,(System.currentTimeMillis()-bgSwitchMs)/1200f));
      if(bgBActive){drawCover(c,bgA,1f-q,W,H);drawCover(c,bgB,q,W,H);}
      else {drawCover(c,bgB,1f-q,W,H);drawCover(c,bgA,q,W,H);}
      p.setShader(new LinearGradient(0,0,0,H,0x55030D2E,0xB807102A,Shader.TileMode.CLAMP));c.drawRect(0,0,W,H,p);p.setShader(null);
      p.setShader(new RadialGradient(W*.5f,H*.38f,W*.55f,0x20FFF2B0,0x00000000,Shader.TileMode.CLAMP));c.drawCircle(W*.5f,H*.38f,W*.55f,p);p.setShader(null);
    }
    void board(Canvas c){
      float pulse=1f+(float)Math.sin(System.currentTimeMillis()/900.0)*.018f;
      c.save();c.scale(pulse,pulse,d(195),d(296));
      p.setShadowLayer(d(16),0,d(10),0x99000000);
      Path wood=new Path();wood.moveTo(d(101),d(218));wood.quadTo(d(101),d(208),d(112),d(204));wood.lineTo(d(278),d(204));wood.quadTo(d(289),d(208),d(289),d(218));wood.lineTo(d(283),d(385));wood.quadTo(d(282),d(395),d(270),d(399));wood.lineTo(d(120),d(399));wood.quadTo(d(108),d(395),d(107),d(385));wood.close();
      p.setShader(new LinearGradient(d(105),d(205),d(285),d(400),Color.rgb(118,61,23),Color.rgb(31,14,9),Shader.TileMode.CLAMP));p.setStyle(Paint.Style.FILL);c.drawPath(wood,p);p.setShader(null);p.clearShadowLayer();
      p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(d(1));p.setColor(0x443D1A09);
      for(int i=0;i<9;i++){float yy=224+i*18;c.drawLine(d(110+i*0.4f),d(yy),d(280-i*0.6f),d(yy+((i&1)==0?1:-1)),p);}
      p.setStyle(Paint.Style.FILL);
      box(c,110,214,280,388,22,Color.rgb(246,237,214),Color.rgb(244,191,67));
      p.setShader(new LinearGradient(d(112),d(216),d(278),d(388),Color.WHITE,Color.rgb(216,202,174),Shader.TileMode.CLAMP));c.drawRoundRect(d(112),d(216),d(278),d(388),d(22),d(22),p);p.setShader(null);
      float x0=116,y0=220,cs=40.5f;
      p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(d(1));p.setColor(0x907C6D58);
      for(int i=0;i<=4;i++){c.drawLine(d(x0+i*cs),d(y0),d(x0+i*cs),d(y0+162),p);c.drawLine(d(x0),d(y0+i*cs),d(x0+162),d(y0+i*cs),p);}p.setStyle(Paint.Style.FILL);
      p.setColor(red);c.drawRoundRect(d(x0),d(y0),d(x0+81),d(y0+81),d(10),d(10),p);
      p.setColor(blue);c.drawRoundRect(d(x0+81),d(y0),d(x0+162),d(y0+81),d(10),d(10),p);
      p.setColor(yellow);c.drawRoundRect(d(x0),d(y0+81),d(x0+81),d(y0+162),d(10),d(10),p);
      p.setColor(green);c.drawRoundRect(d(x0+81),d(y0+81),d(x0+162),d(y0+162),d(10),d(10),p);
      p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(d(2));p.setColor(0xAAFFFFFF);
      c.drawRoundRect(d(x0+8),d(y0+8),d(x0+73),d(y0+73),d(12),d(12),p);c.drawRoundRect(d(x0+89),d(y0+8),d(x0+154),d(y0+73),d(12),d(12),p);c.drawRoundRect(d(x0+8),d(y0+89),d(x0+73),d(y0+154),d(12),d(12),p);c.drawRoundRect(d(x0+89),d(y0+89),d(x0+154),d(y0+154),d(12),d(12),p);p.setStyle(Paint.Style.FILL);
      boardPawn(c,x0+40.5f,y0+46f,16.5f,red);boardPawn(c,x0+121.5f,y0+46f,16.5f,blue);boardPawn(c,x0+40.5f,y0+127f,16.5f,yellow);boardPawn(c,x0+121.5f,y0+127f,16.5f,green);
      Path cross=new Path();cross.moveTo(d(178),d(220));cross.lineTo(d(199),d(241));cross.lineTo(d(220),d(220));cross.lineTo(d(220),d(301));cross.lineTo(d(199),d(322));cross.lineTo(d(178),d(301));cross.close();p.setColor(Color.rgb(255,195,53));c.drawPath(cross,p);
      float spin=(System.currentTimeMillis()%2400L)/2400f*360f;c.save();c.rotate(spin,d(199),d(299));
      p.setShadowLayer(d(7),0,d(4),0xA0000000);p.setShader(new LinearGradient(d(179),d(276),d(219),d(322),Color.WHITE,Color.rgb(205,194,169),Shader.TileMode.CLAMP));c.drawRoundRect(d(177),d(274),d(221),d(320),d(9),d(9),p);p.setShader(null);p.clearShadowLayer();
      p.setColor(0xFF7C5B20);float[][]q={{-7,-9},{7,-9},{0,0},{-7,9},{7,9}};for(float[]v:q)c.drawCircle(d(199+v[0]),d(297+v[1]),d(2.6f),p);c.restore();
      p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(d(1.3f));p.setColor(0x99FFF1A6);c.drawRoundRect(d(102),d(218),d(278),d(388),d(22),d(22),p);p.setStyle(Paint.Style.FILL);
      c.restore();postInvalidateDelayed(16);
    }
    private int topInsetPx(){
      WindowInsets insets=getRootWindowInsets();
      return insets==null?0:Math.max(0,insets.getSystemWindowInsetTop());
    }
    private int bottomInsetPx(){
      WindowInsets insets=getRootWindowInsets();
      return insets==null?0:Math.max(0,insets.getSystemWindowInsetBottom());
    }
    @Override protected void onDraw(Canvas c){
      float den=getResources().getDisplayMetrics().density; int topInset=topInsetPx(),bottomInset=bottomInsetPx();
      float availableH=Math.max(1f,getHeight()-topInset-bottomInset); s=Math.min(getWidth()/(390f*den),availableH/(710f*den));
      float sc=s*den,ox=(getWidth()-390f*sc)/2f,oy=topInset+(availableH-710f*sc)/2f;
      c.save(); background(c,getWidth(),getHeight()); c.translate(ox,oy);
      // Clean premium hierarchy: header -> title -> hero board -> primary action -> quick actions -> stake -> nav.
      box(c,7,6,383,68,24,Color.rgb(8,22,62),Color.rgb(46,130,244));
      p.setShader(new RadialGradient(d(37),d(35),d(29),0x5548B7FF,0x00000000,Shader.TileMode.CLAMP));c.drawCircle(d(37),d(35),d(29),p);p.setShader(null);
      p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(d(2));p.setColor(gold2);c.drawCircle(d(37),d(37),d(23),p);p.setStyle(Paint.Style.FILL);pawn(c,37,38,10.5f,blue);
      String u=str("username"),tok=str("token"); long bal=lng("accountBalanceMGA"); String dn=u.isEmpty()?"Vahiny":(u.length()>13?u.substring(0,13)+"…":u);
      t(c,dn,67,31,12.5f,Color.WHITE,true,false); t(c,"LudoMada • Prêt à jouer",67,49,8.2f,0xD8FFFFFF,false,false);
      box(c,228,17,304,57,19,Color.rgb(10,38,91),Color.rgb(72,145,255));t(c,"C",241,42,12,gold2,true,true);t(c,bal>0?formatBal(bal):"0",277,42,9.5f,Color.WHITE,true,true);box(c,307,20,329,54,16,gold,0);t(c,"+",318,43,14,Color.rgb(70,38,3),true,true);
      t(c,"♪",345,43,16,Color.WHITE,true,true);t(c,"⚙",373,43,15,Color.WHITE,true,true);
      if(tok.isEmpty()){box(c,270,69,371,94,12,Color.rgb(28,95,202),gold);t(c,"CONNEXION",320,86,7.5f,Color.WHITE,true,true);}
      t(c,"LE JEU QUI RASSEMBLE !",195,88,8.5f,gold2,true,true);
      t(c,"LudoMada",195,121,31,Color.WHITE,true,true); t(c,"Joue • Gagne • Grandis",195,144,11.5f,gold2,true,true); t(c,"2 à 4 joueurs • Classique",195,160,7.8f,0xE2FFFFFF,false,true);
      // Hero board remains the visual focus and is never covered by side menus.
      board(c);
      box(c,58,405,332,457,25,Color.rgb(245,181,28),0);p.setShader(new LinearGradient(d(58),d(405),d(332),d(457),Color.rgb(255,225,113),Color.rgb(225,142,14),Shader.TileMode.CLAMP));c.drawRoundRect(d(58),d(405),d(332),d(457),d(25),d(25),p);p.setShader(null);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(d(1.4f));p.setColor(0xD9FFF2B0);c.drawRoundRect(d(58),d(405),d(332),d(457),d(25),d(25),p);p.setStyle(Paint.Style.FILL);t(c,"▶",105,438,18,Color.rgb(75,39,2),true,true);t(c,"JOUER",195,433,15,Color.rgb(70,36,2),true,true);t(c,"EN LIGNE OU HORS-LIGNE",195,448,7.2f,Color.rgb(88,50,8),true,true);
      // Four balanced quick actions.
      String[] ic={"＋","↗","◆","●"},title={"CRÉER","REJOINDRE","WALLET","PROFIL"};int[] colors={Color.rgb(22,91,194),Color.rgb(111,35,192),Color.rgb(8,139,91),Color.rgb(45,66,103)};
      for(int i=0;i<4;i++){float l=8+i*94.5f;box(c,l,470,l+88,526,15,colors[i],0);t(c,ic[i],l+18,497,16,gold2,true,true);t(c,title[i],l+53,497,7.8f,Color.WHITE,true,true);}
      box(c,8,539,382,603,17,Color.rgb(7,25,63),Color.rgb(40,119,231));t(c,"CHOISISSEZ VOTRE MISE",195,556,8.8f,gold2,true,true);
      int[]fs={500,1000,2000,5000,10000,20000};for(int i=0;i<6;i++){int f=fs[i];float l=11+i*61.3f;boolean a=f==fee;box(c,l,566,l+54,592,10,a?Color.rgb(255,193,29):Color.rgb(13,45,94),a?Color.WHITE:Color.rgb(52,129,237));t(c,f<1000?f+" Ar":(f/1000)+"K",l+27,583,7,a?Color.rgb(67,34,0):Color.WHITE,true,true);}
      t(c,"Madagasikara • Miray amin'ny LudoMada",195,620,7.8f,gold2,false,true);
      float by=638;box(c,6,by,384,by+66,23,Color.rgb(5,15,49),Color.rgb(39,112,230));String[]labs={"Accueil","Jeux","Événements","Profil"},icons={"⌂","🎲","♛","●"};for(int i=0;i<4;i++){float xx=53+i*95;boolean a=i==0;if(a)box(c,xx-31,by+5,xx+31,by+60,19,Color.rgb(26,23,67),gold);t(c,icons[i],xx,by+29,16,a?gold2:Color.WHITE,true,true);t(c,labs[i],xx,by+49,7.4f,a?gold2:0xE3FFFFFF,true,true);}
      c.restore();
    }
    private String formatBal(long v){return String.format(java.util.Locale.getDefault(),"%,d",v).replace(',',' ');}
    @Override public boolean onTouchEvent(MotionEvent e){
      if(e.getAction()!=MotionEvent.ACTION_UP)return true;
      float den=getResources().getDisplayMetrics().density;int topInset=topInsetPx(),bottomInset=bottomInsetPx();float availableH=Math.max(1f,getHeight()-topInset-bottomInset);float sc=s*den,ox=(getWidth()-390f*sc)/2f,oy=topInset+(availableH-710f*sc)/2f;float x=(e.getX()-ox)/sc,y=(e.getY()-oy)/sc;if(x<0||x>390||y<0||y>710)return true;String tok=str("token");
      if(y<68){if(x<210)call(tok.isEmpty()?"login":"profile");else if(x<335)call(tok.isEmpty()?"login":"wallet");else call("profile");return true;}
      if(y>=69&&y<98&&x>260){call("login");return true;}
      if(y>=395&&y<465){call("gameModeDialog");return true;}
      if(y>=468&&y<532){int i=(int)(x/94.5f);if(i==0)call("createGame");else if(i==1)call("joinDialog");else if(i==2)call("wallet");else call("profile");return true;}
      if(y>=538&&y<610){int[]fs={500,1000,2000,5000,10000,20000};int i=(int)((x-11)/61.3f);i=Math.max(0,Math.min(5,i));fee=fs[i];set("entryFee",fee);invalidate();return true;}
      if(y>=636&&y<=708){int i=(int)(x/95f);if(i==0)call("home");else if(i==1)call("gameModeDialog");else if(i==2)call("events");else call("profile");return true;}
      return true;
    }
  }
}
