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
         COL_FOOD, COL_SNAKE, COL_SNAKEHD, COL_DARK, COL_DARKER;
uint16_t QCAT_COLORS[5];
uint16_t QBTN_COLORS[4];
void setupPalette() {
    COL_BG      = tft.color565(13, 18, 38);
    COL_DARKER  = tft.color565(6, 8, 18);
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
    // Vivid colors for quiz categories + answer buttons ("viel farbenfroher")
    QCAT_COLORS[0] = tft.color565(33, 150, 243);  // blau   - 3D-Druck
    QCAT_COLORS[1] = tft.color565(255, 152, 0);   // orange - Technik
    QCAT_COLORS[2] = tft.color565(156, 39, 176);  // lila   - Allgemein
    QCAT_COLORS[3] = tft.color565(76, 175, 80);   // gruen  - Tiere
    QCAT_COLORS[4] = tft.color565(0, 191, 165);   // tuerkis- Wissen
    QBTN_COLORS[0] = tft.color565(41, 98, 255);
    QBTN_COLORS[1] = tft.color565(156, 39, 176);
    QBTN_COLORS[2] = tft.color565(233, 30, 99);
    QBTN_COLORS[3] = tft.color565(0, 150, 136);
}

// ── App state ──────────────────────────────────────
enum Screen { SCR_HOME, SCR_NOTES_LIST, SCR_NOTES_EDIT, SCR_QUIZ_SELECT, SCR_QUIZ,
              SCR_GAME_SELECT, SCR_SNAKE, SCR_FLAPPY, SCR_PICTURES };
Screen currentScreen = SCR_HOME;
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
bool   kbdExpanded = true;
#define LIST_TOP    66
#define LIST_ITEM_H 34
#define LIST_VIS    5

const char* ROW0[10] = {"Q","W","E","R","T","Z","U","I","O","P"};
const char* ROW1[10] = {"A","S","D","F","G","H","J","K","L","<"};
const char* ROW2[10] = {"Y","X","C","V","B","N","M","ae","oe","ue"};

// ── Quiz: 5 Kategorien x 5 Fragen ("fuenf Quizze") ───────────
#define QCATS 5
#define QN 5
const char* QCAT_NAMES[QCATS] = {"3D-Druck", "Technik", "Allgemein", "Tiere", "Wissen"};

const char* qQ[QCATS][QN] = {
  { "Welches Material wird beim FDM-Druck am haeufigsten benutzt?",
    "Wofuer steht die Abkuerzung FDM?",
    "Wie gross ist eine typische Standard-Duese?",
    "Was ist ein 'Brim' beim 3D-Druck?",
    "Welche Duesentemperatur passt etwa fuer PLA?" },
  { "Was misst man in Ohm?",
    "Wofuer steht WLAN?",
    "Welches Bauteil speichert Daten dauerhaft?",
    "Was bedeutet CPU?",
    "Welche Einheit misst Stromstaerke?" },
  { "Wie viele Kontinente gibt es ueblicherweise?",
    "Welches ist das groesste Saeugetier der Welt?",
    "In welchem Land steht der Eiffelturm?",
    "Wie viele Tage hat ein Schaltjahr?",
    "Welcher Planet ist der Sonne am naechsten?" },
  { "Wie viele Beine hat eine Spinne?",
    "Welches Tier ist das schnellste Landtier?",
    "Was fressen Pandas hauptsaechlich?",
    "Wie nennt man eine Gruppe von Woelfen?",
    "Welches Tier kann seine Farbe wechseln?" },
  { "Welches Element hat das Symbol O?",
    "Was misst ein Thermometer?",
    "Wie viele Knochen hat ein Erwachsener etwa?",
    "Was ist H2O?",
    "Welche Kraft zieht Dinge zur Erde?" }
};
const char* qO[QCATS][QN][4] = {
  { {"PLA","Beton","Glas","Papier"},
    {"Fast Data Mode","Final Draft Model","Fused Deposition Mod.","Flex Druck Material"},
    {"4 mm","0.4 mm","40 mm","14 mm"},
    {"Rand fuer Haftung","Ein Druckfehler","Die Duese","Ein Filament-Typ"},
    {"60 C","1000 C","500 C","200 C"} },
  { {"Widerstand","Spannung","Leistung","Frequenz"},
    {"Wireless LAN","Wired Local Net","World Link Area","Web Local Access"},
    {"RAM","Cache","SSD","Register"},
    {"Central Processing Unit","Computer Power Unit","Central Power Use","Core Process Util."},
    {"Volt","Watt","Ampere","Ohm"} },
  { {"5","6","7","8"},
    {"Elefant","Blauwal","Giraffe","Nashorn"},
    {"Italien","Spanien","Frankreich","Deutschland"},
    {"364","365","366","367"},
    {"Merkur","Venus","Erde","Mars"} },
  { {"6","8","10","12"},
    {"Loewe","Gepard","Pferd","Strauss"},
    {"Fleisch","Fisch","Bambus","Beeren"},
    {"Rudel","Herde","Schwarm","Kolonie"},
    {"Frosch","Chamaeleon","Eidechse","Salamander"} },
  { {"Gold","Sauerstoff","Eisen","Silber"},
    {"Druck","Temperatur","Feuchtigkeit","Gewicht"},
    {"106","206","306","406"},
    {"Sauerstoff","Wasserstoff","Wasser","Salz"},
    {"Magnetismus","Reibung","Gravitation","Spannung"} }
};
const uint8_t qCorrect[QCATS][QN] = {
  {0,2,1,0,3}, {0,0,1,0,2}, {2,1,2,2,0}, {1,1,2,0,1}, {1,1,1,2,2}
};
int  qCat = 0, qIdx = 0, qScore = 0, qPicked = -1;
bool qAnswered = false, qFinished = false;

// "Portrait-Karte" fuer das Quiz innerhalb des Landscape-Screens
// (kein echtes Display-Rotieren -> keine zweite Touch-Kalibrierung noetig,
//  dadurch bleibt der Touch zu 100% so zuverlaessig wie vorher)
#define QCARD_X 60
#define QCARD_Y 4
#define QCARD_W 200
#define QCARD_H 232

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

// ── Flappy Bird ──────────────────────────────────
#define PIPE_W 26
#define PIPE_GAP 70
#define N_PIPES 3
struct Pipe { int x; int gapY; bool passed; };
Pipe pipes[N_PIPES];
float flBy, flVel;
int   flBx = 70, flScore = 0, flBest = 0;
bool  flOver = false;
unsigned long flLastMove = 0;
const int FL_SPEED = 30;
#define FL_TOP (GY0 + 2)
#define FL_BOT (SCR_H - 2)

// ── Forward declarations ──────────────────────────
void drawHomeIcon(int x, int y);
bool handleHomeTap(int x, int y);
void drawGear(int cx, int cy);
void drawHomeScreen();
void drawNotesList();
void drawNotesEdit();
void drawKeyboard();
void drawQuizSelect();
void drawQuiz();
void drawGameSelect();
void drawPictures();
void initSnake();
void drawSnakeFull();
void drawSnakeHeader();
void moveSnake();
void initFlappy();
void drawFlappyHeader();
void drawFlappyField();
void moveFlappy();
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
    kbdExpanded = true;
}
void deleteNote(int idx) {
    for (int i = idx; i < noteCount - 1; i++) { notes[i] = notes[i + 1]; saveNote(i); }
    noteCount--;
    saveCount();
}

// ── Touch mapping using calibration (UNVERAENDERT - das ist der Teil,
//    der den Touch zuverlaessig macht, daran wird nichts mehr angefasst) ──
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
    touch.setRotation(0);   // raw orientation; calibration handles the rest

    prefs.begin("cyd", false);
    loadNotes();
    snBest = prefs.getInt("snbest", 0);
    flBest = prefs.getInt("flbest", 0);
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
    tft.drawString("Offline  -  ohne WLAN", 92, 120);
    delay(900);

    if (!touchCal) runCalibration();

    switchScreen(SCR_HOME);
}

// ═══════════════════════════════════════════════
void loop() {
    if (currentScreen == SCR_SNAKE && !snOver &&
        millis() - snLastMove > (unsigned long)SN_SPEED) {
        snLastMove = millis();
        moveSnake();
    }
    if (currentScreen == SCR_FLAPPY && !flOver &&
        millis() - flLastMove > (unsigned long)FL_SPEED) {
        flLastMove = millis();
        moveFlappy();
    }
    if (touch.tirqTouched() && touch.touched()) {
        TS_Point p = touch.getPoint();
        int sx, sy;
        mapTouchPoint(p, sx, sy);
        if (millis() - lastTouchMs > 200UL) {
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

// ── Home icon (kleiner Button, ersetzt die alte 3er-Tableiste) ──
void drawHomeIcon(int x, int y) {
    tft.fillRoundRect(x, y, 28, 24, 6, COL_CARD);
    // simple house glyph
    tft.fillTriangle(x + 14, y + 3, x + 4, y + 12, x + 24, y + 12, COL_ACCENT);
    tft.fillRect(x + 7, y + 12, 14, 9, COL_ACCENT);
    tft.fillRect(x + 12, y + 15, 4, 6, COL_CARD);
}
bool handleHomeTap(int x, int y) {
    if (x < 32 && y < TAB_H) { switchScreen(SCR_HOME); return true; }
    return false;
}
void drawGear(int cx, int cy) {
    tft.fillCircle(cx, cy, 7, COL_ACCENT);
    tft.fillCircle(cx, cy, 3, COL_BG);
    tft.fillRect(cx - 1, cy - 10, 2, 4, COL_ACCENT);
    tft.fillRect(cx - 1, cy + 6, 2, 4, COL_ACCENT);
    tft.fillRect(cx - 10, cy - 1, 4, 2, COL_ACCENT);
    tft.fillRect(cx + 6, cy - 1, 4, 2, COL_ACCENT);
}

void switchScreen(Screen s) {
    currentScreen = s;
    redrawCurrent();
}
void redrawCurrent() {
    if      (currentScreen == SCR_HOME)        drawHomeScreen();
    else if (currentScreen == SCR_NOTES_LIST)   drawNotesList();
    else if (currentScreen == SCR_NOTES_EDIT)   drawNotesEdit();
    else if (currentScreen == SCR_QUIZ_SELECT)  drawQuizSelect();
    else if (currentScreen == SCR_QUIZ)         drawQuiz();
    else if (currentScreen == SCR_GAME_SELECT)  drawGameSelect();
    else if (currentScreen == SCR_PICTURES)     drawPictures();
    else if (currentScreen == SCR_SNAKE) { if (snLen == 0) initSnake(); else drawSnakeFull(); }
    else if (currentScreen == SCR_FLAPPY) drawFlappyHeader(); // Feld zeichnet moveFlappy/initFlappy
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

// ── kleine Icon-Glyphen fuer die Home-Kacheln ───────────
void iconGamepad(int cx, int cy) {
    tft.fillRoundRect(cx - 22, cy - 12, 44, 24, 8, COL_DARK);
    tft.fillCircle(cx - 10, cy, 5, COL_TEXT);
    tft.fillCircle(cx + 12, cy - 6, 4, COL_TEXT);
    tft.fillCircle(cx + 12, cy + 6, 4, COL_TEXT);
}
void iconQuiz(int cx, int cy) {
    tft.fillCircle(cx, cy, 18, COL_DARK);
    tft.setTextColor(COL_TEXT, COL_DARK); tft.setTextSize(2);
    tft.drawString("?", cx - 5, cy - 8);
}
void iconNotes(int cx, int cy) {
    tft.fillRoundRect(cx - 16, cy - 18, 32, 36, 4, COL_DARK);
    for (int i = 0; i < 3; i++)
        tft.drawFastHLine(cx - 10, cy - 8 + i * 8, 20, COL_TEXT);
}
void iconPic(int cx, int cy) {
    tft.fillRoundRect(cx - 18, cy - 14, 36, 28, 4, COL_DARK);
    tft.fillCircle(cx - 8, cy - 4, 4, tft.color565(255, 205, 60));
    tft.fillTriangle(cx - 15, cy + 9, cx - 1, cy - 3, cx + 13, cy + 9, tft.color565(60, 180, 90));
}

// ══ HOME ═════════════════════════════════════════
void drawHomeScreen() {
    tft.fillScreen(COL_BG);
    tft.setTextSize(2); tft.setTextColor(COL_ACCENT);
    tft.drawString("CYD Mini-Apps", 84, 6);
    drawGear(305, 15);

    struct Tile { int x, y, w, h; uint16_t col; const char* label; int icon; };
    Tile tiles[4] = {
        {5,   40, 150, 90, tft.color565(41, 98, 255),  "Spiele",   0},
        {165, 40, 150, 90, tft.color565(156, 39, 176), "Quiz",     1},
        {5,  140, 150, 90, tft.color565(0, 150, 136),  "Notizen",  2},
        {165,140, 150, 90, tft.color565(233, 30, 99),  "Bilder",   3},
    };
    for (int i = 0; i < 4; i++) {
        Tile& t = tiles[i];
        tft.fillRoundRect(t.x, t.y, t.w, t.h, 12, t.col);
        int cx = t.x + t.w / 2, cy = t.y + 32;
        if (t.icon == 0) iconGamepad(cx, cy);
        else if (t.icon == 1) iconQuiz(cx, cy);
        else if (t.icon == 2) iconNotes(cx, cy);
        else iconPic(cx, cy);
        tft.setTextSize(2); tft.setTextColor(COL_TEXT, t.col);
        int tw = strlen(t.label) * 12;
        tft.drawString(t.label, t.x + (t.w - tw) / 2, t.y + t.h - 26);
    }
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
    drawHomeIcon(2, 3);
    tft.fillRoundRect(36, 3, SCR_W - 42, 24, 8, COL_ACCENT2);
    tft.setTextSize(2);
    tft.setTextColor(COL_DARK, COL_ACCENT2);
    tft.drawString("+ Neue Notiz", 110, 9);

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

// ══ NOTES EDIT (mit ein-/ausklappbarer Tastatur) ════════
// Tasten "lassen viel Platz" -> Tastatur ist jetzt per Knopf einklappbar,
// dann nutzt der Textbereich fast den ganzen Bildschirm.
void drawNotesEdit() {
    tft.fillScreen(COL_BG);
    drawHomeIcon(2, 3);
    tft.fillRoundRect(32, 3, 92, 24, 8, COL_ACCENT);
    tft.fillRoundRect(126, 3, 92, 24, 8, COL_BAD);
    tft.fillRoundRect(220, 3, 98, 24, 8, kbdExpanded ? COL_ACCENT2 : COL_CARD2);
    tft.setTextSize(1);
    tft.setTextColor(COL_DARK, COL_ACCENT);
    tft.drawString("Speichern", 44, 11);
    tft.setTextColor(COL_TEXT, COL_BAD);
    tft.drawString("Loeschen", 140, 11);
    tft.setTextColor(COL_TEXT, kbdExpanded ? COL_ACCENT2 : COL_CARD2);
    tft.drawString(kbdExpanded ? "Tastatur ^" : "Tastatur v", 230, 11);

    int textTop = TAB_H + 2;
    int textBottom = kbdExpanded ? KBD_Y : (SCR_H - 2);
    tft.drawRoundRect(2, textTop, SCR_W - 4, textBottom - textTop, 6, COL_CARD2);

    tft.setTextColor(COL_TEXT, COL_BG);
    tft.setTextSize(1);
    const int charPerLine = 52, lineH = 11;
    const int maxLines = (textBottom - textTop - 8) / lineH;
    const int textY = textTop + 6;
    String linesBuf[20]; int lineCount = 0; String cur = "";
    String display = (editIndex >= 0 ? notes[editIndex] : "") + "_";
    for (int i = 0; i < (int)display.length() && lineCount < 19; i++) {
        char c = display[i];
        if (c == '\n') { linesBuf[lineCount++] = cur; cur = ""; }
        else if ((int)cur.length() >= charPerLine) { linesBuf[lineCount++] = cur; cur = String(c); }
        else cur += c;
    }
    linesBuf[lineCount++] = cur;
    int start = (lineCount > maxLines) ? lineCount - maxLines : 0;
    for (int i = start; i < lineCount && i < lineCount; i++)
        tft.drawString(linesBuf[i], 8, textY + (i - start) * lineH);

    if (kbdExpanded) drawKeyboard();
    else {
        tft.setTextColor(COL_MUTE);
        tft.drawString("Tippen im Feld oder 'Tastatur' oeffnet die Tasten", 8, SCR_H - 14);
    }
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

// ── kleine Badge-Icons fuer die 5 Quiz-Kategorien ("fuenf Bilder") ──
void drawCatBadge(int x, int y, int cat) {
    tft.fillCircle(x, y, 13, tft.color565(255, 255, 255));
    tft.setTextColor(QCAT_COLORS[cat], tft.color565(255, 255, 255));
    tft.setTextSize(2);
    char l[2] = { QCAT_NAMES[cat][0], 0 };
    tft.drawString(l, x - 6, y - 8);
}

// ══ QUIZ-AUSWAHL (Portrait-Karte, 5 Quizze zur Auswahl) ══════
void drawQuizSelect() {
    tft.fillScreen(COL_DARKER);
    tft.fillRoundRect(QCARD_X, QCARD_Y, QCARD_W, QCARD_H, 14, COL_BG);
    tft.drawRoundRect(QCARD_X, QCARD_Y, QCARD_W, QCARD_H, 14, COL_ACCENT);
    drawHomeIcon(QCARD_X + 4, QCARD_Y + 4);
    tft.setTextSize(1); tft.setTextColor(COL_TEXT);
    tft.drawString("Quiz waehlen", QCARD_X + 70, QCARD_Y + 12);

    for (int i = 0; i < QCATS; i++) {
        int y = QCARD_Y + 40 + i * 34;
        tft.fillRoundRect(QCARD_X + 10, y, QCARD_W - 20, 30, 8, QCAT_COLORS[i]);
        drawCatBadge(QCARD_X + 28, y + 15, i);
        tft.setTextSize(2);
        tft.setTextColor(COL_TEXT, QCAT_COLORS[i]);
        tft.drawString(QCAT_NAMES[i], QCARD_X + 48, y + 7);
    }
    tft.setTextSize(1); tft.setTextColor(COL_MUTE);
    tft.drawString("Kategorie antippen", QCARD_X + 48, QCARD_Y + 218);
}

// ══ QUIZ (Portrait-Karte, bunte Buttons) ═════════════════════
void drawQuiz() {
    tft.fillScreen(COL_DARKER);
    tft.fillRoundRect(QCARD_X, QCARD_Y, QCARD_W, QCARD_H, 14, COL_BG);
    tft.drawRoundRect(QCARD_X, QCARD_Y, QCARD_W, QCARD_H, 14, QCAT_COLORS[qCat]);
    drawHomeIcon(QCARD_X + 4, QCARD_Y + 4);

    if (qFinished) {
        tft.setTextSize(2); tft.setTextColor(QCAT_COLORS[qCat]);
        tft.drawString("Fertig!", QCARD_X + 60, QCARD_Y + 50);
        tft.setTextSize(1); tft.setTextColor(COL_MUTE);
        tft.drawString(QCAT_NAMES[qCat], QCARD_X + 60, QCARD_Y + 76);
        tft.setTextSize(3); tft.setTextColor(COL_TEXT);
        tft.drawString(String(qScore) + " / " + String(QN), QCARD_X + 58, QCARD_Y + 96);
        tft.fillRoundRect(QCARD_X + 20, QCARD_Y + 150, 160, 34, 8, QCAT_COLORS[qCat]);
        tft.setTextSize(2); tft.setTextColor(COL_TEXT, QCAT_COLORS[qCat]);
        tft.drawString("Nochmal", QCARD_X + 50, QCARD_Y + 158);
        tft.fillRoundRect(QCARD_X + 20, QCARD_Y + 190, 160, 34, 8, COL_CARD2);
        tft.setTextSize(2); tft.setTextColor(COL_TEXT, COL_CARD2);
        tft.drawString("Kategorie", QCARD_X + 44, QCARD_Y + 198);
        return;
    }

    tft.fillRoundRect(QCARD_X + 10, QCARD_Y + 32, QCARD_W - 20, 18, 4, QCAT_COLORS[qCat]);
    tft.setTextSize(1); tft.setTextColor(COL_TEXT, QCAT_COLORS[qCat]);
    tft.drawString(String(QCAT_NAMES[qCat]) + " " + String(qIdx + 1) + "/" + String(QN) +
                   "  Pkt:" + String(qScore), QCARD_X + 16, QCARD_Y + 37);

    tft.setTextSize(1); tft.setTextColor(COL_TEXT);
    drawWrapped(qQ[qCat][qIdx], QCARD_X + 12, QCARD_Y + 56, 30, 11);

    for (int i = 0; i < 4; i++) {
        int y = QCARD_Y + 96 + i * 31;
        uint16_t bg = QBTN_COLORS[i];
        if (qAnswered) {
            if (i == qCorrect[qCat][qIdx]) bg = COL_GOOD;
            else if (i == qPicked)         bg = COL_BAD;
        }
        tft.fillRoundRect(QCARD_X + 10, y, QCARD_W - 20, 28, 7, bg);
        tft.fillRoundRect(QCARD_X + 16, y + 4, 20, 20, 5, COL_DARK);
        tft.setTextSize(1);
        tft.setTextColor(QCAT_COLORS[qCat], COL_DARK);
        char letter[2] = {(char)('A' + i), 0};
        tft.drawString(letter, QCARD_X + 22, y + 10);
        tft.setTextColor(COL_TEXT, bg);
        tft.drawString(qO[qCat][qIdx][i], QCARD_X + 42, y + 10);
    }
    if (qAnswered) {
        tft.setTextSize(1); tft.setTextColor(QCAT_COLORS[qCat]);
        tft.drawString("Tippen fuer naechste Frage", QCARD_X + 16, QCARD_Y + 222);
    }
}

// ── Mini-Icons fuer die Spiele-Auswahl ──
void iconSnakeMini(int cx, int cy) {
    tft.fillRoundRect(cx - 18, cy - 5, 10, 10, 2, COL_SNAKEHD);
    tft.fillRoundRect(cx - 6, cy - 5, 10, 10, 2, COL_SNAKE);
    tft.fillRoundRect(cx + 6, cy - 5, 10, 10, 2, COL_SNAKE);
    tft.fillRoundRect(cx + 18, cy + 7, 10, 10, 2, COL_SNAKE);
}
void iconBirdMini(int cx, int cy) {
    tft.fillCircle(cx, cy, 14, tft.color565(255, 205, 60));
    tft.fillTriangle(cx + 10, cy, cx + 24, cy - 4, cx + 24, cy + 4, tft.color565(255, 120, 40));
    tft.fillCircle(cx + 6, cy - 6, 3, COL_DARK);
}

// ══ SPIELE-AUSWAHL ═══════════════════════════════
void drawGameSelect() {
    tft.fillScreen(COL_BG);
    drawHomeIcon(2, 3);
    tft.setTextSize(2); tft.setTextColor(COL_TEXT);
    tft.drawString("Spiele waehlen", 90, 6);

    tft.fillRoundRect(16, 50, 138, 150, 14, tft.color565(34, 150, 90));
    iconSnakeMini(85, 110);
    tft.setTextSize(2); tft.setTextColor(COL_TEXT, tft.color565(34, 150, 90));
    tft.drawString("Snake", 56, 160);
    tft.setTextSize(1);
    tft.drawString("Best: " + String(snBest), 62, 182);

    tft.fillRoundRect(166, 50, 138, 150, 14, tft.color565(33, 110, 200));
    iconBirdMini(235, 110);
    tft.setTextSize(2); tft.setTextColor(COL_TEXT, tft.color565(33, 110, 200));
    tft.drawString("Flappy Bird", 178, 160);
    tft.setTextSize(1);
    tft.drawString("Best: " + String(flBest), 212, 182);
}

// ══ BILDER (Platzhalter fuer das Katzenbild) ═════════════
void drawCatPlaceholder(int cx, int cy) {
    // Wird ersetzt, sobald das echte Foto als Bitmap eingebaut wird.
    tft.fillCircle(cx, cy, 34, tft.color565(235, 200, 150));
    tft.fillTriangle(cx - 30, cy - 16, cx - 14, cy - 38, cx - 6, cy - 14, tft.color565(235, 200, 150));
    tft.fillTriangle(cx + 30, cy - 16, cx + 14, cy - 38, cx + 6, cy - 14, tft.color565(235, 200, 150));
    tft.fillCircle(cx - 12, cy - 4, 3, COL_DARK);
    tft.fillCircle(cx + 12, cy - 4, 3, COL_DARK);
    tft.fillTriangle(cx - 3, cy + 6, cx + 3, cy + 6, cx, cy + 11, tft.color565(255, 140, 150));
    for (int i = -1; i <= 1; i++) {
        tft.drawLine(cx - 20, cy + 4 + i * 5, cx - 40, cy + 2 + i * 5, COL_MUTE);
        tft.drawLine(cx + 20, cy + 4 + i * 5, cx + 40, cy + 2 + i * 5, COL_MUTE);
    }
}
void drawPictures() {
    tft.fillScreen(COL_BG);
    drawHomeIcon(2, 3);
    tft.setTextSize(2); tft.setTextColor(COL_TEXT);
    tft.drawString("Bilder", 140, 6);

    tft.fillRoundRect(90, 46, 140, 140, 10, COL_CARD);
    tft.drawRoundRect(90, 46, 140, 140, 10, COL_ACCENT);
    drawCatPlaceholder(160, 116);

    tft.setTextSize(1); tft.setTextColor(COL_MUTE);
    tft.drawString("Eigenes Bild folgt bald!", 78, 200);
    tft.drawString("Schick es mir, dann baue ich es fest ein.", 30, 214);
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
    tft.fillRect(0, 0, SCR_W, GY0 - 4, COL_BG);
    drawHomeIcon(2, 3);
    tft.fillRoundRect(36, 6, 150, 18, 4, COL_CARD);
    tft.setTextSize(1); tft.setTextColor(COL_TEXT, COL_CARD);
    tft.drawString("Punkte " + String(snScore) + "   Best " + String(snBest), 42, 11);
}
void drawSnakeFull() {
    tft.fillScreen(COL_BG);
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

// ══ FLAPPY BIRD ═══════════════════════════════════
void flappyResetPipe(int i, int fromX) {
    pipes[i].x = fromX;
    pipes[i].gapY = random(FL_TOP + 50, FL_BOT - 50);
    pipes[i].passed = false;
}
void drawFlappyHeader() {
    tft.fillRect(0, 0, SCR_W, GY0 - 4, COL_BG);
    drawHomeIcon(2, 3);
    tft.fillRoundRect(36, 6, 150, 18, 4, COL_CARD);
    tft.setTextSize(1); tft.setTextColor(COL_TEXT, COL_CARD);
    tft.drawString("Punkte " + String(flScore) + "   Best " + String(flBest), 42, 11);
}
void drawFlappyField() {
    tft.fillRect(0, GY0, SCR_W, SCR_H - GY0, COL_BG);
    for (int i = 0; i < N_PIPES; i++) {
        int top = pipes[i].gapY - PIPE_GAP / 2;
        int bot = pipes[i].gapY + PIPE_GAP / 2;
        if (top > FL_TOP) tft.fillRoundRect(pipes[i].x, FL_TOP, PIPE_W, top - FL_TOP, 4, COL_ACCENT2);
        if (bot < FL_BOT) tft.fillRoundRect(pipes[i].x, bot, PIPE_W, FL_BOT - bot, 4, COL_ACCENT2);
    }
    tft.fillCircle(flBx, (int)flBy, 8, tft.color565(255, 205, 60));
    tft.fillCircle(flBx + 3, (int)flBy - 3, 2, COL_DARK);
}
void initFlappy() {
    flBy = (FL_TOP + FL_BOT) / 2; flVel = 0;
    flScore = 0; flOver = false;
    for (int i = 0; i < N_PIPES; i++) flappyResetPipe(i, SCR_W + i * 120);
    drawFlappyHeader();
    drawFlappyField();
}
void flappyGameOver() {
    flOver = true;
    if (flScore > flBest) { flBest = flScore; prefs.putInt("flbest", flBest); }
    tft.fillRoundRect(38, 86, 244, 96, 10, COL_CARD);
    tft.drawRoundRect(38, 86, 244, 96, 10, COL_BAD);
    tft.setTextSize(3); tft.setTextColor(COL_BAD);
    tft.drawString("Game Over", 76, 100);
    tft.setTextSize(2); tft.setTextColor(COL_TEXT);
    tft.drawString("Punkte: " + String(flScore), 98, 138);
    tft.setTextSize(1); tft.setTextColor(COL_ACCENT);
    tft.drawString("Tippen zum Neustart", 100, 164);
}
void moveFlappy() {
    flVel += 0.5f;
    flBy += flVel;
    if (flBy - 8 < FL_TOP || flBy + 8 > FL_BOT) { flappyGameOver(); return; }
    int maxX = -99999;
    for (int i = 0; i < N_PIPES; i++) if (pipes[i].x > maxX) maxX = pipes[i].x;
    for (int i = 0; i < N_PIPES; i++) {
        pipes[i].x -= 3;
        if (pipes[i].x + PIPE_W < 0) { flappyResetPipe(i, maxX + 120); continue; }
        if (!pipes[i].passed && pipes[i].x + PIPE_W < flBx) {
            pipes[i].passed = true; flScore++; drawFlappyHeader();
        }
        if (flBx + 8 > pipes[i].x && flBx - 8 < pipes[i].x + PIPE_W) {
            int top = pipes[i].gapY - PIPE_GAP / 2, bot = pipes[i].gapY + PIPE_GAP / 2;
            if (flBy - 8 < top || flBy + 8 > bot) { flappyGameOver(); return; }
        }
    }
    drawFlappyField();
}

// ── Touch router ──────────────────────────────────
void handleTouch(int x, int y) {
    if (currentScreen == SCR_HOME) {
        if (x >= 292 && y < TAB_H) { runCalibration(); redrawCurrent(); return; }
        if (y < 40 || (y > 130 && y < 140)) return;
        int col = (x < 160) ? 0 : 1;
        int row = (y < 130) ? 0 : 1;
        if (row == 0 && col == 0) switchScreen(SCR_GAME_SELECT);
        else if (row == 0 && col == 1) switchScreen(SCR_QUIZ_SELECT);
        else if (row == 1 && col == 0) switchScreen(SCR_NOTES_LIST);
        else switchScreen(SCR_PICTURES);
        return;
    }

    if (currentScreen == SCR_NOTES_LIST) {
        if (handleHomeTap(x, y)) return;
        if (y >= 3 && y < 27 && x >= 36) { addNote(); switchScreen(SCR_NOTES_EDIT); return; }
        bool scrollable = noteCount > LIST_VIS;
        if (scrollable && x >= SCR_W - 28) {
            if (y < LIST_TOP + 74) listScroll--; else listScroll++;
            drawNotesList(); return;
        }
        if (y >= LIST_TOP) {
            int v = (y - LIST_TOP) / LIST_ITEM_H, idx = listScroll + v;
            if (v < LIST_VIS && idx < noteCount) { editIndex = idx; kbdExpanded = true; switchScreen(SCR_NOTES_EDIT); }
        }
        return;
    }

    if (currentScreen == SCR_NOTES_EDIT) {
        if (y < TAB_H) {
            if (x < 32) { if (editIndex >= 0) saveNote(editIndex); editIndex = -1; switchScreen(SCR_HOME); return; }
            if (x < 124) { if (editIndex >= 0) saveNote(editIndex); editIndex = -1; switchScreen(SCR_NOTES_LIST); return; }
            if (x < 218) { if (editIndex >= 0) deleteNote(editIndex); editIndex = -1; switchScreen(SCR_NOTES_LIST); return; }
            kbdExpanded = !kbdExpanded; drawNotesEdit(); return;
        }
        if (!kbdExpanded) { kbdExpanded = true; drawNotesEdit(); return; }
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

    if (currentScreen == SCR_QUIZ_SELECT) {
        if (x >= QCARD_X + 4 && x <= QCARD_X + 32 && y >= QCARD_Y + 4 && y <= QCARD_Y + 28) {
            switchScreen(SCR_HOME); return;
        }
        if (y >= QCARD_Y + 40 && x >= QCARD_X + 10 && x <= QCARD_X + QCARD_W - 10) {
            int i = (y - (QCARD_Y + 40)) / 34;
            if (i >= 0 && i < QCATS && (y - (QCARD_Y + 40)) % 34 < 30) {
                qCat = i; qIdx = 0; qScore = 0; qPicked = -1; qAnswered = false; qFinished = false;
                switchScreen(SCR_QUIZ);
            }
        }
        return;
    }

    if (currentScreen == SCR_QUIZ) {
        if (x >= QCARD_X + 4 && x <= QCARD_X + 32 && y >= QCARD_Y + 4 && y <= QCARD_Y + 28) {
            switchScreen(SCR_HOME); return;
        }
        if (qFinished) {
            if (x >= QCARD_X + 20 && x <= QCARD_X + 180 && y >= QCARD_Y + 150 && y <= QCARD_Y + 184) {
                qIdx = 0; qScore = 0; qPicked = -1; qAnswered = false; qFinished = false;
                drawQuiz(); return;
            }
            if (x >= QCARD_X + 20 && x <= QCARD_X + 180 && y >= QCARD_Y + 190 && y <= QCARD_Y + 224) {
                switchScreen(SCR_QUIZ_SELECT); return;
            }
            return;
        }
        if (!qAnswered) {
            if (y >= QCARD_Y + 96) {
                int i = (y - (QCARD_Y + 96)) / 31;
                if (i >= 0 && i < 4 && (y - (QCARD_Y + 96)) % 31 < 28) {
                    qPicked = i; qAnswered = true;
                    if (i == qCorrect[qCat][qIdx]) qScore++;
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

    if (currentScreen == SCR_GAME_SELECT) {
        if (handleHomeTap(x, y)) return;
        if (x < 154 && y >= 50 && y <= 200) { initSnake(); switchScreen(SCR_SNAKE); return; }
        if (x >= 166 && y >= 50 && y <= 200) { initFlappy(); switchScreen(SCR_FLAPPY); return; }
        return;
    }

    if (currentScreen == SCR_PICTURES) {
        if (handleHomeTap(x, y)) return;
        return;
    }

    if (currentScreen == SCR_SNAKE) {
        if (handleHomeTap(x, y)) return;
        if (snOver) { initSnake(); return; }
        steerSnake(x, y);
        return;
    }

    if (currentScreen == SCR_FLAPPY) {
        if (handleHomeTap(x, y)) return;
        if (flOver) { initFlappy(); return; }
        flVel = -3.2f;
        return;
    }
}
