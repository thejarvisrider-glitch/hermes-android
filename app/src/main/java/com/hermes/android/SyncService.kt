package com.hermes.android

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Background sync service that keeps workspace in sync with PC
 * Runs continuously while PC is reachable
 */
class SyncService : Service() {

    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var executor: ScheduledExecutorService
    private val SYNC_INTERVAL = 30L  // seconds

    override fun onCreate() {
        super.onCreate()
        
        // Acquire partial wake lock to keep sync running
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Hermes::SyncService")
        wakeLock.acquire(10 * 60 * 1000L)  // 10 minutes max
        
        executor = Executors.newSingleThreadScheduledExecutor()
        
        // Create notification channel
        createNotificationChannel()
        
        // Start as foreground service
        startForeground(1, createNotification("Sync service running"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Schedule periodic sync
        executor.scheduleAtFixedRate({
            performSync()
        }, 0, SYNC_INTERVAL, TimeUnit.SECONDS)
        
        return START_STICKY  // Restart if killed
    }

    private fun performSync() {
        try {
            val prefs = getSharedPreferences("hermes_prefs", Context.MODE_PRIVATE)
            val gatewayUrl = prefs.getString("gateway_url", "") ?: return
            val pcIp = gatewayUrl.removePrefix("http://").substringBefore(":")
            
            // Check if PC is reachable
            val pcReachable = try {
                val socket = java.net.Socket()
                socket.connect(java.net.InetSocketAddress(pcIp, 18789), 3000)
                socket.close()
                true
            } catch (e: Exception) { false }
            
            if (pcReachable) {
                // Sync workspace from PC
                syncWorkspaceFromPC(pcIp)
            }
            
            // Update notification
            val status = if (pcReachable) "PC connected — syncing" else "PC offline — local mode"
            updateNotification(status)
            
        } catch (e: Exception) {
            // Sync failed, will retry
        }
    }

    private fun syncWorkspaceFromPC(pcIp: String) {
        val workspaceDir = File(filesDir, "hermes_workspace")
        if (!workspaceDir.exists()) workspaceDir.mkdirs()
        
        try {
            // Try to copy files from PC via HTTP API
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .build()
            
            val request = okhttp3.Request.Builder()
                .url("http://$pcIp:18789/api/workspace/list")
                .build()
            
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return
                    val json = org.json.JSONObject(body)
                    val files = json.getJSONArray("files")
                    
                    for (i in 0 until files.length()) {
                        val file = files.getJSONObject(i)
                        val path = file.getString("path")
                        val content = file.getString("content")
                        
                        val localFile = File(workspaceDir, path)
                        localFile.parentFile?.mkdirs()
                        localFile.writeText(content)
                    }
                    
                    // Update sync timestamp
                    File(workspaceDir, ".sync_timestamp")
                        .writeText(System.currentTimeMillis().toString())
                }
            }
        } catch (e: Exception) {
            // Fallback: use Syncthing if available
            syncViaSyncthing()
        }
    }

    private fun syncViaSyncthing() {
        try {
            val prefs = getSharedPreferences("hermes_prefs", Context.MODE_PRIVATE)
            val syncthingUrl = prefs.getString("syncthing_url", "http://127.0.0.1:8384") ?: return
            val apiKey = prefs.getString("syncthing_api_key", "") ?: return

            val client = okhttp3.OkHttpClient()
            val request = okhttp3.Request.Builder()
                .url("$syncthingUrl/rest/db/scan?folder=hermes_workspace")
                .addHeader("X-API-Key", apiKey)
                .post("".toRequestBody("application/json".toMediaType()))
                .build()
            
            client.newCall(request).execute()
        } catch (e: Exception) {
            // Syncthing not available
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "hermes_sync",
                "Hermes Sync",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps workspace in sync with PC"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        return NotificationCompat.Builder(this, "hermes_sync")
            .setContentTitle("Hermes Sync")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(1, createNotification(text))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        executor.shutdown()
        if (wakeLock.isHeld) wakeLock.release()
        super.onDestroy()
    }
}