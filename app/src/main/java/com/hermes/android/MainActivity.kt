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
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Hermes Android — Offline-First Architecture
 * 
 * Mode 1: PC ONLINE → Connect to PC gateway via WebView
 * Mode 2: PC OFFLINE → Run local Hermes via Termux, serve via local WebView
 * 
 * Sync: Syncthing-based bidirectional workspace sync
 * - While PC is online: workspace files sync continuously
 * - When PC goes offline: mobile uses last-synced workspace
 * - When PC comes back: sync changes back to PC before switching mode
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var webView: WebView
    private lateinit var statusIndicator: View
    private lateinit var statusText: TextView
    private lateinit var syncStatusText: TextView
    private val handler = Handler(Looper.getMainLooper())
    
    private var isOnline = false
    private var isSyncing = false
    private var gatewayUrl: String = ""
    private val localGatewayUrl = "http://127.0.0.1:18789"
    private val workspaceDir: File by lazy { File(filesDir, "hermes_workspace") }
    private val syncMarkerFile: File by lazy { File(workspaceDir, ".sync_timestamp") }

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("hermes_prefs", Context.MODE_PRIVATE)
        gatewayUrl = prefs.getString("gateway_url", "http://192.168.1.100:18789") ?: "http://192.168.1.100:18789"

        initViews()
        setupWebView()
        setupStatusBarClick()
        
        // Initialize workspace
        initWorkspace()
        
        // Check connection and load appropriate mode
        checkConnectionAndLoad()
        
        // Start monitoring
        startConnectionMonitor()
        startSyncMonitor()
    }

    private fun initViews() {
        webView = findViewById(R.id.webView)
        statusIndicator = findViewById(R.id.statusIndicator)
        statusText = findViewById(R.id.statusText)
        syncStatusText = findViewById(R.id.syncStatusText)
    }

    /**
     * Initialize local workspace directory structure
     * This mirrors the PC Hermes workspace
     */
    private fun initWorkspace() {
        if (!workspaceDir.exists()) {
            workspaceDir.mkdirs()
            // Create subdirectories matching PC structure
            File(workspaceDir, "skills").mkdirs()
            File(workspaceDir, "sessions").mkdirs()
            File(workspaceDir, "cron").mkdirs()
            File(workspaceDir, "cache").mkdirs()
        }
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

    /**
     * Check PC gateway availability and load appropriate mode
     */
    private fun checkConnectionAndLoad() {
        updateStatus("checking", "⏳ Checking PC connection...")
        Thread {
            val pcReachable = pingGateway(gatewayUrl)
            val localReachable = pingGateway(localGatewayUrl)

            handler.post {
                when {
                    pcReachable -> {
                        // PC is ONLINE → Use PC gateway
                        isOnline = true
                        updateStatus("online", "🟢 Online — Connected to PC")
                        webView.loadUrl(gatewayUrl)
                        // Trigger sync from PC to mobile
                        syncFromPC()
                    }
                    localReachable -> {
                        // PC offline but local Hermes is running
                        isOnline = false
                        updateStatus("offline", "🟡 Offline — Local Hermes Active")
                        webView.loadUrl(localGatewayUrl)
                    }
                    else -> {
                        // Nothing reachable — try to start local Hermes
                        isOnline = false
                        updateStatus("offline", "🟡 Offline — Starting Local Hermes")
                        startLocalHermes()
                    }
                }
            }
        }.start()
    }

    /**
     * Start local Hermes instance on Android using Termux
     * This runs the full Hermes agent locally on the phone
     */
    private fun startLocalHermes() {
        try {
            // Check if Termux is installed
            val termuxInstalled = try {
                packageManager.getPackageInfo("com.termux", 0)
                true
            } catch (e: Exception) { false }

            if (!termuxInstalled) {
                showTermuxInstallDialog()
                return
            }

            // Start Hermes in Termux via intent
            val intent = Intent().apply {
                setClassName("com.termux", "com.termux.app.RunCommandService")
                action = "com.termux.RUN_COMMAND"
                putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", "cd ~/hermes_workspace && python run_agent.py gateway start"))
                putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home/hermes_workspace")
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            }
            startService(intent)

            // Wait for local gateway to come online
            handler.postDelayed({
                if (pingGateway(localGatewayUrl)) {
                    updateStatus("offline", "🟡 Offline — Local Hermes Running")
                    webView.loadUrl(localGatewayUrl)
                } else {
                    updateStatus("error", "🔴 Local Hermes failed to start")
                    showOfflineHelp()
                }
            }, 5000)

        } catch (e: Exception) {
            updateStatus("error", "🔴 Cannot start local Hermes: ${e.message}")
            showOfflineHelp()
        }
    }

    /**
     * Sync workspace files from PC to mobile
     * Uses Syncthing or direct file copy when on same network
     */
    private fun syncFromPC() {
        if (isSyncing) return
        isSyncing = true
        updateSyncStatus("🔄 Syncing workspace from PC...")

        Thread {
            try {
                // Method 1: Try Syncthing API
                val syncthingOk = syncViaSyncthing()
                
                // Method 2: Direct file copy from PC share
                if (!syncthingOk) {
                    syncViaDirectCopy()
                }

                handler.post {
                    updateSyncStatus("✅ Workspace synced — ${getWorkspaceSize()}")
                    isSyncing = false
                    syncMarkerFile.writeText(System.currentTimeMillis().toString())
                }
            } catch (e: Exception) {
                handler.post {
                    updateSyncStatus("⚠️ Sync incomplete: ${e.message}")
                    isSyncing = false
                }
            }
        }.start()
    }

    /**
     * Sync workspace changes from mobile back to PC
     * Called when PC comes back online
     */
    private fun syncToPC() {
        if (isSyncing) return
        isSyncing = true
        updateSyncStatus("🔄 Syncing changes to PC...")

        Thread {
            try {
                // Push local changes back to PC
                val syncthingOk = syncViaSyncthing()
                
                if (!syncthingOk) {
                    syncViaDirectCopyToPC()
                }

                handler.post {
                    updateSyncStatus("✅ Changes synced to PC")
                    isSyncing = false
                }
            } catch (e: Exception) {
                handler.post {
                    updateSyncStatus("⚠️ Sync to PC incomplete: ${e.message}")
                    isSyncing = false
                }
            }
        }.start()
    }

    /**
     * Sync via Syncthing REST API
     */
    private fun syncViaSyncthing(): Boolean {
        return try {
            val syncthingUrl = prefs.getString("syncthing_url", "http://127.0.0.1:8384") ?: "http://127.0.0.1:8384"
            val apiKey = prefs.getString("syncthing_api_key", "") ?: ""

            // Trigger folder sync
            val request = Request.Builder()
                .url("$syncthingUrl/rest/db/scan?folder=hermes_workspace")
                .addHeader("X-API-Key", apiKey)
                .post("".toRequestBody("application/json".toMediaType()))
                .build()
            
            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Direct file copy from PC via SMB/SSH (fallback)
     */
    private fun syncViaDirectCopy() {
        // Copy files from PC network share or SSH
        val pcIp = gatewayUrl.removePrefix("http://").substringBefore(":")
        val sharePath = "\\\\$Ip\\hermes_workspace"
        
        try {
            val pcDir = File(sharePath)
            if (pcDir.exists()) {
                copyDirectory(pcDir, workspaceDir)
            }
        } catch (e: Exception) {
            // Fallback: use adb or other method
        }
    }

    private fun syncViaDirectCopyToPC() {
        val pcIp = gatewayUrl.removePrefix("http://").substringBefore(":")
        val sharePath = "\\\\$Ip\\hermes_workspace"
        
        try {
            val pcDir = File(sharePath)
            if (pcDir.exists()) {
                copyDirectory(workspaceDir, pcDir)
            }
        } catch (e: Exception) {
            // Fallback
        }
    }

    private fun copyDirectory(source: File, dest: File) {
        if (source.isDirectory) {
            if (!dest.exists()) dest.mkdirs()
            source.listFiles()?.forEach { file ->
                copyDirectory(File(file.name), File(dest, file.name))
            }
        } else {
            FileInputStream(source).use { input ->
                FileOutputStream(dest).use { output ->
                    input.copyTo(output)
                }
            }
        }
    }

    private fun getWorkspaceSize(): String {
        val size = workspaceDir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
        return when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> "${size / 1024} KB"
            else -> "${size / (1024 * 1024)} MB"
        }
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
            "online" -> Color.parseColor("#4CAF50")
            "offline" -> Color.parseColor("#FF9800")
            "checking" -> Color.parseColor("#2196F3")
            else -> Color.parseColor("#F44336")
        }
        statusIndicator.setBackgroundColor(color)
    }

    private fun updateSyncStatus(message: String) {
        syncStatusText.text = message
    }

    /**
     * Monitor PC connection and auto-switch modes
     */
    private fun startConnectionMonitor() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                Thread {
                    val pcReachable = pingGateway(gatewayUrl)
                    
                    handler.post {
                        if (pcReachable && !isOnline) {
                            // PC came back ONLINE
                            updateStatus("online", "🟢 PC back online — Syncing...")
                            syncToPC()  // Push mobile changes to PC first
                            handler.postDelayed({
                                isOnline = true
                                webView.loadUrl(gatewayUrl)
                                syncFromPC()  // Then pull latest from PC
                            }, 3000)
                        } else if (!pcReachable && isOnline) {
                            // PC went OFFLINE
                            isOnline = false
                            updateStatus("offline", "🟡 PC offline — Switching to local")
                            startLocalHermes()
                        }
                    }
                }.start()
                handler.postDelayed(this, 15000)  // Check every 15 seconds
            }
        }, 15000)
    }

    /**
     * Monitor sync status
     */
    private fun startSyncMonitor() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (isOnline && !isSyncing) {
                    // Periodic sync while online
                    syncFromPC()
                }
                handler.postDelayed(this, 60000)  // Sync every 60 seconds
            }
        }, 60000)
    }

    private fun showSettingsDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null)
        val etIp = dialogView.findViewById<EditText>(R.id.etGatewayIp)
        val etPort = dialogView.findViewById<EditText>(R.id.etGatewayPort)
        val etSyncthingUrl = dialogView.findViewById<EditText>(R.id.etSyncthingUrl)
        val etSyncthingKey = dialogView.findViewById<EditText>(R.id.etSyncthingApiKey)
        val switchAutoRetry = dialogView.findViewById<Switch>(R.id.switchAutoRetry)
        val btnSyncNow = dialogView.findViewById<Button>(R.id.btnSyncNow)
        val btnViewWorkspace = dialogView.findViewById<Button>(R.id.btnViewWorkspace)

        val parts = gatewayUrl.removePrefix("http://").split(":")
        etIp.setText(parts[0])
        etPort.setText(parts.getOrElse(1) { "18789" })
        etSyncthingUrl.setText(prefs.getString("syncthing_url", "http://127.0.0.1:8384"))
        etSyncthingKey.setText(prefs.getString("syncthing_api_key", ""))
        switchAutoRetry.isChecked = prefs.getBoolean("auto_retry", true)

        val dialog = AlertDialog.Builder(this)
            .setTitle("⚙️ Settings")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                gatewayUrl = "http://${etIp.text}:${etPort.text}"
                prefs.edit()
                    .putString("gateway_url", gatewayUrl)
                    .putString("syncthing_url", etSyncthingUrl.text.toString())
                    .putString("syncthing_api_key", etSyncthingKey.text.toString())
                    .putBoolean("auto_retry", switchAutoRetry.isChecked)
                    .apply()
                checkConnectionAndLoad()
            }
            .setNegativeButton("Cancel", null)
            .create()

        btnSyncNow.setOnClickListener {
            syncFromPC()
            Toast.makeText(this, "Sync started", Toast.LENGTH_SHORT).show()
        }

        btnViewWorkspace.setOnClickListener {
            showWorkspaceInfo()
        }

        dialog.show()
    }

    private fun showWorkspaceInfo() {
        val info = buildString {
            appendLine("📁 Workspace: ${workspaceDir.absolutePath}")
            appendLine("📊 Size: ${getWorkspaceSize()}")
            appendLine()
            appendLine("Files:")
            workspaceDir.walkTopDown().filter { it.isFile }.forEach { file ->
                appendLine("  • ${file.relativeTo(workspaceDir)} (${file.length() / 1024} KB)")
            }
            appendLine()
            appendLine("Last sync: ${if (syncMarkerFile.exists()) java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date(syncMarkerFile.readText().toLong())) else "Never"}")
        }
        
        AlertDialog.Builder(this)
            .setTitle("📁 Workspace Info")
            .setMessage(info)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showTermuxInstallDialog() {
        AlertDialog.Builder(this)
            .setTitle("Termux Required")
            .setMessage("To run Hermes locally on your phone, you need Termux installed.\n\nInstall Termux from F-Droid, then install Hermes inside it.")
            .setPositiveButton("Install Termux") { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/en/packages/com.termux/")))
                } catch (e: Exception) {
                    Toast.makeText(this, "Cannot open browser", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showOfflineHelp() {
        AlertDialog.Builder(this)
            .setTitle("🔴 Offline Mode")
            .setMessage(
                "PC gateway not reachable.\n\n" +
                "The app will run Hermes locally using the last synced workspace.\n\n" +
                "Make sure:\n" +
                "1. Hermes workspace has been synced at least once\n" +
                "2. Termux is installed for local Hermes\n" +
                "3. Syncthing is set up for file sync"
            )
            .setPositiveButton("Retry") { _, _ -> checkConnectionAndLoad() }
            .setNegativeButton("Settings") { _, _ -> showSettingsDialog() }
            .show()
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            AlertDialog.Builder(this)
                .setTitle("Exit Hermes?")
                .setMessage("Hermes will stop running on this device.")
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