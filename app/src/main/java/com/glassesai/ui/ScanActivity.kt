package com.glassesai.ui

import android.bluetooth.BluetoothDevice
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.glassesai.app.R
import com.glassesai.app.databinding.ActivityScanBinding
import com.glassesai.glasses.BluetoothEvent
import com.glassesai.glasses.ConnectionManager
import com.glassesai.glasses.GlassesBluetoothReceiver
import com.glassesai.glasses.GlassesManager
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.bluetooth.DeviceManager
import com.oudmon.ble.base.scan.BleScannerHelper
import com.oudmon.ble.base.scan.ScanRecord
import com.oudmon.ble.base.scan.ScanWrapperCallback
import android.bluetooth.le.ScanResult
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

class ScanActivity : AppCompatActivity(), GlassesBluetoothReceiver.ConnectionCallback {
    
    companion object {
        private const val TAG = "ScanActivity"
        private const val SCAN_TIMEOUT_MS = 15000L
        private const val CONNECT_TIMEOUT_MS = 15000L  // Increased to 15 seconds
    }
    
    private lateinit var binding: ActivityScanBinding
    private lateinit var glassesManager: GlassesManager
    private val deviceList = mutableListOf<ScannedDevice>()
    private lateinit var adapter: DeviceAdapter
    private val handler = Handler(Looper.getMainLooper())
    private var isScanning = false
    private var isConnecting = false
    private var connectingDeviceName: String? = null
    
    // Scan callback using the SDK's BleScannerHelper
    private val bleScanCallback = object : ScanWrapperCallback {
        override fun onStart() {
            Log.d(TAG, "BLE scan started")
        }
        
        override fun onStop() {
            Log.d(TAG, "BLE scan stopped")
            runOnUiThread {
                isScanning = false
                binding.btnScan.text = "Scan for Glasses"
                if (!isConnecting) {
                    binding.progressBar.visibility = View.GONE
                }
            }
        }
        
        override fun onLeScan(device: BluetoothDevice?, rssi: Int, scanRecord: ByteArray?) {
            device?.let {
                val name = it.name ?: return
                
                // Log all devices for debugging
                Log.d(TAG, "Found BLE device: $name (${it.address}) rssi: $rssi")
                
                val scannedDevice = ScannedDevice(name, it.address, rssi)
                
                if (!deviceList.any { d -> d.address == it.address }) {
                    runOnUiThread {
                        deviceList.add(scannedDevice)
                        deviceList.sortByDescending { d -> d.rssi }
                        adapter.notifyDataSetChanged()
                        updateEmptyState()
                    }
                }
            }
        }
        
        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with error code: $errorCode")
            runOnUiThread {
                Toast.makeText(this@ScanActivity, "Scan failed: $errorCode", Toast.LENGTH_SHORT).show()
                isScanning = false
                binding.btnScan.text = "Scan for Glasses"
                binding.progressBar.visibility = View.GONE
            }
        }
        
        override fun onParsedData(device: BluetoothDevice?, scanRecord: ScanRecord?) {
            // Not used
        }
        
        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            // Not used
        }
    }
    
    private val stopScanRunnable = Runnable {
        stopScan()
    }
    
    private val connectTimeoutRunnable = Runnable {
        if (isConnecting) {
            Log.e(TAG, "Connection timeout")
            isConnecting = false
            runOnUiThread {
                Toast.makeText(this, "Connection timeout. Please try again.", Toast.LENGTH_LONG).show()
                binding.progressBar.visibility = View.GONE
                binding.btnScan.isEnabled = true
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScanBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        glassesManager = GlassesManager.getInstance(this)
        
        // Register for connection callbacks via direct callback (more reliable)
        GlassesBluetoothReceiver.connectionCallback = this
        
        // Also register EventBus (as backup)
        try {
            if (!EventBus.getDefault().isRegistered(this)) {
                EventBus.getDefault().register(this)
                Log.d(TAG, "EventBus registered")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register EventBus", e)
        }
        
        setupUI()
        setupRecyclerView()
    }
    
    // Direct callback from GlassesBluetoothReceiver (more reliable than EventBus)
    override fun onConnected(deviceName: String?) {
        Log.d(TAG, "onConnected callback: deviceName=$deviceName, isConnecting=$isConnecting")
        if (isConnecting) {
            handler.removeCallbacks(connectTimeoutRunnable)
            isConnecting = false
            
            runOnUiThread {
                Toast.makeText(this, "Connected to ${deviceName ?: connectingDeviceName ?: "glasses"}", Toast.LENGTH_SHORT).show()
                glassesManager.onConnected(deviceName ?: connectingDeviceName)
                finish()
            }
        }
    }
    
    override fun onDisconnected() {
        Log.d(TAG, "onDisconnected callback")
        if (isConnecting) {
            handler.removeCallbacks(connectTimeoutRunnable)
            isConnecting = false
            
            runOnUiThread {
                Toast.makeText(this, "Connection failed", Toast.LENGTH_SHORT).show()
                binding.progressBar.visibility = View.GONE
                binding.btnScan.isEnabled = true
            }
        }
    }
    
    /**
     * EventBus subscriber for Bluetooth connection events (backup method)
     */
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onBluetoothEvent(event: BluetoothEvent) {
        Log.d(TAG, "BluetoothEvent received via EventBus: connected=${event.connected}")
        
        if (event.connected) {
            onConnected(null)
        } else {
            onDisconnected()
        }
    }
    
    private fun setupUI() {
        binding.apply {
            toolbar.setNavigationOnClickListener { finish() }
            
            btnScan.setOnClickListener {
                if (isScanning) {
                    stopScan()
                } else {
                    startScan()
                }
            }
        }
    }
    
    private fun setupRecyclerView() {
        adapter = DeviceAdapter(deviceList) { device ->
            stopScan()
            connectToDevice(device)
        }
        
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        
        updateEmptyState()
    }
    
    private fun startScan() {
        Log.d(TAG, "Starting BLE scan")
        deviceList.clear()
        adapter.notifyDataSetChanged()
        updateEmptyState()
        
        isScanning = true
        binding.btnScan.text = "Stop Scanning"
        binding.progressBar.visibility = View.VISIBLE
        
        // Reset and start scan using BleScannerHelper
        BleScannerHelper.getInstance().reSetCallback()
        BleScannerHelper.getInstance().scanDevice(this, null, bleScanCallback)
        
        // Set timeout
        handler.removeCallbacks(stopScanRunnable)
        handler.postDelayed(stopScanRunnable, SCAN_TIMEOUT_MS)
    }
    
    private fun stopScan() {
        Log.d(TAG, "Stopping BLE scan")
        handler.removeCallbacks(stopScanRunnable)
        BleScannerHelper.getInstance().stopScan(this)
        isScanning = false
        binding.btnScan.text = "Scan for Glasses"
        if (!isConnecting) {
            binding.progressBar.visibility = View.GONE
        }
    }
    
    private fun connectToDevice(device: ScannedDevice) {
        Log.d(TAG, "Connecting to: ${device.name} (${device.address})")
        
        isConnecting = true
        connectingDeviceName = device.name
        
        binding.progressBar.visibility = View.VISIBLE
        binding.btnScan.isEnabled = false
        
        Toast.makeText(this, "Connecting to ${device.name}...", Toast.LENGTH_SHORT).show()
        
        // Store device address and connect
        DeviceManager.getInstance().deviceAddress = device.address
        ConnectionManager.startConnecting()  // Mark connecting in global manager
        BleOperateManager.getInstance().connectDirectly(device.address)
        
        // Set connection timeout
        handler.removeCallbacks(connectTimeoutRunnable)
        handler.postDelayed(connectTimeoutRunnable, CONNECT_TIMEOUT_MS)
    }
    
    private fun updateEmptyState() {
        binding.tvEmpty.visibility = if (deviceList.isEmpty()) View.VISIBLE else View.GONE
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
        handler.removeCallbacks(stopScanRunnable)
        handler.removeCallbacks(connectTimeoutRunnable)
        stopScan()
        
        // Unregister callbacks
        GlassesBluetoothReceiver.connectionCallback = null
        try {
            if (EventBus.getDefault().isRegistered(this)) {
                EventBus.getDefault().unregister(this)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister EventBus", e)
        }
    }
    
    // Data class for scanned devices
    data class ScannedDevice(
        val name: String,
        val address: String,
        val rssi: Int
    )
    
    // RecyclerView Adapter
    inner class DeviceAdapter(
        private val devices: List<ScannedDevice>,
        private val onItemClick: (ScannedDevice) -> Unit
    ) : RecyclerView.Adapter<DeviceAdapter.ViewHolder>() {
        
        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tvDeviceName)
            val tvAddress: TextView = view.findViewById(R.id.tvDeviceAddress)
        }
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_device, parent, false)
            return ViewHolder(view)
        }
        
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val device = devices[position]
            holder.tvName.text = "${device.name} (${device.rssi} dBm)"
            holder.tvAddress.text = device.address
            holder.itemView.setOnClickListener { onItemClick(device) }
        }
        
        override fun getItemCount() = devices.size
    }
}
