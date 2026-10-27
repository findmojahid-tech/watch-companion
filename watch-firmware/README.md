# Momo on the original W17 Pro display

This is **original portable rendering code**, not a flashed watch firmware.

## What is actually implemented

- `momo_face_renderer.c/.h` renders Momo's eyes, smile, blinking,
  listening, thinking, speaking, happy and sleeping expressions.
- Input resolution is a parameter: tested with 320x390 and 320x385.
- RGB565 pixels are drawn into caller-supplied **16x16 tiles** using 512
  bytes of display buffer. There is no full-screen framebuffer, malloc,
  Android, cloud connection or proprietary dependency.
- `tests/test_momo_face.c` checks the animation's eye pixels,
  blink transitions, tile bounds, guard words and alternate resolution.
- Build/test with: `gcc -std=c11 -Wall -Wextra -Werror -pedantic
  watch-firmware/momo_face_renderer.c watch-firmware/tests/test_momo_face.c
  -o /tmp/momo_face_test && /tmp/momo_face_test`.
- CI runs this test before Android build.

## W17 hardware evidence

Retail documentation for W17 Pro describes Goodix GR5515 at 64 MHz
with approximately **256 KB RAM**, and a 320x390 display. Some other
W17 Pro listings instead say 320x385, so the exact panel resolution of
this specific firmware 4.21 still needs physical verification.
Sources:
- https://uquid.com/shop/product/new-original-iwo-w-pro-smart-watch-inch-mm-series-bluetooth-call-ecg-ip-waterproof-wireless-charger-smartwatch-color-add-nylon-strap-size-original-w-pro-igvrTeVVWLaTV
- https://www.goodix.com/en/software_tool/gr551x_sdk

Official Goodix source:
https://github.com/goodix-ble/GR551x.SDK
includes a GUI library and LCD driver examples, but those examples are
NOT the board-specific firmware or pinout for this Watch 7.

## What is missing before this code runs on the real watch

1. **Original Watch 7 firmware source or a vendor integration SDK** to link
   the new renderer into the existing watch face runtime. Otherwise we
   need a hardware-specific replacement firmware and recovery strategy.
2. Exact LCD controller and SPI pin mapping, external flash partitions,
   filesystem/asset format, and bootloader signature requirements.
3. Confirm which flash partition stores W17's custom dial and
   which package format selects a custom dial. Generic Goodix resource DFU
   is a raw flash update protocol, **not** a watch-face file system.
4. A physical test with a reversible recovery path before any write.

The Goodix DFU protocol on the user's GATT profile
(`a6ed0401-d344-460a-8075-b9e8ec90d71b`) can send *resource*
data such as image/font files according to the manufacturer docs:
https://www.goodix.com/en/docview/GR551x%20DFU%20Application%20Note?objectId=62&objectType=document&version=171

**IMPORTANT:** Program Flash command `0x0024` directly modifies memory.
The generic example's `0x01040000` resource address is **not the verified
dial location for W17 firmware 4.21**. NEVER write a graphics file or use
the example start address without identifying the exact watch storage layout.
`GoodixDfuInspector.kt` parses captured packets offline and has no
Bluetooth write interface, deliberately.

## What "fully standalone" means

A 64 MHz microcontroller with ~256 KB RAM can drive a face animation and
a wake button or other tiny command parser. It cannot realistically run a
modern broad-knowledge LLM capable of answering arbitrary questions without
a more capable external computer/network. For this unchanged W17 Pro,
the existing Android Pixel 7 remains the AI engine, and the watch acts as
Bluetooth microphone, speaker and (once a safe dial integration exists) face.
Phone-independent AI would require a different hardware design or more
capable replacement internals.

No APK should be published as "complete" until the owner has verified an
actual watch-side face plus end-to-end speech on their physical watch.
