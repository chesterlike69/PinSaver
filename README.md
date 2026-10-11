# PinSaver

An Android app that saves Pinterest pins locally. It shows up in Pinterest's Share menu — a single tap stores the link, preview image, and metadata in a local SQLite database and displays them in a grid.

<p align="center">
  <img src="https://img.shields.io/badge/Kotlin-2.2.10-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin"/>
  <img src="https://img.shields.io/badge/Compose-BOM%202024.10-4285F4?logo=jetpackcompose&logoColor=white" alt="Compose"/>
  <img src="https://img.shields.io/badge/Room-2.7.0-3DDC84?logo=android&logoColor=white" alt="Room"/>
  <img src="https://img.shields.io/badge/AGP-9.0.0-3DDC84?logo=android&logoColor=white" alt="AGP"/>
  <img src="https://img.shields.io/badge/minSdk-24-orange" alt="minSdk 24"/>
  <br/>
  <img src="https://img.shields.io/github/v/release/chesterlike69/PinSaver" alt="Release"/>
  <img src="https://img.shields.io/github/downloads/chesterlike69/PinSaver/total" alt="Downloads"/>
  <img src="https://img.shields.io/github/license/chesterlike69/PinSaver" alt="License"/>
</p>

---

## Features

- 📌 **Share intent from Pinterest** — the app appears in the system Share menu
- 🖼️ **Automatic preview fetch** — via Pinterest oEmbed, with a fallback to parsing `og:image` from the HTML
- 🔗 **Short-link expansion** — `pin.it/xxx` → canonical `pinterest.com/pin/…`
- 📱 **Pinterest-style grid** — two columns, adaptive card proportions
- 💾 **Local Room database** — stored in the app's public folder and accessible via USB
- 📤 **Database export** — to any location through the system dialog (SAF)
- 📥 **Import as merge** — existing pins are preserved, duplicates by URL get updated
- 🔒 **Automatic backup before import** — a timestamped copy in `files/backups/`

---

## Screenshots

> _Screenshots can be added later — just upload PNGs to the repo and link them here._

```
[Pin grid]      [Empty state]      [Export / import menu]
```

---

## Tech Stack

| Component | Version |
|-----------|---------|
| Kotlin | 2.2.10 |
| Jetpack Compose | BOM 2024.10.01 |
| Material 3 | from BOM |
| Room | 2.7.0 |
| OkHttp | 4.12.0 |
| Coil | 2.7.0 |
| Coroutines | 1.9.0 |
| AGP | 9.0.0 |
| Gradle | 9.6.0 |
| KSP | 2.3.4 |
| JDK | 21 |
| minSdk | 24 (Android 7.0) |
| targetSdk / compileSdk | 35 |

---

## Build

### Requirements

- **JDK 17+** (21 is used)
- **Android SDK** with platform API 35
- **Gradle** — pulled automatically via the wrapper

### With Android Studio

1. Open **Android Studio** → **Open** → select the `PinSaver` folder
2. Wait for `Gradle Sync`
3. Click **Run ▶** — the APK will be built and installed on the device

### From the command line

```bash
./gradlew assembleDebug
```

The APK will be at:

```
app/build/outputs/apk/debug/app-debug.apk
```

Install via ADB:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## Usage

### Saving a pin

1. Open **Pinterest** on your phone → find a pin
2. Tap **Share** → pick **PinSaver**
3. Wait for the "Pin saved" toast
4. Open PinSaver — the card appears in the grid

### Export / import

- **Export** — button in the toolbar → choose where to save the `.db` (Files, Google Drive, any cloud)
- **Import** — button in the toolbar → pick a `.db` file → pins are merged into the current DB (duplicates by URL are replaced)

Before every import, a backup of the current database is created automatically.

---

## Where the data lives

Everything is stored **locally on the device** — no servers involved.

```
/sdcard/Android/data/com.example.pinsaver/files/databases/pins.db   ← database
/sdcard/Android/data/com.example.pinsaver/files/backups/             ← auto-backups
```

### Access over USB

1. Connect your phone via cable → enable **"File transfer"** mode
2. On the PC: `This PC → <Phone> → Internal storage → Android → data → com.example.pinsaver → files`
3. Open `pins.db` in **DB Browser for SQLite**, or copy it wherever you need

⚠️ On Android 11+ the built-in file manager **does not show** the `Android/data` folder. USB and ADB always work.

### Via ADB

```bash
adb pull /sdcard/Android/data/com.example.pinsaver/files/databases/pins.db
```

---

## Project Structure

```
PinSaver/
├── build.gradle                    # root build.gradle
├── settings.gradle                 # module settings
├── gradle.properties               # Gradle / Android flags
├── gradlew, gradlew.bat            # wrapper scripts
├── gradle/wrapper/                 # wrapper files
└── app/
    ├── build.gradle                # module config
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/example/pinsaver/
        │   ├── App.kt                    # Application, owns DB & repository
        │   ├── ExternalStorageContext.kt # DB path in external folder
        │   ├── Pin.kt                    # Room entity
        │   ├── PinDao.kt                 # DAO
        │   ├── AppDatabase.kt            # @Database
        │   ├── PinRepository.kt          # network + metadata parsing
        │   ├── ShareActivity.kt          # intercepts ACTION_SEND
        │   └── MainActivity.kt           # Compose UI
        └── res/values/
            ├── themes.xml
            └── colors.xml
```

---

## How it works

### Saving a pin

```
Pinterest → Share → PinSaver (ShareActivity)
    ↓
Extract URL from EXTRA_TEXT (regex)
    ↓
If pin.it → HTTP redirect → canonical URL
    ↓
Try oEmbed: pinterest.com/oembed.json?url=…
    ├── success → take title, author_name, thumbnail_url
    └── failure → parse HTML, pull og:image, og:title, og:description
    ↓
Strip boilerplate ("Check out this Pin from X")
    ↓
INSERT OR REPLACE in Room (key = URL)
```

### Importing a database

```
User picks a .db file via SAF
    ↓
Backup current DB → files/backups/pins-backup-<date>.db
    ↓
Copy imported file into cache
    ↓
Read directly via SQLiteDatabase → List<Pin>
    ↓
INSERT OR REPLACE each pin into the current DB
    ↓
UI updates automatically (Room Flow)
```

---

## Known limitations

- **Pinterest only** — other sites are not supported
- **`.db` format** — import works only with a database of the same schema (version = 1)
- **No sync** between devices — transfer manually via export/import
- **Private pins** may not expose a preview via oEmbed — in that case the card is saved without an image

---

## Roadmap

- [ ] Delete a pin (long-press on a card)
- [ ] Dark theme (Material 3 dark scheme)
- [ ] App icon (adaptive icon in Pinterest colors)
- [ ] Search across pins
- [ ] "Refresh metadata" button for old pins
- [ ] JSON export (for compatibility with other apps)

---

## License

MIT — use it however you like. If the repo was useful, drop a ⭐.
