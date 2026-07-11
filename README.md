# Private Notes — On-device summarizer (Gemini Nano)

A minimal, heavily-commented Android demo that summarizes a note **entirely on the phone**
using the **ML Kit GenAI Summarization API**, which runs on **Gemini Nano** via **Android
AICore**.

This is the deliberate opposite of a cloud AI demo:

| | This app |
|---|---|
| API key | **None** |
| Network calls for AI | **None** |
| Cost per summary | **Free** |
| Works offline | **Yes** |
| Where your note goes | **Nowhere — it stays on the device** |

The whole point for the classroom: **turn off wifi and mobile data, and it still works.**

---

## What is Gemini Nano / AICore?

- **Gemini Nano** is a small version of Google's Gemini model that runs directly on the
  phone's hardware — no server involved.
- **Android AICore** is the system service that hosts Nano and its adapters. Apps don't
  ship the model; they ask AICore to run it locally.
- **ML Kit GenAI** (`com.google.mlkit:genai-summarization`) is the friendly library we call.
  It talks to AICore on-device. There is no cloud SDK and no key anywhere in this project.

Because inference happens on-device, the user's private note never leaves the phone, it
costs nothing per request, and it works with the radios off.

---

## Before you start — what you need (checklist)

Tick all of these before running:

- [ ] **Android Studio** (latest stable — Ladybug or newer).
- [ ] **JDK 11+** (Android Studio ships its own — no separate install needed).
- [ ] A **Gemini Nano–supported physical device**, e.g. **Pixel 8 / 8 Pro / 8a or newer**,
      Pixel 9 series, or another supported flagship (Samsung S24+, etc.).
      ⚠️ A normal **emulator will NOT work** — Nano needs real device hardware + AICore.
- [ ] The device on **Android 8.0 (API 26) or higher**.
- [ ] **Android AICore** app installed and updated on the device (details in Step 4 below).
- [ ] A **USB cable** to connect the phone, plus **wifi/internet for the first run only**
      (the model downloads once, then it's fully offline).

> No Google account, no API key, no billing, no Firebase — none of that is needed.

---

## Setup — step by step (do these in order)

### Step 1 — Open the project in Android Studio

1. Launch **Android Studio**.
2. **File → Open** → select this project's root folder (`GeminiNano`) → **OK**.
3. Wait for Gradle to finish syncing (bottom status bar shows progress). First sync
   downloads dependencies, so keep the internet on. If it asks to upgrade the Gradle
   plugin or install an SDK, click **Accept / Install**.

### Step 2 — Point the project at your Android SDK

The file `local.properties` tells Gradle where your SDK is. Android Studio usually creates
it automatically. If Gradle complains "SDK location not found", open `local.properties` in
the project root and set:

```properties
sdk.dir=/Users/<you>/Library/Android/sdk      # macOS
# sdk.dir=C:\\Users\\<you>\\AppData\\Local\\Android\\Sdk   # Windows
```

(Don't commit this file — it's machine-specific.)

### Step 3 — Turn on Developer Mode + USB debugging on the phone

1. On the phone: **Settings → About phone**.
2. Tap **Build number** 7 times until it says "You are now a developer".
3. Go back to **Settings → System → Developer options**.
4. Turn on **USB debugging**.
5. Connect the phone to the computer with USB. On the phone, tap **Allow** when it asks to
   trust this computer.

### Step 4 — Make sure Android AICore is installed and ready (important!)

Gemini Nano lives inside the **Android AICore** system app. Without it, the demo shows
"On-device AI unavailable".

1. On the phone, open the **Play Store** and search **"Android AICore"**.
2. If there's an **Update** button, tap it. Otherwise it's already installed.
3. Keep the phone on **wifi and charging** for a few minutes so AICore can pull its latest
   config in the background. (On a freshly reset phone this can take a while — a reboot
   speeds it up.)

> The app already handles the case where AICore isn't ready — it checks availability first
> and never crashes. This step just makes the demo actually produce a summary.

### Step 5 — Build and run

1. In Android Studio's toolbar, pick your **physical device** from the device dropdown
   (not an emulator).
2. Press the green **Run ▶** button (or `Ctrl/Cmd + R`).
3. The app **Private Notes** installs and launches.

### Step 6 — First summary (one-time model download)

1. Paste or type a note. **Longer notes work best** — aim for a few paragraphs (real prose,
   ~150+ words), e.g. long meeting notes or a journal entry.
2. Tap **Summarize**.
3. The first time, you'll see **"Preparing on-device model…"** with a **live progress bar
   and a `42%  (128 MB / 305 MB)` counter** while Nano downloads (a few hundred MB, one
   time, over wifi). When it reaches 100% the download disappears and a 3-bullet summary
   appears automatically.
4. Every run after that is instant and offline (no download, no progress bar).

> How the progress is shown: ML Kit's `DownloadCallback` reports `onDownloadStarted(total)`
> and repeated `onDownloadProgress(downloaded)` events. `NotesRepository` forwards those to
> the `ViewModel`, which stores `downloadedBytes / totalBytes` in state, and the UI renders
> a `LinearProgressIndicator` that fills up. If the size is briefly unknown it shows an
> animated indeterminate bar labelled "Starting download…".

---

## Step 7 — Prove it's really on-device (the classroom demo)

1. Do one summary while online so the model finishes downloading.
2. Turn on **Airplane mode** (or switch off **wifi AND mobile data**).
3. Paste a fresh note → tap **Summarize** → it still produces a summary. 🎉
4. Open `AndroidManifest.xml` and show the class there is **no `INTERNET` permission** — the
   app literally cannot phone home.
5. Tap **About** in the app to show the on-device vs cloud comparison.

---

## Troubleshooting (common issues)

| Symptom | Fix |
|---|---|
| App shows "On-device AI needs a supported device (e.g. Pixel 8+)." | You're on an emulator or an unsupported phone. Use a real Pixel 8+ / supported device. |
| "Feature not available" / error code 606 | AICore hasn't finished its background setup. Keep the phone online, wait a few minutes, **reboot**, and retry. |
| "AICore service failed to bind" (error 601) | Update the **Android AICore** app in Play Store, then reinstall this app. |
| Gradle sync fails: "SDK location not found" | Set `sdk.dir` in `local.properties` (see Step 2). |
| Summary is only 1–2 bullets or weak | The note was too short. `ARTICLE` mode needs real prose of a few hundred words. |
| Manifest merger error about minSdk | Already handled via `tools:overrideLibrary` in `AndroidManifest.xml`; do a **Build → Clean Project** then rebuild. |
| Nothing happens on unlocked-bootloader device | The API doesn't support devices with an unlocked bootloader. Use a stock device. |

---

## Device requirements (summary)

On-device AI needs a **Gemini Nano–supported device** — for example a **Pixel 8 / 8 Pro or
newer** — running **Android 8.0 (API 26) or higher** with **Android AICore** installed and
up to date. The app itself installs on **minSdk 24**, but the AI path is guarded at runtime
and shows a clear message on unsupported devices. It **never** falls back to the cloud —
that's the teaching point.

---

## On-device (Nano) vs Cloud (Gemini API)

| | On-device (Gemini Nano) | Cloud (Gemini API) |
|---|---|---|
| **Privacy** | Note stays on the phone | Note is sent to Google's servers |
| **Offline** | Works with no connection | Requires internet |
| **Cost** | Free | Billed per request |
| **Setup** | No API key | API key + billing required |
| **Capability** | Small, fast, focused tasks | Largest, most capable models |
| **Best for** | Private, cheap, offline features | Heavy reasoning, long context, latest models |

The same contrast is available in-app via the **About** button.

---

## How the code is organized (MVVM)

```
app/src/main/java/com/anandgaur/gemininano/
├── MainActivity.kt            # Hosts the Compose UI. No AI code.
├── SummarizerViewModel.kt     # Holds UI state (StateFlow) + orchestrates the flow.
├── data/
│   └── NotesRepository.kt      # The ONLY place that touches ML Kit / Gemini Nano.
└── ui/
    ├── PrivateNotesScreen.kt   # Single screen: badges, editor, summary card, About.
    └── theme/                  # Material 3 theme.
```

Flow when you tap **Summarize**:

1. `NotesRepository.checkStatus()` asks ML Kit whether Nano is `AVAILABLE`,
   `DOWNLOADABLE`, `DOWNLOADING`, or unsupported. (No network for the AI.)
2. If downloadable, `downloadModel()` fetches the model once → UI shows
   *"Preparing on-device model…"*.
3. `summarize()` runs inference on the device and returns a 3-bullet summary.
4. The ViewModel exposes it as immutable `NotesUiState`; the Compose screen just renders it.

The AI configuration lives in `NotesRepository`:

```kotlin
SummarizerOptions.builder(context)
    .setInputType(SummarizerOptions.InputType.ARTICLE)
    .setOutputType(SummarizerOptions.OutputType.THREE_BULLETS)
    .setLanguage(SummarizerOptions.Language.ENGLISH)
    .setLongInputAutoTruncationEnabled(true)
    .build()
```

---

## Tech stack

- Kotlin + Jetpack Compose (Material 3)
- MVVM: `AndroidViewModel` + `StateFlow` UI state + `NotesRepository`
- Coroutines (with `kotlinx-coroutines-guava` to `await()` ML Kit's `ListenableFuture`s)
- `com.google.mlkit:genai-summarization:1.0.0-beta1` — on-device only
- minSdk 24 (AI path requires API 26+ and a Nano-supported device)

## Notes & limitations

- The Summarization API is **Beta**; the model supports **English, Japanese, and Korean**.
- Input is capped at ~4000 tokens (~3000 English words); longer input is auto-truncated.
- `ARTICLE` mode works best on real prose of a few hundred words — very short notes may
  return only one or two bullets.
