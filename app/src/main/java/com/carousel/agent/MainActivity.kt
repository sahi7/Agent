package com.carousel.agent

import android.content.Context
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.appcompat.widget.Toolbar
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import android.widget.Button
import com.google.android.material.navigation.NavigationView
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.core.content.edit
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var connectionStatusText: TextView
    private lateinit var retryButton: Button
    private val scope = CoroutineScope(Dispatchers.Main)
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navView: NavigationView
    private lateinit var toolbar: Toolbar
    private lateinit var toggle: ActionBarDrawerToggle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val sharedPrefs = EncryptedSharedPreferences.create(
            "auth_prefs",
            MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
            this,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

        // Initialize toolbar
        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        // Initialize DrawerLayout and NavigationView
        drawerLayout = findViewById(R.id.drawer_layout)
        navView = findViewById(R.id.nav_view)

        // Set up ActionBarDrawerToggle
        toggle = ActionBarDrawerToggle(
            this, drawerLayout, toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close
        )
        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        // Set up header with device name
        val headerView = navView.getHeaderView(0)
        val deviceNameText = headerView.findViewById<TextView>(R.id.device_name)
        val logoImageView = headerView.findViewById<ImageView>(R.id.branch_logo)
        deviceNameText.text = sharedPrefs.getString("device_name", "Device")
        val logoPath = sharedPrefs.getString("branch_logo_path", null)
        android.util.Log.d("MainActivity", "Logo from prefs: $logoPath")
        if (logoPath != null) {
            try {
                val file = File(logoPath)
                if (file.exists()) {
                    val bitmap = BitmapFactory.decodeFile(logoPath)
                    logoImageView.setImageBitmap(bitmap)
                    logoImageView.visibility = View.VISIBLE
                } else {
                    logoImageView.visibility = View.GONE
                    android.util.Log.e("MainActivity", "Logo file not found at $logoPath")
                }
            } catch (e: Exception) {
                logoImageView.visibility = View.GONE
                android.util.Log.e("MainActivity", "Error loading logo: ${e.message}", e)
            }
        } else {
            logoImageView.visibility = View.GONE
        }

        // Set up close button in header
        headerView.findViewById<View>(R.id.close_button).setOnClickListener {
            closeDrawerWithHaptic()
        }

        // Set up close button in header
        headerView.findViewById<View>(R.id.close_button).setOnClickListener {
            closeDrawerWithHaptic()
        }

        // Handle menu item clicks
        navView.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.nav_reset -> {
                    scope.launch {
                        PrinterUtils.handleResetCommand(this@MainActivity, null)
                    }
                    closeDrawerWithHaptic()
                    true
                }
                else -> false
            }
        }

        // Handle drawer slide for content push
        drawerLayout.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {
                val content = findViewById<View>(R.id.content_frame)
                content.translationX = drawerView.width * slideOffset
            }

            override fun onDrawerOpened(drawerView: View) {
                performHapticFeedback()
            }

            override fun onDrawerClosed(drawerView: View) {
                performHapticFeedback()
            }
        })

        // Handle back press
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    closeDrawerWithHaptic()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        connectionStatusText = findViewById(R.id.connection_status_text)
        retryButton = findViewById(R.id.retry_button)

        // Request notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        // Check if device_token exists, else redirect to SetupActivity
        val deviceToken = sharedPrefs.getString("device_token", null)
        if (deviceToken == null) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }

        // Start PrintService only if no WebSocket connection exists
        if (PrintService.webSocket == null) {
            val branchId = sharedPrefs.getString("branch_id", "1") ?: "1"
            val deviceId = sharedPrefs.getString("device_id", "") ?: ""
            startService(Intent(this, PrintService::class.java).apply {
                putExtra("BRANCH_ID", branchId)
                putExtra("DEVICE_TOKEN", deviceToken)
                putExtra("DEVICE_ID", deviceId)
            })
        }

        // Load SettingsFragment
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.content_frame, SettingsFragment())
                .commit()
        }

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
            scope.launch {
                PrintService.handleDisconnection()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED)) {
            Toast.makeText(this, "Notifications permission required for print service", Toast.LENGTH_LONG).show()
            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = android.net.Uri.fromParts("package", packageName, null)
            startActivity(intent)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return if (toggle.onOptionsItemSelected(item)) {
            true
        } else {
            super.onOptionsItemSelected(item)
        }
    }

    private fun closeDrawerWithHaptic() {
        drawerLayout.closeDrawer(GravityCompat.START)
        performHapticFeedback()
    }

    private fun performHapticFeedback() {
        val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        vibrator.vibrate(android.os.VibrationEffect.createOneShot(50, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
    }
}

// Custom DrawerLayout to restrict swipe-to-open to 20dp edge
//class CustomDrawerLayout(context: Context, attrs: android.util.AttributeSet?) : DrawerLayout(context, attrs) {
//    private val edgeSize = context.resources.getDimensionPixelSize(R.dimen.nav_edge_swipe_width)
//
//    override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean {
//        ev ?: return false  // Null safety for ev
//        if (ev.action == MotionEvent.ACTION_DOWN && ev.x > edgeSize) {
//            return false
//        }
//        return super.onInterceptTouchEvent(ev)
//    }
//}