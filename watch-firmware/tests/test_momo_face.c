#include "../momo_face_renderer.h"
#include <assert.h>
#include <stdio.h>

static uint16_t sample(uint16_t x, uint16_t y, momo_expression_t e, uint32_t tick) {
    uint16_t value = 0;
    assert(momo_render_tile(&value, 1, x, y, 1, 1, 320, 390, e, tick));
    return value;
}

int main(void) {
    uint16_t tile[16 * 16 + 2];
    for (int i = 0; i < 16 * 16 + 2; ++i) tile[i] = 0xDEAD;
    assert(momo_render_tile(tile, 16, 64, 148, 16, 16, 320, 390, MOMO_IDLE, 0));
    assert(tile[0] == 0x18A4u);
    assert(tile[16 * 16] == 0xDEAD && tile[16 * 16 + 1] == 0xDEAD);
    assert(sample(103, 175, MOMO_IDLE, 0) == 0x86DFu);
    assert(sample(103, 175, MOMO_IDLE, 26) == 0x18A4u);
    assert(sample(103, 175, MOMO_LISTENING, 0) == 0x86DFu);
    assert(sample(0, 0, MOMO_IDLE, 0) == 0x0842u);
    assert(sample(160, 245, MOMO_SPEAKING, 2) != 0x0842u);
    assert(!momo_render_tile(NULL, 1, 0, 0, 1, 1, 320, 390, MOMO_IDLE, 0));
    assert(!momo_render_tile(tile, 16, 319, 389, 16, 16, 320, 390, MOMO_IDLE, 0));
    assert(!momo_render_tile(tile, 16, 0, 0, 16, 16, 320, 390, (momo_expression_t)99, 0));
    assert(momo_render_tile(tile, 16, 0, 0, 16, 16, 320, 385, MOMO_IDLE, 0));
    printf("Momo RGB565 16x16-tile renderer tests passed\n");
    return 0;
}
