package com.ludomadagascar;

import android.app.Activity;
import android.content.Context;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class PremiumHeroViewV2 extends View {
  private final HomeView home;
  public PremiumHeroViewV2(Context c){super(c);setLayerType(LAYER_TYPE_SOFTWARE,null);home=new HomeView(c);}
  @Override protected void onAttachedToWindow(){super.onAttachedToWindow();invalidate();}
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
    void menu(Canvas c,float l,float y,float r,float b,int fill,int stroke,String icon,String title,String sub){
      box(c,l,y,r,b,16,fill,stroke);t(c,icon,l+17,(y+b)/2f+2,18,gold2,true,true);t(c,title,l+34,y+21,10.5f,Color.WHITE,true,false);t(c,sub,l+34,y+36,7.5f,0xD8FFFFFF,false,false);
    }
    void board(Canvas c){
      box(c,122,214,268,392,25,Color.rgb(243,235,216),Color.rgb(208,166,68));
      p.setShader(new LinearGradient(d(122),d(214),d(268),d(392),Color.rgb(255,255,255),Color.rgb(231,222,203),Shader.TileMode.CLAMP));c.drawRoundRect(d(122),d(214),d(268),d(392),d(25),d(25),p);p.setShader(null);
      float x0=129,y0=221,cs=34.5f;
      p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(d(0.7f));p.setColor(0x88999999);
      for(int i=0;i<=4;i++){c.drawLine(d(x0+i*cs),d(y0),d(x0+i*cs),d(y0+138),p);c.drawLine(d(x0),d(y0+i*cs),d(x0+138),d(y0+i*cs),p);}p.setStyle(Paint.Style.FILL);
      p.setColor(red);c.drawRect(d(x0),d(y0),d(x0+69),d(y0+69),p);p.setColor(blue);c.drawRect(d(x0+69),d(y0),d(x0+138),d(y0+69),p);p.setColor(green);c.drawRect(d(x0+69),d(y0+69),d(x0+138),d(y0+138),p);p.setColor(yellow);c.drawRect(d(x0),d(y0+69),d(x0+69),d(y0+138),p);
      pawn(c,x0+34.5f,y0+34.5f,17,red);pawn(c,x0+103.5f,y0+34.5f,17,blue);pawn(c,x0+34.5f,y0+103.5f,17,yellow);pawn(c,x0+103.5f,y0+103.5f,17,green);
      float phase=(System.currentTimeMillis()%2200L)/2200f; float angle=(float)Math.sin(phase*Math.PI*2.0)*7.0f; c.save();c.rotate(angle,d(200),d(297));
      box(c,176,273,224,321,10,Color.WHITE,Color.rgb(132,127,119));p.setColor(Color.DKGRAY);float[][]q={{-7,-9},{7,-9},{0,0},{-7,9},{7,9}};for(float[]v:q)c.drawCircle(d(200+v[0]),d(297+v[1]),d(2.5f),p);c.restore();postInvalidateDelayed(50);
    }
    private int bottomInsetPx(){
      WindowInsets insets=getRootWindowInsets();
      return insets==null?0:Math.max(0,insets.getSystemWindowInsetBottom());
    }
    @Override protected void onDraw(Canvas c){
      float den=getResources().getDisplayMetrics().density;
      int bottomInset=bottomInsetPx();
      float availableH=Math.max(1f,getHeight()-bottomInset);
      s=Math.min(getWidth()/(390f*den),availableH/(710f*den));
      float sc=s*den, ox=(getWidth()-390f*sc)/2f, oy=(availableH-710f*sc)/2f;
      c.save();c.translate(ox,oy);
      p.setShader(new LinearGradient(0,0,0,d(710),Color.rgb(3,16,56),Color.rgb(74,20,78),Shader.TileMode.CLAMP));c.drawRect(0,0,d(390),d(710),p);
      p.setShader(new RadialGradient(d(195),d(255),d(280),Color.rgb(22,112,226),Color.TRANSPARENT,Shader.TileMode.CLAMP));c.drawCircle(d(195),d(255),d(280),p);p.setShader(null);
      p.setColor(0x223C8FE8);c.drawCircle(d(77),d(190),d(88),p);p.setColor(0x2848D5B1);c.drawCircle(d(329),d(246),d(105),p);
      Path m=new Path();m.moveTo(d(0),d(318));m.lineTo(d(62),d(250));m.lineTo(d(104),d(301));m.lineTo(d(151),d(252));m.lineTo(d(207),d(311));m.lineTo(d(271),d(247));m.lineTo(d(325),d(300));m.lineTo(d(390),d(242));m.lineTo(d(390),d(365));m.lineTo(d(0),d(365));m.close();p.setColor(0x55224B8D);c.drawPath(m,p);
      p.setShader(new RadialGradient(d(55),d(555),d(42),Color.rgb(255,205,88),0x00FFC95C,Shader.TileMode.CLAMP));c.drawCircle(d(55),d(555),d(42),p);p.setShader(null);
      p.setColor(0x5532141E);c.drawRect(d(37),d(548),d(44),d(640),p);c.drawCircle(d(28),d(545),d(18),p);c.drawCircle(d(47),d(540),d(22),p);c.drawCircle(d(15),d(548),d(15),p);
      p.setColor(0x48201418);c.drawRect(d(354),d(560),d(361),d(640),p);c.drawCircle(d(343),d(555),d(20),p);c.drawCircle(d(364),d(550),d(24),p);c.drawCircle(d(379),d(558),d(16),p);
      String u=str("username"),tok=str("token");long bal=lng("accountBalanceMGA");
      box(c,7,6,383,68,24,Color.rgb(9,25,71),Color.rgb(37,116,236));
      p.setColor(0x223D9DFF);c.drawCircle(d(37),d(37),d(27),p);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(d(2));p.setColor(gold2);c.drawCircle(d(37),d(37),d(25),p);p.setStyle(Paint.Style.FILL);pawn(c,37,38,11,blue);
      p.setColor(Color.WHITE);c.drawRect(d(59),d(26),d(78),d(36),p);p.setColor(Color.rgb(239,63,75));c.drawRect(d(59),d(26),d(78),d(31),p);p.setColor(Color.rgb(37,166,105));c.drawRect(d(59),d(31),d(78),d(36),p);
      t(c,u.isEmpty()?"Player_Mada":u,84,31,12.5f,Color.WHITE,true,false);t(c,"Joueur LudoMada",84,50,8.5f,0xE1FFFFFF,false,false);
      box(c,84,55,145,63,4,Color.rgb(5,18,48),Color.rgb(37,71,126));box(c,85,56,121,62,3,Color.rgb(34,221,112),0);
      box(c,151,17,222,57,19,Color.rgb(10,35,84),Color.rgb(69,139,255));t(c,"C",164,43,13,gold2,true,true);t(c,bal>0?formatBal(bal):"0",201,42,10,Color.WHITE,true,true);box(c,224,20,246,54,16,gold,0);t(c,"+",235,44,15,Color.rgb(75,42,4),true,true);
      box(c,250,17,309,57,19,Color.rgb(8,35,83),Color.rgb(55,151,239));t(c,"◆",263,43,16,Color.rgb(49,205,255),true,true);t(c,"—",291,42,10,Color.WHITE,true,true);box(c,311,20,327,54,14,gold,0);t(c,"+",319,43,12,Color.rgb(75,42,4),true,true);
      t(c,"♪",337,43,16,Color.WHITE,true,true);t(c,"◖",350,43,14,Color.WHITE,true,true);t(c,"FR",362,43,7.5f,Color.WHITE,true,true);t(c,"⚙",376,43,15,Color.WHITE,true,true);
      if(tok.isEmpty()){box(c,279,69,371,92,11,Color.rgb(28,95,202),gold);t(c,"CONNEXION",325,85,7.5f,Color.WHITE,true,true);}
      t(c,"LE JEU QUI RASSEMBLE !",195,103,8.5f,gold2,true,true);
      t(c,"♛",195,125,27,gold,true,true);t(c,"Ludo",161,151,38,gold2,true,true);t(c,"Mada",226,151,38,Color.WHITE,true,true);
      box(c,143,160,247,164,1.5f,red,0);box(c,143,164,247,168,1,Color.WHITE,0);box(c,143,168,247,172,1.5f,green,0);
      t(c,"Joue • Gagne • Grandis",195,187,12.5f,gold2,false,true);t(c,"2 à 4 joueurs  •  Classique",195,202,7.7f,0xE2FFFFFF,false,true);
      board(c);
      menu(c,8,110,126,158,Color.rgb(244,174,25),gold,"▶","JOUER","En ligne ou Hors-ligne");
      menu(c,8,164,126,212,Color.rgb(22,72,185),Color.rgb(45,135,255),"♛","TOURNOIS","Événements & classements");
      menu(c,8,218,126,266,Color.rgb(12,147,87),Color.rgb(29,223,137),"▣","MON WALLET","Dépôt / Retrait / Historique");
      menu(c,8,272,126,320,Color.rgb(102,30,202),Color.rgb(195,81,255),"●","PROFIL","Infos • Paramètres");
      menu(c,8,326,126,374,Color.rgb(12,131,183),Color.rgb(33,215,246),"▤","TUTORIELS","Apprends à jouer");
      box(c,266,107,382,174,17,Color.rgb(60,10,69),Color.rgb(255,180,40));t(c,"BONUS DE BIENVENUE",324,125,9.5f,Color.WHITE,true,true);t(c,"Bonus à venir",324,145,11,gold2,true,true);t(c,"Offre future — aucun crédit fictif",324,158,6.6f,0xD8FFFFFF,false,true);box(c,286,147,363,169,11,Color.rgb(255,202,28),gold2);t(c,"BIENTÔT",324,162,8.5f,Color.rgb(75,35,2),true,true);
      box(c,266,181,382,232,16,Color.rgb(8,34,79),Color.rgb(38,126,247));t(c,"MISE ACTUELLE",324,198,7.6f,Color.WHITE,true,true);t(c,fee+" Ar",324,219,11,gold2,true,true);t(c,"⌄",369,219,14,Color.WHITE,true,true);
      box(c,266,239,382,352,17,Color.rgb(8,34,79),Color.rgb(38,126,247));t(c,"MODE DE JEU",324,257,8,Color.WHITE,true,true);
      box(c,275,264,373,307,15,Color.rgb(24,105,223),Color.rgb(61,160,255));t(c,"◎",291,289,15,Color.WHITE,true,true);t(c,"En ligne",319,283,10,Color.WHITE,true,true);t(c,"Joueurs réels  ›",326,298,7.2f,0xD7FFFFFF,false,true);
      box(c,275,311,373,346,15,Color.rgb(126,40,226),Color.rgb(191,89,255));t(c,"♟",291,333,15,Color.WHITE,true,true);t(c,"Hors-ligne",323,327,9.5f,Color.WHITE,true,true);t(c,"2 à 4 joueurs  ›",326,340,7.1f,0xD7FFFFFF,false,true);
      box(c,7,397,383,447,18,Color.rgb(8,20,59),Color.rgb(33,111,225));t(c,"♛  CHOISISSEZ VOTRE MISE",195,414,9.7f,gold2,true,true);
      int[]fs={500,1000,2000,5000,10000,20000};for(int i=0;i<6;i++){int f=fs[i];float l=10+i*61.5f;boolean a=f==fee;box(c,l,420,l+55,443,9,a?Color.rgb(255,193,29):Color.rgb(13,37,83),a?Color.WHITE:Color.rgb(40,118,230));t(c,f<1000?f+" Ar":(f/1000)+"K Ar",l+27.5f,436,7,a?Color.rgb(74,35,0):Color.WHITE,true,true);}
      String[]n={"ROOM PRIVÉE","CLASSEMENT","ANTI-TRICHE","SUPPORT"},sub={"Crée ta partie","Top joueurs","Jeu équitable","Assistance 24/7"},ic={"🎲","♛","✓","◉"};
      for(int i=0;i<4;i++){float l=8+i*94.5f;box(c,l,466,l+88,535,16,Color.rgb(7,31,72),Color.rgb(39,115,230));t(c,ic[i],l+44,491,17,i==2?green:gold2,true,true);t(c,n[i],l+44,510,6.9f,Color.WHITE,true,true);t(c,sub[i],l+44,526,6.3f,0xD6FFFFFF,false,true);}
      t(c,"Madagasikara  •  Miray amin’ny LudoMada",195,577,8.5f,gold2,false,true);
      float by=640;box(c,6,by,384,by+65,23,Color.rgb(5,15,49),Color.rgb(39,112,230));
      String[]labs={"Accueil","Jeux","Événements","Social","Boutique"},icons={"⌂","🎲","♛","♟","🛒"};for(int i=0;i<5;i++){float xx=43+i*76;boolean a=i==0;if(a)box(c,xx-31,by+5,xx+31,by+60,19,Color.rgb(26,23,67),gold);t(c,icons[i],xx,by+29,16,a?gold2:Color.WHITE,true,true);t(c,labs[i],xx,by+49,7.4f,a?gold2:0xE3FFFFFF,true,true);}
      c.restore();
    }
    private String formatBal(long v){return String.format(java.util.Locale.getDefault(),"%,d",v).replace(',',' ');}
    @Override public boolean onTouchEvent(MotionEvent e){
      if(e.getAction()!=MotionEvent.ACTION_UP)return true;
      float den=getResources().getDisplayMetrics().density;
      float availableH=Math.max(1f,getHeight()-bottomInsetPx());
      float sc=s*den,ox=(getWidth()-390f*sc)/2f,oy=(availableH-710f*sc)/2f;
      float x=(e.getX()-ox)/sc,y=(e.getY()-oy)/sc;String tok=str("token");
      if(x<0||x>390||y<0||y>710)return true;
      if(y<68){
        if(x<170)call(tok.isEmpty()?"login":"profile"); else if(x<273)call(tok.isEmpty()?"login":"wallet"); else call("profile");
        return true;
      }
      if(y>=69&&y<95&&x>270){call("login");return true;}
      if(y>=145&&y<176&&x>270){call("wallet");return true;}
      if(y>=108&&y<159&&x<130){call("gameModeDialog");return true;}
      if(y>=164&&y<213&&x<130){call("events");return true;}
      if(y>=218&&y<267&&x<130){call("wallet");return true;}
      if(y>=272&&y<321&&x<130){call("profile");return true;}
      if(y>=326&&y<376&&x<130){call("tutorials");return true;}
      if(y>=264&&y<309&&x>270){call("lobby");return true;}
      if(y>=311&&y<350&&x>270){call("offlineMenu");return true;}
      if(y>=300&&y<395&&x>=115&&x<=275){call("gameModeDialog");return true;}
      if(y>=397&&y<448){
        int[]fs={500,1000,2000,5000,10000,20000};int i=(int)((x-10)/61.5f);i=Math.max(0,Math.min(5,i));fee=fs[i];set("entryFee",fee);invalidate();return true;
      }
      if(y>=466&&y<541){
        int i=(int)(x/94.5f);if(i==0)call("joinDialog");else if(i==1)call("events");else if(i==2)call("tutorials");else call("social");return true;
      }
      float by=640;
      if(y>=by&&y<=by+66){
        int i=(int)(x/76f);if(i==0)call("home");else if(i==1)call("gameModeDialog");else if(i==2)call("events");else if(i==3)call("social");else call("shop");return true;
      }
      return true;
    }
  }
}
