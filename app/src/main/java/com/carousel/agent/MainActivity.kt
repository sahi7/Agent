package com.carousel.agent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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

        // Request notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            if ( (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED)) {
                Toast.makeText(this, "Notifications permission required for print service", Toast.LENGTH_LONG).show()
                val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                intent.data = android.net.Uri.fromParts("package", packageName, null)
            }
        }
    }
}

