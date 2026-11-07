# Mittelformat-Look (Annäherung) für Kaminfeuer-Fotos

Kein Nachbau einer bestimmten Kamera. Die Pipeline nähert einen Look an:
geringe Schärfentiefe, weiche Lichter, zurückhaltende Farben.

## Ablauf

```
python3 -m venv venv
venv/bin/pip install torch --index-url https://download.pytorch.org/whl/cpu
venv/bin/pip install -r hasselblad/requirements.txt
venv/bin/python hasselblad/process.py          # alle Bilder aus config.json
venv/bin/python hasselblad/process.py feuer    # nur ein Bild
venv/bin/python hasselblad/build_viewer.py     # Vorher/Nachher-HTML
```

Originale liegen unverändert in `photos/originals/`. Alle Ergebnisse landen in `out/`.

## Einstellungen pro Bild

`hasselblad/config.json`: Unter `defaults` stehen die Standardwerte, unter
`images.<name>` die Abweichungen pro Bild. Was jeder Parameter bedeutet,
steht in `_doc`. Beispiel für einen manuellen Fokuspunkt mit schwächerer Unschärfe:

```json
"feuer": { "source": "photos/originals/feuer.jpg", "focus_x": 0.45, "focus_y": 0.48, "blur_strength": 1.2 }
```

## Ausgaben

| Datei | Inhalt |
|---|---|
| `<name>_hasselblad.jpg` | volle Auflösung, Qualität 95, EXIF und ICC vom Original |
| `<name>_hasselblad.tif` | 16 bit RGB, zlib-komprimiert, ICC eingebettet |
| `<name>_depth.png` | relative Tiefe, 16 bit (hell = nah) |
| `<name>_focus.png` | Fokusmaske (weiß = scharf, dunkler = stärkere Unschärfe) |
| `<name>_params.json` | gewählter Fokuspunkt, Ebenen und Radien, Parameter |
| `<name>_vergleich.html`, `index.html` | Vorher/Nachher-Regler, offline |

## Verfahren

- **Tiefe:** Depth Anything V2 Base (`depth-anything/Depth-Anything-V2-Base-hf`) mit
  Spiegel-TTA. Das Ergebnis ist eine relative inverse Tiefe. Ein Guided Filter
  richtet die Tiefenkanten an den Bildkanten aus.
- **Fokus:** automatisch die Region mit der höchsten Detailenergie relativ zur
  Helligkeit. Ausgefressene und sehr dunkle Bereiche sowie der Bildrand sind
  ausgeschlossen.
- **Unschärfe:** etwa 14–16 Tiefenebenen. Jede Ebene bekommt eine Scheibenfaltung
  (Kreisblende), deren Radius proportional zum Disparitätsabstand von der
  Fokusebene wächst. Zusammengesetzt wird von hinten nach vorn, im linearen
  Licht, mit normalisierter Deckkraft. Kleine Lichtpunkte werden vorher leicht
  verstärkt, aber nur dort, wo das Bild unscharf wird.
- **Ton/Farbe:** Lichter-Schulter auf der Luminanz, angewendet als Faktor, damit der
  Farbton erhalten bleibt. Der Maximalkanal wird weich begrenzt. Rot und Orange
  werden in OKLab in der Chroma gedämpft. Dazu kommen eine leichte
  Schattenanhebung und eine sanfte S-Kurve. Keine Körnung, keine Vignette. Das
  8-bit-JPEG bekommt ein Dither von ±1 LSB gegen Banding.
- Der Farbraum des Originals bleibt erhalten (hier Display P3 mit sRGB-Kurve).

## Grenzen

- Ausgefressene Kanäle im JPEG lassen sich nicht wiederherstellen. Die Schulter
  nimmt nur die Härte des Übergangs.
- „Linear“ bedeutet hier: sRGB-Kurve entfernt. Die Tonkurve des Handys steckt
  weiterhin in den Daten.
- Die Tiefe ist geschätzt. Bei Flammen (halbtransparent) und Rauch ist sie nur
  ungefähr richtig.
- Verdeckte Bereiche hinter unscharfem Vordergrund sind unbekannt. Unscharfer
  Vordergrund wird darum nur nach außen weich, nicht durchscheinend.
