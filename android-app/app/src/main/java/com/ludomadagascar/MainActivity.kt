package com.ludomadagascar

import android.app.*
import android.os.Bundle
import android.os.CancellationSignal
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.content.Intent
import android.net.Uri
import android.content.Context
import java.util.Locale
import android.widget.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.concurrent.thread
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialManagerCallback
import androidx.credentials.CustomCredential
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException

class MainActivity : Activity() {
    private val apiBase = BuildConfig.API_BASE_URL
    private val entryFees = listOf(500, 1000, 2000, 5000, 10000, 20000)
    private var entryFee = 500
    private var token = ""; private var refreshToken = ""; private var userId = ""; private var username = ""
    private var accountBalanceMGA: Long = 0L; private var topBalanceView: TextView? = null
    private var gameId = ""; private var version = 0; private var retryCount = 0; private var startingGame = false
    private var state: JSONObject? = null; private var currentScreen = ""
    private var offlineTurn = 0; private var offlineDice = 0; private var offlineState: JSONObject? = null; private var offlineHost: LudoBoardView? = null; private var offlinePlayers = 2
    private var walletBalanceView: TextView? = null
    private var selectedLanguage = "fr"
    private fun t(en:String, fr:String, mg:String) = when(selectedLanguage) { "fr" -> fr; "mg" -> mg; else -> en }
    private lateinit var root: LinearLayout; private lateinit var status: TextView; private lateinit var music: CalmMusic; private lateinit var musicButton: Button; private var musicA11yButton: Button? = null; private var musicStateA11y: TextView? = null; private var lobbyMusicRowRef: LinearLayout? = null
    private var boardHost: LudoBoardView? = null
    private var moveOverlay: View? = null
    private var movePending = false
    private val credentialManager by lazy { CredentialManager.create(this) }

    private fun dp(v:Int) = (v * resources.displayMetrics.density).toInt()
    private fun dp(v:Float) = (v * resources.displayMetrics.density).toInt()
    private fun shape(color:Int, radius:Float=20f, stroke:Int?=null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius.toInt()).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }
    private fun label(s:String, size:Float=16f, bold:Boolean=false, color:Int=Color.rgb(35,45,60)) = TextView(this).apply {
        text=s; textSize=size; setTextColor(color); if(bold) setTypeface(null, android.graphics.Typeface.BOLD)
        setPadding(dp(2),dp(2),dp(2),dp(2))
    }
    private fun add(v:View, h:Int=-2, top:Int=7, bottom:Int=7) {
        root.addView(v, LinearLayout.LayoutParams(-1, if(h==-1) -1 else dp(h)).apply { setMargins(0,dp(top),0,dp(bottom)) })
    }
    // Game screens use a physical-height design scale. Using dp here would multiply every
    // control by the emulator density and starve the board viewport on 720px-high devices.
    private fun gamePx(v:Int):Int = (v.toFloat() * resources.displayMetrics.heightPixels / 720f).toInt().coerceAtLeast(1)
    private fun addGame(v:View, h:Int, top:Int=4, bottom:Int=4) {
        root.addView(v, LinearLayout.LayoutParams(-1,gamePx(h)).apply { setMargins(0,gamePx(top),0,gamePx(bottom)) })
    }
    private val navy = Color.rgb(8,24,54)
    private val royal = Color.rgb(24,92,210)
    private val cyan = Color.rgb(34,171,255)
    private val emerald = Color.rgb(19,166,112)
    private val gold = Color.rgb(237,183,52)
    private fun darker(c:Int):Int = Color.rgb((Color.red(c)*.62f).toInt(),(Color.green(c)*.62f).toInt(),(Color.blue(c)*.62f).toInt())
    private fun button(s:String, accent:Int=royal, action:(()->Unit)?=null) = Button(this).apply {
        text=s; textSize=14f; isAllCaps=false; setTextColor(Color.WHITE); setTypeface(null,android.graphics.Typeface.BOLD)
        letterSpacing=.01f; setPadding(dp(16),0,dp(16),0)
        background=GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(accent,darker(accent))).apply{
            cornerRadius=dp(18).toFloat(); setStroke(dp(1),0x44FFFFFF)
        }
        minHeight=dp(54); stateListAnimator=null; elevation=dp(7).toFloat(); isHapticFeedbackEnabled=true
        setOnTouchListener { v,e -> when(e.actionMasked){ MotionEvent.ACTION_DOWN -> v.animate().scaleX(.985f).scaleY(.985f).setDuration(70L).start(); MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(110L).start() }; false }
        setOnClickListener { action?.invoke() }
    }
    private fun page(name:String, title:String): LinearLayout {
        currentScreen=name
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        val gameSurface=(name=="board" || name=="offline")
        val content=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(if(gameSurface)12 else 20),dp(if(gameSurface)10 else 18),dp(if(gameSurface)12 else 20),dp(if(gameSurface)14 else 20))
            background=if(gameSurface)
                GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(Color.rgb(8,32,63),Color.rgb(12,69,101),Color.rgb(7,29,56)))
            else
                GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(Color.rgb(249,251,255),Color.rgb(230,239,251),Color.rgb(246,249,254)))
        }
        content.setOnApplyWindowInsetsListener { v,insets ->
            val bottom=insets.systemWindowInsetBottom.coerceAtLeast(0)
            v.setPadding(dp(if(name=="board" || name=="offline")12 else 20),dp(if(name=="board" || name=="offline")10 else 18),dp(if(name=="board" || name=="offline")12 else 20),dp(if(name=="board" || name=="offline")14 else 20)+bottom)
            insets
        }
        root=content
        if(name=="board" || name=="offline") setContentView(root)
        else {
            val scroll=ScrollView(this)
            scroll.addView(content); setContentView(scroll)
        }
        content.requestApplyInsets()
        addTopBar(title)
        return content
    }

    private fun addTopBar(title:String) {
        val bar=LinearLayout(this).apply {
            gravity=Gravity.CENTER_VERTICAL; setPadding(dp(8),dp(6),dp(8),dp(6))
            background=if(currentScreen=="board" || currentScreen=="offline")
                GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(0xD9174168.toInt(),0xB80A2D50.toInt())).apply{
                    cornerRadius=dp(20).toFloat(); setStroke(dp(1),0x6697D6FF)
                }
            else
                GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(Color.argb(248,255,255,255),Color.argb(236,246,250,255))).apply{
                    cornerRadius=dp(22).toFloat(); setStroke(dp(1),Color.rgb(223,231,241))
                }; elevation=dp(4).toFloat()
        }
        bar.addView(BrandLogoView(this),LinearLayout.LayoutParams(dp(46),dp(46)))
        val wrap=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(10),0,0,0)}
        wrap.addView(label("LudoMada",18f,true,if(currentScreen=="board" || currentScreen=="offline") Color.WHITE else royal))
        wrap.addView(label(title.uppercase(Locale.getDefault()),11f,true,if(currentScreen=="board" || currentScreen=="offline") 0xD8CFEAFF.toInt() else Color.rgb(104,116,134)))
        bar.addView(wrap,LinearLayout.LayoutParams(0,-2,1f))
        val balanceBox=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(dp(8),dp(3),dp(8),dp(3))
            background=GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(Color.rgb(8,42,88),Color.rgb(24,92,160))).apply{
                cornerRadius=dp(13).toFloat();setStroke(dp(1),0x66FFFFFF)
            }
            setOnClickListener{if(token.isNotBlank())wallet() else login()}
        }
        balanceBox.addView(label("SOLDE DU COMPTE",8.5f,true,Color.WHITE))
        topBalanceView=label("— Ar",12f,true,gold);topBalanceView!!.gravity=Gravity.CENTER
        balanceBox.addView(topBalanceView,LinearLayout.LayoutParams(-2,dp(22)))
        bar.addView(balanceBox,LinearLayout.LayoutParams(dp(112),dp(48)))
        root.addView(bar,LinearLayout.LayoutParams(-1,-2).apply{setMargins(0,0,0,dp(14))})
    }
    private fun card(title:String, body:String, accent:Int=royal): LinearLayout {
        val outer=LinearLayout(this).apply{
            orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(6),dp(5),dp(8),dp(5))
            background=GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(Color.WHITE,Color.rgb(239,244,251))).apply{
                cornerRadius=dp(23).toFloat();setStroke(dp(1),Color.rgb(219,228,239))
            };elevation=dp(4).toFloat()
        }
        outer.addView(View(this).apply{background=GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(accent,darker(accent))).apply{cornerRadius=dp(8).toFloat()}},LinearLayout.LayoutParams(dp(7),dp(68)))
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(13),dp(10),dp(10),dp(10))}
        box.addView(label(title,17f,true,Color.rgb(24,41,65)))
        box.addView(label(body,13f,false,Color.rgb(95,107,125)),LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(4)})
        outer.addView(box,LinearLayout.LayoutParams(0,-2,1f)); return outer
    }


    private fun premiumTile(icon:String,title:String,body:String,accent:Int,action:(()->Unit)):LinearLayout{
        val box=LinearLayout(this).apply{
            orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(9),dp(8),dp(9),dp(8))
            background=GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(Color.WHITE,Color.rgb(239,244,251))).apply{
                cornerRadius=dp(20).toFloat();setStroke(dp(1),Color.rgb(220,228,240))
            };elevation=dp(3).toFloat();setOnClickListener{action()}
        }
        val iconView=TextView(this).apply{text=icon;textSize=22f;gravity=Gravity.CENTER;setTextColor(Color.WHITE);
            background=GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(accent,darker(accent))).apply{cornerRadius=dp(14).toFloat()}}
        box.addView(iconView,LinearLayout.LayoutParams(dp(50),dp(50)))
        val tx=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(10),0,0,0)}
        tx.addView(label(title,14f,true,Color.rgb(25,41,65)))
        tx.addView(label(body,11f,false,Color.rgb(102,114,132)),LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(2)})
        box.addView(tx,LinearLayout.LayoutParams(0,-2,1f));box.addView(label("›",24f,true,Color.rgb(149,159,174)))
        return box
    }

    private fun sectionTitle(title:String,subtitle:String=""){
        add(label(title,19f,true,Color.rgb(24,39,63)),31,4,1)
        if(subtitle.isNotBlank())add(label(subtitle,12f,false,Color.rgb(108,119,137)),24,0,6)
    }

    private fun bottomNav(active:String){
        val row=LinearLayout(this).apply{
            orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER;setPadding(dp(6),dp(5),dp(6),dp(5))
            background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(Color.WHITE,Color.rgb(235,241,249))).apply{
                cornerRadius=dp(21).toFloat();setStroke(dp(1),Color.rgb(218,227,238))
            };elevation=dp(6).toFloat()
        }
        listOf("home" to ("⌂" to "Accueil"),"play" to ("●" to "Jouer"),"wallet" to ("¤" to "Wallet"),"social" to ("♟" to "Social"),"profile" to ("◉" to "Profil")).forEach{(key,pair)->
            val b=TextView(this).apply{
                text=pair.first + "\n" + pair.second;textSize=10f;gravity=Gravity.CENTER;setTypeface(null,android.graphics.Typeface.BOLD)
                setTextColor(if(key==active)royal else Color.rgb(102,113,130));setPadding(0,dp(4),0,dp(4))
                setOnClickListener{when(key){"home"->home();"play"->gameModeDialog();"wallet"->wallet();"social"->social();"profile"->profile()}}
            }
            row.addView(b,LinearLayout.LayoutParams(0,dp(54),1f))
        }
        root.addView(row,LinearLayout.LayoutParams(-1,dp(66)).apply{setMargins(0,dp(10),0,0)})
    }

    private fun musicIconButton(): Button = Button(this).apply {
        text = if (music.enabled()) "🎵" else "🔇"
        textSize = 22f
        isAllCaps = false
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setPadding(0,0,0,0)
        minimumWidth = 0
        minimumHeight = 0
        minWidth = 0
        minHeight = 0
        contentDescription = if (music.enabled()) "MUSIC_TOGGLE: activée" else "MUSIC_TOGGLE: désactivée"
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        isFocusable = true
        isClickable = true
        background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.rgb(31,52,84), Color.rgb(12,29,55))
        ).apply {
            cornerRadius = dp(18).toFloat()
            setStroke(dp(1), 0x66FFFFFF)
        }
        elevation = dp(5f).toFloat()
        setOnTouchListener { v,e ->
            when(e.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(.92f).scaleY(.92f).setDuration(70L).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(110L).start()
            }
            false
        }
        setOnClickListener {
            music.toggle()
            updateMusicButton()
        }
    }

    private val sessionPrefs by lazy { getSharedPreferences("ludomada_session", Context.MODE_PRIVATE) }

    private fun saveSession() {
        sessionPrefs.edit()
            .putString("token", token)
            .putString("refreshToken", refreshToken)
            .putString("userId", userId)
            .putString("username", username)
            .apply()
    }

    private fun restoreSession():Boolean {
        val savedToken=sessionPrefs.getString("token","").orEmpty()
        if(savedToken.isBlank()) return false
        token=savedToken
        refreshToken=sessionPrefs.getString("refreshToken","").orEmpty()
        userId=sessionPrefs.getString("userId","").orEmpty()
        username=sessionPrefs.getString("username","").orEmpty()
        return true
    }

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        music=CalmMusic(this)
        // Premium Home is the first screen; authenticated state is restored in the background
        // and reused by Home/Wallet/online actions when present.
        restoreSession()
        home()
    }

    private fun splash() {
        currentScreen="splash"; root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(dp(30),0,dp(30),0);setBackgroundColor(Color.rgb(244,247,251))};setContentView(root)
        root.addView(BrandLogoView(this),LinearLayout.LayoutParams(dp(118),dp(118)))
        add(label("LudoMada",31f,true,Color.rgb(18,43,76)),42,10,4)
        add(label("Joue • Gagne • Profite",15f,false,Color.rgb(88,99,116)),28,0,8)
        add(label("Jeu en ligne + hors-ligne",13f,true,Color.rgb(15,79,199)),30,0,12)
        root.postDelayed({home()},700)
    }

    private fun login() {
        currentScreen="login"
        // Landscape-first login: everything important stays above the fold so Ramus and the user can reach offline play.
        root=LinearLayout(this).apply{
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.CENTER_VERTICAL
            setPadding(dp(18),dp(14),dp(18),dp(14))
            background=GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(4,18,44),Color.rgb(19,75,143),Color.rgb(7,34,74)))
        }
        setContentView(root)

        val brand=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            gravity=Gravity.CENTER
            setPadding(dp(12),dp(4),dp(18),dp(4))
        }
        brand.addView(BrandLogoView(this),LinearLayout.LayoutParams(dp(122),dp(122)).apply{bottomMargin=dp(6)})
        brand.addView(label("LudoMada",29f,true,Color.WHITE).apply{gravity=Gravity.CENTER})
        brand.addView(label("Joue • Gagne • Grandis",13f,false,Color.argb(235,246,249,255)).apply{gravity=Gravity.CENTER})
        brand.addView(label("2–4 joueurs  •  mises dès 500 Ar",11f,true,Color.rgb(255,198,55)).apply{
            gravity=Gravity.CENTER
            setPadding(dp(4),dp(10),dp(4),0)
        })
        brand.addView(label("Jeu en ligne + hors-ligne",11f,false,Color.argb(220,235,243,255)).apply{
            gravity=Gravity.CENTER
            setPadding(dp(4),dp(6),dp(4),0)
        })
        val offlineCard=TextView(this).apply{
            text="👥 Jouer hors-ligne"
            textSize=12.5f
            setTypeface(null,android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity=Gravity.CENTER
            contentDescription="Jouer hors-ligne"
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES
            isClickable=true
            isFocusable=true
            background=GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(83,99,122),Color.rgb(48,61,80))).apply{
                cornerRadius=dp(16).toFloat()
                setStroke(dp(1),0x55FFFFFF)
            }
            elevation=dp(5).toFloat()
            setOnClickListener{offlineMenu()}
        }
        brand.addView(offlineCard,LinearLayout.LayoutParams(dp(235),dp(46)).apply{topMargin=dp(14)})
        root.addView(brand,LinearLayout.LayoutParams(0,-1,.40f))

        val panel=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            gravity=Gravity.CENTER_HORIZONTAL
            setPadding(dp(16),dp(8),dp(8),dp(4))
            background=GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xB80B2144.toInt(),0x98264D7B.toInt())).apply{
                cornerRadius=dp(24).toFloat()
                setStroke(dp(1),0x557EB8FF)
            }
            elevation=dp(7).toFloat()
        }
        panel.addView(label("CONNEXION",18f,true,Color.WHITE).apply{gravity=Gravity.CENTER},LinearLayout.LayoutParams(-1,dp(28)))
        status=label("",10.5f,false,Color.rgb(255,181,188)).apply{gravity=Gravity.CENTER}
        panel.addView(status,LinearLayout.LayoutParams(-1,dp(22)).apply{topMargin=dp(1)})
        val fieldBg=Color.argb(238,255,255,255)
        val u=EditText(this).apply{
            hint="Nom, email ou mobile";textSize=14f
            setTextColor(Color.rgb(24,38,58));setHintTextColor(Color.rgb(115,126,142))
            background=shape(fieldBg,16f,0x55FFFFFF);setPadding(dp(15),0,dp(15),0);setSingleLine(true)
        }
        val p=EditText(this).apply{
            hint="Mot de passe";textSize=14f
            setTextColor(Color.rgb(24,38,58));setHintTextColor(Color.rgb(115,126,142))
            inputType=129;background=shape(fieldBg,16f,0x55FFFFFF);setPadding(dp(15),0,dp(15),0);setSingleLine(true)
        }
        panel.addView(u,LinearLayout.LayoutParams(-1,dp(46)).apply{topMargin=dp(4)})
        panel.addView(p,LinearLayout.LayoutParams(-1,dp(46)).apply{topMargin=dp(6)})

        fun actionButton(text:String,accent:Int,action:(()->Unit))=button(text,accent,action).apply{
            minHeight=dp(46);textSize=12.5f
        }
        panel.addView(actionButton("Se connecter",Color.rgb(28,118,246)){
            if(validAuth(u,p))auth("/api/auth/login",u.text.toString().trim(),p.text.toString())
        },LinearLayout.LayoutParams(-1,dp(46)).apply{topMargin=dp(8)})
        panel.addView(actionButton("Créer un compte",Color.rgb(16,170,119)){registerDialog()},
            LinearLayout.LayoutParams(-1,dp(46)).apply{topMargin=dp(5)})
        panel.addView(actionButton("G  Continuer avec Google",Color.rgb(88,106,132)){googleSignup()},
            LinearLayout.LayoutParams(-1,dp(46)).apply{topMargin=dp(5)})
        // Offline action is exposed from the left brand column so its accessibility bounds remain inside the landscape viewport.
        panel.addView(label("Paiements sécurisés côté serveur",9.5f,false,Color.argb(205,255,255,255)).apply{gravity=Gravity.CENTER},
            LinearLayout.LayoutParams(-1,dp(20)).apply{topMargin=dp(3)})
        val loginMusicRow=LinearLayout(this).apply{
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.END or Gravity.CENTER_VERTICAL
            addView(musicIconButton(),LinearLayout.LayoutParams(dp(46),dp(46)))
        }
        musicButton=loginMusicRow.getChildAt(0) as Button
        panel.addView(loginMusicRow,LinearLayout.LayoutParams(-1,dp(46)).apply{topMargin=dp(1)})
        root.addView(panel,LinearLayout.LayoutParams(0,-1,.60f))
    }

    private fun googleSignup() {
        status.text="Fanamarinana Google..."
        post("/api/auth/google/challenge",JSONObject(),null,{j->
            val nonce=j.optString("nonce")
            val clientId=j.optString("serverClientId").trim()
            if(nonce.isBlank() || clientId.isBlank()){
                status.text="Google Sign-In tsy mbola configuré amin'ny serveur."
                return@post
            }
            val option=GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(clientId)
                .setAutoSelectEnabled(false)
                .setNonce(nonce)
                .build()
            val request=GetCredentialRequest.Builder()
                .addCredentialOption(option)
                .build()
            credentialManager.getCredentialAsync(
                this,request,CancellationSignal(),mainExecutor,
                object:CredentialManagerCallback<GetCredentialResponse,GetCredentialException>{
                    override fun onResult(result:GetCredentialResponse){
                        val credential=result.credential
                        if(credential is CustomCredential && credential.type==GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL){
                            try{
                                val googleCredential=GoogleIdTokenCredential.createFrom(credential.data)
                                finishGoogleSignIn(googleCredential.idToken,nonce)
                            }catch(_:GoogleIdTokenParsingException){
                                status.text="Google: credential invalide."
                            }
                        }else{
                            status.text="Google: type de credential non supporté."
                        }
                    }
                    override fun onError(e:GetCredentialException){
                        status.text="Google: "+(e.message ?: "connexion annulée")
                    }
                }
            )
        },{msg->status.text=msg})
    }

    private fun finishGoogleSignIn(idToken:String,nonce:String) {
        if(idToken.isBlank() || nonce.isBlank()){
            status.text="Google: credential manquante."
            return
        }
        status.text="Validation du compte Google..."
        post("/api/auth/google",JSONObject().put("idToken",idToken).put("nonce",nonce),null,{j->
            token=j.optString("accessToken")
            refreshToken=j.optString("refreshToken")
            val usr=j.optJSONObject("user")
            userId=usr?.optString("id").orEmpty()
            username=usr?.optString("username").orEmpty()
            if(token.isBlank() || refreshToken.isBlank() || userId.isBlank()){
                status.text="Google: session serveur invalide."
            }else{
                saveSession()
                home()
            }
        },{msg->status.text=msg})
    }

    private fun registerDialog() {
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(8),0,dp(8),0)}
        val u=EditText(this).apply{hint="Username (3+ caractères)"};val email=EditText(this).apply{hint="Email (optionnel)";inputType=33}
        val phone=EditText(this).apply{hint="Numéro mobile (optionnel)";inputType=3};val pass=EditText(this).apply{hint="Password (10+ caractères)";inputType=129}
        listOf(u,email,phone,pass).forEach{box.addView(it,LinearLayout.LayoutParams(-1,dp(52)).apply{bottomMargin=dp(6)})}
        AlertDialog.Builder(this).setTitle("Créer un compte").setView(box).setPositiveButton("S'inscrire"){_,_->
            if(u.text.length<3||pass.text.length<10){status.text="Username 3+ sy password 10+ ilaina.";return@setPositiveButton}
            post("/api/auth/register",JSONObject().put("username",u.text.toString().trim()).put("password",pass.text.toString()).apply{if(email.text.isNotBlank())put("email",email.text.toString().trim());if(phone.text.isNotBlank())put("phoneNumber",phone.text.toString().trim())}){j->username=u.text.toString().trim();auth("/api/auth/login",u.text.toString().trim(),pass.text.toString())}
        }.setNegativeButton("Aoka",null).show()
    }

    private fun validAuth(u:EditText,p:EditText):Boolean { if(u.text.isBlank()||p.text.isBlank()){status.text="Fenoy ny identifiant sy mot de passe.";return false}; return true }
    private fun auth(path:String,u:String,p:String) {
        status.text="Connexion..."
        post(path,JSONObject().put("identifier",u).put("password",p)){j->
            token=j.optString("accessToken")
            refreshToken=j.optString("refreshToken")
            val usr=j.optJSONObject("user")
            userId=usr?.optString("id").orEmpty()
            username=usr?.optString("username").orEmpty()
            if(token.isBlank()) status.text="Connexion refusée. Vérifiez vos identifiants." else { saveSession(); home() }
        }
    }

    private fun formatMGA(value:Long):String {
        val s=value.toString(); return s.reversed().chunked(3).joinToString(" ").reversed()
    }

    override fun onResume() {
        super.onResume()
        if(::music.isInitialized && token.isNotBlank()) refreshAccountBalance()
    }

    private fun refreshAccountBalance() {
        if(token.isBlank()){
            accountBalanceMGA=0L
            topBalanceView?.text="— Ar"
            window.decorView.invalidate()
            return
        }
        topBalanceView?.text="… Ar"
        get("/api/wallet"){j->
            val value=j.optString("balanceMGA").toLongOrNull() ?: 0L
            accountBalanceMGA=value
            runOnUiThread{topBalanceView?.text="${formatMGA(value)} Ar";window.decorView.invalidate()}
        }
    }

    override fun onBackPressed() {
        when (currentScreen) {
            "offlineMenu" -> home()
            "offline" -> offlineMenu()
            "room" -> home()
            "lobby" -> home()
            "profile", "wallet", "history", "social", "events", "tutorials", "settings" -> home()
            else -> super.onBackPressed()
        }
    }

    private fun home() {
        currentScreen="home"
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        // Re-assert landscape after returning from login/offline navigation; cloud Android surfaces can retain portrait bounds.
        window.decorView.post { requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor=Color.TRANSPARENT
        window.navigationBarColor=Color.TRANSPARENT
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        ) and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
        window.setBackgroundDrawable(GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(Color.rgb(3,12,36),Color.rgb(57,18,67))))
        setContentView(PremiumHeroViewV2(this))
        refreshAccountBalance()
    }

    private fun profile() {
        page("profile","Profil")
        add(card("Kaonty","${if(username.isBlank())"—" else username}",Color.rgb(130,85,180)))
        add(button("Langue / Language",Color.rgb(37,93,196)){languageDialog()},52)
        add(card("Sécurité","Vos identifiants et paiements restent protégés côté serveur.",Color.rgb(37,93,196)))
        add(button("← Miverina",Color.rgb(70,82,100)){home()},52)
    }

    private fun tutorials() {
        page("tutorials","Tutoriels")
        sectionTitle("Maîtriser le Ludo","Les bases en quelques écrans")
        add(card("1 • Préparer la partie","Rejoins un lobby et choisis une mise.",royal),82,3,5)
        add(card("2 • Lancer le dé","Chaque lancer est validé côté serveur.",emerald),82,3,5)
        add(card("3 • Déplacer un pion","Choisis un pion autorisé après le dé.",Color.rgb(126,82,188)),82,3,5)
        add(card("4 • Gagner","La partie et le portefeuille sont réconciliés côté serveur.",gold),82,3,8)
        val lobbyHomeButton=button("← Accueil",Color.rgb(70,82,100)){home()}.apply{
            contentDescription="Accueil"
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        add(lobbyHomeButton,52)
    }

    private fun events() {
        page("events","Événements")
        add(card("Défi de la semaine","Parties spéciales et objectifs à suivre.",gold),88,3,8)
        add(premiumTile("✦","Prochain challenge","Bientôt disponible",gold){Toast.makeText(this,"Événement en préparation.",Toast.LENGTH_SHORT).show()},76,3,5)
        add(premiumTile("♛","Classement","Voir les meilleurs joueurs",royal){Toast.makeText(this,"Classement bientôt disponible.",Toast.LENGTH_SHORT).show()},76,3,8)
        add(button("← Accueil",Color.rgb(70,82,100)){home()},52)
    }

    private fun social() {
        page("social","Social")
        sectionTitle("Social","Retrouver tes amis et tes parties")
        add(premiumTile("♟","Amis","Invitations et joueurs récents",Color.rgb(126,82,188)){Toast.makeText(this,"Fonction sociale en préparation.",Toast.LENGTH_SHORT).show()},78,3,5)
        add(premiumTile("◉","Inviter","Partager une room",royal){joinDialog()},78,3,5)
        add(premiumTile("★","Défis","Jouer",emerald){lobby()},78,3,8)
        add(button("← Accueil",Color.rgb(70,82,100)){home()},52)
        bottomNav("social")
    }

    private fun shop() {
        page("shop","Boutique")
        sectionTitle("Boutique premium","Une identité visuelle prête pour les futurs objets")
        add(premiumTile("✦","Thèmes de plateau","Bientôt disponible",royal){Toast.makeText(this,"Boutique en préparation.",Toast.LENGTH_SHORT).show()},78,3,5)
        add(premiumTile("◆","Avatars","Profils et badges",Color.rgb(126,82,188)){Toast.makeText(this,"Avatars bientôt disponibles.",Toast.LENGTH_SHORT).show()},78,3,5)
        add(premiumTile("◈","Effets premium","Animations et styles",gold){Toast.makeText(this,"Effets bientôt disponibles.",Toast.LENGTH_SHORT).show()},78,3,8)
        add(button("← Accueil",Color.rgb(70,82,100)){home()},52)
        bottomNav("shop")
    }

    private fun languageDialog() {
        val languages=arrayOf("Malagasy", "Français", "English")
        AlertDialog.Builder(this).setTitle("🌐 Langue / Language").setSingleChoiceItems(languages,1){d,which-> selectedLanguage=when(which){0->"mg";1->"fr";else->"en"};
            recreate();Toast.makeText(this,"${languages[which]}",Toast.LENGTH_SHORT).show();d.dismiss()
        }.setNegativeButton("Fermer",null).show()
    }

    private fun paymentTile(title:String,subtitle:String,logo:String,accent:Int,action:(()->Unit)):LinearLayout{
        val box=LinearLayout(this).apply{
            orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(8),dp(8),dp(8),dp(8));isClickable=true;isFocusable=true
            background=GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(Color.WHITE,Color.rgb(232,239,249))).apply{cornerRadius=dp(19).toFloat();setStroke(dp(1),Color.rgb(207,220,237))}
            elevation=dp(7).toFloat();contentDescription="$title • $subtitle";importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES;setOnClickListener{action()}
            setOnTouchListener{v,e->when(e.actionMasked){MotionEvent.ACTION_DOWN->v.animate().translationY(dp(1).toFloat()).scaleX(.985f).scaleY(.985f).setDuration(70L).start();MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->v.animate().translationY(0f).scaleX(1f).scaleY(1f).setDuration(120L).start()};false}
        }
        val mark=TextView(this).apply{text=logo;textSize=17f;gravity=Gravity.CENTER;setTypeface(null,android.graphics.Typeface.BOLD);setTextColor(Color.WHITE);contentDescription="$title logo";importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES;background=GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(accent,darker(accent))).apply{cornerRadius=dp(14).toFloat();setStroke(dp(1),0x66FFFFFF)}}
        box.addView(mark,LinearLayout.LayoutParams(dp(52),dp(52)))
        val tx=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(10),0,dp(4),0)}
        tx.addView(label(title,14.5f,true,Color.rgb(24,41,65)))
        tx.addView(label(subtitle,10.5f,false,Color.rgb(98,112,132)),LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(2)})
        box.addView(tx,LinearLayout.LayoutParams(0,-2,1f));box.addView(label("›",25f,true,Color.rgb(138,151,170)))
        return box
    }

    private fun wallet() {
        page("wallet","Wallet")
        status=label("Maka ny solde...",15f,false,Color.rgb(88,99,116));add(status)
        walletBalanceView=label("— Ar",27f,true,Color.rgb(35,150,84));walletBalanceView!!.gravity=Gravity.CENTER
        val balanceCard=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(dp(12),dp(10),dp(12),dp(10));background=shape(Color.WHITE,22f,Color.rgb(219,228,239));elevation=dp(5).toFloat()}
        balanceCard.addView(label("SOLDE MGA",10f,true,Color.rgb(105,116,132)),LinearLayout.LayoutParams(-1,dp(20)))
        balanceCard.addView(walletBalanceView,LinearLayout.LayoutParams(-1,dp(45)))
        add(balanceCard,82,6,8)
        val actions=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
        actions.addView(button("➕ Dépôt • OFF",Color.rgb(95,105,118)){Toast.makeText(this,"Dépôt désactivé: aucun provider de paiement n’est actif.",Toast.LENGTH_LONG).show()},LinearLayout.LayoutParams(0,dp(52),1f).apply{setMargins(0,0,dp(5),0)})
        actions.addView(button("➖ Retrait • OFF",Color.rgb(95,105,118)){Toast.makeText(this,"Retrait désactivé: aucun provider de paiement n’est actif.",Toast.LENGTH_LONG).show()},LinearLayout.LayoutParams(0,dp(52),1f).apply{setMargins(dp(5),0,0,0)})
        add(actions,52)
        sectionTitle("Paiements disponibles","Choisis un service pour ouvrir son parcours sécurisé.")
        val payRow1=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
        payRow1.addView(paymentTile("MVola","DÉSACTIVÉ • reste OFF","mV",Color.rgb(28,111,224)){Toast.makeText(this,"MVola reste désactivé.",Toast.LENGTH_SHORT).show()},LinearLayout.LayoutParams(0,dp(72),1f).apply{setMargins(0,dp(3),dp(4),dp(3))})
        payRow1.addView(paymentTile("Airtel Money","DÉSACTIVÉ • reste OFF","AIR",Color.rgb(205,40,55)){Toast.makeText(this,"Airtel Money reste désactivé.",Toast.LENGTH_SHORT).show()},LinearLayout.LayoutParams(0,dp(72),1f).apply{setMargins(dp(4),dp(3),0,dp(3))})
        add(payRow1,72,0,0)
        val payRow2=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
        payRow2.addView(paymentTile("Orange Money","DÉSACTIVÉ • reste OFF","O",Color.rgb(247,133,18)){Toast.makeText(this,"Orange Money reste désactivé.",Toast.LENGTH_SHORT).show()},LinearLayout.LayoutParams(0,dp(72),1f).apply{setMargins(0,dp(3),dp(4),dp(3))})
        payRow2.addView(paymentTile("Binance Pay","DÉSACTIVÉ • crypto OFF","B",Color.rgb(25,44,74)){Toast.makeText(this,"Binance Pay reste désactivé.",Toast.LENGTH_SHORT).show()},LinearLayout.LayoutParams(0,dp(72),1f).apply{setMargins(dp(4),dp(3),0,dp(3))})
        add(payRow2,72,0,6)
        sectionTitle("Crypto-actifs","Logos/marks distincts • catalogue visuel seulement • aucune balance fictive")
        val cr1=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
        cr1.addView(paymentTile("Tether / USDT","Binance OFF • indisponible","₮",Color.rgb(38,145,105)){Toast.makeText(this,"USDT indisponible tant que Binance reste désactivé.",Toast.LENGTH_SHORT).show()},LinearLayout.LayoutParams(0,dp(72),1f).apply{setMargins(0,dp(3),dp(4),dp(3))})
        cr1.addView(paymentTile("Bitcoin / BTC","Binance OFF • indisponible","₿",Color.rgb(242,145,28)){Toast.makeText(this,"BTC indisponible tant que Binance reste désactivé.",Toast.LENGTH_SHORT).show()},LinearLayout.LayoutParams(0,dp(72),1f).apply{setMargins(dp(4),dp(3),0,dp(3))})
        add(cr1,72,0,0)
        val cr2=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
        cr2.addView(paymentTile("Ethereum / ETH","Binance OFF • indisponible","Ξ",Color.rgb(76,92,150)){Toast.makeText(this,"ETH indisponible tant que Binance reste désactivé.",Toast.LENGTH_SHORT).show()},LinearLayout.LayoutParams(0,dp(72),1f).apply{setMargins(0,dp(3),dp(4),dp(3))})
        cr2.addView(paymentTile("Solana / SOL","Binance OFF • indisponible","SOL",Color.rgb(95,62,155)){Toast.makeText(this,"SOL indisponible tant que Binance reste désactivé.",Toast.LENGTH_SHORT).show()},LinearLayout.LayoutParams(0,dp(72),1f).apply{setMargins(dp(4),dp(3),0,dp(3))})
        add(cr2,72,0,0)
        val cr3=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
        cr3.addView(paymentTile("Tron / TRX","Binance OFF • indisponible","TRX",Color.rgb(210,48,58)){Toast.makeText(this,"TRX indisponible tant que Binance reste désactivé.",Toast.LENGTH_SHORT).show()},LinearLayout.LayoutParams(0,dp(72),1f).apply{setMargins(0,dp(3),dp(4),dp(3))})
        cr3.addView(paymentTile("Crypto catalogue","Provider OFF • aucune action","◎",Color.rgb(78,92,112)){Toast.makeText(this,"Aucun provider crypto actif dans cette build.",Toast.LENGTH_SHORT).show()},LinearLayout.LayoutParams(0,dp(72),1f).apply{setMargins(dp(4),dp(3),0,dp(3))})
        add(cr3,72,0,6)
        add(button("📜 Historique",Color.rgb(70,82,100)){history()},52);add(button("↻ Havaozina",Color.rgb(37,93,196)){loadWallet()},52);add(button("⌂ Accueil",Color.rgb(55,65,80)){home()},52)
        bottomNav("wallet")
        loadWallet()
    }

    private fun loadWallet() {
        get("/api/wallet"){j->
            val value=j.optString("balanceMGA").toLongOrNull() ?: 0L
            accountBalanceMGA=value
            runOnUiThread{
                topBalanceView?.text="${formatMGA(value)} Ar"
                if(currentScreen=="wallet"){
                    status.text="Solde disponible"
                    walletBalanceView?.text="${formatMGA(value)} Ar"
                }
            }
        }
    }

    private fun history() {
        page("history","Historique")
        status=label("Maka ny transactions...",14f,false,Color.rgb(88,99,116));add(status)
        add(button("↻ Havaozina",Color.rgb(37,93,196)){loadHistory()},52);add(button("← Wallet",Color.rgb(70,82,100)){wallet()},52);loadHistory()
    }

    private fun loadHistory() { get("/api/wallet/transactions"){j->val rows=j.optJSONArray("items")?:j.optJSONArray("transactions");runOnUiThread{if(!::status.isInitialized||currentScreen!="history")return@runOnUiThread;status.text=if(rows==null||rows.length()==0)"Tsy mbola misy transaction." else buildString{append("${rows.length()} transaction(s)\n\n");for(i in 0 until minOf(rows.length(),30)){val x=rows.optJSONObject(i);append("• ${(x?.optString("type",x?.optString("kind","TRANSACTION") ?: "TRANSACTION") ?: "TRANSACTION")}: ${(x?.optString("amount","0") ?: "0")} Ar — ${(x?.optString("status","") ?: "")}${x?.optString("cryptoCurrency").takeUnless{it.isNullOrBlank()}?.let{" • $it"} ?: ""}\n")}}}} }

    private fun pay(deposit:Boolean, preselected:Int=0, cryptoCurrency:String?=null) {
        val amount=EditText(this).apply { hint="Montant (Ar)";inputType=2;setPadding(dp(12),0,dp(12),0) }
        val contact=EditText(this).apply { hint="Téléphone ou identifiant (si requis)";inputType=1;setPadding(dp(12),0,dp(12),0) }
        val methods=arrayOf("MVola","Airtel Money","Orange Money","Binance Pay / Crypto")
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(8),0,dp(8),0)}
        box.addView(amount,LinearLayout.LayoutParams(-1,dp(52)));box.addView(contact,LinearLayout.LayoutParams(-1,dp(52)).apply{topMargin=dp(6)})
        val cryptoNote=if(!cryptoCurrency.isNullOrBlank()) "Asset crypto sélectionné: ${cryptoCurrency.uppercase()}" else "Choisis un asset crypto depuis le menu Crypto avant un dépôt/retrait Binance."
        box.addView(label("Méthodes: MVola • Airtel Money • Orange Money • Binance Pay/Crypto. ${cryptoNote} Le serveur refuse automatiquement tout provider non activé; aucun crédit fictif.",11f,false,Color.rgb(95,107,125)).apply{setPadding(dp(2),dp(8),dp(2),0)})
        AlertDialog.Builder(this).setTitle(if(deposit)"Dépôt" else "Retrait").setView(box).setSingleChoiceItems(methods,preselected.coerceIn(0,3),null)
            .setPositiveButton("Hanohy"){d,_->val a=amount.text.toString().toLongOrNull()?:0;val selected=(d as AlertDialog).listView.checkedItemPosition.coerceIn(0,3);if(!deposit && selected==0){Toast.makeText(this,"MVola retrait: API non supportée par le provider actuel.",Toast.LENGTH_LONG).show();return@setPositiveButton};val method=arrayOf("MVOLA","AIRTEL_MONEY","ORANGE_MONEY","BINANCE")[selected];if(a>0)post(if(deposit)"/api/wallet/deposit" else "/api/wallet/withdraw",JSONObject().put("amountMGA",a).put("method",method).apply{if(contact.text.isNotBlank())put("customerPhone",contact.text.toString().trim());if(method=="BINANCE" && !cryptoCurrency.isNullOrBlank())put("cryptoCurrency",cryptoCurrency.uppercase())},UUID.randomUUID().toString(),{status.text=if(deposit)"Dépôt envoyé: PENDING" else "Retrait envoyé: PENDING";loadWallet()},{msg->status.text=msg})}
            .setNegativeButton("Aoka",null).show()
    }

    private fun gameModeDialog() {
        AlertDialog.Builder(this).setTitle("Choisissez le mode")
            .setItems(arrayOf("🌐 En ligne — Joueurs réels","👥 Hors-ligne — 2 à 4 joueurs")){_,which->if(which==0)lobby() else offlineMenu()}
            .setNegativeButton("Aoka",null).show()
    }

    private fun cryptoDialog() {
        if(token.isBlank()){login();return}
        val crypto=EditText(this).apply{hint="Montant crypto";inputType=8194;setPadding(dp(12),0,dp(12),0)}
        val mga=EditText(this).apply{hint="Montant MGA";inputType=2;setPadding(dp(12),0,dp(12),0)}
        val assetButton=Button(this).apply{isAllCaps=false;text="Asset: chargement…";setTextColor(Color.WHITE);background=GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(Color.rgb(38,102,185),Color.rgb(24,53,94))).apply{cornerRadius=dp(16).toFloat()}}
        val info=label("Lecture de la configuration crypto serveur…",11f,false,Color.rgb(95,107,125))
        val external=label("Wallet externe: aucun connecteur WalletConnect configuré dans cette build — aucun faux état de connexion.",10.5f,false,Color.rgb(125,82,42))
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(8),0,dp(8),0)}
        box.addView(assetButton,LinearLayout.LayoutParams(-1,dp(50)).apply{bottomMargin=dp(7)})
        box.addView(crypto,LinearLayout.LayoutParams(-1,dp(52)).apply{bottomMargin=dp(7)})
        box.addView(mga,LinearLayout.LayoutParams(-1,dp(52)).apply{bottomMargin=dp(7)})
        box.addView(info,LinearLayout.LayoutParams(-1,dp(46)))
        box.addView(external,LinearLayout.LayoutParams(-1,dp(54)))
        val actions=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
        val deposit=button("➕ Dépôt crypto",Color.rgb(35,150,84)){}
        val withdraw=button("➖ Retrait crypto",Color.rgb(210,55,65)){}
        actions.addView(deposit,LinearLayout.LayoutParams(0,dp(48),1f).apply{setMargins(0,dp(4),dp(4),0)})
        actions.addView(withdraw,LinearLayout.LayoutParams(0,dp(48),1f).apply{setMargins(dp(4),dp(4),0,0)})
        box.addView(actions,LinearLayout.LayoutParams(-1,dp(58)))
        var assets=emptyList<String>()
        var rates=JSONObject()
        var selected=""
        lateinit var dialog: AlertDialog
        fun applyAsset(asset:String){
            selected=asset.uppercase()
            assetButton.text="Asset crypto: $selected"
            val rate=rates.optDouble(selected,0.0)
            info.tag=rate
            info.text=if(rate>0) "1 $selected = ${formatDecimal(rate)} Ar" else "Taux non configuré pour $selected."
        }
        assetButton.setOnClickListener{
            if(assets.isNotEmpty()) AlertDialog.Builder(this).setTitle("Choisir un crypto-actif").setItems(assets.toTypedArray()){_,which->applyAsset(assets[which])}.setNegativeButton("Aoka",null).show()
        }
        deposit.setOnClickListener{
            val rate=if(selected.isBlank())0.0 else rates.optDouble(selected,0.0)
            if(selected.isBlank() || rate<=0) Toast.makeText(this,"Crypto-actif non configurable pour les transactions.",Toast.LENGTH_LONG).show() else {dialog.dismiss();pay(true,3,selected)}
        }
        withdraw.setOnClickListener{
            val rate=if(selected.isBlank())0.0 else rates.optDouble(selected,0.0)
            if(selected.isBlank() || rate<=0) Toast.makeText(this,"Crypto-actif non configurable pour les transactions.",Toast.LENGTH_LONG).show() else {dialog.dismiss();pay(false,3,selected)}
        }
        dialog=AlertDialog.Builder(this).setTitle("Crypto • MGA ↔ Crypto").setView(box)
            .setSingleChoiceItems(arrayOf("Crypto → MGA","MGA → Crypto"),0){_,which->
                val rateHolder=info.tag as? Double ?: 0.0
                if(rateHolder>0){
                    if(which==0){val v=crypto.text.toString().toDoubleOrNull()?:0.0;mga.setText(if(v>0)formatDecimal(v*rateHolder) else "")}
                    else{val v=mga.text.toString().toDoubleOrNull()?:0.0;crypto.setText(if(v>0)formatDecimal(v/rateHolder) else "")}
                }
            }
            .setPositiveButton("CONVERTIR"){_,_->Toast.makeText(this,"Conversion calculée avec le taux serveur. Aucun solde n’est crédité sans provider actif.",Toast.LENGTH_LONG).show()}
            .setNegativeButton("Aoka",null).create()
        dialog.show()
        get("/api/payments/config"){j->
            val b=j.optJSONObject("BINANCE")
            val arr=b?.optJSONArray("currencies")
            assets=(0 until (arr?.length()?:0)).mapNotNull{arr?.optString(it)?.trim()?.uppercase()?.takeIf{v->v.isNotBlank()}}
            rates=b?.optJSONObject("rates")?:JSONObject()
            val enabled=b?.optBoolean("enabled",false)==true
            runOnUiThread{
                if(assets.isNotEmpty()) applyAsset(assets.first()) else {assetButton.text="Crypto: aucun asset configuré";info.text="Aucun crypto-actif avec rate serveur n’est configuré."}
                external.text=if(enabled) "Wallet externe: aucun connecteur WalletConnect configuré. Binance Pay est le provider crypto serveur actif." else "Wallet externe: aucun connecteur WalletConnect configuré. Provider crypto non activé sur le serveur."
            }
        }
    }

    private fun formatDecimal(v:Double):String = String.format(java.util.Locale.US,"%.8f",v).trimEnd('0').trimEnd('.')
    private fun lobby() {
        page("lobby","Lalao")
        val lobbyMusicRow=LinearLayout(this).apply{
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.CENTER_VERTICAL
            contentDescription="Ambiance musicale"
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        lobbyMusicRowRef=lobbyMusicRow
        lobbyMusicRow.addView(label("Ambiance musicale",13f,true,Color.rgb(88,99,116)),LinearLayout.LayoutParams(0,dp(52),1f))

        musicButton=musicIconButton()

        // Stable automation target: identity never changes with state/locale.
        musicA11yButton=Button(this).apply{
            text=""
            setTextColor(Color.TRANSPARENT)
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription="LOBBY_MUSIC_TOGGLE"
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES
            isFocusable=true
            isClickable=true
            minWidth=0
            minHeight=0
            setOnClickListener{
                music.toggle()
                updateMusicButton()
            }
        }

        // Non-clickable state probe for deterministic Ramus assertions.
        musicStateA11y=TextView(this).apply{
            text=if(music.enabled()) "MUSIC_TOGGLE_ON" else "MUSIC_TOGGLE_OFF"
            textSize=1f
            setTextColor(Color.WHITE)
            alpha=0.01f
            contentDescription=text
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }

        val musicSlot=FrameLayout(this).apply{
            addView(musicButton,FrameLayout.LayoutParams(dp(52),dp(52)))
            addView(musicA11yButton,FrameLayout.LayoutParams(dp(52),dp(52)))
            val stateLp=FrameLayout.LayoutParams(dp(3),dp(3))
            stateLp.leftMargin=dp(1); stateLp.topMargin=dp(1)
            addView(musicStateA11y,stateLp)
        }
        lobbyMusicRow.addView(musicSlot,LinearLayout.LayoutParams(dp(52),dp(52)))
        add(lobbyMusicRow,52,0,5)

        updateMusicButton()

        add(card("Choisir la mise","500 • 1 000 • 2 000 • 5 000 • 10 000 • 20 000 Ar",Color.rgb(37,93,196)))
        status=label("Safidio ny vola hilalaovana.",14f,false,Color.rgb(88,99,116));add(status)
        val rows=listOf(entryFees.take(3),entryFees.drop(3))
        rows.forEach { fees ->
            val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
            fees.forEach { fee -> row.addView(button("${fee.toString().replace(Regex("(?<=\\d)(?=(\\d{3})+$)")," ")} Ar",Color.rgb(37,93,196)){ entryFee=fee; createGame() },LinearLayout.LayoutParams(0,dp(52),1f).apply{setMargins(dp(3),0,dp(3),0)}) }
            add(row,52)
        }
        add(button("🔑 Hiditra amin'ny room",Color.rgb(130,85,180)){joinDialog()},54)
        add(button("← Accueil",Color.rgb(70,82,100)){home()},52)
    }

    private fun createGame() {
        if(token.isBlank()){ login(); return }
        status.text="Mamorona room — $entryFee Ar..."
        post("/api/games",JSONObject().put("entryFeeMGA",entryFee),UUID.randomUUID().toString()){j->
            gameId=j.optString("gameId")
            if(gameId.isBlank())status.text="Tsy nahazo Game ID." else room()
        }
    }

    private fun joinDialog() {
        val id=EditText(this).apply{hint="Game ID";setPadding(dp(12),0,dp(12),0)}
        val choices=entryFees.map { "${it} Ar" }.toTypedArray()
        var selected=entryFees.indexOf(entryFee).coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("Hiditra amin'ny room").setView(id).setSingleChoiceItems(choices,selected){_,which->selected=which}
            .setPositiveButton("HIDITRA"){_,_->
                if(token.isBlank()){ login(); return@setPositiveButton }
                if(id.text.isNotBlank()){
                    gameId=id.text.toString().trim();entryFee=entryFees[selected]
                    post("/api/games/$gameId/join",JSONObject().put("entryFeeMGA",entryFee),UUID.randomUUID().toString()){room()}
                }
            }
            .setNegativeButton("AOKA",null).show()
    }

    private fun room() {
        page("room","Room")
        sectionTitle("Room de jeu","Prépare ta partie avant le lancement")
        add(card("MISE","$entryFee Ar",Color.rgb(37,93,196)),78,3,5)
        add(card("JOUEURS","En attente • 2 à 4 joueurs",Color.rgb(126,82,188)),78,3,5)
        add(label("CODE DE ROOM",12f,true,Color.rgb(88,99,116)),28,3,2)
        add(card("$gameId","Partage ce code avec tes joueurs",Color.rgb(12,147,87)),74,3,6)
        status=label("Connexion au lobby…",14f,true,Color.rgb(37,93,196));add(status,42,3,4)
        val actions=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
        actions.addView(button("↻ Actualiser",Color.rgb(37,93,196)){refreshLobby()},LinearLayout.LayoutParams(0,dp(52),1f).apply{setMargins(0,0,dp(5),0)})
        actions.addView(button("Quitter",Color.rgb(70,82,100)){gameId="";lobby()},LinearLayout.LayoutParams(0,dp(52),1f).apply{setMargins(dp(5),0,0,0)})
        add(actions,52,3,8)
        add(card("Lancement automatique","La partie démarre dès que 2 joueurs sont présents. État et portefeuille restent validés côté serveur.",Color.rgb(235,170,25)),82,3,8)
        refreshLobby()
    }

    private fun startGame(){
        startingGame=true
        status.text="Manomboka ny lalao..."
        post("/api/games/$gameId/start",JSONObject(),UUID.randomUUID().toString(),{
            startingGame=false
            showGameEntryLoading()
            root.postDelayed({ if(gameId.isNotBlank()) refreshState() }, 350L)
        },{msg->
            startingGame=false
            status.text=msg
        })
    }

    private fun scheduleLobbyRefresh(){root.postDelayed({if(currentScreen=="room"&&gameId.isNotBlank())refreshLobby()},3000)}
    private fun refreshLobby(){get("/api/games/$gameId/lobby"){j->retryCount=0;val n=j.optJSONArray("participants")?.length()?:0;val prize=n*entryFee;runOnUiThread{if(currentScreen=="room"){status.text="👥 Mpilalao: $n / 4   •   Prize pool: $prize Ar";if(n>=2){if(j.optString("creatorId")==userId && j.optString("status")=="WAITING" && !startingGame)startGame();else refreshState()} else scheduleLobbyRefresh()}}}}

    private fun offlineMenu() {
        page("offlineMenu","Hors-ligne")
        add(card("Ludo hors-ligne","2–4 joueurs  •  sans Internet",Color.rgb(130,85,180)),74,2,5)

        // Compact 2×2 selector: all actions remain above the fold on landscape.
        val row1=LinearLayout(this).apply{
            orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER
        }
        row1.addView(button("👤 2 joueurs",Color.rgb(37,93,196)){startOffline(2)}.apply{
            contentDescription="OFFLINE_2_PLAYERS"
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }, LinearLayout.LayoutParams(0,dp(48),1f).apply{setMargins(0,0,dp(5),dp(4))})
        row1.addView(button("👥 3 joueurs",Color.rgb(37,93,196)){startOffline(3)}.apply{
            contentDescription="OFFLINE_3_PLAYERS"
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }, LinearLayout.LayoutParams(0,dp(48),1f).apply{setMargins(dp(5),0,0,dp(4))})
        add(row1,48,0,0)

        val row2=LinearLayout(this).apply{
            orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER
        }
        row2.addView(button("👥 4 joueurs",Color.rgb(37,93,196)){startOffline(4)}.apply{
            contentDescription="OFFLINE_4_PLAYERS"
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }, LinearLayout.LayoutParams(0,dp(48),1f).apply{setMargins(0,dp(4),dp(5),dp(5))})
        val homeButton=button("← Accueil",Color.rgb(70,82,100)){home()}.apply{
            contentDescription="Accueil"
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        row2.addView(homeButton,
            LinearLayout.LayoutParams(0,dp(48),1f).apply{setMargins(dp(5),dp(4),0,dp(5))})
        add(row2,48,0,0)
    }

    private fun startOffline(players:Int) {
        offlineTurn=0;offlineDice=0;offlinePlayers=players;offlineHost=null
        val arr=org.json.JSONArray();for(i in 0 until players){val ts=org.json.JSONArray();for(t in 0..3)ts.put(JSONObject().put("pos",-1));arr.put(JSONObject().put("id","offline-$i").put("tokens",ts))}
        offlineState=JSONObject().put("players",arr)
        offlineGame(players)
    }

    private fun offlineGame(players:Int) {
        page("offline","Ludo hors-ligne")
        val info=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        status=label("Joueur ${offlineTurn+1}/$players • Dé: $offlineDice",13f,true,Color.rgb(37,93,196));info.addView(status,LinearLayout.LayoutParams(0,-2,1f));info.addView(label("HORS-LIGNE",13f,true,Color.rgb(130,85,180)));addGame(info,34,0,4)
        val host=LudoBoardView(this);host.onRollTap={offlineRoll(host)};host.onPawnTap={offlineMove(host,players,it)};host.state=offlineState;host.player=offlineTurn;offlineHost=host
        root.addView(host,LinearLayout.LayoutParams(-1,0,1f).apply{setMargins(0,gamePx(2),0,gamePx(4))})
        host.animateState()
        addGame(button("🎲 Lancer le dé",Color.rgb(235,170,25)){offlineRoll(host)}.apply{
            contentDescription="GAME_DICE_ROLL"
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES
        },54,3,3)
        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
        for(i in 0..3)row.addView(button("Pion ${i+1}",Color.rgb(70,82,100)){offlineMove(host,players,i)}.apply{
            contentDescription="GAME_PAWN_${i+1}"
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES
        },LinearLayout.LayoutParams(0,gamePx(46),1f).apply{setMargins(gamePx(2),0,gamePx(2),0)})
        addGame(row,46,2,3);addGame(button("← Hors-ligne",Color.rgb(70,82,100)){offlineMenu()},44,2,2)
    }

    private fun offlineGlobalSquare(player:Int,pos:Int):Int? = if(pos in 0..51) ((player*13)+pos)%52 else null
    private fun offlineCanMove(pos:Int,dice:Int):Boolean = if(dice !in 1..6) false else if(pos<0) dice==6 else pos+dice<=57
    private fun offlineHasLegalMove(playersState:org.json.JSONArray,dice:Int,player:Int):Boolean {
        val p=playersState.optJSONObject(player)?:return false
        val ts=p.optJSONArray("tokens")?:return false
        for(i in 0 until minOf(4,ts.length())) if(offlineCanMove(ts.optJSONObject(i)?.optInt("pos",-1)?:-1,dice)) return true
        return false
    }
    private fun advanceOfflineTurn(players:Int,host:LudoBoardView){
        offlineTurn=(offlineTurn+1)%players;host.player=offlineTurn
        status.text="Joueur ${offlineTurn+1}/$players • Dé: 0";host.invalidate()
    }
    private fun offlineRoll(host:LudoBoardView){
        if(host.isDiceRolling())return
        val ps=offlineState?.optJSONArray("players")?:return
        host.startDiceAnimation()
        offlineDice=(1..6).random()
        offlineState?.put("dice",offlineDice)
        if(!offlineHasLegalMove(ps,offlineDice,offlineTurn)){
            status.text="Joueur ${offlineTurn+1} • Dé: $offlineDice • Aucun coup — tour suivant"
            offlineDice=0
            root.postDelayed({if(currentScreen=="offline")advanceOfflineTurn(offlinePlayers,host)},550L)
            return
        }
        status.text="Joueur ${offlineTurn+1} • Dé: $offlineDice";host.invalidate()
    }
    private fun offlineMove(host:LudoBoardView,players:Int,tokenIndex:Int){
        if(offlineDice==0){status.text="Atsipazo aloha ny dé.";return}
        val ps=offlineState?.optJSONArray("players")?:return
        val p=ps.optJSONObject(offlineTurn)?:return
        val ts=p.optJSONArray("tokens")?:return
        val tok=ts.optJSONObject(tokenIndex)?:return
        val old=tok.optInt("pos",-1);val dice=offlineDice
        if(!offlineCanMove(old,dice)){status.text=if(old<0)"Mila 6 vao mivoaka ny pion." else "Tsy afaka mihetsika: tsy ampy ny toerana sisa.";return}
        val next=if(old<0)0 else old+dice
        tok.put("pos",next)
        var captured=0
        val landing=offlineGlobalSquare(offlineTurn,next)
        if(landing!=null){
            val safe=intArrayOf(0,8,13,21,26,34,39,47).contains(landing)
            if(!safe){
                for(pi in 0 until minOf(players,ps.length())){
                    if(pi==offlineTurn)continue
                    val other=ps.optJSONObject(pi)?:continue
                    val ots=other.optJSONArray("tokens")?:continue
                    for(ti in 0 until minOf(4,ots.length())){
                        val ot=ots.optJSONObject(ti)?:continue
                        if(offlineGlobalSquare(pi,ot.optInt("pos",-1))==landing){ot.put("pos",-1);captured++}
                    }
                }
            }
        }
        offlineDice=0;offlineState?.put("dice",0);host.state=offlineState;host.player=offlineTurn;host.animateState()
        val finished=(0 until minOf(4,ts.length())).all{ts.optJSONObject(it)?.optInt("pos",-1)==57}
        if(finished){
            offlineState?.put("phase","FINISHED")?.put("winnerId","offline-$offlineTurn")
            status.text="🏆 Joueur ${offlineTurn+1} gagne la partie !"
            host.invalidate();return
        }
        val extra=dice==6 || captured>0
        status.text="Joueur ${offlineTurn+1} • Dé: 0 • ${if(captured>0)"Capture ! " else ""}Déplacement…"
        root.postDelayed({if(currentScreen=="offline"){if(extra){status.text="Joueur ${offlineTurn+1}/$players • Rejouez — Dé: 0"}else advanceOfflineTurn(players,host)}},700L)
    }
    private fun board(){page("board","Lalao")
        val info=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        val st=label("",13f,true,Color.rgb(37,93,196));info.addView(st,LinearLayout.LayoutParams(0,-2,1f));info.addView(label("$entryFee Ar",15f,true,Color.rgb(235,170,25)));add(info,32,0,5)
        val host=LudoBoardView(this);host.onRollTap={roll()};host.onPawnTap={move(it)};boardHost=host; root.addView(host,LinearLayout.LayoutParams(-1,0,1f).apply{setMargins(0,dp(4),0,dp(8))})
        status=st;add(button("🎲 Lancer le dé",Color.rgb(235,170,25)){roll()},54);add(button("↻ Synchroniser",Color.rgb(37,93,196)){refreshState()},52)
        val tokens=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
        for(i in 0..3){tokens.addView(button("Pion ${i+1}",Color.rgb(70,82,100)){move(i)},LinearLayout.LayoutParams(0,dp(46),1f).apply{setMargins(dp(2),0,dp(2),0)})};add(tokens,46,2,4)
        add(label("✨ Safidio mivantana ilay pion misy famirapiratana rehefa afaka mihetsika.",12f,false,Color.rgb(120,128,140)),30,0,0)
        updateBoard(host);root.postDelayed({if(currentScreen=="board"&&gameId.isNotBlank())refreshState()},4000)
    }

    private fun updateBoard(host:LudoBoardView){
        host.state=state;host.player=playerIndex();host.animateState();
        val i=state?.optInt("currentPlayerIndex",0)?:0;val d=state?.optInt("dice",0)?:0
        status.text="Tour: ${listOf("🔴","🟢","🟡","🔵").getOrElse(i){"?"}}  •  Dé: $d"
    }

    private fun refreshState(){get("/api/games/$gameId/state"){j->
        retryCount=0;state=j;version=j.optInt("stateVersion",0)
        runOnUiThread{
            val serverStatus=j.optString("status")
            if(serverStatus=="PLAYING" && currentScreen!="board"){
                showBoardEntry()
                return@runOnUiThread
            }
            if(currentScreen=="board" && boardHost!=null) updateBoard(boardHost!!)
            else if(currentScreen=="board") board()
        }
    }}
    private fun playerIndex():Int{val a=state?.optJSONArray("players")?:return -1;for(i in 0 until a.length())if(a.optJSONObject(i)?.optString("id")==userId)return i;return -1}
    private fun roll(){
        if(gameId.isBlank() || movePending || boardHost==null) return
        val host=boardHost ?: return
        host.startDiceAnimation()
        post("/api/games/$gameId/roll",JSONObject().put("expectedVersion",version),UUID.randomUUID().toString(),{
            refreshState()
        },{msg->if(::status.isInitialized)status.text=msg})
    }
    private fun move(i:Int){
        val ps=state?.optJSONArray("players")
        val pi=playerIndex()
        val dice=state?.optInt("dice",0)?:0
        val pos=ps?.optJSONObject(pi)?.optJSONArray("tokens")?.optJSONObject(i)?.optInt("pos",-1)?:-1
        if(pi !in 0..3 || dice !in 1..6 || (pos<0 && dice!=6) || (pos>=0 && pos+dice>57)){
            if(::status.isInitialized) status.text="Tsy afaka mihetsika io pion io amin'ity dé ity."
            return
        }
        requestMove(i)
    }

    private fun showGameEntryLoading() {
        if(moveOverlay!=null) return
        val overlay=FrameLayout(this).apply{
            setBackgroundColor(0x66081936)
            isClickable=true
            isFocusable=true
        }
        val card=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            gravity=Gravity.CENTER
            setPadding(dp(26),dp(22),dp(26),dp(22))
            background=GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.WHITE,Color.rgb(234,242,253))
            ).apply{
                cornerRadius=dp(22).toFloat()
                setStroke(dp(1),Color.rgb(212,225,242))
            }
            elevation=dp(12).toFloat()
        }
        card.addView(ProgressBar(this).apply{isIndeterminate=true},LinearLayout.LayoutParams(dp(42),dp(42)))
        card.addView(label("Préparation de la partie…",16f,true,Color.rgb(25,52,88)).apply{
            gravity=Gravity.CENTER
            setPadding(0,dp(10),0,0)
        })
        card.addView(label("Synchronisation du plateau",11f,false,Color.rgb(106,120,140)).apply{
            gravity=Gravity.CENTER
            setPadding(0,dp(4),0,0)
        })
        overlay.addView(card,FrameLayout.LayoutParams(dp(285),-2,Gravity.CENTER))
        (window.decorView as ViewGroup).addView(overlay,ViewGroup.LayoutParams(-1,-1))
        moveOverlay=overlay
    }

    private fun showMoveProgress(){
        if(moveOverlay!=null) return
        val overlay=FrameLayout(this).apply{
            setBackgroundColor(0x33081936)
            isClickable=true
            isFocusable=true
        }
        val box=LinearLayout(this).apply{
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.CENTER
            setPadding(dp(18),dp(12),dp(18),dp(12))
            background=GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.WHITE,Color.rgb(239,246,255))
            ).apply{
                cornerRadius=dp(18).toFloat()
                setStroke(dp(1),Color.rgb(213,226,242))
            }
            elevation=dp(9).toFloat()
        }
        box.addView(ProgressBar(this).apply{isIndeterminate=true},LinearLayout.LayoutParams(dp(28),dp(28)))
        box.addView(label("Déplacement…",14f,true,Color.rgb(31,82,150)).apply{
            setPadding(dp(12),0,0,0)
        })
        overlay.addView(box,FrameLayout.LayoutParams(-2,-2,Gravity.CENTER))
        (window.decorView as ViewGroup).addView(overlay,ViewGroup.LayoutParams(-1,-1))
        moveOverlay=overlay
    }

    private fun hideTransientOverlay(){
        val v=moveOverlay ?: return
        (v.parent as? ViewGroup)?.removeView(v)
        moveOverlay=null
    }

    private fun requestMove(i:Int){
        if(gameId.isBlank() || movePending) return
        movePending=true
        showMoveProgress()
        post(
            "/api/games/$gameId/move",
            JSONObject().put("tokenIndex",i).put("expectedVersion",version),
            UUID.randomUUID().toString(),
            {
                movePending=false
                hideTransientOverlay()
                refreshState()
            },
            {msg->
                movePending=false
                hideTransientOverlay()
                if(::status.isInitialized) status.text=msg
            }
        )
    }

    private fun showBoardEntry(){
        hideTransientOverlay()
        currentScreen="board"
        board()
    }

    private fun clearSession() {
        token=""
        refreshToken=""
        userId=""
        username=""
        gameId=""
        accountBalanceMGA=0L
        sessionPrefs.edit().clear().apply()
    }

    private fun refreshAccessToken(failedToken:String):Boolean {
        synchronized(sessionRefreshLock) {
            if(token.isNotBlank() && token != failedToken) return true
            val rt=refreshToken
            if(rt.isBlank()) return false
            return try {
                val c=(URL(apiBase+"/api/auth/refresh").openConnection() as HttpURLConnection).apply {
                    requestMethod="POST"
                    connectTimeout=10000
                    readTimeout=10000
                    doInput=true
                    doOutput=true
                    setRequestProperty("Content-Type","application/json")
                }
                c.outputStream.use { it.write(JSONObject().put("refreshToken",rt).toString().toByteArray()) }
                val code=c.responseCode
                val tx=(if(code in 200..299)c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
                if(code !in 200..299) return false
                val j=JSONObject(tx)
                val newAccess=j.optString("accessToken")
                val newRefresh=j.optString("refreshToken")
                if(newAccess.isBlank() || newRefresh.isBlank()) return false
                token=newAccess
                refreshToken=newRefresh
                saveSession()
                true
            } catch(_:Exception) { false }
        }
    }

    private val sessionRefreshLock=Any()

    private fun get(path:String, ok:(JSONObject)->Unit)=request("GET",path,null,null,ok)
    private fun post(path:String, body:JSONObject, id:String?=null, ok:(JSONObject)->Unit)=request("POST",path,body,id,ok)
    private fun post(path:String, body:JSONObject, id:String?, ok:(JSONObject)->Unit, fail:((String)->Unit))=request("POST",path,body,id,ok,fail)
    private fun request(method:String,path:String,body:JSONObject?,id:String?,ok:(JSONObject)->Unit, fail:((String)->Unit)?=null, allowRefresh:Boolean=true){
        if(::status.isInitialized) runOnUiThread{status.text="Mampifandray..."}
        thread {
            try {
                val requestToken=token
                val c=(URL(apiBase+path).openConnection() as HttpURLConnection).apply{requestMethod=method;connectTimeout=10000;readTimeout=10000;doInput=true
                    setRequestProperty("Content-Type","application/json");if(requestToken.isNotBlank())setRequestProperty("Authorization","Bearer $requestToken");if(!id.isNullOrBlank())setRequestProperty("Idempotency-Key",id)
                    if(body!=null)doOutput=true}
                if(body!=null)c.outputStream.use{it.write(body.toString().toByteArray())}
                val code=c.responseCode;val input=if(code in 200..299)c.inputStream else c.errorStream;val tx=input?.bufferedReader()?.readText().orEmpty()
                if(code==401 && allowRefresh && path!="/api/auth/refresh" && refreshAccessToken(requestToken)) {
                    return@thread request(method,path,body,id,ok,fail,false)
                }
                if(code !in 200..299){
                    val msg=try{
                        val e=JSONObject(tx).optJSONObject("error")
                        e?.optString("message").takeUnless{it.isNullOrBlank()} ?: JSONObject(tx).optString("error","Erreur HTTP $code")
                    }catch(_:Exception){"Erreur HTTP $code"}
                    runOnUiThread{
                        if(code==401 && path!="/api/auth/login" && path!="/api/auth/register"){
                            clearSession()
                            if(currentScreen!="login") login()
                        }
                        if(fail!=null)fail.invoke(msg) else if(::status.isInitialized)status.text=msg
                    };return@thread
                }
                runOnUiThread{
                    retryCount=0
                    val payload=if(tx.isBlank())JSONObject()else JSONObject(tx)
                    ok(payload.optJSONObject("data") ?: payload)
                }
            } catch(e:Exception){retryCount++;runOnUiThread{if(fail!=null)fail.invoke("Connexion tapaka. Averina... ($retryCount)") else if(::status.isInitialized)status.text="Connexion tapaka. Averina... ($retryCount)";if(currentScreen=="room")root.postDelayed({if(currentScreen=="room")refreshLobby()},minOf(15000L,3000L*retryCount))}}
        }
    }

    // Gameplay rendering is implemented by the dedicated OpenGL ES scene in LudoBoardView.kt.
    private fun musicLabel():String = if(music.enabled()) "Musique activée" else "Musique désactivée"
    private fun updateMusicButton(){
        val enabled=music.enabled()
        val humanState=if(enabled) "Musique activée" else "Musique désactivée"
        val stableState=if(enabled) "MUSIC_TOGGLE_ON" else "MUSIC_TOGGLE_OFF"
        if(::musicButton.isInitialized){
            musicButton.text=if(enabled) "🎵" else "🔇"
            musicButton.contentDescription=humanState
        }
        musicA11yButton?.apply{
            contentDescription="LOBBY_MUSIC_TOGGLE"
            sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        }
        musicStateA11y?.apply{
            text=stableState
            contentDescription=stableState
            sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        }
        lobbyMusicRowRef?.apply{
            contentDescription="Ambiance musicale"
            sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        }
    }

    override fun onStart(){ super.onStart(); if(::music.isInitialized) music.start() }
    override fun onStop(){ if(::music.isInitialized) music.stop(); super.onStop() }

}
