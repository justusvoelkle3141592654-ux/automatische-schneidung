#include <Arduino.h>
#include <TFT_eSPI.h>
#include <SPI.h>
#include <XPT2046_Touchscreen.h>
#include <WiFi.h>
#include <WiFiManager.h>
#include <HTTPClient.h>
#include <ArduinoJson.h>
#include <Preferences.h>

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

// ── Colors (RGB565) ────────────────────────────────────────
#define C_BG      0x0841u
#define C_TAB     0x2945u
#define C_TAB_ACT 0x0488u
#define C_KEY     0x3186u
#define C_KEY_SP  0x528Au
#define C_ACCENT  0x07FFu
#define C_LGREY   0xC618u
#define C_DGREY   0x7BEFu

// ── Hardware ───────────────────────────────────────────────
TFT_eSPI tft;
SPIClass touchSPI(VSPI);
XPT2046_Touchscreen touch(TOUCH_CS, TOUCH_IRQ);
Preferences prefs;

// ── App state ──────────────────────────────────────────────
enum Screen { SCR_WEATHER, SCR_NOTES };
Screen currentScreen = SCR_WEATHER;

String notesText = "";
String city      = "Berlin";

String wxTemp    = "--";
String wxDesc    = "Laedt...";
String wxHumid   = "--";
String wxFeels   = "--";
bool   wxLoaded  = false;
unsigned long wxFetchedAt = 0;
unsigned long lastTouchMs = 0;

// ── Keyboard rows ──────────────────────────────────────────
const char* ROW0[10] = {"Q","W","E","R","T","Z","U","I","O","P"};
const char* ROW1[10] = {"A","S","D","F","G","H","J","K","L","<"};
const char* ROW2[10] = {"Y","X","C","V","B","N","M","ae","oe","ue"};

// ── Forward declarations ───────────────────────────────────
void drawTabBar();
void drawWeatherScreen();
void drawNotesScreen();
void drawKeyboard();
void handleTouch(int x, int y);
void fetchWeather();
String translateDesc(const String& eng);

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

    // Backlight on
    pinMode(TFT_BL_PIN, OUTPUT);
    digitalWrite(TFT_BL_PIN, HIGH);

    // Display
    tft.init();
    tft.setRotation(1);
    tft.fillScreen(C_BG);
    tft.setTextColor(C_ACCENT);
    tft.setTextSize(2);
    tft.drawString("CYD Wetter & Notizen", 5, 90);
    tft.setTextColor(TFT_WHITE);
    tft.setTextSize(1);
    tft.drawString("Verbinde mit WLAN...", 10, 116);

    // Touch (VSPI, custom pins)
    touchSPI.begin(TOUCH_CLK, TOUCH_MISO, TOUCH_MOSI, TOUCH_CS);
    touch.begin(touchSPI);
    touch.setRotation(1);

    // Saved data
    prefs.begin("cyd", false);
    notesText = prefs.getString("notes", "");
    city      = prefs.getString("city",  "Berlin");

    // WiFi + optional config portal
    WiFiManager wm;
    wm.setConfigPortalTimeout(180);
    wm.setAPCallback(onAPStarted);
    WiFiManagerParameter cityParam("city", "Stadt fuer Wetter", city.c_str(), 40);
    wm.addParameter(&cityParam);
    wm.autoConnect("CYD-Setup");

    // Persist city (set by portal or keep existing)
    String nc = String(cityParam.getValue());
    if (nc.length() > 0) {
        city = nc;
        prefs.putString("city", city);
    }

    fetchWeather();

    tft.fillScreen(C_BG);
    drawTabBar();
    drawWeatherScreen();
}

// ══════════════════════════════════════════════════════════
void loop() {
    // Periodic weather refresh every 10 minutes
    if (WiFi.status() == WL_CONNECTED &&
        millis() - wxFetchedAt > 600000UL) {
        fetchWeather();
        if (currentScreen == SCR_WEATHER) drawWeatherScreen();
    }

    // Touch
    if (touch.tirqTouched() && touch.touched()) {
        TS_Point p = touch.getPoint();
        // Map raw ADC to screen coords (landscape rotation 1)
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

// ── Draw helpers ───────────────────────────────────────────
void drawTabBar() {
    uint16_t lc = (currentScreen == SCR_WEATHER) ? (uint16_t)C_TAB_ACT : (uint16_t)C_TAB;
    uint16_t rc = (currentScreen == SCR_NOTES)   ? (uint16_t)C_TAB_ACT : (uint16_t)C_TAB;

    tft.fillRect(0,     0, TAB_W, TAB_H, lc);
    tft.fillRect(TAB_W, 0, TAB_W, TAB_H, rc);

    tft.setTextSize(2);
    tft.setTextColor(TFT_WHITE, lc);
    tft.drawString("Wetter",  22, 7);
    tft.setTextColor(TFT_WHITE, rc);
    tft.drawString("Notizen", TAB_W + 14, 7);

    tft.drawLine(0, TAB_H, SCR_W, TAB_H, C_ACCENT);
}

void drawWeatherScreen() {
    tft.fillRect(0, TAB_H + 1, SCR_W, SCR_H - TAB_H - 1, C_BG);

    int y = TAB_H + 8;

    // City
    tft.setTextColor(C_ACCENT, C_BG);
    tft.setTextSize(2);
    tft.drawString(city, 10, y);
    y += 26;

    // Temperature (large)
    tft.setTextColor(TFT_WHITE, C_BG);
    tft.setTextSize(5);
    tft.drawString(wxTemp + " C", 10, y);
    y += 52;

    // Description
    tft.setTextSize(2);
    tft.setTextColor(C_LGREY, C_BG);
    String desc = translateDesc(wxDesc);
    tft.drawString(desc, 10, y);
    y += 26;

    // Details
    tft.setTextSize(1);
    tft.setTextColor(C_ACCENT, C_BG);
    tft.drawString("Luftfeuchte: " + wxHumid + " %", 10, y);
    y += 14;
    tft.drawString("Gefuehlt:    " + wxFeels + " C",  10, y);

    // Hint + last update
    tft.setTextColor(C_DGREY, C_BG);
    tft.drawString("[ Tippen zum Aktualisieren ]", 10, SCR_H - 20);

    if (wxLoaded) {
        unsigned long s = (millis() - wxFetchedAt) / 1000;
        String t = (s < 60) ? "Vor " + String(s) + "s"
                             : "Vor " + String(s / 60) + " Min";
        tft.drawString(t, SCR_W - 75, SCR_H - 20);
    }
}

void drawNotesScreen() {
    tft.fillRect(0, TAB_H + 1, SCR_W, KBD_Y - TAB_H - 1, C_BG);
    tft.drawRect(0, TAB_H, SCR_W - 1, KBD_Y - TAB_H, C_ACCENT);

    tft.setTextColor(TFT_WHITE, C_BG);
    tft.setTextSize(1);

    const int charPerLine = 52;
    const int lineH       = 10;
    const int maxLines    = (KBD_Y - TAB_H - 8) / lineH;
    const int textY       = TAB_H + 4;

    // Word-wrap notesText into lines, add cursor
    String linesBuf[10];
    int lineCount = 0;
    String cur = "";
    String display = notesText + "_";

    for (int i = 0; i < (int)display.length() && lineCount < 9; i++) {
        char c = display[i];
        if (c == '\n') {
            linesBuf[lineCount++] = cur;
            cur = "";
        } else if ((int)cur.length() >= charPerLine) {
            linesBuf[lineCount++] = cur;
            cur = String(c);
        } else {
            cur += c;
        }
    }
    linesBuf[lineCount++] = cur;

    int start = (lineCount > maxLines) ? lineCount - maxLines : 0;
    for (int i = start; i < lineCount; i++) {
        tft.drawString(linesBuf[i], 4, textY + (i - start) * lineH);
    }

    drawKeyboard();
}

void drawKeyboard() {
    // Row 0: QWERTZUIOP
    for (int i = 0; i < 10; i++) {
        int kx = i * KEY_W, ky = KBD_Y;
        tft.fillRect(kx + 1, ky + 1, KEY_W - 2, KEY_H - 2, C_KEY);
        tft.setTextColor(TFT_WHITE, C_KEY);
        tft.setTextSize(1);
        tft.drawString(ROW0[i], kx + 11, ky + 12);
    }
    // Row 1: ASDFGHJKL + DEL
    for (int i = 0; i < 10; i++) {
        int kx = i * KEY_W, ky = KBD_Y + KEY_H;
        uint16_t col = (i == 9) ? (uint16_t)C_KEY_SP : (uint16_t)C_KEY;
        tft.fillRect(kx + 1, ky + 1, KEY_W - 2, KEY_H - 2, col);
        tft.setTextColor(TFT_WHITE, col);
        tft.setTextSize(1);
        if (i == 9) tft.drawString("DEL", kx + 5,  ky + 12);
        else        tft.drawString(ROW1[i], kx + 11, ky + 12);
    }
    // Row 2: YXCVBNM + ae/oe/ue
    for (int i = 0; i < 10; i++) {
        int kx = i * KEY_W, ky = KBD_Y + KEY_H * 2;
        uint16_t col = (i >= 7) ? (uint16_t)C_KEY_SP : (uint16_t)C_KEY;
        tft.fillRect(kx + 1, ky + 1, KEY_W - 2, KEY_H - 2, col);
        tft.setTextColor(TFT_WHITE, col);
        tft.setTextSize(1);
        int tx = (strlen(ROW2[i]) > 1) ? kx + 7 : kx + 11;
        tft.drawString(ROW2[i], tx, ky + 12);
    }
    // Row 3: [.][,][   LEER   ][NL]
    int ky3 = KBD_Y + KEY_H * 3;
    tft.fillRect(1,            ky3 + 1, KEY_W - 2,  KEY_H - 2, C_KEY);
    tft.fillRect(KEY_W + 1,    ky3 + 1, KEY_W - 2,  KEY_H - 2, C_KEY);
    tft.fillRect(KEY_W*2 + 1,  ky3 + 1, 192 - 2,    KEY_H - 2, C_KEY_SP);
    tft.fillRect(KEY_W*2 + 193, ky3 + 1, 63,         KEY_H - 2, C_ACCENT);

    tft.setTextColor(TFT_WHITE, C_KEY);
    tft.setTextSize(1);
    tft.drawString(".",    11,           ky3 + 12);
    tft.drawString(",",    KEY_W + 11,   ky3 + 12);
    tft.drawString("LEER", KEY_W*2 + 76, ky3 + 12);
    tft.setTextColor(C_BG, C_ACCENT);
    tft.drawString("NL",   KEY_W*2 + 213, ky3 + 12);
}

// ── Touch event router ─────────────────────────────────────
void handleTouch(int x, int y) {
    // Tab bar
    if (y < TAB_H) {
        Screen ns = (x < TAB_W) ? SCR_WEATHER : SCR_NOTES;
        if (ns != currentScreen) {
            currentScreen = ns;
            tft.fillScreen(C_BG);
            drawTabBar();
            if (currentScreen == SCR_WEATHER) drawWeatherScreen();
            else                               drawNotesScreen();
        }
        return;
    }

    // Weather: tap anywhere = refresh
    if (currentScreen == SCR_WEATHER) {
        if (WiFi.status() == WL_CONNECTED) {
            fetchWeather();
            drawWeatherScreen();
        }
        return;
    }

    // Notes keyboard
    if (y < KBD_Y) return;

    int row = (y - KBD_Y) / KEY_H;
    int col = constrain(x / KEY_W, 0, 9);
    String ch = "";

    if (row == 0) {
        ch = String(ROW0[col]);
    } else if (row == 1) {
        if (col == 9) {
            if (notesText.length() > 0)
                notesText.remove(notesText.length() - 1);
            prefs.putString("notes", notesText);
            drawNotesScreen();
            return;
        }
        ch = String(ROW1[col]);
    } else if (row == 2) {
        ch = String(ROW2[col]);
    } else if (row == 3) {
        if      (x < KEY_W)           ch = ".";
        else if (x < KEY_W * 2)       ch = ",";
        else if (x < KEY_W * 2 + 192) ch = " ";
        else                           ch = "\n";
    }

    if (ch.length() > 0) {
        notesText += ch;
        prefs.putString("notes", notesText);
        drawNotesScreen();
    }
}

// ── Weather fetcher ────────────────────────────────────────
void fetchWeather() {
    if (WiFi.status() != WL_CONNECTED) {
        wxDesc = "Kein WLAN";
        return;
    }
    HTTPClient http;
    http.begin("http://wttr.in/" + city + "?format=j1");
    http.setTimeout(10000);
    int code = http.GET();
    if (code == HTTP_CODE_OK) {
        JsonDocument doc;
        DeserializationError err = deserializeJson(doc, http.getStream());
        if (!err) {
            JsonObject cur = doc["current_condition"][0];
            wxTemp   = cur["temp_C"].as<String>();
            wxDesc   = cur["weatherDesc"][0]["value"].as<String>();
            wxHumid  = cur["humidity"].as<String>();
            wxFeels  = cur["FeelsLikeC"].as<String>();
            wxLoaded = true;
            wxFetchedAt = millis();
        } else {
            wxDesc = "Parse-Fehler";
        }
    } else {
        wxDesc = "HTTP " + String(code);
    }
    http.end();
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
