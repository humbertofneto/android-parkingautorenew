package com.example.parkingautorenew

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.ArrayAdapter
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import java.text.SimpleDateFormat

class AutoRenewActivity : AppCompatActivity() {
    
    companion object {
        private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 1001
        private const val SCHEDULE_EXACT_ALARM_REQUEST_CODE = 1002
        
        // ✅ SINGLETON: Flag estático que persiste enquanto a app está em memória
        // Previne que múltiplas instâncias de AutoRenewActivity sejam criadas
        var isActiveSessionRunning = false
    }
    private lateinit var licensePlateInput: EditText
    private lateinit var parkingDurationSpinner: Spinner
    private lateinit var renewalFrequencySpinner: Spinner
    private lateinit var emailCheckbox: CheckBox
    private lateinit var emailInput: EditText
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
    private lateinit var countersLayout: LinearLayout
    private lateinit var versionText: TextView

    private var isRunning = false
    private var automationManager: ParkingAutomationManager? = null
    private val countdownHandler = Handler(Looper.getMainLooper())
    private var nextRenewalTimeMillis: Long = 0
    private var lastConfirmationDetails: ConfirmationDetails? = null
    private var wakeLock: PowerManager.WakeLock? = null
    
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

        // ✅ PROTEÇÃO TRIPLA contra múltiplas instâncias + RECOVERY de crash:
        // 1. Check estático (persiste em memória) - mais confiável
        // 2. Check SharedPreferences (sobrevive restart)
        // 3. RECOVERY: se prefs marcado mas estático false = app foi killed, recuperar
        if (isActiveSessionRunning) {
            Log.d("AutoRenewActivity", "Session already active (static flag), finishing this instance")
            finish()
            return
        }
        
        val prefs = getSharedPreferences("parking_prefs", Context.MODE_PRIVATE)
        val autoRenewEnabled = prefs.getBoolean("auto_renew_enabled", false)
        
        if (autoRenewEnabled) {
            // ⚠️ RECOVERY SCENARIO: auto_renew_enabled = true mas isActiveSessionRunning = false
            // Significa que a Activity foi killed mas SERVICE continua rodando
            Log.d("AutoRenewActivity", "RECOVERY: Activity was killed, restoring session from SharedPreferences")
            
            // ✅ Restaurar flag estático
            isActiveSessionRunning = true
            isRunning = true  // Marcar como rodando
            
            // NÃO parar o service - ele continua rodando!
            Log.d("AutoRenewActivity", "Service should still be running, will reconnect to it")
        }

        // Enable back button
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        licensePlateInput = findViewById(R.id.licensePlateInput)
        parkingDurationSpinner = findViewById(R.id.parkingDurationSpinner)
        renewalFrequencySpinner = findViewById(R.id.renewalFrequencySpinner)
        emailCheckbox = findViewById(R.id.emailCheckbox)
        emailInput = findViewById(R.id.emailInput)
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
        countersLayout = findViewById(R.id.countersLayout)
        versionText = findViewById(R.id.versionText)
        versionText.text = "v${BuildConfig.VERSION_NAME}"

        setupAutomationWebView()
        setupSpinners()
        setupEmailCheckbox()
        createNotificationChannel()
        setupButtonListeners()
        setupLicensePlateInput()
        
        // Inicializar contadores com valores zerados
        successCountText.text = "0"
        failureCountText.text = "0"
        
        // Esconder botão STOP na tela inicial (não há renovação rodando)
        stopButton.visibility = View.GONE
        
        // Esconder contadores na tela inicial (não há operações ainda)
        countersLayout.visibility = View.GONE
        
        // Solicitar permissões necessárias
        requestNotificationPermission()
        requestScheduleExactAlarmPermission()
        
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

        // ✅ Se foi RECOVERY, restaurar UI do estado salvo
        if (autoRenewEnabled && isRunning) {
            Log.d("AutoRenewActivity", "RECOVERY: Restoring UI state from SharedPreferences")
            restoreUIFromRecovery(prefs)
        }

        Log.d("AutoRenewActivity", "=== onCreate() COMPLETE ===")
    }
    
    private fun restoreUIFromRecovery(prefs: android.content.SharedPreferences) {
        // Restaurar UI para mostrar que sessão está ativa
        startButton.visibility = View.GONE
        stopButton.visibility = View.VISIBLE
        stopButton.isEnabled = true
        
        // Esconder inputs
        licensePlateInput.visibility = View.GONE
        parkingDurationSpinner.visibility = View.GONE
        renewalFrequencySpinner.visibility = View.GONE
        emailCheckbox.visibility = View.GONE
        emailInput.visibility = View.GONE
        
        // ✅ Esconder TODAS as labels dos inputs (incluindo licensePlateLabel!)
        licensePlateLabel.visibility = View.GONE
        parkingDurationLabel.visibility = View.GONE
        renewalFrequencyLabel.visibility = View.GONE
        
        // Mostrar contadores
        countersLayout.visibility = View.VISIBLE
        
        // Restaurar contadores salvos
        val successCount = prefs.getInt("success_count", 0)
        val failureCount = prefs.getInt("failure_count", 0)
        successCountText.text = successCount.toString()
        failureCountText.text = failureCount.toString()
        
        // ✅ Restaurar última confirmação se existir
        val lastPlate = prefs.getString("last_plate", null)
        val lastStartTime = prefs.getString("last_start_time", null)
        val lastExpiryTime = prefs.getString("last_expiry_time", null)
        val lastLocation = prefs.getString("last_location", null)
        val lastConfirmation = prefs.getString("last_confirmation_number", null)
        val nextRenewalTime = prefs.getLong("next_renewal_time", 0)
        
        // Mostrar status
        statusText.visibility = View.VISIBLE
        if (lastPlate != null && successCount > 0) {
            statusText.text = """Status: ✅ Sessão Restaurada (rodando em background)
                |
                |═══ ÚLTIMA CONFIRMAÇÃO ═══
                |Start: ${lastStartTime ?: "N/A"}
                |Expiry: ${lastExpiryTime ?: "N/A"}
                |Placa: $lastPlate
                |Local: ${lastLocation ?: "N/A"}
                |Confirmação #: ${lastConfirmation ?: "N/A"}""".trimMargin()
        } else {
            statusText.text = "Status: ⏳ Sessão Restaurada\n\nAguardando primeira renovação..."
        }
        
        // ✅ Restaurar countdown se houver próxima renovação agendada
        // IMPORTANTE: Restaurar o tempo EXATO salvo, NÃO recalcular!
        if (nextRenewalTime > 0) {
            nextRenewalTimeMillis = nextRenewalTime
            countdownText.visibility = View.VISIBLE
            // NÃO chamar startCountdownTimer() aqui pois ela RECALCULA o tempo!
            // Apenas continuar com o timer existente
            updateCountdown()
            Log.d("AutoRenewActivity", "Countdown restaurado com tempo exato: ${nextRenewalTime - System.currentTimeMillis()}ms restantes")
        }
        
        Log.d("AutoRenewActivity", "UI restored - successCount=$successCount, failureCount=$failureCount, nextRenewal=$nextRenewalTime")
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        Log.d("AutoRenewActivity", "onNewIntent() called - bringing existing session to foreground")
        Log.d("AutoRenewActivity", "isActiveSessionRunning=$isActiveSessionRunning, isRunning=$isRunning")
        // Apenas trazer para foreground, não fazer nada pois a sessão já está ativa
        setIntent(intent)  // Atualizar intent da activity
    }

    private fun requestScheduleExactAlarmPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // Android 12+
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.SCHEDULE_EXACT_ALARM
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.SCHEDULE_EXACT_ALARM),
                    SCHEDULE_EXACT_ALARM_REQUEST_CODE
                )
                Log.d("AutoRenewActivity", "Requesting SCHEDULE_EXACT_ALARM permission")
            } else {
                Log.d("AutoRenewActivity", "SCHEDULE_EXACT_ALARM permission already granted")
            }
        }
    }
    
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    SCHEDULE_EXACT_ALARM_REQUEST_CODE
                )
                Log.d("AutoRenewActivity", "Requesting notification permission")
            } else {
                Log.d("AutoRenewActivity", "Notification permission already granted")
            }
        }
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
    
    private fun setupLicensePlateInput() {
        // Converter todas as letras para maiúsculas automaticamente
        licensePlateInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            
            override fun afterTextChanged(s: android.text.Editable?) {
                s?.let {
                    val uppercase = it.toString().uppercase()
                    if (it.toString() != uppercase) {
                        licensePlateInput.removeTextChangedListener(this)
                        it.replace(0, it.length, uppercase)
                        licensePlateInput.addTextChangedListener(this)
                    }
                }
            }
        })
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

    private fun setupEmailCheckbox() {
        // Quando checkbox for marcado, mostrar campo de email
        emailCheckbox.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                emailInput.visibility = View.VISIBLE
                emailInput.isEnabled = true
            } else {
                emailInput.visibility = View.GONE
                emailInput.isEnabled = false
                emailInput.text.clear()
            }
        }
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

            // Validar email se checkbox marcado
            if (emailCheckbox.isChecked) {
                val email = emailInput.text.toString().trim()
                if (email.isEmpty()) {
                    statusText.text = "Status: Por favor, insira um email"
                    statusText.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                    statusText.text = "Status: Email inválido"
                    statusText.visibility = View.VISIBLE
                    return@setOnClickListener
                }
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

        // ✅ Set flag estático para evitar múltiplas instâncias
        isActiveSessionRunning = true
        
        // ✅ Manter tela ligada enquanto sessão está ativa
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        // ✅ Adquirir WakeLock para manter CPU ligada (fallback em case de sleep)
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (wakeLock == null) {
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "parkingautorenew:renewal_wakelock"
            )
            wakeLock?.acquire()
            Log.d("AutoRenewActivity", "WakeLock adquirido")
        }
        
        isRunning = true
        startButton.visibility = View.GONE  // Esconder durante execução
        stopButton.isEnabled = true
        stopButton.visibility = View.VISIBLE
        
        // Esconder campos de input
        licensePlateInput.visibility = View.GONE
        parkingDurationSpinner.visibility = View.GONE
        renewalFrequencySpinner.visibility = View.GONE
        emailCheckbox.visibility = View.GONE
        emailInput.visibility = View.GONE
        
        // Atualizar labels com os valores escolhidos
        licensePlateLabel.text = "Placa do Veículo: $plate"
        parkingDurationLabel.text = "Tempo de Estacionamento: $duration"
        renewalFrequencyLabel.text = "Renovar a Cada: $frequency"

        statusText.text = "Status: Auto-Renew ativo\nPlaca: $plate\nDuração: $duration\nRenovação: a cada $frequency"
        statusText.visibility = View.VISIBLE
        
        // Mostrar contadores
        countersLayout.visibility = View.VISIBLE
        
        // Zerar contadores para nova reserva
        successCountText.text = "0"
        failureCountText.text = "0"

        // Guardar configurações para o background service usar
        val prefs = getSharedPreferences("parking_prefs", Context.MODE_PRIVATE)
        
        // Salvar preferências de email
        val sendEmail = emailCheckbox.isChecked
        val email = if (sendEmail) emailInput.text.toString().trim() else ""
        
        // Zerar contadores nas preferências para nova reserva
        prefs.edit().apply {
            putInt("success_count", 0)
            putInt("failure_count", 0)
            putBoolean("send_email", sendEmail)
            putString("user_email", email)
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

        // Renovação é agendada por AlarmManager + Handler em ParkingRenewalService
        // WorkManager não é usado (redundante com AlarmManager)
        Log.d("AutoRenewActivity", "Renewal scheduled by ParkingRenewalService every ${timeUnit.first} ${timeUnit.second}")
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
        
        // Obter configurações de email (reusar prefs já declarado)
        val sendEmail = prefs.getBoolean("send_email", false)
        val email = prefs.getString("user_email", "") ?: ""
        
        // Iniciar automação com parâmetros de email
        automationManager?.start(plate, duration, sendEmail, email)
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
        
        // ✅ Salvar última confirmação em SharedPreferences para RECOVERY
        val prefs = getSharedPreferences("parking_prefs", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("last_plate", details.plate)
            putString("last_start_time", details.startTime)
            putString("last_expiry_time", details.expiryTime)
            putString("last_location", details.location)
            putString("last_confirmation_number", details.confirmationNumber)
            apply()
        }
        
        // ✅ Atualizar licensePlateLabel com a placa extraída do HTML (para validar se é a mesma)
        licensePlateLabel.visibility = View.VISIBLE
        licensePlateLabel.text = "Placa do Veículo: ${details.plate}"
        
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
        
        // ✅ Salvar nextRenewalTime para RECOVERY
        prefs.edit().putLong("next_renewal_time", nextRenewalTimeMillis).apply()
        
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
        
        // ✅ Clear flag estático quando sessão termina
        isActiveSessionRunning = false
        
        // ✅ Liberar WakeLock e remover FLAG_KEEP_SCREEN_ON
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
            Log.d("AutoRenewActivity", "WakeLock liberado")
        }
        
        // Parar countdown timer
        countdownHandler.removeCallbacksAndMessages(null)

        isRunning = false
        startButton.visibility = View.VISIBLE  // Mostrar novamente
        startButton.text = "Start Again"  // Mudar texto para indicar restart
        startButton.isEnabled = true
        stopButton.isEnabled = false
        stopButton.visibility = View.GONE
        
        // ESCONDER campos de input
        licensePlateInput.visibility = View.GONE
        parkingDurationSpinner.visibility = View.GONE
        renewalFrequencySpinner.visibility = View.GONE
        
        // ESCONDER labels dos campos de ENTRADA (mas manter licensePlateLabel visível para validação)
        // licensePlateLabel será atualizado dinamicamente com a placa extraída do HTML
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
        startButton.visibility = View.VISIBLE
        startButton.text = "Start"  // Texto original
        startButton.isEnabled = true
        
        // Mostrar campos de entrada vazios
        licensePlateInput.visibility = View.VISIBLE
        parkingDurationSpinner.visibility = View.VISIBLE
        renewalFrequencySpinner.visibility = View.VISIBLE
        emailCheckbox.visibility = View.VISIBLE
        emailCheckbox.isChecked = false
        emailInput.visibility = View.GONE
        emailInput.isEnabled = false
        emailInput.text.clear()
        
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
        
        // Esconder tempo total
        totalTimeText.visibility = View.GONE
        
        // Mostrar status inicial
        statusText.visibility = View.VISIBLE
        statusText.text = "Status: Aguardando configuração"
        
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
        countersLayout.visibility = View.GONE
        
        // Esconder botão STOP (não há renovação rodando)
        stopButton.visibility = View.GONE
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

    override fun onResume() {
        super.onResume()
        Log.d("AutoRenewActivity", "onResume() - Activity brought to foreground, isRunning=$isRunning")
        
        // ✅ Restaurar WakeLock se sessão ativa
        if (isRunning && wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "parkingautorenew:renewal_wakelock"
            )
            wakeLock?.acquire()
            Log.d("AutoRenewActivity", "WakeLock restaurado em onResume")
        }
        
        // ✅ IMPORTANTE: Recomeçar countdown handler se sessão ativa
        if (isRunning) {
            Log.d("AutoRenewActivity", "Restarting countdown handler in onResume")
            updateCountdown()  // Isso vai começar os postDelayed novamente
        }
    }

    override fun onPause() {
        super.onPause()
        Log.d("AutoRenewActivity", "onPause() - Activity going to background, isRunning=$isRunning")
        
        // ✅ IMPORTANTE: Pausar o countdown handler quando Activity sai
        // Isso evita que continue rodando e causando crashes
        countdownHandler.removeCallbacksAndMessages(null)
        Log.d("AutoRenewActivity", "Countdown handler paused in onPause")
        
        // Não liberar WakeLock aqui! Queremos que Service continue rodando
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("AutoRenewActivity", "onDestroy() called, isRunning=$isRunning, isActiveSessionRunning=$isActiveSessionRunning")
        
        // ✅ Clear flag estático quando activity é destruída
        isActiveSessionRunning = false
        
        // ✅ Liberar recursos de tela
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
            Log.d("AutoRenewActivity", "WakeLock liberado em onDestroy")
        }
        
        // Parar todas as operações de automação
        automationManager?.stop()
        
        // Remover todos os callbacks pendentes
        countdownHandler.removeCallbacksAndMessages(null)
        
        // Desregistrar BroadcastReceiver para evitar broadcasts fantasmas
        try {
            unregisterReceiver(renewalBroadcastReceiver)
            Log.d("AutoRenewActivity", "BroadcastReceiver unregistered successfully")
        } catch (e: Exception) {
            Log.e("AutoRenewActivity", "Error unregistering receiver: ${e.message}")
        }
        
        // Limpar AutomationManager completamente
        automationManager = null
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    // ✅ Gerenciar mudanças de configuração (rotação de tela)
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.d("AutoRenewActivity", "onConfigurationChanged() - orientation changed to ${if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) "LANDSCAPE" else "PORTRAIT"}")
        
        // Reinflar o layout com nova orientação
        setContentView(R.layout.activity_auto_renew)
        
        // RE-FAZER TODAS as referências às views porque o layout foi recriado
        licensePlateInput = findViewById(R.id.licensePlateInput)
        parkingDurationSpinner = findViewById(R.id.parkingDurationSpinner)
        renewalFrequencySpinner = findViewById(R.id.renewalFrequencySpinner)
        emailCheckbox = findViewById(R.id.emailCheckbox)
        emailInput = findViewById(R.id.emailInput)
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
        countersLayout = findViewById(R.id.countersLayout)
        versionText = findViewById(R.id.versionText)
        versionText.text = "v\${BuildConfig.VERSION_NAME}"
        
        // Reconfigurar WebView
        setupAutomationWebView()
        
        // Reconfigurar spinners
        setupSpinners()
        
        // Reconfigurar checkbox de email
        setupEmailCheckbox()
        
        // Re-registrar listeners dos botões
        setupButtonListeners()
        
        // Reconfigurar license plate input
        setupLicensePlateInput()
        
        // Re-registrar BroadcastReceiver
        try {
            unregisterReceiver(renewalBroadcastReceiver)
        } catch (e: Exception) {
            // Ignorar se já não estava registrado
        }
        val filter = IntentFilter().apply {
            addAction("RENEWAL_START")
            addAction("RENEWAL_UPDATE")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(renewalBroadcastReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(renewalBroadcastReceiver, filter)
        }
        
        // Restaurar UI ao estado atual (de memória, não SharedPreferences)
        restoreUIFromMemory()
        
        // Handler CONTINUA RODANDO (não foi recriado, apenas as views)
        // updateCountdown() será chamado pelo onResume() logo após
        
        Log.d("AutoRenewActivity", "onConfigurationChanged() completed - UI restored")
    }
    
    // ✅ Restaurar UI do estado em memória (não SharedPreferences)
    private fun restoreUIFromMemory() {
        Log.d("AutoRenewActivity", "Restoring UI from memory - isRunning=$isRunning")
        
        if (isRunning) {
            // UI está rodando, restaurar visual correto
            startButton.visibility = View.GONE
            stopButton.visibility = View.VISIBLE
            stopButton.isEnabled = true
            
            licensePlateInput.visibility = View.GONE
            parkingDurationSpinner.visibility = View.GONE
            renewalFrequencySpinner.visibility = View.GONE
            emailCheckbox.visibility = View.GONE
            emailInput.visibility = View.GONE
            
            licensePlateLabel.visibility = View.VISIBLE
            parkingDurationLabel.visibility = View.VISIBLE
            renewalFrequencyLabel.visibility = View.VISIBLE
            
            countersLayout.visibility = View.VISIBLE
            statusText.visibility = View.VISIBLE
            
            // Restaurar valores dos contadores (do SharedPreferences)
            val prefs = getSharedPreferences("parking_prefs", Context.MODE_PRIVATE)
            val successCount = prefs.getInt("success_count", 0)
            val failureCount = prefs.getInt("failure_count", 0)
            successCountText.text = successCount.toString()
            failureCountText.text = failureCount.toString()
            
            // Restaurar labels com valores configurados
            val plate = prefs.getString("license_plate", "")
            val duration = prefs.getString("parking_duration", "")
            val frequency = prefs.getString("renewal_frequency", "")
            
            if (!plate.isNullOrEmpty()) {
                licensePlateLabel.text = "Placa do Veículo: $plate"
            }
            if (!duration.isNullOrEmpty()) {
                parkingDurationLabel.text = "Tempo de Estacionamento: $duration"
            }
            if (!frequency.isNullOrEmpty()) {
                renewalFrequencyLabel.text = "Renovar a Cada: $frequency"
            }
            
            // Restaurar countdown se houver
            if (nextRenewalTimeMillis > 0) {
                countdownText.visibility = View.VISIBLE
            }
            
            Log.d("AutoRenewActivity", "UI restored to running state - successCount=$successCount, failureCount=$failureCount")
        } else {
            // UI está parada, mostrar estado inicial
            startButton.visibility = View.VISIBLE
            stopButton.visibility = View.GONE
            
            licensePlateInput.visibility = View.VISIBLE
            parkingDurationSpinner.visibility = View.VISIBLE
            renewalFrequencySpinner.visibility = View.VISIBLE
            emailCheckbox.visibility = View.VISIBLE
            
            licensePlateLabel.visibility = View.VISIBLE
            licensePlateLabel.text = "Placa do Veículo"
            parkingDurationLabel.visibility = View.VISIBLE
            parkingDurationLabel.text = "Tempo de Estacionamento"
            renewalFrequencyLabel.visibility = View.VISIBLE
            renewalFrequencyLabel.text = "Renovar a Cada"
            
            countersLayout.visibility = View.GONE
            countdownText.visibility = View.GONE
            statusText.visibility = View.VISIBLE
            
            Log.d("AutoRenewActivity", "UI restored to stopped state")
        }
    }
    
    // ✅ Salvar estado para casos extremos (app killed durante rotação)
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        Log.d("AutoRenewActivity", "onSaveInstanceState() - saving critical state")
        
        outState.putBoolean("isRunning", isRunning)
        outState.putBoolean("isActiveSessionRunning", isActiveSessionRunning)
        outState.putLong("nextRenewalTimeMillis", nextRenewalTimeMillis)
        
        // Salvar contadores
        val successCount = successCountText.text.toString().toIntOrNull() ?: 0
        val failureCount = failureCountText.text.toString().toIntOrNull() ?: 0
        outState.putInt("success_count", successCount)
        outState.putInt("failure_count", failureCount)
        
        Log.d("AutoRenewActivity", "State saved: isRunning=$isRunning, nextRenewal=$nextRenewalTimeMillis")
    }
    
    // ✅ Restaurar estado em casos extremos
    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        Log.d("AutoRenewActivity", "onRestoreInstanceState() - restoring from Bundle")
        
        val wasRunning = savedInstanceState.getBoolean("isRunning", false)
        isActiveSessionRunning = savedInstanceState.getBoolean("isActiveSessionRunning", false)
        nextRenewalTimeMillis = savedInstanceState.getLong("nextRenewalTimeMillis", 0)
        
        if (wasRunning) {
            isRunning = true
            
            // Restaurar contadores
            val successCount = savedInstanceState.getInt("success_count", 0)
            val failureCount = savedInstanceState.getInt("failure_count", 0)
            successCountText.text = successCount.toString()
            failureCountText.text = failureCount.toString()
            
            Log.d("AutoRenewActivity", "State restored: isRunning=$isRunning, nextRenewal=$nextRenewalTimeMillis")
            
            // UI será restaurada pelo restoreUIFromMemory() já chamado
        }
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

    // Bloquear o botão de voltar durante uma sessão ativa
    override fun onBackPressed() {
        if (isRunning) {
            // Ignorar silenciosamente o back press durante sessão ativa
            return
        }
        // Permitir voltar apenas se não há sessão ativa
        super.onBackPressed()
    }
}
