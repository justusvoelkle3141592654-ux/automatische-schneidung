#include <Arduino.h>
#include <TFT_eSPI.h>
#include <SPI.h>
#include <XPT2046_Touchscreen.h>
#include <Preferences.h>
#include <math.h>
#include <stdio.h>
#include "photos.h"
#include <WiFi.h>
#include <HTTPClient.h>
#include <WiFiClientSecure.h>
#include <ArduinoJson.h>
#include <TJpg_Decoder.h>

// Wird beim CI-Build per GitHub-Secret als Compiler-Flag gesetzt
// (siehe platformio.ini / build-and-deploy.yml). Lokal ohne Secret leer.
#ifndef API_FOOTBALL_KEY
#define API_FOOTBALL_KEY ""
#endif

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

// Voller Bildschirm-Sprite-Puffer fuer flackerfreies Zeichnen
// (wird nur dort benutzt, wo vorher pro Frame/Tastendruck der ganze
//  Bereich geloescht wurde -> Flappy Bird & Notizen-Editor)
TFT_eSprite spr = TFT_eSprite(&tft);
bool sprOK = false;

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

// Dunklere, zur Kategorie passende Hintergrundfarbe ableiten
// ("ein bisschen mehr Hintergrundfarbe" statt einfarbig dunkelgrau)
uint16_t tintDark(uint16_t c565) {
    uint8_t r = (c565 >> 11) & 0x1F, g = (c565 >> 5) & 0x3F, b = c565 & 0x1F;
    r = (uint8_t)(r * 0.22f); g = (uint8_t)(g * 0.22f); b = (uint8_t)(b * 0.22f);
    return (r << 11) | (g << 5) | b;
}

// ── App state ──────────────────────────────────────
enum Screen { SCR_HOME, SCR_NOTES_LIST, SCR_NOTES_EDIT, SCR_QUIZ_SELECT, SCR_QUIZ,
              SCR_GAME_SELECT, SCR_SNAKE, SCR_FLAPPY, SCR_PICTURES,
              SCR_CALC, SCR_STOPWATCH,
              SCR_SETTINGS, SCR_WIFI_SETUP,
              SCR_TTT_SELECT, SCR_TTT,
              SCR_NEWS_LIST, SCR_NEWS_DETAIL,
              SCR_WM_LIST, SCR_WM_DETAIL };
Screen currentScreen = SCR_HOME;
Screen settingsReturnScreen = SCR_HOME;
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

// ── Bilder-Galerie ──────────────────────────────
#define PIC_COUNT 6
const char* PIC_NAMES[PIC_COUNT] = { "Katze 1", "Katze 2", "Katze 3", "Katze 4", "Katze 5", "Katze 6" };
int picIndex = 0;

// ── Taschenrechner ──────────────────────────────
String calInput = "0";
double calFirst = 0;
char   calOp = 0;
bool   calNewEntry = true;
const char* CALC_LABELS[4][4] = {
    {"7","8","9","/"},
    {"4","5","6","*"},
    {"1","2","3","-"},
    {"C","0",".","+"}
};

// ── Stoppuhr ─────────────────────────────────────
unsigned long swElapsed = 0;
unsigned long swStartMs = 0;
bool swRunning = false;
unsigned long swLastDraw = 0;

// ── WLAN ─────────────────────────────────────────
String wifiSsid = "";
String wifiPass = "";
int    wifiEditField = 0;     // 0 = SSID-Feld, 1 = Passwort-Feld
bool   wifiShift     = false;
bool   wifiDigitMode = false;
String wifiStatusMsg = "";
const char* WKEY_LETTERS[3][10] = {
  {"q","w","e","r","t","z","u","i","o","p"},
  {"a","s","d","f","g","h","j","k","l","-"},
  {"y","x","c","v","b","n","m",".","_","@"}
};
const char* WKEY_DIGITS[3][10] = {
  {"1","2","3","4","5","6","7","8","9","0"},
  {"!","#","$","%","&","*","(",")","+","="},
  {"/",":",";","?","~","[","]","{","}","^"}
};
bool wifiIsConnected() { return WiFi.status() == WL_CONNECTED; }
bool wifiTryConnect(unsigned long timeoutMs) {
    if (wifiSsid.length() == 0) { wifiStatusMsg = "Keine SSID gespeichert"; return false; }
    WiFi.mode(WIFI_STA);
    WiFi.begin(wifiSsid.c_str(), wifiPass.c_str());
    unsigned long t0 = millis();
    while (WiFi.status() != WL_CONNECTED && millis() - t0 < timeoutMs) delay(200);
    bool ok = (WiFi.status() == WL_CONNECTED);
    wifiStatusMsg = ok ? "Verbunden: " + WiFi.localIP().toString() : "Verbindung fehlgeschlagen";
    return ok;
}
void wifiLoadCreds() {
    wifiSsid = prefs.getString("wssid", "");
    wifiPass = prefs.getString("wpass", "");
}
void wifiSaveCreds() {
    prefs.putString("wssid", wifiSsid);
    prefs.putString("wpass", wifiPass);
}
void drawWifiIcon(int x, int y) {
    uint16_t col = wifiIsConnected() ? COL_GOOD : COL_MUTE;
    for (int i = 0; i < 3; i++) {
        int r = 4 + i * 4;
        tft.drawCircleHelper(x, y + 4, r, 1, col);
        tft.drawCircleHelper(x, y + 4, r, 2, col);
    }
    tft.fillCircle(x, y + 4, 2, col);
}

// ── HTTP/JSON-Hilfsfunktion (HTTPS ohne Zertifikatspruefung -
//    fuer einen Hobby-Geraet ausreichend, da nur oeffentliche,
//    nicht-sensible Daten abgerufen werden) ──────────
bool httpGetJson(const String& url, DynamicJsonDocument& doc, const char* apiKeyHeader = nullptr) {
    if (!wifiIsConnected()) return false;
    WiFiClientSecure client;
    client.setInsecure();
    HTTPClient http;
    http.setTimeout(8000);
    if (!http.begin(client, url)) return false;
    if (apiKeyHeader && apiKeyHeader[0]) http.addHeader("x-apisports-key", apiKeyHeader);
    int code = http.GET();
    bool ok = false;
    if (code == 200) {
        DeserializationError err = deserializeJson(doc, http.getStream());
        ok = !err;
    }
    http.end();
    return ok;
}

// ── Nachrichten (RSS: ARD/Tagesschau, ZDF, WDR, Zeit Online) ──
#define NEWS_MAX 16
#define NEWS_SOURCES 4
struct NewsItem { String title; String desc; String imgUrl; String source; };
NewsItem newsItems[NEWS_MAX];
int  newsCount  = 0;
int  newsScroll = 0;
int  newsSel    = -1;
bool newsLoading = false;
const char* NEWS_SOURCE_NAMES[NEWS_SOURCES] = { "ARD", "ZDF", "WDR", "ZEIT" };
const char* NEWS_URLS[NEWS_SOURCES] = {
    "https://www.tagesschau.de/xml/rss2",
    "https://www.zdf.de/rss/zdf/nachrichten",
    "https://www.wdr.de/xml/newsticker.rdf",
    "https://newsfeed.zeit.de/index"
};

// ── WM 2026 Liveticker + Gewinn-Wahrscheinlichkeit ──────
#define WM_MAX 12
struct WMFixture {
    long   id;
    String home, away, dateStr, statusShort;
    int    goalsHome, goalsAway, elapsed;
    int    predHome, predDraw, predAway;   // -1 = noch nicht geladen
    bool   live;
};
WMFixture wmFixtures[WM_MAX];
int  wmCount   = 0;
int  wmSel     = -1;
int  wmScroll  = 0;
bool wmLoading = false;
bool wmIsLive  = false;   // true = echte Live-Spiele, false = naechste Spiele

// ── TicTacToe ────────────────────────────────────
int  tttBoard[9];
bool tttVsAI   = true;
int  tttTurn   = 1;        // 1 = X (Spieler), 2 = O (KI oder Spieler 2)
bool tttOver   = false;
int  tttWinner = 0;        // 0 = unentschieden/offen, 1 oder 2
int  tttWinLine[3] = {-1, -1, -1};
unsigned long tttAiMoveAt = 0;
bool tttAiPending = false;

// ── Forward declarations ──────────────────────────
void drawHomeIcon(TFT_eSPI& g, int x, int y);
bool handleHomeTap(int x, int y);
void drawGear(int cx, int cy);
void initHomeTiles();
void drawHomeScreen();
void drawNotesList();
void drawNotesEdit();
void drawKeyboard(TFT_eSPI& g);
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
void drawCalcScreen();
void drawStopwatchScreen();
void drawStopwatchTime();
void handleTouch(int x, int y);
void switchScreen(Screen s);
void redrawCurrent();
void runCalibration();
void drawSettings();
void settingsHandleTouch(int x, int y);
void drawWifiSetup();
void wifiHandleTouch(int x, int y);
void drawTttSelect();
void drawTtt();
void tttHandleTouch(int x, int y);
void tttStartGame();
bool tjpgOutputCb(int16_t x, int16_t y, uint16_t w, uint16_t h, uint16_t* bitmap);
void newsFetchAll();
void drawNewsList();
void drawNewsDetail();
void newsHandleTouch(int x, int y);
void wmFetchFixtures();
void wmFetchPrediction(int idx);
void drawWmList();
void drawWmDetail();
void wmHandleTouch(int x, int y);

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

    spr.setColorDepth(16);
    sprOK = (spr.createSprite(SCR_W, SCR_H) != nullptr);

    touchSPI.begin(TOUCH_CLK, TOUCH_MISO, TOUCH_MOSI, TOUCH_CS);
    touch.begin(touchSPI);
    touch.setRotation(0);   // raw orientation; calibration handles the rest

    prefs.begin("cyd", false);
    loadNotes();
    snBest = prefs.getInt("snbest", 0);
    flBest = prefs.getInt("flbest", 0);
    randomSeed(esp_random());
    initHomeTiles();
    wifiLoadCreds();

    TJpgDec.setJpgScale(1);
    TJpgDec.setSwapBytes(true);
    TJpgDec.setCallback(tjpgOutputCb);

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
    tft.drawString("Spiele, Quiz, Notizen, News, WM ...", 56, 120);
    delay(900);

    if (!touchCal) runCalibration();

    if (wifiSsid.length() > 0) {
        tft.fillScreen(COL_BG);
        tft.setTextSize(1); tft.setTextColor(COL_MUTE);
        tft.drawString("Verbinde mit WLAN...", 110, 116);
        wifiTryConnect(6000);
    }

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
    if (currentScreen == SCR_STOPWATCH && swRunning &&
        millis() - swLastDraw > 60UL) {
        swLastDraw = millis();
        drawStopwatchTime();
    }
    if (currentScreen == SCR_TTT && tttAiPending && millis() >= tttAiMoveAt) {
        tttAiPending = false;
        int mv = tttBestMove();
        if (mv >= 0) {
            tttBoard[mv] = 2;
            tttDrawCell(mv);
            tttFinishCheck();
            if (!tttOver) tttTurn = 1;
            drawTttStatus();
        }
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
// nimmt eine TFT_eSPI&, damit er sowohl direkt aufs Display als auch
// in den Sprite-Puffer zeichnen kann (fuer flackerfreie Screens)
void drawHomeIcon(TFT_eSPI& g, int x, int y) {
    g.fillRoundRect(x, y, 28, 24, 6, COL_CARD);
    // simple house glyph
    g.fillTriangle(x + 14, y + 3, x + 4, y + 12, x + 24, y + 12, COL_ACCENT);
    g.fillRect(x + 7, y + 12, 14, 9, COL_ACCENT);
    g.fillRect(x + 12, y + 15, 4, 6, COL_CARD);
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
    else if (currentScreen == SCR_CALC)         drawCalcScreen();
    else if (currentScreen == SCR_STOPWATCH)    drawStopwatchScreen();
    else if (currentScreen == SCR_SETTINGS)     drawSettings();
    else if (currentScreen == SCR_WIFI_SETUP)   drawWifiSetup();
    else if (currentScreen == SCR_TTT_SELECT)   drawTttSelect();
    else if (currentScreen == SCR_TTT)          drawTtt();
    else if (currentScreen == SCR_NEWS_LIST)    drawNewsList();
    else if (currentScreen == SCR_NEWS_DETAIL)  drawNewsDetail();
    else if (currentScreen == SCR_WM_LIST)      drawWmList();
    else if (currentScreen == SCR_WM_DETAIL)    drawWmDetail();
    else if (currentScreen == SCR_SNAKE) { if (snLen == 0) initSnake(); else drawSnakeFull(); }
    else if (currentScreen == SCR_FLAPPY) {
        drawFlappyHeader();
        drawFlappyField();
        if (sprOK) spr.pushSprite(0, 0);
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

// ── kleine Icon-Glyphen fuer die Home-Kacheln ───────────
void iconGamepad(int cx, int cy) {
    tft.fillRoundRect(cx - 22, cy - 12, 44, 24, 8, COL_DARK);
    tft.fillCircle(cx - 10, cy, 5, COL_TEXT);
    tft.fillCircle(cx + 12, cy - 6, 4, COL_TEXT);
    tft.fillCircle(cx + 12, cy + 6, 4, COL_TEXT);
}
void iconQuiz(int cx, int cy) {
    tft.fillCircle(cx, cy, 16, COL_DARK);
    tft.setTextColor(COL_TEXT, COL_DARK); tft.setTextSize(2);
    tft.drawString("?", cx - 5, cy - 8);
}
void iconNotes(int cx, int cy) {
    tft.fillRoundRect(cx - 14, cy - 16, 28, 32, 4, COL_DARK);
    for (int i = 0; i < 3; i++)
        tft.drawFastHLine(cx - 8, cy - 7 + i * 7, 16, COL_TEXT);
}
void iconPic(int cx, int cy) {
    tft.fillRoundRect(cx - 16, cy - 12, 32, 24, 4, COL_DARK);
    tft.fillCircle(cx - 7, cy - 3, 3, tft.color565(255, 205, 60));
    tft.fillTriangle(cx - 13, cy + 8, cx - 1, cy - 2, cx + 11, cy + 8, tft.color565(60, 180, 90));
}
void iconCalc(int cx, int cy) {
    tft.fillRoundRect(cx - 14, cy - 16, 28, 32, 4, COL_DARK);
    tft.fillRect(cx - 10, cy - 12, 20, 7, COL_TEXT);
    for (int r = 0; r < 3; r++)
        for (int c = 0; c < 3; c++)
            tft.fillCircle(cx - 8 + c * 8, cy + 1 + r * 6, 2, COL_TEXT);
}
void iconStopwatch(int cx, int cy) {
    tft.fillCircle(cx, cy + 2, 14, COL_DARK);
    tft.drawCircle(cx, cy + 2, 14, COL_TEXT);
    tft.drawLine(cx, cy + 2, cx, cy - 6, COL_TEXT);
    tft.drawLine(cx, cy + 2, cx + 6, cy + 4, COL_TEXT);
    tft.fillRect(cx - 3, cy - 16, 6, 4, COL_TEXT);
}
void iconNews(int cx, int cy) {
    tft.fillRoundRect(cx - 16, cy - 14, 32, 28, 4, COL_DARK);
    tft.fillRect(cx - 11, cy - 9, 12, 9, COL_TEXT);
    tft.drawFastHLine(cx - 11, cy + 3, 22, COL_TEXT);
    tft.drawFastHLine(cx - 11, cy + 8, 22, COL_TEXT);
}
void iconWM(int cx, int cy) {
    tft.fillCircle(cx, cy, 14, COL_DARK);
    tft.fillTriangle(cx, cy - 8, cx - 7, cy - 2, cx + 7, cy - 2, COL_TEXT);
    tft.fillTriangle(cx, cy + 8, cx - 7, cy + 2, cx + 7, cy + 2, COL_TEXT);
    tft.drawCircle(cx, cy, 14, COL_TEXT);
}
void iconTTT(int cx, int cy) {
    tft.drawFastVLine(cx - 6, cy - 14, 28, COL_TEXT);
    tft.drawFastVLine(cx + 6, cy - 14, 28, COL_TEXT);
    tft.drawFastHLine(cx - 14, cy - 6, 28, COL_TEXT);
    tft.drawFastHLine(cx - 14, cy + 6, 28, COL_TEXT);
    tft.drawLine(cx - 11, cy - 11, cx - 3, cy - 3, COL_ACCENT);
    tft.drawLine(cx - 3, cy - 11, cx - 11, cy - 3, COL_ACCENT);
    tft.drawCircle(cx + 9, cy + 0, 5, COL_ACCENT2);
}

// ══ HOME (3x3 Raster) ═════════════════════════════
struct HomeTile { int x, y, w, h; uint16_t col; const char* label; int icon; Screen target; };
#define HOME_TILE_COUNT 9
HomeTile homeTiles[HOME_TILE_COUNT];
void initHomeTiles() {
    int x0 = 4, x1 = 108, x2 = 212, w = 104, h = 64;
    int y0 = 32, y1 = 99, y2 = 166;
    homeTiles[0] = {x0, y0, w, h, tft.color565(41, 98, 255),  "Spiele",  0, SCR_GAME_SELECT};
    homeTiles[1] = {x1, y0, w, h, tft.color565(156, 39, 176), "Quiz",    1, SCR_QUIZ_SELECT};
    homeTiles[2] = {x2, y0, w, h, tft.color565(0, 150, 136),  "Notizen", 2, SCR_NOTES_LIST};
    homeTiles[3] = {x0, y1, w, h, tft.color565(233, 30, 99),  "Bilder",  3, SCR_PICTURES};
    homeTiles[4] = {x1, y1, w, h, tft.color565(255, 152, 0),  "Rechner", 4, SCR_CALC};
    homeTiles[5] = {x2, y1, w, h, tft.color565(0, 191, 165),  "Stoppuhr",5, SCR_STOPWATCH};
    homeTiles[6] = {x0, y2, w, h, tft.color565(255, 87, 34),  "News",    6, SCR_NEWS_LIST};
    homeTiles[7] = {x1, y2, w, h, tft.color565(56, 142, 60),  "WM 2026", 7, SCR_WM_LIST};
    homeTiles[8] = {x2, y2, w, h, tft.color565(94, 53, 177),  "TicTacToe",8, SCR_TTT_SELECT};
}
void drawHomeScreen() {
    tft.fillScreen(COL_BG);
    tft.setTextSize(2); tft.setTextColor(COL_ACCENT);
    tft.drawString("CYD Mini-Apps", 84, 6);
    drawGear(305, 15);

    for (int i = 0; i < HOME_TILE_COUNT; i++) {
        HomeTile& t = homeTiles[i];
        tft.fillRoundRect(t.x, t.y, t.w, t.h, 10, t.col);
        int cx = t.x + t.w / 2, cy = t.y + 20;
        if      (t.icon == 0) iconGamepad(cx, cy);
        else if (t.icon == 1) iconQuiz(cx, cy);
        else if (t.icon == 2) iconNotes(cx, cy);
        else if (t.icon == 3) iconPic(cx, cy);
        else if (t.icon == 4) iconCalc(cx, cy);
        else if (t.icon == 5) iconStopwatch(cx, cy);
        else if (t.icon == 6) iconNews(cx, cy);
        else if (t.icon == 7) iconWM(cx, cy);
        else                  iconTTT(cx, cy);
        tft.setTextSize(1); tft.setTextColor(COL_TEXT, t.col);
        int tw = strlen(t.label) * 6;
        tft.drawString(t.label, t.x + (t.w - tw) / 2, t.y + t.h - 13);
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
    drawHomeIcon(tft, 2, 3);
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

// ══ NOTES EDIT (mit ein-/ausklappbarer Tastatur, flackerfrei) ════════
// Tasten "lassen viel Platz" -> Tastatur ist per Knopf einklappbar.
// Zeichnet komplett in den Sprite-Puffer und zeigt das Ergebnis erst
// am Ende in EINEM Schritt an (kein fillScreen direkt aufs Display
// mehr bei jedem Tastendruck -> kein Flackern).
void drawNotesEdit() {
    TFT_eSPI& g = sprOK ? (TFT_eSPI&)spr : tft;
    g.fillScreen(COL_BG);
    drawHomeIcon(g, 2, 3);
    g.fillRoundRect(32, 3, 92, 24, 8, COL_ACCENT);
    g.fillRoundRect(126, 3, 92, 24, 8, COL_BAD);
    g.fillRoundRect(220, 3, 98, 24, 8, kbdExpanded ? COL_ACCENT2 : COL_CARD2);
    g.setTextSize(1);
    g.setTextColor(COL_DARK, COL_ACCENT);
    g.drawString("Speichern", 44, 11);
    g.setTextColor(COL_TEXT, COL_BAD);
    g.drawString("Loeschen", 140, 11);
    g.setTextColor(COL_TEXT, kbdExpanded ? COL_ACCENT2 : COL_CARD2);
    g.drawString(kbdExpanded ? "Tastatur ^" : "Tastatur v", 230, 11);

    int textTop = TAB_H + 2;
    int textBottom = kbdExpanded ? KBD_Y : (SCR_H - 2);
    g.drawRoundRect(2, textTop, SCR_W - 4, textBottom - textTop, 6, COL_CARD2);

    g.setTextColor(COL_TEXT, COL_BG);
    g.setTextSize(1);
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
        g.drawString(linesBuf[i], 8, textY + (i - start) * lineH);

    if (kbdExpanded) drawKeyboard(g);
    else {
        g.setTextColor(COL_MUTE);
        g.drawString("Tippen im Feld oder 'Tastatur' oeffnet die Tasten", 8, SCR_H - 14);
    }
    if (sprOK) spr.pushSprite(0, 0);
}
void drawKeyboard(TFT_eSPI& g) {
    for (int i = 0; i < 10; i++) {
        int kx = i * KEY_W, ky = KBD_Y;
        g.fillRoundRect(kx + 1, ky + 1, KEY_W - 2, KEY_H - 2, 4, COL_KEY);
        g.setTextColor(COL_TEXT, COL_KEY); g.setTextSize(1);
        g.drawString(ROW0[i], kx + 11, ky + 12);
    }
    for (int i = 0; i < 10; i++) {
        int kx = i * KEY_W, ky = KBD_Y + KEY_H;
        uint16_t col = (i == 9) ? COL_ACCENT2 : COL_KEY;
        g.fillRoundRect(kx + 1, ky + 1, KEY_W - 2, KEY_H - 2, 4, col);
        g.setTextColor(COL_TEXT, col); g.setTextSize(1);
        if (i == 9) g.drawString("DEL", kx + 5, ky + 12);
        else        g.drawString(ROW1[i], kx + 11, ky + 12);
    }
    for (int i = 0; i < 10; i++) {
        int kx = i * KEY_W, ky = KBD_Y + KEY_H * 2;
        uint16_t col = (i >= 7) ? COL_KEYSP : COL_KEY;
        g.fillRoundRect(kx + 1, ky + 1, KEY_W - 2, KEY_H - 2, 4, col);
        g.setTextColor(COL_TEXT, col); g.setTextSize(1);
        int tx = (strlen(ROW2[i]) > 1) ? kx + 7 : kx + 11;
        g.drawString(ROW2[i], tx, ky + 12);
    }
    int ky3 = KBD_Y + KEY_H * 3;
    g.fillRoundRect(1, ky3 + 1, KEY_W - 2, KEY_H - 2, 4, COL_KEY);
    g.fillRoundRect(KEY_W + 1, ky3 + 1, KEY_W - 2, KEY_H - 2, 4, COL_KEY);
    g.fillRoundRect(KEY_W * 2 + 1, ky3 + 1, 192 - 2, KEY_H - 2, 4, COL_KEYSP);
    g.fillRoundRect(KEY_W * 2 + 193, ky3 + 1, 63, KEY_H - 2, 4, COL_ACCENT);
    g.setTextColor(COL_TEXT, COL_KEY); g.setTextSize(1);
    g.drawString(".", 11, ky3 + 12);
    g.drawString(",", KEY_W + 11, ky3 + 12);
    g.setTextColor(COL_TEXT, COL_KEYSP);
    g.drawString("LEER", KEY_W * 2 + 76, ky3 + 12);
    g.setTextColor(COL_DARK, COL_ACCENT);
    g.drawString("NL", KEY_W * 2 + 213, ky3 + 12);
}

// ── kleine Badge-Icons fuer die 5 Quiz-Kategorien ("fuenf Bilder") ──
void drawCatBadge(int x, int y, int cat) {
    tft.fillCircle(x, y, 13, tft.color565(255, 255, 255));
    tft.setTextColor(QCAT_COLORS[cat], tft.color565(255, 255, 255));
    tft.setTextSize(2);
    char l[2] = { QCAT_NAMES[cat][0], 0 };
    tft.drawString(l, x - 6, y - 8);
}

// ══ QUIZ-AUSWAHL (Portrait-Karte, mehr Hintergrundfarbe) ══════
void drawQuizSelect() {
    uint16_t bgTint = tintDark(QCAT_COLORS[0]);
    tft.fillScreen(bgTint);
    tft.fillRect(QCARD_X - 5, 0, 5, SCR_H, QCAT_COLORS[2]);
    tft.fillRect(QCARD_X + QCARD_W, 0, 5, SCR_H, QCAT_COLORS[3]);
    tft.fillRoundRect(QCARD_X, QCARD_Y, QCARD_W, QCARD_H, 14, COL_BG);
    tft.drawRoundRect(QCARD_X, QCARD_Y, QCARD_W, QCARD_H, 14, COL_ACCENT);
    drawHomeIcon(tft, QCARD_X + 4, QCARD_Y + 4);
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

// ══ QUIZ (Portrait-Karte, bunte Buttons, farbiger Rahmen) ═════════════
void drawQuiz() {
    uint16_t bgTint = tintDark(QCAT_COLORS[qCat]);
    tft.fillScreen(bgTint);
    tft.fillRect(QCARD_X - 5, 0, 5, SCR_H, QCAT_COLORS[qCat]);
    tft.fillRect(QCARD_X + QCARD_W, 0, 5, SCR_H, QCAT_COLORS[qCat]);
    tft.fillRoundRect(QCARD_X, QCARD_Y, QCARD_W, QCARD_H, 14, COL_BG);
    tft.drawRoundRect(QCARD_X, QCARD_Y, QCARD_W, QCARD_H, 14, QCAT_COLORS[qCat]);
    drawHomeIcon(tft, QCARD_X + 4, QCARD_Y + 4);

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
    drawHomeIcon(tft, 2, 3);
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

// ══ BILDER (navigierbare Galerie, 6 echte Fotos) ═════════════
void drawPictureArrow(bool left) {
    int x = left ? 16 : SCR_W - 38;
    int y = 96;
    tft.fillRoundRect(x, y, 22, 40, 6, COL_CARD);
    if (left) tft.fillTriangle(x + 16, y + 6, x + 16, y + 34, x + 5, y + 20, COL_ACCENT);
    else      tft.fillTriangle(x + 6, y + 6, x + 6, y + 34, x + 17, y + 20, COL_ACCENT);
}
void drawPictures() {
    tft.fillScreen(COL_BG);
    drawHomeIcon(tft, 2, 3);
    tft.setTextSize(2); tft.setTextColor(COL_TEXT);
    tft.drawString("Bilder", 140, 6);

    tft.fillRoundRect(90, 46, 140, 140, 10, COL_CARD);
    tft.drawRoundRect(90, 46, 140, 140, 10, COL_ACCENT);
    tft.setSwapBytes(true);
    tft.pushImage(94, 50, PHOTO_SIZE, PHOTO_SIZE, PHOTOS[picIndex]);
    tft.setSwapBytes(false);

    drawPictureArrow(true);
    drawPictureArrow(false);

    tft.setTextSize(1); tft.setTextColor(COL_MUTE);
    int tw = strlen(PIC_NAMES[picIndex]) * 6;
    tft.drawString(PIC_NAMES[picIndex], (SCR_W - tw) / 2, 192);
    tft.drawString(String(picIndex + 1) + " / " + String(PIC_COUNT), 142, 206);
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
    drawHomeIcon(tft, 2, 3);
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

// ══ FLAPPY BIRD (flackerfrei via Sprite, bunter Hintergrund) ═════════
void flappyResetPipe(int i, int fromX) {
    pipes[i].x = fromX;
    pipes[i].gapY = random(FL_TOP + 50, FL_BOT - 50);
    pipes[i].passed = false;
}
void drawFlappyHeader() {
    TFT_eSPI& g = sprOK ? (TFT_eSPI&)spr : tft;
    g.fillRect(0, 0, SCR_W, GY0 - 4, COL_BG);
    drawHomeIcon(g, 2, 3);
    g.fillRoundRect(36, 6, 150, 18, 4, COL_CARD);
    g.setTextSize(1); g.setTextColor(COL_TEXT, COL_CARD);
    g.drawString("Punkte " + String(flScore) + "   Best " + String(flBest), 42, 11);
}
// Bunter Himmel-Verlauf + Gras statt einfarbiger Flaeche ("mehr Farben/Hintergrund")
void drawFlappySky() {
    TFT_eSPI& g = sprOK ? (TFT_eSPI&)spr : tft;
    int bands = 8;
    int bandH = (FL_BOT - GY0) / bands;
    for (int b = 0; b < bands; b++) {
        uint8_t r = 70 + b * 4, gr = 150 + b * 8, bl = 220 - b * 6;
        if (gr > 255) gr = 255;
        g.fillRect(0, GY0 + b * bandH, SCR_W, bandH + 1, g.color565(r, gr, bl));
    }
    g.fillRect(0, FL_BOT - 16, SCR_W, 16, g.color565(139, 94, 52));
    g.fillRect(0, FL_BOT - 18, SCR_W, 4, g.color565(86, 196, 96));
}
void drawFlappyField() {
    TFT_eSPI& g = sprOK ? (TFT_eSPI&)spr : tft;
    drawFlappySky();
    for (int i = 0; i < N_PIPES; i++) {
        int top = pipes[i].gapY - PIPE_GAP / 2;
        int bot = pipes[i].gapY + PIPE_GAP / 2;
        uint16_t pipeCol = g.color565(60, 190, 90);
        uint16_t pipeCap  = g.color565(40, 150, 70);
        if (top > FL_TOP) {
            g.fillRoundRect(pipes[i].x, FL_TOP, PIPE_W, top - FL_TOP, 4, pipeCol);
            g.fillRoundRect(pipes[i].x - 2, top - 10, PIPE_W + 4, 10, 3, pipeCap);
        }
        if (bot < FL_BOT) {
            g.fillRoundRect(pipes[i].x, bot, PIPE_W, FL_BOT - bot, 4, pipeCol);
            g.fillRoundRect(pipes[i].x - 2, bot, PIPE_W + 4, 10, 3, pipeCap);
        }
    }
    g.fillCircle(flBx, (int)flBy, 9, g.color565(255, 205, 60));
    g.fillTriangle(flBx - 2, (int)flBy, flBx - 12, (int)flBy - 5, flBx - 12, (int)flBy + 5,
                    g.color565(255, 140, 40));
    g.fillCircle(flBx + 4, (int)flBy - 3, 2, COL_DARK);
}
void initFlappy() {
    flBy = (FL_TOP + FL_BOT) / 2; flVel = 0;
    flScore = 0; flOver = false;
    for (int i = 0; i < N_PIPES; i++) flappyResetPipe(i, SCR_W + i * 120);
    drawFlappyHeader();
    drawFlappyField();
    if (sprOK) spr.pushSprite(0, 0);
}
void flappyGameOver() {
    flOver = true;
    if (flScore > flBest) { flBest = flScore; prefs.putInt("flbest", flBest); }
    TFT_eSPI& g = sprOK ? (TFT_eSPI&)spr : tft;
    g.fillRoundRect(38, 86, 244, 96, 10, COL_CARD);
    g.drawRoundRect(38, 86, 244, 96, 10, COL_BAD);
    g.setTextSize(3); g.setTextColor(COL_BAD);
    g.drawString("Game Over", 76, 100);
    g.setTextSize(2); g.setTextColor(COL_TEXT);
    g.drawString("Punkte: " + String(flScore), 98, 138);
    g.setTextSize(1); g.setTextColor(COL_ACCENT);
    g.drawString("Tippen zum Neustart", 100, 164);
    if (sprOK) spr.pushSprite(0, 0);
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
    if (sprOK) spr.pushSprite(0, 0);
}

// ══ TASCHENRECHNER ════════════════════════════════
void calcClear() { calInput = "0"; calFirst = 0; calOp = 0; calNewEntry = true; }
void calcAppendDigit(char d) {
    if (calNewEntry) { calInput = String(d); calNewEntry = false; }
    else if (calInput == "0") calInput = String(d);
    else if (calInput.length() < 12) calInput += d;
}
void calcAppendDot() {
    if (calNewEntry) { calInput = "0."; calNewEntry = false; return; }
    if (calInput.indexOf('.') < 0 && calInput.length() < 11) calInput += ".";
}
String fmtCalc(double v) {
    if (v == (long long)v && fabs(v) < 1e12) return String((long long)v);
    String s = String(v, 6);
    while (s.endsWith("0")) s.remove(s.length() - 1);
    if (s.endsWith(".")) s.remove(s.length() - 1);
    return s;
}
void calcEvaluate() {
    double cur = calInput.toDouble();
    double res = cur;
    if (calOp == '+') res = calFirst + cur;
    else if (calOp == '-') res = calFirst - cur;
    else if (calOp == '*') res = calFirst * cur;
    else if (calOp == '/') res = (cur == 0) ? NAN : (calFirst / cur);
    calInput = isnan(res) ? "Fehler" : fmtCalc(res);
    calOp = 0;
    calNewEntry = true;
}
void calcSetOp(char op) {
    if (calOp != 0 && !calNewEntry) calcEvaluate();
    calFirst = calInput.toDouble();
    calOp = op;
    calNewEntry = true;
}
void drawCalcDisplay() {
    tft.fillRoundRect(10, 30, SCR_W - 20, 40, 8, COL_DARK);
    tft.setTextSize(3); tft.setTextColor(COL_TEXT, COL_DARK);
    String shown = calInput;
    if (shown.length() > 12) shown = shown.substring(shown.length() - 12);
    int tw = shown.length() * 18;
    tft.drawString(shown, SCR_W - 20 - tw, 42);
}
void drawCalcScreen() {
    tft.fillScreen(COL_DARKER);
    drawHomeIcon(tft, 2, 3);
    tft.setTextSize(2); tft.setTextColor(COL_TEXT);
    tft.drawString("Rechner", 120, 4);
    drawCalcDisplay();

    int gx = 10, gy = 78, bw = 73, bh = 38, gap = 4;
    for (int r = 0; r < 4; r++) {
        for (int c = 0; c < 4; c++) {
            int x = gx + c * (bw + gap), y = gy + r * (bh + gap);
            const char* lbl = CALC_LABELS[r][c];
            bool isOp = (c == 3) || (r == 3 && c == 0);
            uint16_t col = isOp ? tft.color565(255, 152, 0) : COL_CARD;
            tft.fillRoundRect(x, y, bw, bh, 8, col);
            tft.setTextSize(2); tft.setTextColor(COL_TEXT, col);
            int tw = strlen(lbl) * 12;
            tft.drawString(lbl, x + (bw - tw) / 2, y + 10);
        }
    }
}
void calcHandleTouch(int x, int y) {
    int gx = 10, gy = 78, bw = 73, bh = 38, gap = 4;
    if (y < gy) return;
    int r = (y - gy) / (bh + gap);
    int c = (x - gx) / (bw + gap);
    if (r < 0 || r > 3 || c < 0 || c > 3) return;
    if ((y - gy) % (bh + gap) > bh) return;
    if ((x - gx) % (bw + gap) > bw) return;
    const char* lbl = CALC_LABELS[r][c];
    if (r == 3 && c == 0) calcClear();
    else if (c == 3) calcSetOp(lbl[0]);
    else if (r == 3 && c == 2) calcAppendDot();
    else calcAppendDigit(lbl[0]);
    drawCalcScreen();
}

// ══ STOPPUHR ══════════════════════════════════════
String formatStopwatch(unsigned long ms) {
    unsigned long totalCs = ms / 10;
    unsigned long cs = totalCs % 100;
    unsigned long totalS = totalCs / 100;
    unsigned long s = totalS % 60;
    unsigned long m = totalS / 60;
    char buf[16];
    sprintf(buf, "%02lu:%02lu.%02lu", m, s, cs);
    return String(buf);
}
void drawStopwatchTime() {
    unsigned long now = swRunning ? swElapsed + (millis() - swStartMs) : swElapsed;
    tft.fillRoundRect(40, 70, SCR_W - 80, 50, 10, COL_DARK);
    tft.setTextSize(4); tft.setTextColor(COL_ACCENT, COL_DARK);
    String t = formatStopwatch(now);
    int tw = t.length() * 24;
    tft.drawString(t, (SCR_W - tw) / 2, 83);
}
void drawStopwatchScreen() {
    tft.fillScreen(COL_DARKER);
    drawHomeIcon(tft, 2, 3);
    tft.setTextSize(2); tft.setTextColor(COL_TEXT);
    tft.drawString("Stoppuhr", 110, 4);
    drawStopwatchTime();

    tft.fillRoundRect(40, 140, 110, 50, 10, swRunning ? COL_BAD : COL_GOOD);
    tft.setTextSize(2); tft.setTextColor(COL_TEXT, swRunning ? COL_BAD : COL_GOOD);
    tft.drawString(swRunning ? "Stop" : "Start", 62, 156);

    tft.fillRoundRect(170, 140, 110, 50, 10, COL_CARD2);
    tft.setTextSize(2); tft.setTextColor(COL_TEXT, COL_CARD2);
    tft.drawString("Reset", 196, 156);
}
void stopwatchHandleTouch(int x, int y) {
    if (y >= 140 && y <= 190) {
        if (x >= 40 && x <= 150) {
            if (swRunning) { swElapsed += millis() - swStartMs; swRunning = false; }
            else { swStartMs = millis(); swRunning = true; }
            drawStopwatchScreen();
        } else if (x >= 170 && x <= 280) {
            swRunning = false; swElapsed = 0;
            drawStopwatchScreen();
        }
    }
}

// ══ EINSTELLUNGEN (hinter dem Zahnrad-Symbol) ════════════
void drawSettings() {
    tft.fillScreen(COL_DARKER);
    drawHomeIcon(tft, 2, 3);
    tft.setTextSize(2); tft.setTextColor(COL_TEXT);
    tft.drawString("Einstellungen", 90, 4);

    tft.fillRoundRect(30, 50, 260, 56, 10, COL_CARD);
    tft.setTextSize(2); tft.setTextColor(COL_TEXT, COL_CARD);
    tft.drawString("Touch kalibrieren", 56, 68);

    tft.fillRoundRect(30, 118, 260, 56, 10, COL_ACCENT2);
    tft.setTextSize(2); tft.setTextColor(COL_DARK, COL_ACCENT2);
    tft.drawString("WLAN einrichten", 60, 136);

    drawWifiIcon(290, 184);
    tft.setTextSize(1); tft.setTextColor(wifiIsConnected() ? COL_GOOD : COL_MUTE);
    tft.drawString(wifiIsConnected() ? "WLAN verbunden" : "Kein WLAN", 70, 192);
}
void settingsHandleTouch(int x, int y) {
    if (handleHomeTap(x, y)) return;
    if (y >= 50 && y <= 106 && x >= 30 && x <= 290) { runCalibration(); switchScreen(SCR_HOME); return; }
    if (y >= 118 && y <= 174 && x >= 30 && x <= 290) { switchScreen(SCR_WIFI_SETUP); return; }
}

// ══ WLAN EINRICHTEN (eigene Tastatur mit Buchstaben/Ziffern) ══
void drawWifiKeyboard(TFT_eSPI& g) {
    const char* (*rows)[10] = wifiDigitMode ? WKEY_DIGITS : WKEY_LETTERS;
    for (int r = 0; r < 3; r++) {
        for (int c = 0; c < 10; c++) {
            int kx = c * KEY_W, ky = KBD_Y + r * KEY_H;
            bool isDel = (r == 1 && c == 9);
            uint16_t col = isDel ? COL_BAD : COL_KEY;
            g.fillRoundRect(kx + 1, ky + 1, KEY_W - 2, KEY_H - 2, 4, col);
            g.setTextColor(COL_TEXT, col); g.setTextSize(1);
            if (isDel) { g.drawString("DEL", kx + 5, ky + 12); continue; }
            String lbl = rows[r][c];
            if (!wifiDigitMode && wifiShift) lbl.toUpperCase();
            g.drawString(lbl, kx + 11, ky + 12);
        }
    }
    int ky3 = KBD_Y + KEY_H * 3;
    g.fillRoundRect(1, ky3 + 1, 62, KEY_H - 2, 4, wifiDigitMode ? COL_ACCENT2 : COL_KEYSP);
    g.setTextColor(COL_TEXT, wifiDigitMode ? COL_ACCENT2 : COL_KEYSP); g.setTextSize(1);
    g.drawString(wifiDigitMode ? "ABC" : "123", 14, ky3 + 12);

    g.fillRoundRect(65, ky3 + 1, 62, KEY_H - 2, 4, wifiShift ? COL_ACCENT2 : COL_KEYSP);
    g.setTextColor(COL_TEXT, wifiShift ? COL_ACCENT2 : COL_KEYSP);
    g.drawString("SHIFT", 72, ky3 + 12);

    g.fillRoundRect(129, ky3 + 1, 126, KEY_H - 2, 4, COL_KEYSP);
    g.setTextColor(COL_TEXT, COL_KEYSP);
    g.drawString("LEERTASTE", 150, ky3 + 12);

    g.fillRoundRect(257, ky3 + 1, 62, KEY_H - 2, 4, COL_GOOD);
    g.setTextColor(COL_DARK, COL_GOOD);
    g.drawString("OK", 275, ky3 + 12);
}
void drawWifiSetup() {
    TFT_eSPI& g = sprOK ? (TFT_eSPI&)spr : tft;
    g.fillScreen(COL_BG);
    drawHomeIcon(g, 2, 3);
    g.fillRoundRect(32, 3, 110, 24, 8, COL_GOOD);
    g.setTextSize(1); g.setTextColor(COL_DARK, COL_GOOD);
    g.drawString("Verbinden", 50, 11);

    g.fillRoundRect(8, 30, 152, 26, 6, wifiEditField == 0 ? COL_ACCENT2 : COL_CARD2);
    g.setTextColor(COL_TEXT, wifiEditField == 0 ? COL_ACCENT2 : COL_CARD2); g.setTextSize(1);
    String ssidShown = wifiSsid.length() ? wifiSsid : "(SSID eingeben)";
    g.drawString("SSID: " + ssidShown, 14, 40);

    g.fillRoundRect(166, 30, 146, 26, 6, wifiEditField == 1 ? COL_ACCENT2 : COL_CARD2);
    g.setTextColor(COL_TEXT, wifiEditField == 1 ? COL_ACCENT2 : COL_CARD2);
    String passMasked = "";
    for (unsigned int i = 0; i < wifiPass.length(); i++) passMasked += "*";
    g.drawString("Pass: " + (wifiPass.length() ? passMasked : String("(optional)")), 172, 40);

    g.setTextColor(COL_MUTE, COL_BG);
    g.drawString(wifiStatusMsg, 8, 60);

    drawWifiKeyboard(g);
    if (sprOK) spr.pushSprite(0, 0);
}
void wifiKeyTap(const String& ch) {
    String& field = (wifiEditField == 0) ? wifiSsid : wifiPass;
    if (field.length() < 32) field += ch;
}
void wifiHandleTouch(int x, int y) {
    if (y < TAB_H) {
        if (x < 32) { switchScreen(SCR_SETTINGS); return; }
        if (x < 142) {
            wifiSaveCreds();
            wifiStatusMsg = "Verbinde...";
            drawWifiSetup();
            bool ok = wifiTryConnect(10000);
            drawWifiSetup();
            if (ok) { delay(600); switchScreen(SCR_SETTINGS); }
            return;
        }
        return;
    }
    if (y >= 30 && y <= 56) {
        if (x < 160) wifiEditField = 0; else wifiEditField = 1;
        drawWifiSetup();
        return;
    }
    if (y < KBD_Y) return;
    int row = (y - KBD_Y) / KEY_H;
    if (row < 0 || row > 3) return;
    if (row < 3) {
        int col = constrain(x / KEY_W, 0, 9);
        if (row == 1 && col == 9) {
            String& field = (wifiEditField == 0) ? wifiSsid : wifiPass;
            if (field.length() > 0) field.remove(field.length() - 1);
            drawWifiSetup();
            return;
        }
        const char* (*rows)[10] = wifiDigitMode ? WKEY_DIGITS : WKEY_LETTERS;
        String lbl = rows[row][col];
        if (!wifiDigitMode && wifiShift) lbl.toUpperCase();
        wifiKeyTap(lbl);
        drawWifiSetup();
        return;
    }
    // row 3: mode toggle / shift / space / OK
    if (x < 64) { wifiDigitMode = !wifiDigitMode; drawWifiSetup(); return; }
    if (x < 128) { wifiShift = !wifiShift; drawWifiSetup(); return; }
    if (x < 256) { wifiKeyTap(" "); drawWifiSetup(); return; }
    wifiSaveCreds();
    wifiStatusMsg = "Verbinde...";
    drawWifiSetup();
    bool ok = wifiTryConnect(10000);
    drawWifiSetup();
    if (ok) { delay(600); switchScreen(SCR_SETTINGS); }
}

// ══ TICTACTOE ═════════════════════════════════════
#define TTT_X0 70
#define TTT_Y0 40
#define TTT_CELL 60
int tttCheckWinnerLine(int* b, int* line) {
    const int lines[8][3] = {{0,1,2},{3,4,5},{6,7,8},{0,3,6},{1,4,7},{2,5,8},{0,4,8},{2,4,6}};
    for (int i = 0; i < 8; i++) {
        const int* l = lines[i];
        if (b[l[0]] && b[l[0]] == b[l[1]] && b[l[1]] == b[l[2]]) {
            if (line) { line[0] = l[0]; line[1] = l[1]; line[2] = l[2]; }
            return b[l[0]];
        }
    }
    return 0;
}
bool tttBoardFull(int* b) {
    for (int i = 0; i < 9; i++) if (!b[i]) return false;
    return true;
}
int tttMinimax(int* b, int depth, bool maximizing) {
    int w = tttCheckWinnerLine(b, nullptr);
    if (w == 2) return 10 - depth;
    if (w == 1) return depth - 10;
    if (tttBoardFull(b)) return 0;
    if (maximizing) {
        int best = -999;
        for (int i = 0; i < 9; i++) if (!b[i]) {
            b[i] = 2; int v = tttMinimax(b, depth + 1, false); b[i] = 0;
            if (v > best) best = v;
        }
        return best;
    } else {
        int best = 999;
        for (int i = 0; i < 9; i++) if (!b[i]) {
            b[i] = 1; int v = tttMinimax(b, depth + 1, true); b[i] = 0;
            if (v < best) best = v;
        }
        return best;
    }
}
int tttBestMove() {
    int bestVal = -999, bestMove = -1;
    for (int i = 0; i < 9; i++) if (tttBoard[i] == 0) {
        tttBoard[i] = 2;
        int v = tttMinimax(tttBoard, 0, false);
        tttBoard[i] = 0;
        if (v > bestVal) { bestVal = v; bestMove = i; }
    }
    return bestMove;
}
void tttStartGame() {
    for (int i = 0; i < 9; i++) tttBoard[i] = 0;
    tttTurn = 1; tttOver = false; tttWinner = 0;
    tttWinLine[0] = tttWinLine[1] = tttWinLine[2] = -1;
    tttAiPending = false;
}
void drawTttSelect() {
    tft.fillScreen(COL_BG);
    drawHomeIcon(tft, 2, 3);
    tft.setTextSize(2); tft.setTextColor(COL_TEXT);
    tft.drawString("TicTacToe", 100, 6);

    tft.fillRoundRect(16, 50, 138, 150, 14, tft.color565(33, 110, 200));
    tft.setTextSize(2); tft.setTextColor(COL_TEXT, tft.color565(33, 110, 200));
    tft.drawString("Gegen", 60, 105);
    tft.drawString("die KI", 58, 130);

    tft.fillRoundRect(166, 50, 138, 150, 14, tft.color565(156, 39, 176));
    tft.setTextSize(2); tft.setTextColor(COL_TEXT, tft.color565(156, 39, 176));
    tft.drawString("2 Spieler", 184, 105);
    tft.drawString("lokal", 210, 130);
}
void tttDrawCell(int i) {
    int col = i % 3, row = i / 3;
    int cx = TTT_X0 + col * TTT_CELL, cy = TTT_Y0 + row * TTT_CELL;
    bool winning = (i == tttWinLine[0] || i == tttWinLine[1] || i == tttWinLine[2]);
    tft.fillRoundRect(cx + 3, cy + 3, TTT_CELL - 6, TTT_CELL - 6, 8,
                       winning ? COL_GOOD : COL_CARD);
    if (tttBoard[i] == 1) {
        uint16_t c = winning ? COL_DARK : COL_ACCENT;
        tft.drawLine(cx + 14, cy + 14, cx + TTT_CELL - 14, cy + TTT_CELL - 14, c);
        tft.drawLine(cx + 15, cy + 14, cx + TTT_CELL - 13, cy + TTT_CELL - 14, c);
        tft.drawLine(cx + TTT_CELL - 14, cy + 14, cx + 14, cy + TTT_CELL - 14, c);
        tft.drawLine(cx + TTT_CELL - 13, cy + 14, cx + 15, cy + TTT_CELL - 14, c);
    } else if (tttBoard[i] == 2) {
        uint16_t c = winning ? COL_DARK : COL_ACCENT2;
        tft.drawCircle(cx + TTT_CELL / 2, cy + TTT_CELL / 2, 16, c);
        tft.drawCircle(cx + TTT_CELL / 2 - 1, cy + TTT_CELL / 2, 16, c);
    }
}
void drawTttStatus() {
    tft.fillRect(0, 0, SCR_W, TAB_H, COL_BG);
    drawHomeIcon(tft, 2, 3);
    tft.setTextSize(1); tft.setTextColor(COL_TEXT);
    String msg;
    if (tttOver) {
        if (tttWinner == 0) msg = "Unentschieden!";
        else if (tttVsAI) msg = (tttWinner == 1) ? "Du gewinnst!" : "KI gewinnt!";
        else msg = (tttWinner == 1) ? "Spieler 1 gewinnt!" : "Spieler 2 gewinnt!";
        msg += "  (antippen = neu)";
    } else if (tttVsAI) {
        msg = (tttTurn == 1) ? "Du bist dran (X)" : "KI denkt...";
    } else {
        msg = (tttTurn == 1) ? "Spieler 1 (X) ist dran" : "Spieler 2 (O) ist dran";
    }
    tft.fillRoundRect(32, 3, SCR_W - 38, 24, 8, COL_CARD);
    tft.setTextColor(COL_TEXT, COL_CARD);
    tft.drawString(msg, 40, 11);
}
void drawTtt() {
    tft.fillScreen(COL_BG);
    drawTttStatus();
    for (int i = 0; i < 9; i++) tttDrawCell(i);
}
void tttFinishCheck() {
    int line[3];
    int w = tttCheckWinnerLine(tttBoard, line);
    if (w) {
        tttOver = true; tttWinner = w;
        tttWinLine[0] = line[0]; tttWinLine[1] = line[1]; tttWinLine[2] = line[2];
    } else if (tttBoardFull(tttBoard)) {
        tttOver = true; tttWinner = 0;
    }
}
void tttHandleTouch(int x, int y) {
    if (handleHomeTap(x, y)) return;
    if (tttOver) { tttStartGame(); drawTtt(); return; }
    if (tttVsAI && tttTurn == 2) return;   // KI ist dran, Touch ignorieren
    if (y < TTT_Y0 || y >= TTT_Y0 + TTT_CELL * 3 || x < TTT_X0 || x >= TTT_X0 + TTT_CELL * 3) return;
    int col = (x - TTT_X0) / TTT_CELL, row = (y - TTT_Y0) / TTT_CELL;
    int idx = row * 3 + col;
    if (tttBoard[idx] != 0) return;
    tttBoard[idx] = tttTurn;
    tttDrawCell(idx);
    tttFinishCheck();
    if (!tttOver) {
        tttTurn = (tttTurn == 1) ? 2 : 1;
        if (tttVsAI && tttTurn == 2) {
            tttAiPending = true;
            tttAiMoveAt = millis() + 450;
        }
    }
    drawTttStatus();
}

// ══ NACHRICHTEN (RSS: ARD/Tagesschau, ZDF, WDR, Zeit Online) ══
String xmlTagContent(const String& s, const String& tag) {
    String openTag = "<" + tag;
    int start = s.indexOf(openTag);
    if (start < 0) return "";
    int tagEnd = s.indexOf('>', start);
    if (tagEnd < 0) return "";
    String closeTag = "</" + tag + ">";
    int closeStart = s.indexOf(closeTag, tagEnd);
    if (closeStart < 0) return "";
    return s.substring(tagEnd + 1, closeStart);
}
String xmlAttr(const String& s, const String& tagStart, const String& attr) {
    int p = s.indexOf(tagStart);
    if (p < 0) return "";
    int tagClose = s.indexOf('>', p);
    if (tagClose < 0) tagClose = s.length();
    int attrPos = s.indexOf(attr + "=\"", p);
    if (attrPos < 0 || attrPos > tagClose) return "";
    int vs = attrPos + attr.length() + 2;
    int ve = s.indexOf('"', vs);
    if (ve < 0) return "";
    return s.substring(vs, ve);
}
String xmlClean(String s) {
    s.trim();
    if (s.startsWith("<![CDATA[")) {
        int end = s.indexOf("]]>");
        s = (end >= 0) ? s.substring(9, end) : s.substring(9);
    }
    s.replace("&amp;", "&"); s.replace("&quot;", "\""); s.replace("&#039;", "'");
    s.replace("&apos;", "'"); s.replace("&lt;", "<"); s.replace("&gt;", ">");
    s.trim();
    return s;
}
void newsFetchAll() {
    newsCount = 0; newsScroll = 0;
    if (!wifiIsConnected()) return;
    for (int s = 0; s < NEWS_SOURCES && newsCount < NEWS_MAX; s++) {
        WiFiClientSecure client; client.setInsecure();
        HTTPClient http;
        http.setTimeout(8000);
        if (!http.begin(client, NEWS_URLS[s])) continue;
        int code = http.GET();
        if (code == 200) {
            int contentLen = http.getSize();
            if (contentLen > 150000) { http.end(); continue; }
            String body = http.getString();
            int pos = 0, perSource = 0;
            while (perSource < 4 && newsCount < NEWS_MAX) {
                int itemStart = body.indexOf("<item", pos);
                if (itemStart < 0) break;
                int itemEnd = body.indexOf("</item>", itemStart);
                if (itemEnd < 0) break;
                String itemXml = body.substring(itemStart, itemEnd);
                pos = itemEnd + 7;
                String title = xmlClean(xmlTagContent(itemXml, "title"));
                if (title.length() == 0) continue;
                String desc = xmlClean(xmlTagContent(itemXml, "description"));
                String img = xmlAttr(itemXml, "<enclosure", "url");
                if (img.length() == 0) img = xmlAttr(itemXml, "<media:thumbnail", "url");
                if (img.length() == 0) img = xmlAttr(itemXml, "<media:content", "url");
                newsItems[newsCount].title  = title.length() > 110 ? title.substring(0, 110) : title;
                newsItems[newsCount].desc   = desc.length() > 280 ? desc.substring(0, 280) : desc;
                newsItems[newsCount].imgUrl = img;
                newsItems[newsCount].source = NEWS_SOURCE_NAMES[s];
                newsCount++; perSource++;
            }
        }
        http.end();
    }
}
#define NEWS_LIST_TOP 36
#define NEWS_ITEM_H   38
#define NEWS_VIS      5
void drawNewsList() {
    tft.fillScreen(COL_BG);
    drawHomeIcon(tft, 2, 3);
    tft.setTextSize(2); tft.setTextColor(COL_TEXT);
    tft.drawString("Nachrichten", 90, 4);

    if (!wifiIsConnected()) {
        tft.setTextSize(1); tft.setTextColor(COL_BAD);
        tft.drawString("Kein WLAN verbunden.", 18, 80);
        tft.setTextColor(COL_MUTE);
        tft.drawString("Einstellungen -> WLAN einrichten", 18, 98);
        return;
    }
    if (newsLoading) {
        tft.setTextSize(1); tft.setTextColor(COL_ACCENT);
        tft.drawString("Lade Nachrichten...", 18, 90);
        return;
    }
    if (newsCount == 0) {
        tft.setTextSize(1); tft.setTextColor(COL_MUTE);
        tft.drawString("Keine Nachrichten geladen.", 18, 80);
        tft.fillRoundRect(18, 100, 140, 30, 8, COL_ACCENT);
        tft.setTextColor(COL_DARK, COL_ACCENT);
        tft.drawString("Neu laden", 48, 108);
        return;
    }
    int maxScroll = newsCount > NEWS_VIS ? newsCount - NEWS_VIS : 0;
    if (newsScroll > maxScroll) newsScroll = maxScroll;
    if (newsScroll < 0) newsScroll = 0;
    bool scrollable = newsCount > NEWS_VIS;
    int itemW = scrollable ? SCR_W - 34 : SCR_W - 12;
    for (int v = 0; v < NEWS_VIS; v++) {
        int idx = newsScroll + v;
        if (idx >= newsCount) break;
        int y = NEWS_LIST_TOP + v * NEWS_ITEM_H;
        tft.fillRoundRect(6, y, itemW, NEWS_ITEM_H - 5, 6, COL_CARD);
        tft.fillRoundRect(6, y, 5, NEWS_ITEM_H - 5, 2, COL_ACCENT);
        tft.setTextSize(1); tft.setTextColor(COL_ACCENT, COL_CARD);
        tft.drawString(newsItems[idx].source, 14, y + 4);
        tft.setTextColor(COL_TEXT, COL_CARD);
        String t = newsItems[idx].title;
        if (t.length() > 44) t = t.substring(0, 44) + "...";
        tft.drawString(t, 14, y + 17);
    }
    if (scrollable) {
        int ax = SCR_W - 26;
        tft.fillRoundRect(ax, NEWS_LIST_TOP, 22, 78, 6, COL_CARD);
        tft.fillTriangle(ax + 11, NEWS_LIST_TOP + 8, ax + 4, NEWS_LIST_TOP + 24, ax + 18, NEWS_LIST_TOP + 24, COL_ACCENT);
        int by = NEWS_LIST_TOP + 86;
        tft.fillRoundRect(ax, by, 22, 78, 6, COL_CARD);
        tft.fillTriangle(ax + 11, by + 70, ax + 4, by + 54, ax + 18, by + 54, COL_ACCENT);
    }
}
// Direkter Empfang+Dekodierung eines JPEG-Thumbnails (nur fuer die
// Detailansicht, nicht fuer die Liste, um Speicher/Bandbreite zu sparen).
bool newsThumbReady = false;
bool tjpgOutputCb(int16_t x, int16_t y, uint16_t w, uint16_t h, uint16_t* bitmap) {
    tft.pushImage(x, y, w, h, bitmap);
    return true;
}
void newsDrawThumb(const String& url, int x, int y, int boxW, int boxH) {
    tft.fillRoundRect(x, y, boxW, boxH, 8, COL_CARD2);
    if (url.length() == 0 || !wifiIsConnected()) return;
    WiFiClientSecure client; client.setInsecure();
    HTTPClient http;
    http.setTimeout(8000);
    if (!http.begin(client, url)) return;
    int code = http.GET();
    if (code == 200) {
        int len = http.getSize();
        if (len > 0 && len < 60000) {
            uint8_t* buf = (uint8_t*)malloc(len);
            if (buf) {
                WiFiClient* stream = http.getStreamPtr();
                int got = stream->readBytes(buf, len);
                if (got == len) {
                    uint16_t jw, jh;
                    TJpgDec.getJpgSize(&jw, &jh, buf, len);
                    uint8_t scale = 1;
                    while ((jw / scale) > boxW || (jh / scale) > boxH) scale *= 2;
                    if (scale > 8) scale = 8;
                    TJpgDec.setJpgScale(scale);
                    TJpgDec.setCallback(tjpgOutputCb);
                    int dx = x + (boxW - (int)(jw / scale)) / 2;
                    int dy = y + (boxH - (int)(jh / scale)) / 2;
                    TJpgDec.drawJpg(dx, dy, buf, len);
                }
                free(buf);
            }
        }
    }
    http.end();
}
void drawNewsDetail() {
    if (newsSel < 0 || newsSel >= newsCount) { switchScreen(SCR_NEWS_LIST); return; }
    NewsItem& it = newsItems[newsSel];
    tft.fillScreen(COL_BG);
    drawHomeIcon(tft, 2, 3);
    tft.fillRoundRect(32, 3, 90, 24, 8, COL_CARD2);
    tft.setTextSize(1); tft.setTextColor(COL_TEXT, COL_CARD2);
    tft.drawString("Zur Liste", 48, 11);
    tft.setTextColor(COL_ACCENT, COL_BG);
    tft.drawString(it.source, 230, 11);

    newsDrawThumb(it.imgUrl, 8, 32, 80, 80);

    tft.setTextColor(COL_TEXT, COL_BG); tft.setTextSize(1);
    drawWrapped(it.title, 96, 34, 35, 12);

    tft.setTextColor(COL_MUTE, COL_BG);
    drawWrapped(it.desc, 8, 118, 52, 12);
}
void newsHandleTouch(int x, int y) {
    if (handleHomeTap(x, y)) return;
    if (currentScreen == SCR_NEWS_LIST) {
        if (!wifiIsConnected()) return;
        if (newsCount == 0 && !newsLoading) {
            newsLoading = true; drawNewsList();
            newsFetchAll();
            newsLoading = false; drawNewsList();
            return;
        }
        bool scrollable = newsCount > NEWS_VIS;
        if (scrollable && x >= SCR_W - 28) {
            if (y < NEWS_LIST_TOP + 82) newsScroll--; else newsScroll++;
            drawNewsList(); return;
        }
        if (y >= NEWS_LIST_TOP) {
            int v = (y - NEWS_LIST_TOP) / NEWS_ITEM_H, idx = newsScroll + v;
            if (v < NEWS_VIS && idx < newsCount) { newsSel = idx; switchScreen(SCR_NEWS_DETAIL); }
        }
        return;
    }
    if (currentScreen == SCR_NEWS_DETAIL) {
        if (y < TAB_H && x >= 32 && x < 122) { switchScreen(SCR_NEWS_LIST); return; }
        return;
    }
}

// ══ WM 2026 (Liveticker + Gewinn-Wahrscheinlichkeit, eine App) ══
void wmFetchFixtures() {
    wmCount = 0; wmSel = -1;
    if (!wifiIsConnected()) return;
    DynamicJsonDocument doc(16384);
    String url = "https://v3.football.api-sports.io/fixtures?league=1&season=2026&live=all";
    wmIsLive = false;
    if (httpGetJson(url, doc, API_FOOTBALL_KEY)) {
        JsonArray arr = doc["response"].as<JsonArray>();
        if (arr.size() > 0) wmIsLive = true;
        for (JsonObject o : arr) {
            if (wmCount >= WM_MAX) break;
            WMFixture& f = wmFixtures[wmCount];
            f.id          = o["fixture"]["id"] | 0L;
            f.home        = String((const char*)(o["teams"]["home"]["name"] | "?"));
            f.away        = String((const char*)(o["teams"]["away"]["name"] | "?"));
            f.goalsHome   = o["goals"]["home"] | 0;
            f.goalsAway   = o["goals"]["away"] | 0;
            f.elapsed     = o["fixture"]["status"]["elapsed"] | 0;
            f.statusShort = String((const char*)(o["fixture"]["status"]["short"] | "?"));
            f.dateStr     = String((const char*)(o["fixture"]["date"] | ""));
            f.predHome = f.predDraw = f.predAway = -1;
            f.live = true;
            wmCount++;
        }
    }
    if (wmCount == 0) {
        DynamicJsonDocument doc2(16384);
        String url2 = "https://v3.football.api-sports.io/fixtures?league=1&season=2026&next=8";
        if (httpGetJson(url2, doc2, API_FOOTBALL_KEY)) {
            JsonArray arr = doc2["response"].as<JsonArray>();
            for (JsonObject o : arr) {
                if (wmCount >= WM_MAX) break;
                WMFixture& f = wmFixtures[wmCount];
                f.id          = o["fixture"]["id"] | 0L;
                f.home        = String((const char*)(o["teams"]["home"]["name"] | "?"));
                f.away        = String((const char*)(o["teams"]["away"]["name"] | "?"));
                f.goalsHome   = 0; f.goalsAway = 0; f.elapsed = 0;
                f.statusShort = String((const char*)(o["fixture"]["status"]["short"] | "NS"));
                f.dateStr     = String((const char*)(o["fixture"]["date"] | ""));
                f.predHome = f.predDraw = f.predAway = -1;
                f.live = false;
                wmCount++;
            }
        }
    }
}
void wmFetchPrediction(int idx) {
    if (idx < 0 || idx >= wmCount || !wifiIsConnected()) return;
    DynamicJsonDocument doc(8192);
    String url = "https://v3.football.api-sports.io/predictions?fixture=" + String(wmFixtures[idx].id);
    if (httpGetJson(url, doc, API_FOOTBALL_KEY)) {
        JsonArray arr = doc["response"].as<JsonArray>();
        if (arr.size() > 0) {
            JsonObject pct = arr[0]["predictions"]["percent"];
            String h = String((const char*)(pct["home"] | "0%"));
            String d = String((const char*)(pct["draw"] | "0%"));
            String a = String((const char*)(pct["away"] | "0%"));
            wmFixtures[idx].predHome = h.toInt();
            wmFixtures[idx].predDraw = d.toInt();
            wmFixtures[idx].predAway = a.toInt();
        }
    }
}
String wmShortDate(const String& iso) {
    if (iso.length() < 16) return iso;
    return iso.substring(8, 10) + "." + iso.substring(5, 7) + " " + iso.substring(11, 16);
}
#define WM_LIST_TOP 36
#define WM_ITEM_H   42
#define WM_VIS      4
void drawWmList() {
    tft.fillScreen(COL_BG);
    drawHomeIcon(tft, 2, 3);
    tft.setTextSize(2); tft.setTextColor(COL_TEXT);
    tft.drawString("WM 2026", 100, 4);

    if (!wifiIsConnected()) {
        tft.setTextSize(1); tft.setTextColor(COL_BAD);
        tft.drawString("Kein WLAN verbunden.", 18, 80);
        tft.setTextColor(COL_MUTE);
        tft.drawString("Einstellungen -> WLAN einrichten", 18, 98);
        return;
    }
    if (wmLoading) {
        tft.setTextSize(1); tft.setTextColor(COL_ACCENT);
        tft.drawString("Lade Spiele...", 18, 90);
        return;
    }
    if (wmCount == 0) {
        tft.setTextSize(1); tft.setTextColor(COL_MUTE);
        tft.drawString("Keine Spiele gefunden.", 18, 76);
        tft.setTextColor(COL_MUTE);
        tft.drawString("(API-Key fehlt evtl. oder Limit erreicht)", 18, 92);
        tft.fillRoundRect(18, 110, 140, 30, 8, COL_ACCENT);
        tft.setTextColor(COL_DARK, COL_ACCENT);
        tft.drawString("Neu laden", 48, 118);
        return;
    }
    tft.setTextSize(1); tft.setTextColor(COL_MUTE);
    tft.drawString(wmIsLive ? "Live-Spiele" : "Naechste Spiele", 200, 8);

    int maxScroll = wmCount > WM_VIS ? wmCount - WM_VIS : 0;
    if (wmScroll > maxScroll) wmScroll = maxScroll;
    if (wmScroll < 0) wmScroll = 0;
    bool scrollable = wmCount > WM_VIS;
    int itemW = scrollable ? SCR_W - 34 : SCR_W - 12;
    for (int v = 0; v < WM_VIS; v++) {
        int idx = wmScroll + v;
        if (idx >= wmCount) break;
        WMFixture& f = wmFixtures[idx];
        int y = WM_LIST_TOP + v * WM_ITEM_H;
        tft.fillRoundRect(6, y, itemW, WM_ITEM_H - 5, 6, COL_CARD);
        tft.fillRoundRect(6, y, 5, WM_ITEM_H - 5, 2, f.live ? COL_GOOD : COL_ACCENT);
        tft.setTextSize(1); tft.setTextColor(COL_TEXT, COL_CARD);
        tft.drawString(f.home + " - " + f.away, 16, y + 5);
        if (f.live) {
            tft.setTextColor(COL_GOOD, COL_CARD);
            tft.drawString(String(f.goalsHome) + ":" + String(f.goalsAway) +
                            "  " + String(f.elapsed) + "'", 16, y + 20);
        } else {
            tft.setTextColor(COL_MUTE, COL_CARD);
            tft.drawString(wmShortDate(f.dateStr), 16, y + 20);
        }
    }
    if (scrollable) {
        int ax = SCR_W - 26;
        tft.fillRoundRect(ax, WM_LIST_TOP, 22, 78, 6, COL_CARD);
        tft.fillTriangle(ax + 11, WM_LIST_TOP + 8, ax + 4, WM_LIST_TOP + 24, ax + 18, WM_LIST_TOP + 24, COL_ACCENT);
        int by = WM_LIST_TOP + 86;
        tft.fillRoundRect(ax, by, 22, 78, 6, COL_CARD);
        tft.fillTriangle(ax + 11, by + 70, ax + 4, by + 54, ax + 18, by + 54, COL_ACCENT);
    }
}
void drawWmDetail() {
    if (wmSel < 0 || wmSel >= wmCount) { switchScreen(SCR_WM_LIST); return; }
    WMFixture& f = wmFixtures[wmSel];
    tft.fillScreen(COL_BG);
    drawHomeIcon(tft, 2, 3);
    tft.fillRoundRect(32, 3, 90, 24, 8, COL_CARD2);
    tft.setTextSize(1); tft.setTextColor(COL_TEXT, COL_CARD2);
    tft.drawString("Zur Liste", 48, 11);

    tft.setTextSize(2); tft.setTextColor(COL_TEXT);
    tft.drawString(f.home, 20, 40);
    tft.drawString(f.away, 20, 66);

    if (f.live) {
        tft.setTextSize(3); tft.setTextColor(COL_GOOD);
        tft.drawString(String(f.goalsHome) + " : " + String(f.goalsAway), 200, 44);
        tft.setTextSize(1); tft.setTextColor(COL_ACCENT);
        tft.drawString("Minute " + String(f.elapsed) + "'  (" + f.statusShort + ")", 200, 78);
    } else {
        tft.setTextSize(1); tft.setTextColor(COL_MUTE);
        tft.drawString("Anstoss: " + wmShortDate(f.dateStr), 200, 50);
    }

    tft.drawFastHLine(16, 100, SCR_W - 32, COL_CARD2);
    tft.setTextSize(1); tft.setTextColor(COL_TEXT);
    tft.drawString("Gewinn-Wahrscheinlichkeit:", 16, 110);

    if (f.predHome < 0) {
        tft.fillRoundRect(16, 130, 150, 30, 8, COL_ACCENT);
        tft.setTextColor(COL_DARK, COL_ACCENT);
        tft.drawString("Vorhersage laden", 32, 138);
        return;
    }
    int barY = 134, barH = 16, barW = SCR_W - 32;
    int wH = barW * f.predHome / 100, wD = barW * f.predDraw / 100, wA = barW * f.predAway / 100;
    tft.fillRect(16, barY, wH, barH, COL_GOOD);
    tft.fillRect(16 + wH, barY, wD, barH, COL_MUTE);
    tft.fillRect(16 + wH + wD, barY, wA, barH, COL_BAD);
    tft.setTextSize(1); tft.setTextColor(COL_TEXT);
    tft.drawString(f.home + ": " + String(f.predHome) + "%", 16, barY + 22);
    tft.drawString("Unentsch.: " + String(f.predDraw) + "%", 16, barY + 36);
    tft.drawString(f.away + ": " + String(f.predAway) + "%", 16, barY + 50);
}
void wmHandleTouch(int x, int y) {
    if (handleHomeTap(x, y)) return;
    if (currentScreen == SCR_WM_LIST) {
        if (!wifiIsConnected()) return;
        if (wmCount == 0 && !wmLoading) {
            wmLoading = true; drawWmList();
            wmFetchFixtures();
            wmLoading = false; drawWmList();
            return;
        }
        bool scrollable = wmCount > WM_VIS;
        if (scrollable && x >= SCR_W - 28) {
            if (y < WM_LIST_TOP + 82) wmScroll--; else wmScroll++;
            drawWmList(); return;
        }
        if (y >= WM_LIST_TOP) {
            int v = (y - WM_LIST_TOP) / WM_ITEM_H, idx = wmScroll + v;
            if (v < WM_VIS && idx < wmCount) { wmSel = idx; switchScreen(SCR_WM_DETAIL); }
        }
        return;
    }
    if (currentScreen == SCR_WM_DETAIL) {
        if (y < TAB_H && x >= 32 && x < 122) { switchScreen(SCR_WM_LIST); return; }
        if (wmSel >= 0 && wmFixtures[wmSel].predHome < 0 &&
            y >= 130 && y <= 160 && x >= 16 && x <= 166) {
            wmFetchPrediction(wmSel);
            drawWmDetail();
            return;
        }
        return;
    }
}

// ── Touch router ──────────────────────────────────
void handleTouch(int x, int y) {
    if (currentScreen == SCR_HOME) {
        if (x >= 292 && y < TAB_H) { switchScreen(SCR_SETTINGS); return; }
        if (y < 30) return;
        for (int i = 0; i < HOME_TILE_COUNT; i++) {
            HomeTile& t = homeTiles[i];
            if (x >= t.x && x < t.x + t.w && y >= t.y && y < t.y + t.h) {
                if (t.target == SCR_CALC) calcClear();
                if (t.target == SCR_STOPWATCH) { swRunning = false; swElapsed = 0; }
                switchScreen(t.target);
                return;
            }
        }
        return;
    }

    if (currentScreen == SCR_SETTINGS) { settingsHandleTouch(x, y); return; }
    if (currentScreen == SCR_WIFI_SETUP) { wifiHandleTouch(x, y); return; }

    if (currentScreen == SCR_TTT_SELECT) {
        if (handleHomeTap(x, y)) return;
        if (x < 154 && y >= 50 && y <= 200) { tttVsAI = true; tttStartGame(); switchScreen(SCR_TTT); return; }
        if (x >= 166 && y >= 50 && y <= 200) { tttVsAI = false; tttStartGame(); switchScreen(SCR_TTT); return; }
        return;
    }
    if (currentScreen == SCR_TTT) { tttHandleTouch(x, y); return; }

    if (currentScreen == SCR_NEWS_LIST || currentScreen == SCR_NEWS_DETAIL) { newsHandleTouch(x, y); return; }
    if (currentScreen == SCR_WM_LIST || currentScreen == SCR_WM_DETAIL) { wmHandleTouch(x, y); return; }

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
        if (y >= 96 && y <= 136) {
            if (x >= 16 && x <= 38) { picIndex = (picIndex - 1 + PIC_COUNT) % PIC_COUNT; drawPictures(); return; }
            if (x >= SCR_W - 38 && x <= SCR_W - 16) { picIndex = (picIndex + 1) % PIC_COUNT; drawPictures(); return; }
        }
        return;
    }

    if (currentScreen == SCR_CALC) {
        if (handleHomeTap(x, y)) return;
        calcHandleTouch(x, y);
        return;
    }

    if (currentScreen == SCR_STOPWATCH) {
        if (handleHomeTap(x, y)) return;
        stopwatchHandleTouch(x, y);
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
