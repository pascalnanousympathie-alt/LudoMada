package com.ludomadagascar;

import android.app.Activity;
import android.content.Context;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class PremiumHeroViewV2 extends View {
  public PremiumHeroViewV2(Context c){super(c);setVisibility(INVISIBLE);}
  @Override protected void onAttachedToWindow(){super.onAttachedToWindow();postDelayed(()->install(),80);}
  private void install(){
    if(!(getParent() instanceof ViewGroup))return;
    Activity a=(Activity)getContext(); ViewGroup host=(ViewGroup)getParent();
    host.removeAllViews();
    FrameLayout f=new FrameLayout(a); f.setBackgroundColor(Color.rgb(7,8,37));
    host.addView(f,new ViewGroup.LayoutParams(-1,-1));
    f.addView(new HomeView(a),new FrameLayout.LayoutParams(-1,-1));
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
    HomeView(Context c){super(c);fee=(int)lng("entryFee");if(fee==0)fee=500;setLayerType(LAYER_TYPE_SOFTWARE,null);}
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
    void board(Canvas c){
      box(c,92,330,298,448,18,Color.rgb(238,227,201),Color.rgb(198,155,64));
      p.setColor(red);c.drawRect(d(100),d(338),d(195),d(389),p);p.setColor(blue);c.drawRect(d(195),d(338),d(290),d(389),p);
      p.setColor(green);c.drawRect(d(195),d(389),d(290),d(440),p);p.setColor(yellow);c.drawRect(d(100),d(389),d(195),d(440),p);
      p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(d(1));p.setColor(0xAAFFFFFF);
      for(int i=0;i<=6;i++)c.drawLine(d(100+i*31.7f),d(338),d(100+i*31.7f),d(440),p);
      for(int i=0;i<=5;i++)c.drawLine(d(100),d(338+i*20.4f),d(290),d(338+i*20.4f),p);p.setStyle(Paint.Style.FILL);
      pawn(c,137,362,18,red);pawn(c,253,362,18,green);pawn(c,138,414,18,blue);pawn(c,252,414,18,yellow);
      box(c,172,360,218,402,9,Color.rgb(250,249,244),Color.rgb(140,132,121));p.setColor(Color.rgb(40,36,36));
      float[][]q={{-7,-7},{0,0},{7,7},{-7,7},{7,-7}};for(float[]v:q)c.drawCircle(d(195+v[0]),d(381+v[1]),d(2.4f),p);
    }
    @Override protected void onDraw(Canvas c){
      s=getWidth()/(390f*getResources().getDisplayMetrics().density);
      p.setShader(new LinearGradient(0,0,0,getHeight(),Color.rgb(7,7,38),Color.rgb(43,18,91),Shader.TileMode.CLAMP));c.drawRect(0,0,getWidth(),getHeight(),p);
      p.setShader(new RadialGradient(d(195),d(290),d(285),Color.rgb(96,42,173),Color.TRANSPARENT,Shader.TileMode.CLAMP));c.drawCircle(d(195),d(290),d(285),p);p.setShader(null);
      p.setColor(0x28FFFFFF);for(int i=0;i<18;i++)c.drawCircle(d((i*47)%382),d(82+(i*51)%650),d(i%4==0?1.5f:.8f),p);
      String u=str("username"),tok=str("token");
      box(c,10,8,380,76,23,Color.rgb(19,19,67),Color.rgb(70,113,232));p.setColor(0x246EBBFF);c.drawCircle(d(45),d(42),d(25),p);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(d(2));p.setColor(gold);c.drawCircle(d(45),d(42),d(24),p);p.setStyle(Paint.Style.FILL);pawn(c,45,43,11,blue);
      t(c,u.isEmpty()?"INVITÉ":u,73,35,15,Color.WHITE,true,false);t(c,tok.isEmpty()?"Connecte-toi pour jouer":"Mandeha miaraka",73,54,9.5f,0xD2FFFFFF,false,false);
      if(tok.isEmpty()){box(c,282,22,369,61,18,Color.rgb(28,93,199),gold);t(c,"CONNEXION",325,47,10,Color.WHITE,true,true);}
      else{box(c,244,19,329,57,18,Color.rgb(10,34,87),blue);t(c,"SOLDE",286,32,8,Color.WHITE,true,true);long bal=lng("accountBalanceMGA");t(c,(bal>0?bal:"—")+" Ar",286,49,10.5f,gold2,true,true);box(c,337,19,370,57,17,Color.rgb(25,50,112),gold);t(c,"⚙",353,45,18,Color.WHITE,true,true);}
      t(c,"LE JEU QUI RASSEMBLE !",195,94,9,gold2,true,true);t(c,"Ludo",150,144,45,gold2,true,true);t(c,"Mada",248,144,45,Color.WHITE,true,true);t(c,"♛",195,112,25,gold,true,true);
      box(c,143,151,248,156,2,red,0);box(c,143,156,248,161,1,Color.WHITE,0);box(c,143,161,248,166,2,green,0);t(c,"Joue  •  Gagne  •  Grandis",195,187,14,gold2,false,true);t(c,"Malagasy Style",195,207,10,0xD9FFFFFF,false,true);
      box(c,14,216,187,263,16,Color.rgb(42,24,89),Color.rgb(180,91,255));t(c,"♜",31,247,20,gold2,true,true);t(c,"CLASSEMENT",50,238,10.5f,Color.WHITE,true,false);t(c,"Les meilleurs joueurs",50,253,8.5f,0xD2FFFFFF,false,false);
      box(c,203,216,376,263,16,Color.rgb(59,24,90),gold);t(c,"♛",220,247,20,gold2,true,true);t(c,"VIP",239,238,10.5f,Color.WHITE,true,false);t(c,"Avantages exclusifs",239,253,8.5f,0xD2FFFFFF,false,false);
      p.setShader(new LinearGradient(d(14),d(278),d(376),d(525),Color.rgb(37,82,173),Color.rgb(75,26,105),Shader.TileMode.CLAMP));c.drawRoundRect(d(14),d(278),d(376),d(525),d(28),d(28),p);p.setShader(null);
      t(c,"2 – 4 JOUEURS",195,302,13,gold2,true,true);t(c,"Classique • Rapide • Amical",195,317,8.5f,Color.WHITE,false,true);
      p.setColor(0x1D8BDDFF);c.drawCircle(d(62),d(374),d(70),p);p.setColor(0x1932D6A0);c.drawCircle(d(325),d(374),d(83),p);board(c);
      box(c,17,430,91,484,17,Color.rgb(20,31,82),gold);t(c,"◉",54,457,22,gold2,true,true);t(c,"SPIN",54,476,8,Color.WHITE,true,true);
      box(c,299,430,373,484,17,Color.rgb(34,21,80),Color.rgb(235,76,101));t(c,"🎁",336,457,20,gold2,true,true);t(c,"BONUS",336,476,8,Color.WHITE,true,true);
      box(c,96,484,294,520,19,gold2,Color.WHITE);t(c,"▶  JOUER",195,508,18,Color.rgb(61,37,7),true,true);
      t(c,"♛  CHOISISSEZ VOTRE MISE",195,549,12,gold2,true,true);
      int[]fs={500,1000,2000,5000,10000,20000};for(int i=0;i<6;i++){int f=fs[i];float l=8+i*63.7f;boolean a=f==fee;box(c,l,560,l+59,600,15,a?Color.rgb(55,35,91):Color.rgb(13,28,67),a?gold:Color.rgb(54,105,210));t(c,f<1000?f+" Ar":(f/1000)+" K Ar",l+29.5f,585,8.2f,a?gold2:Color.WHITE,true,true);}
      String[]n={"ROOM PRIVÉE","CLASSEMENT","ANTI-TRICHE","SUPPORT"},sub={"Crée ta partie","Top joueurs","Jeu équitable","Assistance 24/7"},ic={"🎲","♛","✓","◉"};
      for(int i=0;i<4;i++){float l=10+i*95;box(c,l,615,l+89,690,18,Color.rgb(18,27,72),Color.rgb(53,111,224));t(c,ic[i],l+44.5f,644,19,i==2?green:gold2,true,true);t(c,n[i],l+44.5f,666,7.3f,Color.WHITE,true,true);t(c,sub[i],l+44.5f,681,6.7f,0xD1FFFFFF,false,true);}
      float by=getHeight()/getResources().getDisplayMetrics().density/s-76;box(c,9,by,381,by+68,23,Color.rgb(7,10,45),gold);String[]labs={"Accueil","Événements","Lobby","Social","Boutique"},icons={"⌂","▣","🎲","♟","🛒"};
      for(int i=0;i<5;i++){float xx=48+i*73.5f;if(i==0)box(c,xx-29,by+5,xx+29,by+63,20,Color.rgb(27,24,74),gold);t(c,icons[i],xx,by+31,17,i==0?gold2:Color.WHITE,true,true);t(c,labs[i],xx,by+52,7.8f,i==0?gold2:0xDCE6EFFF,true,true);}
    }
    @Override public boolean onTouchEvent(MotionEvent e){
      if(e.getAction()!=MotionEvent.ACTION_UP)return true;float den=getResources().getDisplayMetrics().density,x=e.getX()/den/s,y=e.getY()/den/s;String tok=str("token");
      if(y<68&&x>275){if(tok.isEmpty())call("login");else call(x<332?"wallet":"profile");return true;}
      if(y>278&&y<525&&x>90&&x<300){call("lobby");return true;}
      if(y>555&&y<606){int[]fs={500,1000,2000,5000,10000,20000};int i=(int)((x-8)/63.7f);i=Math.max(0,Math.min(5,i));fee=fs[i];set("entryFee",fee);invalidate();return true;}
      if(y>610&&y<700){int i=(int)(x/95);if(i==0)call("joinDialog");else if(i==1)call("events");else if(i==2)call("tutorials");else call("social");return true;}
      return true;
    }
  }
}
