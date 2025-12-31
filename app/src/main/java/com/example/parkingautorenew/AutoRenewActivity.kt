package com.example.parkingautorenew

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
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
import android.view.View
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
    private lateinit var exitButton: Button
    private lateinit var automationWebView: WebView
    private lateinit var licensePlateLabel: TextView
    private lateinit var parkingDurationLabel: TextView
    private lateinit var renewalFrequencyLabel: TextView
    private lateinit var countdownText: TextView
    private lateinit var totalTimeText: TextView

    private var isRunning = false
    private var renewalWorkTag = "parking_auto_renew"
    private var automationManager: ParkingAutomationManager? = null
    private val countdownHandler = Handler(Looper.getMainLooper())
    private var nextRenewalTimeMillis: Long = 0
    private var lastConfirmationDetails: ConfirmationDetails? = null
    
    // BroadcastReceiver para receber notificações do Service
    private val renewalBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "RENEWAL_START") {
                val plate = intent.getStringExtra("plate") ?: "desconhecida"
                Log.d("AutoRenewActivity", "Broadcast received: RENEWAL_START")
                statusText.text = "Status: ⏳ EXECUTANDO RENOVAÇÃO...\nPlaca: $plate\n\nAguarde..."
            } else if (intent?.action == "RENEWAL_UPDATE") {
                val status = intent.getStringExtra("status") ?: "unknown"
                val confirmation = intent.getStringExtra("confirmation") ?: ""
                
                Log.d("AutoRenewActivity", "Broadcast received: status=$status, confirmation=$confirmation")
                
                if (status == "success") {
                    // Extrair dados de confirmação do intent
                    val startTime = intent.getStringExtra("startTime") ?: "N/A"
                    val expiryTime = intent.getStringExtra("expiryTime") ?: "N/A"
                    val plate = intent.getStringExtra("plate") ?: "N/A"
                    val location = intent.getStringExtra("location") ?: "N/A"
                    val confirmationNumber = intent.getStringExtra("confirmationNumber") ?: "N/A"
                    
                    val confirmationDetails = ConfirmationDetails(
                        startTime = startTime,
                        expiryTime = expiryTime,
                        plate = plate,
                        location = location,
                        confirmationNumber = confirmationNumber
                    )
                    
                    lastConfirmationDetails = confirmationDetails
                    incrementSuccessCount()
                    
                    // Mostrar mensagem de sucesso temporariamente
                    statusText.text = "Status: ✅ RENOVAÇÃO CONCLUÍDA COM SUCESSO!\n\nAtualizando informações..."
                    
                    // Após 1.5 segundos, mostrar detalhes completos
                    Handler(Looper.getMainLooper()).postDelayed({
                        updateStatusWithConfirmation(confirmationDetails)
                        startCountdownTimer()
                    }, 1500)
                } else if (status == "error") {
                    incrementFailureCount()
                    statusText.text = "Status: Erro na renovação\n$confirmation"
                }
            }
        }
    }

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
        totalTimeText = findViewById(R.id.totalTimeText)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        exitButton = findViewById(R.id.exitButton)
        licensePlateLabel = findViewById(R.id.licensePlateLabel)
        parkingDurationLabel = findViewById(R.id.parkingDurationLabel)
        renewalFrequencyLabel = findViewById(R.id.renewalFrequencyLabel)
        countdownText = findViewById(R.id.countdownText)

        setupAutomationWebView()
        setupSpinners()
        createNotificationChannel()
        setupButtonListeners()
        loadCounters()
        
        // Registrar BroadcastReceiver para atualizações do Service
        val filter = IntentFilter().apply {
            addAction("RENEWAL_START")
            addAction("RENEWAL_UPDATE")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(renewalBroadcastReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(renewalBroadcastReceiver, filter)
        }
        
        Log.d("AutoRenewActivity", "BroadcastReceiver registered")

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
            // Se o botão for "Start Again", resetar para tela inicial
            if (startButton.text.toString().contains("Again")) {
                resetToInitialState()
                return@setOnClickListener
            }
            
            val plate = licensePlateInput.text.toString().trim()
            if (plate.isEmpty()) {
                statusText.text = "Status: Por favor, insira a placa do veículo"
                statusText.visibility = View.VISIBLE
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

        exitButton.setOnClickListener {
            Log.d("AutoRenewActivity", "Exit button clicked")
            
            // Parar serviço se estiver rodando
            if (isRunning) {
                val serviceIntent = Intent(this, ParkingRenewalService::class.java)
                serviceIntent.action = "STOP_AUTO_RENEW"
                startService(serviceIntent)
                
                WorkManager.getInstance(this).cancelAllWorkByTag(renewalWorkTag)
            }
            
            // Zerar todos os dados e contadores
            val prefs = getSharedPreferences("parking_prefs", Context.MODE_PRIVATE)
            prefs.edit().clear().apply()
            
            // Fechar aplicação completamente (remove da task stack)
            finishAffinity()
            // Alternativa mais agressiva se necessário:
            // finishAndRemoveTask()
            // System.exit(0)
        }
    }

    private fun startAutoRenew(plate: String, duration: String, frequency: String) {
        Log.d("AutoRenewActivity", "startAutoRenew - Plate: $plate, Duration: $duration, Frequency: $frequency")

        isRunning = true
        startButton.isEnabled = false
        startButton.text = "Start\nAuto-Renew"
        stopButton.isEnabled = true
        stopButton.visibility = View.VISIBLE
        
        // Esconder campos de input
        licensePlateInput.visibility = View.GONE
        parkingDurationSpinner.visibility = View.GONE
        renewalFrequencySpinner.visibility = View.GONE
        
        // Atualizar labels com os valores escolhidos
        licensePlateLabel.text = "Placa do Veículo: $plate"
        parkingDurationLabel.text = "Tempo de Estacionamento: $duration"
        renewalFrequencyLabel.text = "Renovar a Cada: $frequency"

        statusText.text = "Status: Auto-Renew ativo\nPlaca: $plate\nDuração: $duration\nRenovação: a cada $frequency"
        statusText.visibility = View.VISIBLE
        
        // Mostrar contadores
        successCountText.visibility = View.VISIBLE
        failureCountText.visibility = View.VISIBLE
        
        // Zerar contadores para nova reserva
        successCountText.text = "0"
        failureCountText.text = "0"

        // Guardar configurações para o background service usar
        val prefs = getSharedPreferences("parking_prefs", Context.MODE_PRIVATE)
        
        // Zerar contadores nas preferências para nova reserva
        prefs.edit().apply {
            putInt("success_count", 0)
            putInt("failure_count", 0)
            apply()
        }
        
        // Salvar timestamp da primeira renovação se não existir
        if (!prefs.contains("first_renewal_time")) {
            prefs.edit().putLong("first_renewal_time", System.currentTimeMillis()).apply()
        }
        
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
        
        // Verificar se houve renovação muito recente (evitar duplicatas)
        val prefs = getSharedPreferences("parking_prefs", Context.MODE_PRIVATE)
        val lastRenewalTime = prefs.getLong("last_renewal_time", 0)
        val now = System.currentTimeMillis()
        val timeSinceLastRenewal = now - lastRenewalTime
        
        // Se a última renovação foi há menos de 30 segundos, pular
        if (timeSinceLastRenewal < 30000) {
            Log.w("AutoRenewActivity", "Renewal attempted too soon (${timeSinceLastRenewal/1000}s ago), skipping")
            return
        }

        statusText.text = "Status: Executando renovação...\nPlaca: $plate"

        // Criar automação
        automationManager = ParkingAutomationManager(
            automationWebView,
            onSuccess = { confirmationDetails ->
                Log.d("AutoRenewActivity", "Renewal completed successfully")
                lastConfirmationDetails = confirmationDetails
                
                // Salvar timestamp da renovação
                val prefs = getSharedPreferences("parking_prefs", Context.MODE_PRIVATE)
                prefs.edit().putLong("last_renewal_time", System.currentTimeMillis()).apply()
                
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
            |Confirmação #: ${details.confirmationNumber}""".trimMargin()
        
        // Mostrar o countdown separado
        countdownText.visibility = View.VISIBLE
        countdownText.text = "⏱ Próxima renovação em: calculando..."
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
        
        val now = System.currentTimeMillis()
        val prefs = getSharedPreferences("parking_prefs", Context.MODE_PRIVATE)
        val lastRenewalTime = prefs.getLong("last_renewal_time", 0)
        
        // Calcular tempo decorrido desde a última renovação
        val elapsedMillis = if (lastRenewalTime > 0) now - lastRenewalTime else 0L
        val elapsedMinutes = (elapsedMillis / 1000 / 60).toInt()
        val elapsedSeconds = ((elapsedMillis / 1000) % 60).toInt()
        
        val elapsedText = when {
            elapsedMinutes > 0 -> "há ${elapsedMinutes}min ${elapsedSeconds}s"
            else -> "há ${elapsedSeconds}s"
        }
        
        val remainingMillis = nextRenewalTimeMillis - now
        
        if (remainingMillis > 0) {
            val minutes = (remainingMillis / 1000 / 60).toInt()
            val seconds = ((remainingMillis / 1000) % 60).toInt()
            
            // Atualizar status text (sem countdown)
            lastConfirmationDetails?.let { details ->
                statusText.text = """Status: Auto-Renew ativo
                    |Última renovação: $elapsedText
                    |
                    |═══ CONFIRMAÇÃO ═══
                    |Start: ${details.startTime}
                    |Expiry: ${details.expiryTime}
                    |Placa: ${details.plate}
                    |Local: ${details.location}
                    |Confirmação #: ${details.confirmationNumber}""".trimMargin()
            }
            
            // Atualizar countdown separado
            countdownText.visibility = View.VISIBLE
            countdownText.text = "⏱ Próxima renovação em: ${minutes} min ${seconds} seg"
            
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
        startButton.text = "Start\nAgain"
        stopButton.isEnabled = false
        stopButton.visibility = View.GONE
        
        // ESCONDER campos de input
        licensePlateInput.visibility = View.GONE
        parkingDurationSpinner.visibility = View.GONE
        renewalFrequencySpinner.visibility = View.GONE
        
        // ESCONDER labels dos campos
        licensePlateLabel.visibility = View.GONE
        parkingDurationLabel.visibility = View.GONE
        renewalFrequencyLabel.visibility = View.GONE
        
        // Esconder countdown
        countdownText.visibility = View.GONE
        
        // Esconder resumo (statusText)
        statusText.visibility = View.GONE

        // Parar Foreground Service
        val serviceIntent = Intent(this, ParkingRenewalService::class.java)
        serviceIntent.action = "STOP_AUTO_RENEW"
        startService(serviceIntent)

        // Cancelar work agendado
        WorkManager.getInstance(this).cancelAllWorkByTag(renewalWorkTag)

        // Obter preferências e calcular tempo total
        val prefs = getSharedPreferences("parking_prefs", Context.MODE_PRIVATE)
        val firstRenewalTime = prefs.getLong("first_renewal_time", 0)
        val lastRenewalTime = prefs.getLong("last_renewal_time", 0)
        
        // Calcular e mostrar tempo total
        if (firstRenewalTime > 0 && lastRenewalTime > 0) {
            val totalMillis = lastRenewalTime - firstRenewalTime
            val hours = (totalMillis / 1000 / 60 / 60).toInt()
            val minutes = ((totalMillis / 1000 / 60) % 60).toInt()
            
            val totalTimeValue = when {
                hours > 0 -> "${hours}h ${minutes}min"
                minutes > 0 -> "${minutes}min"
                else -> "menos de 1 minuto"
            }
            
            // Mostrar tempo total em TextView separado com label
            totalTimeText.visibility = View.VISIBLE
            totalTimeText.text = "⏱ Tempo total estacionado: $totalTimeValue"
        }

        // Manter contadores visíveis - NÃO zerar aqui
        // Os contadores serão zerados apenas ao pressionar START para nova reserva
        
        // Limpar apenas flags e timestamps, mantendo os contadores
        prefs.edit().apply {
            putBoolean("auto_renew_enabled", false)
            remove("first_renewal_time")
            remove("last_renewal_time")
            apply()
        }

        Log.d("AutoRenewActivity", "Auto-renew stopped, counters reset")
    }
    
    private fun resetToInitialState() {
        Log.d("AutoRenewActivity", "Resetting to initial state")
        
        // Voltar botão para estado original
        startButton.text = "Start\nAuto-Renew"
        startButton.isEnabled = true
        
        // Mostrar campos de entrada vazios
        licensePlateInput.visibility = View.VISIBLE
        parkingDurationSpinner.visibility = View.VISIBLE
        renewalFrequencySpinner.visibility = View.VISIBLE
        
        // Mostrar labels
        licensePlateLabel.visibility = View.VISIBLE
        parkingDurationLabel.visibility = View.VISIBLE
        renewalFrequencyLabel.visibility = View.VISIBLE
        
        // Resetar labels para texto original
        licensePlateLabel.text = "Placa do Veículo"
        parkingDurationLabel.text = "Tempo de Estacionamento"
        renewalFrequencyLabel.text = "Renovar a Cada"
        
        // Limpar campo de placa
        licensePlateInput.text.clear()
        
        // Esconder tempo total e status
        totalTimeText.visibility = View.GONE
        statusText.visibility = View.GONE
        
        // Zerar e esconder contadores
        val prefs = getSharedPreferences("parking_prefs", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putInt("success_count", 0)
            putInt("failure_count", 0)
            remove("first_renewal_time")
            remove("last_renewal_time")
            apply()
        }
        
        successCountText.text = "0"
        failureCountText.text = "0"
        successCountText.visibility = View.GONE
        failureCountText.visibility = View.GONE
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
        
        // Desregistrar BroadcastReceiver
        try {
            unregisterReceiver(renewalBroadcastReceiver)
            Log.d("AutoRenewActivity", "BroadcastReceiver unregistered")
        } catch (e: Exception) {
            Log.e("AutoRenewActivity", "Error unregistering receiver: ${e.message}")
        }
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
