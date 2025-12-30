package com.example.parkingautorenew

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.work.Worker
import androidx.work.WorkerParameters

class ParkingRenewalWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    override fun doWork(): Result {
        Log.d("ParkingRenewalWorker", "=== doWork() START ===")

        return try {
            // Obter dados armazenados
            val prefs = applicationContext.getSharedPreferences("parking_prefs", Context.MODE_PRIVATE)
            val isEnabled = prefs.getBoolean("auto_renew_enabled", false)

            if (!isEnabled) {
                Log.d("ParkingRenewalWorker", "Auto-renew not enabled, skipping")
                return Result.success()
            }

            val plate = prefs.getString("license_plate", "") ?: ""
            val duration = prefs.getString("parking_duration", "") ?: ""
            val frequency = prefs.getString("renewal_frequency", "") ?: ""

            Log.d("ParkingRenewalWorker", "Renewal parameters - Plate: $plate, Duration: $duration, Frequency: $frequency")

            if (plate.isEmpty() || duration.isEmpty()) {
                Log.e("ParkingRenewalWorker", "Missing required parameters")
                return Result.retry()
            }

            // Executar automação em background
            val result = executeAutomation(plate, duration)
            
            if (result) {
                Log.d("ParkingRenewalWorker", "=== doWork() COMPLETE - Success ===")
                Result.success()
            } else {
                Log.w("ParkingRenewalWorker", "Automation failed, retrying")
                Result.retry()
            }

        } catch (e: Exception) {
            Log.e("ParkingRenewalWorker", "Error during renewal: ${e.message}")
            e.printStackTrace()
            Result.retry()
        }
    }
    
    private fun executeAutomation(plate: String, duration: String): Boolean {
        Log.d("ParkingRenewalWorker", "Starting background automation")
        
        // Criar WebView para automação
        val webView = WebView(applicationContext)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        
        // Usar um semáforo para aguardar conclusão
        var success = false
        var finished = false
        val lock = Object()
        
        val automationManager = ParkingAutomationManager(
            webView,
            onSuccess = { confirmationDetails ->
                Log.d("ParkingRenewalWorker", "Background automation completed successfully")
                Log.d("ParkingRenewalWorker", "Confirmation: ${confirmationDetails.confirmationNumber}")
                success = true
                synchronized(lock) {
                    finished = true
                    lock.notifyAll()
                }
            },
            onError = { error ->
                Log.e("ParkingRenewalWorker", "Background automation error: $error")
                synchronized(lock) {
                    finished = true
                    lock.notifyAll()
                }
            }
        )
        
        // Iniciar automação
        Handler(Looper.getMainLooper()).post {
            automationManager.start(plate, duration)
        }
        
        // Aguardar conclusão (máximo 5 minutos)
        synchronized(lock) {
            val maxWaitMillis = 5 * 60 * 1000L  // 5 minutos
            val startTime = System.currentTimeMillis()
            
            while (!finished && (System.currentTimeMillis() - startTime) < maxWaitMillis) {
                try {
                    lock.wait(1000)  // Esperar em chunks de 1 segundo
                } catch (e: InterruptedException) {
                    Log.w("ParkingRenewalWorker", "Interrupted while waiting for automation")
                    Thread.currentThread().interrupt()
                    break
                }
            }
            
            if (!finished) {
                Log.w("ParkingRenewalWorker", "Automation timeout after 5 minutes")
                automationManager.stop()
            }
        }
        
        return success
    }
}
