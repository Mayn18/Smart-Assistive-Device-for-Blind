# Security and Privacy

This is an experimental local-network prototype, not a hardened connected device.

## Network exposure

- `/health`, `/capture`, `/stream`, UDP discovery, and the WebSocket event server have no authentication.
- HTTP and WebSocket traffic are unencrypted. Anyone able to reach the camera on the local network may view its camera feed.
- Do not port-forward the device or connect it to a public or untrusted network. Prefer the device's isolated soft AP or a trusted private network.
- Change the soft-AP password from its example value before deployment.

## Credentials and data

- Store Wi-Fi credentials in the ignored `ESP32/secrets.h` file; never commit that file.
- The app performs OCR on-device. Translation can download a model; this project has no application backend.
- The app does not intentionally retain recognized text as reading history.
- A scan of repository history found likely Wi-Fi credential literals in older firmware revisions. Rotate any Wi-Fi credentials that may have been used with this project; deleting them from the latest revision does not remove them from Git history.

## Safety boundary

OCR and camera availability are fallible. This prototype is for stationary printed-text reading assistance only. It is not a mobility aid, navigation system, emergency device, or replacement for a white cane or trained mobility assistance.