package com.carousel.agent

import android.content.Context
import android.content.SharedPreferences
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

class SettingsFragment : Fragment() {
    private lateinit var printerList: RecyclerView
    private lateinit var discoverButton: Button
    private val printers = mutableListOf<PrinterConfig>()
    private val scope = CoroutineScope(Dispatchers.IO)
    private lateinit var sharedPrefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedPrefs = getEncryptedSharedPrefs()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_settings, container, false)
        printerList = view.findViewById(R.id.printer_list)
        printerList.layoutManager = LinearLayoutManager(context)
        printerList.adapter = PrinterAdapter(printers, {}, {}, {}) // Update with handlers later
        discoverButton = view.findViewById(R.id.discover_button)
        discoverButton.setOnClickListener {
            scope.launch {
                scanPrinters(null, senderId=null) // Manual scan, no scan_id
            }
        }
        loadStoredPrinters()
        return view
    }

    private fun loadStoredPrinters() {
        val storedPrintersJson = sharedPrefs.getString("printers", "[]")
        val jsonArray = JSONArray(storedPrintersJson)
        printers.clear()
        for (i in 0 until jsonArray.length()) {
            val json = jsonArray.getJSONObject(i)
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
        printerList.adapter?.notifyDataSetChanged()
    }

    private fun savePrinters() {
        val jsonArray = JSONArray()
        printers.forEach { config ->
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
        sharedPrefs.edit().putString("printers", jsonArray.toString()).apply()
    }

    suspend fun scanPrinters(scanId: String?, senderId: String?) {
        val newPrinters = mutableListOf<PrinterConfig>()
        // USB Detection
        val usbManager = requireContext().getSystemService(Context.USB_SERVICE) as UsbManager
        usbManager.deviceList.values.filter { it.deviceClass == 7 }.forEach {
            val fingerprint = it.serialNumber ?: "${it.vendorId}:${it.productId}"
            val config = PrinterConfig(
                printerId = "", // Backend assigns
                connectionType = "usb",
                vendorId = it.vendorId.toString(16),
                productId = it.productId.toString(16),
                fingerprint = fingerprint
            )
            newPrinters.add(config)
            streamDiscoveredPrinter(config, scanId, senderId)
        }
        // Network Detection
        try {
            val jmdns = JmDNS.create(InetAddress.getLocalHost())
            val services = jmdns.list("_printer._tcp.local.", 5000)
            for (service in services) {
                if (service.inetAddresses.isNotEmpty()) {
                    val ip = service.inetAddresses[0].hostAddress
                    val fingerprint = service.application ?: ip // Fallback to IP if no MAC
                    val config = PrinterConfig(
                        printerId = "", // Backend assigns
                        connectionType = "network",
                        ipAddress = ip,
                        fingerprint = fingerprint
                    )
                    newPrinters.add(config)
                    streamDiscoveredPrinter(config, scanId, senderId)
                }
            }
            jmdns.close()
        } catch (e: Exception) {
            Log.e("JmDNS", "Network discovery failed: ${e.message}")
        }
        // Update local storage and UI
        requireActivity().runOnUiThread {
            printers.clear()
            printers.addAll(newPrinters)
            if (printers.isNotEmpty() && printers.none { it.isDefault }) {
                printers[0] = printers[0].copy(isDefault = true)
            }
            printerList.adapter?.notifyDataSetChanged()
            Toast.makeText(context, "Found ${newPrinters.size} printers", Toast.LENGTH_SHORT).show()
        }
        savePrinters()
        // Send scan completion
        PrintService.webSocket?.send(JSONObject().apply {
            put("type", "scan_complete")
            put("scan_id", scanId ?: "")
            put("sender", senderId ?: "")
            put("count", newPrinters.size)
        }.toString())
    }

    private suspend fun streamDiscoveredPrinter(config: PrinterConfig, scanId: String?, senderId: String?) {
        PrintService.webSocket?.send(JSONObject().apply{
            put("type", "printer_discovered")
            put("scan_id", scanId ?: "")
            put("sender", senderId ?: "")
            put("config", JSONObject().apply {
                put("fingerprint", config.fingerprint)
                put("connection_type", config.connectionType)
                putOpt("vendor_id", config.vendorId)
                putOpt("product_id", config.productId)
                putOpt("ip_address", config.ipAddress)
                putOpt("serial_port", config.serialPort)
                put("profile", config.profile)
            })
        }.toString())
    }

    private fun getEncryptedSharedPrefs(): SharedPreferences = EncryptedSharedPreferences.create(
        "app_prefs",
        MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
        requireContext(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
}