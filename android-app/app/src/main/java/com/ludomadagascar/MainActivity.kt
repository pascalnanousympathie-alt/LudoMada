package com.ludomadagascar

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private val apiBase = "http://127.0.0.1:3000"
    private var accessToken = ""
    private var currentUserId = ""
    private var currentGameId = ""
    private var currentFee = 0
    private var stateVersion = 0
    private var stateJson: JSONObject? = null
    private lateinit var status: TextView
    private lateinit var box: LinearLayout
    private val path = buildPath()
    private val colors = intArrayOf(Color.rgb(210,45,45), Color.rgb(35,150,70), Color.rgb(235,185,35), Color.rgb(45,95,190))
    private val names = arrayOf("RED", "GREEN", "YELLOW", "BLUE")

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); login() }

    private fun root() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(18,24,18,18); setBackgroundColor(Color.rgb(245,247,250))
    }
    private fun add(v: View) { box.addView(v, LinearLayout.LayoutParams(-1,-2).apply { setMargins(0,6,0,6) }) }
    private fun title(t:String)=TextView(this).apply { text=t; textSize=26f; setTypeface(null,Typeface.BOLD) }
    private fun info(t:String)=TextView(this).apply { text=t; textSize=16f }

    private fun login() {
        box=root(); add(title("Ludo Madagascar")); status=TextView(this); add(status)
        val username=EditText(this).apply { hint="Username" }
        val password=EditText(this).apply { hint="Password (10 caractères minimum)"; inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        add(username); add(password)
        add(Button(this).apply { text="HIDITRA"; setOnClickListener {
            post("/api/auth/login", JSONObject().put("username",username.text.toString().trim()).put("password",password.text.toString())) { j ->
                accessToken=j.optString("accessToken"); currentUserId=j.optJSONObject("user")?.optString("id").orEmpty()
                if(accessToken.isBlank()) status.text="Erreur: access token absent" else lobby()
            }
        }})
        add(Button(this).apply { text="HAMORONA KAONTY"; setOnClickListener {
            post("/api/auth/register", JSONObject().put("username",username.text.toString().trim()).put("password",password.text.toString())) { j ->
                accessToken=j.optString("accessToken"); currentUserId=j.optJSONObject("user")?.optString("id").orEmpty()
                if(accessToken.isBlank()) status.text="Inscription vita. Midira izao." else lobby()
            }
        }})
        setContentView(box)
    }

    private fun lobby() {
        box=root(); add(title("Ludo Madagascar")); status=TextView(this).apply { text="Safidio ny vola hidirana" }; add(status)
        listOf(500,1000,2000).forEach { fee -> add(Button(this).apply { text="MILALAO – $fee AR"; setOnClickListener { createGame(fee) } }) }
        add(Button(this).apply { text="HIDITRA AMIN'NY ROOM EXISTANT"; setOnClickListener { joinDialog() } })
        setContentView(box)
    }

    private fun createGame(fee:Int) {
        currentFee=fee
        post("/api/games", JSONObject().put("entryFeeMGA",fee), UUID.randomUUID().toString()) { j ->
            currentGameId=j.optString("gameId")
            if(currentGameId.isBlank()) status.text="Erreur: Game ID tsy hita" else waitingRoom()
        }
    }

    private fun joinDialog() {
        val input=EditText(this).apply { hint="Game ID" }
        val feeInput=EditText(this).apply { hint="Vola: 500 / 1000 / 2000"; inputType=InputType.TYPE_CLASS_NUMBER }
        val holder=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; addView(input); addView(feeInput) }
        AlertDialog.Builder(this).setTitle("Hiditra room").setView(holder).setPositiveButton("HIDITRA") { _,_ ->
            val id=input.text.toString().trim(); val fee=feeInput.text.toString().toIntOrNull() ?: 0
            if(id.isNotBlank() && fee in listOf(500,1000,2000)) joinGame(id,fee) else status.text="Ampidiro Game ID sy vola marina"
        }.setNegativeButton("AOKA",null).show()
    }

    private fun joinGame(id:String,fee:Int) {
        currentGameId=id; currentFee=fee
        post("/api/games/$id/join", JSONObject().put("entryFeeMGA",fee), UUID.randomUUID().toString()) { waitingRoom() }
    }

    private fun waitingRoom() {
        box=root(); add(title("Room – $currentFee AR")); status=TextView(this); add(status)
        add(info("Game ID: $currentGameId")); add(info("Mpilalao 4 no ilaina vao manomboka."))
        add(Button(this).apply { text="HAVAOZINA"; setOnClickListener { refreshGame() } })
        add(Button(this).apply { text="HIVERINA"; setOnClickListener { lobby() } })
        setContentView(box); refreshGame()
    }

    private fun refreshGame() {
        get("/api/games/$currentGameId/lobby") { j ->
            val n=j.optJSONArray("participants")?.length() ?: 0
            status.text="Mpilalao: $n / 4"
            if(n >= 2) refreshState() else status.text="Mpilalao: $n / 4 – miandry mpilalao..."
        }
    }

    private fun showBoard() {
        box=root()
        val top=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
        status=TextView(this).apply { textSize=16f; layoutParams=LinearLayout.LayoutParams(0,-2,1f) }
        top.addView(status)
        top.addView(Button(this).apply { text="↻"; setOnClickListener { refreshState() } })
        top.addView(Button(this).apply { text="ROOM"; setOnClickListener { waitingRoom() } })
        add(top)
        add(title("LUDO BOARD"))
        val board=GridLayout(this).apply { columnCount=15; rowCount=15; useDefaultMargins=false }
        val cells=Array(15){ arrayOfNulls<TextView>(15) }
        for(r in 0 until 15) for(c in 0 until 15) {
            val tv=TextView(this).apply {
                gravity=Gravity.CENTER; textSize=9f; setTypeface(null,Typeface.BOLD)
                setBackgroundColor(Color.WHITE); setTextColor(Color.DKGRAY)
                layoutParams=GridLayout.LayoutParams().apply { width=0; height=0; rowSpec=GridLayout.spec(r,1f); columnSpec=GridLayout.spec(c,1f) }
            }
            cells[r][c]=tv; board.addView(tv)
        }
        box.addView(board,LinearLayout.LayoutParams(-1,0,1f))
        val controls=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        val dice=Button(this).apply { text="🎲 ROLL"; setOnClickListener { roll() } }
        controls.addView(dice)
        val moves=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER }
        for(i in 0..3) moves.addView(Button(this).apply { text="P${i+1}"; tag=i; setOnClickListener { move(i) } },LinearLayout.LayoutParams(0,-2,1f))
        controls.addView(moves)
        box.addView(controls)
        setContentView(box)
        drawBoard(cells)
    }

    private fun drawBoard(cells:Array<Array<TextView?>>) {
        for(r in 0 until 15) for(c in 0 until 15) { cells[r][c]?.apply { text=""; setBackgroundColor(if(isPath(r,c)) Color.rgb(232,232,232) else Color.WHITE) } }
        val state=stateJson ?: return
        val players=state.optJSONArray("players") ?: return
        for(pi in 0 until players.length()) {
            val p=players.optJSONObject(pi) ?: continue; val tokens=p.optJSONArray("tokens") ?: continue
            for(ti in 0 until tokens.length()) {
                val pos=tokens.optJSONObject(ti)?.optInt("pos",-1) ?: -1
                val coord=tokenCoord(pi,pos) ?: homeCoord(pi,ti)
                val tv=cells[coord.first][coord.second] ?: continue
                tv.text=(if(tv.text.isNullOrBlank()) "" else tv.text.toString()+" ")+"${names[pi][0]}${ti+1}"
                tv.setTextColor(Color.WHITE); tv.setBackgroundColor(colors[pi]); tv.setOnClickListener { if(pi == playerIndex()) move(ti) }
            }
        }
    }

    private fun playerIndex():Int {
        val ps=stateJson?.optJSONArray("players") ?: return -1
        for(i in 0 until ps.length()) if(ps.optJSONObject(i)?.optString("id") == currentUserId) return i
        return -1
    }

    private fun refreshState() {
        get("/api/games/$currentGameId/state") { j ->
            stateJson=j; stateVersion=j.optInt("stateVersion",0)
            val phase=j.optString("phase",j.optString("status","")); val idx=j.optInt("currentPlayerIndex",0)
            status.text="Tour: ${names.getOrElse(idx){"?"}} | Dice: ${j.optInt("dice",0)} | Version: $stateVersion | $phase"
            showBoard()
            get("/api/games/$currentGameId/legal-moves") { lm ->
                val arr=lm.optJSONArray("moves"); val list=if(arr==null) "aucun" else (0 until arr.length()).joinToString(",") { "P${arr.optInt(it)+1}" }
                status.text="Tour: ${names.getOrElse(idx){"?"}} | Dice: ${j.optInt("dice",0)} | Pions jouables: $list"
            }
        }
    }

    private fun roll() {
        val body=JSONObject().put("expectedVersion",stateVersion)
        post("/api/games/$currentGameId/roll",body,UUID.randomUUID().toString()) { refreshState() }
    }

    private fun move(tokenIndex:Int) {
        val body=JSONObject().put("tokenIndex",tokenIndex).put("expectedVersion",stateVersion)
        post("/api/games/$currentGameId/move",body,UUID.randomUUID().toString()) { refreshState() }
    }

    private fun isPath(r:Int,c:Int):Boolean = path.any { it.first==r && it.second==c }

    private fun tokenCoord(player:Int,pos:Int):Pair<Int,Int>? {
        if(pos<0 || pos>51) return null
        val start=player*13
        return path[(start+pos)%52]
    }

    private fun homeCoord(player:Int,token:Int):Pair<Int,Int> = when(player) {
        0 -> arrayOf(2 to 2,2 to 4,4 to 2,4 to 4)[token]
        1 -> arrayOf(2 to 10,2 to 12,4 to 10,4 to 12)[token]
        2 -> arrayOf(10 to 2,10 to 4,12 to 2,12 to 4)[token]
        else -> arrayOf(10 to 10,10 to 12,12 to 10,12 to 12)[token]
    }

    private fun buildPath():List<Pair<Int,Int>> {
        val p=mutableListOf<Pair<Int,Int>>()
        for(c in 1..13) p.add(0 to c)
        for(r in 1..13) p.add(r to 14)
        for(c in 13 downTo 1) p.add(14 to c)
        for(r in 13 downTo 1) p.add(r to 0)
        return p
    }

    private fun post(path:String,body:JSONObject,idempotency:String?=null,ok:(JSONObject)->Unit) { request("POST",path,body,idempotency,ok) }
    private fun get(path:String,ok:(JSONObject)->Unit) { request("GET",path,null,null,ok) }

    private fun request(method:String,path:String,body:JSONObject?,idempotency:String?,ok:(JSONObject)->Unit) {
        if(::status.isInitialized) status.text="Miandry..."
        thread {
            try {
                val c=(URL(apiBase+path).openConnection() as HttpURLConnection).apply {
                    requestMethod=method; setRequestProperty("Content-Type","application/json")
                    if(accessToken.isNotBlank()) setRequestProperty("Authorization","Bearer $accessToken")
                    if(!idempotency.isNullOrBlank()) setRequestProperty("Idempotency-Key",idempotency)
                    connectTimeout=10000; readTimeout=10000; doInput=true; if(body!=null) doOutput=true
                }
                if(body!=null) c.outputStream.use { it.write(body.toString().toByteArray()) }
                val code=c.responseCode; val stream=if(code in 200..299)c.inputStream else c.errorStream
                val text=stream?.bufferedReader()?.readText().orEmpty()
                if(code !in 200..299) {
                    val msg=try { JSONObject(text).optString("error","Erreur HTTP $code") } catch(_:Exception) { "Erreur HTTP $code" }
                    runOnUiThread { status.text="Erreur: $msg" }; return@thread
                }
                val json=if(text.isBlank()) JSONObject() else JSONObject(text)
                runOnUiThread { status.text="OK"; ok(json) }
            } catch(x:Exception) { runOnUiThread { status.text="Erreur réseau: ${x.message ?: x.javaClass.simpleName}" } }
        }
    }
}
