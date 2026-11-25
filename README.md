# Gemini Nano Manager

A rootless diagnostic and management app for Android AICore and the public ML Kit GenAI
surfaces on GrapheneOS. The Stage 4 iteration is feature-complete against the project's
definition: it reports what an ordinary sandboxed app can actually observe, probes every
public ML Kit GenAI capability independently, offers only user-triggered downloads, runs a
fixed-input inference matrix, keeps the original on-device summarization, and does not
pretend to provision anything it cannot.

Derived from the public [anandgaur22/GeminiNano](https://github.com/anandgaur22/GeminiNano)
demo. Internal experimental derivative; not affiliated with Google.

## Install on the device (no ADB required)

The deliverable is the `offline` debug build, versionName **`0.5.0-offline`** (versionCode 5),
applicationId `dev.shockstruck.gemininano`. The `Build offline debug APK` workflow builds it on
every push and pull request and uploads it as the `gemini-nano-manager-offline-debug` artifact;
you can also build it locally (see [Build](#build)). Installing it needs only the phone:

1. **Get the APK onto the device.** Download the workflow artifact in the phone's browser, or
   copy `app-offline-debug.apk` to the phone over USB/MTP, a local file share or a cloud drive
   you already use. No ADB is involved.
2. **Allow the installer.** The first time, Android asks the file manager or browser for
   permission to install unknown apps. Allow it for that app
   (`Settings → Apps → Special app access → Install unknown apps`); the grant belongs to the
   file manager or browser, not to Gemini Nano Manager.
3. **Tap the APK and confirm the identity.** The installer should show **Gemini Nano Manager** and
   version `0.5.0-offline`. If your installer does not display the package name or version code,
   confirm them after the first launch in **Settings → Version and provenance** (Application ID
   `dev.shockstruck.gemininano`, version name and code) and in **System Components** (version,
   installer and signing certificate SHA-256). If the package name or version differs, you are
   installing the wrong file — stop and re-download it.
4. **If installation fails with a signature mismatch**, uninstall the older debug build first.
   Debug APKs signed with different debug keys cannot upgrade each other, so the installer
   rejects the new one. Uninstalling also clears the app's local settings and stored probe
   result; files you already exported are not affected. Then tap the new APK again.

ADB is an optional convenience only; nothing else in this document requires it:

```bash
adb install -r app-offline-debug.apk
```

Emulators cannot exercise the on-device AI path.

## On-device validation procedure

This is the evidence path for the installed build. Every step is read-only observation; the app
never provisions anything. Use a supported physical device with Android AICore installed and
updated.

1. **Overview.** Open the app. The **Runtime state** card shows the Android AICore and Private
   Compute Services versionName/versionCode (or `unknown`), the PCS
   `PROVIDE_PRIVATE_COMPUTE_SERVICES` cell, the retained **Summarization feature status**, base
   model name when returned, the last probe time, the device and the build fingerprint. Tap
   **Re-check** if it has not probed yet. Record the AICore and PCS versions exactly as shown.
2. **Provisioning Diagnostics → Run Full Diagnostics.** Tap **Run Full Diagnostics** and wait for
   `Report ready`. Then **Export JSON** and **Export text** (a Storage Access Framework file
   picker, or the export directory configured in Settings). The JSON report contains:
   the report schema and generation time; the app identity (applicationId, versionName,
   versionCode); device facts (manufacturer, model, device, fingerprint, Android release, SDK
   integer, security patch); the full permission matrix with *requested*, `checkPermission`,
   the `requestedPermissionsFlags` grant bit and the `getPermissionInfo` declaration for every
   principal × permission cell; a permission-verification block listing every permission PCS,
   AICore and the other catalogued packages request, with the same three sources and any
   requested-but-denied signature or privileged permission called out; every GenAI feature row
   (status — including an explicit `NOT PROBED` row when no result exists — feature id, error
   code, exception class, message, base model name or signature, vector dimension and token
   limit when returned, latency, probe time, last download); the four Prompt configs with the
   same fields; the retained
   summarization probe; the PCS surface (components, declared permission, enabled state, probed
   intent actions); any PCS bind results already run; the offline inference matrix if it has run;
   and the diagnostic event log. The text export carries the same content in readable form. The
   action performs no download and no bind.
3. **AICore Features → Re-probe all.** Tap **Re-probe all features** in the GenAI feature matrix
   and **Re-probe all four** in the Prompt card. For every row record: status, feature id, error
   code, exception class, message, base model name or model signature when the API returns one,
   probe time and probe latency. Rows are independent — one failure, or one probe that hangs to
   its timeout, never changes another row. A `606` row means AICore has no configuration entry
   for that feature id; report it as unprovisioned. A row with no result reads **NOT PROBED**;
   that is not an error.
4. **Model Manager.** Only a row ML Kit itself reports as **DOWNLOADABLE** offers a download
   button. Tap **Download** on that row and record the progress bytes and the result; after a
   failure the same row offers **Retry download** — record that result too. Nothing downloads
   automatically, and no row offers a size the SDK did not report. A **PROBE ERROR** row shows
   the error code, exception class, message and probe time; a row with no result shows
   **NOT PROBED**, and its detail is never used for a probed result.
5. **Offline Inference.** Before running anything, disable Wi-Fi and mobile data yourself in the
   system settings (airplane mode is a convenient way to do both) and confirm both are off. Then
   tap **Run offline matrix (N eligible)** and record each row's outcome, output/detail, error
   code, exception class, message and latency. Export the results afterwards through
   **Provisioning Diagnostics → Run Full Diagnostics → Export JSON**: the report's `offline` array
   carries them. The app never toggles Wi-Fi or mobile data and cannot control AICore's or PCS's
   network use, so a success row is evidence of offline operation **only** when you disabled
   connectivity yourself and checked it was off. A success with connectivity on is not offline
   evidence.
6. **Private Compute Services → Run bind probe.** This is a separate explicit action; Run Full
   Diagnostics does not trigger it. The rows are planned from the installed PCS package's own
   exported, effectively enabled, permission-free services (the current build's
   `com.google.android.apps.miphone.astrea.*` names; the older open-source names are aliases).
   Before you tap, every planned bind reads **NOT RUN**; a known service the package does not
   declare says **not declared**, which is different from declared-but-not-exported and from
   components-not-read. Tap **Run bind probe (N eligible)** and record, per service: the bind
   outcome (`binder returned`, `SecurityException`, null binding, timeout, refusal, binding
   died), the guarded binder interface descriptor (an empty descriptor stays empty), whether
   the binder is alive, the exception class/message, the elapsed time and the unbind-call
   count. The probe does one `bindService(BIND_AUTO_CREATE)` per eligible service with a
   3-second wait and exactly one unbind; it sends no binder transaction, builds no AIDL stub,
   calls no `asInterface` and uses no transaction code. **Its result is this app's own bind
   attempt and never proves AICore's access to PCS.**
7. **Private Compute Services → launch controls.** **Open PCS settings** and **Open Network
   usage log** are separate explicit taps, shown only when the installed package reports the
   activity as exported, effectively enabled and permission-free. The item-details activity is
   listed but not launched because its required extras are not publicly known; none are
   guessed. Each tap records resolved/launched, `ActivityNotFoundException`,
   `SecurityException` or another error. Nothing is read back from another app's screen.
8. **What to attach back.** The Provisioning Diagnostics JSON export (and the text export if
   convenient); the Diagnostic Logs JSON export if you want the event stream; the AICore and PCS
   versions from Overview; the permission matrix (already in the JSON export); the per-feature
   and per-Prompt rows; the six bind outcomes; and the offline matrix with a note confirming you
   disabled connectivity yourself.
8. **Known platform limit.** On GrapheneOS the summarization probe reports
   `Feature 622 is not available` (`3-PREPARATION_ERROR` / `606-FEATURE_NOT_FOUND`): AICore has
   no provisioning entry for feature 622. Feature 622 is the AICore AIDL constant
   `SAPI_SUMMARIZATION_EN_2` (decompiled source, not a model); the model is unknown. No rootless
   app can insert that manifest into AICore — this app reports the state and does not fix it, and
   a Prompt `606` with a different feature id does not change that.

## Sections

| Section | State |
| --- | --- |
| Overview | Implemented: AICore/PCS presence and version, real summarization probe, most recent failure, decision-tree recommendation, retained summarization test |
| System Components | Implemented: version, installer, signing SHA-256, SDK levels, requested permissions, observable grants, declared/exported components, metadata, probed intent actions |
| AICore Features | Implemented: independent Prompt probe over the four `ModelConfig` combinations (`STABLE/FAST`, `STABLE/FULL`, `PREVIEW/FAST`, `PREVIEW/FULL`) plus the full public GenAI matrix — Rewriting, Proofreading, Image Description, Embedding, Agent, Speech Recognition, Speech Translation and Text to Speech. Each row is an isolated probe with status, feature id, error class/code/message, base model name or signature only when returned, latency, and a download button only when ML Kit says DOWNLOADABLE. The retained summarization status is shown separately |
| Private Compute Services | Implemented: requested-vs-granted permission matrix, exported surface with type/permission/enabled/actions and visibility limits, safe `bindService` outcomes for the six fixed services, and a one-tap `ACTION_APPLICATION_PREFERENCES` check |
| Model Manager | Implemented: per-feature observed status, explicit download only when DOWNLOADABLE, progress from the public callbacks, retry after failure, no catalog, no automatic download, no invented cancel |
| Provisioning Diagnostics | Implemented: the evidence blocks (matrix, feature statuses, Prompt, PCS surface/bind, decision), the exact feature-622 label, and one **Run Full Diagnostics** action producing an exportable JSON + text report |
| Offline Inference | Implemented: one short fixed-input inference per AVAILABLE feature with exact failures and latency; the user must disable connectivity externally and no offline success is claimed from this app alone |
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

## What the screens observe

- **Permission matrix.** PCS (`com.google.android.as.oss`), ASI (`com.google.android.as`),
  AICore (`com.google.android.aicore`), legacy AICore (`com.google.android.apps.aicore`)
  and this app against `PROVIDE_PRIVATE_COMPUTE_SERVICES`,
  `com.google.android.apps.aicore.service.BIND_SERVICE`, `WRITE_SECURE_SETTINGS`,
  `MANAGE_VIRTUAL_MACHINE`, `READ_DEVICE_CONFIG`, `READ_RESTRICTED_STATS` and `INTERNET`.
  Every cell shows *requested* (`requestedPermissions`), `checkPermission`, the
  `requestedPermissionsFlags` grant bit and the `getPermissionInfo` declaration (declaring
  package and protection level/flags, or "not defined on this OS"). A disagreement between the
  two grant sources is its own status; "not requested" is never rendered as "denied" and never
  hides an observed grant; one principal's state is never inferred from another's.
- **Prompt.** The four public `ModelConfig` combinations are probed independently of
  summarization. `checkStatus()` runs on every probe; `getBaseModelName()` and one
  `generateContent("Reply OK")` run only when ML Kit reports AVAILABLE; a download starts
  only from an explicit tap when it reports DOWNLOADABLE; the client is always closed.
  The feature id is parsed only from `Feature <id> is not available`. Feature 622 never
  derives Prompt state.
- **PCS surface and bind.** Exported services, activities, receivers and providers are
  enumerated with declared permission, manifest and effective enabled state, the public
  runtime enabled setting and probed intent actions. A reduced package read that did not
  return components says "components not read", never "none". The bind plan is built from
  those runtime facts: only an exported, effectively enabled, permission-free service is
  eligible; the two current `astrea` names come first and the open-source names remain
  aliases. The probe performs one `bindService(BIND_AUTO_CREATE)` per eligible service
  with a 3-second wait and exactly one `unbindService`, recording the outcome, the guarded
  descriptor read and its own timing, binder-alive state, exception, disconnects and
  elapsed time. It sends no binder transaction, builds no AIDL stub, calls no
  `asInterface` and uses no transaction code. Job-bound services are static facts only.
  Launch controls for PCS settings and the network usage log are explicit taps; the
  item-details activity is not launched because its extras are unknown.
- **Feature matrix.** Eight independent clients over the public SDKs: Rewriting,
  Proofreading, Image Description, Embedding, Agent, Speech Recognition, Speech
  Translation and Text to Speech. Each probe records only what its own client returned;
  one failure never marks another feature. Speech uses file/fixed-buffer input only, so
  no `RECORD_AUDIO` is requested. Embedding reports the model signature and vector
  dimension the API returned. Agent runs over the public `Agent`/`AgentRunner` API with
  app functions disabled.
- **Model Manager.** Downloads start only from an explicit tap on a row ML Kit reports
  as DOWNLOADABLE. Progress is the SDK callback's byte count; the only "total" shown is
  the one `onDownloadStarted`/`DownloadStarted` reported. A failed download keeps its
  Retry button. No catalog, no size estimate, no automatic download and no invented
  cancel operation.
- **Offline Inference.** One short fixed-input inference per AVAILABLE feature, with
  latency and the exact failure. Rewriting/Proofreading use fixed text; Image Description
  uses a generated 96×96 bitmap; Embedding a fixed sentence; Agent a one-turn "Reply OK";
  speech recognition/translation a fixed 1-second 16 kHz mono PCM16 silence buffer or WAV
  file; Text to Speech a fixed sentence with the first supported en-US voice. The screen
  tells the user to disable connectivity externally; it never toggles a radio.
- **Run Full Diagnostics.** One explicit action re-inspects packages, rebuilds the
  permission matrix, probes every GenAI feature status, the four Prompt configs and the
  retained summarization probe, records the PCS surface, includes any bind results the
  user already ran, and builds JSON and text reports through the existing SAF export
  path. Each probe is isolated and bounded by a 30-second timeout. The report separates
  "probe executed" from "feature available" for every feature and Prompt row, lists every
  explicitly untested operation, names the AICore/PCS/ASI/GMS versionCode and signer
  SHA-256, includes the relevant `getSystemAvailableFeatures` names, and keeps full
  permission enumeration to AICore and PCS. Bind rows carry the holder's first-install and
  last-update times beside AICore's first-install time. It performs no download and no
  bind.
- **Decision line.** Built from the evidence above: PCS PCC state, Prompt 606/AVAILABLE
  results and the gRPC bind outcome. The label is always
  `622 — AICore AIDL constant SAPI_SUMMARIZATION_EN_2 (decompiled source, not a model); model unknown.`
  No line says "provisioned" unless a Prompt config reported AVAILABLE and generation
  succeeded.
- The matrix, Prompt results, surface summary and bind results are emitted into the local
  `DiagnosticBus`, so the JSON export from Diagnostic Logs contains them.

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
- The safe bind probe records what the framework returns to this app on this OS. It cannot
  and does not send a gRPC request, so a returned binder is evidence about this app's own
  bind attempt only; it says nothing about AICore's access to PCS or a gRPC call's
  authorization.
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
