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

// ── Hardware ──────────────────────────────────────
TFT_eSPI tft;
SPIClass touchSPI(VSPI);
XPT2046_Touchscreen touch(TOUCH_CS, TOUCH_IRQ);
Preferences prefs;

// ── Palette (assigned at runtime) ───────────────────
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

// ── App state ──────────────────────────────────────
enum Screen { SCR_NOTES_LIST, SCR_NOTES_EDIT, SCR_QUIZ, SCR_SNAKE };
Screen currentScreen = SCR_NOTES_LIST;
unsigned long lastTouchMs = 0;

// ── Touch calibration ──────────────────────────
bool touchCal = false;
int  cAxSwap = 0, cXa = 200, cXb = 3700, cYa = 240, cYb = 3800;

// ── Notes ────────────────────────────────────────
#define MAX_NOTES 30
String notes[MAX_NOTES];
int    noteCount  = 0;
int    editIndex  = -1;
int    listScroll = 0;
#define LIST_TOP    66
#define LIST_ITEM_H 34
#define LIST_VIS    5

const char* ROW0[10] = {"Q","W","E","R","T","Z","U","I","O","P"};
const char* ROW1[10] = {"A","S","D","F","G","H","J","K","L","<"};
const char* ROW2[10] = {"Y","X","C","V","B","N","M","ae","oe","ue"};

// ── Quiz ─────────────────────────────────────────
#define QN 5
const char* qQ[QN] = {
    "Welches Material wird beim FDM-Druck am haeufigsten benutzt?",
    "Wofuer steht die Abkuerzung FDM?",
    "Wie gross ist eine typische Standard-Duese?",
    "Was ist ein 'Brim' beim 3D-Druck?",
    "Welche Duesentemperatur passt etwa fuer PLA?"
};
const char* qO[QN][4] = {
    {"PLA", "Beton", "Glas", "Papier"},
    {"Fast Data Mode", "Final Draft Model", "Fused Deposition Mod.", "Flex Druck Material"},
    {"4 mm", "0.4 mm", "40 mm", "14 mm"},
    {"Rand fuer Haftung", "Ein Druckfehler", "Die Duese", "Ein Filament-Typ"},
    {"60 C", "1000 C", "500 C", "200 C"}
};
const int qCorrect[QN] = {0, 2, 1, 0, 3};
int  qIdx = 0, qScore = 0, qPicked = -1;
bool qAnswered = false, qFinished = false;
#define OPT_Y0 92
#define OPT_H  35
#define OPT_STEP 37

// ── Snake ────────────────────────────────────────
#define CELL  10
#define GCOLS 32
#define GY0   46
#define GROWS 19
#define SNAKE_MAX 200
int  snX[SNAKE_MAX], snY[SNAKE_MAX];
int  snLen, snDir, foodX, foodY, snScore, snBest = 0;
bool snOver = false;
unsigned long snLastMove = 0;
const int SN_SPEED = 170;

// ── Forward declarations ──────────────────────────
void drawTabs(int active);
void drawGear(int cx, int cy);
void drawNotesList();
void drawNotesEdit();
void drawKeyboard();
void drawQuiz();
void initSnake();
void drawSnakeFull();
void moveSnake();
void handleTouch(int x, int y);
void switchScreen(Screen s);
void redrawCurrent();
void runCalibration();

// ── Notes persistence ──────────────────────────
void loadNotes() {
    noteCount = prefs.getInt("ncount", 0);
    if (noteCount > MAX_NOTES) noteCount = MAX_NOTES;
    for (int i = 0; i < noteCount; i++)
        notes[i] = prefs.getString(("n" + String(i)).c_str(), "");
}
void saveNote(int i) { prefs.putString(("n" + String(i)).c_str(), notes[i]); }
void saveCount()     { prefs.putInt("ncount", noteCount); }
void addNote() {
    if (noteCount >= MAX_NOTES) return;
    notes[noteCount] = "";
    editIndex = noteCount;
    noteCount++;
    saveCount();
    saveNote(editIndex);
}
void deleteNote(int idx) {
    for (int i = idx; i < noteCount - 1; i++) { notes[i] = notes[i + 1]; saveNote(i); }
    noteCount--;
    saveCount();
}

// ── Touch mapping using calibration ──────────────────
void mapTouchPoint(const TS_Point& p, int& sx, int& sy) {
    int xsrc = cAxSwap ? p.y : p.x;
    int ysrc = cAxSwap ? p.x : p.y;
    sx = map(xsrc, cXa, cXb, 40, 280);
    sy = map(ysrc, cYa, cYb, 40, 200);
    sx = constrain(sx, 0, SCR_W - 1);
    sy = constrain(sy, 0, SCR_H - 1);
}

// ═══════════════════════════════════════════════
void setup() {
    Serial.begin(115200);
    pinMode(TFT_BL_PIN, OUTPUT);
    digitalWrite(TFT_BL_PIN, HIGH);

    tft.init();
    tft.setRotation(1);
    setupPalette();
    tft.fillScreen(COL_BG);

    touchSPI.begin(TOUCH_CLK, TOUCH_MISO, TOUCH_MOSI, TOUCH_CS);
    touch.begin(touchSPI);
    touch.setRotation(0);   // use native raw orientation; calibration handles the rest

    prefs.begin("cyd", false);
    loadNotes();
    snBest = prefs.getInt("snbest", 0);
    randomSeed(esp_random());

    touchCal = prefs.getBool("tcal", false);
    if (touchCal) {
        cAxSwap = prefs.getInt("taxs", 0);
        cXa = prefs.getInt("txa", 200);
        cXb = prefs.getInt("txb", 3700);
        cYa = prefs.getInt("tya", 240);
        cYb = prefs.getInt("tyb", 3800);
    }

    // Splash
    tft.setTextColor(COL_ACCENT);
    tft.setTextSize(2);
    tft.drawString("CYD Mini-Apps", 66, 92);
    tft.setTextColor(COL_MUTE);
    tft.setTextSize(1);
    tft.drawString("Notizen  -  Quiz  -  Snake", 80, 120);
    delay(900);

    if (!touchCal) runCalibration();

    switchScreen(SCR_NOTES_LIST);
}

// ═══════════════════════════════════════════════
void loop() {
    if (currentScreen == SCR_SNAKE && !snOver &&
        millis() - snLastMove > (unsigned long)SN_SPEED) {
        snLastMove = millis();
        moveSnake();
    }
    if (touch.tirqTouched() && touch.touched()) {
        TS_Point p = touch.getPoint();
        int sx, sy;
        mapTouchPoint(p, sx, sy);
        if (millis() - lastTouchMs > 220UL) {
            lastTouchMs = millis();
            handleTouch(sx, sy);
        }
    }
    delay(15);
}

// ── Touch calibration (3 points, detects axis swap/inversion) ─
void drawCross(int x, int y, uint16_t col) {
    tft.drawFastHLine(x - 12, y, 25, col);
    tft.drawFastVLine(x, y - 12, 25, col);
    tft.drawCircle(x, y, 6, col);
}
void calWaitRelease() { while (touch.touched()) delay(10); delay(150); }
TS_Point calReadPoint(int px, int py, int idx) {
    tft.fillScreen(COL_BG);
    tft.setTextColor(COL_ACCENT); tft.setTextSize(2);
    tft.drawString("Touch-Kalibrierung", 36, 24);
    tft.setTextColor(COL_TEXT); tft.setTextSize(1);
    tft.drawString("Bitte das Kreuz genau antippen.", 50, 56);
    tft.setTextColor(COL_MUTE);
    tft.drawString("Punkt " + String(idx + 1) + " von 3", 130, 72);
    drawCross(px, py, COL_ACCENT);
    while (!(touch.tirqTouched() && touch.touched())) delay(10);
    TS_Point p = touch.getPoint();
    drawCross(px, py, COL_GOOD);
    delay(120);
    calWaitRelease();
    return p;
}
void runCalibration() {
    const int px[3] = {40, 280, 40};
    const int py[3] = {40, 40, 200};
    int rx[3], ry[3];
    calWaitRelease();
    for (int i = 0; i < 3; i++) {
        TS_Point p = calReadPoint(px[i], py[i], i);
        rx[i] = p.x; ry[i] = p.y;
    }
    int dxx = abs(rx[1] - rx[0]), dxy = abs(ry[1] - ry[0]);
    cAxSwap = (dxy > dxx) ? 1 : 0;
    cXa = cAxSwap ? ry[0] : rx[0];
    cXb = cAxSwap ? ry[1] : rx[1];
    cYa = cAxSwap ? rx[0] : ry[0];
    cYb = cAxSwap ? rx[2] : ry[2];
    if (cXa == cXb) cXb = cXa + 1;
    if (cYa == cYb) cYb = cYa + 1;
    prefs.putBool("tcal", true);
    prefs.putInt("taxs", cAxSwap);
    prefs.putInt("txa", cXa); prefs.putInt("txb", cXb);
    prefs.putInt("tya", cYa); prefs.putInt("tyb", cYb);
    touchCal = true;
    tft.fillScreen(COL_BG);
    tft.setTextColor(COL_GOOD); tft.setTextSize(2);
    tft.drawString("Fertig kalibriert!", 44, 104);
    delay(700);
}

// ── Tab bar (Notizen | Quiz | Snake) + gear ───────────
void drawGear(int cx, int cy) {
    tft.fillCircle(cx, cy, 7, COL_ACCENT);
    tft.fillCircle(cx, cy, 3, COL_BG);
    tft.fillRect(cx - 1, cy - 10, 2, 4, COL_ACCENT);
    tft.fillRect(cx - 1, cy + 6, 2, 4, COL_ACCENT);
    tft.fillRect(cx - 10, cy - 1, 4, 2, COL_ACCENT);
    tft.fillRect(cx + 6, cy - 1, 4, 2, COL_ACCENT);
}
void drawTabs(int active) {
    tft.fillRect(0, 0, SCR_W, TAB_H, COL_BG);
    const char* names[3] = {"Notizen", "Quiz", "Snake"};
    for (int i = 0; i < 3; i++) {
        int x = 3 + i * 96, w = 90;
        uint16_t bg = (i == active) ? COL_ACCENT : COL_CARD;
        tft.fillRoundRect(x, 3, w, 24, 8, bg);
        tft.setTextSize(2);
        tft.setTextColor((i == active) ? COL_DARK : COL_MUTE, bg);
        int tw = strlen(names[i]) * 12;
        tft.drawString(names[i], x + (w - tw) / 2, 9);
    }
    drawGear(305, 15);
}
bool handleTabTap(int x, int y) {
    if (y >= TAB_H) return false;
    if (x >= 292) { runCalibration(); redrawCurrent(); return true; }
    int i = x / 96; if (i > 2) i = 2;
    if (i == 0) switchScreen(SCR_NOTES_LIST);
    else if (i == 1) switchScreen(SCR_QUIZ);
    else switchScreen(SCR_SNAKE);
    return true;
}
void switchScreen(Screen s) {
    currentScreen = s;
    redrawCurrent();
}
void redrawCurrent() {
    if (currentScreen == SCR_NOTES_LIST) drawNotesList();
    else if (currentScreen == SCR_NOTES_EDIT) drawNotesEdit();
    else if (currentScreen == SCR_QUIZ) drawQuiz();
    else if (currentScreen == SCR_SNAKE) {
        if (snLen == 0) initSnake(); else drawSnakeFull();
    }
}

// ── Word-wrap helper ────────────────────────────
void drawWrapped(String s, int x, int y, int maxChars, int lineH) {
    String line = "";
    while (s.length()) {
        int sp = s.indexOf(' ');
        String w = (sp < 0) ? s : s.substring(0, sp);
        s = (sp < 0) ? "" : s.substring(sp + 1);
        if (line.length() && (int)(line.length() + 1 + w.length()) > maxChars) {
            tft.drawString(line, x, y); y += lineH; line = w;
        } else line = line.length() ? line + " " + w : w;
    }
    if (line.length()) tft.drawString(line, x, y);
}

// ══ NOTES LIST ═══════════════════════════════════
String firstLine(const String& s) {
    int nl = s.indexOf('\n');
    String l = (nl >= 0) ? s.substring(0, nl) : s;
    if (l.length() == 0) l = "(leere Notiz)";
    if (l.length() > 28) l = l.substring(0, 28) + "...";
    return l;
}
void drawNotesList() {
    tft.fillScreen(COL_BG);
    drawTabs(0);
    tft.fillRoundRect(6, 36, SCR_W - 12, 24, 8, COL_ACCENT2);
    tft.setTextSize(2);
    tft.setTextColor(COL_DARK, COL_ACCENT2);
    tft.drawString("+ Neue Notiz", 100, 42);

    if (noteCount == 0) {
        tft.setTextSize(1);
        tft.setTextColor(COL_MUTE);
        tft.drawString("Noch keine Notizen.", 18, 112);
        tft.drawString("Tippe oben auf '+ Neue Notiz'.", 18, 128);
        return;
    }
    int maxScroll = noteCount > LIST_VIS ? noteCount - LIST_VIS : 0;
    if (listScroll > maxScroll) listScroll = maxScroll;
    if (listScroll < 0) listScroll = 0;
    bool scrollable = noteCount > LIST_VIS;
    int itemW = scrollable ? SCR_W - 34 : SCR_W - 12;
    for (int v = 0; v < LIST_VIS; v++) {
        int idx = listScroll + v;
        if (idx >= noteCount) break;
        int y = LIST_TOP + v * LIST_ITEM_H;
        tft.fillRoundRect(6, y, itemW, LIST_ITEM_H - 5, 6, COL_CARD);
        tft.fillRoundRect(6, y, 5, LIST_ITEM_H - 5, 2, COL_ACCENT);
        tft.setTextSize(2);
        tft.setTextColor(COL_TEXT, COL_CARD);
        tft.drawString(firstLine(notes[idx]), 16, y + 7);
    }
    if (scrollable) {
        int ax = SCR_W - 26;
        tft.fillRoundRect(ax, LIST_TOP, 22, 70, 6, COL_CARD);
        tft.fillTriangle(ax + 11, LIST_TOP + 8, ax + 4, LIST_TOP + 22, ax + 18, LIST_TOP + 22, COL_ACCENT);
        int by = LIST_TOP + 78;
        tft.fillRoundRect(ax, by, 22, 70, 6, COL_CARD);
        tft.fillTriangle(ax + 11, by + 62, ax + 4, by + 48, ax + 18, by + 48, COL_ACCENT);
    }
}

// ══ NOTES EDIT ═══════════════════════════════════
void drawNotesEdit() {
    tft.fillRect(0, 0, SCR_W, KBD_Y, COL_BG);
    tft.fillRoundRect(4, 3, 150, 24, 8, COL_ACCENT);
    tft.fillRoundRect(166, 3, 150, 24, 8, COL_BAD);
    tft.setTextSize(2);
    tft.setTextColor(COL_DARK, COL_ACCENT);
    tft.drawString("< Speichern", 20, 9);
    tft.setTextColor(COL_TEXT, COL_BAD);
    tft.drawString("Loeschen", 196, 9);
    tft.drawRoundRect(2, TAB_H + 2, SCR_W - 4, KBD_Y - TAB_H - 4, 6, COL_CARD2);

    tft.setTextColor(COL_TEXT, COL_BG);
    tft.setTextSize(1);
    const int charPerLine = 52, lineH = 10;
    const int maxLines = (KBD_Y - TAB_H - 10) / lineH;
    const int textY = TAB_H + 6;
    String linesBuf[10]; int lineCount = 0; String cur = "";
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
        tft.drawString(linesBuf[i], 8, textY + (i - start) * lineH);
    drawKeyboard();
}
void drawKeyboard() {
    for (int i = 0; i < 10; i++) {
        int kx = i * KEY_W, ky = KBD_Y;
        tft.fillRoundRect(kx + 1, ky + 1, KEY_W - 2, KEY_H - 2, 4, COL_KEY);
        tft.setTextColor(COL_TEXT, COL_KEY); tft.setTextSize(1);
        tft.drawString(ROW0[i], kx + 11, ky + 12);
    }
    for (int i = 0; i < 10; i++) {
        int kx = i * KEY_W, ky = KBD_Y + KEY_H;
        uint16_t col = (i == 9) ? COL_ACCENT2 : COL_KEY;
        tft.fillRoundRect(kx + 1, ky + 1, KEY_W - 2, KEY_H - 2, 4, col);
        tft.setTextColor(COL_TEXT, col); tft.setTextSize(1);
        if (i == 9) tft.drawString("DEL", kx + 5, ky + 12);
        else        tft.drawString(ROW1[i], kx + 11, ky + 12);
    }
    for (int i = 0; i < 10; i++) {
        int kx = i * KEY_W, ky = KBD_Y + KEY_H * 2;
        uint16_t col = (i >= 7) ? COL_KEYSP : COL_KEY;
        tft.fillRoundRect(kx + 1, ky + 1, KEY_W - 2, KEY_H - 2, 4, col);
        tft.setTextColor(COL_TEXT, col); tft.setTextSize(1);
        int tx = (strlen(ROW2[i]) > 1) ? kx + 7 : kx + 11;
        tft.drawString(ROW2[i], tx, ky + 12);
    }
    int ky3 = KBD_Y + KEY_H * 3;
    tft.fillRoundRect(1, ky3 + 1, KEY_W - 2, KEY_H - 2, 4, COL_KEY);
    tft.fillRoundRect(KEY_W + 1, ky3 + 1, KEY_W - 2, KEY_H - 2, 4, COL_KEY);
    tft.fillRoundRect(KEY_W * 2 + 1, ky3 + 1, 192 - 2, KEY_H - 2, 4, COL_KEYSP);
    tft.fillRoundRect(KEY_W * 2 + 193, ky3 + 1, 63, KEY_H - 2, 4, COL_ACCENT);
    tft.setTextColor(COL_TEXT, COL_KEY); tft.setTextSize(1);
    tft.drawString(".", 11, ky3 + 12);
    tft.drawString(",", KEY_W + 11, ky3 + 12);
    tft.setTextColor(COL_TEXT, COL_KEYSP);
    tft.drawString("LEER", KEY_W * 2 + 76, ky3 + 12);
    tft.setTextColor(COL_DARK, COL_ACCENT);
    tft.drawString("NL", KEY_W * 2 + 213, ky3 + 12);
}

// ══ QUIZ ════════════════════════════════════════
void drawQuiz() {
    tft.fillScreen(COL_BG);
    drawTabs(1);
    if (qFinished) {
        tft.setTextSize(2); tft.setTextColor(COL_ACCENT);
        tft.drawString("Quiz beendet!", 70, 58);
        tft.setTextSize(3); tft.setTextColor(COL_TEXT);
        tft.drawString(String(qScore) + " / " + String(QN), 108, 96);
        tft.fillRoundRect(90, 158, 140, 46, 10, COL_ACCENT);
        tft.setTextSize(2); tft.setTextColor(COL_DARK, COL_ACCENT);
        tft.drawString("Nochmal", 112, 172);
        return;
    }
    tft.setTextSize(1); tft.setTextColor(COL_MUTE);
    tft.drawString("Frage " + String(qIdx + 1) + " / " + String(QN) +
                   "     Punkte: " + String(qScore), 10, 38);
    tft.setTextSize(2); tft.setTextColor(COL_TEXT);
    drawWrapped(qQ[qIdx], 10, 52, 25, 17);
    for (int i = 0; i < 4; i++) {
        int y = OPT_Y0 + i * OPT_STEP;
        uint16_t bg = COL_CARD;
        if (qAnswered) {
            if (i == qCorrect[qIdx]) bg = COL_GOOD;
            else if (i == qPicked)   bg = COL_BAD;
        }
        tft.fillRoundRect(6, y, SCR_W - 12, OPT_H, 8, bg);
        tft.fillRoundRect(12, y + 6, 22, OPT_H - 12, 5, COL_DARK);
        tft.setTextSize(2);
        tft.setTextColor(COL_ACCENT, COL_DARK);
        char letter[2] = {(char)('A' + i), 0};
        tft.drawString(letter, 19, y + 9);
        tft.setTextColor(COL_TEXT, bg);
        tft.drawString(qO[qIdx][i], 44, y + 9);
    }
    if (qAnswered) {
        tft.setTextSize(1); tft.setTextColor(COL_ACCENT);
        tft.drawString("Tippen fuer naechste Frage", 90, 232);
    }
}

// ══ SNAKE ═════════════════════════════════════
void placeFood() {
    bool ok;
    do {
        ok = true;
        foodX = random(GCOLS); foodY = random(GROWS);
        for (int i = 0; i < snLen; i++)
            if (snX[i] == foodX && snY[i] == foodY) { ok = false; break; }
    } while (!ok);
}
void snCell(int cx, int cy, uint16_t col) {
    tft.fillRoundRect(cx * CELL, GY0 + cy * CELL, CELL - 1, CELL - 1, 2, col);
}
void drawSnakeHeader() {
    tft.fillRect(0, TAB_H + 1, SCR_W, GY0 - TAB_H - 1, COL_BG);
    tft.fillRoundRect(6, 33, 150, 12, 4, COL_CARD);
    tft.setTextSize(1); tft.setTextColor(COL_TEXT, COL_CARD);
    tft.drawString("Punkte " + String(snScore) + "   Best " + String(snBest), 12, 35);
}
void drawSnakeFull() {
    tft.fillScreen(COL_BG);
    drawTabs(2);
    drawSnakeHeader();
    tft.drawRoundRect(0, GY0 - 2, SCR_W, GROWS * CELL + 4, 4, COL_CARD2);
    snCell(foodX, foodY, COL_FOOD);
    for (int i = 0; i < snLen; i++)
        snCell(snX[i], snY[i], i == 0 ? COL_SNAKEHD : COL_SNAKE);
}
void initSnake() {
    snLen = 3; snDir = 1;
    snX[0] = 8; snY[0] = 9;
    snX[1] = 7; snY[1] = 9;
    snX[2] = 6; snY[2] = 9;
    snScore = 0; snOver = false;
    placeFood();
    drawSnakeFull();
}
void snakeGameOver() {
    snOver = true;
    if (snScore > snBest) { snBest = snScore; prefs.putInt("snbest", snBest); }
    tft.fillRoundRect(38, 86, 244, 96, 10, COL_CARD);
    tft.drawRoundRect(38, 86, 244, 96, 10, COL_BAD);
    tft.setTextSize(3); tft.setTextColor(COL_BAD);
    tft.drawString("Game Over", 76, 100);
    tft.setTextSize(2); tft.setTextColor(COL_TEXT);
    tft.drawString("Punkte: " + String(snScore), 98, 138);
    tft.setTextSize(1); tft.setTextColor(COL_ACCENT);
    tft.drawString("Tippen zum Neustart", 100, 164);
}
void moveSnake() {
    int nx = snX[0], ny = snY[0];
    if (snDir == 0) ny--; else if (snDir == 1) nx++;
    else if (snDir == 2) ny++; else nx--;
    if (nx < 0 || nx >= GCOLS || ny < 0 || ny >= GROWS) { snakeGameOver(); return; }
    for (int i = 0; i < snLen; i++)
        if (snX[i] == nx && snY[i] == ny) { snakeGameOver(); return; }
    bool ate = (nx == foodX && ny == foodY);
    int tailX = snX[snLen - 1], tailY = snY[snLen - 1];
    for (int i = snLen - 1; i > 0; i--) { snX[i] = snX[i - 1]; snY[i] = snY[i - 1]; }
    snX[0] = nx; snY[0] = ny;
    if (ate) {
        if (snLen < SNAKE_MAX) { snX[snLen] = tailX; snY[snLen] = tailY; snLen++; }
        snScore++; placeFood(); snCell(foodX, foodY, COL_FOOD); drawSnakeHeader();
    } else {
        tft.fillRect(tailX * CELL, GY0 + tailY * CELL, CELL - 1, CELL - 1, COL_BG);
    }
    snCell(snX[0], snY[0], COL_SNAKEHD);
    if (snLen > 1) snCell(snX[1], snY[1], COL_SNAKE);
}
void steerSnake(int x, int y) {
    int dx = x - 160, dy = y - (GY0 + GROWS * CELL / 2);
    int nd;
    if (abs(dx) > abs(dy)) nd = (dx > 0) ? 1 : 3;
    else                   nd = (dy > 0) ? 2 : 0;
    if ((snDir == 0 && nd == 2) || (snDir == 2 && nd == 0) ||
        (snDir == 1 && nd == 3) || (snDir == 3 && nd == 1)) return;
    snDir = nd;
}

// ── Touch router ──────────────────────────────────
void handleTouch(int x, int y) {
    if (currentScreen == SCR_NOTES_LIST) {
        if (handleTabTap(x, y)) return;
        if (y >= 36 && y < 60) { addNote(); switchScreen(SCR_NOTES_EDIT); return; }
        bool scrollable = noteCount > LIST_VIS;
        if (scrollable && x >= SCR_W - 28) {
            if (y < LIST_TOP + 74) listScroll--; else listScroll++;
            drawNotesList(); return;
        }
        if (y >= LIST_TOP) {
            int v = (y - LIST_TOP) / LIST_ITEM_H, idx = listScroll + v;
            if (v < LIST_VIS && idx < noteCount) { editIndex = idx; switchScreen(SCR_NOTES_EDIT); }
        }
        return;
    }
    if (currentScreen == SCR_NOTES_EDIT) {
        if (y < TAB_H) {
            if (x < 160) { if (editIndex >= 0) saveNote(editIndex); }
            else         { if (editIndex >= 0) deleteNote(editIndex); }
            editIndex = -1; switchScreen(SCR_NOTES_LIST); return;
        }
        if (y < KBD_Y || editIndex < 0) return;
        int row = (y - KBD_Y) / KEY_H, col = constrain(x / KEY_W, 0, 9);
        String ch = "";
        if (row == 0) ch = String(ROW0[col]);
        else if (row == 1) {
            if (col == 9) {
                if (notes[editIndex].length() > 0) notes[editIndex].remove(notes[editIndex].length() - 1);
                saveNote(editIndex); drawNotesEdit(); return;
            }
            ch = String(ROW1[col]);
        }
        else if (row == 2) ch = String(ROW2[col]);
        else if (row == 3) {
            if (x < KEY_W) ch = ".";
            else if (x < KEY_W * 2) ch = ",";
            else if (x < KEY_W * 2 + 192) ch = " ";
            else ch = "\n";
        }
        if (ch.length()) { notes[editIndex] += ch; saveNote(editIndex); drawNotesEdit(); }
        return;
    }
    if (currentScreen == SCR_QUIZ) {
        if (handleTabTap(x, y)) return;
        if (qFinished) {
            if (x >= 90 && x <= 230 && y >= 158 && y <= 204) {
                qIdx = 0; qScore = 0; qPicked = -1; qAnswered = false; qFinished = false;
                drawQuiz();
            }
            return;
        }
        if (!qAnswered) {
            if (y >= OPT_Y0) {
                int i = (y - OPT_Y0) / OPT_STEP;
                if (i >= 0 && i < 4) {
                    qPicked = i; qAnswered = true;
                    if (i == qCorrect[qIdx]) qScore++;
                    drawQuiz();
                }
            }
        } else {
            qIdx++;
            if (qIdx >= QN) qFinished = true;
            else { qAnswered = false; qPicked = -1; }
            drawQuiz();
        }
        return;
    }
    if (currentScreen == SCR_SNAKE) {
        if (handleTabTap(x, y)) return;
        if (snOver) { initSnake(); return; }
        steerSnake(x, y);
        return;
    }
}
