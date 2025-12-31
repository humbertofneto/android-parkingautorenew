package com.example.parkingautorenew

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.ArrayAdapter
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import java.text.SimpleDateFormat

class AutoRenewActivity : AppCompatActivity() {
    private lateinit var licensePlateInput: EditText
    private lateinit var parkingDurationSpinner: Spinner
    private lateinit var renewalFrequencySpinner: Spinner
    private lateinit var statusText: TextView
    private lateinit var successCountText: TextView
    private lateinit var failureCountText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var automationWebView: WebView

    private var isRunning = false
    private var renewalWorkTag = "parking_auto_renew"
    private var automationManager: ParkingAutomationManager? = null
    private val countdownHandler = Handler(Looper.getMainLooper())
    private var nextRenewalTimeMillis: Long = 0
    private var lastConfirmationDetails: ConfirmationDetails? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_auto_renew)

        Log.d("AutoRenewActivity", "=== onCreate() START ===")

        // Enable back button
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        licensePlateInput = findViewById(R.id.licensePlateInput)
        parkingDurationSpinner = findViewById(R.id.parkingDurationSpinner)
        renewalFrequencySpinner = findViewById(R.id.renewalFrequencySpinner)
        statusText = findViewById(R.id.statusText)
        successCountText = findViewById(R.id.successCountText)
        failureCountText = findViewById(R.id.failureCountText)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)

        setupAutomationWebView()
        setupSpinners()
        createNotificationChannel()
        setupButtonListeners()
        loadCounters()

        Log.d("AutoRenewActivity", "=== onCreate() COMPLETE ===")
    }

    private fun setupAutomationWebView() {
        Log.d("AutoRenewActivity", "Setting up automation WebView")
        
        automationWebView = WebView(this)
        automationWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        
        automationWebView.addJavascriptInterface(AutomationBridge(), "Android")
        
        Log.d("AutoRenewActivity", "Automation WebView configured")
    }

    private fun setupSpinners() {
        Log.d("AutoRenewActivity", "Setting up spinners")

        // Spinner de Duração de Estacionamento
        val durationOptions = arrayOf("1 Hour", "2 Hour")
        val durationAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, durationOptions)
        durationAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        parkingDurationSpinner.adapter = durationAdapter

        // Spinner de Periodicidade de Renovação
        val frequencyOptions = arrayOf("5 min (test)", "30 min", "1 hour", "1:30 hour", "2 hour")
        val frequencyAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, frequencyOptions)
        frequencyAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        renewalFrequencySpinner.adapter = frequencyAdapter
    }

    private fun setupButtonListeners() {
        startButton.setOnClickListener {
            val plate = licensePlateInput.text.toString().trim()
            if (plate.isEmpty()) {
                statusText.text = "Status: Por favor, insira a placa do veículo"
                return@setOnClickListener
            }

            Log.d("AutoRenewActivity", "Start button clicked - Plate: $plate")

            val duration = parkingDurationSpinner.selectedItem.toString()
            val frequency = renewalFrequencySpinner.selectedItem.toString()

            startAutoRenew(plate, duration, frequency)
        }

        stopButton.setOnClickListener {
            Log.d("AutoRenewActivity", "Stop button clicked")
            stopAutoRenew()
        }
    }

    private fun startAutoRenew(plate: String, duration: String, frequency: String) {
        Log.d("AutoRenewActivity", "startAutoRenew - Plate: $plate, Duration: $duration, Frequency: $frequency")

        isRunning = true
        startButton.isEnabled = false
        stopButton.isEnabled = true
        licensePlateInput.isEnabled = false
        parkingDurationSpinner.isEnabled = false
        renewalFrequencySpinner.isEnabled = false

        statusText.text = "Status: Auto-Renew ativo\nPlaca: $plate\nDuração: $duration\nRenovação: a cada $frequency"

        // Guardar configurações para o background service usar
        val prefs = getSharedPreferences("parking_prefs", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("license_plate", plate)
            putString("parking_duration", duration)
            putString("renewal_frequency", frequency)
            putBoolean("auto_renew_enabled", true)
            apply()
        }

        // Iniciar Foreground Service
        val serviceIntent = Intent(this, ParkingRenewalService::class.java)
        serviceIntent.action = "START_AUTO_RENEW"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        
        Log.d("AutoRenewActivity", "Foreground service started")

        // Agendar a renovação periódica (backup com WorkManager)
        // Mas NÃO executar a primeira renovação aqui - apenas a Activity executa
        schedulePeriodicRenewal(frequency)

        // Executar primeira renovação imediatamente na Activity
        executeRenewal(plate, duration)
    }

    private fun schedulePeriodicRenewal(frequency: String) {
        Log.d("AutoRenewActivity", "Scheduling periodic renewal - Frequency: $frequency")

        val timeUnit = when (frequency) {
            "5 min (test)" -> Pair(5, TimeUnit.MINUTES)
            "30 min" -> Pair(30, TimeUnit.MINUTES)
            "1 hour" -> Pair(1, TimeUnit.HOURS)
            "1:30 hour" -> Pair(90, TimeUnit.MINUTES)
            "2 hour" -> Pair(2, TimeUnit.HOURS)
            else -> Pair(1, TimeUnit.HOURS)
        }

        val renewalRequest = PeriodicWorkRequestBuilder<ParkingRenewalWorker>(
            timeUnit.first.toLong(),
            timeUnit.second
        )
            .addTag(renewalWorkTag)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            renewalWorkTag,
            androidx.work.ExistingPeriodicWorkPolicy.REPLACE,
            renewalRequest
        )

        Log.d("AutoRenewActivity", "Renewal scheduled every ${timeUnit.first} ${timeUnit.second}")
    }

    private fun executeRenewal(plate: String, duration: String) {
        Log.d("AutoRenewActivity", "Executing renewal - Plate: $plate, Duration: $duration")

        statusText.text = "Status: Executando renovação...\nPlaca: $plate"

        // Criar automação
        automationManager = ParkingAutomationManager(
            automationWebView,
            onSuccess = { confirmationDetails ->
                Log.d("AutoRenewActivity", "Renewal completed successfully")
                lastConfirmationDetails = confirmationDetails
                incrementSuccessCount()
                updateStatusWithConfirmation(confirmationDetails)
                startCountdownTimer()
            },
            onError = { error ->
                Log.e("AutoRenewActivity", "Renewal error: $error")
                incrementFailureCount()
                statusText.text = "Status: Erro na renovação\n$error"
            }
        )

        // Guardar placa na tag do WebView para a automação usar
        automationWebView.tag = plate
        
        // Iniciar automação
        automationManager?.start(plate, duration)
    }

    private fun updateStatusWithConfirmation(details: ConfirmationDetails) {
        val timestamp = SimpleDateFormat("HH:mm:ss").format(System.currentTimeMillis())
        statusText.text = """Status: Auto-Renew ativo
            |Última renovação: $timestamp
            |
            |═══ CONFIRMAÇÃO ═══
            |Start: ${details.startTime}
            |Expiry: ${details.expiryTime}
            |Placa: ${details.plate}
            |Local: ${details.location}
            |Confirmação #: ${details.confirmationNumber}
            |
            |⏱ Próxima renovação: calculando...""".trimMargin()
    }
    
    private fun startCountdownTimer() {
        val prefs = getSharedPreferences("parking_prefs", Context.MODE_PRIVATE)
        val frequency = prefs.getString("renewal_frequency", "1 hour") ?: "1 hour"
        
        // Calcular próxima renovação
        val intervalMillis = when (frequency) {
            "5 min (test)" -> 5 * 60 * 1000L
            "30 min" -> 30 * 60 * 1000L
            "1 hour" -> 60 * 60 * 1000L
            "1:30 hour" -> 90 * 60 * 1000L
            "2 hour" -> 2 * 60 * 60 * 1000L
            else -> 60 * 60 * 1000L
        }
        
        nextRenewalTimeMillis = System.currentTimeMillis() + intervalMillis
        updateCountdown()
    }
    
    private fun updateCountdown() {
        if (!isRunning) return
        
        val remainingMillis = nextRenewalTimeMillis - System.currentTimeMillis()
        
        if (remainingMillis > 0) {
            val minutes = (remainingMillis / 1000 / 60).toInt()
            val seconds = ((remainingMillis / 1000) % 60).toInt()
            
            // Atualizar apenas a linha do countdown
            lastConfirmationDetails?.let { details ->
                val timestamp = SimpleDateFormat("HH:mm:ss").format(nextRenewalTimeMillis - (remainingMillis))
                statusText.text = """Status: Auto-Renew ativo
                    |Última renovação: $timestamp
                    |
                    |═══ CONFIRMAÇÃO ═══
                    |Start: ${details.startTime}
                    |Expiry: ${details.expiryTime}
                    |Placa: ${details.plate}
                    |Local: ${details.location}
                    |Confirmação #: ${details.confirmationNumber}
                    |
                    |⏱ Próxima renovação em: ${minutes}min ${seconds}s""".trimMargin()
            }
            
            // Agendar próxima atualização em 1 segundo
            countdownHandler.postDelayed({ updateCountdown() }, 1000)
        }
    }
    
    private fun stopAutoRenew() {
        Log.d("AutoRenewActivity", "Stopping auto-renew")
        
        // Parar countdown timer
        countdownHandler.removeCallbacksAndMessages(null)

        isRunning = false
        startButton.isEnabled = true
        stopButton.isEnabled = false
        licensePlateInput.isEnabled = true
        parkingDurationSpinner.isEnabled = true
        renewalFrequencySpinner.isEnabled = true

        statusText.text = "Status: Auto-Renew parado"
        
        // Parar Foreground Service
        val serviceIntent = Intent(this, ParkingRenewalService::class.java)
        serviceIntent.action = "STOP_AUTO_RENEW"
        startService(serviceIntent)

        // Cancelar work agendado
        WorkManager.getInstance(this).cancelAllWorkByTag(renewalWorkTag)

        // Limpar preferências
        val prefs = getSharedPreferences("parking_prefs", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putBoolean("auto_renew_enabled", false)
            apply()
        }

        Log.d("AutoRenewActivity", "Auto-renew stopped, service stopped")
    }

    private fun loadCounters() {
        val prefs = getSharedPreferences("parking_prefs", Context.MODE_PRIVATE)
        val successCount = prefs.getInt("success_count", 0)
        val failureCount = prefs.getInt("failure_count", 0)
        
        successCountText.text = successCount.toString()
        failureCountText.text = failureCount.toString()
        
        Log.d("AutoRenewActivity", "Counters loaded - Success: $successCount, Failure: $failureCount")
    }
    
    private fun incrementSuccessCount() {
        val prefs = getSharedPreferences("parking_prefs", Context.MODE_PRIVATE)
        val currentCount = prefs.getInt("success_count", 0)
        val newCount = currentCount + 1
        
        prefs.edit().putInt("success_count", newCount).apply()
        successCountText.text = newCount.toString()
        
        Log.d("AutoRenewActivity", "Success count incremented to $newCount")
    }
    
    private fun incrementFailureCount() {
        val prefs = getSharedPreferences("parking_prefs", Context.MODE_PRIVATE)
        val currentCount = prefs.getInt("failure_count", 0)
        val newCount = currentCount + 1
        
        prefs.edit().putInt("failure_count", newCount).apply()
        failureCountText.text = newCount.toString()
        
        Log.d("AutoRenewActivity", "Failure count incremented to $newCount")
    }

    private fun createNotificationChannel() {
        Log.d("AutoRenewActivity", "Creating notification channel")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = "parking_auto_renew"
            val channelName = "Parking Auto Renew"
            val importance = NotificationManager.IMPORTANCE_DEFAULT

            val channel = NotificationChannel(channelId, channelName, importance)
            channel.description = "Notificações de renovação automática de estacionamento"

            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)

            Log.d("AutoRenewActivity", "Notification channel created: $channelId")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("AutoRenewActivity", "onDestroy() called")
        countdownHandler.removeCallbacksAndMessages(null)
        automationManager?.stop()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    inner class AutomationBridge {
        @JavascriptInterface
        fun onPageReady(pageNumber: Int) {
            Log.d("AutomationBridge", "onPageReady: $pageNumber")
            automationManager?.onPageReady(pageNumber)
        }

        @JavascriptInterface
        fun onStepComplete(step: String) {
            Log.d("AutomationBridge", "onStepComplete: $step")
        }

        @JavascriptInterface
        fun onError(message: String) {
            Log.e("AutomationBridge", "Error: $message")
            runOnUiThread {
                statusText.text = "Status: Erro\n$message"
            }
        }
    }
}
