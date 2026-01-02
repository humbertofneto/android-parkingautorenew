package com.example.parkingautorenew

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
}
