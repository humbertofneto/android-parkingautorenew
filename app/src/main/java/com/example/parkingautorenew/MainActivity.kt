package com.example.parkingautorenew

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private lateinit var autoRenewBtn: Button
    private lateinit var debugIcon: LinearLayout
    private lateinit var versionText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("MainActivity", "=== onCreate() START ===")
        
        setContentView(R.layout.activity_main)
        Log.d("MainActivity", "Layout inflated from activity_main.xml")

        autoRenewBtn = findViewById(R.id.autoRenewBtn)
        debugIcon = findViewById(R.id.debugIcon)
        versionText = findViewById(R.id.versionText)
        
        // Definir versão dinamicamente do BuildConfig
        versionText.text = "v${BuildConfig.VERSION_NAME}"
        
        Log.d("MainActivity", "All UI elements found and bound")
        
        // ✅ Verificar se há sessão ativa já na inicialização e redirecionar
        val prefs = getSharedPreferences("parking_prefs", Context.MODE_PRIVATE)
        val isAutoRenewEnabled = prefs.getBoolean("auto_renew_enabled", false)
        if (isAutoRenewEnabled) {
            Log.d("MainActivity", "onCreate: Session already active - redirecting to AutoRenewActivity")
            Log.d("MainActivity", "isAutoRenewEnabled=$isAutoRenewEnabled, AutoRenewActivity.isActiveSessionRunning=${AutoRenewActivity.isActiveSessionRunning}")
            val autoRenewIntent = Intent(this, AutoRenewActivity::class.java)
            autoRenewIntent.flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_CLEAR_TOP
            startActivity(autoRenewIntent)
            // NÃO fazer finish() aqui - deixar MainActivity na stack como root
        }

        autoRenewBtn.setOnClickListener {
            Log.d("MainActivity", "AUTO RENEW clicked - Navigating to AutoRenewActivity")
            val intent = Intent(this, AutoRenewActivity::class.java)
            startActivity(intent)
        }

        debugIcon.setOnClickListener {
            Log.d("MainActivity", "Debug icon clicked - Navigating to DebugActivity")
            val intent = Intent(this, DebugActivity::class.java)
            startActivity(intent)
        }

        Log.d("MainActivity", "=== onCreate() COMPLETE ===")
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        Log.d("MainActivity", "onNewIntent() called - Activity already in stack")
        
        // Verificar se há uma sessão de auto-renew ativa
        val prefs = getSharedPreferences("parking_prefs", Context.MODE_PRIVATE)
        val isAutoRenewEnabled = prefs.getBoolean("auto_renew_enabled", false)
        
        if (isAutoRenewEnabled) {
            Log.d("MainActivity", "Auto-renew is active - bringing AutoRenewActivity to foreground")
            // Se houver sessão ativa, trazer AutoRenewActivity para frente (não cria nova instância)
            val autoRenewIntent = Intent(this, AutoRenewActivity::class.java)
            autoRenewIntent.flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            startActivity(autoRenewIntent)
            return
        }
        
        Log.d("MainActivity", "No active auto-renew - staying on MainActivity")
    }
}
