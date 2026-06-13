package com.hermes.android

import android.animation.ValueAnimator
import android.content.Context
import android.content.SharedPreferences
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import okhttp3.*
import org.json.JSONObject
import java.util.*
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private var gatewayUrl = "http://192.168.1.5:18789"
    private var wsUrl = "ws://192.168.1.5:18789/api/ws"
    private var webSocket: WebSocket? = null
    private var isConnected = false
    private var isOnline = false
    private val handler = Handler(Looper.getMainLooper())
    private val messages = mutableListOf<Msg>()
    private val pendingQueue = mutableListOf<String>()
    private var reconnectCount = 0

    private lateinit var recycler: RecyclerView
    private lateinit var adapter: MsgAdapter
    private lateinit var inputField: EditText
    private lateinit var sendBtn: ImageButton
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var syncBadge: TextView
    private lateinit var btnSettings: ImageButton
    private lateinit var typingIndicator: LinearLayout
    private lateinit var offlineBanner: TextView

    data class Msg(val role: String, val content: String, val time: Long = System.currentTimeMillis())

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("hermes", MODE_PRIVATE)
        gatewayUrl = prefs.getString("gw", "http://192.168.1.5:18789") ?: "http://192.168.1.5:18789"
        wsUrl = gatewayUrl.replace("http://", "ws://") + "/api/ws"
        buildUI()
        connect()
        startMonitor()
    }

    private fun buildUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(C_BG)
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        }

        // Status bar
        val statusContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(14)); setBackgroundColor(C_SURFACE)
        }
        statusDot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8))
            background = circle(C_ORANGE)
        }
        statusText = TextView(this).apply {
            text = "Connecting…"; textSize = 13f; setTextColor(C_TEXT_SEC)
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { setMargins(dp(10), 0, 0, 0) }
        }
        syncBadge = TextView(this).apply {
            text = ""; textSize = 10f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(dp(6), dp(2), dp(6), dp(2)); background = rounded(C_GREEN, dp(10))
            visibility = View.GONE; layoutParams = LinearLayout.LayoutParams(WRAP, dp(20)).apply { setMargins(0, 0, dp(8), 0) }
        }
        btnSettings = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_preferences)
            setBackgroundColor(Color.TRANSPARENT); setColorFilter(C_TEXT_SEC)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            setOnClickListener { showSettings() }
        }
        statusContainer.addView(statusDot); statusContainer.addView(statusText)
        statusContainer.addView(syncBadge); statusContainer.addView(btnSettings)

        // Offline banner
        offlineBanner = TextView(this).apply {
            text = "⚠ Offline — Messages will send when connected"
            textSize = 12f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8)); setBackgroundColor(C_ORANGE); visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }

        // Chat list
        recycler = RecyclerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
            layoutManager = LinearLayoutManager(this@MainActivity).apply { stackFromEnd = true }
            setBackgroundColor(C_BG); setPadding(0, dp(8), 0, dp(8)); clipToPadding = false
        }
        adapter = MsgAdapter(messages)
        recycler.adapter = adapter

        // Typing indicator
        typingIndicator = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(4)); setBackgroundColor(C_BG); visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }
        for (i in 0..2) {
            val dot = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(6), dp(6)).apply { setMargins(dp(2), 0, dp(2), 0) }
                background = rounded(C_TEXT_SEC, dp(3))
            }
            typingIndicator.addView(dot)
        }

        // Input bar
        val inputBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(12)); setBackgroundColor(C_SURFACE)
        }
        inputField = EditText(this).apply {
            hint = "Message Hermes…"; setHintTextColor(C_HINT); setTextColor(C_TEXT)
            textSize = 16f; setPadding(dp(16), dp(12), dp(16), dp(12))
            background = rounded(C_INPUT, dp(24))
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { setMargins(0, 0, dp(8), 0) }
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { send(); true } else false }
        }
        sendBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_send); setBackgroundColor(C_ACCENT)
            background = circle(C_ACCENT); setColorFilter(Color.WHITE)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            setOnClickListener { send() }
        }
        inputBar.addView(inputField); inputBar.addView(sendBtn)

        root.addView(statusContainer); root.addView(offlineBanner)
        root.addView(recycler); root.addView(typingIndicator); root.addView(inputBar)
        setContentView(root)

        addMsg("assistant", "👋 Welcome to Hermes!\n\nI'm your AI assistant. Send me a message to get started.", animate = true)
    }

    private fun connect() {
        updateStatus("connecting", "Connecting…")
        val req = Request.Builder().url(wsUrl).build()
        webSocket = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, resp: Response) {
                isConnected = true; isOnline = true; reconnectCount = 0
                handler.post {
                    updateStatus("online", "● Connected"); hideOfflineBanner()
                    syncBadge.text = "Synced"; syncBadge.visibility = View.VISIBLE
                    pulse(syncBadge); pendingQueue.forEach { sendRpc(it) }; pendingQueue.clear()
                }
            }
            override fun onMessage(ws: WebSocket, text: String) {
                handler.post {
                    hideTyping()
                    try {
                        val obj = JSONObject(text)
                        when {
                            obj.has("result") -> addMsg("assistant", obj.optJSONObject("result")?.optString("content", "") ?: text, animate = true)
                            obj.optString("method") == "agent.token" -> appendToLastAssistant(obj.optJSONObject("params")?.optString("token", "") ?: "")
                            obj.optString("method") == "agent.start" -> showTyping()
                            obj.optString("method") == "agent.done" -> hideTyping()
                        }
                    } catch (e: Exception) { addMsg("assistant", text, animate = true) }
                }
            }
            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                ws.close(1000, null); isConnected = false
                handler.post { updateStatus("offline", "○ Disconnected"); showOfflineBanner() }
            }
            override fun onFailure(ws: WebSocket, t: Throwable, resp: Response?) {
                isConnected = false; isOnline = false
                handler.post {
                    updateStatus("offline", "○ Failed"); showOfflineBanner()
                    addMsg("assistant", "⚠ Cannot reach gateway at $gatewayUrl\n\n• PC is on?\n• Hermes desktop app running?\n• Same WiFi?", animate = false)
                }
            }
        })
    }

    private fun sendRpc(text: String) {
        val rpc = JSONObject().apply {
            put("jsonrpc", "2.0"); put("id", UUID.randomUUID().toString())
            put("method", "chat.send"); put("params", JSONObject().apply { put("message", text) })
        }.toString()
        webSocket?.send(rpc)
    }

    private fun send() {
        val text = inputField.text.toString().trim()
        if (text.isEmpty()) return
        inputField.setText("")
        addMsg("user", text, animate = true)
        if (isConnected) { sendRpc(text); showTyping() }
        else { pendingQueue.add(text); addMsg("assistant", "⏸ Queued — will send when connected", animate = false); connect() }
    }

    private fun addMsg(role: String, content: String, animate: Boolean = false) {
        messages.add(Msg(role, content))
        val pos = messages.size - 1
        handler.post {
            adapter.notifyItemInserted(pos)
            recycler.smoothScrollToPosition(pos)
            if (animate) recycler.findViewHolderForAdapterPosition(pos)?.itemView?.apply {
                alpha = 0f; translationY = 50f
                animate().alpha(1f).translationY(0f).setDuration(300).setInterpolator(DecelerateInterpolator()).start()
            }
        }
    }

    private fun appendToLastAssistant(token: String) {
        if (messages.isNotEmpty() && messages.last().role == "assistant") {
            val last = messages.last()
            messages[messages.size - 1] = last.copy(content = last.content + token)
            handler.post { adapter.notifyItemChanged(messages.size - 1); recycler.smoothScrollToPosition(messages.size - 1) }
        } else addMsg("assistant", token)
    }

    private fun updateStatus(state: String, text: String) {
        statusText.text = text
        val c = when (state) { "online" -> C_GREEN; "offline" -> C_RED; else -> C_ORANGE }
        statusDot.background = circle(c)
        ValueAnimator.ofFloat(1f, 1.3f, 1f).apply {
            duration = 400; addUpdateListener { statusDot.scaleX = it.animatedValue as Float; statusDot.scaleY = it.animatedValue as Float }; start()
        }
    }

    private fun showTyping() {
        typingIndicator.visibility = View.VISIBLE
        for (i in 0 until typingIndicator.childCount) {
            typingIndicator.getChildAt(i)?.let { dot ->
                ValueAnimator.ofFloat(0.4f, 1f, 0.4f).apply {
                    duration = 600; startDelay = (i * 200).toLong(); repeatCount = ValueAnimator.INFINITE
                    addUpdateListener { dot.alpha = it.animatedValue as Float }; start()
                }
            }
        }
    }

    private fun hideTyping() { typingIndicator.visibility = View.GONE }

    private fun showOfflineBanner() {
        if (offlineBanner.visibility != View.VISIBLE) {
            offlineBanner.visibility = View.VISIBLE
            offlineBanner.translationY = -dp(40).toFloat()
            offlineBanner.animate().translationY(0f).setDuration(300).setInterpolator(DecelerateInterpolator()).start()
        }
    }

    private fun hideOfflineBanner() {
        if (offlineBanner.visibility == View.VISIBLE) {
            offlineBanner.animate().translationY(-dp(40).toFloat()).setDuration(200).withEndAction { offlineBanner.visibility = View.GONE }.start()
        }
    }

    private fun startMonitor() {
        handler.postDelayed(object : Runnable {
            override fun run() { if (!isConnected) { reconnectCount++; connect() }; handler.postDelayed(this, 15000) }
        }, 15000)
    }

    private fun showSettings() {
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        val etIp = view.findViewById<EditText>(R.id.etGatewayIp)
        val etPort = view.findViewById<EditText>(R.id.etGatewayPort)
        val parts = gatewayUrl.replace("http://", "").split(":")
        etIp.setText(parts[0]); etPort.setText(parts.getOrElse(1) { "18789" })
        AlertDialog.Builder(this)
            .setTitle("Gateway Settings").setView(view)
            .setPositiveButton("Save") { _, _ ->
                gatewayUrl = "http://${etIp.text}:${etPort.text}"
                wsUrl = "ws://${etIp.text}:${etPort.text}/api/ws"
                prefs.edit().putString("gw", gatewayUrl).apply()
                webSocket?.close(1000, "reconnect"); connect()
            }
            .setNegativeButton("Cancel", null).show()
    }

    inner class MsgAdapter(private val items: List<Msg>) : RecyclerView.Adapter<MsgAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) { val bubble: TextView = v.findViewById(R.id.bubble); val time: TextView = v.findViewById(R.id.time) }
        override fun onCreateViewHolder(p: ViewGroup, t: Int): VH { return VH(layoutInflater.inflate(R.layout.item_chat, p, false)) }
        override fun onBindViewHolder(h: VH, pos: Int) {
            val m = items[pos]; h.bubble.text = m.content
            h.time.text = android.text.format.DateFormat.format("HH:mm", m.time).toString()
            val lp = h.bubble.layoutParams as FrameLayout.LayoutParams
            if (m.role == "user") {
                h.bubble.background = rounded(C_ACCENT, dp(18)); h.bubble.setTextColor(Color.WHITE)
                lp.gravity = Gravity.END; h.time.gravity = Gravity.END; h.time.setTextColor(C_HINT)
            } else {
                h.bubble.background = rounded(C_SURFACE_CARD, dp(18)); h.bubble.setTextColor(C_TEXT)
                lp.gravity = Gravity.START; h.time.gravity = Gravity.START; h.time.setTextColor(C_HINT)
            }
            h.bubble.layoutParams = lp; h.bubble.maxWidth = (resources.displayMetrics.widthPixels * 0.78f).toInt()
        }
        override fun getItemCount() = items.size
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private val Int.dp: Int get() = dp(this)
    private fun circle(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
    private fun rounded(color: Int, r: Int) = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; setColor(color); cornerRadius = r.toFloat() }
    private fun pulse(v: View) { ValueAnimator.ofFloat(1f, 1.2f, 1f).apply { duration = 500; addUpdateListener { v.scaleX = it.animatedValue as Float; v.scaleY = it.animatedValue as Float }; start() }

    override fun onBackPressed() { moveTaskToBack(true) }
    override fun onDestroy() { webSocket?.close(1000, "exit"); handler.removeCallbacksAndMessages(null); super.onDestroy() }

    companion object {
        val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val C_BG = 0xFF0D1117.toInt()
        const val C_SURFACE = 0xFF161B22.toInt()
        const val C_SURFACE_CARD = 0xFF21262D.toInt()
        const val C_INPUT = 0xFF30363D.toInt()
        const val C_ACCENT = 0xFF1A73E8.toInt()
        const val C_GREEN = 0xFF3FB950.toInt()
        const val C_RED = 0xFFF85149.toInt()
        const val C_ORANGE = 0xFFF0883E.toInt()
        const val C_TEXT = 0xFFE6EDF3.toInt()
        const val C_TEXT_SEC = 0xFF8B949E.toInt()
        const val C_HINT = 0xFF484F58.toInt()
    }
}