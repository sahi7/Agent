package com.carousel.agent

import android.content.Context
import android.content.SharedPreferences
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import org.json.JSONArray
import org.json.JSONObject
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.io.File

object PrinterUtils {
    private val _scanResultFlow = MutableSharedFlow<Int>(replay = 1)
    val scanResultFlow: SharedFlow<Int> = _scanResultFlow

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

    fun handleSetDefaultCommand(context: Context, printerId: String, senderId: String) {
        val sharedPrefs = getEncryptedSharedPrefs(context)
        val printers = mutableListOf<PrinterConfig>()
        val storedPrintersJson = sharedPrefs.getString("printers", "[]")
        val jsonArray = JSONArray(storedPrintersJson)
        for (i in 0 until jsonArray.length()) {
            val json = jsonArray.getJSONObject(i)
            printers.add(json.toPrinterConfig().copy(isDefault = json.getString("printer_id") == printerId))
        }
        savePrinters(context, printers)
        PrintService.webSocket?.send(JSONObject().apply {
            put("type", "ack")
            put("status", "success")
            put("sender", senderId)
            put("command", "default")
            put("printer_id", printerId)
        }.toString())
        Log.i("WebSocket", "Set default printer $printerId")
    }

    fun handleRemoveCommand(context: Context, printerId: String, senderId: String) {
        Log.i("PrinterUtils", "In Remove printer $printerId")
        val sharedPrefs = getEncryptedSharedPrefs(context)
        val printers = mutableListOf<PrinterConfig>()
        val storedPrintersJson = sharedPrefs.getString("printers", "[]")
        val jsonArray = JSONArray(storedPrintersJson)
        for (i in 0 until jsonArray.length()) {
            val json = jsonArray.getJSONObject(i)
            if (json.getString("printer_id") != printerId) {
                printers.add(json.toPrinterConfig())
            }
        }
        if (printers.isNotEmpty() && printers.none { it.isDefault }) {
            printers[0] = printers[0].copy(isDefault = true)
        }
        savePrinters(context, printers)
        PrintService.webSocket?.send(JSONObject().apply {
            put("type", "ack")
            put("status", "success")
            put("sender", senderId)
            put("command", "remove")
            put("printer_id", printerId)
        }.toString())
        Log.i("WebSocket", "Removed printer $printerId")
    }

    fun handleResetCommand(context: Context, senderId: String) {
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
}