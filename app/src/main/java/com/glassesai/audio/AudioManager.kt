package com.glassesai.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.*
import android.util.Log
import com.glassesai.app.Config
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Manages audio capture (microphone) and playback (speaker) for Gemini Live.
 * Uses Bluetooth SCO/HFP to capture audio from glasses microphone.
 */
class AudioManager(private val context: Context) {
    
    companion object {
        private const val TAG = "AudioManager"
        
        // Buffer sizes
        private const val CAPTURE_BUFFER_SIZE = 3200 // 100ms at 16kHz mono Int16
        private const val PLAYBACK_BUFFER_SIZE = 4800 // 100ms at 24kHz mono Int16
    }
    
    var onAudioCaptured: ((ByteArray) -> Unit)? = null
    
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var isCapturing = false
    private var isPlaying = false
    private var scoStarted = false
    
    private val systemAudioManager: android.media.AudioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
    }
    
    private val captureScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var captureJob: Job? = null
    
    // Audio accumulator for sending chunks
    private val accumulatedData = mutableListOf<Byte>()
    private val minSendBytes = 3200 // 100ms at 16kHz mono Int16
    
    /**
     * Setup audio session for voice chat with glasses.
     * Uses Bluetooth SCO to route audio through glasses microphone.
     */
    @SuppressLint("MissingPermission")
    fun setupAudioSession(useBluetoothSco: Boolean = true): Boolean {
        try {
            Log.d(TAG, "Setting up audio session, Bluetooth SCO: $useBluetoothSco")
            
            // Start Bluetooth SCO to use glasses microphone
            if (useBluetoothSco) {
                startBluetoothSco()
            }
            
            // Create AudioRecord for microphone input (will use SCO if available)
            val minBufferSize = AudioRecord.getMinBufferSize(
                Config.INPUT_AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            
            // Use VOICE_COMMUNICATION source - this will use Bluetooth SCO mic when SCO is active
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                Config.INPUT_AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBufferSize, CAPTURE_BUFFER_SIZE * 2)
            )
            
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "Failed to initialize AudioRecord")
                return false
            }
            
            // Create AudioTrack for speaker output
            val playbackMinBuffer = AudioTrack.getMinBufferSize(
                Config.OUTPUT_AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            
            val audioFormat = AudioFormat.Builder()
                .setSampleRate(Config.OUTPUT_AUDIO_SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build()
            
            audioTrack = AudioTrack(
                audioAttributes,
                audioFormat,
                maxOf(playbackMinBuffer, PLAYBACK_BUFFER_SIZE * 4),
                AudioTrack.MODE_STREAM,
                android.media.AudioManager.AUDIO_SESSION_ID_GENERATE
            )
            
            if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "Failed to initialize AudioTrack")
                return false
            }
            
            Log.d(TAG, "Audio session setup complete")
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up audio session", e)
            return false
        }
    }
    
    /**
     * Start Bluetooth SCO to route audio through glasses microphone
     */
    @SuppressLint("MissingPermission")
    private fun startBluetoothSco() {
        try {
            // Check if Bluetooth SCO is available
            if (!systemAudioManager.isBluetoothScoAvailableOffCall) {
                Log.w(TAG, "Bluetooth SCO not available off call")
            }
            
            // Set audio mode to communication for SCO
            systemAudioManager.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
            
            // Start Bluetooth SCO connection
            systemAudioManager.startBluetoothSco()
            systemAudioManager.isBluetoothScoOn = true
            scoStarted = true
            
            Log.d(TAG, "Bluetooth SCO started - using glasses microphone")
            
            // Log available audio devices
            logAudioDevices()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error starting Bluetooth SCO", e)
        }
    }
    
    /**
     * Stop Bluetooth SCO
     */
    @SuppressLint("MissingPermission")
    private fun stopBluetoothSco() {
        try {
            if (scoStarted) {
                systemAudioManager.isBluetoothScoOn = false
                systemAudioManager.stopBluetoothSco()
                systemAudioManager.mode = android.media.AudioManager.MODE_NORMAL
                scoStarted = false
                Log.d(TAG, "Bluetooth SCO stopped")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping Bluetooth SCO", e)
        }
    }
    
    /**
     * Log available audio devices for debugging
     */
    private fun logAudioDevices() {
        try {
            val devices = systemAudioManager.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS)
            Log.d(TAG, "Available input devices:")
            devices.forEach { device ->
                Log.d(TAG, "  - ${device.productName} (type: ${device.type}, address: ${device.address})")
            }
            
            val outputDevices = systemAudioManager.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
            Log.d(TAG, "Available output devices:")
            outputDevices.forEach { device ->
                Log.d(TAG, "  - ${device.productName} (type: ${device.type}, address: ${device.address})")
            }
            
            Log.d(TAG, "Bluetooth SCO available: ${systemAudioManager.isBluetoothScoAvailableOffCall}")
            Log.d(TAG, "Bluetooth SCO on: ${systemAudioManager.isBluetoothScoOn}")
            Log.d(TAG, "Audio mode: ${systemAudioManager.mode}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error logging audio devices", e)
        }
    }
    
    /**
     * Start capturing audio from microphone
     */
    fun startCapture() {
        if (isCapturing) {
            Log.d(TAG, "Already capturing")
            return
        }
        
        Log.d(TAG, "Starting audio capture")
        
        try {
            audioRecord?.startRecording()
            isCapturing = true
            
            // Start playback track
            audioTrack?.play()
            isPlaying = true
            
            // Start capture loop
            captureJob = captureScope.launch {
                val buffer = ShortArray(CAPTURE_BUFFER_SIZE / 2) // Short = 2 bytes
                
                while (isActive && isCapturing) {
                    val readResult = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    
                    if (readResult > 0) {
                        // Convert Short array to ByteArray (little-endian)
                        val byteBuffer = ByteBuffer.allocate(readResult * 2)
                        byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
                        for (i in 0 until readResult) {
                            byteBuffer.putShort(buffer[i])
                        }
                        
                        val pcmData = byteBuffer.array()
                        
                        // Accumulate data
                        synchronized(accumulatedData) {
                            accumulatedData.addAll(pcmData.toList())
                            
                            // Send when we have enough data
                            if (accumulatedData.size >= minSendBytes) {
                                val chunk = accumulatedData.toByteArray()
                                accumulatedData.clear()
                                onAudioCaptured?.invoke(chunk)
                            }
                        }
                    }
                }
            }
            
            Log.d(TAG, "Audio capture started")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error starting capture", e)
            isCapturing = false
        }
    }
    
    /**
     * Stop capturing audio
     */
    fun stopCapture() {
        Log.d(TAG, "Stopping audio capture")
        
        isCapturing = false
        captureJob?.cancel()
        captureJob = null
        
        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecord", e)
        }
        
        try {
            audioTrack?.stop()
            isPlaying = false
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioTrack", e)
        }
        
        // Flush any remaining accumulated audio
        synchronized(accumulatedData) {
            if (accumulatedData.isNotEmpty()) {
                val chunk = accumulatedData.toByteArray()
                accumulatedData.clear()
                onAudioCaptured?.invoke(chunk)
            }
        }
        
        Log.d(TAG, "Audio capture stopped")
    }
    
    /**
     * Play audio data (from Gemini response)
     */
    fun playAudio(data: ByteArray) {
        if (data.isEmpty()) return
        
        try {
            // Ensure track is playing
            if (audioTrack?.playState != AudioTrack.PLAYSTATE_PLAYING) {
                audioTrack?.play()
                isPlaying = true
            }
            
            // Write audio data to track
            audioTrack?.write(data, 0, data.size)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error playing audio", e)
        }
    }
    
    /**
     * Stop playback (for interruption)
     */
    fun stopPlayback() {
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.play() // Resume for future playback
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping playback", e)
        }
    }
    
    /**
     * Check if currently capturing
     */
    fun isCapturing(): Boolean = isCapturing
    
    /**
     * Release all audio resources
     */
    fun release() {
        Log.d(TAG, "Releasing audio resources")
        
        stopCapture()
        
        // Stop Bluetooth SCO
        stopBluetoothSco()
        
        try {
            audioRecord?.release()
            audioRecord = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing AudioRecord", e)
        }
        
        try {
            audioTrack?.release()
            audioTrack = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing AudioTrack", e)
        }
        
        captureScope.cancel()
        onAudioCaptured = null
        
        Log.d(TAG, "Audio resources released")
    }
}
