package com.hermes.android

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var webView: WebView
    private lateinit var statusIndicator: View
    private lateinit var statusText: TextView
    private lateinit var syncStatusText: TextView
    private val handler = Handler(Looper.getMainLooper())
    
    private var isOnline = false
    private var gatewayUrl: String = ""
    private val localUrl = "http://127.0.0.1:18789"

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("hermes_prefs", Context.MODE_PRIVATE)
        gatewayUrl = prefs.getString("gateway_url", "http://192.168.1.100:18789") ?: "http://192.168.1.100:18789"

        webView = findViewById(R.id.webView)
        statusIndicator = findViewById(R.id.statusIndicator)
        statusText = findViewById(R.id.statusText)
        syncStatusText = findViewById(R.id.syncStatusText)

        setupWebView()
        setupClicks()
        checkConnection()
        startMonitor()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean = false
        }
        webView.webChromeClient = WebChromeClient()
    }

    private fun setupClicks() {
        statusIndicator.setOnClickListener { showSettings() }
        statusText.setOnClickListener { showSettings() }
    }

    private fun checkConnection() {
        updateStatus("checking", "⏳ Checking...")
        Thread {
            val pcOk = ping(gatewayUrl)
            val localOk = ping(localUrl)
            handler.post {
                when {
                    pcOk -> { isOnline = true; updateStatus("online", "🟢 Online — PC"); webView.loadUrl(gatewayUrl) }
                    localOk -> { isOnline = false; updateStatus("offline", "🟡 Offline — Local"); webView.loadUrl(localUrl) }
                    else -> { isOnline = false; updateStatus("error", "🔴 No gateway"); showHelp() }
                }
            }
        }.start()
    }

    private fun ping(url: String): Boolean {
        return try {
            val req = Request.Builder().url(url).head().build()
            client.newCall(req).execute().use { it.code < 500 }
        } catch (e: Exception) { false }
    }

    private fun updateStatus(state: String, msg: String) {
        statusText.text = msg
        val c = when (state) {
            "online" -> Color.parseColor("#4CAF50")
            "offline" -> Color.parseColor("#FF9800")
            "checking" -> Color.parseColor("#2196F3")
            else -> Color.parseColor("#F44336")
        }
        statusIndicator.setBackgroundColor(c)
    }

    private fun startMonitor() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                Thread {
                    val pcOk = ping(gatewayUrl)
                    handler.post {
                        if (pcOk && !isOnline) {
                            isOnline = true; updateStatus("online", "🟢 PC back!"); webView.loadUrl(gatewayUrl)
                        } else if (!pcOk && isOnline) {
                            isOnline = false; updateStatus("offline", "🟡 PC offline")
                        }
                    }
                }.start()
                handler.postDelayed(this, 15000)
            }
        }, 15000)
    }

    private fun showSettings() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null)
        val etIp = view.findViewById<EditText>(R.id.etGatewayIp)
        val etPort = view.findViewById<EditText>(R.id.etGatewayPort)
        val swAuto = view.findViewById<Switch>(R.id.switchAutoRetry)

        val parts = gatewayUrl.removePrefix("http://").split(":")
        etIp.setText(parts[0])
        etPort.setText(parts.getOrElse(1) { "18789" })
        swAuto.isChecked = prefs.getBoolean("auto_retry", true)

        AlertDialog.Builder(this)
            .setTitle("⚙️ Settings")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                gatewayUrl = "http://${etIp.text}:${etPort.text}"
                prefs.edit().putString("gateway_url", gatewayUrl).putBoolean("auto_retry", swAuto.isChecked).apply()
                checkConnection()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showHelp() {
        AlertDialog.Builder(this)
            .setTitle("🔴 No Gateway")
            .setMessage("Cannot reach Hermes gateway.\n\n1. Open Hermes desktop app on PC\n2. Check same WiFi\n3. Verify IP address")
            .setPositiveButton("Retry") { _, _ -> checkConnection() }
            .setNegativeButton("Settings") { _, _ -> showSettings() }
            .show()
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        webView.destroy()
        super.onDestroy()
    }
}