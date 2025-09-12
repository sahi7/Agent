package com.carousel.agent

import android.content.Context
import android.hardware.usb.UsbManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import javax.jmdns.JmDNS

class PrinterScanner(
    private val context: Context,
    private val scope: CoroutineScope,
    private val savePrintersCallback: (List<PrinterConfig>) -> Unit // Callback to save printers
) {
    private val printers = mutableListOf<PrinterConfig>()

    suspend fun scanPrinters(scanId: String?, senderId: String?, branchId: String?) {
        printers.clear()
        // USB Detection
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        usbManager.deviceList.values.filter { it.deviceClass == 7 }.forEach {
            val fingerprint = it.serialNumber ?: "${it.vendorId}:${it.productId}"
            val config = PrinterConfig(
                printerId = "", // Backend assigns
                connectionType = "usb",
                vendorId = it.vendorId.toString(16),
                productId = it.productId.toString(16),
                fingerprint = fingerprint
            )
            printers.add(config)
            streamDiscoveredPrinter(config, scanId, senderId)
        }
        // Network Detection
        try {
            val jmdns = JmDNS.create(InetAddress.getLocalHost())
            val services = jmdns.list("_printer._tcp.local.", 5000)
            for (service in services) {
                if (service.inetAddresses.isNotEmpty()) {
                    val ip = service.inetAddresses[0].hostAddress
                    val fingerprint = service.application ?: ip
                    val config = PrinterConfig(
                        printerId = "", // Backend assigns
                        connectionType = "network",
                        ipAddress = ip,
                        fingerprint = fingerprint
                    )
                    printers.add(config)
                    streamDiscoveredPrinter(config, scanId, senderId)
                }
            }
            jmdns.close()
        } catch (e: Exception) {
            Log.e("JmDNS", "Network discovery failed: ${e.message}")
        }
        // Save printers locally via callback
        if (printers.isNotEmpty() && printers.none { it.isDefault }) {
            printers[0] = printers[0].copy(isDefault = true)
        }
        savePrintersCallback(printers)
        // Send completion with printers
        val printersJson = JSONArray()
        printers.forEach { config ->
            printersJson.put(JSONObject().apply {
                put("fingerprint", config.fingerprint)
                put("connection_type", config.connectionType)
                putOpt("vendor_id", config.vendorId)
                putOpt("product_id", config.productId)
                putOpt("ip_address", config.ipAddress)
                putOpt("serial_port", config.serialPort)
                put("profile", config.profile)
            })
        }
        Log.d("PrinterScanner", "Printers discovered $printersJson")
        PrintService.webSocket?.send(JSONObject().apply {
            put("type", "scan_complete")
            put("branch_id", branchId ?: "")
            put("scan_id", scanId ?: "")
            put("sender", senderId ?: "")
            put("count", printers.size)
            put("printers", printersJson)
        }.toString()) ?: Log.e("PrinterScanner", "WebSocket is null, cannot send scan_complete")
    }

    private suspend fun streamDiscoveredPrinter(config: PrinterConfig, scanId: String?, senderId: String?) {
        PrintService.webSocket?.send(JSONObject().apply {
            put("type", "printer_discovered")
            put("scan_id", scanId ?: "")
            put("sender", senderId)
            put("config", JSONObject().apply {
                put("fingerprint", config.fingerprint)
                put("connection_type", config.connectionType)
                putOpt("vendor_id", config.vendorId)
                putOpt("product_id", config.productId)
                putOpt("ip_address", config.ipAddress)
                putOpt("serial_port", config.serialPort)
                put("profile", config.profile)
            })
        }.toString()) ?: Log.e("PrinterScanner", "WebSocket is null, cannot send printer_discovered")
    }
}