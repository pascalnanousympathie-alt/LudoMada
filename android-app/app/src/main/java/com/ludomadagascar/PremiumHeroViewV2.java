package com.ludomadagascar;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class PremiumHeroViewV2 extends FrameLayout {
  private static final String HOME_ACCESSIBILITY_BASE =
      "LudoMada Premium Home | JOUER | TOURNOIS | MON WALLET | PROFIL | TUTORIELS | MODE DE JEU | EN LIGNE | HORS-LIGNE";
  private final HomeView home;
  private final LudoBoardView homePreview;

  public PremiumHeroViewV2(Context c){
    super(c);
    setClipChildren(false);
    setClipToPadding(false);
    setBackground(new GradientDrawable(
      GradientDrawable.Orientation.TL_BR,
      new int[]{Color.rgb(3,16,56),Color.rgb(74,20,78)}
    ));
    setContentDescription("LudoMada Premium Home | LudoMada 3D Home Preview | Player profile | coins | gems | Joue. Gagne. Grandis | En ligne | Hors-ligne | Wallet");
    // The real GLES scene sits behind the transparent native UI overlay. It owns
    // the rev5 board, GLB pawns/dice, outdoor Madagascar environment and baobabs.
    homePreview=new LudoBoardView(c,null,null,true);
    homePreview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
    addView(homePreview,new FrameLayout.LayoutParams(-1,-1));
    home=new HomeView(c);
    home.setBackgroundColor(Color.TRANSPARENT);
    home.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
    home.setFocusable(true);
    home.setClickable(true);
    home.setContentDescription(HOME_ACCESSIBILITY_BASE);
    addView(home,new FrameLayout.LayoutParams(-1,-1));
  }

  public LudoBoardView getHomePreviewView(){ return homePreview; }

  /**
   * The Home overlay is the foreground accessibility node and can mask its GL child.
   * Mirror the renderer's certified readiness marker onto that visible Home node so
   * UI automation can verify the real GLES frame without relying on logcat.
   */
  public void publishHomePreviewReady(String rendererMarker){
    if(rendererMarker==null || rendererMarker.trim().isEmpty()) return;
    final String description = HOME_ACCESSIBILITY_BASE + " | " + rendererMarker;
    home.post(() -> {
      if(!description.equals(home.getContentDescription())){
        home.setContentDescription(description);
      }
    });
  }

  public void resetHomePreviewReady(){
    home.post(() -> {
      if(!HOME_ACCESSIBILITY_BASE.equals(home.getContentDescription())){
        home.setContentDescription(HOME_ACCESSIBILITY_BASE);
      }
    });
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

  private boolean bool(String n){
    try{Method m=getContext().getClass().getDeclaredMethod(n);m.setAccessible(true);return Boolean.TRUE.equals(m.invoke(getContext()));}
    catch(Throwable e){return false;}
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
    @Override protected void onDraw(Canvas c){
      float den=getResources().getDisplayMetrics().density;
      s=Math.min(getWidth()/(390f*den),getHeight()/(710f*den));
      float sc=s*den, ox=(getWidth()-390f*sc)/2f, oy=(getHeight()-710f*sc)/2f;
      c.save();c.translate(ox,oy);
      // Keep the live GLES scene as the visual hero. Native UI uses only compact,
      // translucent controls and deliberately leaves the central board sightline open.
      p.setShader(null);
      String u=str("username"),tok=str("token");long bal=lng("accountBalanceMGA");

      // Compact glass identity / wallet bar.
      box(c,7,6,383,68,24,0xE6091947,Color.rgb(37,116,236));
      p.setColor(0x223D9DFF);c.drawCircle(d(37),d(37),d(27),p);
      p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(d(2));p.setColor(gold2);
      c.drawCircle(d(37),d(37),d(25),p);p.setStyle(Paint.Style.FILL);
      pawn(c,37,38,11,blue);
      p.setColor(Color.WHITE);c.drawRect(d(59),d(26),d(78),d(36),p);
      p.setColor(Color.rgb(239,63,75));c.drawRect(d(59),d(26),d(78),d(31),p);
      p.setColor(Color.rgb(37,166,105));c.drawRect(d(59),d(31),d(78),d(36),p);
      String displayName=u.trim().isEmpty()?"Player Mada":u.trim();
      if(displayName.length()>9)displayName=displayName.substring(0,8)+"…";
      t(c,displayName,84,31,10.5f,Color.WHITE,true,false);
      t(c,"Joueur LudoMada",84,50,8.0f,0xE1FFFFFF,false,false);
      box(c,151,17,222,57,19,0xEE0A2354,Color.rgb(69,139,255));
      t(c,"C",164,43,13,gold2,true,true);
      t(c,bal>0?formatBal(bal):"0",201,42,10,Color.WHITE,true,true);
      box(c,224,20,246,54,16,gold,0);t(c,"+",235,44,15,Color.rgb(75,42,4),true,true);
      box(c,250,17,309,57,19,0xEE082353,Color.rgb(55,151,239));
      t(c,"◆",263,43,16,Color.rgb(49,205,255),true,true);
      t(c,"—",291,42,10,Color.WHITE,true,true);
      box(c,311,20,327,54,14,gold,0);t(c,"+",319,43,12,Color.rgb(75,42,4),true,true);
      t(c,"♪",337,43,16,bool("musicEnabledFromHome")?Color.WHITE:0xFF7D8799,true,true);
      t(c,"◖",350,43,14,Color.WHITE,true,true);
      t(c,"FR",362,43,7.5f,Color.WHITE,true,true);
      t(c,"⚙",376,43,15,Color.WHITE,true,true);
      if(tok.isEmpty()){
        box(c,279,69,371,92,11,0xEF1C5FC8,gold);
        t(c,"CONNEXION",325,85,7.5f,Color.WHITE,true,true);
      }

      // Small brand label only; no central logo card or side panels over the 3D board.
      box(c,92,82,298,112,15,0x88071935,0x55D8B86D);
      t(c,"LUDOMADA  •  MADAGASCAR",195,95,8.5f,gold2,true,true);
      t(c,"Le Ludo qui rassemble",195,106,7.4f,0xE8FFFFFF,false,true);

      // The 3D environment and board remain unobstructed from y=113 to y=438.

      // One clear primary action replaces the former five large left menus and right mode panel.
      box(c,48,441,342,495,20,0xF2F2B536,gold2);
      t(c,"JOUER",195,464,16,Color.rgb(62,34,5),true,true);
      t(c,"EN LIGNE  •  HORS-LIGNE",195,482,8.2f,Color.rgb(80,47,9),true,true);

      // Compact stake selector: preserve all six MGA amounts without covering the board.
      box(c,7,505,383,573,18,0xE6091947,Color.rgb(33,111,225));
      t(c,"CHOISISSEZ VOTRE MISE",195,521,8.8f,gold2,true,true);
      int[]fs={500,1000,2000,5000,10000,20000};
      for(int i=0;i<6;i++){
        int f=fs[i];float l=10+i*61.5f;boolean a=f==fee;
        box(c,l,531,l+55,559,10,
            a?Color.rgb(255,193,29):0xD90D2A5B,
            a?Color.WHITE:Color.rgb(40,118,230));
        t(c,f<1000?f+" Ar":(f/1000)+"K Ar",l+27.5f,549,7,
            a?Color.rgb(74,35,0):Color.WHITE,true,true);
      }

      // Secondary destinations are kept low and visually quiet.
      String[]n={"ROOM","RANG","GUIDE","SOCIAL"};
      String[]sub={"Privée","Joueurs","Tutoriel","Amis"};
      String[]ic={"🎲","♛","✓","◉"};
      for(int i=0;i<4;i++){
        float l=8+i*94.5f;
        box(c,l,582,l+88,626,13,0xB8071F48,0x883977C5);
        t(c,ic[i],l+15,609,13,i==2?green:gold2,true,true);
        t(c,n[i],l+53,601,6.9f,Color.WHITE,true,true);
        t(c,sub[i],l+53,615,6.3f,0xD6FFFFFF,false,true);
      }

      t(c,"Madagasikara  •  Miray amin’ny LudoMada",195,635,7.4f,gold2,false,true);
      float by=640;
      box(c,6,by,384,by+65,23,0xF2050F31,Color.rgb(39,112,230));
      String[]labs={"Accueil","Jeux","Événements","Social","Boutique"};
      String[]icons={"⌂","🎲","♛","♟","🛒"};
      for(int i=0;i<5;i++){
        float xx=43+i*76;boolean a=i==0;
        if(a)box(c,xx-31,by+5,xx+31,by+60,19,0xF21A1743,gold);
        t(c,icons[i],xx,by+29,16,a?gold2:Color.WHITE,true,true);
        t(c,labs[i],xx,by+49,7.4f,a?gold2:0xE3FFFFFF,true,true);
      }
      c.restore();
    }
    private String formatBal(long v){return String.format(java.util.Locale.getDefault(),"%,d",v).replace(',',' ');}
    @Override public boolean onTouchEvent(MotionEvent e){
      if(e.getAction()!=MotionEvent.ACTION_UP)return true;
      float den=getResources().getDisplayMetrics().density,sc=s*den,
          ox=(getWidth()-390f*sc)/2f,oy=(getHeight()-710f*sc)/2f;
      float x=(e.getX()-ox)/sc,y=(e.getY()-oy)/sc;
      String tok=str("token");
      if(x<0||x>390||y<0||y>710)return true;

      if(y<68){
        if(x>=328&&x<346){call("toggleMusicFromHome");invalidate();return true;}
        if(x<170)call(tok.isEmpty()?"login":"profile");
        else if(x<273)call(tok.isEmpty()?"login":"wallet");
        else call("profile");
        return true;
      }
      if(y>=69&&y<95&&x>270){call("login");return true;}

      // Main CTA; the 3D scene itself stays a passive, unobstructed Home preview.
      if(y>=438&&y<499&&x>=35&&x<=355){call("gameModeDialog");return true;}

      if(y>=505&&y<573){
        int[]fs={500,1000,2000,5000,10000,20000};
        int i=(int)((x-10)/61.5f);i=Math.max(0,Math.min(5,i));
        fee=fs[i];set("entryFee",fee);invalidate();return true;
      }
      if(y>=580&&y<630){
        int i=Math.max(0,Math.min(3,(int)(x/94.5f)));
        if(i==0)call("joinDialog");
        else if(i==1)call("events");
        else if(i==2)call("tutorials");
        else call("social");
        return true;
      }
      if(y>=640&&y<=706){
        int i=Math.max(0,Math.min(4,(int)(x/76f)));
        if(i==0)call("home");
        else if(i==1)call("gameModeDialog");
        else if(i==2)call("events");
        else if(i==3)call("social");
        else call("shop");
        return true;
      }
      return true;
    }
  }
}
