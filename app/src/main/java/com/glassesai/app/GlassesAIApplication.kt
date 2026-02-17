package com.glassesai.app

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.glassesai.glasses.BluetoothEvent
import com.glassesai.glasses.GlassesBluetoothReceiver
import com.oudmon.ble.base.bluetooth.BleAction
import com.oudmon.ble.base.bluetooth.BleBaseControl
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.bluetooth.DeviceManager
import com.oudmon.ble.base.communication.LargeDataHandler
import org.greenrobot.eventbus.EventBus

class GlassesAIApplication : Application() {
    
    companion object {
        private const val TAG = "GlassesAI"
        
        lateinit var instance: GlassesAIApplication
            private set
            
        lateinit var CONTEXT: Context
            private set
    }
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        CONTEXT = applicationContext
        Log.i(TAG, "GlassesAI Application initialized")
        
        initBle()
    }
    
    private fun initBle() {
        try {
            // Initialize LargeDataHandler first
            LargeDataHandler.getInstance()
            
            // Initialize BleOperateManager with context
            BleOperateManager.getInstance(this)
            BleOperateManager.getInstance().setApplication(this)
            BleOperateManager.getInstance().init()
            Log.i(TAG, "BleOperateManager initialized")
            
            // Register the BLE callback receiver for service discovery events
            val intentFilter = BleAction.getIntentFilter()
            val bleReceiver = GlassesBluetoothReceiver()
            LocalBroadcastManager.getInstance(CONTEXT)
                .registerReceiver(bleReceiver, intentFilter)
            Log.i(TAG, "GlassesBluetoothReceiver registered with LocalBroadcastManager")
            
            // Register Bluetooth state receiver
            val deviceFilter = getDeviceIntentFilter()
            val deviceReceiver = SystemBluetoothReceiver()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                registerReceiver(deviceReceiver, deviceFilter, RECEIVER_EXPORTED)
            } else {
                registerReceiver(deviceReceiver, deviceFilter)
            }
            Log.i(TAG, "SystemBluetoothReceiver registered")
            
            // Initialize BleBaseControl
            BleBaseControl.getInstance(CONTEXT).setmContext(this)
            Log.i(TAG, "BleBaseControl initialized")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize BLE", e)
        }
    }
    
    private fun getDeviceIntentFilter(): IntentFilter {
        val intentFilter = IntentFilter()
        intentFilter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        intentFilter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        intentFilter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
        intentFilter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        return intentFilter
    }
    
    /**
     * System Bluetooth state receiver
     */
    inner class SystemBluetoothReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val connectState = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
                    if (connectState == BluetoothAdapter.STATE_OFF) {
                        Log.i(TAG, "Bluetooth turned OFF")
                        BleOperateManager.getInstance().setBluetoothTurnOff(false)
                        BleOperateManager.getInstance().disconnect()
                        EventBus.getDefault().post(BluetoothEvent(false))
                    } else if (connectState == BluetoothAdapter.STATE_ON) {
                        Log.i(TAG, "Bluetooth turned ON")
                        BleOperateManager.getInstance().setBluetoothTurnOff(true)
                        // Try to reconnect if we have a saved device
                        val savedAddress = DeviceManager.getInstance().deviceAddress
                        if (!savedAddress.isNullOrEmpty()) {
                            BleOperateManager.getInstance().reConnectMac = savedAddress
                            BleOperateManager.getInstance().connectDirectly(savedAddress)
                        }
                    }
                }
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    Log.i(TAG, "ACL Connected")
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    Log.i(TAG, "ACL Disconnected")
                }
            }
        }
    }
}
