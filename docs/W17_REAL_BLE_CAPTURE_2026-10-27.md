# Actual Watch 7 / W17 Pro BLE experiment: 27 October 2026

Evidence supplied by the owner from an **actual Pixel 7 + Watch 7**,
firmware `4.21`, hardware `1.0`, using Momo's 30-second BLE capture.
This file avoids saving the watch MAC, user credentials, private/random
notification bytes or unverified interpretations as confirmed protocol.

## Observed outcomes

- GATT services and all 11 characteristics matched the pinned 4.21/1.0
  signature from the earlier independent read-only report.
- `READ 6e400005` succeeded (3 bytes), undecoded `02 00 00`.
- `READ 6e400006` succeeded (13 bytes) and contains the ASCII substring
  `2.0.4`. The meaning (watch app / radio / other version) is unverified.
- `READ 6e400007` succeeded (40 bytes), with ASCII substrings
  `H01P`, `4.21`, `2.0.4`. `H01P` is a **device-code candidate**, not
  a proven chipset/bootloader ID. The exact 40-byte value was truncated
  to a 36-byte preview in the original capture; the newer logger uses
  a 48-byte preview, sufficient for this value.
- `READ 6e400008` returned Android GATT status **1**, but a notification
  from the same characteristic arrived later. A failed read does not mean
  it lacks notification data.
- `READ 6e400009` succeeded (19 bytes), still undecoded.
- Eight client configuration descriptors (CCCD) were subscribed, including
  `6e40000b` through `6e40000d`; subscription does not identify a
  dial-transfer characteristic.
- Exactly **6 watch notifications** were captured **before** the ID query:
  `6e400006` (1), `6e400007` (3), `6e400008` (1),
  `6e400009` (1). No notifications arrived from `0b`/`0c`/`0d`
  in the original report.
- The owner opted in to the fixed NJJ-family product-ID query
  `BC 3C 01 01 00 00` over `6e40000b`. Android reported
  **GATT write accepted**. **NO watch-originated notification was
  recorded after the query**. This is NOT evidence that the watch
  recognizes the NJJ protocol and NOT an application-level ACK.
- The `6e400007` 12-byte notifications contain 32-bit big-endian values
  that resemble timestamps for **1 January 2022**. Their *meaning is
  unverified*: they may be historical data, counters, or opaque fields.
  Never use them to infer the current watch clock or payload format.

## Result and implementation after this evidence

The physical read/notify BLE flow is confirmed to function. The
proprietary dial transfer, display composition, audio protocol, and
whether animated faces are supported remain **unknown**. No watch-face
file was uploaded; no visible watch-screen transformation occurred.

New source files under `integration/momo-final`:

- `Watch7PacketAnalysis.kt`: conservative extraction of ASCII version
  candidates and query/notification ordering; never labels a GATT write
  success as a watch-level command response.
- `Watch7PacketAnalysisTest.kt`: literal real-world 006/007 read
  fixtures and six-before-zero-after notification sequencing tests.
- `DirectWatchClient.kt` and `MomoHome.kt`: time-offset trace events,
  full 40-byte value previews, automatic candidate labeling and
  query-response conclusion in the copyable report. Repeat-query button
  disabled once a command is attempted per capture.

**Unblocker remains a verified firmware-4.21 compatible photo dial
transfer protocol, including image format and ACK framing.**
Until that is observed, random resource/OTA uploads are not a credible
way to complete the physical Momo watch face. No phone OS/firmware
changes, root, or additional Android permissions are required by
this investigation.

**Privacy:** A raw `6e400008` notification includes an unusual
alphanumeric sequence. It may identify the device or represent a
secret; it is deliberately **redacted from this repository**.
Never commit raw device tokens, pairing secrets, or arbitrary user
notification payloads.
