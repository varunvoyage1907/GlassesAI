# GlassesAI - AI-Powered Smart Glasses App for G300

An Android application that brings real-time AI vision and voice capabilities to HeyCyan G300 smart glasses, powered by Google's Gemini Live API.

## Features

- **Real-time AI Vision**: Capture photos from your G300 glasses and get AI-powered descriptions
- **Voice Interaction**: Natural voice conversations with Gemini AI
- **Bluetooth Integration**: Seamless connection with G300 glasses via BLE
- **Live Audio**: Bidirectional audio streaming for voice commands and AI responses

## Architecture

```
┌─────────────────┐     BLE      ┌─────────────────┐
│   G300 Glasses  │◄────────────►│   Android App   │
│  - Camera       │              │  - GlassesManager│
│  - Microphone   │              │  - AudioManager  │
│  - Speakers     │              │  - GeminiService │
└─────────────────┘              └────────┬────────┘
                                          │
                                          │ WebSocket
                                          ▼
                                 ┌─────────────────┐
                                 │  Gemini Live API │
                                 │  (Multimodal AI) │
                                 └─────────────────┘
```

## Technical Breakthrough

### Image Transfer via BLE

The HeyCyan SDK transfers images in chunks via Bluetooth Low Energy. Key discoveries:

1. **Notification-Driven Fetch**: Wait for `0x02` notification before fetching image data
2. **Chunk Accumulation**: Image data arrives in ~1KB chunks with `success=false` until complete
3. **Protocol Structure**: Each chunk has 11-byte header (bytes 7-9: total chunks, 9-11: current index)

```kotlin
// Wait for AI photo notification (0x02)
deviceNotifyListener = object : GlassesDeviceNotifyListener() {
    override fun parseData(cmdType: Int, response: GlassesDeviceNotifyRsp) {
        when (eventType) {
            0x02 -> fetchThumbnailNow()  // AI Photo ready
            0x03 -> // Microphone activated
        }
    }
}

// Accumulate chunks
LargeDataHandler.getInstance().getPictureThumbnails { cmdType, success, data ->
    imageDataBuffer.addAll(data.toList())
    if (success) {
        // Transfer complete - process full image
        onImageReceived?.invoke(imageDataBuffer.toByteArray())
    }
}
```

### Gemini Live API Integration

Real-time bidirectional communication with Gemini using WebSocket:

1. **Binary Message Handling**: Gemini sends responses as binary WebSocket frames
2. **Model**: `models/gemini-2.5-flash-native-audio-preview-12-2025`
3. **Audio Format**: PCM 16-bit, 16kHz sample rate

```kotlin
// Handle both text and binary WebSocket messages
override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
    val text = bytes.utf8()
    handleMessage(text)  // Process setupComplete, audio responses, etc.
}
```

### Bluetooth SCO for Glasses Microphone

Access the G300's microphone via Bluetooth Hands-Free Profile:

```kotlin
fun startBluetoothSco() {
    systemAudioManager.mode = AudioManager.MODE_IN_COMMUNICATION
    systemAudioManager.startBluetoothSco()
    systemAudioManager.isBluetoothScoOn = true
}
```

## Setup

### Prerequisites

- Android Studio Arctic Fox or later
- Android SDK 24+ (Android 7.0)
- HeyCyan G300 Smart Glasses
- Google Cloud API key with Gemini API access

### Configuration

1. Clone the repository
2. Add your Gemini API key in `Config.kt`:

```kotlin
object Config {
    const val GEMINI_API_KEY = "your-api-key-here"
}
```

3. Place the HeyCyan SDK (`glasses_sdk_*.aar`) in `app/libs/`

### Building

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Usage

1. **Pair your G300 glasses** with your Android phone via Bluetooth
2. **Open GlassesAI** and grant permissions (Bluetooth, Microphone, Location)
3. **Connect** to your glasses from the device list
4. **Start Session** to begin AI interaction
5. **Capture** photos using the app button or voice command on glasses

## Project Structure

```
app/src/main/java/com/glassesai/
├── app/
│   └── Config.kt           # API keys and configuration
├── audio/
│   └── AudioManager.kt     # Microphone/speaker handling, Bluetooth SCO
├── gemini/
│   └── GeminiLiveService.kt # WebSocket client for Gemini Live API
├── glasses/
│   └── GlassesManager.kt   # BLE communication with G300
├── openclaw/
│   └── OpenClawBridge.kt   # Agentic actions integration
└── ui/
    ├── MainActivity.kt     # Device scanning and connection
    └── SessionActivity.kt  # AI session interface
```

## Key Files Modified

| File | Purpose |
|------|---------|
| `GlassesManager.kt` | BLE image chunk accumulation, notification handling |
| `GeminiLiveService.kt` | Binary WebSocket message handling |
| `AudioManager.kt` | Bluetooth SCO for glasses microphone |
| `SessionActivity.kt` | UI and component orchestration |

## Dependencies

- **HeyCyan SDK**: Proprietary SDK for G300 glasses communication
- **OkHttp**: WebSocket client for Gemini Live API
- **Kotlin Coroutines**: Asynchronous operations
- **AndroidX**: Modern Android components

## Troubleshooting

### Image not displaying
- Ensure glasses are connected before capturing
- Check logs for `0x02` notification reception
- Verify chunk accumulation in `GlassesManager` logs

### Session timeout
- Verify API key is valid
- Check WebSocket connection in logs
- Ensure both binary and text message handlers are implemented

### No audio from glasses
- Enable Bluetooth SCO in AudioManager
- Check `MODE_IN_COMMUNICATION` is set
- Verify glasses are paired as audio device (HFP)

## Credits

- Inspired by [VisionClaw](https://github.com/anthropics/VisionClaw) for Meta Ray-Ban glasses
- Built for [HeyCyan G300 Smart Glasses](https://github.com/ebowwa/HeyCyanSmartGlassesSDK)
- Powered by [Google Gemini Live API](https://ai.google.dev/)

## License

MIT License - See LICENSE file for details
