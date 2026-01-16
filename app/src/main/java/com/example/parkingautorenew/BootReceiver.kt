package com.example.parkingautorenew

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "BootReceiver"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "BootReceiver triggered: ${intent.action}")
        
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                Log.d(TAG, "Device booted, checking for active auto-renew session")
                restoreAutoRenewIfNeeded(context)
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.d(TAG, "App updated, checking for active auto-renew session")
                restoreAutoRenewIfNeeded(context)
            }
        }
    }
    
    private fun restoreAutoRenewIfNeeded(context: Context) {
        val prefs = context.getSharedPreferences("parking_prefs", Context.MODE_PRIVATE)
        val autoRenewEnabled = prefs.getBoolean("auto_renew_enabled", false)
        
        if (autoRenewEnabled) {
            Log.d(TAG, "Auto-renew was active, restoring Service and alarms")
            
            val serviceIntent = Intent(context, ParkingRenewalService::class.java).apply {
                action = "START_AUTO_RENEW"
            }
            
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
                Log.d(TAG, "ParkingRenewalService restarted successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restart service: ${e.message}", e)
            }
        } else {
            Log.d(TAG, "No active auto-renew session found")
        }
    }
}
