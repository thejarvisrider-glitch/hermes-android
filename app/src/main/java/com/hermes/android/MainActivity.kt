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
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var webView: WebView
    private lateinit var statusIndicator: View
    private lateinit var statusText: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var isOnline = false
    private var gatewayUrl: String = ""
    private val localUrl = "http://127.0.0.1:18789"
    private var autoRetry = true

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("hermes_prefs", Context.MODE_PRIVATE)
        gatewayUrl = prefs.getString("gateway_url", "http://192.168.1.100:18789") ?: "http://192.168.1.100:18789"
        autoRetry = prefs.getBoolean("auto_retry", true)

        webView = findViewById(R.id.webView)
        statusIndicator = findViewById(R.id.statusIndicator)
        statusText = findViewById(R.id.statusText)

        setupWebView()
        setupStatusBarClick()
        checkConnectionAndLoad()
        startConnectionMonitor()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false
        }
        webView.webChromeClient = WebChromeClient()
    }

    private fun setupStatusBarClick() {
        statusIndicator.setOnClickListener { showSettingsDialog() }
        statusText.setOnClickListener { showSettingsDialog() }
    }

    private fun showSettingsDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null)
        val etIp = dialogView.findViewById<EditText>(R.id.etGatewayIp)
        val etPort = dialogView.findViewById<EditText>(R.id.etGatewayPort)
        val switchAutoRetry = dialogView.findViewById<Switch>(R.id.switchAutoRetry)

        val parts = gatewayUrl.removePrefix("http://").split(":")
        etIp.setText(parts[0])
        etPort.setText(parts.getOrElse(1) { "18789" })
        switchAutoRetry.isChecked = autoRetry

        AlertDialog.Builder(this)
            .setTitle("⚙️ Gateway Settings")
            .setView(dialogView)
            .setPositiveButton("Save & Connect") { _, _ ->
                val ip = etIp.text.toString().trim()
                val port = etPort.text.toString().trim()
                if (ip.isNotEmpty() && port.isNotEmpty()) {
                    gatewayUrl = "http://$ip:$port"
                    autoRetry = switchAutoRetry.isChecked
                    prefs.edit()
                        .putString("gateway_url", gatewayUrl)
                        .putBoolean("auto_retry", autoRetry)
                        .apply()
                    checkConnectionAndLoad()
                }
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Test") { _, _ ->
                val ip = etIp.text.toString().trim()
                val port = etPort.text.toString().trim()
                if (ip.isNotEmpty() && port.isNotEmpty()) {
                    testConnection("http://$ip:$port")
                }
            }
            .show()
    }

    private fun testConnection(url: String) {
        Toast.makeText(this, "Testing $url...", Toast.LENGTH_SHORT).show()
        Thread {
            val reachable = pingGateway(url)
            handler.post {
                if (reachable) {
                    Toast.makeText(this, "✅ Connection successful!", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "❌ Cannot reach gateway", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun checkConnectionAndLoad() {
        updateStatus("checking", "⏳ Checking connection...")
        Thread {
            val laptopReachable = pingGateway(gatewayUrl)
            val localReachable = pingGateway(localUrl)

            handler.post {
                when {
                    laptopReachable -> {
                        isOnline = true
                        updateStatus("online", "🟢 Online — Laptop")
                        webView.loadUrl(gatewayUrl)
                    }
                    localReachable -> {
                        isOnline = false
                        updateStatus("offline", "🟡 Offline — Local")
                        webView.loadUrl(localUrl)
                    }
                    else -> {
                        isOnline = false
                        updateStatus("error", "🔴 No gateway found")
                        showOfflineHelp()
                    }
                }
            }
        }.start()
    }

    private fun pingGateway(url: String): Boolean {
        return try {
            val request = Request.Builder().url(url).head().build()
            client.newCall(request).execute().use { it.code < 500 }
        } catch (e: Exception) { false }
    }

    private fun updateStatus(state: String, message: String) {
        statusText.text = message
        val color = when (state) {
            "online" -> Color.parseColor("#4CAF50")      // Green
            "offline" -> Color.parseColor("#FF9800")      // Orange
            "checking" -> Color.parseColor("#2196F3")     // Blue
            else -> Color.parseColor("#F44336")           // Red
        }
        statusIndicator.setBackgroundColor(color)
    }

    private fun startConnectionMonitor() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (autoRetry) {
                    if (isOnline) {
                        Thread {
                            if (!pingGateway(gatewayUrl)) {
                                handler.post { updateStatus("error", "🔴 Laptop disconnected") }
                            }
                        }.start()
                    } else {
                        Thread {
                            if (pingGateway(gatewayUrl)) {
                                handler.post {
                                    isOnline = true
                                    updateStatus("online", "🟢 Laptop back online!")
                                    webView.loadUrl(gatewayUrl)
                                }
                            }
                        }.start()
                    }
                }
                handler.postDelayed(this, 30000)
            }
        }, 30000)
    }

    private fun showOfflineHelp() {
        AlertDialog.Builder(this)
            .setTitle("🔴 No Gateway Found")
            .setMessage(
                "Cannot connect to Hermes gateway at:\n$gatewayUrl\n\n" +
                "Make sure:\n" +
                "1. Your laptop is turned on\n" +
                "2. Hermes desktop app is running\n" +
                "3. Both devices are on the same WiFi\n\n" +
                "Your laptop IP might be different. Common IPs:\n" +
                "• 192.168.1.x\n" +
                "• 192.168.0.x\n" +
                "• 10.0.0.x"
            )
            .setPositiveButton("Retry") { _, _ -> checkConnectionAndLoad() }
            .setNegativeButton("Settings") { _, _ -> showSettingsDialog() }
            .setNeutralButton("Force Local") { _, _ ->
                webView.loadUrl(localUrl)
                updateStatus("offline", "🟡 Forced local mode")
            }
            .show()
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            AlertDialog.Builder(this)
                .setTitle("Exit Hermes?")
                .setPositiveButton("Exit") { _, _ -> finish() }
                .setNegativeButton("Stay", null)
                .show()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        webView.destroy()
        super.onDestroy()
    }
}