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
    private lateinit var root: LinearLayout; private lateinit var status: TextView; private lateinit var music: CalmMusic; private lateinit var musicButton: Button
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
        val content=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(20),dp(18),dp(20),dp(20))
            background=GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(Color.rgb(249,251,255),Color.rgb(230,239,251),Color.rgb(246,249,254)))
        }
        content.setOnApplyWindowInsetsListener { v,insets ->
            val bottom=insets.systemWindowInsetBottom.coerceAtLeast(0)
            v.setPadding(dp(20),dp(18),dp(20),dp(20)+bottom)
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
            gravity=Gravity.CENTER_VERTICAL; setPadding(dp(8),dp(7),dp(8),dp(7))
            background=GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(Color.argb(248,255,255,255),Color.argb(236,246,250,255))).apply{
                cornerRadius=dp(22).toFloat(); setStroke(dp(1),Color.rgb(223,231,241))
            }; elevation=dp(4).toFloat()
        }
        bar.addView(BrandLogoView(this),LinearLayout.LayoutParams(dp(46),dp(46)))
        val wrap=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(10),0,0,0)}
        wrap.addView(label("LudoMada",18f,true,royal))
        wrap.addView(label(title.uppercase(Locale.getDefault()),11f,true,Color.rgb(104,116,134)))
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
        contentDescription = if (music.enabled()) "Musique activée" else "Musique désactivée"
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
        music=CalmMusic(this)
        val hasSession = restoreSession()
        if (hasSession && token.isNotBlank()) home() else splash()
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
        root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_HORIZONTAL;setPadding(dp(22),dp(22),dp(22),dp(18))}
        root.background=GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(Color.rgb(5,23,55),Color.rgb(11,70,145),Color.rgb(3,27,67)))
        setContentView(root)
        val brand=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_HORIZONTAL}
        brand.addView(BrandLogoView(this),LinearLayout.LayoutParams(dp(104),dp(104)))
        brand.addView(label("LudoMada",27f,true,Color.WHITE))
        brand.addView(label("Le Ludo malagasy, en ligne ou hors-ligne",12f,false,Color.argb(217,233,243,255)))
        add(brand,152,0,7)
        add(label("2–4 joueurs  •  mises dès 500 Ar",12f,true,Color.WHITE),25,1,4)
        status=label("",12f,false,Color.rgb(255,181,188));add(status,28,0,4)
        val fieldBg=Color.argb(235,255,255,255)
        val u=EditText(this).apply{hint="Nom, email ou mobile";textSize=15f;setTextColor(Color.rgb(24,38,58));setHintTextColor(Color.rgb(115,126,142));background=shape(fieldBg,18f,0x55FFFFFF);setPadding(dp(16),0,dp(16),0);setSingleLine(true)}
        val p=EditText(this).apply{hint="Mot de passe";textSize=15f;setTextColor(Color.rgb(24,38,58));setHintTextColor(Color.rgb(115,126,142));inputType=129;background=shape(fieldBg,18f,0x55FFFFFF);setPadding(dp(16),0,dp(16),0);setSingleLine(true)}
        add(u,52,5,5);add(p,52,5,9)
        add(button("Se connecter",Color.rgb(28,118,246)){if(validAuth(u,p))auth("/api/auth/login",u.text.toString().trim(),p.text.toString())},53,4,6)
        add(button("Créer un compte",Color.rgb(16,170,119)){registerDialog()},53,4,5)
        add(button("G  Continuer avec Google",Color.rgb(245,247,251)){googleSignup()},50,3,5)
        add(button("👥 Jouer hors-ligne",Color.rgb(70,82,100)){offlineMenu()},50,3,7)
        val secure=TextView(this).apply{text="Paiements sécurisés côté serveur";textSize=11f;setTextColor(Color.argb(217,255,255,255));gravity=Gravity.CENTER;setPadding(dp(4),0,dp(4),0)}
        add(secure,28,2,3)
        val loginMusicRow=LinearLayout(this).apply{
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.END or Gravity.CENTER_VERTICAL
            addView(musicIconButton(),LinearLayout.LayoutParams(dp(52),dp(52)))
        }
        musicButton = loginMusicRow.getChildAt(0) as Button
        add(loginMusicRow,52,4,0)
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

    private fun home() {
        currentScreen="home"
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
        add(button("← Accueil",Color.rgb(70,82,100)){home()},52)
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
            elevation=dp(7).toFloat();setOnClickListener{action()}
            setOnTouchListener{v,e->when(e.actionMasked){MotionEvent.ACTION_DOWN->v.animate().translationY(dp(1).toFloat()).scaleX(.985f).scaleY(.985f).setDuration(70L).start();MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->v.animate().translationY(0f).scaleX(1f).scaleY(1f).setDuration(120L).start()};false}
        }
        val mark=TextView(this).apply{text=logo;textSize=17f;gravity=Gravity.CENTER;setTypeface(null,android.graphics.Typeface.BOLD);setTextColor(Color.WHITE);background=GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(accent,darker(accent))).apply{cornerRadius=dp(14).toFloat();setStroke(dp(1),0x66FFFFFF)}}
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
        actions.addView(button("➕ Dépôt",Color.rgb(35,150,84)){pay(true)},LinearLayout.LayoutParams(0,dp(52),1f).apply{setMargins(0,0,dp(5),0)})
        actions.addView(button("➖ Retrait",Color.rgb(210,55,65)){pay(false)},LinearLayout.LayoutParams(0,dp(52),1f).apply{setMargins(dp(5),0,0,0)})
        add(actions,52)
        sectionTitle("Paiements disponibles","Choisis un service pour ouvrir son parcours sécurisé.")
        val payRow1=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
        payRow1.addView(paymentTile("MVola","Dépôt / retrait","M",Color.rgb(28,111,224)){pay(true,0)},LinearLayout.LayoutParams(0,dp(72),1f).apply{setMargins(0,dp(3),dp(4),dp(3))})
        payRow1.addView(paymentTile("Airtel Money","Dépôt / retrait","A",Color.rgb(205,40,55)){pay(true,1)},LinearLayout.LayoutParams(0,dp(72),1f).apply{setMargins(dp(4),dp(3),0,dp(3))})
        add(payRow1,72,0,0)
        val payRow2=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
        payRow2.addView(paymentTile("Orange Money","Dépôt / retrait","O",Color.rgb(247,133,18)){pay(true,2)},LinearLayout.LayoutParams(0,dp(72),1f).apply{setMargins(0,dp(3),dp(4),dp(3))})
        payRow2.addView(paymentTile("Binance Pay","Crypto ↔ MGA","B",Color.rgb(25,44,74)){cryptoDialog()},LinearLayout.LayoutParams(0,dp(72),1f).apply{setMargins(dp(4),dp(3),0,dp(3))})
        add(payRow2,72,0,6)
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

    private fun loadHistory() { get("/api/wallet/transactions"){j->val rows=j.optJSONArray("items")?:j.optJSONArray("transactions");runOnUiThread{if(!::status.isInitialized||currentScreen!="history")return@runOnUiThread;status.text=if(rows==null||rows.length()==0)"Tsy mbola misy transaction." else buildString{append("${rows.length()} transaction(s)\n\n");for(i in 0 until minOf(rows.length(),30)){val x=rows.optJSONObject(i);append("• ${(x?.optString("type",x?.optString("kind","TRANSACTION") ?: "TRANSACTION") ?: "TRANSACTION")}: ${(x?.optString("amount","0") ?: "0")} Ar — ${(x?.optString("status","") ?: "")}\n")}}}} }

    private fun pay(deposit:Boolean, preselected:Int=0) {
        val amount=EditText(this).apply { hint="Montant (Ar)";inputType=2;setPadding(dp(12),0,dp(12),0) }
        val contact=EditText(this).apply { hint="Téléphone ou identifiant (si requis)";inputType=1;setPadding(dp(12),0,dp(12),0) }
        val methods=arrayOf("MVola","Airtel Money","Orange Money","Binance Pay / Crypto")
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(8),0,dp(8),0)}
        box.addView(amount,LinearLayout.LayoutParams(-1,dp(52)));box.addView(contact,LinearLayout.LayoutParams(-1,dp(52)).apply{topMargin=dp(6)})
        box.addView(label("Méthodes: MVola • Airtel Money • Orange Money • Binance Pay/Crypto. Le serveur refuse automatiquement tout provider non activé; aucun crédit fictif.",11f,false,Color.rgb(95,107,125)).apply{setPadding(dp(2),dp(8),dp(2),0)})
        AlertDialog.Builder(this).setTitle(if(deposit)"Dépôt" else "Retrait").setView(box).setSingleChoiceItems(methods,preselected.coerceIn(0,3),null)
            .setPositiveButton("Hanohy"){d,_->val a=amount.text.toString().toLongOrNull()?:0;val selected=(d as AlertDialog).listView.checkedItemPosition.coerceIn(0,3);if(!deposit && selected==0){Toast.makeText(this,"MVola retrait: API non supportée par le provider actuel.",Toast.LENGTH_LONG).show();return@setPositiveButton};val method=arrayOf("MVOLA","AIRTEL_MONEY","ORANGE_MONEY","BINANCE")[selected];if(a>0)post(if(deposit)"/api/wallet/deposit" else "/api/wallet/withdraw",JSONObject().put("amountMGA",a).put("method",method).apply{if(contact.text.isNotBlank())put("customerPhone",contact.text.toString().trim())},UUID.randomUUID().toString(),{status.text=if(deposit)"Dépôt envoyé: PENDING" else "Retrait envoyé: PENDING";loadWallet()},{msg->status.text=msg})}
            .setNegativeButton("Aoka",null).show()
    }

    private fun gameModeDialog() {
        AlertDialog.Builder(this).setTitle("Choisissez le mode")
            .setItems(arrayOf("🌐 En ligne — Joueurs réels","👥 Hors-ligne — 2 à 4 joueurs")){_,which->if(which==0)lobby() else offlineMenu()}
            .setNegativeButton("Aoka",null).show()
    }

    private fun cryptoDialog() {
        if(token.isBlank()){login();return}
        val crypto=EditText(this).apply{hint="Montant crypto (0.1 / 0.5 / 1 / 2 / 5)";inputType=8194;setPadding(dp(12),0,dp(12),0)}
        val mga=EditText(this).apply{hint="Montant MGA";inputType=2;setPadding(dp(12),0,dp(12),0)}
        val info=label("Récupération du taux serveur…",11f,false,Color.rgb(95,107,125))
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(8),0,dp(8),0)}
        box.addView(crypto,LinearLayout.LayoutParams(-1,dp(52)).apply{bottomMargin=dp(7)})
        box.addView(mga,LinearLayout.LayoutParams(-1,dp(52)).apply{bottomMargin=dp(7)})
        box.addView(info,LinearLayout.LayoutParams(-1,dp(42)))
        AlertDialog.Builder(this).setTitle("Crypto • MGA ↔ Crypto").setView(box)
            .setSingleChoiceItems(arrayOf("Crypto → MGA","MGA → Crypto"),0){_,which->
                val rateHolder=info.tag as? Double ?: 0.0
                if(rateHolder>0){
                    if(which==0){val v=crypto.text.toString().toDoubleOrNull()?:0.0;mga.setText(if(v>0)formatDecimal(v*rateHolder) else "")}
                    else{val v=mga.text.toString().toDoubleOrNull()?:0.0;crypto.setText(if(v>0)formatDecimal(v/rateHolder) else "")}
                }
            }
            .setPositiveButton("CONVERTIR"){_,_->Toast.makeText(this,"Conversion calculée avec le taux serveur. Aucun solde n’est crédité sans provider actif.",Toast.LENGTH_LONG).show()}
            .setNegativeButton("Aoka",null).show()
        get("/api/payments/config"){j->
            val b=j.optJSONObject("BINANCE");val rate=b?.optDouble("mgaRate",0.0)?:0.0;val cur=b?.optString("currency","CRYPTO")?:"CRYPTO";info.tag=rate
            runOnUiThread{info.text=if(rate>0) "Taux: 1 $cur = ${formatDecimal(rate)} Ar • Provider ${if(b?.optBoolean("enabled",false)==true) "ACTIF" else "non activé"}" else "Taux crypto non configuré sur le serveur. Provider non activé."}
        }
    }
    private fun formatDecimal(v:Double):String = String.format(java.util.Locale.US,"%.8f",v).trimEnd('0').trimEnd('.')
    private fun lobby() {
        page("lobby","Lalao")
        val lobbyMusicRow=LinearLayout(this).apply{
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.CENTER_VERTICAL
        }
        lobbyMusicRow.addView(label("Ambiance musicale",13f,true,Color.rgb(88,99,116)),LinearLayout.LayoutParams(0,dp(52),1f))
        musicButton=musicIconButton()
        lobbyMusicRow.addView(musicButton,LinearLayout.LayoutParams(dp(52),dp(52)))
        add(lobbyMusicRow,52,0,5)
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
        add(card("Ludo hors-ligne","2–4 joueurs  •  sans Internet",Color.rgb(130,85,180)))
        add(button("👤 2 joueurs",Color.rgb(37,93,196)){startOffline(2)},52)
        add(button("👥 3 joueurs",Color.rgb(37,93,196)){startOffline(3)},52)
        add(button("👥 4 joueurs",Color.rgb(37,93,196)){startOffline(4)},52)
        add(button("← Accueil",Color.rgb(70,82,100)){home()},52)
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
        status=label("Joueur ${offlineTurn+1}/$players • Dé: $offlineDice",13f,true,Color.rgb(37,93,196));info.addView(status,LinearLayout.LayoutParams(0,-2,1f));info.addView(label("HORS-LIGNE",13f,true,Color.rgb(130,85,180)));add(info,34,0,5)
        val host=LudoBoardView(this);host.state=offlineState;host.player=offlineTurn;offlineHost=host
        root.addView(host,LinearLayout.LayoutParams(-1,0,1f).apply{setMargins(0,dp(4),0,dp(8))})
        host.animateState()
        add(button("🎲 Lancer le dé",Color.rgb(235,170,25)){offlineRoll(host)},54)
        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
        for(i in 0..3)row.addView(button("Pion ${i+1}",Color.rgb(70,82,100)){offlineMove(host,players,i)},LinearLayout.LayoutParams(0,dp(46),1f).apply{setMargins(dp(2),0,dp(2),0)})
        add(row,46,2,4);add(button("← Hors-ligne",Color.rgb(70,82,100)){offlineMenu()},50)
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
        offlineDice=0;host.state=offlineState;host.player=offlineTurn;host.animateState()
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
        val host=LudoBoardView(this); boardHost=host; root.addView(host,LinearLayout.LayoutParams(-1,0,1f).apply{setMargins(0,dp(4),0,dp(8))})
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

    private inner class LudoBoardView(context:Context):View(context){
        var state:JSONObject?=null;var player=-1;private val paints=Array(4){Paint(Paint.ANTI_ALIAS_FLAG)}
        private val cols=intArrayOf(Color.rgb(218,60,65),Color.rgb(42,155,86),Color.rgb(232,178,34),Color.rgb(54,105,205))
        private val display=Array(4){Array(4){FloatArray(2){Float.NaN}}}
        private val lastPos=Array(4){IntArray(4){Int.MIN_VALUE}}
        private var stateAnimator:android.animation.ValueAnimator?=null
        private var diceRolling=false
        private var dicePhase=0f
        private val jumpLift=Array(4){FloatArray(4)}
        private val spawnScale=Array(4){FloatArray(4){1f}}
        init{setLayerType(View.LAYER_TYPE_HARDWARE,null);for(i in 0..3){paints[i].color=cols[i];paints[i].style=Paint.Style.FILL}}
        override fun onDraw(canvas:Canvas){
            super.onDraw(canvas)
            val w=width.toFloat(); val h=height.toFloat(); val s=minOf(w,h)
            val ox=(w-s)/2f
            val topSpace=(h-s).coerceAtLeast(0f)
            val oy=topSpace*.20f
            val cell=s/15f
            val board=Paint(Paint.ANTI_ALIAS_FLAG)
            board.setShadowLayer(dp(8).toFloat(),0f,dp(3).toFloat(),0x55000000)
            board.shader=LinearGradient(ox,oy,ox,oy+s,Color.rgb(255,255,255),Color.rgb(241,245,250),Shader.TileMode.CLAMP)
            canvas.drawRoundRect(ox,oy,ox+s,oy+s,dp(24).toFloat(),dp(24).toFloat(),board)
            board.clearShadowLayer(); board.shader=null
            drawZone(canvas,ox,oy,0,0,Color.rgb(220,60,66),cell)
            drawZone(canvas,ox,oy,9,0,Color.rgb(42,158,92),cell)
            drawZone(canvas,ox,oy,0,9,Color.rgb(238,184,43),cell)
            drawZone(canvas,ox,oy,9,9,Color.rgb(58,109,211),cell)
            val pathPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.rgb(246,248,251);style=Paint.Style.FILL}
            val pathStroke=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.rgb(222,228,236);style=Paint.Style.STROKE;strokeWidth=dp(0.8f).toFloat();setStrokeCap(Paint.Cap.ROUND)}
            for((r,c) in visualPath()){drawCell(canvas,ox,oy,cell,r,c,pathPaint,pathStroke)}
            val homePaints=arrayOf(
                Paint(Paint.ANTI_ALIAS_FLAG).apply{color=0x55DC3C42},
                Paint(Paint.ANTI_ALIAS_FLAG).apply{color=0x5542AE68},
                Paint(Paint.ANTI_ALIAS_FLAG).apply{color=0x55EEB82B},
                Paint(Paint.ANTI_ALIAS_FLAG).apply{color=0x553A79D5}
            )
            for(i in 1..5){canvas.drawRoundRect(ox+(i+0.08f)*cell,oy+7.08f*cell,ox+(i+0.92f)*cell,oy+7.92f*cell,dp(6).toFloat(),dp(6).toFloat(),homePaints[0])}
            for(i in 9..13){canvas.drawRoundRect(ox+(i+0.08f)*cell,oy+7.08f*cell,ox+(i+0.92f)*cell,oy+7.92f*cell,dp(6).toFloat(),dp(6).toFloat(),homePaints[1])}
            for(i in 9..13){canvas.drawRoundRect(ox+7.08f*cell,oy+(i+0.08f)*cell,ox+7.92f*cell,oy+(i+0.92f)*cell,dp(6).toFloat(),dp(6).toFloat(),homePaints[2])}
            for(i in 1..5){canvas.drawRoundRect(ox+7.08f*cell,oy+(i+0.08f)*cell,ox+7.92f*cell,oy+(i+0.92f)*cell,dp(6).toFloat(),dp(6).toFloat(),homePaints[3])}
            val starCells=arrayOf(1 to 6,6 to 1,8 to 13,13 to 8)
            for((r,c) in starCells){drawSafe(canvas,ox,oy,cell,r,c)}
            drawCenter(canvas,ox,oy,cell)
            drawDie(canvas,ox+7.5f*cell,oy+7.5f*cell,cell)
            drawStartMarkers(canvas,ox,oy,cell)
            drawTokens(canvas,ox,oy,cell)
        }
        private fun drawZone(c:Canvas,ox:Float,oy:Float,r:Int,col:Int,color:Int,cell:Float){
            val outer=Paint(Paint.ANTI_ALIAS_FLAG).apply{this.color=color}
            outer.setShadowLayer(dp(2).toFloat(),0f,dp(1).toFloat(),0x33000000)
            c.drawRect(ox+r*cell,oy+col*cell,ox+(r+6)*cell,oy+(col+6)*cell,outer)
            outer.clearShadowLayer()
            val inner=Paint(Paint.ANTI_ALIAS_FLAG).apply{this.color=Color.WHITE}
            c.drawRoundRect(ox+(r+1)*cell,oy+(col+1)*cell,ox+(r+5)*cell,oy+(col+5)*cell,dp(12).toFloat(),dp(12).toFloat(),inner)
            val shade=Paint(Paint.ANTI_ALIAS_FLAG).apply{this.color=0x12000000}
            c.drawRoundRect(ox+(r+1.15f)*cell,oy+(col+1.2f)*cell,ox+(r+4.85f)*cell,oy+(col+4.85f)*cell,dp(10).toFloat(),dp(10).toFloat(),shade)
            val sockets=arrayOf(1.8f to 1.8f,4.2f to 1.8f,1.8f to 4.2f,4.2f to 4.2f)
            for((sx,sy) in sockets){
                val cx=ox+(r+sx)*cell;val cy=oy+(col+sy)*cell
                val ring=Paint(Paint.ANTI_ALIAS_FLAG).apply{this.color=0x17000000}
                c.drawCircle(cx,cy,cell*.53f,ring)
                val hole=Paint(Paint.ANTI_ALIAS_FLAG).apply{this.color=color;alpha=120}
                c.drawCircle(cx,cy,cell*.40f,hole)
            }
        }

        private fun drawTokens(c:Canvas,ox:Float,oy:Float,cell:Float){
            for(pi in 0..3)for(ti in 0..3){
                val q=tokenCoord(pi,ti)
                val rr=if(display[pi][ti][0].isNaN()) q[0] else display[pi][ti][0]
                val cc=if(display[pi][ti][1].isNaN()) q[1] else display[pi][ti][1]
                val lift=jumpLift[pi][ti]
                drawToken(c,ox+cc*cell,oy+(rr-lift)*cell,cell,cols[pi],pi==player,spawnScale[pi][ti])
                if(pi==player && tokenCanMoveForUi(pi,ti)) drawLegalHint(c,ox+cc*cell,oy+(rr-lift)*cell,cell)
            }
        }
        private fun tokenCoord(pi:Int,ti:Int):FloatArray{
            val fallback=arrayOf(
                arrayOf(1.8f to 1.8f,4.2f to 1.8f,1.8f to 4.2f,4.2f to 4.2f),
                arrayOf(10.8f to 1.8f,13.2f to 1.8f,10.8f to 4.2f,13.2f to 4.2f),
                arrayOf(1.8f to 10.8f,4.2f to 10.8f,1.8f to 13.2f,4.2f to 13.2f),
                arrayOf(10.8f to 10.8f,13.2f to 10.8f,10.8f to 13.2f,13.2f to 13.2f))
            var rr=fallback[pi][ti].first;var cc=fallback[pi][ti].second
            val ps=state?.optJSONArray("players");val ts=ps?.optJSONObject(pi)?.optJSONArray("tokens")
            val pos=ts?.optJSONObject(ti)?.optInt("pos",-1)?:-1
            if(pos in 0..51){val q=visualPath()[(pi*13+pos)%52];rr=q.first+0.5f;cc=q.second+0.5f}
            else if(pos in 52..57){
                val finish=arrayOf(
                    floatArrayOf(7.5f,6.5f,7.5f,6.75f,7.5f,7.0f,7.5f,7.25f,7.5f,7.5f,7.5f,7.5f),
                    floatArrayOf(6.5f,7.5f,6.75f,7.5f,7.0f,7.5f,7.25f,7.5f,7.5f,7.5f,7.5f,7.5f),
                    floatArrayOf(7.5f,8.5f,7.5f,8.25f,7.5f,8.0f,7.5f,7.75f,7.5f,7.5f,7.5f,7.5f),
                    floatArrayOf(8.5f,7.5f,8.25f,7.5f,8.0f,7.5f,7.75f,7.5f,7.5f,7.5f,7.5f,7.5f))
                val k=(pos-52)*2;rr=finish[pi][k];cc=finish[pi][k+1]
            }
            return floatArrayOf(rr,cc)
        }
        private fun animationCoord(pi:Int,pos:Int):FloatArray{
            if(pos in 0..51){
                val q=visualPath()[(pi*13+pos)%52]
                return floatArrayOf(q.first+0.5f,q.second+0.5f)
            }
            if(pos in 52..57){
                val finish=arrayOf(
                    floatArrayOf(7.5f,6.5f,7.5f,6.75f,7.5f,7.0f,7.5f,7.25f,7.5f,7.5f,7.5f,7.5f),
                    floatArrayOf(6.5f,7.5f,6.75f,7.5f,7.0f,7.5f,7.25f,7.5f,7.5f,7.5f,7.5f,7.5f),
                    floatArrayOf(7.5f,8.5f,7.5f,8.25f,7.5f,8.0f,7.5f,7.75f,7.5f,7.5f,7.5f,7.5f),
                    floatArrayOf(8.5f,7.5f,8.25f,7.5f,8.0f,7.5f,7.75f,7.5f,7.5f,7.5f,7.5f,7.5f)
                )[pi]
                val k=(pos-52)*2
                return floatArrayOf(finish[k],finish[k+1])
            }
            return floatArrayOf(7.5f,7.5f)
        }
        fun animateState(){
            val targets=Array(4){Array(4){FloatArray(2)}}
            val oldPos=Array(4){IntArray(4){Int.MIN_VALUE}}
            val newPos=Array(4){IntArray(4){-1}}
            for(pi in 0..3)for(ti in 0..3){
                val q=tokenCoord(pi,ti)
                targets[pi][ti][0]=q[0];targets[pi][ti][1]=q[1]
                val ts=state?.optJSONArray("players")?.optJSONObject(pi)?.optJSONArray("tokens")
                newPos[pi][ti]=ts?.optJSONObject(ti)?.optInt("pos",-1)?:-1
                if(display[pi][ti][0].isNaN()){
                    display[pi][ti][0]=q[0];display[pi][ti][1]=q[1]
                }
                oldPos[pi][ti]=lastPos[pi][ti]
                if(oldPos[pi][ti]==Int.MIN_VALUE) lastPos[pi][ti]=newPos[pi][ti]
            }
            stateAnimator?.cancel()
            var maxSteps=0
            for(pi in 0..3)for(ti in 0..3){
                val from=oldPos[pi][ti];val to=newPos[pi][ti]
                if(from>=0 && to>from) maxSteps=maxOf(maxSteps,(to-from).coerceAtMost(6))
                if(from<0 && to>=0) maxSteps=maxOf(maxSteps,1)
            }
            val duration=maxSteps*125L+340L
            stateAnimator=android.animation.ValueAnimator.ofFloat(0f,1f).apply{
                this.duration=duration
                interpolator=android.view.animation.DecelerateInterpolator(1.5f)
                addUpdateListener{va->
                    val f=va.animatedValue as Float
                    for(pi in 0..3)for(ti in 0..3){
                        val from=oldPos[pi][ti];val to=newPos[pi][ti]
                        if(from>=0 && to>from){
                            val span=(to-from).coerceAtLeast(1)
                            val u=f*span
                            val step=kotlin.math.floor(u).toInt().coerceAtMost(span-1)
                            val local=u-step
                            jumpLift[pi][ti]=(kotlin.math.sin(local*Math.PI).toFloat()*0.20f)
                            spawnScale[pi][ti]=1f
                            val q1=animationCoord(pi,from+step)
                            val q2=animationCoord(pi,from+step+1)
                            display[pi][ti][0]=q1[0]+(q2[0]-q1[0])*local
                            display[pi][ti][1]=q1[1]+(q2[1]-q1[1])*local
                        }else if(from<0 && to>=0){
                            val targetR=targets[pi][ti][0];val targetC=targets[pi][ti][1]
                            val baseR=display[pi][ti][0];val baseC=display[pi][ti][1]
                            val local=f.coerceIn(0f,1f)
                            jumpLift[pi][ti]=kotlin.math.sin(local*Math.PI).toFloat()*0.30f
                            spawnScale[pi][ti]=0.72f+0.28f*kotlin.math.sin(local*Math.PI*0.5).toFloat()
                            display[pi][ti][0]=baseR+(targetR-baseR)*local
                            display[pi][ti][1]=baseC+(targetC-baseC)*local
                        }else if(f>=1f){
                            jumpLift[pi][ti]=0f
                            spawnScale[pi][ti]=1f
                            display[pi][ti][0]=targets[pi][ti][0]
                            display[pi][ti][1]=targets[pi][ti][1]
                        }
                    }
                    invalidate()
                }
                addListener(object:android.animation.AnimatorListenerAdapter(){
                    override fun onAnimationEnd(animation:android.animation.Animator){
                        for(pi in 0..3)for(ti in 0..3){
                            display[pi][ti][0]=targets[pi][ti][0]
                            display[pi][ti][1]=targets[pi][ti][1]
                            jumpLift[pi][ti]=0f
                            spawnScale[pi][ti]=1f
                            lastPos[pi][ti]=newPos[pi][ti]
                        }
                        invalidate()
                    }
                })
                start()
            }
        }

        private fun drawCell(c:Canvas,ox:Float,oy:Float,cell:Float,r:Int,col:Int,fill:Paint,stroke:Paint){
            val l=ox+col*cell+dp(0.7f);val t=oy+r*cell+dp(0.7f);val rr=l+cell-dp(1.4f);val bb=t+cell-dp(1.4f)
            c.drawRoundRect(l,t,rr,bb,dp(3.5f).toFloat(),dp(3.5f).toFloat(),fill)
            c.drawRoundRect(l,t,rr,bb,dp(3.5f).toFloat(),dp(3.5f).toFloat(),stroke)
        }
        private fun drawSafe(c:Canvas,ox:Float,oy:Float,cell:Float,r:Int,col:Int){
            val cx=ox+(col+.5f)*cell;val cy=oy+(r+.5f)*cell
            val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.rgb(205,211,221)}
            c.drawCircle(cx,cy,cell*.28f,p);p.color=Color.WHITE;c.drawCircle(cx,cy,cell*.18f,p)
            p.color=Color.rgb(159,170,187);val star=Path()
            for(i in 0..9){val a=-Math.PI/2+i*Math.PI/5;val rad=if(i%2==0)cell*.27f else cell*.12f;val x=cx+(kotlin.math.cos(a)*rad).toFloat();val y=cy+(kotlin.math.sin(a)*rad).toFloat();if(i==0)star.moveTo(x,y)else star.lineTo(x,y)}
            star.close();c.drawPath(star,p)
        }
        private fun drawCenter(c:Canvas,ox:Float,oy:Float,cell:Float){
            val cx=ox+7.5f*cell; val cy=oy+7.5f*cell
            val corners=arrayOf(
                floatArrayOf(6f,6f,9f,6f), floatArrayOf(9f,6f,9f,9f),
                floatArrayOf(9f,9f,6f,9f), floatArrayOf(6f,9f,6f,6f))
            for(i in 0..3){
                val a=corners[i]; val path=Path(); path.moveTo(ox+a[0]*cell,oy+a[1]*cell); path.lineTo(ox+a[2]*cell,oy+a[3]*cell); path.lineTo(cx,cy); path.close()
                val base=cols[i]
                val hi=Color.rgb(minOf(255,Color.red(base)+70),minOf(255,Color.green(base)+70),minOf(255,Color.blue(base)+70))
                val lo=Color.rgb((Color.red(base)*.42f).toInt(),(Color.green(base)*.42f).toInt(),(Color.blue(base)*.42f).toInt())
                val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{shader=LinearGradient(ox+a[0]*cell,oy+a[1]*cell,cx,cy,hi,lo,Shader.TileMode.CLAMP);setShadowLayer(dp(2f).toFloat(),0f,dp(1f).toFloat(),0x55000000)}
                c.drawPath(path,paint); paint.clearShadowLayer(); paint.shader=null
                paint.style=Paint.Style.STROKE;paint.strokeWidth=dp(.8f).toFloat();paint.color=0x99FFFFFF.toInt();c.drawPath(path,paint)
            }
            val med=Paint(Paint.ANTI_ALIAS_FLAG).apply{shader=RadialGradient(cx-cell*.12f,cy-cell*.18f,cell*.72f,Color.WHITE,Color.rgb(191,182,163),Shader.TileMode.CLAMP);setShadowLayer(dp(5f).toFloat(),0f,dp(2f).toFloat(),0x88000000.toInt())}
            c.drawCircle(cx,cy,cell*.48f,med);med.clearShadowLayer();med.shader=null
            med.style=Paint.Style.STROKE;med.strokeWidth=dp(1.5f).toFloat();med.color=0xB8FFFFFF.toInt();c.drawCircle(cx,cy,cell*.48f,med)
            med.style=Paint.Style.FILL;med.shader=RadialGradient(cx-cell*.1f,cy-cell*.12f,cell*.28f,0xFFFFF8D0.toInt(),0x00FFF0A0,Shader.TileMode.CLAMP);c.drawCircle(cx,cy,cell*.26f,med);med.shader=null
        }
        private fun drawDie(c:Canvas,cx:Float,cy:Float,cell:Float){
            val rolling=diceRolling
            val phase=(System.currentTimeMillis()%900L)/900f
            val face=if(rolling)((phase*6f).toInt()%6)+1 else maxOf(1,if(currentScreen=="offline")offlineDice else state?.optInt("dice",0)?:0)
            val size=cell*1.34f
            c.save()
            val angle=if(rolling)(Math.sin(phase*Math.PI*4.0)*13.0).toFloat() else 0f
            c.rotate(angle,cx,cy)
            val die=Paint(Paint.ANTI_ALIAS_FLAG)
            die.setShadowLayer(dp(5).toFloat(),0f,dp(2).toFloat(),0x88000000)
            die.shader=LinearGradient(cx-size*.5f,cy-size*.5f,cx+size*.5f,cy+size*.5f,Color.WHITE,Color.rgb(216,222,232),Shader.TileMode.CLAMP)
            c.drawRoundRect(cx-size*.5f,cy-size*.5f,cx+size*.5f,cy+size*.5f,cell*.15f,cell*.15f,die)
            die.clearShadowLayer();die.shader=null
            die.style=Paint.Style.STROKE;die.strokeWidth=dp(1f).toFloat();die.color=Color.rgb(144,151,164);c.drawRoundRect(cx-size*.5f,cy-size*.5f,cx+size*.5f,cy+size*.5f,cell*.15f,cell*.15f,die);die.style=Paint.Style.FILL;die.color=Color.rgb(35,39,49)
            val d=size*.23f
            val pts=when(face){
                1->arrayOf(0 to 0);2->arrayOf(-1 to -1,1 to 1);3->arrayOf(-1 to -1,0 to 0,1 to 1);4->arrayOf(-1 to -1,1 to -1,-1 to 1,1 to 1);5->arrayOf(-1 to -1,1 to -1,0 to 0,-1 to 1,1 to 1);else->arrayOf(-1 to -1,-1 to 0,-1 to 1,1 to -1,1 to 0,1 to 1)
            }
            for((xx,yy) in pts)c.drawCircle(cx+xx*d,cy+yy*d,d*.18f,die)
            c.restore()
        }
        fun isDiceRolling():Boolean = diceRolling
        fun startDiceAnimation(){
            if(diceRolling)return
            diceRolling=true;dicePhase=0f
            val start=System.currentTimeMillis()
            fun tick(){
                if(!diceRolling)return
                dicePhase=((System.currentTimeMillis()-start)/650f).coerceIn(0f,1f)
                invalidate()
                if(dicePhase>=1f){diceRolling=false;invalidate()}else postDelayed({tick()},16L)
            }
            tick()
        }
        private fun drawStartMarkers(c:Canvas,ox:Float,oy:Float,cell:Float){
            val starts=arrayOf(6 to 1,1 to 8,8 to 13,13 to 6)
            for(i in starts.indices){
                val (r,col)=starts[i];val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=cols[i];alpha=90}
                c.drawRoundRect(ox+col*cell+dp(1f),oy+r*cell+dp(1f),ox+(col+1)*cell-dp(1f),oy+(r+1)*cell-dp(1f),dp(4f).toFloat(),dp(4f).toFloat(),p)
                val mark=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.WHITE;style=Paint.Style.STROKE;strokeWidth=dp(1.5f).toFloat()}
                c.drawCircle(ox+(col+.5f)*cell,oy+(r+.5f)*cell,cell*.22f,mark)
            }
        }
        private fun drawLegalHint(c:Canvas,cx:Float,cy:Float,cell:Float){
            val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=0xFFEBC348.toInt();style=Paint.Style.STROKE;strokeWidth=dp(2.2f).toFloat()}
            c.drawCircle(cx,cy,cell*.39f,p)
            p.style=Paint.Style.FILL; p.color=0x55EBC348
            c.drawCircle(cx,cy,cell*.44f,p)
        }
        private fun tokenCanMoveForUi(pi:Int,ti:Int):Boolean{
            val activePlayer=if(currentScreen=="offline") offlineTurn else player
            if(pi!=activePlayer)return false
            val dice=if(currentScreen=="offline") offlineDice else state?.optInt("dice",0)?:0
            if(dice !in 1..6)return false
            val ps=if(currentScreen=="offline") offlineState?.optJSONArray("players") else state?.optJSONArray("players")
            val pos=ps?.optJSONObject(pi)?.optJSONArray("tokens")?.optJSONObject(ti)?.optInt("pos",-1)?:-1
            return if(pos<0) dice==6 else pos+dice<=57
        }
        private fun drawToken(c:Canvas,cx:Float,cy:Float,cell:Float,color:Int,active:Boolean,scale:Float=1f){
            val rad=cell*.31f*scale; val base=cy+rad*.62f
            val shadow=Paint(Paint.ANTI_ALIAS_FLAG).apply{this.color=0x66000000;setShadowLayer(dp(4f).toFloat(),0f,dp(3f).toFloat(),0x99000000.toInt())}
            c.drawOval(cx-rad*.9f,base-rad*.10f,cx+rad*.9f,base+rad*.22f,shadow);shadow.clearShadowLayer()
            val body=Path();body.moveTo(cx-rad*.88f,base)
            body.cubicTo(cx-rad*.80f,cy+rad*.30f,cx-rad*.42f,cy+rad*.16f,cx-rad*.36f,cy-rad*.04f)
            body.cubicTo(cx-rad*.66f,cy-rad*.28f,cx-rad*.56f,cy-rad*.82f,cx,cy-rad*1.04f)
            body.cubicTo(cx+rad*.56f,cy-rad*.82f,cx+rad*.66f,cy-rad*.28f,cx+rad*.36f,cy-rad*.04f)
            body.cubicTo(cx+rad*.42f,cy+rad*.16f,cx+rad*.80f,cy+rad*.30f,cx+rad*.88f,base)
            body.quadTo(cx,base+rad*.20f,cx-rad*.88f,base);body.close()
            val hi=Color.rgb(minOf(255,Color.red(color)+92),minOf(255,Color.green(color)+92),minOf(255,Color.blue(color)+92))
            val mid=Color.rgb(minOf(255,Color.red(color)+22),minOf(255,Color.green(color)+22),minOf(255,Color.blue(color)+22))
            val lo=Color.rgb((Color.red(color)*.30f).toInt(),(Color.green(color)*.30f).toInt(),(Color.blue(color)*.30f).toInt())
            val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{shader=LinearGradient(cx-rad,cy-rad,cx+rad,base,hi,lo,Shader.TileMode.CLAMP);setShadowLayer(dp(4f).toFloat(),0f,dp(2f).toFloat(),0xAA000000.toInt())}
            c.drawPath(body,paint);paint.clearShadowLayer();paint.shader=null
            val bevel=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;strokeWidth=dp(1.5f).toFloat();this.color=0xD9FFFFFF.toInt()};c.drawPath(body,bevel)
            bevel.style=Paint.Style.STROKE;bevel.strokeWidth=dp(1f).toFloat();bevel.color=0x66000000;c.drawPath(body,bevel)
            val cap=Paint(Paint.ANTI_ALIAS_FLAG).apply{shader=RadialGradient(cx-rad*.22f,cy-rad*.55f,rad*.52f,0xFFFFFFFF.toInt(),0x00FFFFFF,Shader.TileMode.CLAMP)};c.drawCircle(cx-rad*.05f,cy-rad*.48f,rad*.43f,cap);cap.shader=null
            cap.color=0xEEFFFFFF.toInt();c.drawCircle(cx-rad*.27f,cy-rad*.62f,rad*.13f,cap)
            cap.color=0x55333333; c.drawOval(cx-rad*.58f,base-rad*.02f,cx+rad*.58f,base+rad*.12f,cap)
            if(active){cap.style=Paint.Style.STROKE;cap.strokeWidth=dp(2f).toFloat();cap.color=0xFFFFF0A6.toInt();c.drawCircle(cx,cy-rad*.08f,rad+dp(2f),cap);cap.style=Paint.Style.FILL}
        }
        private fun visualPath():List<Pair<Int,Int>>{
            return listOf(
                6 to 0,6 to 1,6 to 2,6 to 3,6 to 4,6 to 5,5 to 6,4 to 6,3 to 6,2 to 6,1 to 6,0 to 6,
                0 to 7,0 to 8,1 to 8,2 to 8,3 to 8,4 to 8,5 to 8,6 to 9,6 to 10,6 to 11,6 to 12,6 to 13,6 to 14,
                7 to 14,8 to 14,8 to 13,8 to 12,8 to 11,8 to 10,8 to 9,9 to 8,10 to 8,11 to 8,12 to 8,13 to 8,14 to 8,
                14 to 7,14 to 6,13 to 6,12 to 6,11 to 6,10 to 6,9 to 6,8 to 5,8 to 4,8 to 3,8 to 2,8 to 1,8 to 0,7 to 0
            )
        }
        override fun onTouchEvent(e:android.view.MotionEvent):Boolean{
            if(e.action==MotionEvent.ACTION_UP && player in 0..3){
                val s=minOf(width,height).toFloat(); val ox=(width-s)/2f; val oy=(height-s)/2f; val cell=s/15f
                val touched=nearestOwnToken(e.x,e.y,ox,oy,cell)
                if(touched>=0){
                    performClick()
                    performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                    if(currentScreen=="offline") offlineMove(this,offlinePlayers,touched) else move(touched)
                    return true
                }
            }
            return true
        }
        override fun performClick():Boolean{super.performClick();return true}
        private fun nearestOwnToken(x:Float,y:Float,ox:Float,oy:Float,cell:Float):Int{
            val activeState=if(currentScreen=="offline") offlineState else state
            val activePlayer=if(currentScreen=="offline") offlineTurn else player
            val ps=activeState?.optJSONArray("players")
            val tokens=ps?.optJSONObject(activePlayer)?.optJSONArray("tokens")
            val fallback=arrayOf(1.8f to 1.8f,4.2f to 1.8f,1.8f to 4.2f,4.2f to 4.2f)
            var best=-1; var bestD2=Float.MAX_VALUE
            for(ti in 0..3){
                var rr=fallback[ti].first; var cc=fallback[ti].second
                val pos=tokens?.optJSONObject(ti)?.optInt("pos",-1)?:-1
                if(pos>=0){val q=visualPath()[(activePlayer*13+pos)%52];rr=q.first+0.5f;cc=q.second+0.5f}
                val dx=x-(ox+cc*cell); val dy=y-(oy+rr*cell); val d2=dx*dx+dy*dy
                if(d2<bestD2){bestD2=d2;best=ti}
            }
            return if(best>=0 && bestD2 <= (cell*0.82f)*(cell*0.82f)) best else -1
        }
        private fun path():List<Pair<Int,Int>>{val p=mutableListOf<Pair<Int,Int>>();for(c in 1..13)p+=0 to c;for(r in 1..13)p+=r to 14;for(c in 13 downTo 1)p+=14 to c;for(r in 13 downTo 1)p+=r to 0;return p}
    }
    private fun musicLabel():String = if(music.enabled()) "Musique activée" else "Musique désactivée"
    private fun updateMusicButton(){
        if(::musicButton.isInitialized){
            musicButton.text = if(music.enabled()) "🎵" else "🔇"
            musicButton.contentDescription = musicLabel()
        }
    }
    override fun onStart(){ super.onStart(); if(::music.isInitialized) music.start() }
    override fun onStop(){ if(::music.isInitialized) music.stop(); super.onStop() }

}
