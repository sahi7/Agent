package com.carousel.agent

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import androidx.core.content.edit
import java.io.File
import java.io.FileOutputStream
import android.graphics.Bitmap
import android.graphics.BitmapFactory

class SetupActivity : AppCompatActivity() {
    private val client = OkHttpClient()
    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        val deviceIdInput = findViewById<EditText>(R.id.device_id_input)
        val deviceKeyInput = findViewById<EditText>(R.id.device_key_input)
        val submitButton = findViewById<Button>(R.id.submit_button)

        // Check if device_token exists
        val sharedPrefs = getEncryptedSharedPrefs()
        if (sharedPrefs.getString("device_token", null) != null) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        submitButton.setOnClickListener {
            val deviceIdPart = deviceIdInput.text.toString().trim()
            val deviceKeyPart = deviceKeyInput.text.toString().trim()
            val deviceId = deviceIdPart + deviceKeyPart
//            val deviceId = deviceIdInput.text.toString().trim()
            if (deviceId.length != 12) {
                Toast.makeText(this, "Invalid ID or Key", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            scope.launch {
                try {
                    val request = Request.Builder()
                        .url("http://${Constants.SERVER_URL}/api/hardware/register-device/")
                        .post(
                            JSONObject()
                                .put("device_id", deviceId)
                                .toString()
                                .toRequestBody("application/json".toMediaType())
                        )
                        .build()
                    val response = client.newCall(request).execute()
                    if (response.isSuccessful) {
                        val json = JSONObject(response.body?.string())
                        // Extract nested data
                        val branchObject = json.getJSONObject("branch")
                        val deviceObject = json.getJSONObject("device")
                        Log.d("Setup", "Dev OBJ: $deviceObject")

                        val branchId = branchObject.getString("branch_id")
                        val branchName = branchObject.getString("name")
                        val branchAddress = branchObject.getString("address")
                        val branchCurrency = branchObject.getString("currency")
                        val branchTimezone = branchObject.getString("timezone")
                        val logoUrl = branchObject.optString("logo_url", null)
                        if (logoUrl != null) {
                            // Download and save image
                            val request = Request.Builder().url(logoUrl).build()
                            val response = client.newCall(request).execute()

                            if (response.isSuccessful) {
                                val bytes = response.body.bytes()
                                saveImageToPrefs(this@SetupActivity, bytes)
                            }
                        }

                        val deviceId = deviceObject.getString("device_id")
                        val deviceToken = deviceObject.getString("device_token")
                        val deviceName = deviceObject.optString("name", "Unknown")

                        sharedPrefs.edit {
                            putString("device_id", deviceId)
                            putString("branch_id", branchId.toString())
                            putString("device_token", deviceToken)
                            putString("device_name", deviceName)
                            putString("branch_name", branchName)
                            putString("branch_address", branchAddress)
                            putString("branch_currency", branchCurrency)
                            putString("branch_timezone", branchTimezone)
                        }
                        runOnUiThread {
                            Toast.makeText(this@SetupActivity, "Setup complete", Toast.LENGTH_SHORT).show()
                            startActivity(Intent(this@SetupActivity, MainActivity::class.java))
                            finish()
                        }
                    } else {
                        runOnUiThread {
                            Toast.makeText(this@SetupActivity, "Invalid Device ID", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        Toast.makeText(this@SetupActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    // Save image bytes to app storage and path to SharedPrefs
    fun saveImageToPrefs(context: Context, bytes: ByteArray) {
        try {
            val bitmap: Bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

            // Store file under app's private files dir
            val file = File(context.filesDir, "logo_branch.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }

            // Save path to SharedPrefs
            val sharedPrefs = getEncryptedSharedPrefs()
            sharedPrefs.edit {
                putString("branch_logo_path", file.absolutePath)
            }
        } catch (e: Exception) {
            Log.e("PrefsHelper", "Error saving logo: ${e.message}", e)
        }
    }

    private fun getEncryptedSharedPrefs() = EncryptedSharedPreferences.create(
        "auth_prefs",
        MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
        this,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
}