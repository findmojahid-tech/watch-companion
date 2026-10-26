# Momo W17 V5 — practical setup

This is a **phone-powered** English-speaking AI companion for a W17 Pro / Watch 7.
It does not replace the watch firmware or install an Android app directly on the watch.

## V5 addresses these V4 problems

1. Regular questions go to **plain-English Gemini chat**, not a structured task-command JSON parser.
2. The AI setup button **verifies the key against Google and gets a real response before saving it**.
3. The user sees specific HTTP 401/403/404/429, network and model-availability messages.
4. General chat has up to eight turns of recent in-app context.
5. BLE scanner offers paired / last-seen devices even when no new BLE advertisement is broadcast.
6. The watch page provides an original **320x390 static Momo image** for MActivePro photo-dial upload.
7. The opt-in experimental wake service also uses general English AI.

## Install

Open GitHub Actions on pull request #5, download the last successful `WatchCompanion-debug-apk` artifact, unzip and install `app-debug.apk` on Pixel 7.

**Important: V5 has a different debug application ID from V3/V4**, because each cloud build's temporary debug signing certificate can differ. V5 therefore installs **side by side** to preserve your existing apps' tasks/keys. Enter your personal AI Studio key once more in V5. Do not delete V3/V4 until you have verified V5.

## Free AI connection (key never goes into GitHub)

1. Launch **Momo W17 AI V5**.
2. Open **AI Setup**.
3. Tap **Open Google AI Studio** and obtain a free-tier eligible key.
4. Paste the key; tap **Verify & save new API key**.
5. A *successful* status includes a live model name and its English test response.
6. If a key was stored already, choose **Test saved key**.
7. On the Momo tab, type *Who discovered gravity?* or *Explain solar eclipses*.
8. Tap to Speak and ask the same question in English (choose English India or US accent).

Google limits free quotas and may use free-tier inputs to improve its products. V5 sends only recognized text to Gemini, although the Android speech recognizer may use network-based transcription. No external API key is stored in the public GitHub repository.

## Make the watch visually Momo

1. In V5, go to **Watch → 1. Save Momo face for Watch 7**.
2. The app creates an original **320 x 390 PNG** under `Pictures/Momo`.
3. Tap **2. Open MActivePro watch-face app** (the verified W17 companion app, `com.njj.mactivepro`).
4. Connect the `Watch 7` BLE side inside MActivePro.
5. Go to the Watch Face marketplace and select the **Custom / Album / Photo Dial** option, then choose the saved PNG. Names vary by MActivePro version and firmware.
6. Sync it to the watch.

On compatible watches, the new photo is visible on the actual watch screen. **This is static**, not a programmable live face. Whether W17 Pro supports animated/video dials remains unverified.

## Make the watch speak English

1. Pair the separate Bluetooth **Watch call** audio profile in Android Bluetooth settings.
2. Go to **Watch → Watch call → Test watch speaker**.
3. If speech comes from the watch, return to Momo and tap Speak, or opt into experimental Wake Mode and try "Hey Momo".
4. Android may pick the phone microphone or block persistent voice sessions; this cannot be guaranteed on this watch without device-level testing.

## BLE diagnostics

Watch 7 had the standard 1800, 1801, 180A, 180F and 1812 services; `a6ed0401` is a Goodix OTA/DFU channel. It is **not** a watch-face API. V5 never sends commands or OTA payloads to unknown characteristics.

If live on-watch animation or truly phone-independent conversation is required, the manufacturer firmware/app protocol or verified on-watch runtime access is needed. Standard Bluetooth call audio and a photo dial alone do not provide that capability.
