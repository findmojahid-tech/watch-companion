# Momo V8 — verify the watch microphone, not just the speaker

The user's Watch 7 firmware 4.21 successfully established a direct BLE link
and reported battery 75%. The user confirmed that V7's **Test speaker** speech
played from the watch, not the phone.

This proves *Bluetooth output audio works*. It does **not** prove the watch
microphone supplies recognition audio. Android may independently route output
and input, and SpeechRecognizer services may use their own capture pipeline.

## Added: Test watch microphone (3 sec)

- On the app's **Connect your Watch 7** card, select **Watch call**.
- Stop **Momo Listening** before testing to avoid microphone contention.
- Tap **Test watch microphone (3 sec)**, keep the phone far away and speak
  near the watch.
- The test creates an `AudioRecord` session with
  `MediaRecorder.AudioSource.VOICE_COMMUNICATION` (16 kHz / 8 kHz mono PCM).
- The app asks Android to prefer its available Bluetooth headset input, if any.
- It looks at **AudioRecord.routedDevice** *after recording begins*.
- Only if the reported input device is `TYPE_BLUETOOTH_SCO` or
  `TYPE_BLE_HEADSET` does the app report **WATCH MICROPHONE CONFIRMED**.
- If another route is reported, the result identifies it as the phone or
  another microphone, instead of claiming the watch mic works.
- If the OS does not report an input device, it explicitly says
  **microphone route unknown**. An accepted preference is NOT proof of routing.
- It reports input sound level in dBFS, but **never writes microphone samples
  to storage or uploads sound**.
- Stop/release the `AudioRecord` session after around three seconds.

This is a **diagnostic**, not a full Bluetooth speech-recognition rewrite.
Android's built-in `SpeechRecognizer` may still use a different input route,
and always-on wake listening remains constrained by the OS. The app will not
claim hands-free watch input until separately demonstrated.

## Safety

- No vendor app, no OTA firmware updates, no unverified BLE writes.
- No root, no admin, no bootloader modification.
- Only existing Microphone + Nearby devices permissions.
- The V8 debug APK uses `.momo8` and installs beside V7, avoiding data loss.
- The watch should stay paired and connected through the Android Bluetooth
  system as before; the test does not alter GATT services.
