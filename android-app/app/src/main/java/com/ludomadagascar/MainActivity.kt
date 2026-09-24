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
    private val entryFees = listOf(500, 1000, 2000)
    private var entryFee = 500
    private var token = ""; private var userId = ""; private var username = ""
    private var gameId = ""; private var version = 0; private var retryCount = 0; private var startingGame = false
    private var state: JSONObject? = null; private var currentScreen = ""
    private var offlineTurn = 0; private var offlineDice = 0; private var offlineState: JSONObject? = null
    private var selectedLanguage = "fr"
    private fun t(en:String, fr:String, mg:String) = when(selectedLanguage) { "fr" -> fr; "mg" -> mg; else -> en }
    private lateinit var root: LinearLayout; private lateinit var status: TextView; private lateinit var music: CalmMusic; private lateinit var musicButton: Button

    private fun dp(v:Int) = (v * resources.displayMetrics.density).toInt()
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
    private fun button(s:String, accent:Int=Color.rgb(37,93,196), action:(()->Unit)?=null) = Button(this).apply {
        text=s; textSize=14f; isAllCaps=false; setTextColor(Color.WHITE); setTypeface(null,android.graphics.Typeface.BOLD)
        background=shape(accent,18f); minHeight=dp(50); elevation=dp(2).toFloat(); setOnClickListener { action?.invoke() }
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
        val box=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(16),dp(14),dp(16),dp(14)); background=shape(Color.WHITE,22f,Color.rgb(225,230,238)); elevation=dp(1).toFloat() }
        box.addView(label(title,18f,true,accent)); box.addView(label(body,14f,false,Color.rgb(88,99,116)),LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(6)})
        return box
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
        page("login","Tongasoa 👋")
        add(label("Lalao Ludo an-tserasera ho an'ny 2–4 mpilalao.",15f,false,Color.rgb(88,99,116)))
        val info=card("🎯 Mise", "500 • 1 000 • 2 000 Ar", Color.rgb(37,93,196)); add(info)
        status=label("",13f,false,Color.rgb(190,55,55)); add(status)
        val u=EditText(this).apply{hint="Anaran'ny mpampiasa";textSize=16f;background=shape(Color.WHITE,18f,Color.rgb(225,230,238));setPadding(dp(14),0,dp(14),0)}
        val p=EditText(this).apply{hint="Password (10 caractères minimum)";textSize=16f;inputType=129;background=shape(Color.WHITE,18f,Color.rgb(225,230,238));setPadding(dp(14),0,dp(14),0)}
        add(u,54);add(p,54)
        add(button("Hiditra",Color.rgb(37,93,196), { if(validAuth(u,p)) auth("/api/auth/login",u.text.toString().trim(),p.text.toString()) }),54)
        add(button("Hamorona kaonty",Color.rgb(35,150,84), { if(validAuth(u,p)) auth("/api/auth/register",u.text.toString().trim(),p.text.toString()) }),54)
        add(card("🔵 Google", "Connexion Google sécurisée — bientôt disponible.", Color.rgb(70,82,100)))
        add(button("✉️ Inscription Email / 📱 Mobile",Color.rgb(130,85,180)){registerDialog()},52)
        add(label("Ny kaonty sy ny lalao dia voatahiry amin'ny serveur.",12f,false,Color.rgb(120,128,140)))
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

    private fun validAuth(u:EditText,p:EditText):Boolean { if(u.text.isBlank()||p.text.length<10){status.text="Fenoy tsara ny anarana sy password (10+).";return false}; return true }
    private fun auth(path:String,u:String,p:String) { status.text="Mampifandray..."; post(path,JSONObject().put("identifier",u).put("password",p)){j->token=j.optString("accessToken");userId=j.optJSONObject("user")?.optString("id").orEmpty();username=u;if(token.isBlank())status.text="Tsy tafiditra. Jereo indray." else home()} }

    private fun home() {
        page("home","Accueil")
        add(label("${t("Welcome", "Bienvenue", "Tongasoa")}, ${if(username.isBlank())t("Player", "Joueur", "Mpilalao") else username} 👋",24f,true))
        add(label("Vonona ve ianao handresy?",14f,false,Color.rgb(88,99,116)))
        val play=card("🎮 ${t("Online game", "Jeu en ligne", "Lalao an-tserasera")}",t("2–4 players • Choose a 500, 1,000 or 2,000 Ar stake", "2–4 joueurs • Choisissez une mise de 500, 1 000 ou 2 000 Ar", "2–4 mpilalao • Safidio ny mise 500, 1 000 na 2 000 Ar"),Color.rgb(37,93,196));play.setOnClickListener{lobby()};add(play)
        val wallet=card("💰 ${t("Wallet", "Portefeuille", "Wallet")}",t("Balance, deposit, withdrawal and history", "Solde, dépôt, retrait et historique", "Hijery solde, dépôt, retrait ary historique"),Color.rgb(35,150,84));wallet.setOnClickListener{wallet()};add(wallet)
        val prof=card("👤 ${t("Profile", "Profil", "Profil")}",t("Your account and player ID", "Votre compte et votre identifiant", "Kaontinao sy ID mpampiasa"),Color.rgb(130,85,180));prof.setOnClickListener{profile()};add(prof)
        add(button("🕹️ ${t("Offline game", "Jeu hors ligne", "Lalao hors-ligne")}",Color.rgb(130,85,180)){offlineMenu()},54)
        add(button("🚪 Hivoaka",Color.rgb(210,55,65)){token="";userId="";username="";gameId="";login()},52)
    }

    private fun profile() {
        page("profile","Profil")
        add(card("👤 Kaonty","Anarana: ${if(username.isBlank())"—" else username}\nUser ID: ${if(userId.isBlank())"—" else userId}",Color.rgb(130,85,180)))
        add(button("🌐 Langue / Language",Color.rgb(37,93,196)){languageDialog()},52)
        add(card("🛡️ Fiarovana","Aza zaraina amin'olona ny password na token. Ny credentials fandoavam-bola dia tsy tafiditra ao amin'ny APK.",Color.rgb(37,93,196)))
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
        add(card("🎯 Safidio ny mise","500 • 1 000 • 2 000 Ar",Color.rgb(37,93,196)))
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
        add(card("🎯 Mise","$entryFee Ar",Color.rgb(37,93,196)))
        add(card("💰 Prize pool","Miova araka ny isan'ny mpilalao ($entryFee Ar × mpilalao)",Color.rgb(235,170,25)))
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
        add(card("🕹️ Ludo manuel","Milalao tsy misy Internet. 2–4 joueurs, tour par tour, dé ary pion manuel.",Color.rgb(130,85,180)))
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
        add(button("🎲 Atsipazo ny dé",Color.rgb(235,170,25)){offlineRoll(host)},54)
        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
        for(i in 0..3)row.addView(button("Pion ${i+1}",Color.rgb(70,82,100)){offlineMove(host,players,i)},LinearLayout.LayoutParams(0,dp(46),1f).apply{setMargins(dp(2),0,dp(2),0)})
        add(row,46,2,4);add(button("← Hors-ligne",Color.rgb(70,82,100)){offlineMenu()},50)
    }

    private fun offlineRoll(host:LudoBoardView){offlineDice=(1..6).random();status.text="Joueur ${offlineTurn+1} • Dé: $offlineDice";host.invalidate()}
    private fun offlineMove(host:LudoBoardView,players:Int,tokenIndex:Int){if(offlineDice==0){status.text="Atsipazo aloha ny dé.";return};val ps=offlineState?.optJSONArray("players")?:return;val p=ps.optJSONObject(offlineTurn)?:return;val ts=p.optJSONArray("tokens")?:return;val tok=ts.optJSONObject(tokenIndex)?:return;val old=tok.optInt("pos",-1);tok.put("pos",if(old<0&&offlineDice>=6)0 else if(old<0)-1 else old+offlineDice);offlineDice=0;offlineTurn=(offlineTurn+1)%players;host.state=offlineState;host.player=offlineTurn;offlineGame(players)}

    private fun board(){page("board","Lalao")
        val info=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        val st=label("",13f,true,Color.rgb(37,93,196));info.addView(st,LinearLayout.LayoutParams(0,-2,1f));info.addView(label("$entryFee Ar",15f,true,Color.rgb(235,170,25)));add(info,32,0,5)
        val host=LudoBoardView(this);root.addView(host,LinearLayout.LayoutParams(-1,0,1f).apply{setMargins(0,dp(4),0,dp(8))})
        status=st;add(button("🎲 Atsipazo ny dé",Color.rgb(235,170,25)){roll()},54);add(button("↻ Synchroniser",Color.rgb(37,93,196)){refreshState()},52)
        val tokens=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
        for(i in 0..3){tokens.addView(button("Pion ${i+1}",Color.rgb(70,82,100)){move(i)},LinearLayout.LayoutParams(0,dp(46),1f).apply{setMargins(dp(2),0,dp(2),0)})};add(tokens,46,2,4)
        add(label("Safidio ny pion-nao rehefa nahazo dé.",12f,false,Color.rgb(120,128,140)),30,0,0)
        updateBoard(host);root.postDelayed({if(currentScreen=="board"&&gameId.isNotBlank())refreshState()},4000)
    }

    private fun updateBoard(host:LudoBoardView){host.state=state;host.player=playerIndex();host.invalidate();val i=state?.optInt("currentPlayerIndex",0)?:0;val d=state?.optInt("dice",0)?:0;status.text="Tour: ${listOf("🔴","🟢","🟡","🔵").getOrElse(i){"?"}}  •  Dé: $d"}

    private fun refreshState(){get("/api/games/$gameId/state"){j->retryCount=0;state=j;version=j.optInt("stateVersion",0);runOnUiThread{if(currentScreen=="board")board()}}}
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
        init{setLayerType(View.LAYER_TYPE_SOFTWARE,null);for(i in 0..3){paints[i].color=cols[i];paints[i].style=Paint.Style.FILL}}
        override fun onDraw(canvas:Canvas){super.onDraw(canvas);val w=width.toFloat();val h=height.toFloat();val s=minOf(w,h);val ox=(w-s)/2;val oy=(h-s)/2
            val bg=Paint(Paint.ANTI_ALIAS_FLAG);bg.color=Color.WHITE;bg.setShadowLayer(dp(5).toFloat(),0f,dp(2).toFloat(),0x22000000);canvas.drawRoundRect(ox,oy,ox+s,oy+s,dp(22).toFloat(),dp(22).toFloat(),bg)
            val cell=s/15f;drawZone(canvas,ox,oy,0,0,5,cols[0],bg);drawZone(canvas,ox,oy,10,0,5,cols[1],bg);drawZone(canvas,ox,oy,0,10,5,cols[2],bg);drawZone(canvas,ox,oy,10,10,5,cols[3],bg)
            val pathPaint=Paint(Paint.ANTI_ALIAS_FLAG);pathPaint.color=Color.rgb(239,242,247);pathPaint.style=Paint.Style.FILL
            for(c in 1..13){canvas.drawRect(ox+c*cell,oy,ox+(c+1)*cell,oy+cell,pathPaint);canvas.drawRect(ox+c*cell,oy+14*cell,ox+(c+1)*cell,oy+15*cell,pathPaint)}
            for(r in 1..13){canvas.drawRect(ox,oy+r*cell,ox+cell,oy+(r+1)*cell,pathPaint);canvas.drawRect(ox+14*cell,oy+r*cell,ox+15*cell,oy+(r+1)*cell,pathPaint)}
            val center=Paint(Paint.ANTI_ALIAS_FLAG);center.color=Color.rgb(250,251,253);canvas.drawRect(ox+5*cell,oy+5*cell,ox+10*cell,oy+10*cell,center)
            val stroke=Paint(Paint.ANTI_ALIAS_FLAG);stroke.color=Color.rgb(210,216,226);stroke.style=Paint.Style.STROKE;stroke.strokeWidth=dp(1).toFloat();for(i in 0..15){canvas.drawLine(ox+i*cell,oy,ox+i*cell,oy+s,stroke);canvas.drawLine(ox,oy+i*cell,ox+s,oy+i*cell,stroke)}
            drawTokens(canvas,ox,oy,cell)
        }
        private fun drawZone(c:Canvas,ox:Float,oy:Float,r:Int,col:Int,n:Int,color:Int,bg:Paint){bg.color=Color.WHITE;c.drawRoundRect(ox+r*(width.coerceAtMost(height).toFloat()/15f)+dp(5),oy+col*(width.coerceAtMost(height).toFloat()/15f)+dp(5),ox+(r+5)*(width.coerceAtMost(height).toFloat()/15f)-dp(5),oy+(col+5)*(width.coerceAtMost(height).toFloat()/15f)-dp(5),dp(14).toFloat(),dp(14).toFloat(),bg);bg.color=color;c.drawRoundRect(ox+(r+0.5f)*(width.coerceAtMost(height).toFloat()/15f),oy+(col+0.5f)*(width.coerceAtMost(height).toFloat()/15f),ox+(r+4.5f)*(width.coerceAtMost(height).toFloat()/15f),oy+(col+4.5f)*(width.coerceAtMost(height).toFloat()/15f),dp(12).toFloat(),dp(12).toFloat(),bg)}

        private fun drawTokens(c:Canvas,ox:Float,oy:Float,cell:Float){
            val ps=state?.optJSONArray("players")
            val fallback=arrayOf(arrayOf(2 to 2,2 to 4,4 to 2,4 to 4),arrayOf(2 to 10,2 to 12,4 to 10,4 to 12),arrayOf(10 to 2,10 to 4,12 to 2,12 to 4),arrayOf(10 to 10,10 to 12,12 to 10,12 to 12))
            for(pi in 0..3){
                val tokens=ps?.optJSONObject(pi)?.optJSONArray("tokens")
                for(ti in 0..3){
                    val q=fallback[pi][ti];var rr=q.first.toFloat()+0.5f;var cc=q.second.toFloat()+0.5f
                    val pos=tokens?.optJSONObject(ti)?.optInt("pos",-1)?:-1
                    if(pos>=0){val p=path()[(pi*13+pos)%52];rr=p.first+0.5f;cc=p.second+0.5f}
                    val paint=paints[pi];val rad=cell*0.28f;paint.setShadowLayer(dp(2).toFloat(),0f,dp(1).toFloat(),0x55000000)
                    c.drawCircle(ox+cc*cell,oy+rr*cell,rad,paint);paint.clearShadowLayer()
                }
            }
        }
        override fun onTouchEvent(e:android.view.MotionEvent):Boolean{
            if(e.action==MotionEvent.ACTION_UP && player in 0..3){
                val s=minOf(width,height).toFloat();val ox=(width-s)/2;val oy=(height-s)/2;val cell=s/15f
                val gx=((e.x-ox)/cell).toInt().coerceIn(0,14);val gy=((e.y-oy)/cell).toInt().coerceIn(0,14)
                val zone=when{gx<7&&gy<7->0;gx>=7&&gy<7->1;gx<7&&gy>=7->2;else->3}
                if(zone==player){val idx=when((gx+gy)%4){0->0;1->1;2->2;else->3};post("/api/games/$gameId/move",JSONObject().put("tokenIndex",idx).put("expectedVersion",version),UUID.randomUUID().toString()){refreshState()};return true}
            }
            return true
        }
        private fun path():List<Pair<Int,Int>>{val p=mutableListOf<Pair<Int,Int>>();for(c in 1..13)p+=0 to c;for(r in 1..13)p+=r to 14;for(c in 13 downTo 1)p+=14 to c;for(r in 13 downTo 1)p+=r to 0;return p}
    }
    private fun musicLabel():String = if(music.enabled()) "Musique: ACTIVÉE" else "Musique: DÉSACTIVÉE"
    private fun updateMusicButton(){ if(::musicButton.isInitialized) musicButton.text=musicLabel() }
    override fun onStart(){ super.onStart(); if(::music.isInitialized) music.start() }
    override fun onStop(){ if(::music.isInitialized) music.stop(); super.onStop() }

}
