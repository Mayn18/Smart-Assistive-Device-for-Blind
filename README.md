# Smart Assistive Reader

A prototype that pairs an ESP32-CAM with an Android app to capture printed text, recognize it on-device, optionally translate it, and read it aloud.

## Current behavior

- The ESP32-CAM serves an MJPEG feed at `/stream` and a still image at `/capture` over HTTP.
- The Android app discovers the device by UDP, with `esp32cam.local` and the ESP32 soft-AP address as fallbacks.
- The camera button sends `READ`, `CONT`, and `STOP` events over a WebSocket on port 81.
- Android runs ML Kit text recognition and Android Text-to-Speech. Translation is optional and may need a model download.
- The app starts in clearly labeled Simulation mode with a bundled printed-text image. It runs the same OCR and speech flow without attempting a device connection; uncheck Simulation mode to search for a real ESP32-CAM.
- Real-device discovery is reported as connected only after the firmware `/health` endpoint responds with the expected device identity.

This repository does not currently implement object detection, navigation assistance, voice commands, reading history, or a guided setup flow.

## Hardware and setup

1. Use an ESP32-CAM board with an OV2640 camera and a supported Arduino ESP32 core.
2. Copy `ESP32/secrets.h.example` to `ESP32/secrets.h` and set your Wi-Fi credentials. Keep `secrets.h` out of version control.
3. Flash `ESP32/WorkingExample.ino` with the required ESP32 camera and WebSockets libraries.
4. Open the `android/` directory in Android Studio and build/install the app.
5. Put the phone and ESP32 on the same local network, or connect to the ESP32 soft AP if station-mode Wi-Fi is unavailable.

The app needs local network access. The current device endpoints use cleartext HTTP and unauthenticated WebSockets; use only a trusted local network. See [Security](docs/security.md) and [Protocol](docs/protocol.md).

## Testing

From `android/` run:

```sh
./gradlew testDebugUnitTest assembleDebug
```

The automated unit tests cover text cleanup, speech suppression, and WebSocket event parsing. The bundled OCR sample can be exercised on an Android device or emulator in Simulation mode. Camera streaming, Wi-Fi discovery, OCR quality, translation downloads, and speech output should also be checked on real hardware; host-side unit tests do not validate those integrations.

Firmware can be compiled without a board using PlatformIO from the repository root:

```sh
cp -n ESP32/secrets.h.example ESP32/secrets.h
pio run -e esp32cam
```

The sample credentials are compile-only placeholders; replace them only in your ignored local `ESP32/secrets.h` before flashing.

## Limitations and safety

OCR can be inaccurate, especially with blur, glare, small text, or poor lighting. Translation may require downloading a language model. Hardware and network behavior have not been validated across all ESP32-CAM boards and routers.

This prototype is for stationary printed-text reading assistance only. It is not a mobility aid, navigation system, emergency device, or replacement for a white cane or trained mobility assistance.
