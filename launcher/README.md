# Lumen – Android-Launcher im Liquid-Glass-Stil

Eigenständiger Android-Home-Screen (Kotlin, Jetpack Compose), inspiriert von der Glas-Designsprache von iOS 26. Keine Apple-Assets, keine Root-Rechte, ausschließlich offizielle Android-APIs.

## Bauen

Voraussetzungen: JDK 17+, Android SDK (Plattform 36). In Android Studio den Ordner `launcher/` öffnen oder per Kommandozeile:

```
cd launcher
./gradlew assembleRelease      # app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest    # Logik- und Screenshot-Tests
```

`app-release.apk` ist mit R8 optimiert und mit dem Debug-Schlüssel signiert (direkt installierbar). Für eine Veröffentlichung eigenen Signaturschlüssel in `app/build.gradle.kts` eintragen.
Der GitHub-Workflow `.github/workflows/build-launcher-apk.yml` baut beide APKs bei jedem Push.

Mindestversion: Android 8.0 (API 26). Echtzeit-Unschärfe ab Android 12, Lichtbrechung am Dock ab Android 13; darunter wird ein vorberechnetes, weichgezeichnetes Hintergrundbild verwendet.

## Als Standard festlegen

Beim ersten Start fragt Lumen nach der Home-Rolle (ab Android 10 per Systemdialog). Alternativ: Einstellungen › Apps › Standard-Apps › Startbildschirm-App.

## Funktionen

- **Home-Bildschirm:** mehrere Seiten, frei positionierbare Apps, Ordner (durch Aufeinanderziehen), Widgets in drei Größen, Glas-Dock (3–6 Apps), Drag & Drop zwischen Seiten/Dock/Ordnern, Bearbeitungsmodus, Kontextmenü mit App-Shortcuts, Hoch- und Querformat.
- **App-Bibliothek:** automatische Kategorien, Vorschläge, zuletzt installiert, eigene Kategorien, alphabetische Liste mit Index, Live-Suche, Apps aus-/einblenden.
- **Suche:** Apps (auch Initialen und ohne Umlaute), Rechner, Launcher-Aktionen, Web- und Store-Suche.
- **Kontrollzentrum:** Taschenlampe, Lautstärke, Helligkeit, Automatisch drehen, Nicht stören, Bildschirmaufnahme, Medien, Verknüpfungen.
- **Mitteilungszentrale:** gruppierte Karten, Wischen zum Entfernen, alle löschen, Antippen öffnet.
- **Widgets:** Uhr (digital/analog), Datum, Wetter, Kalender, Batterie, Erinnerungen, Musik, Schnellzugriffe sowie alle installierten Android-Widgets.
- **Einstellungen:** Hintergründe (6 eigene Motive, eigenes Foto, System-Hintergrund), Symbolgröße/-abstand/-stil/-pakete, Dock, Unschärfe, Animationen, Schriftgröße, Raster, Seiten, Hell/Dunkel, Akzentfarben, Gesten.

## Technische Grenzen von Android (und die umgesetzte Alternative)

| Wunsch | Grenze | Umsetzung |
|---|---|---|
| WLAN, Bluetooth, Flugmodus, mobile Daten, Energiesparen schalten | Seit Android 10/13 für Apps nicht erlaubt | Status wird angezeigt, Tippen öffnet das offizielle System-Panel |
| Helligkeit, Auto-Drehen | Benötigt Sonderzugriff „Systemeinstellungen ändern“ | Wird beim ersten Benutzen erfragt |
| Nicht stören | Benötigt Zugriff auf „Nicht stören“ | Wird beim ersten Benutzen erfragt |
| Mitteilungen, Mediensteuerung | Benötigt „Mitteilungszugriff“ | Transparente Abfrage mit Erklärung |
| Bildschirm per Doppeltipp sperren | Nur über eine Bedienungshilfe möglich (ab Android 9) | Optionale Bedienungshilfe „Lumen Sperren“, liest keine Inhalte |
| Bildschirmaufnahme | Nur per MediaProjection mit Zustimmung | Eigene Aufnahme (Video ohne Ton) nach Filme/Lumen |
| System-Übersicht der laufenden Apps | Für Drittanbieter-Launcher nicht zugänglich | Launcher-eigene Übersicht der zuletzt aus Lumen geöffneten Apps |
| Systemstatusleiste, Sperrbildschirm ersetzen | Nicht erlaubt | Bleiben vom System; der oberste Bildschirmrand gehört weiter Android |
| System-Hintergrundbild für Glas weichzeichnen | Ab Android 13 nicht lesbar | Lumen zeichnet sein eigenes Hintergrundbild; System-Hintergrund optional (dann getöntes Glas) |
| Anordnen im Querformat | – | Anordnen per Drag & Drop im Hochformat; das Querformat zeigt dasselbe Layout neu angeordnet |

## Netzwerk & Datenschutz

Einzige Internetverbindung: Wetter-Widget (Open-Meteo, ohne Konto) – nur wenn ein Ort gewählt wurde, mit 30 Minuten Cache. Alle Einstellungen und Layouts liegen lokal in `files/state/*.json`.

## Schrift

Inter (SIL Open Font License 1.1), siehe `FONT_LICENSE_Inter.txt`.
