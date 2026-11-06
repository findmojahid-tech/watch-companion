# TIFFY × CMF Watch Pro D395 — Android audio-first integration

## Model and owner-confirmed starting point

- CMF by Nothing Watch Pro, model **D395** (original Watch Pro).
- Owner reports it is paired with the **CMF Watch app** on **Pixel 7** and Bluetooth calls work in both directions.
- This proves the watch has a usable HFP call profile, **not** yet that an independent Android SpeechRecognizer session captures its mic or that Tiffy voice responses are routed to it reliably.
- Nothing/CMF official support: https://support.cmf.tech/hc/en-us/articles/26215012632849-Does-Watch-Pro-support-Bluetooth-calls
- D395 screen: 410 × 502 from https://support.nothing.tech/hc/en-us/categories/25888172835217

## Tiffy implementation in this branch

1. **CMF mode is the default** for new Tiffy installs; existing tasks and cloud keys stay in their existing app data.
2. Does **not** initiate the Watch 7 direct proprietary GATT connection in CMF mode. The CMF Watch app remains the watch's management app.
3. Uses Android `AudioManager.availableCommunicationDevices` for selectable Bluetooth HFP/LE-headset communication routes. No BLE Speaker-only device is offered as an input.
4. `WatchAudioRouting` recognizes safe "Watch Pro" / D395 / CMF Call labels, refuses to pick CMF Buds, and rejects ambiguous multiple watches.
5. When Talk through CMF Watch Pro is tapped, it requests `MODE_IN_COMMUNICATION`, selects the call-audio route, then tries the existing Android 13+ `WatchSpeechBridge` watch microphone capture → Android speech recognition → OpenAI/Gemini/local task engine → TTS output.
6. Optional wake service uses the exact user-selected audio label; if that route disconnects, it doesn't silently substitute the phone mic.
7. The **D395 Tiffy static face** is a local 410 × 502 PNG in `Pictures/Tiffy`; apply with the official CMF Watch app's **Watch Face marketplace → My → Customize Watch Face**. This is a background, **not** a dynamically animated watch app.
8. W17/Watch 7 GATT experiments are isolated behind legacy mode. **Never** run W17 packets or speculative vendor writes on D395.

## Hardware acceptance gates (not yet completed)

- On Pixel 7, confirm that the selected `AudioDeviceInfo` corresponds to D395's HFP call profile (not CMF Buds).
- Use the 3-second microphone check, then a full English utterance from the physical D395 with Pixel 7 placed away. Verify the Android AudioRecord input route *and* recognized words.
- Check OpenAI key setup separately (ChatGPT subscription is not an API key); measure first audio reply on the watch, not the phone.
- Validate repeated wake, lock-screen behavior, interruptions/calls, reconnect, Android permission prompts, latency and power draw.
- Export a Tiffy dial, select it in the official app, verify the physical watch display. No unsupported claim of animated eyes.
- Keep original CMF Watch pairing. No root, vendor token extraction, firmware changes or device resets.

## Limitations

The D395 is not Wear OS, so Tiffy code still runs on the Pixel 7. On-device model inference, direct fullscreen animation and continuous hands-free speech on the watch are **not supported/verified**. Android SpeechRecognizer may ignore injected watch audio on some builds. This work is a safe integration candidate pending physical acceptance, not a finished JARVIS product or signed release.
