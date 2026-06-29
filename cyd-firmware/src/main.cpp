#include <Arduino.h>
#include <TFT_eSPI.h>
#include <SPI.h>
#include <XPT2046_Touchscreen.h>
#include <Preferences.h>

// ── Touch SPI pins (VSPI, custom CYD pins) ──────────────
#define TOUCH_CS   33
#define TOUCH_IRQ  36
#define TOUCH_CLK  25
#define TOUCH_MOSI 32
#define TOUCH_MISO 39
#define TFT_BL_PIN 21

// ── Screen geometry (landscape 320×240) ──────────────
#define SCR_W 320
#define SCR_H 240
#define TAB_H 30
#define KBD_Y 110
#define KEY_W 32
#define KEY_H 32

// ── Palette (set at runtime in setupPalette) ─────────
uint16_t COL_BG, COL_CARD, COL_CARD2, COL_ACCENT, COL_ACCENT2,
         COL_GOOD, COL_BAD, COL_TEXT, COL_MUTE, COL_KEY, COL_KEYSP,
         COL_FOOD, COL_SNAKE, COL_SNAKEHD, COL_DARK;

void setupPalette() {
    COL_BG      = tft.color565(13, 18, 38);
    COL_CARD    = tft.color565(28, 36, 64);
    COL_CARD2   = tft.color565(40, 50, 84);
    COL_ACCENT  = tft.color565(34, 211, 238);
    COL_ACCENT2 = tft.color565(129, 140, 248);
    COL_GOOD    = tft.color565(46, 204, 113);
    COL_BAD     = tft.color565(231, 76, 76);
    COL_TEXT    = tft.color565(236, 240, 247);
    COL_MUTE    = tft.color565(140, 152, 180);
    COL_KEY     = tft.color565(45, 54, 86);
    COL_KEYSP   = tft.color565(60, 72, 112);
    COL_FOOD    = tft.color565(255, 120, 90);
    COL_SNAKE   = tft.color565(34, 197, 120);
    COL_SNAKEHD = tft.color565(120, 255, 170);
    COL_DARK    = tft.color565(8, 11, 24);
}

// NOTE: setupPalette uses tft, so declare tft first (below) – we
// forward-declare by moving the object definition above this call.
