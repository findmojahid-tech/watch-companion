# TIFFY AI — JARVIS companion foundation

This branch builds on `integration/momo-final` without resetting the existing Pixel 7 app, task database, BLE pairing, or firmware protocols.

## Implemented in source
- Voice wake phrase `Tiffy` / `Hey Tiffy`, with exact-match parsing and updated Android foreground notification.
- App label and visible voice/home branding use **Tiffy**.
- Optional **OpenAI Responses API** text brain, short conversational answers with `gpt-4.1-mini`, a three-turn history window and provider-error redaction.
- Independent Android Keystore AES-GCM key storage for OpenAI and Gemini. Existing Gemini key data stays in its original namespace.
- Provider priority: OpenAI → existing Gemini → verified on-device offline model.
- Basic OpenAI text parsing/security tests and updated wake-word tests.
- Existing watch GATT and audio implementation untouched; no watch OTA/dial writes introduced.

## Important limits and acceptance
- This is **source implementation, not a finished release**. The new branch needs Android CI, real Pixel 7/wrist speech latency tests and stable private signing before an owner-facing APK.
- Native real-time interruptible duplex audio is **not** yet integrated; existing watch microphone uses Android speech recognition and existing Android TextToSpeech voice output. A low-latency WebRTC voice session with short-lived backend credentials is future work.
- Android background speech may stop when the phone is locked. Don't promise always-on hands-free wake on this device until physical tests pass.
- W17 Pro firmware 4.21 custom watch-face transfer remains unverified. No actual animated face on the watch is claimed.
- Home Assistant/Matter integration, phone actions, calendar, web browsing, and real-time device control are **not** yet implemented. Future actions must be explicit and permission-gated.
- OpenAI API access is separately billed; ChatGPT subscriptions and chat history are not embedded automatically. A personal API key entered on-device works only as a prototype; a private backend holding the key is recommended before any shared or production release.
- No new APK or forced reinstall is part of this branch.

## Suggested physical acceptance sequence
1. Validate API key → typed Tiffy chat with short answers.
2. Verify Gemini fallback and offline recovery without losing task history.
3. Test Watch call audio route and English/Bengali speech recognition separately.
4. Measure utterance → recognized text → first AI text → watch TTS audio timings.
5. Check screen-off wake behavior, Bluetooth reconnect, interrupted phone calls, memory/power.
6. Do not merge or publish as a final signed application until release gates are satisfied.
