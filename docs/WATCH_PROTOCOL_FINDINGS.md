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
