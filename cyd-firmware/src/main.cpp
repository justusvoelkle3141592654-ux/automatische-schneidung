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

// ── Colors (RGB565) ─────────────────────────────────
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
#define C_GREEN   0x2664u
#define C_GOOD    0x07E0u
#define C_BAD     0xF800u
#define C_FOOD    0xFB40u
#define C_SNAKE   0x07E6u

// ── Hardware ──────────────────────────────────────
TFT_eSPI tft;
SPIClass touchSPI(VSPI);
XPT2046_Touchscreen touch(TOUCH_CS, TOUCH_IRQ);
Preferences prefs;

// ── App state ──────────────────────────────────────
enum Screen { SCR_NOTES_LIST, SCR_NOTES_EDIT, SCR_QUIZ, SCR_SNAKE };
Screen currentScreen = SCR_NOTES_LIST;
unsigned long lastTouchMs = 0;

// ── Notes ────────────────────────────────────────
#define MAX_NOTES 30
String notes[MAX_NOTES];
int    noteCount  = 0;
int    editIndex  = -1;
int    listScroll = 0;
#define LIST_TOP    62
#define LIST_ITEM_H 34
#define LIST_VIS    5

const char* ROW0[10] = {"Q","W","E","R","T","Z","U","I","O","P"};
const char* ROW1[10] = {"A","S","D","F","G","H","J","K","L","<"};
const char* ROW2[10] = {"Y","X","C","V","B","N","M","ae","oe","ue"};

// ── Quiz (offline, 3D printing) ─────────────────────
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
void drawNotesList();
void drawNotesEdit();
void drawKeyboard();
void drawQuiz();
void initSnake();
void drawSnakeFull();
void moveSnake();
void handleTouch(int x, int y);
void switchScreen(Screen s);

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

// ═══════════════════════════════════════════════
void setup() {
    Serial.begin(115200);

    pinMode(TFT_BL_PIN, OUTPUT);
    digitalWrite(TFT_BL_PIN, HIGH);

    tft.init();
    tft.setRotation(1);
    tft.fillScreen(C_BG);

    // Touch – standard CYD landscape calibration
    touchSPI.begin(TOUCH_CLK, TOUCH_MISO, TOUCH_MOSI, TOUCH_CS);
    touch.begin(touchSPI);
    touch.setRotation(1);

    prefs.begin("cyd", false);
    loadNotes();
    snBest = prefs.getInt("snbest", 0);
    randomSeed(esp_random());

    // Splash
    tft.setTextColor(C_ACCENT);
    tft.setTextSize(2);
    tft.drawString("CYD Mini-Apps", 70, 96);
    tft.setTextColor(C_LGREY);
    tft.setTextSize(1);
    tft.drawString("Notizen  -  Quiz  -  Snake", 80, 124);
    delay(900);

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
        int sx = map(p.x, 200, 3700, 0, SCR_W);
        int sy = map(p.y, 240, 3800, 0, SCR_H);
        sx = constrain(sx, 0, SCR_W - 1);
        sy = constrain(sy, 0, SCR_H - 1);
        if (millis() - lastTouchMs > 220UL) {
            lastTouchMs = millis();
            handleTouch(sx, sy);
        }
    }
    delay(15);
}

// ── Tab bar (Notizen | Quiz | Snake) ────────────────
void drawTabs(int active) {
    const char* names[3] = {"Notizen", "Quiz", "Snake"};
    for (int i = 0; i < 3; i++) {
        int x = i * 107;
        uint16_t bg = (i == active) ? (uint16_t)C_TAB_ACT : (uint16_t)C_TAB;
        tft.fillRect(x, 0, (i < 2 ? 106 : 106), TAB_H, bg);
        tft.setTextSize(2);
        tft.setTextColor(TFT_WHITE, bg);
        int tw = strlen(names[i]) * 12;
        tft.drawString(names[i], x + (106 - tw) / 2, 7);
    }
    tft.drawLine(0, TAB_H, SCR_W, TAB_H, C_ACCENT);
}

bool handleTabTap(int x, int y) {
    if (y >= TAB_H) return false;
    int i = x / 107; if (i > 2) i = 2;
    if (i == 0) switchScreen(SCR_NOTES_LIST);
    else if (i == 1) switchScreen(SCR_QUIZ);
    else switchScreen(SCR_SNAKE);
    return true;
}

void switchScreen(Screen s) {
    currentScreen = s;
    if (s == SCR_NOTES_LIST) { listScroll = 0; drawNotesList(); }
    else if (s == SCR_NOTES_EDIT) drawNotesEdit();
    else if (s == SCR_QUIZ) drawQuiz();
    else if (s == SCR_SNAKE) initSnake();
}

// ── Word-wrapped text helper ──────────────────────
int drawWrapped(String s, int x, int y, int maxChars, int lineH) {
    String line = "";
    while (s.length()) {
        int sp = s.indexOf(' ');
        String w = (sp < 0) ? s : s.substring(0, sp);
        s = (sp < 0) ? "" : s.substring(sp + 1);
        if (line.length() && (int)(line.length() + 1 + w.length()) > maxChars) {
            tft.drawString(line, x, y); y += lineH; line = w;
        } else {
            line = line.length() ? line + " " + w : w;
        }
    }
    if (line.length()) { tft.drawString(line, x, y); y += lineH; }
    return y;
}

// ══ NOTES LIST ═══════════════════════════════════
String firstLine(const String& s) {
    int nl = s.indexOf('\n');
    String l = (nl >= 0) ? s.substring(0, nl) : s;
    if (l.length() == 0) l = "(leere Notiz)";
    if (l.length() > 30) l = l.substring(0, 30) + "...";
    return l;
}
void drawNotesList() {
    tft.fillScreen(C_BG);
    drawTabs(0);
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
    if (scrollable) {
        int ax = SCR_W - 24;
        tft.fillTriangle(ax + 10, LIST_TOP + 4, ax + 2, LIST_TOP + 18, ax + 18, LIST_TOP + 18, C_ACCENT);
        int by = LIST_TOP + LIST_VIS * LIST_ITEM_H - 24;
        tft.fillTriangle(ax + 10, by + 18, ax + 2, by + 4, ax + 18, by + 4, C_ACCENT);
    }
}

// ══ NOTES EDIT ═══════════════════════════════════
void drawNotesEdit() {
    tft.fillRect(0, 0, SCR_W, KBD_Y, C_BG);
    tft.fillRect(0, 0, 160, TAB_H, C_TAB_ACT);
    tft.fillRect(160, 0, 160, TAB_H, C_RED);
    tft.setTextSize(2);
    tft.setTextColor(TFT_WHITE, C_TAB_ACT);
    tft.drawString("< Speichern", 14, 7);
    tft.setTextColor(TFT_WHITE, C_RED);
    tft.drawString("Loeschen", 184, 7);
    tft.drawLine(0, TAB_H, SCR_W, TAB_H, C_ACCENT);
    tft.drawRect(0, TAB_H, SCR_W - 1, KBD_Y - TAB_H, C_ACCENT);

    tft.setTextColor(TFT_WHITE, C_BG);
    tft.setTextSize(1);
    const int charPerLine = 52, lineH = 10;
    const int maxLines = (KBD_Y - TAB_H - 8) / lineH;
    const int textY = TAB_H + 4;
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
        tft.drawString(linesBuf[i], 4, textY + (i - start) * lineH);
    drawKeyboard();
}
void drawKeyboard() {
    for (int i = 0; i < 10; i++) {
        int kx = i * KEY_W, ky = KBD_Y;
        tft.fillRect(kx + 1, ky + 1, KEY_W - 2, KEY_H - 2, C_KEY);
        tft.setTextColor(TFT_WHITE, C_KEY); tft.setTextSize(1);
        tft.drawString(ROW0[i], kx + 11, ky + 12);
    }
    for (int i = 0; i < 10; i++) {
        int kx = i * KEY_W, ky = KBD_Y + KEY_H;
        uint16_t col = (i == 9) ? (uint16_t)C_KEY_SP : (uint16_t)C_KEY;
        tft.fillRect(kx + 1, ky + 1, KEY_W - 2, KEY_H - 2, col);
        tft.setTextColor(TFT_WHITE, col); tft.setTextSize(1);
        if (i == 9) tft.drawString("DEL", kx + 5, ky + 12);
        else        tft.drawString(ROW1[i], kx + 11, ky + 12);
    }
    for (int i = 0; i < 10; i++) {
        int kx = i * KEY_W, ky = KBD_Y + KEY_H * 2;
        uint16_t col = (i >= 7) ? (uint16_t)C_KEY_SP : (uint16_t)C_KEY;
        tft.fillRect(kx + 1, ky + 1, KEY_W - 2, KEY_H - 2, col);
        tft.setTextColor(TFT_WHITE, col); tft.setTextSize(1);
        int tx = (strlen(ROW2[i]) > 1) ? kx + 7 : kx + 11;
        tft.drawString(ROW2[i], tx, ky + 12);
    }
    int ky3 = KBD_Y + KEY_H * 3;
    tft.fillRect(1, ky3 + 1, KEY_W - 2, KEY_H - 2, C_KEY);
    tft.fillRect(KEY_W + 1, ky3 + 1, KEY_W - 2, KEY_H - 2, C_KEY);
    tft.fillRect(KEY_W * 2 + 1, ky3 + 1, 192 - 2, KEY_H - 2, C_KEY_SP);
    tft.fillRect(KEY_W * 2 + 193, ky3 + 1, 63, KEY_H - 2, C_ACCENT);
    tft.setTextColor(TFT_WHITE, C_KEY); tft.setTextSize(1);
    tft.drawString(".", 11, ky3 + 12);
    tft.drawString(",", KEY_W + 11, ky3 + 12);
    tft.drawString("LEER", KEY_W * 2 + 76, ky3 + 12);
    tft.setTextColor(C_BG, C_ACCENT);
    tft.drawString("NL", KEY_W * 2 + 213, ky3 + 12);
}

// ══ QUIZ ════════════════════════════════════════
void drawQuiz() {
    tft.fillScreen(C_BG);
    drawTabs(1);

    if (qFinished) {
        tft.setTextSize(2);
        tft.setTextColor(C_ACCENT);
        tft.drawString("Quiz beendet!", 70, 60);
        tft.setTextColor(TFT_WHITE);
        tft.setTextSize(3);
        tft.drawString(String(qScore) + " / " + String(QN), 110, 100);
        tft.fillRoundRect(90, 160, 140, 44, 6, C_ACCENT);
        tft.setTextSize(2);
        tft.setTextColor(C_BG, C_ACCENT);
        tft.drawString("Nochmal", 112, 172);
        return;
    }

    tft.setTextSize(1);
    tft.setTextColor(C_DGREY);
    tft.drawString("Frage " + String(qIdx + 1) + " / " + String(QN) +
                   "     Punkte: " + String(qScore), 8, 36);
    tft.setTextSize(2);
    tft.setTextColor(TFT_WHITE);
    drawWrapped(qQ[qIdx], 8, 50, 25, 17);

    for (int i = 0; i < 4; i++) {
        int y = OPT_Y0 + i * OPT_STEP;
        uint16_t bg = C_CARD;
        if (qAnswered) {
            if (i == qCorrect[qIdx]) bg = C_GREEN;
            else if (i == qPicked)   bg = C_RED;
        }
        tft.fillRoundRect(6, y, SCR_W - 12, OPT_H, 5, bg);
        tft.setTextSize(2);
        tft.setTextColor(TFT_WHITE, bg);
        char letter[4] = {(char)('A' + i), ' ', ' ', 0};
        tft.drawString(String(letter) + qO[qIdx][i], 16, y + 9);
    }
    if (qAnswered) {
        tft.setTextSize(1);
        tft.setTextColor(C_ACCENT);
        tft.drawString("Tippen fuer naechste Frage", 90, 232);
    }
}

// ══ SNAKE ═════════════════════════════════════
void placeFood() {
    bool ok;
    do {
        ok = true;
        foodX = random(GCOLS);
        foodY = random(GROWS);
        for (int i = 0; i < snLen; i++)
            if (snX[i] == foodX && snY[i] == foodY) { ok = false; break; }
    } while (!ok);
}
void snCell(int cx, int cy, uint16_t col) {
    tft.fillRect(cx * CELL, GY0 + cy * CELL, CELL - 1, CELL - 1, col);
}
void drawSnakeHeader() {
    tft.fillRect(0, TAB_H + 1, SCR_W, GY0 - TAB_H - 1, C_BG);
    tft.setTextSize(1);
    tft.setTextColor(C_LGREY, C_BG);
    tft.drawString("Punkte: " + String(snScore) + "    Best: " + String(snBest), 8, 34);
}
void drawSnakeFull() {
    tft.fillScreen(C_BG);
    drawTabs(2);
    drawSnakeHeader();
    tft.drawRect(0, GY0 - 1, SCR_W, GROWS * CELL + 2, C_TAB);
    snCell(foodX, foodY, C_FOOD);
    for (int i = 0; i < snLen; i++)
        snCell(snX[i], snY[i], i == 0 ? (uint16_t)C_GOOD : (uint16_t)C_SNAKE);
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
    tft.fillRoundRect(40, 90, 240, 90, 8, C_TAB);
    tft.setTextSize(3);
    tft.setTextColor(C_BAD);
    tft.drawString("Game Over", 78, 104);
    tft.setTextSize(2);
    tft.setTextColor(TFT_WHITE);
    tft.drawString("Punkte: " + String(snScore), 100, 140);
    tft.setTextSize(1);
    tft.setTextColor(C_ACCENT);
    tft.drawString("Tippen zum Neustart", 100, 166);
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
        snScore++;
        placeFood();
        snCell(foodX, foodY, C_FOOD);
        drawSnakeHeader();
    } else {
        tft.fillRect(tailX * CELL, GY0 + tailY * CELL, CELL - 1, CELL - 1, C_BG);
    }
    snCell(snX[0], snY[0], C_GOOD);
    if (snLen > 1) snCell(snX[1], snY[1], C_SNAKE);
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
        if (y >= 34 && y < 56) { addNote(); switchScreen(SCR_NOTES_EDIT); return; }
        bool scrollable = noteCount > LIST_VIS;
        if (scrollable && x >= SCR_W - 26) {
            int mid = LIST_TOP + (LIST_VIS * LIST_ITEM_H) / 2;
            if (y < mid) listScroll--; else listScroll++;
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
            editIndex = -1;
            switchScreen(SCR_NOTES_LIST);
            return;
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
            if (x >= 90 && x <= 230 && y >= 160 && y <= 204) {
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
