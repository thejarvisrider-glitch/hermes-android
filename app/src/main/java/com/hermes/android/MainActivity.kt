package com.hermes.android

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Hermes Android v4.0 — Native Chat UI
 * 
 * Features:
 * - Native chat UI (message bubbles)
 * - WebSocket connection to Hermes gateway (JSON-RPC)
 * - Online mode: connect to PC gateway
 * - Offline mode: local storage + sync when back online
 * - Workspace sync via Syncthing API
 * - Settings: gateway URL, sync config
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var inputField: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var statusDot: View
    private lateinit var statusLabel: TextView
    private lateinit var syncLabel: TextView
    private lateinit var btnSettings: ImageButton
    private val handler = Handler(Looper.getMainLooper())
    
    private var webSocket: WebSocket? = null
    private var gatewayUrl: String = ""
    private var wsUrl: String = ""
    private var sessionId: String = UUID.randomUUID().toString()
    private var isConnected = false
    private var isOnline = false
    private val messages = mutableListOf<ChatMessage>()
    private val pendingMessages = mutableListOf<String>() // Queue for offline
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // No timeout for WebSocket
        .build()

    data class ChatMessage(
        val id: String = UUID.randomUUID().toString(),
        val role: String = "user", // "user" or "assistant"
        val content: String = "",
        val timestamp: Long = System.currentTimeMillis(),
        val isPending: Boolean = false
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        prefs = getSharedPreferences("hermes_prefs", Context.MODE_PRIVATE)
        gatewayUrl = prefs.getString("gateway_url", "http://192.168.1.5:18789") ?: "http://192.168.1.5:18789"
        wsUrl = gatewayUrl.replace("http://", "ws://").replace("https://", "wss://") + "/api/ws"
        
        setupUI()
        connectWebSocket()
        startConnectionMonitor()
    }

    private fun setupUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0a0a0a"))
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        // ── Status Bar ──
        val statusBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setBackgroundColor(Color.parseColor("#1a1a1a"))
        }
        
        statusDot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply { 
                setMargins(0, 0, dp(8), 0)
            }
            background = getOvalDrawable(Color.parseColor("#FF9800"))
        }
        
        statusLabel = TextView(this).apply {
            text = "Connecting..."
            textSize = 13f
            setTextColor(Color.parseColor("#aaaaaa"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        btnSettings = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_preferences)
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(Color.parseColor("#666666"))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { showSettings() }
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
        }

        syncLabel = TextView(this).apply {
            text = ""
            textSize = 11f
            setTextColor(Color.parseColor("#555555"))
        }

        statusBar.addView(statusDot)
        statusBar.addView(statusLabel)
        statusBar.addView(btnSettings)

        // ── Chat List ──
        recyclerView = RecyclerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            layoutManager = LinearLayoutManager(this@MainActivity).apply {
                stackFromEnd = true
            }
        }
        chatAdapter = ChatAdapter(messages)
        recyclerView.adapter = chatAdapter

        // ── Input Bar ──
        val inputBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(12))
            setBackgroundColor(Color.parseColor("#1a1a1a"))
        }

        inputField = EditText(this).apply {
            hint = "Type a message..."
            setHintTextColor(Color.parseColor("#555555"))
            setTextColor(Color.parseColor("#ffffff"))
            textSize = 16f
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = getRoundedDrawable(Color.parseColor("#2a2a2a"), dp(24))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, dp(8), 0)
            }
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                    sendMessage(); true
                } else false
            }
        }

        sendButton = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_send)
            setBackgroundColor(Color.parseColor("#1A73E8"))
            background = getRoundedDrawable(Color.parseColor("#1A73E8"), dp(28))
            setColorFilter(Color.WHITE)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setOnClickListener { sendMessage() }
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        }

        inputBar.addView(inputField)
        inputBar.addView(sendButton)

        // Assemble
        root.addView(statusBar)
        root.addView(recyclerView)
        root.addView(inputBar)
        
        setContentView(root)
        
        // Add welcome message
        addMessage(ChatMessage(role = "assistant", content = "Welcome to Hermes! Type a message to start chatting."))
    }

    // ── WebSocket Connection ──

    private fun connectWebSocket() {
        updateStatus("connecting", "⏳ Connecting...")
        
        val request = Request.Builder().url(wsUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                isConnected = true
                isOnline = true
                handler.post {
                    updateStatus("online", "🟢 Connected")
                    // Send pending messages
                    for (msg in pendingMessages) {
                        sendRpcMessage(msg)
                    }
                    pendingMessages.clear()
                }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handler.post { handleRpcMessage(text) }
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                ws.close(1000, null)
                isConnected = false
                handler.post { updateStatus("offline", "🔴 Disconnected") }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                isOnline = false
                handler.post { 
                    updateStatus("offline", "🔴 Connection failed")
                    // Try HTTP fallback
                    tryHttpMode()
                }
            }
        })
    }

    private fun handleRpcMessage(json: String) {
        try {
            val obj = JSONObject(json)
            
            // Handle JSON-RPC response
            if (obj.has("result")) {
                val result = obj.getJSONObject("result")
                if (result.has("content")) {
                    val content = result.getString("content")
                    addMessage(ChatMessage(role = "assistant", content = content))
                }
            }
            
            // Handle events
            if (obj.has("method")) {
                val method = obj.getString("method")
                when (method) {
                    "gateway.ready" -> updateStatus("online", "🟢 Online")
                    "agent.start" -> { /* Agent started thinking */ }
                    "agent.token" -> {
                        val token = obj.optJSONObject("params")?.optString("token", "") ?: ""
                        appendToLastMessage(token)
                    }
                    "agent.done" -> {
                        // Response complete
                    }
                }
            }
        } catch (e: Exception) {
            // Not JSON-RPC, treat as plain text
            addMessage(ChatMessage(role = "assistant", content = json))
        }
    }

    private fun sendRpcMessage(text: String) {
        // Hermes uses newline-delimited JSON-RPC over WebSocket
        val rpc = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", UUID.randomUUID().toString())
            put("method", "chat.send")
            put("params", JSONObject().apply {
                put("message", text)
                put("session_id", sessionId)
            })
        }.toString()
        
        webSocket?.send(rpc)
    }

    private fun tryHttpMode() {
        // Fallback: use HTTP API if WebSocket fails
        Thread {
            try {
                val req = Request.Builder().url("$gatewayUrl/health").build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        handler.post { updateStatus("online", "🟢 HTTP Mode") }
                        isOnline = true
                        isConnected = true
                    }
                }
            } catch (e: Exception) {
                handler.post { updateStatus("offline", "🔴 No gateway") }
            }
        }.start()
    }

    // ── Messaging ──

    private fun sendMessage() {
        val text = inputField.text.toString().trim()
        if (text.isEmpty()) return
        
        inputField.setText("")
        addMessage(ChatMessage(role = "user", content = text))
        
        if (isConnected) {
            sendRpcMessage(text)
        } else {
            // Queue for later
            pendingMessages.add(text)
            addMessage(ChatMessage(role = "assistant", content = "⏸ Queued — will send when connected"))
            // Try reconnect
            connectWebSocket()
        }
    }

    private fun addMessage(msg: ChatMessage) {
        messages.add(msg)
        handler.post {
            chatAdapter.notifyItemInserted(messages.size - 1)
            recyclerView.scrollToPosition(messages.size - 1)
        }
    }

    private fun appendToLastMessage(token: String) {
        if (messages.isNotEmpty()) {
            val last = messages.last()
            if (last.role == "assistant" && !last.isPending) {
                messages[messages.size - 1] = last.copy(content = last.content + token)
                handler.post {
                    chatAdapter.notifyItemChanged(messages.size - 1)
                    recyclerView.scrollToPosition(messages.size - 1)
                }
            }
        } else {
            addMessage(ChatMessage(role = "assistant", content = token, isPending = true))
        }
    }

    // ── Connection Monitor ──

    private fun startConnectionMonitor() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (!isConnected) {
                    connectWebSocket()
                }
                handler.postDelayed(this, 15000)
            }
        }, 15000)
    }

    // ── Status ──

    private fun updateStatus(state: String, text: String) {
        statusLabel.text = text
        val color = when (state) {
            "online" -> Color.parseColor("#4CAF50")
            "offline" -> Color.parseColor("#F44336")
            else -> Color.parseColor("#FF9800")
        }
        statusDot.background = getOvalDrawable(color)
    }

    // ── Settings ──

    private fun showSettings() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null)
        val etIp = view.findViewById<EditText>(R.id.etGatewayIp)
        val etPort = view.findViewById<EditText>(R.id.etGatewayPort)
        val swAuto = view.findViewById<Switch>(R.id.switchAutoRetry)

        val parts = gatewayUrl.replace("http://", "").replace("https://", "").split(":")
        etIp.setText(parts[0])
        etPort.setText(parts.getOrElse(1) { "18789" })
        swAuto.isChecked = prefs.getBoolean("auto_retry", true)

        AlertDialog.Builder(this)
            .setTitle("⚙️ Settings")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                gatewayUrl = "http://${etIp.text}:${etPort.text}"
                wsUrl = "ws://${etIp.text}:${etPort.text}/api/ws"
                prefs.edit()
                    .putString("gateway_url", gatewayUrl)
                    .putBoolean("auto_retry", swAuto.isChecked)
                    .apply()
                // Reconnect
                webSocket?.close(1000, "Reconnecting")
                connectWebSocket()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Helpers ──

    private fun dp(px: Int): Int = (px * resources.displayMetrics.density).toInt()

    private fun getOvalDrawable(color: Int): android.graphics.drawable.Drawable {
        val shape = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(color)
        }
        return shape
    }

    private fun getRoundedDrawable(color: Int, radius: Int): android.graphics.drawable.Drawable {
        val shape = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = radius.toFloat()
        }
        return shape
    }

    // ── Chat Adapter (Native Message Bubbles) ──

    inner class ChatAdapter(private val items: List<ChatMessage>) : 
        RecyclerView.Adapter<ChatAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val bubble: TextView = view.findViewById(R.id.bubble)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_chat, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val msg = items[position]
            holder.bubble.text = msg.content
            
            // Style based on role
            val layoutParams = holder.bubble.layoutParams as FrameLayout.LayoutParams
            if (msg.role == "user") {
                holder.bubble.background = getRoundedDrawable(Color.parseColor("#1A73E8"), dp(18))
                holder.bubble.setTextColor(Color.WHITE)
                layoutParams.gravity = android.view.Gravity.END
            } else {
                holder.bubble.background = getRoundedDrawable(Color.parseColor("#2a2a2a"), dp(18))
                holder.bubble.setTextColor(Color.parseColor("#e0e0e0"))
                layoutParams.gravity = android.view.Gravity.START
            }
            holder.bubble.layoutParams = layoutParams
            holder.bubble.maxWidth = (resources.displayMetrics.widthPixels * 0.75).toInt()
        }

        override fun getItemCount() = items.size
    }

    override fun onDestroy() {
        webSocket?.close(1000, "App closed")
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}