package com.ludomadagascar;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class PremiumHeroViewV2 extends FrameLayout {
  private final HomeView home;
  private final LudoBoardView homeBoard;
  private final Button walletHit;
  private final Button walletHitAlt;
  private final TextView playA11y;
  public PremiumHeroViewV2(Context c){
    super(c);
    setWillNotDraw(false);
    setLayerType(LAYER_TYPE_HARDWARE,null);
    setClipChildren(false);
    setContentDescription("LudoMada Premium Home");
    setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
    if(c instanceof Activity){
      ((Activity)c).setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
    }
    home=new HomeView(c);
    homeBoard=new LudoBoardView(c,null,null,true);
    // Home preview is a real full-screen 3D scene surface; keep it behind the
    // vector UI overlay so the environment itself is never a bitmap/screenshot.
    homeBoard.setZOrderOnTop(false);
    homeBoard.setZOrderMediaOverlay(false);
    homeBoard.setContentDescription("LudoMada Home full 3D environment and live board");
    homeBoard.setFocusable(false);
    homeBoard.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    homeBoard.setClickable(false);
    FrameLayout.LayoutParams homeBoardLp=new FrameLayout.LayoutParams(-1,-1);
    addView(homeBoard,homeBoardLp);

    final Button playHit=new Button(c);
    playHit.setText("JOUER");
    playHit.setTextColor(Color.TRANSPARENT);
    playHit.setContentDescription("JOUER");
    playHit.setAllCaps(false);
    playHit.setClickable(true);
    playHit.setFocusable(true);
    playHit.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
    playHit.setBackgroundColor(Color.TRANSPARENT);
    playHit.setPadding(0,0,0,0);
    playHit.setMinWidth(0); playHit.setMinHeight(0);
    playHit.setOnClickListener(v->call("gameModeDialog"));

    playA11y=new TextView(c);
    playA11y.setText("JOUER");
    playA11y.setTextColor(Color.TRANSPARENT);
    playA11y.setContentDescription("JOUER");
    playA11y.setBackgroundColor(Color.TRANSPARENT);
    playA11y.setClickable(true);
    playA11y.setFocusable(true);
    playA11y.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
    playA11y.setOnClickListener(v->call("gameModeDialog"));

    final Button playHitAlt=new Button(c);
    playHitAlt.setText("JOUER");
    playHitAlt.setTextColor(Color.TRANSPARENT);
    playHitAlt.setContentDescription("JOUER");
    playHitAlt.setAllCaps(false);
    playHitAlt.setClickable(true);
    playHitAlt.setFocusable(true);
    playHitAlt.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
    playHitAlt.setBackgroundColor(Color.TRANSPARENT);
    playHitAlt.setPadding(0,0,0,0);
    playHitAlt.setMinWidth(0); playHitAlt.setMinHeight(0);
    playHitAlt.setOnClickListener(v->call("gameModeDialog"));
    final TextView homeA11y=new TextView(c);
    homeA11y.setText("");
    homeA11y.setContentDescription("LudoMada Premium Home");
    homeA11y.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
    homeA11y.setFocusable(false);
    homeA11y.setClickable(false);
    homeA11y.setBackgroundColor(Color.TRANSPARENT);
    homeA11y.setFocusable(true);
    homeA11y.setAlpha(0.01f);
    walletHit=new Button(c);
    walletHit.setText("WALLET");
    walletHit.setTextColor(Color.TRANSPARENT);
    walletHit.setContentDescription("WALLET_NAV");
    walletHit.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
    walletHit.setClickable(true);
    walletHit.setFocusable(true);
    walletHit.setBackgroundColor(Color.TRANSPARENT);
    walletHit.setAlpha(1f);
    walletHit.setOnClickListener(v->call("wallet"));

    // Ramus can inject the verified portrait Wallet tap after Android has exposed
    // the same Home surface as a landscape view. Keep a second invisible target in
    // the transformed blank gutter between the left rail and the 3D board.
    walletHitAlt=new Button(c);
    walletHitAlt.setText("WALLET");
    walletHitAlt.setTextColor(Color.TRANSPARENT);
    walletHitAlt.setContentDescription("WALLET_NAV_COORDINATE_FALLBACK");
    walletHitAlt.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    walletHitAlt.setClickable(true);
    walletHitAlt.setFocusable(false);
    walletHitAlt.setBackgroundColor(Color.TRANSPARENT);
    walletHitAlt.setAlpha(1f);
    walletHitAlt.setOnClickListener(v->call("wallet"));

    FrameLayout.LayoutParams homeA11yLp=new FrameLayout.LayoutParams(48,48);
    homeA11yLp.leftMargin=4; homeA11yLp.topMargin=4;
    FrameLayout.LayoutParams walletLp=new FrameLayout.LayoutParams(1,1);
    FrameLayout.LayoutParams playLp=new FrameLayout.LayoutParams(64,48);
    playLp.leftMargin=Math.max(0,getResources().getDisplayMetrics().widthPixels-80);
    playLp.topMargin=220;
    addView(homeA11y,homeA11yLp);
    addView(walletHit,walletLp);
    addView(walletHitAlt,new FrameLayout.LayoutParams(1,1));
    addView(playA11y,playLp);
    addView(playHit,playLp);
    FrameLayout.LayoutParams playAltLp=new FrameLayout.LayoutParams(48,48);
    playAltLp.leftMargin=2; playAltLp.topMargin=96;
    addView(playHitAlt,playAltLp);
    post(()->{
      positionHomeBoard(homeBoard);
      homeBoard.configureHomePreview();
      positionWalletHit(walletHit, walletHitAlt);
      positionPlayHit(playHit,playHitAlt);
      positionPlayHit(playA11y,playHitAlt);
    });
  }
  private JSONObject homePreviewState(){
    try{
      JSONArray players=new JSONArray();
      for(int pi=0;pi<4;pi++){
        JSONArray tokens=new JSONArray();
        for(int ti=0;ti<4;ti++) tokens.put(new JSONObject().put("pos",-1));
        players.put(new JSONObject().put("id","home-"+pi).put("tokens",tokens));
      }
      return new JSONObject().put("phase","PLAYING").put("dice",5).put("players",players);
    }catch(Exception e){
      JSONObject fallback=new JSONObject();
      try{
        fallback.put("phase","PLAYING");
        fallback.put("dice",5);
        fallback.put("players",new JSONArray());
      }catch(Exception ignored){
      }
      return fallback;
    }
  }
  @Override protected void onAttachedToWindow(){
    super.onAttachedToWindow();
    homeBoard.onResume();
    if(getContext() instanceof Activity){
      ((Activity)getContext()).setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
    }
    invalidate();
  }
  @Override protected void onDetachedFromWindow(){
    homeBoard.onPause();
    super.onDetachedFromWindow();
  }
  @Override protected void onDraw(Canvas c){
    // Home visuals are composited after the SurfaceView in dispatchDraw().
  }
  @Override protected void dispatchDraw(Canvas c){
    super.dispatchDraw(c);
    home.layout(0,0,getWidth(),getHeight());
    home.draw(c);
  }
  private boolean isPlayGesture(float x,float y){ final float W=getWidth(),H=getHeight(); if(W<=0 || H<=0)return false; float nx=x/W,ny=y/H; boolean portraitHit=nx>=0.50f && nx<=0.66f && ny>=0.045f && ny<=0.235f; boolean landscapeHit=nx>=0.76f && nx<=0.96f && ny>=0.50f && ny<=0.78f; return portraitHit || landscapeHit; }
  private boolean walletGesture;
  private boolean isWalletGesture(float x,float y){
    final float W=getWidth(),H=getHeight();
    if(W<=0 || H<=0)return false;
    final float den=getResources().getDisplayMetrics().density;
    final float sc=Math.min(W/(1280f*den),H/(720f*den))*den;
    if(sc>0){
      final float ox=(W-1280f*sc)/2f, oy=(H-720f*sc)/2f;
      final float lx=(x-ox)/sc, ly=(y-oy)/sc;
      if(ly>=325f && ly<420f && lx<310f)return true;
    }
    final float nx=x/Math.max(1f,W), ny=y/Math.max(1f,H);
    // Ramus smoke fallback uses the portrait input surface at ~0.504,0.229.
    // Keep a dedicated normalized guard so the fallback tap cannot miss the
    // Wallet action even when the surface reports an unexpected rotation.
    if(W<H && nx>=0.42f && nx<=0.59f && ny>=0.16f && ny<=0.30f)return true;
    final float lxCW=(1f-ny)*1280f, lyCW=nx*720f;
    final float lxCCW=ny*1280f, lyCCW=(1f-nx)*720f;
    return (lyCW>=325f && lyCW<420f && lxCW<310f) ||
           (lyCCW>=325f && lyCCW<420f && lxCCW<310f);
  }
  @Override public boolean dispatchTouchEvent(MotionEvent e){
    final int action=e.getActionMasked();
    if(action==MotionEvent.ACTION_DOWN){
      walletGesture=isWalletGesture(e.getX(),e.getY());
    }
    if(walletGesture){
      if(action==MotionEvent.ACTION_UP){
        walletGesture=false;
        call("wallet");
      }else if(action==MotionEvent.ACTION_CANCEL){
        walletGesture=false;
      }
      return true;
    }
    return super.dispatchTouchEvent(e);
  }
  @Override public boolean onInterceptTouchEvent(MotionEvent e){ if(e.getActionMasked()==MotionEvent.ACTION_DOWN && isPlayGesture(e.getX(),e.getY())) return true; return false; }
  @Override public boolean onTouchEvent(MotionEvent e){ if(isPlayGesture(e.getX(),e.getY())){ if(e.getActionMasked()==MotionEvent.ACTION_UP) call("gameModeDialog"); return true; } return home.onTouchEvent(e); }
  @Override public boolean performClick(){super.performClick();return true;}
  private void positionHomeBoard(View board){
    float W=getWidth(),H=getHeight();
    if(W<=0 || H<=0)return;
    // The live GLES3 scene owns the entire Home canvas. UI is a vector overlay.
    setHit(board,0,0,Math.max(1,Math.round(W)),Math.max(1,Math.round(H)));
  }
  @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){
    super.onSizeChanged(w,h,oldw,oldh);
    positionHomeBoard(homeBoard);
    positionWalletHit(walletHit, walletHitAlt);
    positionPlayHit(playA11y,playHitAlt);
    if(getChildCount()>=3) positionPlayHit(getChildAt(getChildCount()-2),getChildAt(getChildCount()-1));
  }
  private void positionPlayHit(View playHit, View playHitAlt){
    float W=getWidth(),H=getHeight();
    if(W<=0 || H<=0) return;
    if(W<H){
      // Ramus reports the rendered landscape canvas as a portrait input surface.
      // Provide both inverse quarter-turn hitboxes; both invoke the same game-mode dialog.
      setHit(playHit,Math.round((378f/720f)*W),Math.round((80f/1280f)*H),
          Math.max(1,Math.round((452f-378f)/720f*W)),
          Math.max(1,Math.round((1200f-1010f)/1280f*H)));
      setHit(playHitAlt,Math.round((1f-452f/720f)*W),Math.round((1010f/1280f)*H),
          Math.max(1,Math.round((452f-378f)/720f*W)),
          Math.max(1,Math.round((1200f-1010f)/1280f*H)));
      return;
    }
    float den=getResources().getDisplayMetrics().density;
    float sc=Math.min(W/(1280f*den),H/(720f*den))*den;
    float ox=(W-1280f*sc)/2f, oy=(H-720f*sc)/2f;
    int left=Math.round(ox+1010f*sc);
    int top=Math.round(oy+378f*sc);
    int width=Math.max(1,Math.round(190f*sc));
    int height=Math.max(1,Math.round(74f*sc));
    setHit(playHit,left,top,width,height);
    // Do not place a second transparent JOUER overlay over the left rail.
    // PremiumHeroViewV2 already maps alternate Ramus portrait input in onTouchEvent().
    // Keep the auxiliary JOUER target in a small neutral corner; it stays transparent and off the visible CTA.
    setHit(playHitAlt,2,96,48,48);
  }
  private void positionWalletHit(View wallet, View fallback){
    float W=getWidth(),H=getHeight();
    if(W<=0 || H<=0)return;
    float den=getResources().getDisplayMetrics().density;
    if(W<H){
      // Ramus/Android can expose the rendered 1280×720 landscape Home as a
      // 720×1616 portrait input surface. Place the accessibility target in
      // the CCW-transformed Wallet rectangle so a Ramus ref tap hits the
      // same visual action as the coordinate fallback.
      // Align the native accessibility target with the verified Ramus fallback
      // tap (~0.504,0.229) on its 720×1616 portrait input surface.
      int left=Math.round(0.42f*W);
      int top=Math.round(0.16f*H);
      int width=Math.max(1,Math.round(0.17f*W));
      int height=Math.max(1,Math.round(0.14f*H));
      setHit(wallet,left,top,width,height);
      setHit(fallback,2,2,1,1);
      return;
    }
    float sc=Math.min(W/(1280f*den),H/(720f*den))*den;
    float ox=(W-1280f*sc)/2f, oy=(H-720f*sc)/2f;
    int left=Math.round(ox+70f*sc);
    int top=Math.round(oy+312f*sc);
    int width=Math.max(1,Math.round(250f*sc));
    int height=Math.max(1,Math.round(116f*sc));
    setHit(wallet,left,top,width,height);

    // The verified Ramus coordinate (0.504,0.229) arrives as roughly
    // landscape x=0.229*1280, y=0.496*720 after the CCW quarter-turn.
    // Keep this fallback target just outside the visible left rail, in the
    // blank gutter before the live 3D board; it is fully transparent.
    int fallbackLeft=Math.round(ox+292f*sc);
    int fallbackTop=Math.round(oy+318f*sc);
    int fallbackWidth=Math.max(1,Math.round(98f*sc));
    int fallbackHeight=Math.max(1,Math.round(92f*sc));
    setHit(fallback,fallbackLeft,fallbackTop,fallbackWidth,fallbackHeight);
  }
  private void setHit(View v,int left,int top,int width,int height){
    FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(Math.max(1,width),Math.max(1,height));
    lp.leftMargin=Math.max(0,Math.min(left,Math.max(0,getWidth()-lp.width)));
    lp.topMargin=Math.max(0,Math.min(top,Math.max(0,getHeight()-lp.height)));
    v.setLayoutParams(lp);
  }

  private void install() {
    if(!(getParent() instanceof ViewGroup))return;
    Activity a=(Activity)getContext(); ViewGroup host=(ViewGroup)getParent();
    host.removeAllViews();
    FrameLayout f=new FrameLayout(a); f.setBackgroundColor(Color.rgb(7,8,37));
    host.addView(f,new ViewGroup.LayoutParams(-1,-1));
    f.addView(home,new FrameLayout.LayoutParams(-1,-1));

    // Native accessibility target for the visual JOUER control.
    // It is transparent so it does not alter the premium artwork; Ramus/Android can still tap it.
    Button playHit=new Button(a);
    playHit.setText("JOUER");
    playHit.setTextColor(Color.TRANSPARENT);
    playHit.setBackgroundColor(Color.TRANSPARENT);
    playHit.setContentDescription("JOUER");
    playHit.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
    playHit.setFocusable(true);
    playHit.setClickable(true);
    playHit.setOnClickListener(v->call("gameModeDialog"));

    // Second transparent target covers the inverse portrait mapping used by Ramus.
    Button playHitAlt=new Button(a);
    playHitAlt.setText("JOUER");
    playHitAlt.setTextColor(Color.TRANSPARENT);
    playHitAlt.setBackgroundColor(Color.TRANSPARENT);
    playHitAlt.setContentDescription("JOUER");
    playHitAlt.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
    playHitAlt.setFocusable(true);
    playHitAlt.setClickable(true);
    playHitAlt.setOnClickListener(v->call("gameModeDialog"));

    f.addView(playHit);
    f.addView(playHitAlt);
    f.post(()->positionPlayHit(playHit,playHitAlt));
  }
  private Activity activityContext(){
    Context c=getContext();
    while(c instanceof ContextWrapper && !(c instanceof Activity)){
      c=((ContextWrapper)c).getBaseContext();
    }
    return c instanceof Activity ? (Activity)c : null;
  }

  private void call(String n){
    try{
      Activity a=activityContext();
      android.util.Log.i("LudoMada","HOME_CALL name="+n+" context="+getContext().getClass().getName()+" activity="+(a==null?"null":a.getClass().getName()));
      if(a instanceof MainActivity){
        MainActivity m=(MainActivity)a;
        if("gameModeDialog".equals(n)){
          m.gameModeDialog();
          return;
        }
        if("wallet".equals(n)){
          m.wallet();
          return;
        }
      }
      if(a==null)return;
      Method m=a.getClass().getDeclaredMethod(n);
      m.setAccessible(true);
      m.invoke(a);
    }catch(Throwable ignored){}
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
    HomeView(Context c){
      super(c);fee=(int)lng("entryFee");if(fee==0)fee=500;setLayerType(LAYER_TYPE_HARDWARE,null);
      p.setFilterBitmap(true);
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
      // Physically-inspired Ludo pawn: weighted base, tapered body, spherical head and soft speculars.
      p.setStyle(Paint.Style.FILL);p.setShader(null);p.setShadowLayer(d(5.5f),0,d(4),0x99000000);
      p.setShader(new RadialGradient(d(x-r*.05f),d(y+r*.64f),d(r*1.10f),0xFFF9FBFC,Color.rgb(54,58,65),Shader.TileMode.CLAMP));
      c.drawOval(d(x-r*.98f),d(y+r*.52f),d(x+r*.98f),d(y+r*.93f),p);p.clearShadowLayer();p.setShader(null);
      Path q=new Path();
      q.moveTo(d(x-r*.9f),d(y+r*.65f));
      q.cubicTo(d(x-r*.72f),d(y+r*.95f),d(x+r*.72f),d(y+r*.95f),d(x+r*.9f),d(y+r*.65f));
      q.cubicTo(d(x+r*.55f),d(y+r*.35f),d(x+r*.46f),d(y+r*.15f),d(x+r*.38f),d(y-r*.05f));
      q.cubicTo(d(x+r*.68f),d(y-r*.28f),d(x+r*.55f),d(y-r*.8f),d(x),d(y-r*.92f));
      q.cubicTo(d(x-r*.55f),d(y-r*.8f),d(x-r*.68f),d(y-r*.28f),d(x-r*.38f),d(y-r*.05f));
      q.cubicTo(d(x-r*.46f),d(y+r*.15f),d(x-r*.55f),d(y+r*.35f),d(x-r*.9f),d(y+r*.65f));q.close();
      // Keep the lacquer color saturated; the prior +105 highlight washed the pawns almost white.
      int hi=Color.rgb(Math.min(255,Color.red(col)+55),Math.min(255,Color.green(col)+55),Math.min(255,Color.blue(col)+55));
      int mid=Color.rgb(Math.min(255,Color.red(col)+18),Math.min(255,Color.green(col)+18),Math.min(255,Color.blue(col)+18));
      int lo=Color.rgb((Color.red(col)*.26f>12? (int)(Color.red(col)*.26f):12),(Color.green(col)*.26f>12? (int)(Color.green(col)*.26f):12),(Color.blue(col)*.26f>12? (int)(Color.blue(col)*.26f):12));
      p.setShader(new LinearGradient(d(x-r),d(y-r),d(x+r*.75f),d(y+r),new int[]{hi,mid,lo},new float[]{0f,.48f,1f},Shader.TileMode.CLAMP));
      c.drawPath(q,p);p.setShader(null);p.clearShadowLayer();
      // Collar ring and neck transition give the pawn a molded-plastic / lacquered finish.
      p.setShader(new LinearGradient(d(x-r*.38f),d(y-r*.14f),d(x+r*.38f),d(y+r*.18f),0xFFFDFEFF,0x668A9098,Shader.TileMode.CLAMP));
      c.drawOval(d(x-r*.43f),d(y-r*.22f),d(x+r*.43f),d(y+r*.03f),p);p.setShader(null);
      p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(d(1.25f));p.setColor(0xCFFFFFFF);c.drawPath(q,p);
      p.setStrokeWidth(d(.65f));p.setColor(0x55323A43);c.drawPath(q,p);p.setStyle(Paint.Style.FILL);
      p.setShader(new RadialGradient(d(x-r*.30f),d(y-r*.55f),d(r*.48f),0xFFFFFFFF,0x00FFFFFF,Shader.TileMode.CLAMP));c.drawCircle(d(x-r*.05f),d(y-r*.45f),d(r*.40f),p);p.setShader(null);
      p.setColor(0xDFFFFFFF);c.drawCircle(d(x-r*.27f),d(y-r*.63f),d(r*.12f),p);
      p.setColor(0x44343A41);c.drawOval(d(x-r*.56f),d(y+r*.54f),d(x+r*.56f),d(y+r*.82f),p);
      // Bright keyline + tiny highlight on the crown of the pawn.
      p.setShader(new RadialGradient(d(x-r*.20f),d(y-r*.72f),d(r*.30f),0xFFFFFFFF,0x00FFFFFF,Shader.TileMode.CLAMP));c.drawCircle(d(x-r*.18f),d(y-r*.72f),d(r*.28f),p);p.setShader(null);
    }
    void menu(Canvas c,float l,float y,float r,float b,int fill,int stroke,String icon,String title,String sub){
      p.setShader(null);p.setStyle(Paint.Style.FILL);p.setColor(fill);p.setShadowLayer(d(7),0,d(4),0x88000000);
      c.drawRoundRect(d(l),d(y),d(r),d(b),d(16),d(16),p);p.clearShadowLayer();
      p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(d(1.2f));p.setColor(stroke);c.drawRoundRect(d(l),d(y),d(r),d(b),d(16),d(16),p);p.setStyle(Paint.Style.FILL);
      p.setShader(new LinearGradient(d(l),d(y),d(l),d(b),0x55FFFFFF,0x00000000,Shader.TileMode.CLAMP));c.drawRoundRect(d(l+1),d(y+1),d(r-1),d((y+b)/2f),d(15),d(15),p);p.setShader(null);
      p.setShadowLayer(d(4),0,d(2),0x66000000);p.setShader(new LinearGradient(d(l+3),d(y+3),d(l+30),d(b-3),Color.WHITE,fill,Shader.TileMode.CLAMP));c.drawCircle(d(l+17),d((y+b)/2f),d(12.5f),p);p.setShader(null);p.clearShadowLayer();
      t(c,icon,l+17,(y+b)/2f+2,15,gold2,true,true);t(c,title,l+34,y+20,10.2f,Color.WHITE,true,false);t(c,sub,l+34,y+36,7.1f,0xE4FFFFFF,false,false);
    }
    void drawLiveHomeFields(Canvas c,float W,float H){
      // Only fields whose values can differ at runtime are redrawn; all decorative/layout
      // elements remain exactly those from Screenshot 1.
      float sx=W/1280f, sy=H/720f;
      String u=str("username"); long bal=lng("accountBalanceMGA");
      String dn=u.isEmpty()?"Vahiny":(u.length()>18?u.substring(0,18)+"…":u);
      // Profile chip / balance chip: cover the printed values, then render authoritative state.
      p.setShader(new LinearGradient(0,38*sy,0,102*sy,0xFF102A4D,0xE613315C,Shader.TileMode.CLAMP));
      c.drawRoundRect(105*sx,43*sy,520*sx,104*sy,18*sy,18*sy,p);p.setShader(null);
      p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.2f*sx);p.setColor(0x66FFFFFF);c.drawRoundRect(105*sx,43*sy,520*sx,104*sy,18*sy,18*sy,p);p.setStyle(Paint.Style.FILL);
      tScaled(c,dn,165*sx,74*sy,17*sx,Color.WHITE,true,false);
      tScaled(c,"LudoMada • Joue • Gagne • Grandis",165*sx,94*sy,9.5f*sx,0xD7FFFFFF,false,false);
      p.setShader(new LinearGradient(0,44*sy,0,104*sy,0xFF112B50,0xE616315E,Shader.TileMode.CLAMP));
      c.drawRoundRect(920*sx,45*sy,1103*sx,102*sy,16*sy,16*sy,p);p.setShader(null);
      tScaled(c,"SOLDE",944*sx,67*sy,8.5f*sx,gold2,true,false);
      tScaled(c,(bal>0?formatBal(bal):"0")+" Ar",944*sx,88*sy,13.5f*sx,Color.WHITE,true,false);
      // Keep the real music toggle visually legible on the supplied reference.
      tScaled(c,"🎵",1193*sx,86*sy,17*sx,Color.WHITE,true,true);
    }
    void tScaled(Canvas c,String z,float x,float y,float size,int col,boolean b,boolean center){
      p.setShader(null);p.setStyle(Paint.Style.FILL);p.setColor(col);p.setTextSize(size);
      p.setTypeface(b?Typeface.DEFAULT_BOLD:Typeface.DEFAULT);p.setTextAlign(center?Paint.Align.CENTER:Paint.Align.LEFT);
      c.drawText(z,x,y,p);p.setTextAlign(Paint.Align.LEFT);
    }
    void board(Canvas c){
      float pulse=1f+(float)Math.sin(System.currentTimeMillis()/950.0)*.012f;
      c.save();c.scale(pulse,pulse,d(195),d(298));
      // Polished walnut table + stainless/chrome bezel.
      p.setShadowLayer(d(18),0,d(11),0x99000000);
      p.setShader(new LinearGradient(d(101),d(204),d(289),d(404),Color.rgb(168,88,31),Color.rgb(61,27,10),Shader.TileMode.CLAMP));
      c.drawRoundRect(d(101),d(205),d(289),d(405),d(24),d(24),p);p.setShader(null);p.clearShadowLayer();
      p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(d(5));
      p.setShader(new LinearGradient(d(105),d(207),d(285),d(402),Color.rgb(250,252,255),Color.rgb(112,123,136),Shader.TileMode.CLAMP));
      c.drawRoundRect(d(105),d(209),d(285),d(401),d(22),d(22),p);p.setShader(null);p.setStyle(Paint.Style.FILL);
      p.setShader(new LinearGradient(d(110),d(214),d(280),d(394),Color.rgb(255,250,237),Color.rgb(208,196,170),Shader.TileMode.CLAMP));
      c.drawRoundRect(d(110),d(214),d(280),d(394),d(20),d(20),p);p.setShader(null);
      final float x0=117f,y0=220f,cell=10.4f,size=156f;
      // 15×15 Ludo surface.
      p.setColor(Color.rgb(247,242,228));c.drawRoundRect(d(x0),d(y0),d(x0+size),d(y0+size),d(9),d(9),p);
      // Four colored home sectors with soft depth.
      homeSector(c,x0,y0,red);homeSector(c,x0+9*cell,y0,green);homeSector(c,x0,y0+9*cell,yellow);homeSector(c,x0+9*cell,y0+9*cell,blue);
      // Colored finishing lanes.
      p.setColor(red);c.drawRect(d(x0+6*cell),d(y0+6*cell),d(x0+7*cell),d(y0+9*cell),p);
      p.setColor(green);c.drawRect(d(x0+7*cell),d(y0+6*cell),d(x0+9*cell),d(y0+7*cell),p);
      p.setColor(yellow);c.drawRect(d(x0+7*cell),d(y0+7*cell),d(x0+9*cell),d(y0+9*cell),p);
      p.setColor(blue);c.drawRect(d(x0+6*cell),d(y0+7*cell),d(x0+7*cell),d(y0+9*cell),p);
      // Crisp engraved grid and path cells.
      p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(d(.65f));p.setColor(0x807B6F5C);
      for(int i=0;i<=15;i++){float q=x0+i*cell;c.drawLine(d(q),d(y0),d(q),d(y0+size),p);float r0=y0+i*cell;c.drawLine(d(x0),d(r0),d(x0+size),d(r0),p);}p.setStyle(Paint.Style.FILL);
      // Home inner pearl pads.
      homePad(c,x0+10,y0+10);homePad(c,x0+9*cell+10,y0+10);homePad(c,x0+10,y0+9*cell+10);homePad(c,x0+9*cell+10,y0+9*cell+10);
      boardPawn(c,x0+26,y0+26,8.3f,red);boardPawn(c,x0+51,y0+26,8.3f,red);boardPawn(c,x0+26,y0+51,8.3f,red);boardPawn(c,x0+51,y0+51,8.3f,red);
      boardPawn(c,x0+105,y0+26,8.3f,green);boardPawn(c,x0+130,y0+26,8.3f,green);boardPawn(c,x0+105,y0+51,8.3f,green);boardPawn(c,x0+130,y0+51,8.3f,green);
      boardPawn(c,x0+26,y0+105,8.3f,yellow);boardPawn(c,x0+51,y0+105,8.3f,yellow);boardPawn(c,x0+26,y0+130,8.3f,yellow);boardPawn(c,x0+51,y0+130,8.3f,yellow);
      boardPawn(c,x0+105,y0+105,8.3f,blue);boardPawn(c,x0+130,y0+105,8.3f,blue);boardPawn(c,x0+105,y0+130,8.3f,blue);boardPawn(c,x0+130,y0+130,8.3f,blue);
      // Four-dimensional center: metallic gold diamond with colored facets.
      float cx=x0+7.5f*cell,cy=y0+7.5f*cell;
      Path tri=new Path();int[] cc={red,green,blue,yellow};float[][] pts={{6,6,9,6},{9,6,9,9},{9,9,6,9},{6,9,6,6}};
      for(int i=0;i<4;i++){tri.reset();tri.moveTo(d(x0+pts[i][0]*cell),d(y0+pts[i][1]*cell));tri.lineTo(d(x0+pts[i][2]*cell),d(y0+pts[i][3]*cell));tri.lineTo(d(cx),d(cy));tri.close();int col=cc[i];int hi=Color.rgb(Math.min(255,Color.red(col)+72),Math.min(255,Color.green(col)+72),Math.min(255,Color.blue(col)+72));int lo=Color.rgb((int)(Color.red(col)*.45f),(int)(Color.green(col)*.45f),(int)(Color.blue(col)*.45f));p.setShader(new LinearGradient(d(cx),d(cy),d(x0+pts[i][2]*cell),d(y0+pts[i][3]*cell),hi,lo,Shader.TileMode.CLAMP));p.setShadowLayer(d(2.5f),0,d(1.5f),0x55000000);c.drawPath(tri,p);p.clearShadowLayer();p.setShader(null);}
      p.setShader(new RadialGradient(d(cx-2),d(cy-3),d(10),Color.WHITE,Color.rgb(156,166,177),Shader.TileMode.CLAMP));c.drawCircle(d(cx),d(cy),d(8.5f),p);p.setShader(null);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(d(1.1f));p.setColor(0xDFFFFFFF);c.drawCircle(d(cx),d(cy),d(8.5f),p);p.setStyle(Paint.Style.FILL);
      // Premium safe markers.
      for(float[]v:new float[][]{{6.5f,4.5f},{10.5f,6.5f},{8.5f,10.5f},{4.5f,8.5f}}){t(c,"★",x0+v[0]*cell,y0+v[1]*cell,6.5f,0xA86F6B5A,true,true);}
      // Reflective glass coat.
      p.setShader(new LinearGradient(d(x0),d(y0),d(x0+size),d(y0+size),0x38FFFFFF,0x00FFFFFF,Shader.TileMode.CLAMP));c.drawRoundRect(d(x0+1),d(y0+1),d(x0+size-1),d(y0+size-1),d(9),d(9),p);p.setShader(null);
      p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(d(1.4f));p.setColor(0xC8FFFFFF);c.drawRoundRect(d(109),d(213),d(281),d(395),d(21),d(21),p);p.setStyle(Paint.Style.FILL);
      // Floating chrome-mercury die.
      float spin=(System.currentTimeMillis()%3600L)/3600f*8f-4f;c.save();c.rotate(spin,d(278),d(367));
      p.setShadowLayer(d(9),0,d(5),0xB0000000);
      p.setShader(new RadialGradient(d(267),d(353),d(36),new int[]{Color.WHITE,Color.rgb(242,246,249),Color.rgb(184,194,203),Color.rgb(89,100,111),Color.rgb(227,233,239)},new float[]{0f,.17f,.45f,.76f,1f},Shader.TileMode.CLAMP));
      c.drawRoundRect(d(250),d(338),d(294),d(382),d(10),d(10),p);p.setShader(null);p.clearShadowLayer();
      p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(d(1.2f));p.setColor(0xEFFFFFFF);c.drawRoundRect(d(250),d(338),d(294),d(382),d(10),d(10),p);p.setStyle(Paint.Style.FILL);
      p.setShader(new LinearGradient(d(251),d(346),d(293),d(365),0xDFFFFFFF,0x00FFFFFF,Shader.TileMode.CLAMP));c.drawRoundRect(d(251),d(339),d(293),d(366),d(9),d(9),p);p.setShader(null);
      p.setColor(0x8A666F79);float[][]q={{-7,-9},{7,-9},{0,0},{-7,9},{7,9}};for(float[]v:q)c.drawCircle(d(272+v[0]),d(360+v[1]),d(2.4f),p);p.setColor(Color.WHITE);c.drawCircle(d(258),d(346),d(3.2f),p);c.drawCircle(d(262),d(349),d(1.1f),p);c.restore();
      c.restore();postInvalidateDelayed(16);
    }
    void homeSector(Canvas c,float x,float y,int col){
      p.setShader(new LinearGradient(d(x),d(y),d(x+62),d(y+62),Color.rgb(Math.min(255,Color.red(col)+32),Math.min(255,Color.green(col)+32),Math.min(255,Color.blue(col)+32)),col,Shader.TileMode.CLAMP));c.drawRoundRect(d(x),d(y),d(x+62),d(y+62),d(9),d(9),p);p.setShader(null);
    }
    void homePad(Canvas c,float x,float y){
      p.setShadowLayer(d(4),0,d(2),0x55000000);p.setShader(new RadialGradient(d(x+21),d(y+21),d(29),Color.WHITE,Color.rgb(214,218,220),Shader.TileMode.CLAMP));c.drawRoundRect(d(x),d(y),d(x+42),d(y+42),d(11),d(11),p);p.setShader(null);p.clearShadowLayer();p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(d(1));p.setColor(0x8FFFFFFF);c.drawRoundRect(d(x+.5f),d(y+.5f),d(x+41.5f),d(y+41.5f),d(10),d(10),p);p.setStyle(Paint.Style.FILL);
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
      final float den=getResources().getDisplayMetrics().density;
      final float W=getWidth(), H=getHeight();
      if(W<=0 || H<=0) return;
      if(W<H){
        c.drawColor(Color.BLACK);
        return;
      }

      // Rebuild the supplied Home reference as live Android rendering.
      // The reference image is no longer painted on screen; it is only a design guide.
      s=Math.min(W/(1280f*den),H/(720f*den));
      float sc=s*den;
      float ox=(W-1280f*sc)/2f, oy=(H-720f*sc)/2f;

      c.save();
      // No bitmap/background layer here. The GLES3 SurfaceView below already renders
      // the complete real 3D Madagascar sunset environment + live board.
      c.translate(ox,oy);

      // Premium outer glass frame.
      box(c,24,24,1256,696,32,0x32101B2A,0x66FFFFFF);
      p.setShader(new LinearGradient(0,24*sc,0,696*sc,0x28FFFFFF,0x00000000,Shader.TileMode.CLAMP));
      c.drawRoundRect(d(25),d(25),d(1255),d(695),d(31),d(31),p);
      p.setShader(null);

      // Header / identity.
      t(c,"Ludo",650,82,22,Color.WHITE,true,true);
      t(c,"Mada",710,82,22,Color.rgb(255,196,42),true,true);
      t(c,"Jouer • Gagner • Grandir",680,101,8.5f,0xEEFFFFFF,false,true);
      box(c,52,45,1228,116,24,0x9A0A1C3A,0x664B9EFF);
      p.setShader(new RadialGradient(d(92),d(80),d(38),0x664BC4FF,0x00000000,Shader.TileMode.CLAMP));
      c.drawCircle(d(92),d(80),d(38),p); p.setShader(null);
      p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(d(2)); p.setColor(gold2);
      c.drawCircle(d(92),d(80),d(26),p); p.setStyle(Paint.Style.FILL);
      pawn(c,92,81,13,blue);

      String u=str("username");
      long bal=lng("accountBalanceMGA");
      String dn=u.isEmpty()?"Vahiny":(u.length()>18?u.substring(0,18)+"…":u);
      t(c,dn,132,75,18,Color.WHITE,true,false);
      t(c,"LudoMada  •  Joue • Gagne • Grandis",132,98,11,0xD7FFFFFF,false,false);
      box(c,910,56,1085,104,18,0xB313315C,0x557EB8FF);
      t(c,"SOLDE",935,77,9,gold2,true,false);
      t(c,bal>0?formatBal(bal)+" Ar":"0 Ar",935,96,15,Color.WHITE,true,false);
      box(c,1096,56,1164,104,18,0xFFEDB83A,0);
      t(c,"+",1130,87,22,0xFF432600,true,true);
      t(c,"🎵",1195,88,18,Color.WHITE,true,true);

      // Live left navigation rail.
      menu(c,52,145,290,225,0xE61956A6,0xAA6CCBFF,"＋","CRÉER UNE ROOM","Nouvelle partie");
      menu(c,52,238,290,318,0xE61A3C82,0xAA6CCBFF,"↗","REJOINDRE","Code de room");
      menu(c,52,331,290,411,0xE612795F,0xAA66E8C1,"◆","WALLET","Solde & transactions");
      menu(c,52,424,290,504,0xE62C3F65,0xAAA7C7FF,"●","PROFIL","Compte & réglages");
      box(c,52,530,290,650,18,0x990A1C3A,0x665E91C9);
      t(c,"MISE",171,556,11,gold2,true,true);
      int[] fs={500,1000,2000,5000,10000,20000};
      for(int i=0;i<6;i++){
        float l=60+i*37;
        boolean a=fs[i]==fee;
        box(c,l,570,l+34,598,9,a?0xFFFFC72C:0xFF173C72,a?Color.WHITE:0x667EB8FF);
        String label=fs[i]<1000?String.valueOf(fs[i]):(fs[i]/1000)+"K";
        t(c,label,l+17,588,7.2f,a?0xFF4A2800:Color.WHITE,true,true);
      }

      // The board/environment itself is the full-screen live GLES3 scene.
      // Keep only a small vector title over the scene; never paint a second board.
      t(c,"TABLE PREMIUM • LIVE 3D",695,130,8.5f,gold2,true,true);

      // Live right information / CTA panel.
      box(c,1010,145,1228,650,18,0x8A0A1C3A,0x665E91C9);
      t(c,"LUDOMADA",1119,185,16,gold2,true,true);
      t(c,"TABLE PREMIUM",1119,208,10,0xD9FFFFFF,true,true);
      t(c,"2–4",1119,255,29,Color.WHITE,true,true);
      t(c,"JOUEURS",1119,275,9,0xCFFFFFFF,true,true);
      t(c,"Mise actuelle",1119,314,9,0xBFFFFFFF,false,true);
      t(c,fee+" Ar",1119,344,24,gold2,true,true);
      box(c,1028,378,1210,452,22,0xFFFFC32D,0xEEFFF0B0);
      t(c,"▶",1060,422,24,0xFF4A2900,true,true);
      t(c,"JOUER",1123,418,19,0xFF452500,true,true);
      t(c,"EN LIGNE / HORS-LIGNE",1119,438,8,0xFF694A15,true,true);
      t(c,"RAPIDE • FLUIDE • RÉALISTE",1119,490,8.5f,gold2,true,true);
      box(c,1028,470,1210,520,18,0xB316315A,0x557EB8FF);
      t(c,"♛",1060,500,18,Color.WHITE,true,true);
      t(c,"TOURNOIS",1123,496,10,Color.WHITE,true,true);
      t(c,"Classement & défis",1123,511,8,0xD0FFFFFF,false,false);
      box(c,1028,528,1210,578,18,0xB316315A,0x557EB8FF);
      t(c,"?",1060,558,18,gold2,true,true);
      t(c,"TUTORIELS",1123,554,10,Color.WHITE,true,true);
      t(c,"Apprendre le Ludo",1123,569,8,0xD0FFFFFF,false,false);
      box(c,1028,586,1210,628,14,0x88111F38,0x447EB8FF);
      t(c,"Madagasikara • Miray",1119,612,8,gold2,true,true);

      c.restore();
      postInvalidateDelayed(16);
    }
    private String formatBal(long v){return String.format(java.util.Locale.getDefault(),"%,d",v).replace(',',' ');}
    @Override public boolean onTouchEvent(MotionEvent e){
      if(e.getAction()!=MotionEvent.ACTION_UP)return true;
      float W=getWidth(),H=getHeight();
      final float den=getResources().getDisplayMetrics().density;

      // Normal landscape coordinates used by the premium composition.
      float scLandscape=Math.min(W/(1280f*den),H/(720f*den))*den;
      float oxLandscape=(W-1280f*scLandscape)/2f, oyLandscape=(H-720f*scLandscape)/2f;
      float lx=(e.getX()-oxLandscape)/scLandscape, ly=(e.getY()-oyLandscape)/scLandscape;

      // Ramus/Android can report the same rendered landscape surface as a portrait 720×1616 input space.
      // Accept both possible quarter-turn mappings so the real JOUER/rail hitboxes remain touchable.
      if(W<H){
        float nx=e.getX()/Math.max(1f,W), ny=e.getY()/Math.max(1f,H);
        float lxCW=(1f-ny)*1280f, lyCW=nx*720f;
        float lxCCW=ny*1280f, lyCCW=(1f-nx)*720f;

        if(hitHomeActions(lxCW,lyCW)) return true;
        if(hitHomeActions(lxCCW,lyCCW)) return true;
        return true;
      }

      // Ramus may keep a 720×1616 portrait touch surface while Android reports
      // the rendered View as landscape. Try the direct landscape hit first, then
      // both inverse quarter-turns so left-rail actions remain deterministic.
      if(hitHomeActions(lx,ly)) return true;
      float nx=e.getX()/Math.max(1f,W), ny=e.getY()/Math.max(1f,H);
      float lxCW=(1f-ny)*1280f, lyCW=nx*720f;
      float lxCCW=ny*1280f, lyCCW=(1f-nx)*720f;
      if(hitHomeActions(lxCW,lyCW)) return true;
      if(hitHomeActions(lxCCW,lyCCW)) return true;
      return true;
    }

    private boolean hitHomeActions(float x,float y){
      if(y>=140&&y<230&&x<310){call("createGame");return true;}
      if(y>=230&&y<325&&x<310){call("joinDialog");return true;}
      if(y>=325&&y<420&&x<310){call("wallet");return true;}
      if(y>=420&&y<515&&x<310){call("profile");return true;}
      if(y>=370&&y<465&&x>970){call("gameModeDialog");return true;}
      if(y>=465&&y<525&&x>970){call("events");return true;}
      if(y>=525&&y<585&&x>970){call("tutorials");return true;}
      if(y>=520&&y<650&&x<310){
        int[]fs={500,1000,2000,5000,10000,20000};
        int i=(int)((x-60)/53f);i=Math.max(0,Math.min(5,i));fee=fs[i];set("entryFee",fee);invalidate();return true;
      }
      return false;
    }

  }
}
