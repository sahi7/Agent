package com.carousel.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import androidx.core.content.edit

class SettingsFragment : Fragment() {
    private lateinit var printerList: RecyclerView
    private lateinit var discoverButton: Button
    private val printers = mutableListOf<PrinterConfig>()
    private val scope = CoroutineScope(Dispatchers.IO)
    private lateinit var sharedPrefs: SharedPreferences
    private lateinit var scanReceiver: BroadcastReceiver


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedPrefs = getEncryptedSharedPrefs()
        // Register broadcast receiver for scan results
        scanReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                Log.d("SettingsFragment", "Broadcast received: ${intent?.action}")
                val count = intent?.getIntExtra("count", 0) ?: 0
                if (context != null) {
                    loadStoredPrinters()
                    Toast.makeText(context, "Found $count printers", Toast.LENGTH_SHORT).show()
                } else {
                    Log.e("SettingsFragment", "Context is null in onReceive")
                }
            }
        }
        requireContext().registerReceiver(
            scanReceiver,
            IntentFilter("com.carousel.agent.PRINTER_SCAN_RESULT"),
            Context.RECEIVER_NOT_EXPORTED
        )
        Log.d("SettingsFragment", "Broadcast receiver registered")
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
                PrinterScanner(requireContext(), scope, ::savePrinters).scanPrinters(null, null) // Manual scan
            }
        }
        loadStoredPrinters()
        return view
    }

    override fun onDestroy() {
        requireContext().unregisterReceiver(scanReceiver)
        super.onDestroy()
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
        activity?.runOnUiThread {
            Log.d("SettingsFragment", "Updating RecyclerView with ${printers.size} printers")
            printerList.adapter?.notifyDataSetChanged()
        } ?: Log.e("SettingsFragment", "Activity is null, cannot update RecyclerView")
    }

    private fun savePrinters(newPrinters: List<PrinterConfig>) {
        printers.clear()
        printers.addAll(newPrinters)
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
        sharedPrefs.edit { putString("printers", jsonArray.toString()) }
        Log.d("SettingsFragment", "Saved printers: $jsonArray")
    }

    private fun getEncryptedSharedPrefs(): SharedPreferences = EncryptedSharedPreferences.create(
        "app_prefs",
        MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
        requireContext(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
}