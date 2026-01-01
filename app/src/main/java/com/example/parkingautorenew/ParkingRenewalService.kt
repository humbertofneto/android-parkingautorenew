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
        private const val SUCCESS_CHANNEL_ID = "parking_success_channel"
        private const val SUCCESS_CHANNEL_NAME = "Renovações Concluídas"
        private const val ERROR_CHANNEL_ID = "parking_error_channel"
        private const val ERROR_CHANNEL_NAME = "Erros de Renovação"
        private const val ALARM_REQUEST_CODE = 1234
    }
    
    private lateinit var webView: WebView
    private var automationManager: ParkingAutomationManager? = null
    private val renewalHandler = Handler(Looper.getMainLooper())
    private val notificationUpdateHandler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private var nextRenewalTimeMillis: Long = 0
    
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
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            // Canal para status do serviço
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notificações para auto renovação de estacionamento"
            }
            notificationManager.createNotificationChannel(channel)
            
            // Canal para notificações de sucesso
            val successChannel = NotificationChannel(
                SUCCESS_CHANNEL_ID,
                SUCCESS_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notificações quando renovação é concluída com sucesso"
                enableVibration(true)
                enableLights(true)
            }
            notificationManager.createNotificationChannel(successChannel)
            
            // Canal para notificações de erro
            val errorChannel = NotificationChannel(
                ERROR_CHANNEL_ID,
                ERROR_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notificações quando renovação falha"
                enableVibration(true)
                enableLights(true)
            }
            notificationManager.createNotificationChannel(errorChannel)
            
            Log.d(TAG, "Notification channels created")
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
        val notification = createNotification("Auto-Renew ativo", "Inicializando...")
        startForeground(NOTIFICATION_ID, notification)
        
        Log.d(TAG, "Auto-renew started in foreground")
        
        // Iniciar atualização periódica da notificação
        startNotificationUpdates()
        
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
                
                // Notificação de sucesso removida - apenas popup em caso de falha
                
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
                
                // Enviar broadcast para a Activity atualizar a UI
                val intent = Intent("RENEWAL_UPDATE").apply {
                    putExtra("status", "success")
                    putExtra("confirmation", confirmationDetails.confirmationNumber)
                    putExtra("startTime", confirmationDetails.startTime)
                    putExtra("expiryTime", confirmationDetails.expiryTime)
                    putExtra("plate", confirmationDetails.plate)
                    putExtra("location", confirmationDetails.location)
                    putExtra("confirmationNumber", confirmationDetails.confirmationNumber)
                }
                sendBroadcast(intent)
                Log.d(TAG, "Broadcast sent: RENEWAL_UPDATE with status=success")
            },
            onError = { error ->
                Log.e(TAG, "========== Service: Renewal ERROR ==========")
                Log.e(TAG, "Error: $error")
                updateNotification("Erro na renovação", error)
                
                // Enviar notificação popup de erro
                sendErrorNotification(error)
                
                // Enviar broadcast de erro para a Activity
                val intent = Intent("RENEWAL_UPDATE").apply {
                    putExtra("status", "error")
                    putExtra("confirmation", error)
                }
                sendBroadcast(intent)
                Log.d(TAG, "Broadcast sent: RENEWAL_UPDATE with status=error")
            }
        )
        
        // Enviar broadcast indicando que a renovação está começando
        val startIntent = Intent("RENEWAL_START").apply {
            putExtra("plate", plate)
            putExtra("timestamp", System.currentTimeMillis())
        }
        sendBroadcast(startIntent)
        Log.d(TAG, "Broadcast sent: RENEWAL_START")
        
        // Obter configurações de email
        val sendEmail = prefs.getBoolean("send_email", false)
        val email = prefs.getString("user_email", "") ?: ""
        
        Log.d(TAG, "Starting automation with plate=$plate, duration=$duration, sendEmail=$sendEmail")
        automationManager?.start(plate, duration, sendEmail, email)
        Log.d(TAG, "automationManager.start() called")
        
        // Agendar próxima renovação após esta
        scheduleNextRenewal()
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
        
        // Armazenar tempo da próxima renovação
        nextRenewalTimeMillis = System.currentTimeMillis() + delayMillis
        
        // Usar AlarmManager para garantir execução mesmo em background
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, ParkingRenewalService::class.java).apply {
            action = "EXECUTE_RENEWAL"
        }
        val pendingIntent = PendingIntent.getService(
            this,
            ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val triggerTime = System.currentTimeMillis() + delayMillis
        
        // Usar setExactAndAllowWhileIdle para garantir execução mesmo em Doze mode
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
                Log.d(TAG, "AlarmManager: setExactAndAllowWhileIdle scheduled")
            } else {
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
                Log.d(TAG, "AlarmManager: setExact scheduled")
            }
        } catch (e: SecurityException) {
            // Fallback: usar setAndAllowWhileIdle se SCHEDULE_EXACT_ALARM não foi concedida
            Log.w(TAG, "SCHEDULE_EXACT_ALARM permission denied, using setAndAllowWhileIdle instead: ${e.message}")
            try {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
                Log.d(TAG, "AlarmManager: setAndAllowWhileIdle scheduled (fallback)")
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to schedule alarm with both setExactAndAllowWhileIdle and setAndAllowWhileIdle: ${e2.message}", e2)
            }
        }
        
        // Também usar Handler como backup (pode não funcionar em background profundo)
        renewalHandler.postDelayed({
            if (isRunning) {
                Log.d(TAG, "Handler backup fired - executing renewal")
                executeRenewal()
            }
        }, delayMillis)
        
        Log.d(TAG, "Next renewal scheduled in ${delayMillis / 1000 / 60} minutes using AlarmManager + Handler")
    }
    
    private fun stopAutoRenew() {
        Log.d(TAG, "Stopping auto-renew")
        
        isRunning = false
        renewalHandler.removeCallbacksAndMessages(null)
        notificationUpdateHandler.removeCallbacksAndMessages(null)
        
        // Cancel any pending AlarmManager alarms
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, ParkingRenewalService::class.java).apply {
            action = "EXECUTE_RENEWAL"
        }
        val pendingIntent = PendingIntent.getService(
            this,
            0,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
            Log.d(TAG, "Canceled pending AlarmManager alarm")
        }
        
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
    
    private fun startNotificationUpdates() {
        notificationUpdateHandler.post(object : Runnable {
            override fun run() {
                if (isRunning) {
                    updateNotificationWithCountdown()
                    // Atualizar a cada 30 segundos
                    notificationUpdateHandler.postDelayed(this, 30000)
                }
            }
        })
    }
    
    private fun updateNotificationWithCountdown() {
        val prefs = getSharedPreferences("parking_prefs", Context.MODE_PRIVATE)
        val successCount = prefs.getInt("success_count", 0)
        val failureCount = prefs.getInt("failure_count", 0)
        
        val timeRemaining = nextRenewalTimeMillis - System.currentTimeMillis()
        
        val content = if (timeRemaining > 0) {
            val minutes = (timeRemaining / 1000 / 60).toInt()
            val seconds = ((timeRemaining / 1000) % 60).toInt()
            "Próxima em: ${minutes}min ${seconds}s | ✓$successCount ✗$failureCount"
        } else {
            "Executando renovação... | ✓$successCount ✗$failureCount"
        }
        
        updateNotification("Auto-Renew ativo", content)
    }
    
    private fun sendSuccessNotification(confirmationDetails: ConfirmationDetails) {
        val intent = Intent(this, AutoRenewActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            System.currentTimeMillis().toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(this, SUCCESS_CHANNEL_ID)
            .setContentTitle("✅ Renovação concluída")
            .setContentText("Confirmação #${confirmationDetails.confirmationNumber}")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(
                        "Confirmação: #${confirmationDetails.confirmationNumber}\n" +
                        "Placa: ${confirmationDetails.plate}\n" +
                        "Válido até: ${confirmationDetails.expiryTime}\n" +
                        "Local: ${confirmationDetails.location}"
                    )
            )
            .setSmallIcon(R.drawable.ic_parking)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVibrate(longArrayOf(0, 250, 250, 250))
            .build()
        
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
        
        Log.d(TAG, "Success notification sent")
    }
    
    private fun sendErrorNotification(errorMessage: String) {
        val intent = Intent(this, AutoRenewActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            System.currentTimeMillis().toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(this, ERROR_CHANNEL_ID)
            .setContentTitle("❌ Falha na renovação")
            .setContentText(errorMessage)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("Erro ao renovar o estacionamento:\n\n$errorMessage")
            )
            .setSmallIcon(R.drawable.ic_parking)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVibrate(longArrayOf(0, 500, 200, 500))
            .build()
        
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
        
        Log.d(TAG, "Error notification sent")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service onDestroy()")
        renewalHandler.removeCallbacksAndMessages(null)
        notificationUpdateHandler.removeCallbacksAndMessages(null)
    }
}
