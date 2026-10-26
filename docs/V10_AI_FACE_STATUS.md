# Momo V10 — AI key fix & W17 display compatibility audit

## Verified evidence from the user's Watch 7

The Microwear W17 Pro has:
- Android `Watch 7` BLE direct connection without MActivePro.
- `Watch call` output to its speaker (user verified).
- Bluetooth headset input on the watch (`AudioRecord.routedDevice` showed
  `Watch call`, at -12.5 dBFS) (user verified).
- GATT manufacturer field `Watch 7`, firmware `4.21`, hardware `1.0`
  (reported by device, not necessarily true chipset manufacturer).
- Vendor BLE services `6e400004-...` and `6e40000a-...`;
  `a6ed0401-...` (known Goodix OTA/DFU service).

This is **not enough evidence to upload an arbitrary image to the watch**.
The Goodix OTA service can update software/user data and is hazardous to use
blindly. We must identify the watch's own dial-image codec, upload frame
format, characteristic, authentication and status ACKs before sending data.

### What is NOT an identified protocol

A public Jieli/SMA third-party dial toolkit called Fogg uses the classic
Nordic UART service `6e400001` with write char `6e400002` and notify char
`6e400003`. **Neither that service nor that layout appears in the user's
Watch 7 profile.** It would be unsafe to copy its payloads to `6e400004`
or `a6ed0401`, even though the UUIDs share a similar prefix.

Sources:
- Goodix GR551x DFU Application Note:
  https://www.goodix.com/en/docview/GR551x%20DFU%20Application%20Note?objectId=62&objectType=document&version=171
- Fogg BleUuids.java:
  https://github.com/MiguelDLM/Fogg/blob/main/dial-sender/app/src/main/java/com/example/dialsender/ble/BleUuids.java
- Microwear W17 Pro public screen specification: 320 x 390 pixels.
  https://www.remaxbangladesh.com/Microwear-W17-Pro-Smart-Watch

## V10 changes: AI actually answers

- V9 incorrectly favored the local 0.6B model when installed, even with a
  saved Gemini key. V10 now uses **Gemini first when a key is configured**,
  then falls back to local offline inference if Google fails.
- The **AI Brain** is visible on the main screen (not buried in Advanced).
  Tap Paste key once, then Connect Gemini; the key is **verified with a live
  request before being encrypted and saved on the Pixel 7**.
- Gemini verification now calls `generateContent` directly, instead of
  depending on a separate `models?pageSize=1000` list response.
- Fast model preference `gemini-3.5-flash-lite`, with
  `gemini-3.1-flash-lite` fallback only for HTTP 404. The model config uses
  `thinkingLevel=minimal` and short speech-oriented replies for latency.
- A saved key can be tested or removed from the same screen. Invalid keys,
  quota errors, network failures and unavailable models produce explicit
  messages. There are **no hard-coded API keys, no GitHub secrets, and no
  plaintext keys committed to the public repository**.
- English wake-service replies also prefer the configured Gemini, then
  the on-device fallback. Android SpeechRecognizer remains experimental for
  continuous wake listening, especially with screen off.

**Do not share your API key with ChatGPT.** In this prototype the only place
to enter it is inside the app's main screen. It is stored via Android Keystore
AES-GCM only after successful verification. Direct client-side API requests
are still less secure than a server-side authenticated proxy; use limited
quotas/restrictions on the key and do not distribute this app configured with
another person's credentials.

## V10 changes: watch face

- The Momo 320 x 390 static PNG can be generated and inspected locally.
- A visible **Momo face on Watch 7** panel clearly distinguishes a prepared
  image from an uploaded face.
- When Watch 7 is connected, the app shows firmware and vendor service counts
  and supports **Copy watch-face protocol report** including the GATT
  characteristic properties (READ / WRITE / NOTIFY / INDICATE).
- No firmware/OTA commands, unknown BLE writes, nor automatic face upload
  are performed. The public documents researched do **not** yet establish
  the W17 Pro dial-transfer protocol.
- This APK CANNOT yet show a custom Momo face on the physical W17 screen
  by itself. That work is blocked on vendor protocol identification; do not
  present preparation/export as successful watch display control.

## Installation & privacy

V10 has separate debug package ID ending `.momo10` because past CI debug
APKs use unstable signing credentials. Existing apps and their tasks are not
overwritten. The user must re-enter the API key once in V10; it does not sync
across app sandboxes, by design. The owner may eventually configure stable
release signing through a private GitHub Actions secret.

No mobile phone rooting, bootloader modification, custom ROM, system
partition write, or watch firmware changes are part of this build.

## Next safe steps to unlock the actual watch display

1. Obtain a protocol trace or published SDK for **this Watch 7 firmware**.
   Watch face upload commands must be derived from the matching vendor
   application or a paired reference device; generic Fogg/Goodix examples
   aren't suitable substitutes.
2. Verify a reversible *static dial transfer* under informed user consent and
   with a copy of the watch's current dial/firmware if available.
3. Only then consider animated Momo eyes. A watch with photo-dial support
   may not have any API for frame-by-frame live animation.

This document intentionally records a current limitation instead of
promising unsupported firmware access.
