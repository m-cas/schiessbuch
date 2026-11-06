# CHSchiessbuch

Eigenständige App zum Erfassen von Schiessresultaten und Fotos der Papierscheiben.
Alle Übungen und Fotos werden nur auf dem Telefon gespeichert. Es wird nichts hochgeladen.
Die App funktioniert auch offline.

Ein Code, drei Varianten:

| Variante | Installation |
|---|---|
| **Android-App** | Auf dem Telefon öffnen: <https://github.com/m-cas/schiessbuch/releases/latest/download/CHSchiessbuch.apk> und die Installation erlauben. |
| **iPhone-App** | Über TestFlight (Einladung per E-Mail) bzw. später aus dem App Store. |
| **Web-App** | <https://m-cas.github.io/schiessbuch/> in Safari bzw. Chrome öffnen → «Zum Home-Bildschirm». |

Die Daten der drei Varianten sind getrennt. Zum Umziehen: in der alten Variante
**Backup speichern**, in der neuen **Backup wiederherstellen**.

**Hinweis:** Wenn die App gelöscht wird, sind ihre Übungen und Fotos weg.
Speichere regelmässig ein Backup.

## Aufbau

| Pfad | Inhalt |
|---|---|
| `index.html` | Die ganze App (Oberfläche, Logik, Texte DE/EN) |
| `sw.js`, `manifest.webmanifest`, `*.png` | Offline-Cache, App-Angaben und Symbole der Web-App |
| `capacitor.config.json` | Name und ID der Android/iOS-App (`io.github.mcas.schiessbuch`) |
| `scripts/copy-web.mjs` | Kopiert die Web-App nach `www/` für die nativen Apps |
| `scripts/prepare-native.mjs` | Erzeugt das Android- bzw. iOS-Projekt und setzt Version, Signatur, Kamera-Berechtigungen |
| `assets/logo.png` | Vorlage für App-Symbol und Startbild |
| `android-native/` | Native Android-App (Kotlin, Jetpack Compose) |
| `.github/workflows/android.yml` | Baut die Android-App und veröffentlicht sie als Release (von Hand starten) |
| `.github/workflows/android-native-check.yml` | Test-Build der Android-App im Emulator mit Screenshots (Branch `native`) |
| `ios-native/` | Native iPhone-App (SwiftUI); `project.yml` beschreibt das Xcode-Projekt (XcodeGen) |
| `.github/workflows/ios.yml` | Baut die native iPhone-App und lädt sie zu TestFlight hoch (von Hand starten) |
| `.github/workflows/ios-native-check.yml` | Test-Build der iPhone-App im Simulator mit Screenshots (Branch `native`) |
| `meisterschaft-beispiel.csv` | Beispiel einer Meisterschaftsdatei |
| `tools/schiessbuch_import.py` | Übernimmt per E-Mail gesendete Übungen in eine Excel-Datei (siehe unten) |

Die Ordner `android/` und `ios/` werden beim Bauen auf GitHub erzeugt und nicht gespeichert.

## Neue Version veröffentlichen

1. Änderung in `index.html` auf `main` speichern. Bei Änderungen der Web-App in `sw.js`
   die Nummer bei `CACHE` erhöhen, damit Telefone die neue Version laden.
2. **Web-App:** GitHub Pages ist nach etwa einer Minute aktuell.
3. **Android:** von Hand starten: Actions → «Android app» → *Run workflow*. Die neue APK erscheint unter *Releases*.
4. **iPhone:** Actions → «iOS app» → *Run workflow*. Nach Apples Prüfung (meist 5–30 Minuten)
   erscheint der Build in TestFlight.

## E-Mails nach Excel übernehmen

`tools/schiessbuch_import.py` liest den Text einer E-Mail, die CHSchiessbuch mit «E-Mail» verschickt
(Deutsch oder Englisch, eine oder mehrere Übungen), und hängt **pro Serie eine Zeile** an eine
Excel-Datei an. So lassen sich die Resultate mehrerer Schützen in einer Tabelle sammeln.

Einmalig installieren: `pip3 install openpyxl`

```sh
# E-Mail-Text als Datei (.txt) oder gespeicherte E-Mail (.eml)
python3 tools/schiessbuch_import.py --user "Massimo" --excel resultate.xlsx email.txt

# Text aus der Zwischenablage (macOS)
pbpaste | python3 tools/schiessbuch_import.py --user "Massimo" --excel resultate.xlsx
```

| Option | Bedeutung |
|---|---|
| `--user`, `-u` (Pflicht) | Name des Schützen; steht in der ersten Spalte |
| `--excel`, `-x` | Excel-Datei; fehlt sie, wird sie mit Kopfzeile erstellt (Standard `schiessbuch.xlsx`) |
| `--allow-duplicates` | Zeilen auch eintragen, wenn sie schon vorhanden sind |
| Dateien | eine oder mehrere `.txt`/`.eml`; ohne Angabe wird von der Eingabe gelesen |

Spalten: Benutzer, Datum, Kategorie, Disziplin, Beschreibung, Schiessstand, Waffe, Bedingungen,
Serie, Format, Schüsse, Resultat, Maximum, Resultat 10 Schuss A10, Innenzehner (X), Tiefschuss,
Probe (zählt nicht), Notizen, Fotos, Importiert am.

Eine bereits importierte Serie (gleicher Benutzer, Datum, Beschreibung, Serie, Schüsse und Resultat)
wird übersprungen; dieselbe E-Mail kann also gefahrlos zweimal importiert werden. Fotos werden nicht
übernommen, nur ihre Anzahl.

## Einrichtung (einmalig)

Repository-Secrets unter *Settings → Secrets and variables → Actions*:

| Secret | Wofür |
|---|---|
| `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD` | Signatur der Android-App. Ohne sie entsteht eine Test-Version, die spätere Updates nicht annimmt. |
| `APPLE_TEAM_ID` | Team-ID (developer.apple.com → Membership) |
| `ASC_KEY_ID`, `ASC_ISSUER_ID`, `ASC_KEY_P8` | App Store Connect API-Schlüssel (Rolle *Admin*); `ASC_KEY_P8` = Inhalt der `.p8`-Datei |

In App Store Connect muss eine App mit der Bundle-ID `io.github.mcas.schiessbuch` angelegt sein.

## Lokal entwickeln

```sh
npm install
npm run android   # erzeugt android/, danach in Android Studio öffnen: npx cap open android
npm run ios       # erzeugt ios/ (nur auf einem Mac), danach: npx cap open ios
```

Die Web-App lässt sich ohne Installation testen: `python3 -m http.server` im Repository starten
und <http://localhost:8000> öffnen.
