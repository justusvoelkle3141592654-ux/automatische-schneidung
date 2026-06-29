#include <Arduino.h>
#include <TFT_eSPI.h>
#include <SPI.h>
#include <XPT2046_Touchscreen.h>
#include <WiFi.h>
#include <WiFiManager.h>
#include <HTTPClient.h>
#include <ArduinoJson.h>
#include <Preferences.h>
#include <time.h>

// ── Touch SPI pins (VSPI with custom pins) ─────────────────
#define TOUCH_CS   33
#define TOUCH_IRQ  36
#define TOUCH_CLK  25
#define TOUCH_MOSI 32
#define TOUCH_MISO 39
#define TFT_BL_PIN 21

// ── Screen layout (landscape 320×240) ─────────────────────
#define SCR_W  320
#define SCR_H  240
#define TAB_H   30
#define TAB_W  160
#define KBD_Y  110
#define KEY_W   32
#define KEY_H   32

// ── Static colors (RGB565) ─────────────────────────────────
#define C_BG      0x0841u
#define C_TAB     0x2945u
#define C_TAB_ACT 0x0488u
#define C_KEY     0x3186u
#define C_KEY_SP  0x528Au
#define C_ACCENT  0x07FFu
#define C_LGREY   0xC618u
#define C_DGREY   0x7BEFu
#define C_CARD    0x10A2u
#define C_RED     0xC9A7u

// ── Weather icon categories ────────────────────────────────
enum WxIcon { ICON_SUN, ICON_PARTLY, ICON_CLOUD, ICON_RAIN, ICON_SNOW, ICON_STORM, ICON_FOG };

// ── Hardware ───────────────────────────────────────────────
TFT_eSPI tft;
SPIClass touchSPI(VSPI);
XPT2046_Touchscreen touch(TOUCH_CS, TOUCH_IRQ);
Preferences prefs;

// ── App state ──────────────────────────────────────────────
enum Screen { SCR_WEATHER, SCR_NOTES_LIST, SCR_NOTES_EDIT };
Screen currentScreen = SCR_WEATHER;

String city = "Berlin";

// Weather: index 0 = today (current), 1 = tomorrow, 2 = day after
struct DayWx {
    String  tempBig;   // current temp (day 0) or max (days 1-2)
    String  tMax, tMin;
    String  desc;
    int     code;
    String  humid;
};
DayWx   wx[3];
int     selDay   = 0;
bool    wxLoaded = false;
unsigned long wxFetchedAt = 0;
unsigned long lastTouchMs = 0;

// Notes
#define MAX_NOTES 30
String notes[MAX_NOTES];
int    noteCount   = 0;
int    editIndex   = -1;
int    listScroll  = 0;
#define LIST_TOP    62
#define LIST_ITEM_H 34
#define LIST_VIS    5

// ── Keyboard rows ──────────────────────────────────────────
const char* ROW0[10] = {"Q","W","E","R","T","Z","U","I","O","P"};
const char* ROW1[10] = {"A","S","D","F","G","H","J","K","L","<"};
const char* ROW2[10] = {"Y","X","C","V","B","N","M","ae","oe","ue"};

const char* WD_SHORT[7] = {"So","Mo","Di","Mi","Do","Fr","Sa"};

// ── Forward declarations ───────────────────────────────────
void drawTabBar(bool weatherActive);
void drawWeatherScreen();
void drawNotesList();
void drawNotesEdit();
void drawKeyboard();
void handleTouch(int x, int y);
void fetchWeather();
String translateDesc(const String& eng);
WxIcon iconForCode(int code);
void drawWeatherIcon(WxIcon ic, int cx, int cy, int s);

// ── Notes persistence ──────────────────────────────────────
void loadNotes() {
    noteCount = prefs.getInt("ncount", 0);
    if (noteCount > MAX_NOTES) noteCount = MAX_NOTES;
    for (int i = 0; i < noteCount; i++)
        notes[i] = prefs.getString(("n" + String(i)).c_str(), "");
}
void saveNote(int i)   { prefs.putString(("n" + String(i)).c_str(), notes[i]); }
void saveCount()       { prefs.putInt("ncount", noteCount); }

void addNote() {
    if (noteCount >= MAX_NOTES) return;
    notes[noteCount] = "";
    editIndex = noteCount;
    noteCount++;
    saveCount();
    saveNote(editIndex);
}
void deleteNote(int idx) {
    for (int i = idx; i < noteCount - 1; i++) {
        notes[i] = notes[i + 1];
        saveNote(i);
    }
    noteCount--;
    saveCount();
}

// ── Gradient helper ────────────────────────────────────────
void fillGradient(int y0, int y1, int r1, int g1, int b1, int r2, int g2, int b2) {
    int h = y1 - y0;
    for (int y = 0; y < h; y++) {
        float t = (float)y / (h - 1);
        int r = r1 + (int)((r2 - r1) * t);
        int g = g1 + (int)((g2 - g1) * t);
        int b = b1 + (int)((b2 - b1) * t);
        tft.drawFastHLine(0, y0 + y, SCR_W, tft.color565(r, g, b));
    }
}
void gradientForIcon(WxIcon ic) {
    switch (ic) {
        case ICON_SUN:    fillGradient(0, SCR_H, 18, 28, 64, 232, 150, 46);  break; // night-blue → warm orange
        case ICON_PARTLY: fillGradient(0, SCR_H, 22, 36, 76, 120, 150, 190); break;
        case ICON_CLOUD:  fillGradient(0, SCR_H, 28, 38, 54, 96, 108, 124);  break;
        case ICON_RAIN:   fillGradient(0, SCR_H, 14, 24, 46, 52, 78, 112);   break;
        case ICON_SNOW:   fillGradient(0, SCR_H, 40, 56, 82, 158, 174, 200); break;
        case ICON_STORM:  fillGradient(0, SCR_H, 26, 20, 48, 92, 70, 120);   break;
        case ICON_FOG:    fillGradient(0, SCR_H, 42, 48, 58, 118, 124, 134); break;
    }
}

// ── WiFiManager AP callback: show setup guide on display ───
void onAPStarted(WiFiManager*) {
    tft.fillScreen(C_BG);
    tft.setTextColor(C_ACCENT);
    tft.setTextSize(2);
    tft.drawString("WLAN Einrichten", 10, 15);
    tft.setTextColor(TFT_WHITE);
    tft.setTextSize(1);
    tft.drawString("1. Handy-WLAN verbinden mit:", 10, 55);
    tft.setTextColor(C_ACCENT);
    tft.drawString("   'CYD-Setup'", 10, 68);
    tft.setTextColor(TFT_WHITE);
    tft.drawString("2. Browser oeffnen:", 10, 86);
    tft.setTextColor(C_ACCENT);
    tft.drawString("   192.168.4.1", 10, 99);
    tft.setTextColor(TFT_WHITE);
    tft.drawString("3. WLAN-Daten + Stadt eingeben", 10, 117);
    tft.drawString("4. Auf 'Speichern' tippen", 10, 131);
    tft.setTextColor(C_DGREY);
    tft.drawString("Portal schliesst nach 3 Min.", 10, 158);
}

// ══════════════════════════════════════════════════════════
void setup() {
    Serial.begin(115200);

    pinMode(TFT_BL_PIN, OUTPUT);
    digitalWrite(TFT_BL_PIN, HIGH);

    tft.init();
    tft.setRotation(1);
    tft.fillScreen(C_BG);
    tft.setTextColor(C_ACCENT);
    tft.setTextSize(2);
    tft.drawString("CYD Wetter & Notizen", 5, 90);
    tft.setTextColor(TFT_WHITE);
    tft.setTextSize(1);
    tft.drawString("Verbinde mit WLAN...", 10, 116);

    touchSPI.begin(TOUCH_CLK, TOUCH_MISO, TOUCH_MOSI, TOUCH_CS);
    touch.begin(touchSPI);
    touch.setRotation(1);

    prefs.begin("cyd", false);
    city = prefs.getString("city", "Berlin");
    loadNotes();

    WiFiManager wm;
    wm.setConfigPortalTimeout(180);
    wm.setAPCallback(onAPStarted);
    WiFiManagerParameter cityParam("city", "Stadt fuer Wetter", city.c_str(), 40);
    wm.addParameter(&cityParam);
    wm.autoConnect("CYD-Setup");

    String nc = String(cityParam.getValue());
    if (nc.length() > 0) {
        city = nc;
        prefs.putString("city", city);
    }

    // German time (CET/CEST with DST rules) via NTP
    configTzTime("CET-1CEST,M3.5.0,M10.5.0/3", "pool.ntp.org", "time.nist.gov");

    fetchWeather();

    drawWeatherScreen();
}

// ══════════════════════════════════════════════════════════
void loop() {
    if (WiFi.status() == WL_CONNECTED &&
        millis() - wxFetchedAt > 600000UL) {
        fetchWeather();
        if (currentScreen == SCR_WEATHER) drawWeatherScreen();
    }

    // Refresh clock once a minute on the weather screen
    static unsigned long lastClock = 0;
    if (currentScreen == SCR_WEATHER && millis() - lastClock > 30000UL) {
        lastClock = millis();
        drawWeatherScreen();
    }

    if (touch.tirqTouched() && touch.touched()) {
        TS_Point p = touch.getPoint();
        int sx = map(p.x, 200, 3700, 0, SCR_W);
        int sy = map(p.y, 3800, 200, 0, SCR_H);
        sx = constrain(sx, 0, SCR_W - 1);
        sy = constrain(sy, 0, SCR_H - 1);
        if (millis() - lastTouchMs > 300UL) {
            lastTouchMs = millis();
            handleTouch(sx, sy);
        }
    }
    delay(20);
}

// ── Top tab bar (Wetter | Notizen) ─────────────────────────
void drawTabBar(bool weatherActive) {
    uint16_t lc = weatherActive ? (uint16_t)C_TAB_ACT : (uint16_t)C_TAB;
    uint16_t rc = weatherActive ? (uint16_t)C_TAB : (uint16_t)C_TAB_ACT;
    tft.fillRect(0,     0, TAB_W, TAB_H, lc);
    tft.fillRect(TAB_W, 0, TAB_W, TAB_H, rc);
    tft.setTextSize(2);
    tft.setTextColor(TFT_WHITE, lc);
    tft.drawString("Wetter",  22, 7);
    tft.setTextColor(TFT_WHITE, rc);
    tft.drawString("Notizen", TAB_W + 14, 7);
    tft.drawLine(0, TAB_H, SCR_W, TAB_H, C_ACCENT);
}

// ══ WEATHER ════════════════════════════════════════════════
void drawWeatherScreen() {
    WxIcon ic = wxLoaded ? iconForCode(wx[selDay].code) : ICON_CLOUD;
    gradientForIcon(ic);
    drawTabBar(true);

    // Day selector chips
    const char* labels[3];
    labels[0] = "Heute";
    labels[1] = "Morgen";
    static char d3[8];
    strcpy(d3, "Tag 3");
    time_t now = time(nullptr);
    if (now > 100000) {
        struct tm tmv;
        localtime_r(&now, &tmv);
        time_t t2 = now + 2 * 86400;
        struct tm tm2; localtime_r(&t2, &tm2);
        strcpy(d3, WD_SHORT[tm2.tm_wday]);
    }
    labels[2] = d3;

    for (int i = 0; i < 3; i++) {
        int cx = 4 + i * 106, cw = 102, cy = 34, ch = 22;
        uint16_t bg = (i == selDay) ? (uint16_t)C_ACCENT : (uint16_t)C_CARD;
        uint16_t fg = (i == selDay) ? (uint16_t)C_BG : (uint16_t)C_LGREY;
        tft.fillRoundRect(cx, cy, cw, ch, 4, bg);
        tft.setTextSize(2);
        tft.setTextColor(fg, bg);
        int tw = strlen(labels[i]) * 12;
        tft.drawString(labels[i], cx + (cw - tw) / 2, cy + 4);
    }

    if (!wxLoaded) {
        tft.setTextSize(2);
        tft.setTextColor(TFT_WHITE);
        tft.drawString(wx[0].desc.length() ? wx[0].desc : "Lade Wetter...", 20, 120);
        return;
    }

    DayWx &d = wx[selDay];

    // Big icon on the left
    drawWeatherIcon(ic, 78, 130, 34);

    // Temperature (right)
    tft.setTextColor(TFT_WHITE);
    tft.setTextSize(6);
    String bigT = d.tempBig;
    tft.drawString(bigT, 158, 70);
    int tw = bigT.length() * 36;
    tft.fillCircle(158 + tw + 8, 76, 4, TFT_WHITE);
    tft.fillCircle(158 + tw + 8, 76, 1, TFT_BLACK);
    tft.setTextSize(3);
    tft.drawString("C", 158 + tw + 18, 76);

    // City + description
    tft.setTextSize(2);
    tft.setTextColor(C_ACCENT);
    tft.drawString(city, 158, 122);
    tft.setTextColor(C_LGREY);
    tft.drawString(translateDesc(d.desc), 158, 146);

    // Max / Min
    tft.setTextSize(2);
    tft.setTextColor(TFT_WHITE);
    tft.drawString("max " + d.tMax + "  min " + d.tMin, 158, 172);

    if (selDay == 0) {
        tft.setTextSize(1);
        tft.setTextColor(C_LGREY);
        tft.drawString("Luftfeuchte " + d.humid + " %", 158, 196);
    }

    // Bottom bar: weekday, date, clock
    if (now > 100000) {
        struct tm tmv; localtime_r(&now, &tmv);
        char buf[40];
        snprintf(buf, sizeof(buf), "%s  %02d.%02d.   %02d:%02d",
                 WD_SHORT[tmv.tm_wday], tmv.tm_mday, tmv.tm_mon + 1,
                 tmv.tm_hour, tmv.tm_min);
        tft.setTextSize(2);
        tft.setTextColor(TFT_WHITE);
        tft.drawString(buf, 10, 216);
    } else {
        tft.setTextSize(1);
        tft.setTextColor(C_DGREY);
        tft.drawString("Tippen aktualisiert das Wetter", 10, 224);
    }
}

// ── Weather icon → category ────────────────────────────────
WxIcon iconForCode(int c) {
    if (c == 113) return ICON_SUN;
    if (c == 116) return ICON_PARTLY;
    if (c == 119 || c == 122) return ICON_CLOUD;
    if (c == 143 || c == 248 || c == 260) return ICON_FOG;
    if (c == 200 || c == 386 || c == 389 || c == 392 || c == 395) return ICON_STORM;
    if (c == 179 || c == 182 || c == 185 || c == 227 || c == 230 ||
        c == 317 || c == 320 || c == 323 || c == 326 || c == 329 ||
        c == 332 || c == 335 || c == 338 || c == 350 || c == 362 ||
        c == 365 || c == 368 || c == 371 || c == 374 || c == 377) return ICON_SNOW;
    return ICON_RAIN; // all remaining drizzle/rain codes
}

// ── Draw a weather icon centred at (cx,cy), size s ─────────
void drawWeatherIcon(WxIcon ic, int cx, int cy, int s) {
    uint16_t sun   = tft.color565(255, 205, 60);
    uint16_t cloud = tft.color565(225, 230, 238);
    uint16_t cdark = tft.color565(150, 160, 175);
    uint16_t rain  = tft.color565(90, 170, 255);
    uint16_t bolt  = tft.color565(255, 225, 70);

    auto drawSun = [&](int x, int y, int r) {
        for (int a = 0; a < 360; a += 45) {
            float rad = a * 3.14159 / 180.0;
            int x1 = x + cos(rad) * (r + 4), y1 = y + sin(rad) * (r + 4);
            int x2 = x + cos(rad) * (r + 12), y2 = y + sin(rad) * (r + 12);
            tft.drawLine(x1, y1, x2, y2, sun);
            tft.drawLine(x1 + 1, y1, x2 + 1, y2, sun);
        }
        tft.fillCircle(x, y, r, sun);
    };
    auto drawCloud = [&](int x, int y, int sc, uint16_t col) {
        tft.fillCircle(x - sc, y, sc * 0.75, col);
        tft.fillCircle(x + sc, y, sc * 0.8, col);
        tft.fillCircle(x, y - sc * 0.6, sc, col);
        tft.fillRoundRect(x - sc - (int)(sc * 0.75), y - 2, sc * 3, sc, 5, col);
    };

    switch (ic) {
        case ICON_SUN:
            drawSun(cx, cy, s);
            break;
        case ICON_PARTLY:
            drawSun(cx - s / 2, cy - s / 2, s * 0.6);
            drawCloud(cx + 6, cy + 6, s * 0.7, cloud);
            break;
        case ICON_CLOUD:
            drawCloud(cx, cy, s * 0.8, cloud);
            break;
        case ICON_FOG:
            drawCloud(cx, cy - 6, s * 0.7, cdark);
            for (int i = 0; i < 3; i++)
                tft.fillRoundRect(cx - s, cy + s * 0.4 + i * 8, s * 2, 3, 1, cloud);
            break;
        case ICON_RAIN:
            drawCloud(cx, cy - 8, s * 0.8, cdark);
            for (int i = -1; i <= 1; i++)
                tft.fillRoundRect(cx + i * 16 - 1, cy + s * 0.6, 3, 14, 1, rain);
            break;
        case ICON_SNOW:
            drawCloud(cx, cy - 8, s * 0.8, cloud);
            for (int i = -1; i <= 1; i++)
                tft.fillCircle(cx + i * 16, cy + s * 0.7 + 6, 3, TFT_WHITE);
            break;
        case ICON_STORM:
            drawCloud(cx, cy - 8, s * 0.8, cdark);
            tft.fillTriangle(cx, cy + s * 0.4, cx - 8, cy + s * 0.9,
                             cx + 2, cy + s * 0.9, bolt);
            tft.fillTriangle(cx + 2, cy + s * 0.7, cx + 10, cy + s * 1.2,
                             cx, cy + s * 1.2, bolt);
            break;
    }
}

// ══ NOTES LIST ═════════════════════════════════════════════
String firstLine(const String& s) {
    int nl = s.indexOf('\n');
    String l = (nl >= 0) ? s.substring(0, nl) : s;
    if (l.length() == 0) l = "(leere Notiz)";
    if (l.length() > 30) l = l.substring(0, 30) + "...";
    return l;
}

void drawNotesList() {
    tft.fillScreen(C_BG);
    drawTabBar(false);

    // "+ Neue Notiz" button
    tft.fillRoundRect(4, 34, SCR_W - 8, 22, 4, C_ACCENT);
    tft.setTextSize(2);
    tft.setTextColor(C_BG, C_ACCENT);
    tft.drawString("+ Neue Notiz", 100, 38);

    if (noteCount == 0) {
        tft.setTextSize(1);
        tft.setTextColor(C_DGREY);
        tft.drawString("Noch keine Notizen.", 16, 110);
        tft.drawString("Tippe oben auf '+ Neue Notiz'.", 16, 124);
        return;
    }

    // Clamp scroll
    int maxScroll = noteCount > LIST_VIS ? noteCount - LIST_VIS : 0;
    if (listScroll > maxScroll) listScroll = maxScroll;
    if (listScroll < 0) listScroll = 0;

    bool scrollable = noteCount > LIST_VIS;
    int itemW = scrollable ? SCR_W - 30 : SCR_W - 8;

    for (int v = 0; v < LIST_VIS; v++) {
        int idx = listScroll + v;
        if (idx >= noteCount) break;
        int y = LIST_TOP + v * LIST_ITEM_H;
        tft.fillRoundRect(4, y, itemW, LIST_ITEM_H - 4, 4, C_CARD);
        tft.setTextSize(2);
        tft.setTextColor(TFT_WHITE, C_CARD);
        tft.drawString(firstLine(notes[idx]), 12, y + 7);
    }

    // Scroll arrows
    if (scrollable) {
        int ax = SCR_W - 24;
        tft.fillTriangle(ax + 10, LIST_TOP + 4, ax + 2, LIST_TOP + 18,
                         ax + 18, LIST_TOP + 18, C_ACCENT);
        int by = LIST_TOP + LIST_VIS * LIST_ITEM_H - 24;
        tft.fillTriangle(ax + 10, by + 18, ax + 2, by + 4,
                         ax + 18, by + 4, C_ACCENT);
    }
}

// ══ NOTES EDIT ═════════════════════════════════════════════
void drawNotesEdit() {
    tft.fillRect(0, 0, SCR_W, KBD_Y, C_BG);

    // Top bar: Save (left) | Delete (right)
    tft.fillRect(0, 0, TAB_W, TAB_H, C_TAB_ACT);
    tft.fillRect(TAB_W, 0, TAB_W, TAB_H, C_RED);
    tft.setTextSize(2);
    tft.setTextColor(TFT_WHITE, C_TAB_ACT);
    tft.drawString("< Speichern", 14, 7);
    tft.setTextColor(TFT_WHITE, C_RED);
    tft.drawString("Loeschen", TAB_W + 24, 7);
    tft.drawLine(0, TAB_H, SCR_W, TAB_H, C_ACCENT);

    // Text area
    tft.drawRect(0, TAB_H, SCR_W - 1, KBD_Y - TAB_H, C_ACCENT);
    tft.setTextColor(TFT_WHITE, C_BG);
    tft.setTextSize(1);

    const int charPerLine = 52;
    const int lineH = 10;
    const int maxLines = (KBD_Y - TAB_H - 8) / lineH;
    const int textY = TAB_H + 4;

    String linesBuf[10];
    int lineCount = 0;
    String cur = "";
    String display = (editIndex >= 0 ? notes[editIndex] : "") + "_";
    for (int i = 0; i < (int)display.length() && lineCount < 9; i++) {
        char c = display[i];
        if (c == '\n') { linesBuf[lineCount++] = cur; cur = ""; }
        else if ((int)cur.length() >= charPerLine) { linesBuf[lineCount++] = cur; cur = String(c); }
        else cur += c;
    }
    linesBuf[lineCount++] = cur;
    int start = (lineCount > maxLines) ? lineCount - maxLines : 0;
    for (int i = start; i < lineCount; i++)
        tft.drawString(linesBuf[i], 4, textY + (i - start) * lineH);

    drawKeyboard();
}

void drawKeyboard() {
    for (int i = 0; i < 10; i++) {
        int kx = i * KEY_W, ky = KBD_Y;
        tft.fillRect(kx + 1, ky + 1, KEY_W - 2, KEY_H - 2, C_KEY);
        tft.setTextColor(TFT_WHITE, C_KEY);
        tft.setTextSize(1);
        tft.drawString(ROW0[i], kx + 11, ky + 12);
    }
    for (int i = 0; i < 10; i++) {
        int kx = i * KEY_W, ky = KBD_Y + KEY_H;
        uint16_t col = (i == 9) ? (uint16_t)C_KEY_SP : (uint16_t)C_KEY;
        tft.fillRect(kx + 1, ky + 1, KEY_W - 2, KEY_H - 2, col);
        tft.setTextColor(TFT_WHITE, col);
        tft.setTextSize(1);
        if (i == 9) tft.drawString("DEL", kx + 5, ky + 12);
        else        tft.drawString(ROW1[i], kx + 11, ky + 12);
    }
    for (int i = 0; i < 10; i++) {
        int kx = i * KEY_W, ky = KBD_Y + KEY_H * 2;
        uint16_t col = (i >= 7) ? (uint16_t)C_KEY_SP : (uint16_t)C_KEY;
        tft.fillRect(kx + 1, ky + 1, KEY_W - 2, KEY_H - 2, col);
        tft.setTextColor(TFT_WHITE, col);
        tft.setTextSize(1);
        int tx = (strlen(ROW2[i]) > 1) ? kx + 7 : kx + 11;
        tft.drawString(ROW2[i], tx, ky + 12);
    }
    int ky3 = KBD_Y + KEY_H * 3;
    tft.fillRect(1, ky3 + 1, KEY_W - 2, KEY_H - 2, C_KEY);
    tft.fillRect(KEY_W + 1, ky3 + 1, KEY_W - 2, KEY_H - 2, C_KEY);
    tft.fillRect(KEY_W * 2 + 1, ky3 + 1, 192 - 2, KEY_H - 2, C_KEY_SP);
    tft.fillRect(KEY_W * 2 + 193, ky3 + 1, 63, KEY_H - 2, C_ACCENT);
    tft.setTextColor(TFT_WHITE, C_KEY);
    tft.setTextSize(1);
    tft.drawString(".", 11, ky3 + 12);
    tft.drawString(",", KEY_W + 11, ky3 + 12);
    tft.drawString("LEER", KEY_W * 2 + 76, ky3 + 12);
    tft.setTextColor(C_BG, C_ACCENT);
    tft.drawString("NL", KEY_W * 2 + 213, ky3 + 12);
}

// ── Touch event router ─────────────────────────────────────
void handleTouch(int x, int y) {
    // ---- Weather screen ----
    if (currentScreen == SCR_WEATHER) {
        if (y < TAB_H) {
            if (x >= TAB_W) {                 // → Notizen
                currentScreen = SCR_NOTES_LIST;
                listScroll = 0;
                drawNotesList();
            }
            return;
        }
        if (y >= 34 && y < 56) {              // day chips
            int i = x / 106; if (i > 2) i = 2;
            if (i != selDay) { selDay = i; drawWeatherScreen(); }
            return;
        }
        if (WiFi.status() == WL_CONNECTED) {  // tap content = refresh
            fetchWeather();
            drawWeatherScreen();
        }
        return;
    }

    // ---- Notes list ----
    if (currentScreen == SCR_NOTES_LIST) {
        if (y < TAB_H) {
            if (x < TAB_W) {                  // → Wetter
                currentScreen = SCR_WEATHER;
                drawWeatherScreen();
            }
            return;
        }
        if (y >= 34 && y < 56) {              // + Neue Notiz
            addNote();
            currentScreen = SCR_NOTES_EDIT;
            drawNotesEdit();
            return;
        }
        bool scrollable = noteCount > LIST_VIS;
        if (scrollable && x >= SCR_W - 26) {  // scroll arrows
            int mid = LIST_TOP + (LIST_VIS * LIST_ITEM_H) / 2;
            if (y < mid) listScroll--; else listScroll++;
            drawNotesList();
            return;
        }
        if (y >= LIST_TOP) {                  // open a note
            int v = (y - LIST_TOP) / LIST_ITEM_H;
            int idx = listScroll + v;
            if (v < LIST_VIS && idx < noteCount) {
                editIndex = idx;
                currentScreen = SCR_NOTES_EDIT;
                drawNotesEdit();
            }
        }
        return;
    }

    // ---- Notes edit ----
    if (currentScreen == SCR_NOTES_EDIT) {
        if (y < TAB_H) {
            if (x < TAB_W) {                  // Save & back
                if (editIndex >= 0) saveNote(editIndex);
            } else {                          // Delete
                if (editIndex >= 0) deleteNote(editIndex);
            }
            editIndex = -1;
            currentScreen = SCR_NOTES_LIST;
            drawNotesList();
            return;
        }
        if (y < KBD_Y || editIndex < 0) return;

        int row = (y - KBD_Y) / KEY_H;
        int col = constrain(x / KEY_W, 0, 9);
        String ch = "";
        if (row == 0) {
            ch = String(ROW0[col]);
        } else if (row == 1) {
            if (col == 9) {
                if (notes[editIndex].length() > 0)
                    notes[editIndex].remove(notes[editIndex].length() - 1);
                saveNote(editIndex);
                drawNotesEdit();
                return;
            }
            ch = String(ROW1[col]);
        } else if (row == 2) {
            ch = String(ROW2[col]);
        } else if (row == 3) {
            if      (x < KEY_W)           ch = ".";
            else if (x < KEY_W * 2)       ch = ",";
            else if (x < KEY_W * 2 + 192) ch = " ";
            else                          ch = "\n";
        }
        if (ch.length() > 0) {
            notes[editIndex] += ch;
            saveNote(editIndex);
            drawNotesEdit();
        }
        return;
    }
}

// ── Weather fetcher (filtered JSON to save memory) ─────────
void fetchWeather() {
    if (WiFi.status() != WL_CONNECTED) {
        wx[0].desc = "Kein WLAN";
        return;
    }
    HTTPClient http;
    http.begin("http://wttr.in/" + city + "?format=j1");
    http.setTimeout(12000);
    int code = http.GET();
    if (code != HTTP_CODE_OK) {
        wx[0].desc = "HTTP " + String(code);
        http.end();
        return;
    }

    JsonDocument filter;
    JsonObject fc = filter["current_condition"].add<JsonObject>();
    fc["temp_C"] = true;
    fc["humidity"] = true;
    fc["weatherCode"] = true;
    fc["weatherDesc"][0]["value"] = true;
    JsonObject fw = filter["weather"].add<JsonObject>();
    fw["maxtempC"] = true;
    fw["mintempC"] = true;
    JsonObject fh = fw["hourly"].add<JsonObject>();
    fh["time"] = true;
    fh["weatherCode"] = true;
    fh["weatherDesc"][0]["value"] = true;

    JsonDocument doc;
    DeserializationError err = deserializeJson(doc, http.getStream(),
                                               DeserializationOption::Filter(filter));
    http.end();
    if (err) { wx[0].desc = "Parse-Fehler"; return; }

    JsonObject cur = doc["current_condition"][0];
    wx[0].tempBig = cur["temp_C"].as<String>();
    wx[0].humid   = cur["humidity"].as<String>();
    wx[0].code    = cur["weatherCode"].as<int>();
    wx[0].desc    = cur["weatherDesc"][0]["value"].as<String>();

    JsonArray days = doc["weather"];
    for (int i = 0; i < 3 && i < (int)days.size(); i++) {
        JsonObject day = days[i];
        wx[i].tMax = day["maxtempC"].as<String>();
        wx[i].tMin = day["mintempC"].as<String>();
        // representative midday entry
        JsonArray hrs = day["hourly"];
        JsonObject mid = hrs[hrs.size() > 4 ? 4 : 0];
        for (JsonObject h : hrs) {
            if (h["time"].as<String>() == "1200") { mid = h; break; }
        }
        if (i > 0) {
            wx[i].tempBig = day["maxtempC"].as<String>();
            wx[i].code    = mid["weatherCode"].as<int>();
            wx[i].desc    = mid["weatherDesc"][0]["value"].as<String>();
            wx[i].humid   = "";
        }
    }

    wxLoaded = true;
    wxFetchedAt = millis();
}

String translateDesc(const String& eng) {
    if (eng == "Sunny")                     return "Sonnig";
    if (eng == "Clear")                     return "Klar";
    if (eng.indexOf("Partly cloudy") >= 0)  return "Teils bewoelkt";
    if (eng.indexOf("Cloudy") >= 0)         return "Bewoelkt";
    if (eng.indexOf("Overcast") >= 0)       return "Bedeckt";
    if (eng.indexOf("Drizzle") >= 0)        return "Nieselregen";
    if (eng.indexOf("Freezing") >= 0)       return "Gefrierend";
    if (eng.indexOf("Rain") >= 0)           return "Regen";
    if (eng.indexOf("Snow") >= 0)           return "Schnee";
    if (eng.indexOf("Blizzard") >= 0)       return "Schneesturm";
    if (eng.indexOf("Thunder") >= 0)        return "Gewitter";
    if (eng.indexOf("Fog") >= 0)            return "Nebel";
    if (eng.indexOf("Mist") >= 0)           return "Neblig";
    return eng;
}
