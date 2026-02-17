package com.glassesai.glasses

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.bluetooth.DeviceManager
import com.oudmon.ble.base.communication.LargeDataHandler
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyListener
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyRsp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages connection and communication with G300 smart glasses via BLE.
 * Uses the HeyCyan SDK (glasses_sdk.aar)
 * 
 * IMPORTANT: Following the exact pattern from the HeyCyan SDK sample:
 * 1. Register notification listener FIRST
 * 2. Send command to trigger photo
 * 3. Wait for 0x02 notification
 * 4. Call getPictureThumbnails() to receive image data
 * 
 * NOTE: This is a SINGLETON to ensure callbacks persist across activities.
 */
class GlassesManager private constructor(private var context: Context) {
    
    companion object {
        private const val TAG = "GlassesManager"
        private const val LISTENER_ID = 100
        
        // Commands from HeyCyan SDK
        private val CMD_TAKE_PHOTO = byteArrayOf(0x02, 0x01, 0x01)
        private val CMD_GET_MEDIA_COUNT = byteArrayOf(0x02, 0x04)
        
        // AI Photo command: 0x02, 0x01, 0x06, thumbnailSize, thumbnailSize, 0x02
        // thumbnailSize: 0-6 (higher = better quality)
        private const val THUMBNAIL_SIZE: Byte = 0x02
        
        @Volatile
        private var instance: GlassesManager? = null
        
        fun getInstance(context: Context): GlassesManager {
            return instance ?: synchronized(this) {
                instance ?: GlassesManager(context.applicationContext).also { 
                    instance = it 
                    Log.d(TAG, "Created GlassesManager singleton instance")
                }
            }
        }
    }
    
    /**
     * Update context (needed when activity changes)
     */
    fun updateContext(newContext: Context) {
        this.context = newContext.applicationContext
    }
    
    // Connection state
    enum class ConnectionState {
        DISCONNECTED,
        SCANNING,
        CONNECTING,
        CONNECTED
    }
    
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    
    private val _batteryLevel = MutableStateFlow(0)
    val batteryLevel: StateFlow<Int> = _batteryLevel.asStateFlow()
    
    private val _isCharging = MutableStateFlow(false)
    val isCharging: StateFlow<Boolean> = _isCharging.asStateFlow()
    
    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    val connectedDeviceName: StateFlow<String?> = _connectedDeviceName.asStateFlow()
    
    // Callbacks
    var onImageReceived: ((ByteArray) -> Unit)? = null
    var onAIPhotoTriggered: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onMicrophoneActivated: (() -> Unit)? = null
    
    private var listenerRegistered = false
    
    // Buffer to accumulate image data chunks
    private val imageDataBuffer = mutableListOf<Byte>()
    private var isReceivingImage = false
    
    /**
     * Device notification listener - THIS IS THE KEY PART
     * When glasses take an AI photo, they send a 0x02 notification
     * Then we call getPictureThumbnails() to get the actual image data
     */
    private val deviceNotifyListener = object : GlassesDeviceNotifyListener() {
        override fun parseData(cmdType: Int, response: GlassesDeviceNotifyRsp) {
            try {
                if (response.loadData == null || response.loadData.size < 7) {
                    Log.w(TAG, "Invalid notification data - too short")
                    return
                }
                
                val eventType = response.loadData[6].toInt() and 0xFF
                Log.d(TAG, "=== NOTIFICATION RECEIVED ===")
                Log.d(TAG, "cmdType: $cmdType, eventType: 0x${String.format("%02X", eventType)}")
                Log.d(TAG, "loadData size: ${response.loadData.size}")
                Log.d(TAG, "loadData hex: ${response.loadData.take(15).joinToString(" ") { String.format("%02X", it) }}")
                
                when (eventType) {
                    // Battery status (0x05)
                    0x05 -> {
                        val battery = response.loadData.getOrNull(7)?.toInt()?.and(0xFF) ?: 0
                        val charging = (response.loadData.getOrNull(8)?.toInt()?.and(0xFF) ?: 0) == 1
                        _batteryLevel.value = battery
                        _isCharging.value = charging
                        Log.d(TAG, "Battery: $battery%, Charging: $charging")
                    }
                    
                    // AI Photo notification (0x02) - THIS IS THE CRITICAL ONE
                    0x02 -> {
                        Log.d(TAG, ">>> AI PHOTO NOTIFICATION RECEIVED <<<")
                        
                        // Check loadData[9] for AI photo type
                        if (response.loadData.size > 9) {
                            val subType = response.loadData[9].toInt() and 0xFF
                            Log.d(TAG, "AI photo subType: 0x${String.format("%02X", subType)}")
                            
                            if (subType == 0x02) {
                                Log.d(TAG, "AI photo ready (subType=0x02), notifying...")
                                onAIPhotoTriggered?.invoke()
                            }
                        }
                        
                        // IMPORTANT: Call getPictureThumbnails to get the actual image
                        Log.d(TAG, "Calling getPictureThumbnails()...")
                        fetchThumbnailNow()
                    }
                    
                    // Microphone event (0x03)
                    0x03 -> {
                        val micState = response.loadData.getOrNull(7)?.toInt()?.and(0xFF) ?: 0
                        Log.d(TAG, "Microphone event - state: $micState")
                        if (micState == 1) {
                            Log.d(TAG, "Glasses microphone ACTIVATED")
                            onMicrophoneActivated?.invoke()
                        }
                    }
                    
                    // OTA progress (0x04)
                    0x04 -> {
                        Log.d(TAG, "OTA progress notification")
                    }
                    
                    else -> {
                        Log.d(TAG, "Unknown event type: 0x${String.format("%02X", eventType)}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing notification", e)
            }
        }
    }
    
    /**
     * Initialize the glasses manager - MUST be called before using
     */
    fun initialize() {
        Log.d(TAG, "Initializing GlassesManager")
        
        // Register notification listener - THIS MUST BE DONE FIRST
        if (!listenerRegistered) {
            try {
                // Remove any existing listener first
                try {
                    LargeDataHandler.getInstance().removeOutDeviceListener(LISTENER_ID)
                } catch (e: Exception) {
                    // Ignore - might not exist
                }
                
                LargeDataHandler.getInstance().addOutDeviceListener(LISTENER_ID, deviceNotifyListener)
                listenerRegistered = true
                Log.d(TAG, "=== Device notification listener registered with ID: $LISTENER_ID ===")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register listener", e)
            }
        } else {
            Log.d(TAG, "Listener already registered")
        }
    }
    
    /**
     * Re-register the listener (call after connection established)
     */
    fun reRegisterListener() {
        Log.d(TAG, "Re-registering notification listener")
        listenerRegistered = false
        initialize()
    }
    
    /**
     * Fetch the thumbnail image data from glasses.
     * The SDK returns data in chunks - we need to accumulate them.
     * success=false means more data is coming, success=true means transfer complete.
     */
    private fun fetchThumbnailNow() {
        Log.d(TAG, ">>> Fetching thumbnail NOW <<<")
        
        // Don't clear buffer if we're already receiving - just continue
        synchronized(imageDataBuffer) {
            if (isReceivingImage && imageDataBuffer.isNotEmpty()) {
                Log.d(TAG, "Already receiving image (${imageDataBuffer.size} bytes), not clearing buffer")
                return  // Don't start a new fetch, let the current one complete
            }
            imageDataBuffer.clear()
            isReceivingImage = true
        }
        
        try {
            LargeDataHandler.getInstance().getPictureThumbnails { cmdType, success, data ->
                // Reduce log verbosity during transfer
                if (success) {
                    Log.d(TAG, "getPictureThumbnails complete: success=$success, dataSize=${data?.size ?: 0}")
                }
                
                if (data != null && data.isNotEmpty()) {
                    synchronized(imageDataBuffer) {
                        // Accumulate the data chunk
                        imageDataBuffer.addAll(data.toList())
                        // Log progress every 5KB instead of every chunk
                        if (imageDataBuffer.size % 5000 < 1100) {
                            Log.d(TAG, "Receiving image: ${imageDataBuffer.size} bytes...")
                        }
                        
                        if (success) {
                            // Transfer complete - process the full image
                            isReceivingImage = false
                            val fullImageData = imageDataBuffer.toByteArray()
                            imageDataBuffer.clear()
                            
                            Log.d(TAG, "Transfer COMPLETE! Total image: ${fullImageData.size} bytes")
                            
                            if (fullImageData.size > 100) {
                                Log.d(TAG, "First 20 bytes: ${fullImageData.take(20).joinToString(" ") { String.format("%02X", it) }}")
                                Log.d(TAG, "Last 10 bytes: ${fullImageData.takeLast(10).joinToString(" ") { String.format("%02X", it) }}")
                                
                                // Check for valid JPEG markers
                                val hasJpegStart = fullImageData.size > 2 && 
                                    fullImageData[0] == 0xFF.toByte() && 
                                    fullImageData[1] == 0xD8.toByte()
                                val hasJpegEnd = fullImageData.size > 2 &&
                                    fullImageData[fullImageData.size - 2] == 0xFF.toByte() &&
                                    fullImageData[fullImageData.size - 1] == 0xD9.toByte()
                                    
                                Log.d(TAG, "JPEG start marker: $hasJpegStart, JPEG end marker: $hasJpegEnd")
                                
                                // Try to find JPEG start marker in the data (in case of offset)
                                var jpegStartIndex = -1
                                for (i in 0 until minOf(fullImageData.size - 1, 100)) {
                                    if (fullImageData[i] == 0xFF.toByte() && fullImageData[i + 1] == 0xD8.toByte()) {
                                        jpegStartIndex = i
                                        break
                                    }
                                }
                                
                                val finalImageData = if (jpegStartIndex > 0) {
                                    Log.d(TAG, "Found JPEG start at offset $jpegStartIndex, trimming")
                                    fullImageData.copyOfRange(jpegStartIndex, fullImageData.size)
                                } else if (!hasJpegStart && hasJpegEnd && fullImageData.size > 1000) {
                                    // Image has valid end but missing start - try to use Android's decoder anyway
                                    // It might be able to recover
                                    Log.d(TAG, "Missing JPEG start marker, but has end marker. Passing to decoder anyway.")
                                    fullImageData
                                } else {
                                    fullImageData
                                }
                                
                                if (finalImageData.size > 500) {
                                    // Pass to callback - let BitmapFactory try to decode it
                                    Log.d(TAG, "Invoking onImageReceived with ${finalImageData.size} bytes")
                                    onImageReceived?.invoke(finalImageData) ?: run {
                                        Log.e(TAG, "ERROR: onImageReceived callback is NULL!")
                                    }
                                } else {
                                    Log.e(TAG, "Image data too small: ${finalImageData.size}")
                                    onError?.invoke("Invalid image data received")
                                }
                            } else {
                                Log.e(TAG, "Image too small: ${fullImageData.size} bytes")
                                onError?.invoke("Image too small (${fullImageData.size} bytes)")
                            }
                        } else {
                            // More data coming - don't log every chunk
                        }
                    }
                } else if (success) {
                    // success=true but no data - check if we have buffered data
                    synchronized(imageDataBuffer) {
                        if (imageDataBuffer.isNotEmpty()) {
                            isReceivingImage = false
                            val fullImageData = imageDataBuffer.toByteArray()
                            imageDataBuffer.clear()
                            
                            Log.d(TAG, "Final callback with buffered data: ${fullImageData.size} bytes")
                            if (fullImageData.size > 100) {
                                onImageReceived?.invoke(fullImageData)
                            } else {
                                Log.e(TAG, "Buffered image too small: ${fullImageData.size}")
                            }
                        } else {
                            Log.e(TAG, "Transfer complete but no data received")
                            onError?.invoke("No image data received")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in getPictureThumbnails", e)
            onError?.invoke("Error: ${e.message}")
        }
    }
    
    /**
     * Trigger AI photo capture on the glasses
     */
    fun captureAIPhoto(onSuccess: () -> Unit = {}, onFail: (String) -> Unit = {}) {
        Log.d(TAG, "=== CAPTURING AI PHOTO ===")
        
        // Command bytes: 0x02, 0x01, 0x06, thumbnailSize, thumbnailSize, 0x02
        val command = byteArrayOf(
            0x02, 
            0x01, 
            0x06, 
            THUMBNAIL_SIZE, 
            THUMBNAIL_SIZE, 
            0x02
        )
        Log.d(TAG, "Sending command: ${command.joinToString(" ") { String.format("%02X", it) }}")
        
        LargeDataHandler.getInstance().glassesControl(command) { cmdType, response ->
            Log.d(TAG, "Command response - cmdType: $cmdType, dataType: ${response.dataType}, errorCode: ${response.errorCode}, workTypeIng: ${response.workTypeIng}")
            
            if (response.dataType == 1) {
                when (response.workTypeIng) {
                    1, 6 -> Log.d(TAG, "Glasses in photo/AI mode - SUCCESS")
                    2 -> Log.w(TAG, "Glasses recording video - can't take photo")
                    4 -> Log.w(TAG, "Glasses in transfer mode")
                    5 -> Log.w(TAG, "Glasses in OTA mode")
                    7 -> Log.d(TAG, "Glasses in AI conversation mode - SUCCESS")
                    8 -> Log.w(TAG, "Glasses recording audio")
                    else -> Log.d(TAG, "Glasses workType: ${response.workTypeIng}")
                }
                
                // Command sent successfully - thumbnail will arrive via notification
                Log.d(TAG, "Photo command accepted. Waiting for 0x02 notification...")
                onSuccess()
            } else {
                Log.e(TAG, "Photo command failed - dataType: ${response.dataType}")
                onFail("Photo command failed")
            }
        }
    }
    
    /**
     * Take a regular photo (not AI photo)
     */
    fun takePhoto(onSuccess: () -> Unit = {}, onFail: (String) -> Unit = {}) {
        Log.d(TAG, "Taking regular photo")
        LargeDataHandler.getInstance().glassesControl(CMD_TAKE_PHOTO) { _, response ->
            if (response.dataType == 1 && response.errorCode == 0) {
                Log.d(TAG, "Photo taken successfully")
                onSuccess()
            } else {
                val error = "Failed to take photo, error: ${response.errorCode}"
                Log.e(TAG, error)
                onFail(error)
            }
        }
    }
    
    /**
     * Manually fetch thumbnail - call this if automatic fetch fails
     */
    fun fetchLatestThumbnail() {
        Log.d(TAG, "Manual thumbnail fetch requested")
        fetchThumbnailNow()
    }
    
    // Connection management
    fun setScanning(scanning: Boolean) {
        _connectionState.value = if (scanning) ConnectionState.SCANNING else ConnectionState.DISCONNECTED
    }
    
    fun connectToDevice(deviceAddress: String) {
        Log.d(TAG, "Connecting to device: $deviceAddress")
        _connectionState.value = ConnectionState.CONNECTING
        DeviceManager.getInstance().deviceAddress = deviceAddress
        BleOperateManager.getInstance().connectDirectly(deviceAddress)
    }
    
    fun onConnected(deviceName: String?) {
        Log.d(TAG, "Connected to: $deviceName")
        _connectionState.value = ConnectionState.CONNECTED
        _connectedDeviceName.value = deviceName
        
        // Re-register notification listener after connection
        reRegisterListener()
        
        // Sync time and get battery
        syncTime()
        getBatteryStatus()
    }
    
    fun onDisconnected() {
        Log.d(TAG, "Disconnected from glasses")
        _connectionState.value = ConnectionState.DISCONNECTED
        _connectedDeviceName.value = null
    }
    
    fun disconnect() {
        Log.d(TAG, "Disconnecting from glasses")
        BleOperateManager.getInstance().unBindDevice()
    }
    
    fun isConnected(): Boolean = BleOperateManager.getInstance().isConnected
    
    fun syncTime() {
        Log.d(TAG, "Syncing time with glasses")
        LargeDataHandler.getInstance().syncTime { _, _ -> 
            Log.d(TAG, "Time synced")
        }
    }
    
    fun getBatteryStatus() {
        Log.d(TAG, "Getting battery status")
        LargeDataHandler.getInstance().addBatteryCallBack("glassesai") { _, response ->
            if (response != null) {
                Log.d(TAG, "Battery callback received")
            }
        }
        LargeDataHandler.getInstance().syncBattery()
    }
    
    fun getMediaCount(onResult: (photos: Int, videos: Int, audio: Int) -> Unit) {
        Log.d(TAG, "Getting media count")
        LargeDataHandler.getInstance().glassesControl(CMD_GET_MEDIA_COUNT) { _, response ->
            if (response.dataType == 4) {
                val photos = response.imageCount
                val videos = response.videoCount
                val audio = response.recordCount
                Log.d(TAG, "Media count - Photos: $photos, Videos: $videos, Audio: $audio")
                onResult(photos, videos, audio)
            }
        }
    }
    
    fun bytesToBitmap(data: ByteArray): Bitmap? {
        return try {
            BitmapFactory.decodeByteArray(data, 0, data.size)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode image", e)
            null
        }
    }
    
    fun cleanup() {
        Log.d(TAG, "Cleaning up GlassesManager")
        // Note: NOT clearing callbacks since this is a singleton and 
        // callbacks should persist across activity changes
        // onImageReceived = null
        // onAIPhotoTriggered = null
        // onError = null
        // onMicrophoneActivated = null
    }
}
