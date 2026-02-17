# GlassesAI - AI-Powered Smart Glasses App for G300

An Android application that brings real-time AI vision and voice capabilities to HeyCyan G300 smart glasses, powered by OpenClaw AI backend.

## About the G300 Smart Glasses

### Hardware Specifications

The **HeyCyan G300** are AI-enabled smart glasses with the following features:

| Component | Specification |
|-----------|---------------|
| **Camera** | 8MP front-facing camera for photos |
| **Display** | Micro OLED display in right lens |
| **Audio** | Built-in speakers + microphone |
| **Connectivity** | Bluetooth 5.0 (BLE + Classic) |
| **Battery** | ~4 hours active use |
| **Weight** | ~45g |

### What's Inside the Glasses

```
┌─────────────────────────────────────────────────────────────┐
│                    HeyCyan G300 Glasses                      │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│   [Camera 8MP]                              [Micro OLED]     │
│       ●                                         ▓▓▓         │
│                                                              │
│   ┌─────────┐                              ┌─────────┐      │
│   │ Left    │                              │ Right   │      │
│   │ Temple  │                              │ Temple  │      │
│   │         │                              │         │      │
│   │ Speaker │                              │ Speaker │      │
│   │ Battery │                              │ MCU     │      │
│   │         │                              │ BLE     │      │
│   └─────────┘                              └─────────┘      │
│                                                              │
│   Touch Controls: Tap/Swipe on right temple                 │
│   Button: Press for photo capture / voice activation        │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### Touch Controls

| Gesture | Action |
|---------|--------|
| Single Tap | Take photo |
| Double Tap | Voice command activation |
| Swipe Forward | Volume up |
| Swipe Back | Volume down |
| Long Press | Power on/off |

## How Image Capture Works via Bluetooth

### BLE Communication Protocol

The G300 glasses communicate with the Android app via **Bluetooth Low Energy (BLE)**. Image transfer is a multi-step process:

```
┌──────────────┐                              ┌──────────────┐
│   G300       │                              │  Android     │
│   Glasses    │                              │  App         │
└──────┬───────┘                              └──────┬───────┘
       │                                             │
       │  1. User taps glasses (capture photo)       │
       │                                             │
       │  2. Notification: 0x02 (Photo Ready)        │
       │ ─────────────────────────────────────────►  │
       │                                             │
       │  3. Request: getPictureThumbnails()         │
       │ ◄─────────────────────────────────────────  │
       │                                             │
       │  4. Chunk 1/N (~1KB) success=false          │
       │ ─────────────────────────────────────────►  │
       │                                             │
       │  5. Chunk 2/N (~1KB) success=false          │
       │ ─────────────────────────────────────────►  │
       │                                             │
       │  ... (repeat for all chunks)                │
       │                                             │
       │  6. Final chunk N/N success=true            │
       │ ─────────────────────────────────────────►  │
       │                                             │
       │                              7. Assemble    │
       │                                 full JPEG   │
       │                                             │
       │                              8. Send to     │
       │                                 OpenClaw AI │
       ▼                                             ▼
```

### Image Transfer Details

1. **Photo Trigger**: User taps the glasses temple or presses the capture button
2. **Notification**: Glasses send BLE notification `0x02` indicating photo is ready
3. **Fetch Request**: App calls `LargeDataHandler.getInstance().getPictureThumbnails()`
4. **Chunked Transfer**: Image data arrives in ~1KB chunks via BLE
   - Each chunk has an 11-byte header
   - Bytes 7-9: Total chunks
   - Bytes 9-11: Current chunk index
   - `success=false` until final chunk
5. **Assembly**: App accumulates all chunks into complete JPEG
6. **AI Processing**: Complete image sent to OpenClaw for analysis

### Code Implementation

```kotlin
// In GlassesManager.kt

// Step 1: Listen for photo notification
deviceNotifyListener = object : GlassesDeviceNotifyListener() {
    override fun parseData(cmdType: Int, response: GlassesDeviceNotifyRsp) {
        val eventType = response.data?.firstOrNull()?.toInt() ?: return
        when (eventType) {
            0x02 -> {
                // AI Photo ready - fetch it
                fetchThumbnailNow()
            }
            0x03 -> {
                // Microphone activated on glasses
            }
        }
    }
}

// Step 2: Accumulate chunks
private val imageDataBuffer = mutableListOf<Byte>()

private fun fetchThumbnailNow() {
    imageDataBuffer.clear()
    
    LargeDataHandler.getInstance().getPictureThumbnails { cmdType, success, data ->
        // Add chunk to buffer
        imageDataBuffer.addAll(data.toList())
        
        if (success) {
            // Transfer complete - we have full image
            val fullImage = imageDataBuffer.toByteArray()
            onImageCaptured?.invoke(fullImage)
            imageDataBuffer.clear()
        }
        // If success=false, more chunks coming...
    }
}
```

### Transfer Time

| Image Size | Chunks | Transfer Time |
|------------|--------|---------------|
| 50KB | ~50 | ~5 seconds |
| 100KB | ~100 | ~10 seconds |
| 200KB | ~200 | ~20 seconds |

*Note: BLE transfer is slow (~1KB/s). Images are compressed JPEG thumbnails.*

## App Architecture

```
┌─────────────────┐     BLE      ┌─────────────────────────────────────┐
│   G300 Glasses  │◄────────────►│           Android App               │
│  - Camera       │              │                                     │
│  - Microphone   │              │  ┌─────────────┐  ┌──────────────┐ │
│  - Speakers     │              │  │GlassesManager│  │OpenClawVoice │ │
│  - Touch        │              │  │ (BLE comms) │  │  Service     │ │
└─────────────────┘              │  └──────┬──────┘  └──────┬───────┘ │
                                 │         │                │         │
                                 │         │   Image        │ Voice   │
                                 │         │   (JPEG)       │ (STT/   │
                                 │         │                │  TTS)   │
                                 │         └────────┬───────┘         │
                                 │                  │                 │
                                 └──────────────────┼─────────────────┘
                                                    │
                                                    │ HTTPS POST
                                                    │ /v1/chat/completions
                                                    ▼
                                           ┌───────────────┐
                                           │   OpenClaw    │
                                           │  (AI Brain)   │
                                           │               │
                                           │  - Vision     │
                                           │  - Language   │
                                           │  - Actions    │
                                           └───────────────┘
```

## Features

- **AI Vision**: Capture photos from G300 glasses → OpenClaw analyzes and describes what you see
- **Voice Interaction**: Speak naturally, hear AI responses via Android TTS
- **Bluetooth Integration**: Seamless BLE connection with G300 glasses
- **Image Analysis**: Send captured images to AI with questions like "What am I looking at?"

## Setup

### Prerequisites

- Android Studio Arctic Fox or later
- Android SDK 26+ (Android 8.0)
- HeyCyan G300 Smart Glasses
- OpenClaw backend server (or API endpoint)

### Configuration

1. Clone the repository:
```bash
git clone https://github.com/varunvoyage1907/GlassesAI.git
```

2. Configure OpenClaw in `app/src/main/java/com/glassesai/app/Config.kt`:
```kotlin
object Config {
    const val OPENCLAW_API_URL = "https://your-openclaw-server/v1/chat/completions"
    const val OPENCLAW_GATEWAY_TOKEN = "your-token-here"
    const val OPENCLAW_MODEL = "claude"
}
```

3. Place the HeyCyan SDK (`glasses_sdk_*.aar`) in `app/libs/`

### Building

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Usage

1. **Pair your G300 glasses** with your Android phone via Bluetooth Settings
2. **Open GlassesAI** and grant permissions (Bluetooth, Microphone, Location)
3. **Scan and Connect** to your glasses from the device list
4. **Start AI Session** to begin interaction
5. **Capture photos** by tapping the glasses or using the app button
6. **Ask questions** like "What am I looking at?" - the AI will describe the scene

## Project Structure

```
app/src/main/java/com/glassesai/
├── app/
│   ├── Config.kt              # OpenClaw API configuration
│   ├── MainActivity.kt        # Main entry, permissions
│   └── GlassesAIApplication.kt
├── audio/
│   └── AudioManager.kt        # Bluetooth SCO audio handling
├── glasses/
│   ├── GlassesManager.kt      # BLE communication, image capture
│   ├── ConnectionManager.kt   # BLE connection state
│   └── BluetoothEvent.kt      # Bluetooth events
├── openclaw/
│   ├── OpenClawVoiceService.kt # STT/TTS + OpenClaw HTTP API
│   └── OpenClawBridge.kt       # Task delegation to OpenClaw
└── ui/
    ├── OpenClawSessionActivity.kt # AI session interface
    └── ScanActivity.kt            # Device scanning
```

## Key Components

| Component | Purpose |
|-----------|---------|
| `GlassesManager.kt` | BLE connection, image chunk accumulation, notifications |
| `OpenClawVoiceService.kt` | Android STT/TTS + OpenClaw API communication |
| `OpenClawSessionActivity.kt` | UI for AI session, state management |
| `Config.kt` | API endpoints, tokens, model configuration |

## API Format

### Request (OpenAI Chat Completions)
```json
{
  "model": "claude",
  "messages": [
    {
      "role": "user",
      "content": "What do you see in this image?"
    }
  ]
}
```

### Request with Image (Vision)
```json
{
  "model": "claude",
  "messages": [
    {
      "role": "user",
      "content": [
        {"type": "text", "text": "What's in this image?"},
        {"type": "image_url", "image_url": {"url": "data:image/jpeg;base64,/9j/4AAQ..."}}
      ]
    }
  ]
}
```

### Response
```json
{
  "choices": [
    {
      "message": {
        "role": "assistant",
        "content": "I can see a coffee cup on a wooden table..."
      }
    }
  ]
}
```

## Troubleshooting

### Image not capturing
- Ensure glasses are connected (check status indicator)
- Wait for `0x02` notification before fetching
- Check logs: `adb logcat | grep GlassesManager`

### AI not responding
- Verify OpenClaw API endpoint is accessible
- Check token is valid
- Review logs: `adb logcat | grep OpenClaw`

### Bluetooth connection issues
- Ensure glasses are charged and in pairing mode
- Grant all Bluetooth permissions
- Try forgetting and re-pairing in Android Bluetooth settings

## Dependencies

- **HeyCyan SDK**: Proprietary SDK for G300 glasses (included as AAR)
- **OkHttp**: HTTP client for OpenClaw API
- **Kotlin Coroutines**: Asynchronous operations
- **AndroidX**: Modern Android components
- **PermissionX**: Runtime permission handling

## License

MIT License - See LICENSE file for details

## Credits

- Built for [HeyCyan G300 Smart Glasses](https://www.heycyan.com/)
- Powered by [OpenClaw](https://github.com/openclaw/openclaw) AI backend
