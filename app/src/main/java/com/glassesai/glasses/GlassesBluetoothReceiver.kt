package com.glassesai.glasses

import android.bluetooth.BluetoothDevice
import android.util.Log
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.bluetooth.DeviceManager
import com.oudmon.ble.base.bluetooth.QCBluetoothCallbackCloneReceiver
import com.oudmon.ble.base.communication.LargeDataHandler
import org.greenrobot.eventbus.EventBus

/**
 * Bluetooth callback receiver for G300 glasses connection events.
 * Based on HeyCyan SDK sample's MyBluetoothReceiver
 */
class GlassesBluetoothReceiver : QCBluetoothCallbackCloneReceiver() {
    
    companion object {
        private const val TAG = "GlassesBluetoothReceiver"
        
        // Static callback for connection state changes
        var connectionCallback: ConnectionCallback? = null
    }
    
    interface ConnectionCallback {
        fun onConnected(deviceName: String?)
        fun onDisconnected()
    }
    
    override fun connectStatue(device: BluetoothDevice?, connected: Boolean) {
        Log.d(TAG, "connectStatue: device=${device?.name}, connected=$connected")
        
        if (device != null && connected) {
            if (device.name != null) {
                DeviceManager.getInstance().deviceName = device.name
            }
        } else {
            // Disconnected
            Log.d(TAG, "Device disconnected")
            EventBus.getDefault().post(BluetoothEvent(false))
            connectionCallback?.onDisconnected()
            ConnectionManager.onDisconnected()
        }
    }
    
    override fun onServiceDiscovered() {
        Log.d(TAG, "onServiceDiscovered - BLE services ready")
        
        // Initialize the LargeDataHandler - required before sending any commands
        try {
            LargeDataHandler.getInstance().initEnable()
            Log.d(TAG, "LargeDataHandler initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize LargeDataHandler", e)
        }
        
        // Mark BLE as ready
        BleOperateManager.getInstance().isReady = true
        
        // Get device name
        val deviceName = DeviceManager.getInstance().deviceName
        Log.d(TAG, "Connection complete! Device: $deviceName")
        
        // Post connection success event via EventBus
        Log.d(TAG, "Posting BluetoothEvent(true)")
        EventBus.getDefault().post(BluetoothEvent(true))
        
        // Also call direct callback (more reliable than EventBus)
        Log.d(TAG, "Calling connectionCallback?.onConnected($deviceName)")
        connectionCallback?.onConnected(deviceName)
        
        // Update global connection manager
        ConnectionManager.onConnected(deviceName)
    }
    
    override fun onCharacteristicChange(address: String?, uuid: String?, data: ByteArray?) {
        // Data received from device - handled by LargeDataHandler
    }
    
    override fun onCharacteristicRead(uuid: String?, data: ByteArray?) {
        // Characteristic read callback
        if (uuid != null && data != null) {
            val value = String(data, Charsets.UTF_8)
            Log.d(TAG, "onCharacteristicRead: uuid=$uuid, value=$value")
        }
    }
}
