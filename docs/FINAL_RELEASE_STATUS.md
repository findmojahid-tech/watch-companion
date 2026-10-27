# Momo Final: engineering acceptance status

**Do not publish an APK or ask the owner to install another iteration until
all release gates are met.** The owner has already installed V1–V10 and explicitly
requested one genuinely complete release.

## Evidence (from actual Watch 7 / W17 Pro)

| Feature | Evidence | Status |
|---|---|---|
| Connect without MActivePro | V7 direct GATT connected | VERIFIED on Pixel 7 |
| Read battery | V7 showed 75% | VERIFIED on Pixel 7 |
| Watch call speaker | Owner heard the test TTS **from the watch** | VERIFIED |
| Watch call microphone | Android AudioRecord reported Watch call at -12.5 dBFS | VERIFIED |
| Watch mic -> English STT | V9 code path exists, no physical transcript from owner yet | UNVERIFIED |
| English AI answers through watch | AI key and offline AI unverified in physical test | UNVERIFIED |
| Momo hotword with phone locked | Android SpeechRecognizer is session-based; no test | UNVERIFIED |
| Momo watch face uploaded to watch display | No validated Watch 7 firmware 4.21 protocol | BLOCKED |
| Live eye animation on watch | Requires compatible dial/animation firmware | BLOCKED |
| Phone completely powered off, watch runs model itself | No third-party app runtime demonstrated on W17 Pro | UNSUPPORTED / UNVERIFIED |

## Watch identifiers (do not confuse naming with chipset manufacturer)

- BLE device name: `Watch 7`
- Device Information 2A29 manufacturer string: `Watch 7` (not a chip vendor)
- Reported firmware: `4.21`
- Reported hardware: `1.0`
- GATT includes `a6ed0401-d344-460a-8075-b9e8ec90d71b` (a Goodix-style DFU
  service), `6e400004-b5a3-f393-e0a9-e50e24dcca9e` and
  `6e40000a-b5a3-f393-e0a9-e50e24dcca9e` custom services.

Those UUIDs **do not identify the watch-face transfer format**.

## Research results and non-matches

1. Microwear's MActivePro documentation describes picture, album and video
   watch faces across its device family. It does not give a Watch 7 packet
   format. Source: https://download.microwear.com/mactiveproen.html
2. 2022 MActivePro iOS version history mentions W17 model adaptation and watch
   dial push fixes, showing a vendor implementation existed for some W17.
   Source: https://apptopia.com/ios/app/1587451906/about
3. The independently reverse engineered boAt PRISM / KaHa protocol
   (https://github.com/udaysinh-git/boat-watch) sends dial commands via
   **6e400001 primary service, 6e400002 write and 6e400003 notify**. That
   watch also exposes a secondary **6e40000c** characteristic, but its
   project explicitly says this is NOT the dial channel. The actual Watch 7
   discovery report does **not** contain 6e400001/002/003.
   **Do not transmit that project's 02 8E/94/96 commands to Watch 7.**
4. Goodix-style DFU traffic may flash/update resources. Do not send unknown
   commands or files to the `a6ed0401` channel.
5. W17 Pro reseller specifications conflict between 320x390 and 380x380
   display sizes. Do not assume the correct pixel layout for firmware 4.21
   without watch-specific resolution evidence or a real face file.

## Consolidated software progress (integration/momo-final)

- UI from V10, direct Bluetooth and separate Watch call route from V7/V8/V9,
  key encryption and fast Gemini fallback and offline AI from V10.
- Task engine now avoids duplicate task identifiers on rapid voice commands.
- Robolectric tests cover task CRUD, persistence, ambiguous matches, no
  phantom reminders, speech hypothesis dry run and duplicate IDs.
- Android CI now runs tests before compiling any debug APK.
- Separate debug app ID `.dev`; the stable final release app ID will be
  `com.mojahid.watchcompanion`.
- Production APK signing is configured using private GitHub secrets, never
  the disposable runner debug certificate.
- A separate **manual only** production workflow requires explicit approval
  of physical watch-face/voice/AI acceptance; without valid private signing
  secrets it fails and emits no final APK.
- App text-to-speech length limit adjusted to avoid silently truncating short
  answers at 550 characters.

## Non-negotiable production blockers

1. Confirm exact firmware 4.21 dial writer protocol or manufacturer SDK,
   including screen dimensions, dial file format, optional authentication,
   MTU/chunking, ACK checks and fallback/recovery. A read-only GATT scan is
   not sufficient.
2. Prove a non-destructive upload of an original static Momo dial onto a
   compatible actual Watch 7. Keep unknown OTA commands blocked. An animated
   dial requires separate proof.
3. Verify English STT from the watch, Gemini/offline responses, and wake
   behavior on a real Pixel 7 with the phone locked and awake.
4. Run tests/build and screen-size UI inspections. Android CI compilation
   alone is not physical device acceptance.
5. Configure private production-signing secrets in the GitHub environment and
   preserve that key for every future APK update. Do not commit a signing key.

## User experience

No new APK links, no new per-version installer packages. No secrets sent in
chat. No third-party vendor app as a runtime dependency. Do not claim this is
phone-independent or that live face animation works before demonstrating it.

## Owner-authorized watch-side experiment mode (2026-10-27)

The owner explicitly permitted experiments that might disrupt/damage the **watch**
but prohibited changes to the **Pixel 7 phone**. This modifies the previous
blanket "no experimental vendor writes" preference only for a narrow,
explicit, on-demand watch identification probe. It does NOT waive the
requirement to verify and approve a final production release.

Implemented on `integration/momo-final` (not yet physically executed):

1. Existing Momo GATT connection, exact-match 4.21/1.0 fingerprint. No
   MActivePro needed. No added Android permissions, root, bootloader or OS
   setting changes.
2. 30-second passive experiment: five GATT READ operations
   (`6e400005`–`009`), eight temporary NOTIFY subscriptions
   (`005`–`009`, `00b`–`00d`) with standard CCCD 0x2902 writes.
   Bounded in-memory evidence, explicit copy only. Link is closed and
   automatically reconnected afterwards.
3. Optional explicit checkbox unlocks one speculative NJJ product-ID query
   (`BC 3C 01 01 00 00`) on **one** of `6e40000b` or `6e40000c`,
   one attempt per capture. Older Microwear/NJJ packet is **NOT validated**
   for this W17; no retransmission/OTA/dial file is attempted.
4. Every characteristic and endpoint is code-allowlisted; Goodix
   `a6ed0401` is never targeted by this experimental mode. GitHub CI
   guards the limited GATT write locations, and tests cover the fixed
   UUID/packet allowlist.

**Evidence still required:** actual captured bytes from this exact physical
watch, any responses to the identification query, a verified and reversible
photo-dial transfer, live animation support, background voice, end-to-end
speech/AI, stable signing and physical acceptance. This implementation does
**not** meet the final watch-face acceptance gate by itself.

No new owner-facing APK has been published and PR #12 remains unmerged.

## Physical BLE capture result received from owner (2026-10-27)

The owner installed the previous experimental Android build and performed
a **real W17 Pro firmware 4.21 / hardware 1.0 BLE capture**:

- Verified the exact 11-characteristic service fingerprint.
- Five vendor READ attempts; four succeeded. `6e400008` returned GATT
  status 1 on READ but subsequently emitted a NOTIFY.
- Eight NOTIFY subscriptions completed; six notifications received:
  one on `6e400006`, three on `6e400007`, one each on
  `6e400008` and `6e400009`.
- The READ payloads reveal candidate strings `H01P`, `4.21`,
  `2.0.4` (not fully decoded).
- Owner opted in to one six-byte NJJ-family ID query via
  `6e40000b`; Android GATT write callback succeeded, but **no
  subsequent watch notification** was recorded, and there is NO
  verified watch-level acknowledgement.
- **Nothing was uploaded to the watch display.** Watchface transport,
  expression animation, voice transport and end-to-end acceptance remain
  unverified. Phone OS, firmware and root status were not changed.

**Next branch work actually implemented:** `Watch7PacketAnalysis` decodes
the candidate ASCII fragments, accurately segregates pre-query from
post-query notifications, and correlates watch-face interaction events
with local marker timestamps. Events now include relative elapsed times
and up to 48 hex bytes, enough to preserve the previously truncated
40-byte characteristic. See `docs/W17_REAL_BLE_CAPTURE_2026-10-27.md`
for sanitized evidence (no raw device token/serial in the repository).

No additional test installs required merely to interpret the **existing**
capture. The production-signing, dial upload and final hardware
acceptance gates remain **open**. Do not release as "Momo Final".
