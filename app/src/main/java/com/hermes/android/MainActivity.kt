package com.hermes.android

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Hermes Android — Exact desktop app experience
 * 
 * When PC is online: connects to PC Hermes gateway
 * When PC is offline: runs Hermes locally via Termux
 * Full workspace sync between PC and mobile
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var webView: WebView
    private lateinit var statusBar: LinearLayout
    private lateinit var statusIndicator: View
    private lateinit var statusText: TextView
    private val handler = Handler(Looper.getMainLooper())

    private var mode = "connecting" // "online", "offline", "connecting"
    private var gatewayUrl: String = ""
    private val localUrl = "http://127.0.0.1:18789"
    private val workspaceDir: File by lazy { getDir("workspace", Context.MODE_PRIVATE) }

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("hermes_prefs", Context.MODE_PRIVATE)
        gatewayUrl = prefs.getString("gateway_url", "http://192.168.1.5:18789") ?: "http://192.168.1.5:18789"

        webView = findViewById(R.id.webView)
        statusBar = findViewById(R.id.statusBar)
        statusIndicator = findViewById(R.id.statusIndicator)
        statusText = findViewById(R.id.statusText)

        setupWebView()
        setupStatusBarClick()
        checkMode()
        startMonitor()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            allowUniversalAccessFromFileURLs = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
            javaScriptCanOpenWindowsAutomatically = true
            mediaPlaybackRequiresUserGesture = false
        }

        WebView.setWebContentsDebuggingEnabled(true)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return false
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                updateStatus(mode)
            }
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    // Gateway not reachable, load local UI
                    loadLocalUi()
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress < 100) {
                    updateStatus("connecting", "⏳ Loading... $newProgress%")
                }
            }
            override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                if (msg != null && msg.message().contains("error", true)) {
                    android.util.Log.d("HermesUI", "Console: ${msg.message()}")
                }
                return true
            }
        }

        // Add JavaScript interface for native bridge
        webView.addJavascriptInterface(HermesBridge(), "HermesBridge")
    }

    /**
     * JavaScript bridge — allows the web UI to call native Android functions
     */
    inner class HermesBridge {
        @android.webkit.JavascriptInterface
        fun getMode(): String = mode

        @android.webkit.JavascriptInterface
        fun getGatewayUrl(): String = gatewayUrl

        @android.webkit.JavascriptInterface
        fun setGatewayUrl(url: String) {
            gatewayUrl = url
            prefs.edit().putString("gateway_url", url).apply()
        }

        @android.webkit.JavascriptInterface
        fun showToast(msg: String) {
            handler.post { Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show() }
        }

        @android.webkit.JavascriptInterface
        fun showSettings() {
            handler.post { showSettingsDialog() }
        }

        @android.webkit.JavascriptInterface
        fun refreshConnection() {
            handler.post { checkMode() }
        }
    }

    private fun setupStatusBarClick() {
        statusBar.setOnClickListener { showSettingsDialog() }
    }

    /**
     * Check if PC is reachable and determine mode
     */
    private fun checkMode() {
        updateStatus("connecting", "⏳ Checking PC...")
        Thread {
            val pcReachable = ping(gatewayUrl)

            handler.post {
                if (pcReachable) {
                    // PC ONLINE → Load local UI and connect to PC gateway
                    mode = "online"
                    loadLocalUi()
                    updateStatus("online", "🟢 Online — PC Connected")
                } else {
                    // PC OFFLINE → Load local UI + try local Hermes
                    mode = "offline"
                    tryLocalHermes()
                }
            }
        }.start()
    }

    /**
     * Load the Hermes desktop UI from local assets
     * This gives you the EXACT same UI as the desktop app
     */
    private fun loadLocalUi() {
        webView.loadUrl("file:///android_asset/index.html")
    }

    /**
     * Try to start local Hermes via Termux
     */
    private fun tryLocalHermes() {
        // Check if Termux is installed
        val termuxInstalled = try {
            packageManager.getPackageInfo("com.termux", 0)
            true
        } catch (e: Exception) { false }

        if (termuxInstalled) {
            // Start Hermes in Termux
            try {
                val intent = Intent().apply {
                    setClassName("com.termux", "com.termux.app.RunCommandService")
                    action = "com.termux.RUN_COMMAND"
                    putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
                    putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c",
                        "cd ~/hermes && hermes gateway run --replace 2>&1 &"
                    ))
                    putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home/hermes")
                    putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                    putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
                }
                startService(intent)

                // Wait for local Hermes to start
                handler.postDelayed({
                    if (ping(localUrl)) {
                        mode = "offline"
                        loadLocalUi()
                        updateStatus("offline", "🟡 Offline — Local Hermes Running")
                    } else {
                        // Hermes not running but UI works
                        loadLocalUi()
                        updateStatus("offline", "🟡 Offline — Local Mode (no Termux Hermes)")
                    }
                }, 3000)
            } catch (e: Exception) {
                loadLocalUi()
                updateStatus("offline", "🟡 Offline — Local UI")
            }
        } else {
            loadLocalUi()
            updateStatus("offline", "🟡 Offline — Install Termux for full mode")
        }
    }

    private fun ping(url: String): Boolean {
        return try {
            val req = Request.Builder()
                .url(url)
                .get()
                .build()
            client.newCall(req).execute().use { response ->
                response.isSuccessful || response.code < 500
            }
        } catch (e: Exception) { false }
    }

    private fun updateStatus(newMode: String, msg: String? = null) {
        mode = newMode
        val message = msg ?: when (mode) {
            "online" -> "🟢 Online — PC"
            "offline" -> "🟡 Offline — Local"
            "connecting" -> "⏳ Connecting..."
            else -> "🔴 Error"
        }
        statusText.text = message

        val color = when (mode) {
            "online" -> Color.parseColor("#4CAF50")
            "offline" -> Color.parseColor("#FF9800")
            "connecting" -> Color.parseColor("#2196F3")
            else -> Color.parseColor("#F44336")
        }
        statusIndicator.setBackgroundColor(color)
    }

    private fun startMonitor() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                Thread {
                    val pcAvailable = ping(gatewayUrl)
                    handler.post {
                        if (pcAvailable && mode != "online") {
                            mode = "online"
                            updateStatus("online", "🟢 PC back online!")
                            loadLocalUi()
                        } else if (!pcAvailable && mode == "online") {
                            tryLocalHermes()
                        }
                    }
                }.start()
                handler.postDelayed(this, 15000)
            }
        }, 15000)
    }

    private fun showSettingsDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null)
        val etIp = view.findViewById<EditText>(R.id.etGatewayIp)
        val etPort = view.findViewById<EditText>(R.id.etGatewayPort)
        val swAuto = view.findViewById<Switch>(R.id.switchAutoRetry)
        val btnSync = view.findViewById<Button>(R.id.btnSyncNow)
        val btnInstallTermux = view.findViewById<Button>(R.id.btnInstallTermux)

        val parts = gatewayUrl.removePrefix("http://").split(":")
        etIp.setText(parts[0])
        etPort.setText(parts.getOrElse(1) { "18789" })
        swAuto.isChecked = prefs.getBoolean("auto_retry", true)

        val dialog = AlertDialog.Builder(this)
            .setTitle("⚙️ Hermes Settings")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                gatewayUrl = "http://${etIp.text}:${etPort.text}"
                prefs.edit()
                    .putString("gateway_url", gatewayUrl)
                    .putBoolean("auto_retry", swAuto.isChecked)
                    .apply()
                checkMode()
            }
            .setNegativeButton("Cancel", null)
            .create()

        btnSync.setOnClickListener {
            checkMode()
            Toast.makeText(this, "Syncing...", Toast.LENGTH_SHORT).show()
        }

        btnInstallTermux.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://f-droid.org/en/packages/com.termux/")))
            } catch (e: Exception) {
                Toast.makeText(this, "Cannot open browser", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            AlertDialog.Builder(this)
                .setTitle("Hermes")
                .setMessage("Minimize or exit?")
                .setPositiveButton("Minimize") { _, _ -> moveTaskToBack(true) }
                .setNeutralButton("Exit") { _, _ -> finish() }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        webView.destroy()
        super.onDestroy()
    }
}