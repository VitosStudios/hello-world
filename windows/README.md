# Tippli für Windows

Tippli korrigiert live, was du auf der echten Tastatur tippst – in jedem Programm. Dazu gibt es eine
optionale Bildschirmtastatur (Hexagon oder normal) für Touchscreens. Die KI wählst du selbst
(Claude, ChatGPT, Gemini, Copilot/GitHub Models, Mistral oder ein eigener Server) mit deinem eigenen API-Schlüssel.

## Herunterladen

1. Auf GitHub den Tab **Actions** öffnen und den neuesten erfolgreichen Lauf von **Build** wählen.
2. Unten bei **Artifacts** auf **Tippli-Windows** klicken und das ZIP entpacken.
3. `Tippli.exe` starten. Es ist keine Installation und kein separates .NET nötig.

**SmartScreen:** Die Datei ist (noch) nicht signiert. Windows zeigt deshalb beim ersten Start
„Der Computer wurde durch Windows geschützt“. Klick auf **Weitere Informationen → Trotzdem ausführen**.

## Selbst bauen

Voraussetzung: [.NET 8 SDK](https://dotnet.microsoft.com/download/dotnet/8.0) unter Windows.

```powershell
cd windows
./publish.ps1            # Tests + windows/publish/Tippli.exe
```

Ohne Skript:

```powershell
dotnet test tests/Tippli.Core.Tests -c Release
dotnet publish src/Tippli.Windows -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -o publish
```

Aufbau: `src/Tippli.Core` (plattformunabhängige Logik, Tests in `tests/Tippli.Core.Tests`) und
`src/Tippli.Windows` (WPF-Tray-App). Alle gemeinsamen Daten (Layouts, Prompts, Texte, Farben) kommen beim Bauen
direkt aus `../shared`.

## Erster Start

- Tippli erscheint als Symbol im Infobereich der Taskleiste (ggf. unter „˄“).
- Das Einstellungsfenster öffnet sich mit einer kurzen Einführung:
  1. **KI verbinden:** Anbieter wählen, API-Schlüssel einfügen („Schlüssel holen“ führt zur richtigen Seite),
     **Verbindung testen**.
  2. **Ausprobieren:** im Testfeld absichtlich schlampig tippen.
- Danach einfach in einem beliebigen Programm schreiben – Tippli wandelt die Eingabe Wort für Wort in sauberen Text.
- Tippli startet standardmässig beim Anmelden (abschaltbar unter *Computer (Mac/PC)*).

## Tastenkürzel

| Kürzel | Funktion |
|---|---|
| `Strg+Alt+K` | Live-Korrektur an/aus |
| `Strg+Alt+H` | Tipps für den markierten Text (Umformulierungen, Erklärungen, Emotions-Check, Übersetzen) |
| `Strg+Alt+B` | Bildschirmtastatur zeigen/verstecken |
| `Strg+Alt+J` | Jetzt korrigieren (markierter Text oder die letzte Eingabe) |

Bei Enter wartet Tippli kurz (einstellbar, max. 1,5 s), bis die letzte Korrektur angewendet ist – ein zweites
Enter sendet sofort.

## Datenschutz

- Text geht nur direkt von deinem PC zum gewählten KI-Anbieter – nie an uns. Kein Tracking, keine Werbung.
- Passwort-Felder (über Windows UI Automation erkannt) und ausgeschlossene Programme (z. B. KeePass, 1Password,
  Terminal, Visual Studio Code) werden nie gelesen oder gesendet. Die Liste ist unter *Computer (Mac/PC)* anpassbar.
- API-Schlüssel werden mit Windows DPAPI für dein Benutzerkonto verschlüsselt in `%APPDATA%\Tippli\keys.dat`
  gespeichert, die Einstellungen in `%APPDATA%\Tippli\settings.json`.
- Programme, die als Administrator laufen, kann Tippli (ohne selbst Administrator zu sein) nicht korrigieren.
