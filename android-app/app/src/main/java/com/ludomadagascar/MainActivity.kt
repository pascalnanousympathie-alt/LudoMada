package com.ludomadagascar

import android.app.*
import android.os.*
import android.graphics.Color
import android.text.InputType
import android.view.View
import android.widget.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.concurrent.thread

class MainActivity: Activity() {
    private val apiBase = "http://127.0.0.1:3000"
    private var accessToken = ""
    private var currentGameId = ""
    private var currentFee = 0
    private lateinit var status: TextView
    private lateinit var box: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); login() }

    private fun root() = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(32,48,32,32); setBackgroundColor(Color.rgb(245,247,250)) }
    private fun add(v: View) { box.addView(v, LinearLayout.LayoutParams(-1,-2).apply { setMargins(0,8,0,8) }) }
    private fun title(t:String)=TextView(this).apply { text=t; textSize=28f }
    private fun info(t:String)=TextView(this).apply { text=t; textSize=17f }

    private fun login() {
        box=root(); add(title("Ludo Madagascar")); status=TextView(this); add(status)
        val username=EditText(this).apply { hint="Username" }
        val password=EditText(this).apply { hint="Password (10 caractères minimum)"; inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        add(username); add(password)
        add(Button(this).apply { text="HIDITRA"; setOnClickListener {
            post("/api/auth/login", JSONObject().put("username",username.text.toString().trim()).put("password",password.text.toString())) { j ->
                accessToken=j.optString("accessToken"); if(accessToken.isBlank()) status.text="Erreur: access token absent" else lobby()
            }
        }})
        add(Button(this).apply { text="HAMORONA KAONTY"; setOnClickListener {
            post("/api/auth/register", JSONObject().put("username",username.text.toString().trim()).put("password",password.text.toString())) { j ->
                accessToken=j.optString("accessToken"); status.text=if(accessToken.isBlank()) "Inscription vita. Midira izao." else "Kaonty vita. Afaka milalao."
            }
        }})
        setContentView(box)
    }

    private fun lobby() {
        box=root(); add(title("Ludo Madagascar")); status=TextView(this).apply { text="Safidio ny vola hidirana amin'ny room" }; add(status)
        listOf(500,1000,2000).forEach { fee -> add(Button(this).apply { text="MILALAO – "+fee+" AR"; setOnClickListener { createGame(fee) } }) }
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
        post("/api/games/"+id+"/join", JSONObject().put("entryFeeMGA",fee), UUID.randomUUID().toString()) { waitingRoom() }
    }

    private fun waitingRoom() {
        box=root(); add(title("Room – "+currentFee+" AR")); status=TextView(this); add(status)
        add(info("Game ID: "+currentGameId)); add(info("Zarao amin'ny mpilalao hafa ity Game ID ity."))
        add(Button(this).apply { text="HAVAOZINA NY ROOM"; setOnClickListener { refreshGame() } })
        add(Button(this).apply { text="HIVERINA"; setOnClickListener { lobby() } })
        setContentView(box); refreshGame()
    }

    private fun refreshGame() {
        if(currentGameId.isBlank()) return
        get("/api/games/"+currentGameId+"/lobby") { j ->
            val n=j.optJSONArray("participants")?.length() ?: 0
            status.text="Mpilalao ao amin'ny room: "+n+" / 4"
        }
    }

    private fun post(path:String,body:JSONObject,idempotency:String?=null,ok:(JSONObject)->Unit) { request("POST",path,body,idempotency,ok) }
    private fun get(path:String,ok:(JSONObject)->Unit) { request("GET",path,null,null,ok) }

    private fun request(method:String,path:String,body:JSONObject?,idempotency:String?,ok:(JSONObject)->Unit) {
        status.text="Miandry..."
        thread {
            try {
                val c=(URL(apiBase+path).openConnection() as HttpURLConnection).apply {
                    requestMethod=method; setRequestProperty("Content-Type","application/json")
                    if(accessToken.isNotBlank()) setRequestProperty("Authorization","Bearer "+accessToken)
                    if(!idempotency.isNullOrBlank()) setRequestProperty("Idempotency-Key",idempotency)
                    connectTimeout=10000; readTimeout=10000; doInput=true; if(body!=null) doOutput=true
                }
                if(body!=null) c.outputStream.use { it.write(body.toString().toByteArray()) }
                val code=c.responseCode; val stream=if(code in 200..299)c.inputStream else c.errorStream
                val text=stream?.bufferedReader()?.readText().orEmpty()
                if(code !in 200..299) {
                    val msg=try { JSONObject(text).optString("error","Erreur HTTP "+code) } catch(_:Exception) { "Erreur HTTP "+code }
                    runOnUiThread { status.text="Erreur: "+msg }; return@thread
                }
                val json=JSONObject(text); runOnUiThread { status.text="OK"; ok(json) }
            } catch(x:Exception) { runOnUiThread { status.text="Erreur réseau: "+(x.message ?: x.javaClass.simpleName) } }
        }
    }
}
