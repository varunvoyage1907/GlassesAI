package com.glassesai.app

/**
 * Configuration for GlassesAI app.
 * 
 * OpenClaw is the AI Brain - all AI processing goes through OpenClaw.
 */
object Config {
    
    // ============================================
    // OPENCLAW CONFIGURATION (AI Brain)
    // ============================================
    
    /**
     * OpenClaw gateway host (Railway deployment)
     */
    const val OPENCLAW_HOST = "openclaw-production-ae7f.up.railway.app"
    
    /**
     * OpenClaw gateway authentication token
     */
    const val OPENCLAW_GATEWAY_TOKEN = "585i3qwe480u1tmhyhyafseixuq790r4"
    
    /**
     * OpenClaw HTTP API URL (OpenResponses format)
     */
    const val OPENCLAW_API_URL = "https://openclaw-production-ae7f.up.railway.app/v1/responses"
    
    /**
     * OpenClaw model identifier
     */
    const val OPENCLAW_MODEL = "openclaw:main"
    
    // ============================================
    // AUDIO CONFIGURATION
    // ============================================
    
    const val INPUT_AUDIO_SAMPLE_RATE = 16000
    const val OUTPUT_AUDIO_SAMPLE_RATE = 24000
    const val AUDIO_CHANNELS = 1
    const val AUDIO_BITS_PER_SAMPLE = 16
    
    // ============================================
    // IMAGE CONFIGURATION
    // ============================================
    
    /**
     * JPEG quality for captured images (0-100)
     */
    const val IMAGE_JPEG_QUALITY = 80
    
    // ============================================
    // SYSTEM PROMPT FOR OPENCLAW
    // ============================================
    
    /**
     * System instruction for OpenClaw AI Brain
     */
    val OPENCLAW_SYSTEM_INSTRUCTION = """
        You are an AI assistant integrated with G300 smart glasses.
        
        You can SEE through the user's glasses camera and HEAR them through the microphone.
        
        VISION CAPABILITIES:
        - When the user asks what they're looking at, seeing, or holding, use the capture_photo tool
        - Wait for the image to arrive, then describe EXACTLY what you see in the image
        - Be specific and helpful - identify objects, text, people, scenes, etc.
        - NEVER guess or hallucinate - only describe what's actually in the captured image
        
        ACTIONS:
        - Use execute_action tool for: sending messages, web searches, reminders, notes, smart home, etc.
        - You are the user's AI companion - help them with anything they ask
        
        VOICE:
        - Be conversational, friendly, and concise
        - Speak naturally as if talking to a friend
        - When asked to repeat something, say it exactly without additions
    """.trimIndent()
    
    // ============================================
    // HELPER METHODS
    // ============================================
    
    fun isOpenClawConfigured(): Boolean {
        return OPENCLAW_HOST.isNotEmpty() && 
               OPENCLAW_GATEWAY_TOKEN.isNotEmpty() &&
               OPENCLAW_HOST != "YOUR_OPENCLAW_HOST"
    }
    
    fun getOpenClawApiUrl(): String {
        return OPENCLAW_API_URL
    }
    
    fun getOpenClawAuthHeader(): String {
        return "Bearer $OPENCLAW_GATEWAY_TOKEN"
    }
}
