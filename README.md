# MySchiessbuch

Eigenständige App zum Erfassen von Schiessresultaten und Fotos der Papierscheiben.
Alle Übungen und Fotos werden nur auf dem Telefon gespeichert. Es wird nichts hochgeladen.
Die App funktioniert auch offline.

Ein Code, drei Varianten:

| Variante | Installation |
|---|---|
| **Android-App** | Auf dem Telefon öffnen: <https://github.com/m-cas/schiessbuch/releases/latest/download/MySchiessbuch.apk> und die Installation erlauben. |
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
| `.github/workflows/android.yml` | Baut bei jeder Änderung die Android-App und veröffentlicht sie als Release |
| `.github/workflows/ios.yml` | Baut die iPhone-App und lädt sie zu TestFlight hoch (von Hand starten) |

Die Ordner `android/` und `ios/` werden beim Bauen auf GitHub erzeugt und nicht gespeichert.

## Neue Version veröffentlichen

1. Änderung in `index.html` auf `main` speichern. Bei Änderungen der Web-App in `sw.js`
   die Nummer bei `CACHE` erhöhen, damit Telefone die neue Version laden.
2. **Web-App:** GitHub Pages ist nach etwa einer Minute aktuell.
3. **Android:** läuft automatisch (Actions → «Android app»). Die neue APK erscheint unter *Releases*.
4. **iPhone:** Actions → «iOS app» → *Run workflow*. Nach Apples Prüfung (meist 5–30 Minuten)
   erscheint der Build in TestFlight.

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
