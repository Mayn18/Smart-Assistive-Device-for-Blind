# Device Protocol

The protocol is a prototype contract shared by `ESP32/WorkingExample.ino` and the Android app. All network traffic is currently unauthenticated and unencrypted.

## Discovery

Android broadcasts the UTF-8 message `WHO_IS_ESP32_CAM` over UDP port `4210`. The ESP32 replies to the sender with `ESP32_CAM:<ip-address>`. If discovery fails, Android tries `esp32cam.local` and then `192.168.4.1` for the ESP32 soft AP.

## HTTP endpoints

| Method and path | Response |
| --- | --- |
| `GET /health` | JSON device name/version, network mode, IP, and endpoint paths. |
| `GET /capture` | A single `image/jpeg` frame. |
| `GET /stream` | `multipart/x-mixed-replace` MJPEG stream with boundary `frame`. |

The Android client currently consumes `/stream`. The still capture and health endpoints are available for diagnostics and future client work.

## Button events

The ESP32 hosts a WebSocket server on port `81`. It sends the plain-text messages `READ`, `CONT`, and `STOP`. These events are separate from the MJPEG body; they must not be inserted into JPEG or multipart data.

## Compatibility and security

The reported firmware version is currently `1.0.0`; there is no negotiated protocol version or token authentication. Keep the phone and camera on the same trusted local network. Do not expose the device ports to the internet. Android retries the MJPEG connection after failures; WebSocket events currently have no automatic reconnect loop.