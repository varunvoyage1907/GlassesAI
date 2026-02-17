package com.glassesai.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.glassesai.app.databinding.ActivityMainBinding
import com.glassesai.glasses.ConnectionManager
import com.glassesai.glasses.GlassesManager
import com.glassesai.ui.ScanActivity
import com.glassesai.ui.OpenClawSessionActivity
import com.permissionx.guolindev.PermissionX
import com.oudmon.ble.base.bluetooth.BleOperateManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "MainActivity"
    }
    
    private lateinit var binding: ActivityMainBinding
    private lateinit var glassesManager: GlassesManager
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        glassesManager = GlassesManager.getInstance(this)
        glassesManager.initialize()
        
        setupUI()
        observeState()
        requestPermissions()
    }
    
    private fun setupUI() {
        binding.apply {
            // Title
            tvTitle.text = "GlassesAI"
            tvSubtitle.text = "AI Assistant for Smart Glasses"
            
            // Scan button
            btnScan.setOnClickListener {
                if (checkPermissions()) {
                    startActivity(Intent(this@MainActivity, ScanActivity::class.java))
                } else {
                    requestPermissions()
                }
            }
            
            // Start session button - Now uses OpenClaw as the AI brain!
            btnStartSession.setOnClickListener {
                if (glassesManager.isConnected()) {
                    // Use OpenClaw-powered session (replaces Gemini)
                    startActivity(Intent(this@MainActivity, OpenClawSessionActivity::class.java))
                } else {
                    Toast.makeText(this@MainActivity, "Please connect to glasses first", Toast.LENGTH_SHORT).show()
                }
            }
            
            // Disconnect button
            btnDisconnect.setOnClickListener {
                glassesManager.disconnect()
            }
            
            // Config status
            updateConfigStatus()
        }
    }
    
    private fun observeState() {
        lifecycleScope.launch {
            glassesManager.connectionState.collectLatest { state ->
                updateConnectionUI(state)
            }
        }
        
        lifecycleScope.launch {
            glassesManager.connectedDeviceName.collectLatest { name ->
                binding.tvDeviceName.text = name ?: "No device connected"
            }
        }
        
        lifecycleScope.launch {
            glassesManager.batteryLevel.collectLatest { level ->
                if (level > 0) {
                    binding.tvBattery.text = "Battery: $level%"
                } else {
                    binding.tvBattery.text = ""
                }
            }
        }
    }
    
    private fun updateConnectionUI(state: GlassesManager.ConnectionState) {
        binding.apply {
            when (state) {
                GlassesManager.ConnectionState.DISCONNECTED -> {
                    tvConnectionStatus.text = "Disconnected"
                    tvConnectionStatus.setTextColor(getColor(android.R.color.holo_red_light))
                    btnScan.isEnabled = true
                    btnStartSession.isEnabled = false
                    btnDisconnect.isEnabled = false
                }
                GlassesManager.ConnectionState.SCANNING -> {
                    tvConnectionStatus.text = "Scanning..."
                    tvConnectionStatus.setTextColor(getColor(android.R.color.holo_orange_light))
                    btnScan.isEnabled = false
                    btnStartSession.isEnabled = false
                    btnDisconnect.isEnabled = false
                }
                GlassesManager.ConnectionState.CONNECTING -> {
                    tvConnectionStatus.text = "Connecting..."
                    tvConnectionStatus.setTextColor(getColor(android.R.color.holo_orange_light))
                    btnScan.isEnabled = false
                    btnStartSession.isEnabled = false
                    btnDisconnect.isEnabled = false
                }
                GlassesManager.ConnectionState.CONNECTED -> {
                    tvConnectionStatus.text = "Connected"
                    tvConnectionStatus.setTextColor(getColor(android.R.color.holo_green_light))
                    btnScan.isEnabled = false
                    btnStartSession.isEnabled = true
                    btnDisconnect.isEnabled = true
                }
            }
        }
    }
    
    private fun updateConfigStatus() {
        binding.apply {
            // OpenClaw status (AI Brain)
            if (Config.isOpenClawConfigured()) {
                tvOpenClawStatus.text = "✓ OpenClaw AI Brain Ready"
                tvOpenClawStatus.setTextColor(getColor(android.R.color.holo_green_dark))
                tvGeminiStatus.text = "Powered by OpenClaw"
                tvGeminiStatus.setTextColor(getColor(android.R.color.darker_gray))
            } else {
                tvOpenClawStatus.text = "✗ OpenClaw not configured"
                tvOpenClawStatus.setTextColor(getColor(android.R.color.holo_red_dark))
                tvGeminiStatus.text = "Please configure OpenClaw in Config.kt"
                tvGeminiStatus.setTextColor(getColor(android.R.color.holo_red_light))
            }
        }
    }
    
    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.RECORD_AUDIO
        )
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        
        PermissionX.init(this)
            .permissions(permissions)
            .onExplainRequestReason { scope, deniedList ->
                scope.showRequestReasonDialog(
                    deniedList,
                    "GlassesAI needs these permissions to connect to your glasses and use voice",
                    "OK",
                    "Cancel"
                )
            }
            .request { allGranted, _, _ ->
                if (allGranted) {
                    Log.d(TAG, "All permissions granted")
                } else {
                    Toast.makeText(this, "Some permissions were denied", Toast.LENGTH_SHORT).show()
                }
            }
    }
    
    private fun checkPermissions(): Boolean {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.RECORD_AUDIO
        )
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        
        return permissions.all { 
            checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED 
        }
    }
    
    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume")
        
        // Register as connection listener
        ConnectionManager.connectionListener = object : ConnectionManager.ConnectionListener {
            override fun onConnectionSuccess(deviceName: String?) {
                Log.d(TAG, "ConnectionManager: onConnectionSuccess($deviceName)")
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Connected to ${deviceName ?: "glasses"}", Toast.LENGTH_SHORT).show()
                    glassesManager.onConnected(deviceName)
                }
            }
            
            override fun onConnectionFailed() {
                Log.d(TAG, "ConnectionManager: onConnectionFailed")
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Connection failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
        
        // Check connection state on resume
        try {
            val bleManager = BleOperateManager.getInstance()
            if (bleManager != null && bleManager.isConnected) {
                Log.d(TAG, "BLE is connected on resume")
                // Check if we just connected (via ConnectionManager)
                val connectedDevice = ConnectionManager.connectedDeviceName
                if (connectedDevice != null) {
                    glassesManager.onConnected(connectedDevice)
                } else {
                    glassesManager.onConnected(glassesManager.connectedDeviceName.value)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking BLE connection state", e)
        }
    }
    
    override fun onPause() {
        super.onPause()
        // Don't remove listener on pause - we want to receive callbacks even when paused
    }
    
    override fun onDestroy() {
        super.onDestroy()
        glassesManager.cleanup()
    }
}
