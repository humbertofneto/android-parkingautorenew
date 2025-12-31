package com.example.parkingautorenew

import android.app.AlarmManager
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
        private const val ALARM_REQUEST_CODE = 1234
    }
    
    private lateinit var webView: WebView
    private var automationManager: ParkingAutomationManager? = null
    private val renewalHandler = Handler(Looper.getMainLooper())
    private var isRunning = false
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate()")
        
        createNotificationChannel()
        // NÃO criar WebView aqui - criar novo para cada renovação
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
        
        Log.d(TAG, "WebView created for new renewal")
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
        Log.d(TAG, "executeRenewal() called")
        
        val prefs = getSharedPreferences("parking_prefs", Context.MODE_PRIVATE)
        
        // Verificar se houve renovação muito recente
        val lastRenewalTime = prefs.getLong("last_renewal_time", 0)
        val now = System.currentTimeMillis()
        val timeSinceLastRenewal = now - lastRenewalTime
        
        Log.d(TAG, "Time since last renewal: ${timeSinceLastRenewal/1000}s")
        
        // Se última renovação foi há menos de 60 segundos, pular
        if (timeSinceLastRenewal < 60000 && lastRenewalTime > 0) {
            Log.w(TAG, "Renewal attempted too soon (${timeSinceLastRenewal/1000}s ago), skipping")
            updateNotification("Auto-Renew ativo", "Próxima renovação em ${(60000 - timeSinceLastRenewal)/1000}s")
            return
        }
        
        val plate = prefs.getString("license_plate", "") ?: ""
        val duration = prefs.getString("parking_duration", "1 Hour") ?: "1 Hour"
        
        if (plate.isEmpty()) {
            Log.e(TAG, "License plate not configured")
            updateNotification("Erro", "Placa não configurada")
            return
        }
        
        updateNotification("Executando renovação", "Placa: $plate")
        
        // Criar um novo WebView para esta renovação
        Log.d(TAG, "Creating new WebView for this renewal")
        setupWebView()
        
        automationManager = ParkingAutomationManager(
            webView,
            onSuccess = { confirmationDetails ->
                Log.d(TAG, "========== Service: Renewal SUCCESS ==========")
                Log.d(TAG, "Confirmation: ${confirmationDetails.confirmationNumber}")
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
                Log.e(TAG, "========== Service: Renewal ERROR ==========")
                Log.e(TAG, "Error: $error")
                updateNotification("Erro na renovação", error)
            }
        )
        
        Log.d(TAG, "Starting automation with plate=$plate, duration=$duration")
        automationManager?.start(plate, duration)
        Log.d(TAG, "automationManager.start() called")
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
        
        Log.d(TAG, "Scheduling next renewal in ${delayMillis / 1000 / 60} minutes")
        
        // Usar Handler para agendar próxima renovação
        renewalHandler.postDelayed({
            if (isRunning) {
                Log.d(TAG, "Timer fired - executing renewal")
                executeRenewal()
                scheduleNextRenewal()
            } else {
                Log.d(TAG, "Timer fired but service not running, cancelling")
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
