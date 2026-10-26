# Momo V7 — direct Watch 7 companion (no vendor app)

This branch makes the **Momo Android app** the primary watch pairing and status
interface. The user does not need to install, open, or pair the watch inside
MActivePro. No activity, intent, package query, link, or runtime dependency
on the vendor's application remains in Momo V7.

## Supported without MActivePro

- One portrait-friendly Momo interface (carried forward from V6).
- **Connect Watch 7**: checks saved / paired devices and establishes a direct
  Bluetooth GATT connection from Momo.
- **Find Watch**: built-in BLE scanner plus already paired watches (so the
  watch need not advertise continuously).
- **Android Pairing**: opens the *phone's built-in* Bluetooth settings, not a
  third-party application.
- Discover services, read **standard** battery characteristic (2A19),
  manufacturer (2A29), firmware (2A26) and hardware revision (2A27) where
  present and permitted. The direct session stays open while the app is visible.
- Select **Watch call** audio for output and supported communication audio.
  GATT data and Bluetooth call audio are separate profiles.
- English voice conversation, task store and optional no-key phone-side
  Qwen3 0.6B local AI from V6. LiteRT-LM runtime still needs real Pixel 7
  device testing (a successful APK build only proves compilation).
- Safe, read-only advanced watch protocol inspection, disabled while the main
  direct GATT connection is open.

## Not yet possible / verified

- **Sending a Momo face to the watch display**: the observed vendor
  service/characteristic UUIDs do NOT reveal a reliable, verified dial-transfer
  protocol for the W17 Pro firmware. The app can export an original Momo face
  PNG locally, but it does not falsely claim to have installed it on the watch.
- **Live animated face**: needs a proven display-control or dial-animation
  protocol. Blind writes to `6E4000xx` are deliberately not allowed.
- **Standalone AI executed by the watch with the phone off**: the W17 Pro's
  onboard app runtime and resources have not been demonstrated to support it.
  This prototype uses the phone's processor for the Qwen model and Android's
  compatible Bluetooth audio routes for voice.
- **Always-on, zero-touch voice** with the phone locked: Android platform
  `SpeechRecognizer` is session-based and can be background-restricted. This
  feature is explicitly labelled experimental.

## Watch safety boundary

V7 does not ask for root / device admin / accessibility / bootloader permission,
and will not flash firmware, issue DFU commands, alter phone partitions,
or write unknown Bluetooth characteristics. The `A6ED0401` vendor service
seen in the user's Watch 7 GATT report is treated as a firmware-update channel,
not a safe watchface upload interface.

## First-run steps on Pixel 7

1. Install V7 APK without uninstalling V6 (different debug application ID).
2. Allow microphone + Nearby devices permissions.
3. Open Momo, tap **Connect Watch 7**; if not found, use **Find Watch** or
   **Android Pairing**, then choose Watch 7 from the watch list.
4. A GATT connection with battery/manufacturer/firmware details confirms the
   direct app-to-watch data link. It does not establish watchface support.
5. In Momo select Bluetooth **Watch call**, then **Test speaker**.
6. Use local Qwen model and say Momo / Hey Momo to exercise voice.
7. Use Advanced → Copy BLE report to identify firmware for further
   **device-specific** watchface protocol research without guessing payloads.

## Why not simply copy a different watch's open-source protocol?

The open-source Fogg, Gadgetbridge, CMF and HT36 projects demonstrate that
third-party watchface transfers can be reverse engineered. But each uses a
device-specific service, command framing, authentication, and file format.
There is currently no reliable protocol-to-device mapping from this Watch 7
UUID set to a known face uploader. Borrowing another brand's raw commands may
brick a watch or corrupt its firmware. Therefore V7 intentionally performs
only standard read operations and direct audio pairing.
