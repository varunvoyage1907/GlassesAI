package com.glassesai.openclaw

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.util.Log
import com.glassesai.app.Config
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * OpenClaw Voice Service - Uses Android STT/TTS with OpenClaw HTTP API
 * 
 * Protocol (OpenResponses format):
 * - POST to /v1/responses
 * - Auth: Bearer token
 * - Request: {"model": "openclaw:main", "input": "text" or array with images}
 * - Response: {"output": "response text", ...}
 * 
 * This service:
 * 1. Listens for user speech using Android SpeechRecognizer
 * 2. Sends transcribed text + images to OpenClaw via HTTP POST
 * 3. Receives text response from OpenClaw
 * 4. Speaks the response using Android TextToSpeech
 */
class OpenClawVoiceService(private val context: Context) {
    
    companion object {
        private const val TAG = "OpenClawVoiceService"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
    
    enum class State {
        IDLE,
        CONNECTING,
        LISTENING,
        PROCESSING,
        SPEAKING,
        ERROR
    }
    
    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()
    
    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()
    
    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()
    
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()
    
    private val _lastTranscript = MutableStateFlow<String?>(null)
    val lastTranscript: StateFlow<String?> = _lastTranscript.asStateFlow()
    
    // Callbacks
    var onUserSpeech: ((String) -> Unit)? = null
    var onAiResponse: ((String) -> Unit)? = null
    var onAiResponseChunk: ((String) -> Unit)? = null
    var onToolCall: ((callId: String, name: String, args: String) -> Unit)? = null
    var onListeningStarted: (() -> Unit)? = null
    var onListeningStopped: (() -> Unit)? = null
    var onSpeakingStarted: (() -> Unit)? = null
    var onSpeakingFinished: (() -> Unit)? = null
    var onConnected: (() -> Unit)? = null
    var onDisconnected: ((String?) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    
    // Speech recognizer
    private var speechRecognizer: SpeechRecognizer? = null
    private var recognizerIntent: Intent? = null
    
    // Text to speech
    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false
    
    // OkHttp client for HTTP requests
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // Pending image for next request
    private var pendingImage: ByteArray? = null
    private var pendingImagePrompt: String? = null
    
    /**
     * Initialize the service
     */
    fun initialize(): Boolean {
        Log.d(TAG, "Initializing OpenClawVoiceService")
        
        if (!Config.isOpenClawConfigured()) {
            _errorMessage.value = "OpenClaw not configured"
            return false
        }
        
        // Initialize speech recognizer on main thread
        mainScope.launch {
            initializeSpeechRecognizer()
        }
        
        // Initialize TTS
        initializeTTS()
        
        // Test connection to OpenClaw API
        testConnection()
        
        return true
    }
    
    /**
     * Test connection to OpenClaw API
     */
    private fun testConnection() {
        _state.value = State.CONNECTING
        
        scope.launch {
            try {
                Log.d(TAG, "Testing connection to OpenClaw API: ${Config.getOpenClawApiUrl()}")
                
                // Send a simple test request
                val requestBody = JSONObject().apply {
                    put("model", Config.OPENCLAW_MODEL)
                    put("input", "ping")
                }.toString()
                
                val request = Request.Builder()
                    .url(Config.getOpenClawApiUrl())
                    .addHeader("Authorization", Config.getOpenClawAuthHeader())
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Log.d(TAG, "OpenClaw API connection successful!")
                        _isConnected.value = true
                        _state.value = State.IDLE
                        
                        mainScope.launch {
                            onConnected?.invoke()
                        }
                    } else {
                        val errorBody = response.body?.string() ?: "Unknown error"
                        Log.e(TAG, "OpenClaw API error: ${response.code} - $errorBody")
                        _errorMessage.value = "API error: ${response.code}"
                        _state.value = State.ERROR
                        
                        // Still mark as connected if it's just an API error (server is reachable)
                        if (response.code in 400..499) {
                            _isConnected.value = true
                            mainScope.launch {
                                onConnected?.invoke()
                            }
                        } else {
                            mainScope.launch {
                                onError?.invoke("OpenClaw API error: ${response.code}")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect to OpenClaw API", e)
                _errorMessage.value = e.message
                _state.value = State.ERROR
                
                mainScope.launch {
                    onError?.invoke(e.message ?: "Connection failed")
                }
            }
        }
    }
    
    /**
     * Send a message to OpenClaw via HTTP API
     */
    private fun sendToOpenClaw(text: String, image: ByteArray?) {
        scope.launch {
            try {
                _state.value = State.PROCESSING
                
                val requestBody = buildRequestBody(text, image)
                
                Log.d(TAG, "Sending to OpenClaw: text='${text.take(50)}', hasImage=${image != null}")
                
                val request = Request.Builder()
                    .url(Config.getOpenClawApiUrl())
                    .addHeader("Authorization", Config.getOpenClawAuthHeader())
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                
                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        Log.e(TAG, "OpenClaw request failed", e)
                        _errorMessage.value = e.message
                        _state.value = State.ERROR
                        
                        mainScope.launch {
                            onError?.invoke(e.message ?: "Request failed")
                            delay(1000)
                            startListening()
                        }
                    }
                    
                    override fun onResponse(call: Call, response: Response) {
                        response.use {
                            val responseBody = response.body?.string()
                            
                            if (response.isSuccessful && responseBody != null) {
                                handleOpenClawResponse(responseBody)
                            } else {
                                Log.e(TAG, "OpenClaw error: ${response.code} - $responseBody")
                                _errorMessage.value = "API error: ${response.code}"
                                _state.value = State.ERROR
                                
                                mainScope.launch {
                                    onError?.invoke("OpenClaw error: ${response.code}")
                                    delay(1000)
                                    startListening()
                                }
                            }
                        }
                    }
                })
                
            } catch (e: Exception) {
                Log.e(TAG, "Error sending to OpenClaw", e)
                _errorMessage.value = e.message
                _state.value = State.ERROR
                
                mainScope.launch {
                    onError?.invoke(e.message ?: "Failed to send message")
                    delay(1000)
                    startListening()
                }
            }
        }
    }
    
    /**
     * Build request body for OpenClaw API
     */
    private fun buildRequestBody(text: String, image: ByteArray?): String {
        val json = JSONObject()
        json.put("model", Config.OPENCLAW_MODEL)
        
        if (image != null) {
            // With image: use array format
            val inputArray = JSONArray()
            
            // Add text message
            inputArray.put(JSONObject().apply {
                put("type", "message")
                put("role", "user")
                put("content", text)
            })
            
            // Add image
            val base64Image = Base64.encodeToString(image, Base64.NO_WRAP)
            inputArray.put(JSONObject().apply {
                put("type", "input_image")
                put("source", JSONObject().apply {
                    put("type", "base64")
                    put("media_type", "image/jpeg")
                    put("data", base64Image)
                })
            })
            
            json.put("input", inputArray)
        } else {
            // Text only: simple string format
            json.put("input", text)
        }
        
        return json.toString()
    }
    
    /**
     * Handle response from OpenClaw API
     */
    private fun handleOpenClawResponse(responseBody: String) {
        try {
            Log.d(TAG, "OpenClaw response: ${responseBody.take(300)}")
            
            val json = JSONObject(responseBody)
            val output = json.optString("output", "")
            
            if (output.isNotBlank()) {
                Log.d(TAG, "AI response: ${output.take(100)}...")
                
                mainScope.launch {
                    onAiResponseChunk?.invoke(output)
                    onAiResponse?.invoke(output)
                    speak(output)
                }
            } else {
                Log.w(TAG, "Empty response from OpenClaw")
                mainScope.launch {
                    startListening()
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing OpenClaw response", e)
            _errorMessage.value = "Failed to parse response"
            
            mainScope.launch {
                onError?.invoke("Failed to parse response")
                startListening()
            }
        }
    }
    
    private fun initializeSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "Speech recognition not available")
            _errorMessage.value = "Speech recognition not available on this device"
            return
        }
        
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "Ready for speech")
                _isListening.value = true
                _state.value = State.LISTENING
                onListeningStarted?.invoke()
            }
            
            override fun onBeginningOfSpeech() {
                Log.d(TAG, "Speech started")
            }
            
            override fun onRmsChanged(rmsdB: Float) {}
            
            override fun onBufferReceived(buffer: ByteArray?) {}
            
            override fun onEndOfSpeech() {
                Log.d(TAG, "Speech ended")
                _isListening.value = false
                onListeningStopped?.invoke()
            }
            
            override fun onError(error: Int) {
                val errorMsg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
                    SpeechRecognizer.ERROR_AUDIO -> "Audio error"
                    SpeechRecognizer.ERROR_CLIENT -> "Client error"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permission denied"
                    SpeechRecognizer.ERROR_NETWORK -> "Network error"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                    SpeechRecognizer.ERROR_SERVER -> "Server error"
                    else -> "Unknown error: $error"
                }
                Log.e(TAG, "Speech recognition error: $errorMsg")
                _isListening.value = false
                
                if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    _errorMessage.value = errorMsg
                    onError?.invoke(errorMsg)
                }
                
                mainScope.launch {
                    delay(500)
                    if (_state.value != State.SPEAKING && _state.value != State.PROCESSING && _isConnected.value) {
                        startListening()
                    }
                }
            }
            
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val transcript = matches?.firstOrNull()
                
                if (!transcript.isNullOrBlank()) {
                    Log.d(TAG, "User said: $transcript")
                    _lastTranscript.value = transcript
                    onUserSpeech?.invoke(transcript)
                    processUserInput(transcript)
                } else {
                    mainScope.launch {
                        delay(300)
                        if (_isConnected.value) {
                            startListening()
                        }
                    }
                }
            }
            
            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val partial = matches?.firstOrNull()
                if (!partial.isNullOrBlank()) {
                    Log.d(TAG, "Partial: $partial")
                }
            }
            
            override fun onEvent(eventType: Int, params: Bundle?) {
                Log.d(TAG, "Speech event: $eventType")
            }
        })
        
        recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        
        Log.d(TAG, "Speech recognizer initialized")
    }
    
    private fun initializeTTS() {
        textToSpeech = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = textToSpeech?.setLanguage(Locale.US)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e(TAG, "TTS language not supported")
                    _errorMessage.value = "TTS language not supported"
                } else {
                    ttsReady = true
                    Log.d(TAG, "TTS initialized successfully")
                    textToSpeech?.setSpeechRate(1.1f)
                    
                    textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            Log.d(TAG, "TTS started: $utteranceId")
                            mainScope.launch {
                                _isSpeaking.value = true
                                _state.value = State.SPEAKING
                                onSpeakingStarted?.invoke()
                            }
                        }
                        
                        override fun onDone(utteranceId: String?) {
                            Log.d(TAG, "TTS done: $utteranceId")
                            mainScope.launch {
                                _isSpeaking.value = false
                                onSpeakingFinished?.invoke()
                                delay(300)
                                if (_isConnected.value) {
                                    startListening()
                                }
                            }
                        }
                        
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            Log.e(TAG, "TTS error: $utteranceId")
                            mainScope.launch {
                                _isSpeaking.value = false
                                if (_isConnected.value) {
                                    startListening()
                                }
                            }
                        }
                        
                        override fun onError(utteranceId: String?, errorCode: Int) {
                            Log.e(TAG, "TTS error: $utteranceId, code: $errorCode")
                            mainScope.launch {
                                _isSpeaking.value = false
                                if (_isConnected.value) {
                                    startListening()
                                }
                            }
                        }
                    })
                }
            } else {
                Log.e(TAG, "TTS initialization failed")
                _errorMessage.value = "TTS initialization failed"
            }
        }
    }
    
    fun startListening() {
        mainScope.launch {
            if (_isSpeaking.value || _state.value == State.PROCESSING) {
                Log.d(TAG, "Cannot start listening - speaking or processing")
                return@launch
            }
            
            if (!_isConnected.value) {
                Log.d(TAG, "Cannot start listening - not connected")
                return@launch
            }
            
            Log.d(TAG, "Starting speech recognition")
            try {
                speechRecognizer?.startListening(recognizerIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Error starting speech recognition", e)
                _errorMessage.value = e.message
            }
        }
    }
    
    fun stopListening() {
        mainScope.launch {
            Log.d(TAG, "Stopping speech recognition")
            speechRecognizer?.stopListening()
            _isListening.value = false
        }
    }
    
    fun stopSpeaking() {
        textToSpeech?.stop()
        _isSpeaking.value = false
    }
    
    fun setPendingImage(imageData: ByteArray, prompt: String = "") {
        Log.d(TAG, "Setting pending image (${imageData.size} bytes)")
        pendingImage = imageData
        pendingImagePrompt = prompt
    }
    
    fun sendToolResponse(callId: String, result: String) {
        Log.d(TAG, "Tool response for $callId: $result")
        
        val image = pendingImage
        val prompt = pendingImagePrompt ?: "Describe what you see in this image."
        
        if (image != null) {
            pendingImage = null
            pendingImagePrompt = null
            sendToOpenClaw(prompt, image)
        } else {
            sendToOpenClaw(result, null)
        }
    }
    
    private fun processUserInput(text: String) {
        _state.value = State.PROCESSING
        stopListening()
        
        val image = pendingImage
        pendingImage = null
        pendingImagePrompt = null
        
        sendToOpenClaw(text, image)
    }
    
    private fun speak(text: String) {
        if (!ttsReady) {
            Log.e(TAG, "TTS not ready")
            startListening()
            return
        }
        
        val utteranceId = UUID.randomUUID().toString()
        Log.d(TAG, "Speaking: ${text.take(100)}...")
        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }
    
    fun sendText(text: String) {
        stopListening()
        _state.value = State.PROCESSING
        
        val image = pendingImage
        pendingImage = null
        pendingImagePrompt = null
        
        sendToOpenClaw(text, image)
    }
    
    fun sendImage(imageData: ByteArray, prompt: String) {
        stopListening()
        _state.value = State.PROCESSING
        sendToOpenClaw(prompt, imageData)
    }
    
    fun interrupt() {
        stopSpeaking()
        mainScope.launch {
            delay(200)
            startListening()
        }
    }
    
    fun reconnect() {
        if (!_isConnected.value) {
            testConnection()
        }
    }
    
    fun cleanup() {
        Log.d(TAG, "Cleaning up OpenClawVoiceService")
        stopListening()
        stopSpeaking()
        
        _isConnected.value = false
        
        speechRecognizer?.destroy()
        speechRecognizer = null
        
        textToSpeech?.shutdown()
        textToSpeech = null
        
        scope.cancel()
        mainScope.cancel()
        
        pendingImage = null
        pendingImagePrompt = null
        
        onUserSpeech = null
        onAiResponse = null
        onAiResponseChunk = null
        onToolCall = null
        onListeningStarted = null
        onListeningStopped = null
        onSpeakingStarted = null
        onSpeakingFinished = null
        onConnected = null
        onDisconnected = null
        onError = null
    }
}
