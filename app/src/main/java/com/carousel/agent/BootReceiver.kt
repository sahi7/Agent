package com.carousel.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val sharedPrefs = PrinterUtils.getEncryptedSharedPrefs(context)
            val deviceId = sharedPrefs.getString("device_id", "") ?: return
            val deviceToken = sharedPrefs.getString("device_token", "") ?: return
            val branchId = sharedPrefs.getString("branch_id", "") ?: return

            val serviceIntent = Intent(context, PrintService::class.java).apply {
                putExtra("BRANCH_ID", branchId)
                putExtra("DEVICE_TOKEN", deviceToken)
                putExtra("DEVICE_ID", deviceId)
            }
            context.startForegroundService(serviceIntent)
            Log.i("BootReceiver", "Started PrintService on boot")
        }
    }
}