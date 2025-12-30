package com.example.parkingautorenew

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
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
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var automationWebView: WebView

    private var isRunning = false
    private var renewalWorkTag = "parking_auto_renew"
    private var automationManager: ParkingAutomationManager? = null

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
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)

        setupAutomationWebView()
        setupSpinners()
        createNotificationChannel()
        setupButtonListeners()

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
        val frequencyOptions = arrayOf("30 min", "1 hour", "1:30 hour", "2 hour")
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

        // Agendar a renovação periódica
        schedulePeriodicRenewal(frequency)

        // Executar primeira renovação imediatamente
        executeRenewal(plate, duration)
    }

    private fun schedulePeriodicRenewal(frequency: String) {
        Log.d("AutoRenewActivity", "Scheduling periodic renewal - Frequency: $frequency")

        val timeUnit = when (frequency) {
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
            onSuccess = {
                Log.d("AutoRenewActivity", "Renewal completed successfully")
                val timestamp = SimpleDateFormat("HH:mm:ss").format(System.currentTimeMillis())
                statusText.text = "Status: Auto-Renew ativo\nÚltima renovação: $timestamp"
            },
            onError = { error ->
                Log.e("AutoRenewActivity", "Renewal error: $error")
                statusText.text = "Status: Erro na renovação\n$error"
            }
        )

        // Guardar placa na tag do WebView para a automação usar
        automationWebView.tag = plate
        
        // Iniciar automação
        automationManager?.start(plate, duration)
    }

    private fun stopAutoRenew() {
        Log.d("AutoRenewActivity", "Stopping auto-renew")

        isRunning = false
        startButton.isEnabled = true
        stopButton.isEnabled = false
        licensePlateInput.isEnabled = true
        parkingDurationSpinner.isEnabled = true
        renewalFrequencySpinner.isEnabled = true

        statusText.text = "Status: Auto-Renew parado"

        // Cancelar work agendado
        WorkManager.getInstance(this).cancelAllWorkByTag(renewalWorkTag)

        // Limpar preferências
        val prefs = getSharedPreferences("parking_prefs", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putBoolean("auto_renew_enabled", false)
            apply()
        }

        Log.d("AutoRenewActivity", "Auto-renew stopped")
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
