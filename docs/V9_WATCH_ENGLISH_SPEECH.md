# Momo V9 — Watch call microphone -> English speech -> watch answer

## Evidence on the real W17 / Watch 7

- Momo V7 paired with Watch 7 directly over BLE without MActivePro.
- Bluetooth call speaker played Momo's English TTS (user confirmed).
- Momo V8 AudioRecord diagnostics reported:
  `WATCH MICROPHONE CONFIRMED`
  and sound input level `-12.5 dBFS` through the `Watch call` device.

This confirms Android sees the watch as a Bluetooth microphone. It does **not**
prove the built-in SpeechRecognizer selects the watch input automatically.

## V9 new feature

The **Watch microphone verification** card now has:

**Ask Momo using watch microphone**

1. Select `Watch call` as the communication audio device.
2. Turn *Stop Momo Listening* OFF so there is only one active audio capture.
3. Press `Ask Momo using watch microphone`.
4. Keep your Pixel 7 away from your mouth and speak an English sentence into the watch within 9 seconds.
5. Momo creates a PCM `AudioRecord` for the requested Bluetooth headset input.
6. V9 checks `AudioRecord.routedDevice` while recording and rejects a *different* device route rather than quietly accepting phone audio.
7. Android 13+ `RecognizerIntent.EXTRA_AUDIO_SOURCE` receives the PCM as a private `ParcelFileDescriptor` pipe: 16-bit mono, 16 or 8 kHz.
8. Android's speech recognizer returns transcripts to the app. The app shows **Heard through test: ...** and the capture route.
9. If input was genuinely routed through the selected Bluetooth mic and English text was recognized, Momo passes that text to the existing local task engine or free on-device Qwen model. Replies are spoken through the currently selected Watch call speaker.

The PCM is held in RAM only and discarded immediately after the test.
The application doesn't record to a file or upload the microphone audio.
**Important:** Android's speech recognition provider may independently send audio to its own cloud servers. The recognizer implementation may also ignore
`EXTRA_AUDIO_SOURCE` and open its own microphone; the Pixel 7 must be tested
to establish that spoken English originates at the watch. The app never treats
an available external audio source as absolute proof the recognizer used it.

## First test without downloading the language model

Say: **"Add a task to buy milk."**

This uses the local task engine and should speak a short acknowledgment through
the watch without an AI model.

For general knowledge, first press **Download free AI** on the home screen,
wait for the 347MB model to complete, then ask e.g. **"Why is the sky blue?"**
The small Qwen3 0.6B offline model is not guaranteed to answer advanced
questions correctly; first model inference may have noticeable latency.

## Current limits

V9 is a **one-shot explicit watch microphone speech test**, not yet reliable
always-listening hands-free wake word from the watch.
The foreground `SpeechRecognizer`-based `Hey Momo` mode remains experimental
and isn't guaranteed to work when Android is locked or in the background.
If V9's direct watch-mic transcription works on-device, the same capture route
can be validated for a production offline wake-word engine later.

The watch screen photo face uploader and fully autonomous on-watch inference
are still blocked by unverified proprietary firmware limitations.
No firmware writes, OTA operations, vendor control commands, root, or phone
system changes are performed. V9 installs alongside V8 under a new debug
application ID for data preservation.

## Android API

Android Developers: https://developer.android.com/reference/android/speech/RecognizerIntent#EXTRA_AUDIO_SOURCE
Android Developers: https://developer.android.com/reference/android/speech/SpeechRecognizer
