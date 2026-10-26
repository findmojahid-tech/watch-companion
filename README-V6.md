# Momo V6 — single-screen Pixel 7 + Watch 7 assistant

This is a **phone-powered** Momo that pairs with your existing Bluetooth watch. It cannot install an Android app onto W17 Pro or override the watch firmware.

## What changed from V5

- **Portrait layout**: one scrollable, status-bar-safe Compose screen; the old clipped tabs are not used.
- **One voice control**: Start Momo Listening / Stop Momo Listening; says "Yes?" to either "Momo" or "Hey Momo". No multiple wake-word options.
- **No API key by default**: one-time download of open Qwen3 0.6B no-thinking 4-bit (about **347 MB**) to the Pixel 7, then general short English questions run locally using LiteRT-LM.
- **Fast local tasks**: add/complete/list saved tasks without calling any model.
- **Voice answer routing**: local task engine first, offline Qwen model second, optional personal Gemini key only if offline model isn't installed.
- **Watch setup**: select Android "Watch call" communication device, test TTS, export original Momo PNG and open MActivePro for a photo dial.
- **Safe experiment**: paired-watch BLE discovery and read-only inspection in a collapsed Advanced section. **No unknown characteristic writes, DFU, firmware upload, root, bootloader changes or accessibility privilege**.
- **Separate Android debug app ID** from V5 so previous tasks/keys are not deleted by installing this experiment.

## Key-free AI engine

- Android Kotlin runtime: `com.google.ai.edge.litertlm:litertlm-android:0.18.0`.
- Model: `litert-community/Qwen3-0.6B-int4`, **no-thinking** variant:
  `qwen3_0.6b_nothink_q4_block32_ekv1280.litertlm`
- License: Apache-2.0, inherited from Qwen3.
- One-time model URL: `https://huggingface.co/litert-community/Qwen3-0.6B-int4/resolve/main/qwen3_0.6b_nothink_q4_block32_ekv1280.litertlm`
- Expected SHA256: `2df6821ec12702dafd33915e7a1a1adc7c4b053f3672fd9555dfaf3a114c4139`.
- Source: https://huggingface.co/litert-community/Qwen3-0.6B-int4
- Model is downloaded only after pressing Download free AI, and is verified before inference.
- It stays in app-specific external storage, never in the APK. "Remove downloaded offline model" deletes only that file.
- Local inference runs on the phone's CPU, uses one dedicated background executor and one shared conversation.
- No exact performance or quality claims for Pixel 7 until tested on-device.

### Known limitations

- Qwen3 0.6B is a **small** model. Complex math, specialized subjects, live news and long-form reasoning may be inaccurate; it cannot answer literally every question correctly.
- Android's `SpeechRecognizer` is **not designed for continuous recognition**. Momo's microphone foreground service is explicitly opt-in with a visible notification and may be suspended or stopped by Android, especially when locked or after reboot. It cannot silently auto-start from boot due Android foreground-microphone restrictions.
- Bluetooth HFP audio route can vary by Android device/watch firmware. The watch microphone may not always be selected as the STT source.
- W17's original custom photo face is static. A live expressive face synchronized with conversation would require separately verified watch-firmware protocol support. The custom BLE report includes a Goodix OTA/DFU channel; this application will not write to it.
- **Odysseus is a self-hosted AI workspace**, not a mobile-sized model. Its server install isn't required for V6 and doesn't eliminate model runtime requirements.
- The APK build only validates Android compile and packaging. Hardware/wake/audio and model-download tests must be carried out on the Pixel 7.

## Phone setup

1. Install Momo V6 debug APK separately (leave earlier versions installed).
2. Open the app: top is visible below Android status bar in portrait.
3. Connect `Watch call` through Android Bluetooth settings, choose that device in Momo, test speaker.
4. On home tap **Download free AI — no API key** (Wi-Fi recommended, ~347 MB).
5. Wait for **Offline Momo is ready**. Enter a question such as "Why is the sky blue?" and press Ask Momo to test local LLM inference before enabling the mic.
6. Tap **Start Momo Listening** while the app is visible. Say "Momo"; a detected wake should say "Yes?" then accept the next English question.
7. For a photo dial choose **Save Momo face**, then **Open MActivePro** and use Custom/Album Photo Dial when supported by your W17 variant.

## Phone safety

All downloaded model bytes are stored under the new V6 app's own Android folder. No script executes from the download. No root, system partition changes, or firmware writes are performed. Stop the foreground listening service any time from the big button or Android notification. To remove this experiment, uninstall **Momo V6**; previous V5 remains installed.
