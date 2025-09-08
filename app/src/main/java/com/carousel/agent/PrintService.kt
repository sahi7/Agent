package com.carousel.agent

import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.os.IBinder
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class PrintService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO)
    private lateinit var client: OkHttpClient
    private var webSocket: WebSocket? = null
    private var branchId: String = "1"
    private var deviceToken: String = ""
    private var deviceId: String = ""
    private var isReconnecting = false
    private var reconnectAttempts = 0
    private var reconnectJob: Job? = null
    private var isConnected = false
    private lateinit var sharedPrefs: SharedPreferences

    // Configuration constants
    private val INITIAL_DELAY = 1000L // 1 second
    private val MAX_DELAY = 30000L // 30 seconds
    private val MAX_RECONNECT_ATTEMPTS = 5
    private val BACKOFF_MULTIPLIER = 2.0
    private val JITTER_FACTOR = 0.1 // 10% jitter

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        var webSocket: WebSocket? = null // Shared WebSocket for SettingsFragment
    }

    override fun onCreate() {
        super.onCreate()
        sharedPrefs = EncryptedSharedPreferences.create(
            "app_prefs",
            MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
            this,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        branchId = intent?.getStringExtra("BRANCH_ID") ?: branchId
        deviceToken = intent?.getStringExtra("DEVICE_TOKEN") ?: deviceToken
        deviceId = intent?.getStringExtra("DEVICE_ID") ?: deviceId
        client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
        connectWebSocket()
        return START_STICKY
    }

    private fun connectWebSocket() {
        scope.launch {
            val request = Request.Builder()
                .url("ws://${Constants.SERVER_URL}/ws/device/$deviceId/")
                .header("Authorization", "Token $deviceToken")
                .addHeader("Device-Id", deviceId)
                .build()
            Log.d("WebSocket", "Connecting to URL: ${request.url}")
            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d("WebSocket", "Connection opened")
                    Log.d("WebSocket", "Response code: ${response.code}")
                    Log.d("WebSocket", "Response headers: ${response.headers}")
                    isConnected = true
                    scope.launch {
                        webSocket.send(JSONObject().apply {
                            put("type", "subscribe")
                            put("branch_id", branchId)
                        }.toString())
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    Log.d("WebSocket", "Received message: $text")
                    scope.launch {
                        try {
                            val data = JSONObject(text)
                            when (data.getString("type")) {
                                "connected" -> Log.i("WebSocket", "Connection confirmed")
                                "subscribed" -> Log.i("WebSocket", "Subscribed to branch_$branchId")
                                "scan.command" -> {
                                    val scanId = data.getString("scan_id")
                                    val senderId = data.getString("sender")
                                    SettingsFragment().scanPrinters(scanId, senderId)
                                }
                                "print.command" -> {
                                    try {
                                        // Handle both cases: JSONObject or JSON string
                                        val payloadObj = when (val payload = data.get("payload")) {
                                            is String -> JSONObject(payload)
                                            is JSONObject -> payload
                                            else -> throw IllegalArgumentException("Unexpected payload type: ${payload.javaClass}")
                                        }
                                        processPrintJob(payloadObj)
                                        webSocket.send(JSONObject().apply {
                                            put("type", "ack")
                                            put("order_id", payloadObj.getInt("order_id"))
                                            put("status", "success")
                                        }.toString())
                                    } catch (e: Exception) {
                                        Log.e("WebSocket", "Error processing print.command payload: ${e.message}", e)
                                    }
                                }
                                "update.command" -> {
                                    updatePrinterConfig(data.getString("printer_id"), data.getJSONObject("config"))
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("WebSocket", "Error processing message: ${e.message}")
                        }
                    }
                }
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    Log.d("WebSocket", "Received binary message: ${bytes.hex()}")
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    Log.i("WebSocket", "Closing: $reason")
                    webSocket.close(1000, null)
                    handleDisconnection()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.i("WebSocket", "Connection closed - Code: $code, Reason: $reason")
                    handleDisconnection()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e("WebSocket", "Failure: ${t.message}")
                    handleDisconnection()
//                    reconnect()
                }
            })
        }
    }

    private fun handleDisconnection() {
        Log.w("WebSocket", "Connection lost, initiating reconnection handlers..")
        isConnected = false
        this.webSocket = null
        startReconnection()
    }

    private fun startReconnection() {
        if (isReconnecting) {
            Log.d("WebSocket", "Reconnection already in progress")
            return
        }

        isReconnecting = true
        reconnectAttempts = 0

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            while (reconnectAttempts < MAX_RECONNECT_ATTEMPTS && isReconnecting) {
                reconnectAttempts++

                val delayTime = calculateDelay(reconnectAttempts)
                Log.i("WebSocket", "Reconnection attempt ${reconnectAttempts}/$MAX_RECONNECT_ATTEMPTS in ${delayTime}ms")

                delay(delayTime)

                if (!isReconnecting) break

                try {
                    connectWebSocket()
                    // Wait a bit to see if connection succeeds
                    delay(5000)

                    // If we still don't have a connection, continue retrying
                    if (!isConnected && isReconnecting) {
                        Log.w("WebSocket", "Reconnection attempt $reconnectAttempts failed")
                        continue
                    } else {
                        Log.i("WebSocket", "Reconnection successful on attempt $reconnectAttempts")
                        resetReconnectionState()
                        break
                    }
                } catch (e: Exception) {
                    Log.e("WebSocket", "Reconnection attempt $reconnectAttempts failed", e)
                }
            }

            if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS && webSocket == null) {
                Log.e("WebSocket", "Max reconnection attempts reached. Giving up.")
                isReconnecting = false
                // Notify UI or take appropriate action
                notifyReconnectionFailed()
            }
        }
    }

    private fun calculateDelay(attempt: Int): Long {
        // Exponential backoff with jitter
        val exponentialDelay = (INITIAL_DELAY * Math.pow(BACKOFF_MULTIPLIER, (attempt - 1).toDouble())).toLong()
        val cappedDelay = minOf(exponentialDelay, MAX_DELAY)

        // Add jitter to prevent thundering herd
        val jitter = (cappedDelay * JITTER_FACTOR * (Math.random() * 2 - 1)).toLong()
        val finalDelay = cappedDelay + jitter

        return maxOf(finalDelay, INITIAL_DELAY)
    }

    private fun resetReconnectionState() {
        isReconnecting = false
        reconnectAttempts = 0
        reconnectJob?.cancel()
        reconnectJob = null
        Log.d("WebSocket", "Reconnection state reset")
    }

    private fun notifyReconnectionFailed() {
        // Handle max retry scenario - maybe show notification or alert user
        scope.launch(Dispatchers.Main) {
            // Example: Show notification or update UI
            Log.e("WebSocket", "Unable to reconnect after maximum attempts")
            // You can add UI notifications here
        }
    }

    private suspend fun processPrintJob(job: JSONObject) {
        Log.d("WebSocket", "In printJob processor")
        val printerId = job.optString("printer_id", null)
        val config = if (printerId != null) getPrinterConfig(printerId) else getDefaultPrinterConfig()
        val printer = ReceiptPrinter(this, config)
        try {
            printer.connect()
            printer.printReceipt(job)
            Log.i("Printer", "Printed order ${job.getInt("order_id")}")
        } catch (e: Exception) {
            Log.e("Printer", "Print failed: ${e.message}")
            webSocket?.send(JSONObject().apply {
                put("type", "ack")
                put("order_id", job.getInt("order_id"))
                put("status", "failed")
                put("error", e.message)
            }.toString())
        } finally {
            printer.disconnect()
        }
    }

    private fun getPrinterConfig(printerId: String): PrinterConfig {
        val storedPrintersJson = sharedPrefs.getString("printers", "[]")
        val jsonArray = JSONArray(storedPrintersJson)
        for (i in 0 until jsonArray.length()) {
            val json = jsonArray.getJSONObject(i)
            if (json.getString("printer_id") == printerId) {
                return PrinterConfig(
                    printerId = json.getString("printer_id"),
                    connectionType = json.getString("connectionType"),
                    vendorId = json.optString("vendorId", null),
                    productId = json.optString("productId", null),
                    ipAddress = json.optString("ipAddress", null),
                    serialPort = json.optString("serialPort", null),
                    profile = json.optString("profile", "TM-T88III"),
                    isDefault = json.optBoolean("isDefault", false)
                )
            }
        }
        throw Exception("Printer not found")
    }

    private fun getDefaultPrinterConfig(): PrinterConfig {
        val storedPrintersJson = sharedPrefs.getString("printers", "[]")
        val jsonArray = JSONArray(storedPrintersJson)
        for (i in 0 until jsonArray.length()) {
            val json = jsonArray.getJSONObject(i)
            if (json.optBoolean("isDefault", false)) {
                return PrinterConfig(
                    connectionType = json.getString("connectionType"),
                    vendorId = json.optString("vendorId", null),
                    productId = json.optString("productId", null),
                    ipAddress = json.optString("ipAddress", null),
                    serialPort = json.optString("serialPort", null),
                    profile = json.optString("profile", "TM-T88III"),
                    isDefault = true,
                    printerId = json.optString("printerId", null),
                    fingerprint = json.optString("fingerprint", null),
                )
            }
        }
        throw Exception("No default printer found")
    }

    private fun updatePrinterConfig(printerId: String, configJson: JSONObject) {
        val storedPrintersJson = sharedPrefs.getString("printers", "[]")
        val jsonArray = JSONArray(storedPrintersJson) // Fix: Use JSONArray
        val printers = mutableListOf<PrinterConfig>()
        var found = false
        for (i in 0 until jsonArray.length()) {
            val json = jsonArray.getJSONObject(i)
            if (json.getString("printer_id") == printerId) {
                printers.add(PrinterConfig(
                    printerId = printerId,
                    connectionType = configJson.getString("connection_type"),
                    vendorId = configJson.optString("vendor_id", null),
                    productId = configJson.optString("product_id", null),
                    ipAddress = configJson.optString("ip_address", null),
                    serialPort = configJson.optString("serial_port", null),
                    profile = configJson.optString("profile", "TM-T88III"),
                    isDefault = configJson.optBoolean("is_default", false)
                ))
                found = true
            } else {
                printers.add(PrinterConfig(
                    printerId = json.getString("printer_id"),
                    connectionType = json.getString("connectionType"),
                    vendorId = json.optString("vendorId", null),
                    productId = json.optString("productId", null),
                    ipAddress = json.optString("ipAddress", null),
                    serialPort = json.optString("serialPort", null),
                    profile = json.optString("profile", "TM-T88III"),
                    isDefault = json.optBoolean("isDefault", false)
                ))
            }
        }
        if (!found) {
            printers.add(PrinterConfig(
                printerId = printerId,
                connectionType = configJson.getString("connection_type"),
                vendorId = configJson.optString("vendor_id", null),
                productId = configJson.optString("product_id", null),
                ipAddress = configJson.optString("ip_address", null),
                serialPort = configJson.optString("serial_port", null),
                profile = configJson.optString("profile", "TM-T88III"),
                isDefault = configJson.optBoolean("is_default", false)
            ))
        }
        val newJsonArray = JSONArray()
        printers.forEach { config ->
            newJsonArray.put(JSONObject().apply {
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
        sharedPrefs.edit().putString("printers", newJsonArray.toString()).apply()
    }

    override fun onDestroy() {
        webSocket?.close(1000, "Service stopped")
        client.dispatcher.executorService.shutdown()
        super.onDestroy()
    }
}