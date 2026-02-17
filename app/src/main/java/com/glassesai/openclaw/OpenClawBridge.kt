package com.glassesai.openclaw

import android.util.Log
import com.glassesai.app.Config
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Bridge to OpenClaw gateway for executing agentic tasks.
 * Based on VisionClaw's OpenClawBridge.swift
 */
class OpenClawBridge {
    
    companion object {
        private const val TAG = "OpenClawBridge"
        private const val MAX_HISTORY_TURNS = 10
    }
    
    enum class ConnectionState {
        NOT_CONFIGURED,
        CHECKING,
        CONNECTED,
        UNREACHABLE
    }
    
    var connectionState = ConnectionState.NOT_CONFIGURED
        private set
    
    var lastToolCallStatus: ToolCallStatus = ToolCallStatus.Idle
        private set
    
    private var sessionKey: String = generateSessionKey()
    private val conversationHistory = mutableListOf<JSONObject>()
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private val pingClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
    
    /**
     * Check if OpenClaw gateway is reachable
     */
    suspend fun checkConnection(): ConnectionState = withContext(Dispatchers.IO) {
        if (!Config.isOpenClawConfigured()) {
            connectionState = ConnectionState.NOT_CONFIGURED
            return@withContext connectionState
        }
        
        connectionState = ConnectionState.CHECKING
        
        try {
            val request = Request.Builder()
                .url(Config.getOpenClawApiUrl())
                .addHeader("Authorization", Config.getOpenClawAuthHeader())
                .get()
                .build()
            
            val response = pingClient.newCall(request).execute()
            
            if (response.code in 200..499) {
                connectionState = ConnectionState.CONNECTED
                Log.d(TAG, "OpenClaw gateway reachable (HTTP ${response.code})")
            } else {
                connectionState = ConnectionState.UNREACHABLE
                Log.e(TAG, "OpenClaw gateway unreachable (HTTP ${response.code})")
            }
            
            response.close()
            
        } catch (e: Exception) {
            connectionState = ConnectionState.UNREACHABLE
            Log.e(TAG, "OpenClaw gateway unreachable: ${e.message}")
        }
        
        connectionState
    }
    
    /**
     * Reset the session (start fresh conversation)
     */
    fun resetSession() {
        sessionKey = generateSessionKey()
        conversationHistory.clear()
        Log.d(TAG, "New session: $sessionKey")
    }
    
    /**
     * Delegate a task to OpenClaw for execution
     */
    suspend fun delegateTask(
        task: String,
        toolName: String = "execute"
    ): ToolResult = withContext(Dispatchers.IO) {
        
        lastToolCallStatus = ToolCallStatus.Executing(toolName)
        
        if (!Config.isOpenClawConfigured()) {
            lastToolCallStatus = ToolCallStatus.Failed(toolName, "OpenClaw not configured")
            return@withContext ToolResult.Failure("OpenClaw not configured")
        }
        
        try {
            // Add user message to history
            conversationHistory.add(JSONObject().apply {
                put("role", "user")
                put("content", task)
            })
            
            // Trim history if too long
            while (conversationHistory.size > MAX_HISTORY_TURNS * 2) {
                conversationHistory.removeAt(0)
            }
            
            // Build request body
            val messagesArray = JSONArray()
            conversationHistory.forEach { messagesArray.put(it) }
            
            val body = JSONObject().apply {
                put("model", "openclaw")
                put("messages", messagesArray)
                put("stream", false)
            }
            
            Log.d(TAG, "Sending ${conversationHistory.size} messages to OpenClaw")
            
            val request = Request.Builder()
                .url(Config.getOpenClawApiUrl())
                .addHeader("Authorization", Config.getOpenClawAuthHeader())
                .addHeader("Content-Type", "application/json")
                .addHeader("x-openclaw-session-key", sessionKey)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            
            if (!response.isSuccessful) {
                Log.e(TAG, "OpenClaw failed: HTTP ${response.code} - ${responseBody.take(200)}")
                lastToolCallStatus = ToolCallStatus.Failed(toolName, "HTTP ${response.code}")
                return@withContext ToolResult.Failure("Agent returned HTTP ${response.code}")
            }
            
            // Parse response
            val json = JSONObject(responseBody)
            val choices = json.optJSONArray("choices")
            val firstChoice = choices?.optJSONObject(0)
            val message = firstChoice?.optJSONObject("message")
            val content = message?.optString("content", "") ?: responseBody
            
            // Add assistant response to history
            conversationHistory.add(JSONObject().apply {
                put("role", "assistant")
                put("content", content)
            })
            
            Log.d(TAG, "OpenClaw result: ${content.take(200)}")
            lastToolCallStatus = ToolCallStatus.Completed(toolName)
            
            ToolResult.Success(content)
            
        } catch (e: Exception) {
            Log.e(TAG, "OpenClaw error: ${e.message}")
            lastToolCallStatus = ToolCallStatus.Failed(toolName, e.message ?: "Unknown error")
            ToolResult.Failure("Agent error: ${e.message}")
        }
    }
    
    private fun generateSessionKey(): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        val timestamp = dateFormat.format(Date())
        return "agent:main:glass:$timestamp"
    }
}

/**
 * Result of a tool execution
 */
sealed class ToolResult {
    data class Success(val result: String) : ToolResult()
    data class Failure(val error: String) : ToolResult()
    
    fun toResponseJson(): JSONObject {
        return when (this) {
            is Success -> JSONObject().put("result", result)
            is Failure -> JSONObject().put("error", error)
        }
    }
}

/**
 * Status of tool call execution (for UI)
 */
sealed class ToolCallStatus {
    object Idle : ToolCallStatus()
    data class Executing(val name: String) : ToolCallStatus()
    data class Completed(val name: String) : ToolCallStatus()
    data class Failed(val name: String, val error: String) : ToolCallStatus()
    data class Cancelled(val name: String) : ToolCallStatus()
    
    val displayText: String
        get() = when (this) {
            is Idle -> ""
            is Executing -> "Running: $name..."
            is Completed -> "Done: $name"
            is Failed -> "Failed: $name - $error"
            is Cancelled -> "Cancelled: $name"
        }
    
    val isActive: Boolean
        get() = this is Executing
}
