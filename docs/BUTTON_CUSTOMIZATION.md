# G300 Button Customization Guide

## Physical Buttons on G300 Glasses

The HeyCyan G300 smart glasses have the following physical controls:

```
┌─────────────────────────────────────────────────────────────┐
│                    G300 GLASSES - TOP VIEW                   │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│   LEFT TEMPLE                              RIGHT TEMPLE      │
│   ┌─────────────────┐                  ┌─────────────────┐  │
│   │                 │                  │                 │  │
│   │  [BUTTON] ←─────┼──── Physical     │    [TOUCH]      │  │
│   │  (Top)          │     Button       │    Surface      │  │
│   │                 │                  │                 │  │
│   │  Speaker        │                  │    Speaker      │  │
│   │  Battery        │                  │    MCU/BLE      │  │
│   └─────────────────┘                  └─────────────────┘  │
│                                                              │
│   Physical Button: Press for actions                        │
│   Touch Surface: Tap/Swipe gestures                        │
└─────────────────────────────────────────────────────────────┘
```

## The "Unused" Left Temple Button

The physical button on the **left temple top** is currently mapped to specific functions by the glasses firmware. Based on the SDK, this button likely triggers one of these notifications:

| Press Type | Possible Notification | Current Behavior |
|------------|----------------------|------------------|
| Short Press | `0x02` (AI Photo) | Captures photo for AI analysis |
| Long Press | Power On/Off | System control |
| Double Press | `0x03` (Microphone) | Voice activation |

## How to Capture Button Events in Your App

### Step 1: Register Notification Listener

```kotlin
// In GlassesManager.kt or your activity

private val deviceNotifyListener = object : GlassesDeviceNotifyListener() {
    override fun parseData(cmdType: Int, response: GlassesDeviceNotifyRsp) {
        val eventType = response.loadData[6].toInt() and 0xFF
        
        Log.d(TAG, "Button Event: 0x${String.format("%02X", eventType)}")
        Log.d(TAG, "Full data: ${response.loadData.joinToString(" ") { 
            String.format("%02X", it) 
        }}")
        
        when (eventType) {
            0x02 -> handlePhotoButton(response)
            0x03 -> handleMicrophoneButton(response)
            0x0C -> handlePauseButton(response)
            // Add more handlers as you discover events
            else -> {
                // Log unknown events to discover new button mappings
                Log.d(TAG, "Unknown event: 0x${String.format("%02X", eventType)}")
            }
        }
    }
}

// Register on initialization
LargeDataHandler.getInstance().addOutDeviceListener(100, deviceNotifyListener)
```

### Step 2: Handle Button Events

```kotlin
private fun handlePhotoButton(response: GlassesDeviceNotifyRsp) {
    // Check sub-type for different press patterns
    if (response.loadData.size > 9) {
        val subType = response.loadData[9].toInt() and 0xFF
        
        when (subType) {
            0x01 -> onShortPress()   // Possible short press
            0x02 -> onAIPhotoReady() // AI photo captured
            else -> Log.d(TAG, "Photo subType: $subType")
        }
    }
}

private fun handleMicrophoneButton(response: GlassesDeviceNotifyRsp) {
    val micState = response.loadData[7].toInt() and 0xFF
    
    when (micState) {
        1 -> onMicrophoneActivated()  // Start listening
        0 -> onMicrophoneDeactivated() // Stop listening
    }
}

private fun handlePauseButton(response: GlassesDeviceNotifyRsp) {
    // 0x0C = Pause event (e.g., user pressed to pause TTS)
    val pauseState = response.loadData[7].toInt()
    if (pauseState == 1) {
        onPauseRequested()
    }
}
```

## Discovering Unknown Button Events

To find what the "unused" button does, add comprehensive logging:

```kotlin
override fun parseData(cmdType: Int, response: GlassesDeviceNotifyRsp) {
    // Log EVERYTHING to discover button mappings
    Log.d(TAG, "=== BUTTON/NOTIFICATION EVENT ===")
    Log.d(TAG, "cmdType: $cmdType")
    Log.d(TAG, "loadData size: ${response.loadData?.size}")
    Log.d(TAG, "loadData hex: ${response.loadData?.joinToString(" ") { 
        String.format("%02X", it) 
    }}")
    
    // Parse known positions
    response.loadData?.let { data ->
        if (data.size >= 7) Log.d(TAG, "Event type [6]: 0x${String.format("%02X", data[6])}")
        if (data.size >= 8) Log.d(TAG, "Param 1 [7]: 0x${String.format("%02X", data[7])}")
        if (data.size >= 9) Log.d(TAG, "Param 2 [8]: 0x${String.format("%02X", data[8])}")
        if (data.size >= 10) Log.d(TAG, "Param 3 [9]: 0x${String.format("%02X", data[9])}")
    }
    Log.d(TAG, "=================================")
}
```

Then:
1. Connect to glasses
2. Run `adb logcat | grep "BUTTON\|NOTIFICATION"`
3. Press the physical button
4. Check what event code appears

## Custom Actions You Can Trigger

Once you identify the button's event code, you can map it to any action:

### Example: Map Button to Start/Stop AI Conversation

```kotlin
private var isConversationActive = false

private fun handlePhysicalButton(response: GlassesDeviceNotifyRsp) {
    if (isConversationActive) {
        // Stop conversation
        voiceService?.stopListening()
        voiceService?.stopSpeaking()
        isConversationActive = false
        speak("Conversation ended")
    } else {
        // Start conversation
        voiceService?.startListening()
        isConversationActive = true
        speak("I'm listening")
    }
}
```

### Example: Map Button to Quick Actions

```kotlin
private var pressCount = 0
private var lastPressTime = 0L

private fun handlePhysicalButton() {
    val now = System.currentTimeMillis()
    
    if (now - lastPressTime < 500) {
        pressCount++
    } else {
        pressCount = 1
    }
    lastPressTime = now
    
    // Debounce and determine action
    Handler(Looper.getMainLooper()).postDelayed({
        if (System.currentTimeMillis() - lastPressTime >= 500) {
            when (pressCount) {
                1 -> onSinglePress()   // Take photo
                2 -> onDoublePress()   // Start voice
                3 -> onTriplePress()   // Quick action menu
            }
            pressCount = 0
        }
    }, 500)
}

private fun onSinglePress() {
    glassesManager?.captureAIPhoto()
}

private fun onDoublePress() {
    voiceService?.startListening()
}

private fun onTriplePress() {
    // Custom quick action - e.g., read notifications
    readPendingNotifications()
}
```

## Known Notification Event Codes

| Code | Event | Data Structure |
|------|-------|----------------|
| `0x02` | AI Photo Ready | `loadData[9] == 0x02` = photo captured |
| `0x03` | Microphone | `loadData[7] == 1` = mic on, `0` = off |
| `0x04` | OTA Progress | `loadData[7-9]` = progress values |
| `0x05` | Battery | `loadData[7]` = level, `loadData[8]` = charging |
| `0x0C` | Pause | `loadData[7] == 1` = pause requested |
| `0x0D` | Unbind | `loadData[7] == 1` = unbind requested |
| `0x0E` | Low Storage | Storage warning |
| `0x10` | Translation Pause | Translation paused |
| `0x12` | Volume Change | `loadData[8-10]` = music, `[12-14]` = call |

## Configuring Touch Gesture Mode

The SDK allows changing what touch gestures do:

```kotlin
// Set touch gesture mode (if supported by your SDK version)
enum class TouchGestureMode(val value: Byte) {
    OFF(0x00),
    MUSIC(0x01),
    VIDEO(0x02),
    EBOOK(0x04),
    TAKE_PHOTO(0x05),
    PHONE_CALL(0x06),
    GAME(0x07),
    HR_MEASURE(0x08)
}

fun setTouchGestureMode(mode: TouchGestureMode) {
    val command = byteArrayOf(
        0x02,  // Command prefix
        0x28,  // Touch control command (QCDeviceDataUpdateTouchControl)
        mode.value
    )
    
    LargeDataHandler.getInstance().glassesControl(command) { _, response ->
        Log.d(TAG, "Touch mode set: ${response.dataType}")
    }
}
```

## Testing Your Button Mappings

1. **Build and install the app** with logging enabled
2. **Connect to glasses** via Bluetooth
3. **Start monitoring logs**:
   ```bash
   adb logcat | grep -E "(GlassesManager|BUTTON|NOTIFICATION)"
   ```
4. **Press the physical button** on the left temple
5. **Observe the event code** in the logs
6. **Map the event** to your desired action

## Troubleshooting

### Button press not detected
- Ensure notification listener is registered AFTER connection
- Call `reRegisterListener()` after connection established
- Check that `addOutDeviceListener()` was called with valid ID

### Events detected but not the expected button
- The physical button might be mapped at firmware level
- Some buttons may require specific SDK versions
- Try different press durations (short, long, double)

### Need more control?
- Contact HeyCyan for firmware customization options
- The SDK may have undocumented commands for button mapping
- Consider using touch gestures instead (more configurable)

## Future Improvements

If you discover the exact event code for the left temple button, please update this document and submit a PR to help others!
