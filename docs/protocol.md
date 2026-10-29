# Device Protocol

The protocol is a prototype contract shared by `ESP32/WorkingExample.ino` and the Android app. All network traffic is currently unauthenticated and unencrypted.

## Discovery

Android broadcasts the UTF-8 message `WHO_IS_ESP32_CAM` over UDP port `4210`. The ESP32 replies to the sender with `ESP32_CAM:<ip-address>`. Android accepts only a valid IPv4 address from the reply. If discovery fails, Android tries `esp32cam.local` and then `192.168.4.1` for the ESP32 soft AP. Each candidate must return a successful `/health` response identifying itself as `smart-assistive-reader` before the UI reports a connection.

## HTTP endpoints

| Method and path | Response |
| --- | --- |
| `GET /health` | JSON device name/version, network mode, IP, and endpoint paths. |
| `GET /capture` | A single `image/jpeg` frame. |
| `GET /stream` | `multipart/x-mixed-replace` MJPEG stream with boundary `frame`. |

The Android client currently consumes `/stream`. The still capture and health endpoints are available for diagnostics and future client work.

**Limitation:** the ESP-IDF HTTP server handles requests on a single worker task, and `/stream` keeps that task busy for as long as a client is connected. While a phone is streaming, `/health` and `/capture` are not served (they respond again once the stream ends). This follows from how the server is built; it has not been measured on hardware. The firmware sets 5 s send/receive timeouts and enables LRU socket purging so a stalled or vanished client cannot block new connections indefinitely.

## Button events

The ESP32 hosts a WebSocket server on port `81`. It sends the plain-text messages `READ`, `CONT`, and `STOP`. These events are separate from the MJPEG body; they must not be inserted into JPEG or multipart data.

The gesture-to-event mapping is implemented in `ESP32/GestureDetector.h` and unit-tested on a normal computer (`tests/firmware/run_tests.sh`):

| Gesture on the camera button (GPIO 13 to GND) | Event | Notes |
| --- | --- | --- |
| Single click | `READ` | Sent once the 350 ms double-click window has passed. |
| Double click | `CONT`, or `STOP` if continuous mode was on | The firmware tracks continuous mode locally. |
| Hold for 800 ms | `STOP` | Sent while the button is still held, and also clears the firmware's continuous-mode flag. |

The button is debounced (30 ms), so contact chatter does not produce phantom clicks, and the timing is safe across the 49-day `millis()` wrap.

Because only the firmware knows when the button is double-clicked, it cannot see a mode the phone entered with its on-screen buttons. After switching modes on the phone, the first double-click may therefore send `CONT` for a mode that is already on; a second double-click toggles it off.

### Android reconnect behaviour

The Android app keeps this WebSocket alive. If it drops (Wi-Fi blip, router restart, device reboot) the app reconnects after 1 s, then 2 s, 4 s and every 8 s thereafter until the screen is left or the user switches to Simulation mode, and sends WebSocket pings every 10 s so a half-open connection is noticed. Losing and regaining the link is announced aloud, so a person who is not looking at the screen knows why the button stopped working.

## Compatibility and security

The reported firmware version is currently `1.0.0`; there is no negotiated protocol version or token authentication. Keep the phone and camera on the same trusted local network. Do not expose the device ports to the internet. Android retries both the MJPEG connection and the WebSocket event connection after failures.