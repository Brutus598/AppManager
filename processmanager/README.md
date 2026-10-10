# Process Manager (Minimal Edition)

A minimalist, user-friendly Android app written in Kotlin + Jetpack Compose / Material 3 that
shows a clear list of running and background processes and lets the user terminate them with a
single tap.

## Features

- **Übersichtliche Prozessliste** – alle laufenden Apps, Dienste und Hintergrundprozesse auf einen Blick
- **Detaillierte Infos** – Prozessname, PID, UID, CPU-Auslastung, Arbeitsspeicher (MB), Thread-Anzahl und Laufzeit
- **Zentrale Scan-Funktion** – manueller Refresh per Button sowie automatischer Hintergrund-Scan alle 3 Sekunden
- **Ein-Klick-Beenden** – einzelne Prozesse antippen zum Auswählen, dann **„Ausgewählte beenden“** oder den FAB **„Alle beenden“** nutzen. Systemprozesse werden automatisch ausgelassen
- **Suche** – Prozess- oder App-Namen live filtern
- **Farbcodierte Typen** – Vordergrund-App (grün), Dienst (blau), System (gelb), Hintergrund (lila)
- **Modernes Design** – Material 3, dynamische Ecken, subtile Farbakzente, klare Typografie, helles & dunkles Theme
- **Responsive UI** – Compose-basiert, passt sich jeder Displaygröße an, flüssige Animationen

## Architektur

```
processmanager/
├── build.gradle.kts                # Kotlin + Compose, minSdk 26, targetSdk 34
└── src/main/
    ├── AndroidManifest.xml         # KILL_BACKGROUND_PROCESSES, Nutzungsstatistik
    ├── java/com/appmanager/processmanager/
    │   ├── MainActivity.kt         # Compose-Entry-Point
    │   ├── data/
    │   │   ├── ProcessInfo.kt      # Datenmodell
    │   │   └── ProcessRepository.kt# Scan + Kill via ActivityManager + /proc
    │   ├── util/
    │   │   └── ProcReader.kt       # /proc/[pid]/stat|status Parser
    │   └── ui/
    │       ├── Theme.kt            # Farben, Typografie (hell/dunkel)
    │       ├── ProcessViewModel.kt # State-Holder + Auto-Refresh
    │       └── ProcessScreen.kt    # Scaffold, Liste, Suche, Karten, Dialoge
    └── res/                        # Strings (DE), Farben, Vector-Icon
```

## Hinweise zu Berechtigungen

- `KILL_BACKGROUND_PROCESSES` – erlaubt das Beenden von Hintergrundprozessen anderer Apps über die offizielle `ActivityManager` API.
- `PACKAGE_USAGE_STATS` – optionale Berechtigung (Nutzungsstatistik), verbessert die Erkennung von Vordergrund-Apps; die App fragt danach und leitet bei Bedarf in die Systemeinstellungen weiter.
- Auf Geräten ohne Root sind Kernel- und Systemprozesse für Apps nicht sichtbar bzw. nicht beendbar – die UI blendet diese automatisch aus bzw. deaktiviert die „Beenden“-Aktion.

## Build

Zusammen mit dem Wurzelprojekt bauen:

```bash
./gradlew :processmanager:assembleDebug
```

Das APK landet in `processmanager/build/outputs/apk/debug/`.

## Lizenz

GPL-3.0-or-later (entspricht dem übergeordneten Projekt).
