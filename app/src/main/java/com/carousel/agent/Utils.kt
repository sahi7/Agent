package com.carousel.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.SharedPreferences
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import org.json.JSONArray
import org.json.JSONObject
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

object PrinterUtils {
    private val _scanResultFlow = MutableSharedFlow<Int>(replay = 1)
    val scanResultFlow: SharedFlow<Int> = _scanResultFlow
    private const val CHANNEL_ID = "PrintServiceChannel"
    private val notificationIdCounter =
        AtomicInteger(2) // Start from 2 to avoid FOREGROUND_NOTIFICATION_ID=1
    private var lastNotification: Notification? = null

    fun savePrinters(context: Context, newPrinters: List<PrinterConfig>) {
        val sharedPrefs = getEncryptedSharedPrefs(context)
        val jsonArray = JSONArray()
        newPrinters.forEach { config ->
            jsonArray.put(JSONObject().apply {
                put("printer_id", config.printerId)
                put("connectionType", config.connectionType)
                putOpt("vendorId", config.vendorId)
                putOpt("productId", config.productId)
                putOpt("ipAddress", config.ipAddress)
                putOpt("serialPort", config.serialPort)
                put("profile", config.profile)
                put("isDefault", config.isDefault)
            })
        }
        sharedPrefs.edit { putString("printers", jsonArray.toString()) }
        Log.d("PrinterUtils", "Saved printers: $jsonArray")
        _scanResultFlow.tryEmit(newPrinters.size) // Emit scan result count
    }

    fun getEncryptedSharedPrefs(context: Context): SharedPreferences =
        EncryptedSharedPreferences.create(
            "app_prefs",
            MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

    fun getEncryptedAuthPrefs(context: Context): SharedPreferences =
        EncryptedSharedPreferences.create(
            "auth_prefs",
            MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

    fun handleSetDefaultCommand(context: Context, printerId: String, senderId: String) {
        try {
            val sharedPrefs = getEncryptedSharedPrefs(context)
            val storedPrintersJson = sharedPrefs.getString("printers", "[]") ?: "[]"
            val jsonArray = JSONArray(storedPrintersJson)
            val printers = mutableListOf<PrinterConfig>()

            // Check if printer exists and update default status
            var printerFound = false
            for (i in 0 until jsonArray.length()) {
                val json = jsonArray.getJSONObject(i)
                val currentPrinterId = json.getString("printer_id")
                val isDefault = currentPrinterId == printerId
                if (isDefault) printerFound = true
                printers.add(json.toPrinterConfig().copy(isDefault = isDefault))
            }

            if (!printerFound) {
                // Send error response - printer not found
                PrintService.webSocket?.send(JSONObject().apply {
                    put("type", "ack")
                    put("status", "error")
                    put("sender", senderId)
                    put("command", "default")
                    put("printer_id", printerId)
                    put("error", "Printer not found")
                }.toString())
                return
            }

            // Save updated printers
            savePrinters(context, printers)

            // Send success response
            PrintService.webSocket?.send(JSONObject().apply {
                put("type", "ack")
                put("status", "success")
                put("sender", senderId)
                put("command", "default")
                put("printer_id", printerId)
            }.toString())

        } catch (e: Exception) {
            // Send error response
            PrintService.webSocket?.send(JSONObject().apply {
                put("type", "ack")
                put("status", "error")
                put("sender", senderId)
                put("command", "default")
                put("printer_id", printerId)
                put("error", e.message ?: "Unknown error")
            }.toString())
        }
    }

    fun handleRemoveCommand(context: Context, printerId: String, senderId: String) {
        try {
            val sharedPrefs = getEncryptedSharedPrefs(context)
            val printers = mutableListOf<PrinterConfig>()
            val storedPrintersJson = sharedPrefs.getString("printers", "[]") ?: "[]"
            val jsonArray = JSONArray(storedPrintersJson)

            // Check if printer exists
            var printerFound = false
            for (i in 0 until jsonArray.length()) {
                val json = jsonArray.getJSONObject(i)
                if (json.getString("printer_id") != printerId) {
                    printers.add(json.toPrinterConfig())
                } else {
                    printerFound = true
                }
            }

            if (!printerFound) {
                // Send error response - printer not found
                PrintService.webSocket?.send(JSONObject().apply {
                    put("type", "ack")
                    put("status", "error")
                    put("sender", senderId)
                    put("command", "remove")
                    put("printer_id", printerId)
                    put("error", "Printer not found")
                }.toString())
                return
            }

            // If we removed the default printer and there are still printers, make first one default
            if (printers.isNotEmpty() && printers.none { it.isDefault }) {
                printers[0] = printers[0].copy(isDefault = true)
            }

            savePrinters(context, printers)

            // Send success response
            PrintService.webSocket?.send(JSONObject().apply {
                put("type", "ack")
                put("status", "success")
                put("sender", senderId)
                put("command", "remove")
                put("printer_id", printerId)
            }.toString())

            Log.i("WebSocket", "Removed printer $printerId")

        } catch (e: Exception) {
            // Send error response
            PrintService.webSocket?.send(JSONObject().apply {
                put("type", "ack")
                put("status", "error")
                put("sender", senderId)
                put("command", "remove")
                put("printer_id", printerId)
                put("error", e.message ?: "Unknown error")
            }.toString())
        }
    }

    fun handleResetCommand(context: Context, senderId: String?) {
        clearAllSharedPrefs(context)
        PrintService.webSocket?.send(JSONObject().apply {
            put("type", "ack")
            put("status", "success")
            put("sender", senderId)
            put("command", "reset")
        }.toString())
        restartPrintService(context)
    }

    private fun clearAllSharedPrefs(context: Context) {
        val sharedPrefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
        val files = sharedPrefsDir.listFiles()

        files?.forEach { file ->
            Log.d("SharedPrefs", "Found prefs file: ${file.name}")
            val prefsName = file.name.removeSuffix(".xml")
            val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            prefs.edit { clear() }
            Log.d("SharedPrefs", "Cleared $prefsName")
        }
    }

    private fun restartPrintService(context: Context) {
        val serviceIntent = Intent(context, PrintService::class.java)

        // Stop service first
        context.stopService(serviceIntent)

        // Optionally delay a tiny bit to ensure stop completes
        Handler(Looper.getMainLooper()).postDelayed({
            context.startService(serviceIntent)
        }, 200) // 200ms delay
        sendNotification(
            context = context,
            title = "Service Reboot",
            text = "Print Service has been rebooted, restart the app to load defaults"
        )
    }

    fun handleListCommand(context: Context, senderId: String) {
        val sharedPrefs = getEncryptedSharedPrefs(context)
//        val printers = mutableListOf<PrinterConfig>()
        val jsonArray = JSONArray(sharedPrefs.getString("printers", "[]"))
        val printerData = JSONArray()
        for (i in 0 until jsonArray.length()) {
            val config = jsonArray.getJSONObject(i).toPrinterConfig()
//            printers.add(config)
            printerData.put(JSONObject().apply {
                put("printer_id", config.printerId)
                put("connection_type", config.connectionType)
                putOpt("vendor_id", config.vendorId)
                putOpt("product_id", config.productId)
                putOpt("ip_address", config.ipAddress)
                putOpt("serial_port", config.serialPort)
                put("profile", config.profile)
                put("is_default", config.isDefault)
            })
        }
        PrintService.webSocket?.send(JSONObject().apply {
            put("type", "ack")
            put("printers", printerData)
            put("sender", senderId)
            put("command", "list")
        }.toString())
        Log.i("WebSocket", "Sent printer list")
    }

    suspend fun handleTestCommand(context: Context, printerId: String, senderId: String) {
        try {
            val config = getPrinterConfig(context, printerId)
            val printer = ReceiptPrinter(context, config)
            printer.connect()
            val success = printer.testConnection()
            PrintService.webSocket?.send(JSONObject().apply {
                put("type", "ack")
                put("status", if (success) "success" else "failed")
                put("sender", senderId)
                put("command", "test")
                put("printer_id", printerId)
                if (!success) put("error", "Test print failed")
            }.toString())
            Log.i("WebSocket", "Tested printer $printerId: ${if (success) "success" else "failed"}")
        } catch (e: Exception) {
            PrintService.webSocket?.send(JSONObject().apply {
                put("type", "ack")
                put("status", "failed")
                put("sender", senderId)
                put("command", "test")
                put("printer_id", printerId)
                put("error", e.message)
            }.toString())
            Log.e("WebSocket", "Test failed for $printerId: ${e.message}")
        }
    }

    private fun getPrinterConfig(context: Context, printerId: String): PrinterConfig {
        val sharedPrefs = getEncryptedSharedPrefs(context)
        val storedPrintersJson = sharedPrefs.getString("printers", "[]")
        val jsonArray = JSONArray(storedPrintersJson)
        for (i in 0 until jsonArray.length()) {
            val json = jsonArray.getJSONObject(i)
            if (json.getString("printer_id") == printerId) {
                return json.toPrinterConfig()
            }
        }
        throw Exception("Printer $printerId not found")
    }
//
//    private suspend fun handleHeartbeatCommand() {
//        webSocket?.send(JSONObject().apply {
//            put("type", "heartbeat_response")
//        }.toString())
//        Log.i("WebSocket", "Sent heartbeat response")
//    }

    private fun JSONObject.toPrinterConfig(): PrinterConfig {
        return PrinterConfig(
            printerId = getString("printer_id"),
            connectionType = getString("connection_type"),
            vendorId = optString("vendor_id", null),
            productId = optString("product_id", null),
            ipAddress = optString("ip_address", null),
            serialPort = optString("serial_port", null),
            profile = optString("profile", "TM-T88III"),
            isDefault = optBoolean("is_default", false),
            fingerprint = optString("fingerprint", null)
        )
    }

    fun sendNotification(
        context: Context,
        title: String,
        text: String,
        notificationId: Int = notificationIdCounter.getAndIncrement(),
        isPersistent: Boolean = false
    ) {
        // Create notification channel if not exists
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Print Service",
            NotificationManager.IMPORTANCE_DEFAULT // DEFAULT for visibility
        ).apply {
            description = "Notifications for print service and app events"
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager == null) {
            Log.e("Notification", "NotificationManager is null")
            return
        }
        manager.createNotificationChannel(channel)
        Log.d("Notification", "Notification channel $CHANNEL_ID created")

        // Build notification
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_printer) // Ensure this exists
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(!isPersistent)
            .build()

        // Store last notification for foreground service access
        lastNotification = notification

        // Send notification
        with(NotificationManagerCompat.from(context)) {
            try {
                notify(notificationId, notification)
                Log.d("Notification", "Posted notification $notificationId: $title")
            } catch (e: SecurityException) {
                Log.e("Notification", "Permission denied: ${e.message}")
            }
        }
    }

    fun getLastNotification(): Notification {
        return lastNotification ?: throw IllegalStateException("No notification available")
    }
}