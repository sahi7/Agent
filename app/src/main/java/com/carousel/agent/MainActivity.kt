package com.carousel.agent

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import android.view.View
import android.widget.Button
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var connectionStatusText: TextView
    private lateinit var retryButton: Button
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        connectionStatusText = findViewById(R.id.connection_status_text)
        retryButton = findViewById(R.id.retry_button)

        // Check if device_token exists, else redirect to SetupActivity
        val sharedPrefs = EncryptedSharedPreferences.create(
            "auth_prefs",
            MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
            this,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
//        sharedPrefs.edit { clear() }
        val deviceToken = sharedPrefs.getString("device_token", null)
        if (deviceToken == null) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }

        // Start PrintService with branch_id and device_token
        val branchId = sharedPrefs.getString("branch_id", "1") ?: "1"
        val deviceId = sharedPrefs.getString("device_id", "") ?: ""
        startService(Intent(this, PrintService::class.java).apply {
            putExtra("BRANCH_ID", branchId)
            putExtra("DEVICE_TOKEN", deviceToken)
            putExtra("DEVICE_ID", deviceId)
        })

        // Load SettingsFragment
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, SettingsFragment())
            .commit()

        // Observe connection status from PrintService
        scope.launch {
            PrintService.connectionStatus.collect { status ->
                when (status) {
                    PrintService.ConnectionStatus.DISCONNECTED -> {
                        connectionStatusText.text = "Connection failed"
                        connectionStatusText.visibility = View.VISIBLE
                        retryButton.visibility = View.VISIBLE
                    }
                    PrintService.ConnectionStatus.CONNECTED -> {
                        connectionStatusText.visibility = View.GONE
                        retryButton.visibility = View.GONE
                    }
                    PrintService.ConnectionStatus.RETRYING -> {
                        connectionStatusText.text = "Reconnecting..."
                        connectionStatusText.visibility = View.VISIBLE
                        retryButton.visibility = View.GONE
                    }
                }
            }
        }

        retryButton.setOnClickListener {
            connectionStatusText.text = "Reconnecting..."
            retryButton.visibility = View.GONE
            PrintService.handleDisconnection()
        }
    }
}

