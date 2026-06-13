package com.hermes.android

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import okhttp3.*
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: android.content.SharedPreferences
    private var gatewayUrl = ""
    private var wsUrl = ""
    private var webSocket: WebSocket? = null
    private var isConnected = false
    private val handler = Handler(Looper.getMainLooper())
    private val messages = mutableListOf<Msg>()
    private lateinit var adapter: MsgAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var input: EditText
    private lateinit var statusDot: View
    private lateinit var statusText: TextView

    data class Msg(val role: String, val content: String)

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences("hermes_prefs", MODE_PRIVATE)
        gatewayUrl = prefs.getString("gateway_url", "http://192.168.1.5:18789") ?: "http://192.168.1.5:18789"
        wsUrl = gatewayUrl.replace("http://", "ws://") + "/api/ws"

        // Build UI
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0a0a0a"))
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        // Status bar
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setBackgroundColor(Color.parseColor("#1a1a1a"))
        }
        statusDot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply { setMargins(0, 0, dp(8), 0) }
            setBackgroundColor(Color.parseColor("#FF9800"))
        }
        statusText = TextView(this).apply {
            text = "Connecting..."
            textSize = 13f
            setTextColor(Color.parseColor("#aaa"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnSet = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_preferences)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { showSettings() }
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
        }
        bar.addView(statusDot)
        bar.addView(statusText)
        bar.addView(btnSet)

        // Chat list
        recycler = RecyclerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            layoutManager = LinearLayoutManager(this@MainActivity).apply { stackFromEnd = true }
        }
        adapter = MsgAdapter(messages)
        recycler.adapter = adapter

        // Input
        val inpBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(12))
            setBackgroundColor(Color.parseColor("#1a1a1a"))
        }
        input = EditText(this).apply {
            hint = "Type a message..."
            setHintTextColor(Color.parseColor("#555"))
            setTextColor(Color.WHITE)
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, dp(8), 0) }
        }
        val sendBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_send)
            setBackgroundColor(Color.parseColor("#1A73E8"))
            setOnClickListener { sendMsg() }
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        }
        inpBar.addView(input)
        inpBar.addView(sendBtn)

        root.addView(bar)
        root.addView(recycler)
        root.addView(inpBar)
        setContentView(root)

        addMsg("assistant", "Welcome to Hermes! Connect to your PC gateway to start chatting.")
        connectWs()
        startMonitor()
    }

    private fun connectWs() {
        updateStatus("connecting", "⏳ Connecting...")
        val req = Request.Builder().url(wsUrl).build()
        webSocket = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, resp: Response) {
                isConnected = true
                handler.post { updateStatus("online", "🟢 Connected") }
            }
            override fun onMessage(ws: WebSocket, text: String) {
                handler.post {
                    try {
                        val obj = JSONObject(text)
                        if (obj.has("result")) {
                            val content = obj.optJSONObject("result")?.optString("content", "") ?: text
                            addMsg("assistant", content)
                        }
                    } catch (e: Exception) {
                        addMsg("assistant", text)
                    }
                }
            }
            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                ws.close(1000, null)
                isConnected = false
                handler.post { updateStatus("offline", "🔴 Disconnected") }
            }
            override fun onFailure(ws: WebSocket, t: Throwable, resp: Response?) {
                isConnected = false
                handler.post { updateStatus("offline", "🔴 Failed — tap Settings") }
            }
        })
    }

    private fun sendMsg() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.setText("")
        addMsg("user", text)
        if (isConnected) {
            val rpc = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", UUID.randomUUID().toString())
                put("method", "chat.send")
                put("params", JSONObject().apply { put("message", text) })
            }.toString()
            webSocket?.send(rpc)
        } else {
            addMsg("assistant", "⏸ Not connected — check gateway settings")
            connectWs()
        }
    }

    private fun addMsg(role: String, content: String) {
        messages.add(Msg(role, content))
        handler.post {
            adapter.notifyItemInserted(messages.size - 1)
            recycler.scrollToPosition(messages.size - 1)
        }
    }

    private fun updateStatus(state: String, text: String) {
        statusText.text = text
        val c = when (state) {
            "online" -> Color.parseColor("#4CAF50")
            "offline" -> Color.parseColor("#F44336")
            else -> Color.parseColor("#FF9800")
        }
        statusDot.setBackgroundColor(c)
    }

    private fun startMonitor() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (!isConnected) connectWs()
                handler.postDelayed(this, 15000)
            }
        }, 15000)
    }

    private fun showSettings() {
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        val etIp = view.findViewById<EditText>(R.id.etGatewayIp)
        val etPort = view.findViewById<EditText>(R.id.etGatewayPort)
        val parts = gatewayUrl.replace("http://", "").split(":")
        etIp.setText(parts[0])
        etPort.setText(parts.getOrElse(1) { "18789" })
        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                gatewayUrl = "http://${etIp.text}:${etPort.text}"
                wsUrl = "ws://${etIp.text}:${etPort.text}/api/ws"
                prefs.edit().putString("gateway_url", gatewayUrl).apply()
                webSocket?.close(1000, "reconnect")
                connectWs()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    inner class MsgAdapter(private val items: List<Msg>) : RecyclerView.Adapter<MsgAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) { val bubble: TextView = v.findViewById(R.id.bubble) }
        override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
            val v = layoutInflater.inflate(R.layout.item_chat, p, false); return VH(v)
        }
        override fun onBindViewHolder(h: VH, i: Int) {
            val m = items[i]
            h.bubble.text = m.content
            val lp = h.bubble.layoutParams as FrameLayout.LayoutParams
            if (m.role == "user") {
                h.bubble.setBackgroundColor(Color.parseColor("#1A73E8"))
                h.bubble.setTextColor(Color.WHITE)
                lp.gravity = Gravity.END
            } else {
                h.bubble.setBackgroundColor(Color.parseColor("#2a2a2a"))
                h.bubble.setTextColor(Color.parseColor("#e0e0e0"))
                lp.gravity = Gravity.START
            }
            h.bubble.layoutParams = lp
        }
        override fun getItemCount() = items.size
    }

    override fun onDestroy() {
        webSocket?.close(1000, "exit")
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}