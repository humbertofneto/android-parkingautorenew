package com.example.parkingautorenew

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.core.app.NotificationCompat

class ParkingRenewalService : Service() {
    
    companion object {
        private const val TAG = "ParkingRenewalService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "parking_auto_renew_channel"
        private const val CHANNEL_NAME = "Parking Auto Renew"
    }
    
    private lateinit var webView: WebView
    private var automationManager: ParkingAutomationManager? = null
    private val renewalHandler = Handler(Looper.getMainLooper())
    private var isRunning = false
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate()")
        
        createNotificationChannel()
        setupWebView()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service onStartCommand()")
        
        val action = intent?.action
        
        when (action) {
            "START_AUTO_RENEW" -> {
                startAutoRenew()
            }
            "STOP_AUTO_RENEW" -> {
                stopAutoRenew()
            }
            "EXECUTE_RENEWAL" -> {
                executeRenewal()
            }
        }
        
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notificações para auto renovação de estacionamento"
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
            
            Log.d(TAG, "Notification channel created")
        }
    }
    
    private fun setupWebView() {
        webView = WebView(applicationContext)
        
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        
        Log.d(TAG, "WebView configured for background automation")
    }
    
    private fun startAutoRenew() {
        if (isRunning) {
            Log.w(TAG, "Auto-renew already running")
            return
        }
        
        isRunning = true
        
        // Criar notificação de foreground
        val notification = createNotification("Auto-Renew ativo", "Monitorando renovações...")
        startForeground(NOTIFICATION_ID, notification)
        
        Log.d(TAG, "Auto-renew started in foreground")
        
        // Agendar próximas renovações
        // (Activity executará a primeira renovação)
        scheduleNextRenewal()
    }
    
    private fun executeRenewal() {
        Log.d(TAG, "Executing renewal...")
        
        val prefs = getSharedPreferences("parking_prefs", Context.MODE_PRIVATE)
        val plate = prefs.getString("license_plate", "") ?: ""
        val duration = prefs.getString("parking_duration", "1 Hour") ?: "1 Hour"
        
        if (plate.isEmpty()) {
            Log.e(TAG, "License plate not configured")
            updateNotification("Erro", "Placa não configurada")
            return
        }
        
        updateNotification("Executando renovação", "Placa: $plate")
        
        automationManager = ParkingAutomationManager(
            webView,
            onSuccess = { confirmationDetails ->
                Log.d(TAG, "Renewal completed successfully")
                updateNotification(
                    "Renovação concluída",
                    "Expira: ${confirmationDetails.expiryTime}"
                )
                
                // Salvar última confirmação
                prefs.edit().apply {
                    putString("last_confirmation_start", confirmationDetails.startTime)
                    putString("last_confirmation_expiry", confirmationDetails.expiryTime)
                    putString("last_confirmation_plate", confirmationDetails.plate)
                    putString("last_confirmation_location", confirmationDetails.location)
                    putString("last_confirmation_number", confirmationDetails.confirmationNumber)
                    putLong("last_renewal_time", System.currentTimeMillis())
                    apply()
                }
            },
            onError = { error ->
                Log.e(TAG, "Renewal error: $error")
                updateNotification("Erro na renovação", error)
            }
        )
        
        automationManager?.start(plate, duration)
    }
    
    private fun scheduleNextRenewal() {
        val prefs = getSharedPreferences("parking_prefs", Context.MODE_PRIVATE)
        val frequency = prefs.getString("renewal_frequency", "1 hour") ?: "1 hour"
        
        val delayMillis = when (frequency) {
            "5 min (test)" -> 5 * 60 * 1000L
            "30 min" -> 30 * 60 * 1000L
            "1 hour" -> 60 * 60 * 1000L
            "1:30 hour" -> 90 * 60 * 1000L
            "2 hour" -> 2 * 60 * 60 * 1000L
            else -> 60 * 60 * 1000L
        }
        
        renewalHandler.postDelayed({
            if (isRunning) {
                executeRenewal()
                scheduleNextRenewal()
            }
        }, delayMillis)
        
        Log.d(TAG, "Next renewal scheduled in ${delayMillis / 1000 / 60} minutes")
    }
    
    private fun stopAutoRenew() {
        Log.d(TAG, "Stopping auto-renew")
        
        isRunning = false
        renewalHandler.removeCallbacksAndMessages(null)
        automationManager?.stop()
        
        stopForeground(true)
        stopSelf()
        
        Log.d(TAG, "Auto-renew stopped, service stopped")
    }
    
    private fun createNotification(title: String, content: String): Notification {
        val intent = Intent(this, AutoRenewActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_parking)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
    
    private fun updateNotification(title: String, content: String) {
        val notification = createNotification(title, content)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service onDestroy()")
        renewalHandler.removeCallbacksAndMessages(null)
    }
}
