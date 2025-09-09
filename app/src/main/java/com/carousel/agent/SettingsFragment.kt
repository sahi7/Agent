package com.carousel.agent

import android.content.SharedPreferences
import android.os.Build
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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest

class SettingsFragment : Fragment() {
    private lateinit var printerList: RecyclerView
    private lateinit var discoverButton: Button
    private val printers = mutableListOf<PrinterConfig>()
    private val scope = CoroutineScope(Dispatchers.IO)
    private lateinit var sharedPrefs: SharedPreferences


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedPrefs = PrinterUtils.getEncryptedSharedPrefs(requireContext())
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
                PrinterScanner(requireContext(), scope) { newPrinters ->
                    PrinterUtils.savePrinters(requireContext(), newPrinters)
                }.scanPrinters(null, null)// Manual scan
            }
        }
        // Collect scan results
        lifecycleScope.launch {
            PrinterUtils.scanResultFlow.collectLatest { count ->
                Log.d("SettingsFragment", "Received scan result with count: $count")
                loadStoredPrinters()
                Toast.makeText(requireContext(), "Found $count printers", Toast.LENGTH_SHORT).show()
            }
        }
        loadStoredPrinters()
        Toast.makeText(requireContext(), "SettingsFragment loaded", Toast.LENGTH_SHORT).show()
        return view
    }

    private fun loadStoredPrinters() {
        val storedPrintersJson = sharedPrefs.getString("printers", "[]")
        Log.d("SettingsFragment", "Loading printers: $storedPrintersJson")
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
        Log.d("SettingsFragment", "Loaded ${printers.size} printers")
        activity?.runOnUiThread {
            Log.d("SettingsFragment", "Updating RecyclerView with ${printers.size} printers")
            printerList.adapter?.notifyDataSetChanged()
        } ?: Log.e("SettingsFragment", "Activity is null, cannot update RecyclerView")
    }
}