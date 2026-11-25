# Gemini Nano Manager

A rootless diagnostic and management app for Android AICore and the public ML Kit GenAI
surfaces on GrapheneOS. This is a foundation build: it reports what an ordinary sandboxed
app can actually observe, keeps the original on-device summarization as a probe, and does
not pretend to provision anything it cannot.

Derived from the public [anandgaur22/GeminiNano](https://github.com/anandgaur22/GeminiNano)
demo. Internal experimental derivative; not affiliated with Google.

## Sections

| Section | State |
| --- | --- |
| Overview | Implemented: AICore/PCS presence and version, real summarization probe, most recent failure, decision-tree recommendation, retained summarization test |
| System Components | Implemented: version, installer, signing SHA-256, SDK levels, requested permissions, observable grants, declared/exported components, metadata, probed intent actions |
| AICore Features | Stage 3 placeholder (planned scope shown, no guessed data) |
| Private Compute Services | Stage 4 placeholder |
| Model Manager | Stage 3/4 placeholder |
| Provisioning Diagnostics | Stage 3/4 placeholder |
| Offline Inference | Stage 3 placeholder |
| Privacy | Implemented |
| Diagnostic Logs | Implemented: bounded local event bus, search/filter, copy, JSON and text export through SAF, redaction |
| Settings | Implemented: theme, SAF export directory, log verbosity, reset probes, provenance |

## Build

Requirements:

- Temurin JDK 21
- Android SDK platform `android-37.1` and build-tools `37.0.0`
- Gradle 9.3.1 through the wrapper

```bash
# Point the build at an SDK that has android-37.1 and build-tools 37.0.0
echo "sdk.dir=/path/to/android-sdk" > local.properties

./gradlew --no-daemon assembleOfflineDebug testDebugUnitTest lintDebug
```

The APK is written to
`app/build/outputs/apk/offline/debug/app-offline-debug.apk`.

Modules: `:app`, `:core:model` (pure Kotlin), `:core:android`, `:core:genai` (the only
module importing `com.google.mlkit.genai.*`), `:core:diagnostics`, `:core:testing`.

## Install

Install the debug APK on a physical device, for example:

```bash
adb install -r app/build/outputs/apk/offline/debug/app-offline-debug.apk
```

Emulators cannot exercise the on-device AI path.

## No INTERNET in the default build

The default `offline` flavor is the deliverable. The ML Kit GenAI dependency merges
`android.permission.INTERNET` (and `ACCESS_NETWORK_STATE`) into the manifest, so
`app/src/offline/AndroidManifest.xml` removes both with `tools:node="remove"`. Verify:

```bash
aapt2 dump permissions app/build/outputs/apk/offline/debug/app-offline-debug.apk
```

The output must not contain `android.permission.INTERNET`. An optional `network` flavor
exists only for a future, user-driven repository browser; it is not built by CI and is
not the deliverable. The app's permission says nothing about AICore, Private Compute
Services, Google Play Services or the Play Store, which are separate processes with
their own network permissions.

## On-device limitations

- Gemini Nano requires a supported physical device with Android AICore installed and
  updated. Only the public ML Kit APIs are used; there is no root, Xposed, Shizuku, ADB,
  bootloader or verified-boot change and no hidden or system API.
- On GrapheneOS the summarization probe currently returns
  `3-PREPARATION_ERROR / 606-FEATURE_NOT_FOUND: Feature 622 is not available.` The app
  reports this as unprovisioned and explains the platform restriction. It never reports
  606 as success and never fabricates a model name, size or provisioning percentage.
- Android's package visibility limits what can be read about other packages; results are
  labelled "not visible" rather than guessed. Intent actions come from a fixed set of
  probed actions because other apps' intent-filter XML is not exposed.
- Diagnostic logs are in-process only and are written to disk only when you export them
  through the Storage Access Framework. Note text, e-mail addresses and account-like
  digit strings are redacted.

## Provenance and licence

The upstream demo has no licence file. This repository is an internal experimental
derivative and makes no licence claim over the upstream code. No proprietary Google
APKs, model binaries, keys or other restricted artifacts are included. The app talks to
Google components that are already installed on the device; it does not bundle or
redistribute them.

## CI

`.github/workflows/build.yml` runs on push, pull request and manual dispatch with
`permissions: contents: read`, Temurin JDK 21, SHA-pinned actions, no secrets, and runs
`assembleOfflineDebug testDebugUnitTest lintDebug`. It uploads the offline debug APK and
prints its SHA-256 in the job summary.
