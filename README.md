# Sero Fernbedienung

Android-App (Kotlin) zur Steuerung eines Samsung "The Sero" (Tizen) im selben WLAN über die
WebSocket-Schnittstelle `samsung.remote.control` (Port 8002, wss).

Das Nachrichtenformat stammt aus der Open-Source-Bibliothek
[samsungtvws](https://github.com/xchwarze/samsung-tv-ws-api) (`remote.py`, `connection.py`,
`COMMANDS.md`).

## Bauen

Voraussetzung: JDK 17 oder neuer, Android SDK (Platform 35).

```
echo "sdk.dir=/pfad/zum/android-sdk" > local.properties
./gradlew assembleDebug
```

Ergebnis: `app/build/outputs/apk/debug/app-debug.apk`

Alternativ das Projekt in Android Studio öffnen und "Build > Build APK(s)" wählen.

## Funktionen

- Verbindung: IP eingeben/speichern, Koppeln mit Token-Speicherung, Statusanzeige,
  Suche im eigenen /24-Netz nach Geräten mit offenem Port 8002
- Ein/Aus, Lautstärke, Stumm, Programm, Ziffern, Steuerkreuz, OK, Zurück, Home, Menü
- Source, HDMI (durchschalten), HDMI 1–4, Wiedergabe- und Farbtasten
- Apps: Liste vom Fernseher abfragen (`ed.installedApp.get`) und starten; App-ID manuell
  per WebSocket oder REST starten
- Texteingabe per Handy-Tastatur (`SendInputString`)
- Wake-on-LAN (MAC manuell oder aus Geräteinfo `wifiMac`)
- Drehen-Taste: hält KEY_MULTI_VIEW 4 s gedrückt (Quelle: openHAB-Doku zur Dreh-Halterung, für den Sero nicht belegt)
- Key-Code-Test, auch mit Gedrückthalten (Sekunden)
- Button zu den Android-Übertragen-Einstellungen (Bildschirmspiegelung)

## Sicherheit

Das selbstsignierte Zertifikat des Fernsehers wird nur für die gespeicherte IP akzeptiert.
Beim ersten Verbinden wird sein Fingerabdruck gespeichert; danach wird jedes andere Zertifikat
abgelehnt ("Kopplung zurücksetzen" löscht Token und Fingerabdruck). Berechtigungen: nur
`INTERNET` und `ACCESS_NETWORK_STATE`. Kein Klartext-HTTP, keine Tracker.

## Status

Die App wurde gebaut, aber nicht an einem Fernseher getestet.
