# Gemini Nano Manager

A rootless diagnostic and management app for Android AICore and the public ML Kit GenAI
surfaces on GrapheneOS. This is the Stage 3b provisioning-investigation iteration: it
reports what an ordinary sandboxed app can actually observe, probes the public Prompt API
independently of summarization, keeps the original on-device summarization, and does not
pretend to provision anything it cannot.

Derived from the public [anandgaur22/GeminiNano](https://github.com/anandgaur22/GeminiNano)
demo. Internal experimental derivative; not affiliated with Google.

## Sections

| Section | State |
| --- | --- |
| Overview | Implemented: AICore/PCS presence and version, real summarization probe, most recent failure, decision-tree recommendation, retained summarization test |
| System Components | Implemented: version, installer, signing SHA-256, SDK levels, requested permissions, observable grants, declared/exported components, metadata, probed intent actions |
| AICore Features | Implemented: independent Prompt probe over the four `ModelConfig` combinations (`STABLE/FAST`, `STABLE/FULL`, `PREVIEW/FAST`, `PREVIEW/FULL`), status, feature id, error class/code/message, base model name only when returned, latency, and a download button only when ML Kit says DOWNLOADABLE |
| Private Compute Services | Implemented: requested-vs-granted permission matrix, exported surface with type/permission/enabled/actions and visibility limits, safe `bindService` outcomes for the six fixed services, and a one-tap `ACTION_APPLICATION_PREFERENCES` check |
| Model Manager | Stage 4 placeholder (planned scope shown, no guessed data) |
| Provisioning Diagnostics | Implemented: the four evidence blocks (matrix, Prompt, PCS surface/bind, decision) plus the exact feature-622 label |
| Offline Inference | Stage 4 placeholder |
| Privacy | Implemented |
| Diagnostic Logs | Implemented: bounded local event bus, search/filter, copy, JSON and text export through SAF, redaction |
| Settings | Implemented: theme, SAF export directory, log verbosity, reset probes, provenance |

## Build

Requirements:

- Temurin JDK 21
- Android SDK platform `android-37.1` and build-tools `37.0.0`
- Gradle 9.3.1 through the wrapper

AGP 9.1.1, Gradle 9.3.1 and compileSdk 37.1 are unchanged. The public Prompt artifact
`com.google.mlkit:genai-prompt:1.0.0-beta5` carries Kotlin 2.4 metadata, so the root build
raises AGP's built-in Kotlin runtime dependency to **Kotlin 2.3.0** with the documented
`strictly()` constraint — the same Kotlin version the official `googlesamples/mlkit`
sample uses. `:core:model`, `:core:diagnostics` and `:core:testing` use the same 2.3.0 JVM
plugin. No other dependency version changed.

```bash
# Point the build at an SDK that has android-37.1 and build-tools 37.0.0
echo "sdk.dir=/path/to/android-sdk" > local.properties

./gradlew --no-daemon assembleOfflineDebug testDebugUnitTest lintDebug
```

The APK is written to
`app/build/outputs/apk/offline/debug/app-offline-debug.apk`.

Modules: `:app`, `:core:model` (pure Kotlin), `:core:android`, `:core:genai` (the only
module importing `com.google.mlkit.genai.*`), `:core:diagnostics`, `:core:testing`.

## What the Stage 3b screens observe

- **Permission matrix.** PCS (`com.google.android.as.oss`), ASI (`com.google.android.as`),
  AICore (`com.google.android.aicore`), legacy AICore (`com.google.android.apps.aicore`)
  and this app against `PROVIDE_PRIVATE_COMPUTE_SERVICES`,
  `com.google.android.apps.aicore.service.BIND_SERVICE`, `WRITE_SECURE_SETTINGS`,
  `MANAGE_VIRTUAL_MACHINE`, `READ_DEVICE_CONFIG`, `READ_RESTRICTED_STATS` and `INTERNET`.
  Every cell shows *requested* (`requestedPermissions`) and *granted* (`checkPermission`)
  separately; "not requested" is never rendered as "denied", and one principal's state is
  never inferred from another's.
- **Prompt.** The four public `ModelConfig` combinations are probed independently of
  summarization. `checkStatus()` runs on every probe; `getBaseModelName()` and one
  `generateContent("Reply OK")` run only when ML Kit reports AVAILABLE; a download starts
  only from an explicit tap when it reports DOWNLOADABLE; the client is always closed.
  The feature id is parsed only from `Feature <id> is not available`. Feature 622 never
  derives Prompt state.
- **PCS surface and bind.** Exported services, activities, receivers and providers are
  enumerated with declared permission, enabled state and probed intent actions. The bind
  probe performs one `bindService(BIND_AUTO_CREATE)` per fixed component with a 3-second
  wait and exactly one `unbindService`, recording the framework's `SecurityException`,
  null binding, binder descriptor, disconnects and elapsed time. It sends no binder
  transaction, builds no AIDL stub, uses no reflection and calls no service method.
  Job-bound services are static facts only. `ACTION_APPLICATION_PREFERENCES` is resolved
  and opened only on a user tap.
- **Decision line.** Built from the evidence above: PCS PCC state, Prompt 606/AVAILABLE
  results and the gRPC bind outcome. The label is always
  `622 — AICore AIDL constant SAPI_SUMMARIZATION_EN_2 (decompiled source, not a model); model unknown.`
  No line says "provisioned" unless a Prompt config reported AVAILABLE and generation
  succeeded.
- The matrix, Prompt results, surface summary and bind results are emitted into the local
  `DiagnosticBus`, so the JSON export from Diagnostic Logs contains them.

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
- The Prompt probe is a separate experiment. A Prompt 606 with its own feature id means
  AICore has no ML Kit feature entry for that Prompt configuration; it does not prove the
  base model is absent, and it never marks summarization.
- The safe bind probe records what the framework returns on this OS. It cannot and does
  not send a gRPC request, so a returned binder is evidence that the OS association
  allowed the bind, not that the gRPC service would authorize a call.
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
