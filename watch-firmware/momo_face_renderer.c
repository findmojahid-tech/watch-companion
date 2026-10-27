#include "momo_face_renderer.h"

#define BG_COLOR       0x0842u
#define HEAD_COLOR     0x18A4u
#define EYE_COLOR      0x86DFu
#define EYE_DIM_COLOR  0x4D79u
#define MOUTH_COLOR    0x65DAu

static int iabs(int v) { return v < 0 ? -v : v; }

static bool round_rect(int x, int y, int left, int top,
                       int right, int bottom, int radius)
{
    if (x < left || x >= right || y < top || y >= bottom) return false;
    if (radius <= 0) return true;
    int cx = x < left + radius ? left + radius :
             x >= right - radius ? right - radius - 1 : x;
    int cy = y < top + radius ? top + radius :
             y >= bottom - radius ? bottom - radius - 1 : y;
    int dx = x - cx;
    int dy = y - cy;
    return dx * dx + dy * dy <= radius * radius;
}

static uint16_t color_at(int x, int y, momo_expression_t expression, uint32_t tick)
{
    if (!round_rect(x, y, 17, 30, 303, 350, 58)) return BG_COLOR;

    uint16_t color = HEAD_COLOR;
    int eye_top = 152;
    int eye_bottom = 205;
    int eye_shift = 0;
    if (expression == MOMO_THINKING) eye_shift = 8;
    if (expression == MOMO_LISTENING) { eye_top = 145; eye_bottom = 211; }
    if (expression == MOMO_SLEEPING) { eye_top = 177; eye_bottom = 185; }
    if (expression == MOMO_HAPPY) { eye_top = 159; eye_bottom = 193; }
    if (expression == MOMO_IDLE && tick % 28u >= 25u) {
        eye_top = 177;
        eye_bottom = tick % 28u == 26u ? 183 : 189;
    }
    if (expression == MOMO_SPEAKING && tick % 6u < 3u) {
        eye_top = 158;
        eye_bottom = 202;
    }

    const int radius = (eye_bottom - eye_top) / 2;
    const bool left_eye = round_rect(x, y, 66 + eye_shift, eye_top,
                                     139 + eye_shift, eye_bottom, radius);
    const bool right_eye = round_rect(x, y, 181 + eye_shift, eye_top,
                                      254 + eye_shift, eye_bottom, radius);
    if (left_eye || right_eye) {
        return expression == MOMO_SLEEPING ? EYE_DIM_COLOR : EYE_COLOR;
    }

    /* Simple two-pixel smile curve. Integer arithmetic only. */
    const int from_center = iabs(x - 160);
    if (from_center <= 20 && expression != MOMO_SLEEPING) {
        const int smile_y = 248 - (from_center * from_center) / 23;
        const int thickness = expression == MOMO_SPEAKING ? 5 : 2;
        if (iabs(y - smile_y) <= thickness) color = MOUTH_COLOR;
    }
    return color;
}

bool momo_render_tile(uint16_t *pixels, uint16_t stride,
                      uint16_t tile_x, uint16_t tile_y,
                      uint16_t tile_width, uint16_t tile_height,
                      uint16_t display_width, uint16_t display_height,
                      momo_expression_t expression, uint32_t tick)
{
    if (!pixels || !stride || !tile_width || !tile_height ||
        !display_width || !display_height || stride < tile_width ||
        tile_x >= display_width || tile_y >= display_height ||
        (uint32_t)tile_x + tile_width > display_width ||
        (uint32_t)tile_y + tile_height > display_height ||
        expression < MOMO_IDLE || expression > MOMO_SLEEPING) {
        return false;
    }
    for (uint16_t row = 0; row < tile_height; ++row) {
        for (uint16_t col = 0; col < tile_width; ++col) {
            const int x = ((uint32_t)(tile_x + col) * 320u) / display_width;
            const int y = ((uint32_t)(tile_y + row) * 390u) / display_height;
            pixels[(uint32_t)row * stride + col] = color_at(x, y, expression, tick);
        }
    }
    return true;
}
