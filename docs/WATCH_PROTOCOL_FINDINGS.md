# Watch 7 / Microwear W17 Pro: BLE Protocol Notes

## Observed on an actual Watch 7 device
Service discovery succeeded, including:
- `0x1800` Generic Access
- `0x1801` Generic Attribute
- `0x180A` Device Information (manufacturer and firmware revisions may be readable)
- `0x180F` Battery
- `0x1812` HID
- `a6ed0401-d344-460a-8075-b9e8ec90d71b` with `a6ed0402`, `a6ed0403`, `a6ed0404`
- Two custom vendor services `6e400004-...` and `6e40000a-...`.

The `a6ed0401` group **matches Goodix's published OTA/DFU service and characteristics**. This is evidence of a Goodix-compatible firmware-upgrade channel, **not** evidence of watch-face programmability, and does not identify the whole watch chipset.

References:
- Goodix GR551x DFU App Note: https://www.goodix.com/en/docview/GR551x%20DFU%20Application%20Note?objectId=62&objectType=document&version=171
- Jieli Android Watch SDK: https://github.com/Jieli-Tech/Android-JL_Health
- Bluetooth GATT Assigned Services: https://www.bluetooth.com/specifications/assigned-numbers/

**The canonical Nordic UART service UUID is `6e400001-...`**. It was not discovered on this watch. The custom `6e400004` and `6e40000a` services should *not* be assumed to implement Nordic UART or Jieli RCSP.

## Installation / data preservation
The V4 diagnostics **debug APK** uses its own Android application ID suffix `.watchlab`
and the launcher label **Momo Watch Lab V4**. It installs **beside Momo V3**
rather than replacing its debug signing certificate or erasing existing saved tasks.
Keep the V3 app; use the separate Watch Lab app only for the protocol inspection.

## What V4 Watch Lab does
- Scan nearby BLE devices.
- Connect briefly and enumerate services, characteristics, and standard GATT property flags.
- Perform read-only reads of standardized Device Name, manufacturer, firmware/hardware/software revisions and battery when readable.
- Disconnect the temporary diagnostic session without changing a single watch setting.
- Copy the report for manual analysis.

## What it intentionally does NOT do
- Firmware OTA, DFU, or bootloader operations.
- Any writes to characteristics or descriptors, including proprietary endpoints.
- Auto-enabling notifications, authentication probing, or guessing write payloads.
- Uploading a new dial, live screen frame, or firmware. Such functionality requires a confirmed safe device protocol.

## How to provide the next report
In Android app **Watch → BLE Watch Lab → Scan BLE devices → Watch 7**.
Wait for the read-only audit to finish, then choose **Copy full BLE report**.
Share the lines showing manufacturer, firmware revision and custom characteristic property flags. Avoid including any personal keys or identifiers.

## Face workaround
The app can **Save Momo face image** as a PNG in `Pictures/Momo`.
If the original watch companion app offers *custom photo watchface*, import that PNG there.
This does not make it animated or turn the watch into a standalone programmable app.

## Watch call audio versus BLE inspection
Bluetooth HFP communication audio and GATT are separate profiles. Closing a GATT
inspection session is expected, and does not prove voice audio disconnected.

## Cross-device protocol comparison (2026-10-27; not validated on W17 Pro)

Additional reverse-engineering projects establish that custom BLE watch-face uploading
is technically possible on **other** watches, but their commands must not be
replayed against this specific firmware:

- CMF Watch Pro 2 protocol research documents a working photo-dial transfer
  using 8052 / 9063 / 9064 / 9065 and LZ4 RGB565. It was validated on
  **CMF Watch Pro 2 firmware 1.0.0.73**, not W17 Pro firmware 4.21:
  https://github.com/joshuapassos/CMF-Watch-Pro-2-BLE-Protocol
- The Chinese Smartwatch Home Assistant project documents a different
  feature-flag / command protocol; its watch-face-push flags are **not**
  evidence that Watch 7 implements those commands:
  https://github.com/victorbillyph/chinese-smartwatch-homeassistant
- Fogg's Jieli / HK89 watch dial sender is also a separate platform:
  https://github.com/MiguelDLM/Fogg

**Decision:** no protocol transplant or trial writes. To identify a compatible
dial transport, obtain a verified W17 Pro 4.21 SDK or an authorized dial-upload
trace from the same device/firmware, with service/characteristic mapping,
encoding, acknowledgment behavior, display dimensions, rollback instructions,
and proof that the transfer does not enter Goodix DFU. Until then, PNG export
is an asset only, not a watch-screen feature.

## W17 Pro model-specific recovery evidence (2026-10-27)

A repair article specifically names a **Smochm W17 Pro** and reports Goodix
GRToolbox/DFU recovery following a failed MActivePro firmware update:
https://www.shiningintl.com/supports/technical-support/smart-watch/how-to-fix-the-smochm-w17pro-smartwatch-blocked-after-updaing-firmware/

This is **stronger model-family evidence** for the observed Goodix-style OTA
service than unrelated watch protocol repositories, but does **not** establish
that the owner's Microwear W17 Pro firmware 4.21 has identical board/firmware
or the ability to write an ordinary photo dial through OTA. The article
illustrates that an incorrect update can leave the watch with a black screen.
Do not execute its bootloader/DFU commands, install its utility, or flash its
firmware on the owner's watch.

Historical MActivePro release notes explicitly mention a W17 adaptation fix
and dial push fixes (different dates/versions), making **vendor dial-transfer
implementation or authorized trace** the highest-value next evidence:
https://apptopia.com/ios/app/1587451906/about

**Result:** Watch-face upload remains blocked; no user-visible test APK,
BLE writes, or firmware changes are justified by these findings.

## Exact verified GATT properties: owner's Watch 7 firmware 4.21 / hardware 1.0

The owner copied this **read-only** report from the physical Pixel 7 and W17 Pro.
It excludes the BLE MAC address, media, personal data and any write payload.
This is a device fingerprint, **not** a decoded command protocol.

| Service | Characteristic suffix | Observed GATT property | Safe interpretation |
|---|---|---|---|
| `a6ed0401-d344-460a-8075-b9e8ec90d71b` | `a6ed0402` | NOTIFY | Goodix-style DFU response candidate; not a dial API |
| `a6ed0401-…` | `a6ed0403`, `a6ed0404` | WRITE_NO_RESPONSE | Flash/DFU family; **never write without a validated recovery process** |
| `6e400004-b5a3-f393-e0a9-e50e24dcca9e` | `6e400005`–`6e400009` | READ, NOTIFY | Five read-capable unknown vendor characteristics; content not yet decoded |
| `6e40000a-b5a3-f393-e0a9-e50e24dcca9e` | `6e40000b`, `6e40000c` | WRITE, WRITE_NO_RESPONSE, NOTIFY | Two **unverified** bidirectional vendor endpoints; purpose, framing and authorization unknown |
| `6e40000a-…` | `6e40000d` | NOTIFY | Notification-only unknown vendor endpoint; not a proven transfer ACK |

**Crucial:** `6e40000a` is a *service*, not itself an observed
`WRITE` characteristic. Its `0b`/`0c` children permit writes, but
neither a write property nor a notification property proves watch-face
transfer. The `6e400004` service's `005`–`009` characteristics are
READ/NOTIFY, **not** upload targets. The `a6ed0401` family is associated
with Goodix firmware updates and must not be used to trial dial files.

The real 4.21 topology is now an exact-match, fail-closed test fixture in
`Watch7GattProfile.kt` and `Watch7GattProfileTest.kt`. The app displays
a clear compatibility verdict based on live discovery; it still has **no**
watch-face transmitter. Matching the signature is not evidence that a dial
upload will work.

### What would unblock original static face transfer

Obtain at least one of: a manufacturer-approved **W17 Pro hardware 1.0,
firmware 4.21** photo-dial SDK, or a captured and device-matched authorized
dial upload trace. Then verify the characteristic, dial resource encoding,
screen dimensions, start/data/end framing, ACK and retransmission behavior,
image limits, recovery path and reversibility **without writing anything
to the Goodix DFU channel**. A separate, credible firmware capability check
is needed for animated eyes; a still-image dial never proves animation.

Microwear advertises optional SDK integration for eligible OEM projects:
https://www.microwear.info/oem-services
Do not assume that advertised SDK coverage includes this older W17 Pro
revision. An unrelated MActivePro/NJJ watch report uses FF02/FF03 under
`000001ff`, which **does not match these actual Watch 7 services**:
https://github.com/pedrohsipriano/hub-saude

### Important Goodix DFU mismatch (confirmed against Goodix GR551x DFU Note Rev 1.8)

The actual W17 Pro 4.21 properties differ from the published reference:

| Characteristic | Goodix GR551x Rev 1.8 | This physical Watch 7 |
|---|---|---|
| `a6ed0402` | Write Without Response (OTA RX) | **NOTIFY** |
| `a6ed0403` | Notify/Indicate (OTA TX) | **WRITE_NO_RESPONSE** |
| `a6ed0404` | Write Without Response/Indicate (OTA Control) | **WRITE_NO_RESPONSE** |

Official spec: https://www.goodix.com/en/docview/GR551x%20DFU%20Application%20Note?objectId=62&objectType=document&version=171 (section 4.2.1, Table 4-1).

So even though the **service UUID matches**, its actual properties do **not**
match the specific GR551x reference arrangement. The characteristic IDs
alone do not authorize using a generic Goodix DFU tool or packets; the
firmware may use another revision/variant of the OTA profile. Treat these
as **OTA-associated unknown writers**, not confirmed dial commands. Keep
all writes disabled.

## Opt-in passive vendor channel capture (experimental branch)

`integration/momo-final` now contains a *user-initiated 30-second protocol
capture* built into Momo's existing BLE session, with a **firmware 4.21 /
hardware 1.0 exact profile check**. This is source implementation, not a
claim that it has been exercised on the owner's physical watch.

- READ from the five `6e400005` through `6e400009` characteristics on
  service `6e400004`. Reads may return opaque binary information.
- Subscribe to NOTIFY only on those five plus the three `6e40000b`,
  `6e40000c`, `6e40000d` characteristics on service `6e40000a`.
  **These subscriptions require a standard Bluetooth CCCD 0x2902 write**
  with `01 00`, which changes temporary notification configuration on
  the watch for that connection. No arbitrary payloads are sent.
- After at most 30 seconds, close the temporary GATT connection to remove
  subscriptions, then request Momo's ordinary direct BLE reconnect. Capture
  can be stopped immediately. Timeouts, failed reads and missing CCCDs are
  recorded without attempting unknown command bytes.
- Record no more than 48 lines; display up to 36 bytes per payload. Captures
  are held in app memory only and can be copied **at the owner's request**.
  Bluetooth messages can contain health/personal data: review before sharing.
- OTA service `a6ed0401` is excluded from every read/notify and write
  operation. No bootloader unlocks, Android root, new permissions, phone
  settings or phone firmware changes.
- With explicit **watch-risk opt-in**, one additional experimental action may
  send the fixed six-byte `BC 3C 01 01 00 00` *product ID request* to
  **one** of the actual vendor characteristics `6e40000b` OR `6e40000c`
  (service `6e40000a`). It uses a GATT write-with-response, max one
  attempt per 30-second session, never retries automatically and captures the
  GATT result/any subsequent notification. The command is from a DIFFERENT
  Microwear/NJJ watch protocol using `FF02` and has **NOT** been validated
  on W17 Pro. It could be ignored, disconnect or damage the watch; the owner
  explicitly accepts watch-side experimentation. It cannot flash the Android
  phone and does not send a watchface. Source:
  https://github.com/pedrohsipriano/hub-saude#protocolo-log-hci-de-16102026-lido-com-toolsbtsnoopmjs

This observer may reveal actual vendor characteristic values and
notifications (for example, when changing a dial directly on the watch).
**It does not identify how to upload a face, and silence does not prove
that the dial protocol is absent.** To send a face, a separately verified
device-specific transfer scheme is still necessary.

Previously documented read-only Watch Lab and V10 APK behavior remain
unchanged; the new capture controls exist in branch source only until an
approved consolidated release. The Android CI static safety check allows **exactly two API-version
overloads** for the opt-in one-shot vendor identification query and two
API-version overloads for standard 0x2902 CCCD subscriptions, all in
`DirectWatchClient` and constrained by runtime allowlists. It blocks any
new characteristic or descriptor writer outside that class.
