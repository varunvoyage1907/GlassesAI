package com.glassesai.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.glassesai.app.Config
import com.glassesai.app.databinding.ActivitySessionBinding
import com.glassesai.glasses.GlassesManager
import com.glassesai.openclaw.OpenClawVoiceService
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * OpenClaw-powered AI session activity.
 * 
 * Uses Android's built-in Speech-to-Text and Text-to-Speech with OpenClaw HTTP API.
 * This approach:
 * 1. User speaks -> Android STT -> Text to OpenClaw -> AI response -> Android TTS -> User hears
 * 2. Image capture -> OpenClaw HTTP API -> AI analyzes and describes
 * 
 * OpenClaw is the only AI brain - it internally uses Gemini for processing.
 */
class OpenClawSessionActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "OpenClawSession"
    }
    
    private lateinit var binding: ActivitySessionBinding
    
    // Components
    private var glassesManager: GlassesManager? = null
    private var voiceService: OpenClawVoiceService? = null
    
    // State
    private var isSessionActive = false
    private var lastCapturedImage: ByteArray? = null
    
    // Pending tool calls
    private var pendingPhotoCallId: String? = null
    private var pendingTimeoutHandler: android.os.Handler? = null
    private var pendingTimeoutRunnable: Runnable? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate called")
        binding = ActivitySessionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Permissions are already granted by MainActivity
        // Just initialize directly
        initializeComponents()
        setupUI()
        observeState()
        Log.d(TAG, "Activity fully initialized")
    }
    
    private fun initializeComponents() {
        // Initialize glasses manager
        val glasses = GlassesManager.getInstance(this)
        glasses.updateContext(this)
        glasses.initialize()
        glassesManager = glasses
        
        // Initialize voice service
        voiceService = OpenClawVoiceService(this)
        
        // Wire up glasses image callback
        glasses.onImageReceived = { imageData ->
            Log.d(TAG, ">>> IMAGE RECEIVED from glasses: ${imageData.size} bytes")
            
            // Cancel timeout
            pendingTimeoutRunnable?.let { runnable ->
                pendingTimeoutHandler?.removeCallbacks(runnable)
                Log.d(TAG, "Cancelled timeout - image arrived")
            }
            pendingTimeoutHandler = null
            pendingTimeoutRunnable = null
            
            // Store image
            lastCapturedImage = imageData
            
            // Update UI
            runOnUiThread {
                val bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.size)
                if (bitmap != null) {
                    binding.ivPreview.setImageBitmap(bitmap)
                    binding.ivPreview.visibility = View.VISIBLE
                    binding.tvStatus.text = "Image captured - analyzing..."
                }
            }
            
            // If we have a pending tool call, send the image to OpenClaw
            pendingPhotoCallId?.let { callId ->
                Log.d(TAG, ">>> Sending image to OpenClaw for analysis (callId: $callId)")
                pendingPhotoCallId = null
                
                // Compress image
                val jpegData = compressImage(imageData)
                
                // Send image directly to OpenClaw with prompt
                voiceService?.sendImage(jpegData, "I just captured this image from my smart glasses. Please describe exactly what you see in this image.")
                
                runOnUiThread {
                    binding.tvStatus.text = "AI analyzing image..."
                }
            }
        }
        
        // Wire up glasses AI photo trigger
        glasses.onAIPhotoTriggered = {
            Log.d(TAG, "AI photo triggered from glasses")
            runOnUiThread {
                binding.tvStatus.text = "Photo triggered, waiting for image..."
            }
        }
        
        // Wire up error callback
        glasses.onError = { error ->
            Log.e(TAG, "Glasses error: $error")
            runOnUiThread {
                Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun setupUI() {
        binding.apply {
            toolbar.setNavigationOnClickListener {
                stopSession()
                finish()
            }
            
            btnSession.setOnClickListener {
                if (isSessionActive) {
                    stopSession()
                } else {
                    startSession()
                }
            }
            
            btnCapture.setOnClickListener {
                capturePhoto()
            }
            
            btnCapture.isEnabled = false
            
            // Update UI to show initial state
            tvConnectionState.text = "Ready"
            tvConnectionState.setTextColor(getColor(android.R.color.holo_blue_light))
            tvStatus.text = "Tap 'Start Session' to begin"
        }
    }
    
    private fun observeState() {
        val service = voiceService ?: return
        
        lifecycleScope.launch {
            service.state.collectLatest { state ->
                updateStateUI(state)
            }
        }
        
        lifecycleScope.launch {
            service.isConnected.collectLatest { connected ->
                if (connected && isSessionActive) {
                    binding.tvConnectionState.text = "Connected to OpenClaw"
                    binding.tvConnectionState.setTextColor(getColor(android.R.color.holo_green_light))
                } else if (!connected && isSessionActive) {
                    binding.tvConnectionState.text = "Disconnected"
                    binding.tvConnectionState.setTextColor(getColor(android.R.color.holo_red_light))
                }
            }
        }
        
        lifecycleScope.launch {
            service.isListening.collectLatest { listening ->
                if (listening && isSessionActive) {
                    binding.tvStatus.text = "Listening..."
                }
            }
        }
        
        lifecycleScope.launch {
            service.isSpeaking.collectLatest { speaking ->
                if (speaking) {
                    binding.tvStatus.text = "AI speaking..."
                }
            }
        }
        
        lifecycleScope.launch {
            service.errorMessage.collectLatest { error ->
                if (error != null) {
                    Log.e(TAG, "Voice service error: $error")
                }
            }
        }
    }
    
    private fun updateStateUI(state: OpenClawVoiceService.State) {
        binding.apply {
            when (state) {
                OpenClawVoiceService.State.IDLE -> {
                    if (!isSessionActive) {
                        tvConnectionState.text = "Ready"
                        tvConnectionState.setTextColor(getColor(android.R.color.holo_blue_light))
                    }
                    tvStatus.text = "Ready"
                }
                OpenClawVoiceService.State.CONNECTING -> {
                    tvConnectionState.text = "Connecting..."
                    tvConnectionState.setTextColor(getColor(android.R.color.holo_orange_light))
                    tvStatus.text = "Connecting to OpenClaw..."
                }
                OpenClawVoiceService.State.LISTENING -> {
                    tvConnectionState.text = "Connected - Listening"
                    tvConnectionState.setTextColor(getColor(android.R.color.holo_green_light))
                    tvStatus.text = "Listening..."
                }
                OpenClawVoiceService.State.PROCESSING -> {
                    tvConnectionState.text = "Connected - Processing"
                    tvConnectionState.setTextColor(getColor(android.R.color.holo_orange_light))
                    tvStatus.text = "Thinking..."
                }
                OpenClawVoiceService.State.SPEAKING -> {
                    tvConnectionState.text = "Connected - Speaking"
                    tvConnectionState.setTextColor(getColor(android.R.color.holo_green_light))
                    tvStatus.text = "AI speaking..."
                }
                OpenClawVoiceService.State.ERROR -> {
                    tvConnectionState.text = "Error"
                    tvConnectionState.setTextColor(getColor(android.R.color.holo_red_light))
                }
            }
        }
    }
    
    private fun startSession() {
        Log.d(TAG, "Starting OpenClaw session")
        
        if (!Config.isOpenClawConfigured()) {
            Toast.makeText(this, "Please configure OpenClaw in Config.kt", Toast.LENGTH_LONG).show()
            return
        }
        
        val service = voiceService
        if (service == null) {
            Toast.makeText(this, "Voice service not initialized", Toast.LENGTH_SHORT).show()
            return
        }
        
        isSessionActive = true
        
        // Wire up callbacks before initializing
        service.onUserSpeech = { text ->
            runOnUiThread {
                binding.tvUserTranscript.text = text
                binding.tvAiTranscript.text = ""
            }
        }
        
        service.onAiResponse = { text ->
            runOnUiThread {
                binding.tvAiTranscript.text = text
            }
        }
        
        service.onAiResponseChunk = { chunk ->
            runOnUiThread {
                binding.tvAiTranscript.append(chunk)
            }
        }
        
        service.onConnected = {
            runOnUiThread {
                binding.tvConnectionState.text = "Connected to OpenClaw"
                binding.tvConnectionState.setTextColor(getColor(android.R.color.holo_green_light))
                binding.tvStatus.text = "Connected! Starting to listen..."
                binding.btnCapture.isEnabled = true
                
                // Start listening once connected
                service.startListening()
            }
        }
        
        service.onDisconnected = { reason ->
            runOnUiThread {
                binding.tvConnectionState.text = "Disconnected"
                binding.tvConnectionState.setTextColor(getColor(android.R.color.holo_red_light))
                if (reason != null) {
                    Toast.makeText(this, "Disconnected: $reason", Toast.LENGTH_SHORT).show()
                }
            }
        }
        
        service.onListeningStarted = {
            runOnUiThread {
                binding.tvStatus.text = "Listening..."
            }
        }
        
        service.onListeningStopped = {
            // Status will be updated based on next action
        }
        
        service.onSpeakingStarted = {
            runOnUiThread {
                binding.tvStatus.text = "AI speaking..."
            }
        }
        
        service.onSpeakingFinished = {
            runOnUiThread {
                if (isSessionActive) {
                    binding.tvStatus.text = "Listening..."
                }
            }
        }
        
        service.onError = { error ->
            runOnUiThread {
                Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
            }
        }
        
        // Handle tool calls from OpenClaw
        service.onToolCall = { callId, name, args ->
            Log.d(TAG, ">>> Tool call: $name (id: $callId), args: $args")
            
            when (name) {
                "capture_photo" -> {
                    handleCapturePhotoTool(callId)
                }
                "execute_action" -> {
                    handleExecuteActionTool(callId, args)
                }
                else -> {
                    Log.w(TAG, "Unknown tool: $name")
                    service.sendToolResponse(callId, "Unknown tool: $name")
                }
            }
        }
        
        // Update UI
        binding.btnSession.text = "Stop Session"
        binding.tvConnectionState.text = "Connecting..."
        binding.tvConnectionState.setTextColor(getColor(android.R.color.holo_orange_light))
        binding.tvStatus.text = "Connecting to OpenClaw..."
        
        // Initialize voice service (this will connect to WebSocket)
        if (!service.initialize()) {
            Toast.makeText(this, "Failed to initialize voice service", Toast.LENGTH_SHORT).show()
            isSessionActive = false
            binding.btnSession.text = "Start Session"
            return
        }
    }
    
    private fun handleCapturePhotoTool(callId: String) {
        Log.d(TAG, ">>> AI requested photo capture")
        runOnUiThread {
            binding.tvStatus.text = "Capturing image..."
        }
        
        // Store the call ID so we can respond when image arrives
        pendingPhotoCallId = callId
        
        // Set timeout
        val timeoutHandler = android.os.Handler(mainLooper)
        val timeoutRunnable = Runnable {
            if (pendingPhotoCallId != null) {
                Log.e(TAG, "Photo capture timed out")
                voiceService?.sendToolResponse(callId, "Photo capture timed out. The glasses may not be connected or camera is unavailable. Please try again.")
                pendingPhotoCallId = null
                runOnUiThread {
                    binding.tvStatus.text = "Photo capture timed out"
                }
            }
        }
        timeoutHandler.postDelayed(timeoutRunnable, 30000) // 30 second timeout
        
        pendingTimeoutHandler = timeoutHandler
        pendingTimeoutRunnable = timeoutRunnable
        
        // Trigger photo capture from glasses
        glassesManager?.captureAIPhoto(
            onSuccess = {
                runOnUiThread {
                    binding.tvStatus.text = "Waiting for image from glasses..."
                }
            },
            onFail = { error ->
                Log.e(TAG, "Photo capture failed: $error")
                timeoutHandler.removeCallbacks(timeoutRunnable)
                pendingPhotoCallId = null
                voiceService?.sendToolResponse(callId, "Failed to capture photo: $error")
                runOnUiThread {
                    binding.tvStatus.text = "Capture failed: $error"
                }
            }
        ) ?: run {
            // Glasses manager not initialized
            timeoutHandler.removeCallbacks(timeoutRunnable)
            pendingPhotoCallId = null
            voiceService?.sendToolResponse(callId, "Glasses not connected")
            runOnUiThread {
                binding.tvStatus.text = "Glasses not connected"
            }
        }
    }
    
    private fun handleExecuteActionTool(callId: String, argsJson: String) {
        try {
            val args = JSONObject(argsJson)
            val task = args.optString("task", "")
            
            Log.d(TAG, ">>> Execute action: $task")
            runOnUiThread {
                binding.tvStatus.text = "Executing: ${task.take(50)}..."
            }
            
            // For now, acknowledge the task
            // OpenClaw will handle the actual execution internally
            lifecycleScope.launch {
                try {
                    voiceService?.sendToolResponse(callId, "Task acknowledged: $task. (Note: This action would be executed by OpenClaw's internal tools)")
                    
                    runOnUiThread {
                        binding.tvStatus.text = "Listening..."
                    }
                } catch (e: Exception) {
                    voiceService?.sendToolResponse(callId, "Error: ${e.message}")
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing execute_action args", e)
            voiceService?.sendToolResponse(callId, "Error parsing task: ${e.message}")
        }
    }
    
    private fun stopSession() {
        Log.d(TAG, "Stopping session")
        
        isSessionActive = false
        
        // Cancel any pending tool calls
        pendingPhotoCallId = null
        pendingTimeoutRunnable?.let { pendingTimeoutHandler?.removeCallbacks(it) }
        pendingTimeoutHandler = null
        pendingTimeoutRunnable = null
        
        voiceService?.cleanup()
        
        binding.btnSession.text = "Start Session"
        binding.btnCapture.isEnabled = false
        binding.tvConnectionState.text = "Disconnected"
        binding.tvConnectionState.setTextColor(getColor(android.R.color.holo_red_light))
        binding.tvStatus.text = "Session ended"
        binding.tvUserTranscript.text = ""
        binding.tvAiTranscript.text = ""
    }
    
    private fun capturePhoto() {
        Log.d(TAG, "Manual photo capture")
        binding.tvStatus.text = "Capturing photo..."
        
        glassesManager?.captureAIPhoto(
            onSuccess = {
                runOnUiThread {
                    binding.tvStatus.text = "Photo command sent, waiting..."
                }
            },
            onFail = { error ->
                runOnUiThread {
                    binding.tvStatus.text = "Capture failed: $error"
                    Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
                }
            }
        ) ?: run {
            binding.tvStatus.text = "Glasses not connected"
            Toast.makeText(this, "Glasses not connected", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun compressImage(imageData: ByteArray): ByteArray {
        return try {
            val bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.size)
            if (bitmap != null) {
                val output = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, Config.IMAGE_JPEG_QUALITY, output)
                output.toByteArray()
            } else {
                imageData
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compress image", e)
            imageData
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopSession()
        glassesManager?.cleanup()
    }
}
