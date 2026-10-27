#ifndef MOMO_FACE_RENDERER_H
#define MOMO_FACE_RENDERER_H

#include <stdint.h>
#include <stdbool.h>

/* Portable RGB565 face renderer for memory-constrained embedded displays.
 * Writes only into the caller's tile buffer. No BLE, flash, OTA, malloc,
 * hardware register or firmware bootloader access is implemented here.
 */
typedef enum {
    MOMO_IDLE = 0,
    MOMO_LISTENING = 1,
    MOMO_THINKING = 2,
    MOMO_SPEAKING = 3,
    MOMO_HAPPY = 4,
    MOMO_SLEEPING = 5
} momo_expression_t;

/* width/height are the actual display dimensions, not assumed to be 320x390.
 * Tile rows have stride pixels. All coordinates are display-space.
 * Tick is an arbitrary monotonically increasing animation frame count.
 * Returns false for an invalid buffer/rectangle without writing anything.
 */
bool momo_render_tile(uint16_t *pixels, uint16_t stride,
                      uint16_t tile_x, uint16_t tile_y,
                      uint16_t tile_width, uint16_t tile_height,
                      uint16_t display_width, uint16_t display_height,
                      momo_expression_t expression, uint32_t tick);

#endif
