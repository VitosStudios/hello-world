# Tippli für iPhone, iPad und Mac

Enthält die iOS-App mit KI-Tastatur (Erweiterung) und die Mac-Menüleisten-App.
Gemeinsame Logik liegt im Swift-Paket `TippliCore`, die Daten (Layouts, Prompts,
Texte, Farben) kommen direkt aus `../shared`.

## Projekt erzeugen

```bash
brew install xcodegen
cd apple
xcodegen
open Tippli.xcodeproj
```

Das Xcode-Projekt wird nicht eingecheckt – nach Änderungen an `project.yml`
einfach `xcodegen` erneut ausführen.

## Eigene Apple-ID einstellen

Oben in `project.yml` (Abschnitt `settings`):

- `TIPPLI_BUNDLE_PREFIX` – z. B. `ch.deinname` (gilt für alle Bundle-IDs)
- `DEVELOPMENT_TEAM` – deine Team-ID (Xcode › Settings › Accounts)
- `TIPPLI_APP_GROUP` – App Group, standardmässig `group.<Präfix>.tippli`

Danach `xcodegen` erneut ausführen.

## iPhone / iPad

1. Schema **Tippli** wählen und auf dem iPhone starten.
2. Einstellungen › Allgemein › Tastatur › Tastaturen › Neue Tastatur hinzufügen › **Tippli**.
3. Auf **Tippli** tippen und **Vollen Zugriff erlauben** einschalten (nötig für die KI).
4. In der Tippli-App einen KI-Anbieter wählen und den eigenen API-Schlüssel eintragen.

## Mac

1. Schema **TippliMac** starten – das Symbol erscheint in der Menüleiste.
2. Die Berechtigungen **Bedienungshilfen** und **Eingabeüberwachung** erteilen
   (Systemeinstellungen › Datenschutz & Sicherheit). Danach Tippli neu starten,
   falls die Live-Korrektur nicht sofort aktiv wird.
3. Tastenkürzel: ⌃⌥K Live-Korrektur an/aus, ⌃⌥H Tipps, ⌃⌥B Bildschirmtastatur,
   ⌃⌥J jetzt korrigieren.

Hinweis: Ohne eigenes Team wird die Mac-App nur ad hoc signiert. macOS fragt die
Berechtigungen dann nach jedem Neubau erneut ab.

## Tests und CI

```bash
cd apple/TippliCore && swift test   # Engine-Testfälle aus shared/tests
./apple/ci-build.sh                 # Tests + iOS- und Mac-Build (wie GitHub Actions)
```
