package com.hermes.android

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private var isOnline = false
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    // Default laptop gateway URL — user can change in settings
    private val gatewayUrl: String
        get() = prefs.getString("gateway_url", "http://192.168.1.100:18789") ?: "http://192.168.1.100:18789"

    private val localUrl = "http://127.0.0.1:18789"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences("hermes_prefs", Context.MODE_PRIVATE)

        webView = WebView(this)
        setContentView(webView)

        setupWebView()
        checkConnectionAndLoad()

        // Periodic connection check every 30 seconds
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

    /**
     * Check if laptop gateway is reachable.
     * If YES → load laptop gateway (full power).
     * If NO  → load local Termux Hermes gateway (offline mode).
     */
    private fun checkConnectionAndLoad() {
        lifecycleScope.launch(Dispatchers.IO) {
            val laptopReachable = pingGateway(gatewayUrl)
            val localReachable = pingGateway(localUrl)

            withContext(Dispatchers.Main) {
                when {
                    laptopReachable -> {
                        isOnline = true
                       (webView.loadUrl(gatewayUrl)
                        showToast("🟢 Online — Connected to laptop")
                    }
                    localReachable -> {
                        isOnline = false
                        webView.loadUrl(localUrl)
                        showToast("🟡 Offline — Running local Hermes")
                    }
                    else -> {
                        isOnline = false
                        showOfflineDialog()
                    }
                }
            }
        }
    }

    private fun pingGateway(url: String): Boolean {
        return try {
            val request = Request.Builder()
                .url("$url/health")
                .head()
                .build()
            val response = client.newCall(request).execute()
            response.isSuccessful || response.code == 404  // 404 also means server is up
        } catch (e: Exception) {
            // Try root URL as fallback
            try {
                val request2 = Request.Builder().url(url).head().build()
                client.newCall(request2).execute().use { it.code < 500 }
            } catch (e2: Exception) {
                false
            }
        }
    }

    private fun startConnectionMonitor() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (isOnline) {
                    // Check if laptop is still reachable
                    lifecycleScope.launch(Dispatchers.IO) {
                        val stillOnline = pingGateway(gatewayUrl)
                        if (!stillOnline) {
                            withContext(Dispatchers.Main) {
                                showToast("🔴 Laptop disconnected — Switching to offline mode")
                                checkConnectionAndLoad()  // Will fall back to local
                            }
                        }
                    }
                } else {
                    // Check if laptop came back online
                    lifecycleScope.launch(Dispatchers.IO) {
                        val laptopBack = pingGateway(gatewayUrl)
                        if (laptopBack) {
                            withContext(Dispatchers.Main) {
                                showToast("🟢 Laptop back online — Switching to full mode")
                                isOnline = true
                                webView.loadUrl(gatewayUrl)
                            }
                        }
                    }
                }
                handler.postDelayed(this, 30000)  // Check every 30 seconds
            }
        }, 30000)
    }

    private fun showOfflineDialog() {
        AlertDialog.Builder(this)
            .setTitle("No Hermes Gateway Found")
            .setMessage(
                "Neither laptop nor local Hermes is reachable.\n\n" +
                "Options:\n" +
                "1. Check your laptop is on and Hermes is running\n" +
                "2. Connect to the same WiFi network\n" +
                "3. Set up Termux Hermes for offline mode\n\n" +
                "Gateway URL: $gatewayUrl"
            )
            .setPositiveButton("Retry") { _, _ -> checkConnectionAndLoad() }
            .setNegativeButton("Settings") { _, _ -> showSettings() }
            .setNeutralButton("Force Local") { _, _ ->
                webView.loadUrl(localUrl)
            }
            .show()
    }

    private fun showSettings() {
        val urls = arrayOf(
            gatewayUrl,
            "http://192.168.1.100:18789",
            "http://192.168.0.100:18789",
            "http://10.0.2.2:18789",
            "http://127.0.0.1:18789"
        )
        AlertDialog.Builder(this)
            .setTitle("Select Gateway URL")
            .setItems(urls) { _, which ->
                val selected = urls[which]
                prefs.edit().putString("gateway_url", selected).apply()
                checkConnectionAndLoad()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showToast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            AlertDialog.Builder(this)
                .setTitle("Exit Hermes?")
                .setMessage("Close the app? Hermes will stop responding.")
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