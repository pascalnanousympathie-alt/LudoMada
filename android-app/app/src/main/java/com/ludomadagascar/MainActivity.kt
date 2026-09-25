package com.ludomadagascar

import android.app.*
import android.os.Bundle
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

class MainActivity : Activity() {
    private val apiBase = BuildConfig.API_BASE_URL
    private val entryFees = listOf(500, 1000, 2000, 5000, 10000, 20000)
    private var entryFee = 500
    private var token = ""; private var userId = ""; private var username = ""
    private var gameId = ""; private var version = 0; private var retryCount = 0; private var startingGame = false
    private var state: JSONObject? = null; private var currentScreen = ""
    private var offlineTurn = 0; private var offlineDice = 0; private var offlineState: JSONObject? = null
    private var selectedLanguage = "fr"
    private fun t(en:String, fr:String, mg:String) = when(selectedLanguage) { "fr" -> fr; "mg" -> mg; else -> en }
    private lateinit var root: LinearLayout; private lateinit var status: TextView; private lateinit var music: CalmMusic; private lateinit var musicButton: Button
    private var boardHost: LudoBoardView? = null

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
    private fun darker(c:Int):Int = Color.rgb((Color.red(c)*.70f).toInt(),(Color.green(c)*.70f).toInt(),(Color.blue(c)*.70f).toInt())
    private fun button(s:String, accent:Int=Color.rgb(37,93,196), action:(()->Unit)?=null) = Button(this).apply {
        text=s; textSize=14f; isAllCaps=false; setTextColor(Color.WHITE); setTypeface(null,android.graphics.Typeface.BOLD)
        setPadding(dp(16),0,dp(16),0); background=GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(accent,darker(accent))).apply{cornerRadius=dp(18).toFloat();setStroke(dp(1),0x33FFFFFF)}
        minHeight=dp(52); stateListAnimator=null; elevation=dp(4).toFloat(); setOnClickListener { action?.invoke() }
    }
    private fun page(name:String, title:String): LinearLayout {
        currentScreen=name
        return LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL; setPadding(dp(20),dp(18),dp(20),dp(18));
            setBackgroundColor(Color.rgb(246,248,252));
        }.also { root=it; setContentView(root); addTopBar(title) }
    }
    private fun addTopBar(title:String) {
        val bar=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
        bar.addView(BrandLogoView(this),LinearLayout.LayoutParams(dp(50),dp(50)))
        val wrap=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(10),0,0,0) }
        wrap.addView(label("LudoMada",19f,true,Color.rgb(15,79,199)))
        wrap.addView(label(title,12f,false,Color.rgb(88,99,116)))
        bar.addView(wrap,LinearLayout.LayoutParams(0,-2,1f))
        root.addView(bar,LinearLayout.LayoutParams(-1,-2).apply { setMargins(0,0,0,dp(14)) })
    }
    private fun card(title:String, body:String, accent:Int=Color.rgb(37,93,196)): LinearLayout {
        val outer=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(5),dp(4),dp(5),dp(4));background=GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(Color.WHITE,Color.rgb(240,244,250))).apply{cornerRadius=dp(22).toFloat();setStroke(dp(1),Color.rgb(220,227,237))};elevation=dp(3).toFloat()}
        outer.addView(View(this).apply{background=shape(accent,6f)},LinearLayout.LayoutParams(dp(5),dp(62)))
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(13),dp(9),dp(13),dp(9))}
        box.addView(label(title,17f,true,Color.rgb(28,43,65)))
        box.addView(label(body,13f,false,Color.rgb(95,106,122)),LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(4)})
        outer.addView(box,LinearLayout.LayoutParams(0,-2,1f)); return outer
    }

    override fun onCreate(savedInstanceState:Bundle?) { super.onCreate(savedInstanceState); music=CalmMusic(this); window.statusBarColor=Color.rgb(246,248,252); splash() }

    private fun splash() {
        currentScreen="splash"; root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(dp(30),0,dp(30),0);setBackgroundColor(Color.rgb(244,247,251))};setContentView(root)
        root.addView(BrandLogoView(this),LinearLayout.LayoutParams(dp(118),dp(118)))
        add(label("LudoMada",31f,true,Color.rgb(18,43,76)),42,10,4)
        add(label("Joue • Gagne • Profite",15f,false,Color.rgb(88,99,116)),28,0,8)
        add(label("Jeu en ligne + hors-ligne",13f,true,Color.rgb(15,79,199)),30,0,12)
        root.postDelayed({login()},700)
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
        add(button("Créer un compte",Color.rgb(16,170,119)){registerDialog()},53,4,7)
        val secure=TextView(this).apply{text="Paiements sécurisés côté serveur";textSize=11f;setTextColor(Color.argb(217,255,255,255));gravity=Gravity.CENTER;setPadding(dp(4),0,dp(4),0)}
        add(secure,28,2,3)
        add(button(musicLabel(),Color.rgb(31,52,84)){music.toggle();Toast.makeText(this,musicLabel(),Toast.LENGTH_SHORT).show()},44,3,0)
    }

    private fun googleSignup() {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://accounts.google.com/"))) }
        catch (_:Exception) { status.text="Google tsy azo sokafana amin'ity appareil ity." }
    }

    private fun registerDialog() {
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(8),0,dp(8),0)}
        val u=EditText(this).apply{hint="Username (3+ caractères)"};val email=EditText(this).apply{hint="Email (optionnel)";inputType=33}
        val phone=EditText(this).apply{hint="Numéro mobile (optionnel)";inputType=3};val pass=EditText(this).apply{hint="Password (10+ caractères)";inputType=129}
        listOf(u,email,phone,pass).forEach{box.addView(it,LinearLayout.LayoutParams(-1,dp(52)).apply{bottomMargin=dp(6)})}
        AlertDialog.Builder(this).setTitle("Créer un compte").setView(box).setPositiveButton("S'inscrire"){_,_->
            if(u.text.length<3||pass.text.length<10){status.text="Username 3+ sy password 10+ ilaina.";return@setPositiveButton}
            post("/api/auth/register",JSONObject().put("username",u.text.toString().trim()).put("password",pass.text.toString()).apply{if(email.text.isNotBlank())put("email",email.text.toString().trim());if(phone.text.isNotBlank())put("phoneNumber",phone.text.toString().trim())}){j->token=j.optString("accessToken");userId=j.optJSONObject("user")?.optString("id").orEmpty();username=u.text.toString().trim();home()}
        }.setNegativeButton("Aoka",null).show()
    }

    private fun validAuth(u:EditText,p:EditText):Boolean { if(u.text.isBlank()||p.text.isBlank()){status.text="Fenoy ny identifiant sy mot de passe.";return false}; return true }
    private fun auth(path:String,u:String,p:String) { status.text="Connexion..."; post(path,JSONObject().put("identifier",u).put("password",p)){j->token=j.optString("accessToken");val usr=j.optJSONObject("user");userId=usr?.optString("id").orEmpty();username=usr?.optString("username").orEmpty();if(token.isBlank())status.text="Connexion refusée. Vérifiez vos identifiants." else home()} }

    private fun home() {
        page("home","Accueil")
        add(label("${if(username.isBlank())"Bienvenue" else username}",24f,true,Color.rgb(18,30,48)),40,2,4)
        add(label("Choisissez votre mode de jeu",14f,false,Color.rgb(105,116,132)),28,0,8)
        val play=card("Jouer en ligne","2–4 joueurs  •  à partir de 500 Ar",Color.rgb(37,93,196));play.setOnClickListener{lobby()};add(play,78,4,6)
        val walletCard=card("Portefeuille","Solde  •  dépôt  •  retrait",Color.rgb(35,150,84));walletCard.setOnClickListener{wallet()};add(walletCard,78,4,6)
        add(button("Jouer hors-ligne",Color.rgb(115,78,190)){offlineMenu()},52,5,5)
        add(button("Profil & langue",Color.rgb(70,82,100)){profile()},50,5,5)
        musicButton=button(musicLabel(),Color.rgb(45,55,72)){music.toggle();updateMusicButton()};add(musicButton,48,5,5)
        add(button("Se déconnecter",Color.rgb(190,55,65)){token="";userId="";username="";gameId="";login()},48,5,2)
    }

    private fun profile() {
        page("profile","Profil")
        add(card("Kaonty","${if(username.isBlank())"—" else username}",Color.rgb(130,85,180)))
        add(button("Langue / Language",Color.rgb(37,93,196)){languageDialog()},52)
        add(card("Sécurité","Vos identifiants et paiements restent protégés côté serveur.",Color.rgb(37,93,196)))
        add(button("← Miverina",Color.rgb(70,82,100)){home()},52)
    }

    private fun languageDialog() {
        val languages=arrayOf("Malagasy", "Français", "English")
        AlertDialog.Builder(this).setTitle("🌐 Langue / Language").setSingleChoiceItems(languages,1){d,which-> selectedLanguage=when(which){0->"mg";1->"fr";else->"en"};
            recreate();Toast.makeText(this,"${languages[which]}",Toast.LENGTH_SHORT).show();d.dismiss()
        }.setNegativeButton("Fermer",null).show()
    }

    private fun wallet() {
        page("wallet","Wallet")
        status=label("Maka ny solde...",15f,false,Color.rgb(88,99,116));add(status)
        val actions=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
        actions.addView(button("➕ Dépôt",Color.rgb(35,150,84)){pay(true)},LinearLayout.LayoutParams(0,dp(52),1f).apply{setMargins(0,0,dp(5),0)})
        actions.addView(button("➖ Retrait",Color.rgb(210,55,65)){pay(false)},LinearLayout.LayoutParams(0,dp(52),1f).apply{setMargins(dp(5),0,0,0)})
        add(actions,52)
        add(button("📜 Historique",Color.rgb(70,82,100)){history()},52);add(button("↻ Havaozina",Color.rgb(37,93,196)){loadWallet()},52);add(button("⌂ Accueil",Color.rgb(55,65,80)){home()},52)
        loadWallet()
    }

    private fun loadWallet() { get("/api/wallet"){j->val bal=j.optLong("balance",j.optLong("balanceMGA",0));runOnUiThread{if(currentScreen=="wallet"){status.text="Solde disponible";root.addView(card("💳 Solde","${bal} Ar",Color.rgb(35,150,84)),2)}}} }

    private fun history() {
        page("history","Historique")
        status=label("Maka ny transactions...",14f,false,Color.rgb(88,99,116));add(status)
        add(button("↻ Havaozina",Color.rgb(37,93,196)){loadHistory()},52);add(button("← Wallet",Color.rgb(70,82,100)){wallet()},52);loadHistory()
    }

    private fun loadHistory() { get("/api/wallet/transactions"){j->val rows=j.optJSONArray("items")?:j.optJSONArray("transactions");runOnUiThread{if(!::status.isInitialized||currentScreen!="history")return@runOnUiThread;status.text=if(rows==null||rows.length()==0)"Tsy mbola misy transaction." else buildString{append("${rows.length()} transaction(s)\n\n");for(i in 0 until minOf(rows.length(),30)){val x=rows.optJSONObject(i);append("• ${x?.optString("type",x?.optString("kind","TRANSACTION"))}: ${x?.optString("amount","0")} Ar — ${x?.optString("status","")}\n")}}}} }

    private fun pay(deposit:Boolean) {
        val amount=EditText(this).apply { hint="Montant (Ar)";inputType=2;setPadding(dp(12),0,dp(12),0) }
        val methods=arrayOf("MVola","Airtel Money","Orange Money")
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(8),0,dp(8),0)};box.addView(amount,LinearLayout.LayoutParams(-1,dp(52)));box.addView(label("Credentials tena izy mbola tsy ampiasaina.",12f,false,Color.rgb(120,128,140)))
        AlertDialog.Builder(this).setTitle(if(deposit)"Dépôt" else "Retrait").setView(box).setSingleChoiceItems(methods,0,null)
            .setPositiveButton("Hanohy"){d,_->val a=amount.text.toString().toLongOrNull()?:0;val selected=(d as AlertDialog).listView.checkedItemPosition;val method=arrayOf("MVOLA","AIRTEL_MONEY","ORANGE_MONEY")[selected.coerceIn(0,2)];if(a>0)post(if(deposit)"/api/wallet/deposit" else "/api/wallet/withdraw",JSONObject().put("amountMGA",a).put("method",method),UUID.randomUUID().toString()){wallet()} }
            .setNegativeButton("Aoka",null).show()
    }

    private fun lobby() {
        page("lobby","Lalao")
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

    private fun createGame() { status.text="Mamorona room — $entryFee Ar...";post("/api/games",JSONObject().put("entryFeeMGA",entryFee),UUID.randomUUID().toString()){j->gameId=j.optString("gameId");if(gameId.isBlank())status.text="Tsy nahazo Game ID." else room()} }

    private fun joinDialog() {
        val id=EditText(this).apply{hint="Game ID";setPadding(dp(12),0,dp(12),0)}
        val choices=entryFees.map { "${it} Ar" }.toTypedArray()
        var selected=entryFees.indexOf(entryFee).coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("Hiditra amin'ny room").setView(id).setSingleChoiceItems(choices,selected){_,which->selected=which}
            .setPositiveButton("HIDITRA"){_,_->if(id.text.isNotBlank()){gameId=id.text.toString().trim();entryFee=entryFees[selected];post("/api/games/$gameId/join",JSONObject().put("entryFeeMGA",entryFee),UUID.randomUUID().toString()){room()}}}
            .setNegativeButton("AOKA",null).show()
    }

    private fun room() {
        page("room","Room")
        add(card("Mise","$entryFee Ar",Color.rgb(37,93,196)))
        add(card("Prize pool","$entryFee Ar × joueurs",Color.rgb(235,170,25)))
        add(label("Game ID: $gameId",13f,false,Color.rgb(88,99,116)))
        status=label("Miandry mpilalao...",15f,true,Color.rgb(37,93,196));add(status)
        add(button("↻ Havaozina",Color.rgb(37,93,196)){refreshLobby()},52);add(button("← Lalao",Color.rgb(70,82,100)){gameId="";lobby()},52)
        refreshLobby()
    }

    private fun startGame(){
        startingGame=true
        status.text="Manomboka ny lalao..."
        post("/api/games/$gameId/start",JSONObject(),UUID.randomUUID().toString(),{
            startingGame=false
            refreshState()
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
        offlineTurn=0;offlineDice=0
        val arr=org.json.JSONArray();for(i in 0 until players){val ts=org.json.JSONArray();for(t in 0..3)ts.put(JSONObject().put("pos",-1));arr.put(JSONObject().put("id","offline-$i").put("tokens",ts))}
        offlineState=JSONObject().put("players",arr)
        offlineGame(players)
    }

    private fun offlineGame(players:Int) {
        page("offline","Ludo hors-ligne")
        val info=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        status=label("Joueur ${offlineTurn+1}/$players • Dé: $offlineDice",13f,true,Color.rgb(37,93,196));info.addView(status,LinearLayout.LayoutParams(0,-2,1f));info.addView(label("MANUEL",13f,true,Color.rgb(130,85,180)));add(info,34,0,5)
        val host=LudoBoardView(this);host.state=offlineState;host.player=offlineTurn
        root.addView(host,LinearLayout.LayoutParams(-1,0,1f).apply{setMargins(0,dp(4),0,dp(8))})
        add(button("Lancer le dé",Color.rgb(235,170,25)){offlineRoll(host)},54)
        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
        for(i in 0..3)row.addView(button("Pion ${i+1}",Color.rgb(70,82,100)){offlineMove(host,players,i)},LinearLayout.LayoutParams(0,dp(46),1f).apply{setMargins(dp(2),0,dp(2),0)})
        add(row,46,2,4);add(button("← Hors-ligne",Color.rgb(70,82,100)){offlineMenu()},50)
    }

    private fun offlineRoll(host:LudoBoardView){offlineDice=(1..6).random();status.text="Joueur ${offlineTurn+1} • Dé: $offlineDice";host.invalidate()}
    private fun offlineMove(host:LudoBoardView,players:Int,tokenIndex:Int){if(offlineDice==0){status.text="Atsipazo aloha ny dé.";return};val ps=offlineState?.optJSONArray("players")?:return;val p=ps.optJSONObject(offlineTurn)?:return;val ts=p.optJSONArray("tokens")?:return;val tok=ts.optJSONObject(tokenIndex)?:return;val old=tok.optInt("pos",-1);tok.put("pos",if(old<0&&offlineDice>=6)0 else if(old<0)-1 else old+offlineDice);offlineDice=0;offlineTurn=(offlineTurn+1)%players;host.state=offlineState;host.player=offlineTurn;offlineGame(players)}

    private fun board(){page("board","Lalao")
        val info=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        val st=label("",13f,true,Color.rgb(37,93,196));info.addView(st,LinearLayout.LayoutParams(0,-2,1f));info.addView(label("$entryFee Ar",15f,true,Color.rgb(235,170,25)));add(info,32,0,5)
        val host=LudoBoardView(this); boardHost=host; root.addView(host,LinearLayout.LayoutParams(-1,0,1f).apply{setMargins(0,dp(4),0,dp(8))})
        status=st;add(button("Lancer le dé",Color.rgb(235,170,25)){roll()},54);add(button("↻ Synchroniser",Color.rgb(37,93,196)){refreshState()},52)
        val tokens=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
        for(i in 0..3){tokens.addView(button("Pion ${i+1}",Color.rgb(70,82,100)){move(i)},LinearLayout.LayoutParams(0,dp(46),1f).apply{setMargins(dp(2),0,dp(2),0)})};add(tokens,46,2,4)
        add(label("Safidio ny pion-nao rehefa nahazo dé.",12f,false,Color.rgb(120,128,140)),30,0,0)
        updateBoard(host);root.postDelayed({if(currentScreen=="board"&&gameId.isNotBlank())refreshState()},4000)
    }

    private fun updateBoard(host:LudoBoardView){
        host.state=state;host.player=playerIndex();host.animateState();
        val i=state?.optInt("currentPlayerIndex",0)?:0;val d=state?.optInt("dice",0)?:0
        status.text="Tour: ${listOf("🔴","🟢","🟡","🔵").getOrElse(i){"?"}}  •  Dé: $d"
    }

    private fun refreshState(){get("/api/games/$gameId/state"){j->
        retryCount=0;state=j;version=j.optInt("stateVersion",0)
        runOnUiThread{if(currentScreen=="board" && boardHost!=null)updateBoard(boardHost!!) else if(currentScreen=="board")board()}
    }}
    private fun playerIndex():Int{val a=state?.optJSONArray("players")?:return -1;for(i in 0 until a.length())if(a.optJSONObject(i)?.optString("id")==userId)return i;return -1}
    private fun roll(){post("/api/games/$gameId/roll",JSONObject().put("expectedVersion",version),UUID.randomUUID().toString()){refreshState()}}
    private fun move(i:Int){post("/api/games/$gameId/move",JSONObject().put("tokenIndex",i).put("expectedVersion",version),UUID.randomUUID().toString()){refreshState()}}

    private fun get(path:String, ok:(JSONObject)->Unit)=request("GET",path,null,null,ok)
    private fun post(path:String, body:JSONObject, id:String?=null, ok:(JSONObject)->Unit)=request("POST",path,body,id,ok)
    private fun post(path:String, body:JSONObject, id:String?, ok:(JSONObject)->Unit, fail:((String)->Unit))=request("POST",path,body,id,ok,fail)
    private fun request(method:String,path:String,body:JSONObject?,id:String?,ok:(JSONObject)->Unit, fail:((String)->Unit)?=null){
        if(::status.isInitialized) runOnUiThread{status.text="Mampifandray..."}
        thread {
            try {
                val c=(URL(apiBase+path).openConnection() as HttpURLConnection).apply{requestMethod=method;connectTimeout=10000;readTimeout=10000;doInput=true
                    setRequestProperty("Content-Type","application/json");if(token.isNotBlank())setRequestProperty("Authorization","Bearer $token");if(!id.isNullOrBlank())setRequestProperty("Idempotency-Key",id)
                    if(body!=null)doOutput=true}
                if(body!=null)c.outputStream.use{it.write(body.toString().toByteArray())}
                val code=c.responseCode;val input=if(code in 200..299)c.inputStream else c.errorStream;val tx=input?.bufferedReader()?.readText().orEmpty()
                if(code !in 200..299){val msg=try{JSONObject(tx).optString("error","Erreur HTTP $code")}catch(_:Exception){"Erreur HTTP $code"};runOnUiThread{if(fail!=null)fail.invoke(msg) else if(::status.isInitialized)status.text=msg};return@thread}
                runOnUiThread{ok(if(tx.isBlank())JSONObject()else JSONObject(tx))}
            } catch(e:Exception){retryCount++;runOnUiThread{if(fail!=null)fail.invoke("Connexion tapaka. Averina... ($retryCount)") else if(::status.isInitialized)status.text="Connexion tapaka. Averina... ($retryCount)";if(currentScreen=="room")root.postDelayed({if(currentScreen=="room")refreshLobby()},minOf(15000L,3000L*retryCount))}}
        }
    }

    private inner class LudoBoardView(context:Context):View(context){
        var state:JSONObject?=null;var player=-1;private val paints=Array(4){Paint(Paint.ANTI_ALIAS_FLAG)}
        private val cols=intArrayOf(Color.rgb(218,60,65),Color.rgb(42,155,86),Color.rgb(232,178,34),Color.rgb(54,105,205))
        private val display=Array(4){Array(4){FloatArray(2){Float.NaN}}}
        private var stateAnimator:android.animation.ValueAnimator?=null
        init{setLayerType(View.LAYER_TYPE_SOFTWARE,null);for(i in 0..3){paints[i].color=cols[i];paints[i].style=Paint.Style.FILL}}
        override fun onDraw(canvas:Canvas){
            super.onDraw(canvas)
            val w=width.toFloat(); val h=height.toFloat(); val s=minOf(w,h)
            val ox=(w-s)/2f; val oy=(h-s)/2f; val cell=s/15f
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
                drawToken(c,ox+cc*cell,oy+rr*cell,cell,cols[pi],pi==player)
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
            if(pos>=0){val q=visualPath()[(pi*13+pos)%52];rr=q.first+0.5f;cc=q.second+0.5f}
            return floatArrayOf(rr,cc)
        }
        fun animateState(){
            val from=Array(4){Array(4){FloatArray(2)}};val to=Array(4){Array(4){FloatArray(2)}}
            for(pi in 0..3)for(ti in 0..3){
                val q=tokenCoord(pi,ti);to[pi][ti][0]=q[0];to[pi][ti][1]=q[1]
                if(display[pi][ti][0].isNaN()){display[pi][ti][0]=q[0];display[pi][ti][1]=q[1]}
                from[pi][ti][0]=display[pi][ti][0];from[pi][ti][1]=display[pi][ti][1]
            }
            stateAnimator?.cancel()
            stateAnimator=android.animation.ValueAnimator.ofFloat(0f,1f).apply{
                duration=280L;interpolator=android.view.animation.DecelerateInterpolator(1.6f)
                addUpdateListener{a->val f=a.animatedValue as Float;for(pi in 0..3)for(ti in 0..3){display[pi][ti][0]=from[pi][ti][0]+(to[pi][ti][0]-from[pi][ti][0])*f;display[pi][ti][1]=from[pi][ti][1]+(to[pi][ti][1]-from[pi][ti][1])*f};invalidate()}
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
            val pts=arrayOf(floatArrayOf(ox+6*cell,oy+6*cell,ox+9*cell,oy+6*cell,ox+7.5f*cell,oy+7.5f*cell),
                floatArrayOf(ox+9*cell,oy+6*cell,ox+9*cell,oy+9*cell,ox+7.5f*cell,oy+7.5f*cell),
                floatArrayOf(ox+9*cell,oy+9*cell,ox+6*cell,oy+9*cell,ox+7.5f*cell,oy+7.5f*cell),
                floatArrayOf(ox+6*cell,oy+9*cell,ox+6*cell,oy+6*cell,ox+7.5f*cell,oy+7.5f*cell))
            for(i in 0..3){val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=cols[i]};val path=Path();path.moveTo(pts[i][0],pts[i][1]);path.lineTo(pts[i][2],pts[i][3]);path.lineTo(pts[i][4],pts[i][5]);path.close();c.drawPath(path,p)}
            val ring=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=0x33FFFFFF;style=Paint.Style.STROKE;strokeWidth=dp(1.2f).toFloat()}
            c.drawCircle(ox+7.5f*cell,oy+7.5f*cell,cell*.72f,ring)
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
        private fun drawToken(c:Canvas,cx:Float,cy:Float,cell:Float,color:Int,active:Boolean){
            val rad=cell*.31f
            val shadow=Paint(Paint.ANTI_ALIAS_FLAG).apply{this.color=0x66000000}
            shadow.setShadowLayer(dp(2.5f).toFloat(),0f,dp(1.6f).toFloat(),0x77000000);c.drawCircle(cx,cy+dp(1f),rad,shadow);shadow.clearShadowLayer()
            val body=Paint(Paint.ANTI_ALIAS_FLAG)
            body.shader=RadialGradient(cx-rad*.32f,cy-rad*.38f,rad*.95f,color,Color.rgb((Color.red(color)*.45f).toInt(),(Color.green(color)*.45f).toInt(),(Color.blue(color)*.45f).toInt()),Shader.TileMode.CLAMP)
            c.drawCircle(cx,cy,rad,body);body.shader=null
            if(active){val glow=Paint(Paint.ANTI_ALIAS_FLAG).apply{this.color=Color.WHITE;style=Paint.Style.STROKE;strokeWidth=dp(1.7f).toFloat()};c.drawCircle(cx,cy,rad+dp(2f),glow)}
            val hi=Paint(Paint.ANTI_ALIAS_FLAG).apply{this.color=0x99FFFFFF.toInt()};c.drawCircle(cx-rad*.30f,cy-rad*.34f,rad*.18f,hi)
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
                    post("/api/games/$gameId/move",JSONObject().put("tokenIndex",touched).put("expectedVersion",version),UUID.randomUUID().toString()){refreshState()}
                    return true
                }
            }
            return true
        }
        override fun performClick():Boolean{super.performClick();return true}
        private fun nearestOwnToken(x:Float,y:Float,ox:Float,oy:Float,cell:Float):Int{
            val ps=state?.optJSONArray("players")
            val tokens=ps?.optJSONObject(player)?.optJSONArray("tokens")
            val fallback=arrayOf(1.8f to 1.8f,4.2f to 1.8f,1.8f to 4.2f,4.2f to 4.2f)
            var best=-1; var bestD2=Float.MAX_VALUE
            for(ti in 0..3){
                var rr=fallback[ti].first; var cc=fallback[ti].second
                val pos=tokens?.optJSONObject(ti)?.optInt("pos",-1)?:-1
                if(pos>=0){val q=visualPath()[(player*13+pos)%52];rr=q.first+0.5f;cc=q.second+0.5f}
                val dx=x-(ox+cc*cell); val dy=y-(oy+rr*cell); val d2=dx*dx+dy*dy
                if(d2<bestD2){bestD2=d2;best=ti}
            }
            return if(best>=0 && bestD2 <= (cell*0.82f)*(cell*0.82f)) best else -1
        }
        private fun path():List<Pair<Int,Int>>{val p=mutableListOf<Pair<Int,Int>>();for(c in 1..13)p+=0 to c;for(r in 1..13)p+=r to 14;for(c in 13 downTo 1)p+=14 to c;for(r in 13 downTo 1)p+=r to 0;return p}
    }
    private fun musicLabel():String = if(music.enabled()) "Musique: ACTIVÉE" else "Musique: DÉSACTIVÉE"
    private fun updateMusicButton(){ if(::musicButton.isInitialized) musicButton.text=musicLabel() }
    override fun onStart(){ super.onStart(); if(::music.isInitialized) music.start() }
    override fun onStop(){ if(::music.isInitialized) music.stop(); super.onStop() }

}
