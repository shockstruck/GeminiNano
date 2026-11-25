# Gemini Nano Manager

A rootless diagnostic and management app for Android AICore and the public ML Kit GenAI
surfaces on GrapheneOS. Version 0.7.0 reports what an ordinary sandboxed app can actually
observe, probes every public ML Kit GenAI capability independently, offers only
user-triggered downloads, runs a fixed-input inference matrix, keeps the original
on-device summarization, reads the installed AICore and PCS code as narrow static facts,
and names the first provisioning step the installed-code evidence shows failing (or says
it cannot be determined). It does not pretend to provision anything it cannot.

Derived from the public [anandgaur22/GeminiNano](https://github.com/anandgaur22/GeminiNano)
demo. Internal experimental derivative; not affiliated with Google.

## Install on the device (no ADB required)

The deliverable is the `offline` debug build, versionName **`0.7.0-offline`** (versionCode 8),
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
   version `0.7.0-offline`. If your installer does not display the package name or version code,
   confirm them after the first launch in **Settings → Version and provenance** (Application ID
   `dev.shockstruck.gemininano`, version name and code) and in **System Components** (version,
   installer and signing certificate SHA-256). If the package name or version differs, you are
   installing the wrong file — stop and re-download it.
4. **If installation fails with a signature mismatch**, uninstall the older debug build first.
   Debug APKs signed with different debug keys cannot upgrade each other, so the installer
   rejects the new one. Uninstalling also clears the app's local settings and stored probe
   result; files you already exported are not affected. Then tap the new APK again.

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
2. **AICore Features → Re-probe all.** Tap **Re-probe all features** in the GenAI feature matrix
   and **Re-probe all four** in the Prompt card. For every row record: status, feature id, error
   code, exception class, message, base model name or model signature when the API returns one,
   probe time and probe latency. Rows are independent — one failure, or one probe that hangs to
   its timeout, never changes another row. A `606` row means AICore has no configuration entry
   for that feature id; report it as unprovisioned. A row with no result reads **NOT PROBED**;
   that is not an error.
3. **Model Manager.** Only a row ML Kit itself reports as **DOWNLOADABLE** offers a download
   button. Tap **Download** on that row and record the progress bytes and the result; after a
   failure the same row offers **Retry download** — record that result too. Nothing downloads
   automatically, and no row offers a size the SDK did not report. A **PROBE ERROR** row shows
   the error code, exception class, message and probe time; a row with no result shows
   **NOT PROBED**, and its detail is never used for a probed result.
4. **Offline Inference.** Before running anything, disable Wi-Fi and mobile data yourself in the
   system settings (airplane mode is a convenient way to do both) and confirm both are off. Then
   tap **Run offline matrix (N eligible)** and record each row's outcome, output/detail, error
   code, exception class, message and latency. The final report in step 7 carries them. The app
   never toggles Wi-Fi or mobile data and cannot control AICore's or PCS's network use, so a
   success row is evidence of offline operation **only** when you disabled connectivity yourself
   and checked it was off. A success with connectivity on is not offline evidence.
5. **Private Compute Services → Run bind probe.** Two paths run the same probe: the **Run bind
   probe (N eligible)** button on this screen, or the **Include PCS bind probe** checkbox beside
   **Run Full Diagnostics** in step 7. That checkbox is unchecked by default, and ticking it and
   tapping Run is consent for that one run only; it is never remembered. The rows are planned
   from the installed PCS package's own exported, effectively enabled, permission-free services
   (the current build's `com.google.android.apps.miphone.astrea.*` names; the older open-source
   names are aliases).
   Before you tap, every planned bind reads **NOT RUN**; a known service the package does not
   declare says **not declared**, which is different from declared-but-not-exported and from
   components-not-read. Tap **Run bind probe (N eligible)** and record, per service: the bind
   outcome (`binder returned`, `SecurityException`, null binding, timeout, refusal, binding
   died), the guarded binder interface descriptor (an empty descriptor stays empty), whether
   the binder is alive, the exception class/message, the elapsed time and the unbind-call
   count. The probe does one `bindService(BIND_AUTO_CREATE)` per eligible service with a
   3-second wait and exactly one unbind; it sends no binder transaction, builds no AIDL stub,
   calls no `asInterface` and uses no transaction code. The interface descriptor is read on a
   worker thread, never the main thread, so a hung remote cannot delay the 3-second timeout;
   if that read is still pending at the timeout, the binder outcome is kept and the read is
   recorded as **timed out**. **Its result is this app's own bind attempt and never proves
   AICore's access to PCS.** Every row separates binding (observed) from RPC authorization
   (`not tested: no transaction is sent`) and provisioning (`not tested`), and is marked
   **current run**, **earlier run** with its time, or **not run**. Observations are kept in the
   app's private storage, newest last and at most five per component, with the wall-clock time,
   app version and PCS versionCode; a later `NOT RUN` never replaces an earlier observation. A
   stored row from a different PCS versionCode is labelled and is never classifier input.
6. **Private Compute Services → launch controls.** **Open PCS settings** and **Open Network
   usage log** are separate explicit taps, shown only when the installed package reports the
   activity as exported, effectively enabled and permission-free. The item-details activity is
   listed but not launched because its required extras are not publicly known; none are
   guessed. Each tap records resolved/launched, `ActivityNotFoundException`,
   `SecurityException` or another error. Nothing is read back from another app's screen.
7. **Provisioning Diagnostics → Run Full Diagnostics and export.** Do this **after** the bind and
   launch steps so the snapshot contains them, or tick **Include PCS bind probe** beside the
   button to run the bind first inside the same action. The option is unchecked by default;
   ticking it and tapping Run is consent for that one run only and is never remembered. Tap
   **Run Full Diagnostics**, wait for `Report ready`, then **Export JSON** and **Export text**
   (a Storage Access Framework file picker, or the export directory configured in Settings).
   The same action reads the installed AICore and PCS `classes*.dex` files once, off the main
   thread, and evaluates the provisioning chain the public source trace laid out. The report
   card names the first step shown to fail, or says it cannot be determined, and lists every
   earlier step that is not passed. Every step is `passed`,
   `failed`, `not observable` or `not tested`, with its evidence and that evidence's strength
   (`observed`, `inferred`, `dump only`, `source trace`). A July 2025 public-dump fact never
   decides a step, a step that cannot be observed is never rendered as passed or failed, and a
   flag step is `failed` only from all four of: the marker present and loaded by code, one
   established literal default of the right type, the `READ_DEVICE_CONFIG` check found beside
   it, and the permission observed denied. A flag default is evaluated per exact flag name:
   every default site of that name must carry the same single literal; a flag passed with no
   parameter after it is shown as "no following argument" and is not a default site; an
   unresolved or mixed site leaves the step not observable. A literal boxed with
   `Integer.valueOf`/`Boolean.valueOf` or read from `Boolean.TRUE`/`Boolean.FALSE` counts as
   the literal it boxes when the parameter after the flag is `Integer`, `Boolean` or
   `Object`; a bare prefix used as the receiver of `String.concat` whose result flows
   straight into the next call is the run-time key (`AicDataRelease__build_id_<runtime
   suffix>`), not a separate default. A truncated reference list leaves a flag step not
   observable. That the denied permission makes the flag read its default is exported as
   inferred, because the check is found in the package, not on each flag's read path. A
   marker with no referencing method is "in the string table, loaded by no instruction". A string-table match is not proof the code uses
   it, and an installed-code fact that contradicts the dump is exported as a contradiction.
   The association step compares AICore's signer only with the policy entry named for AICore,
   needs AICore's own complete manifest (no policy, or a PCS entry matching PCS's signer), and
   reports a PCS-side mismatch as inferred because the enforcement flag is not published.
   Assets are listed as a candidate non-DeviceConfig input only: presence is not use.
   The JSON report contains:
   the report schema and generation time; the app identity; device facts; the full permission
   matrix with *requested*, `checkPermission`, the `requestedPermissionsFlags` bit and the
   `getPermissionInfo` declaration for every matrix cell; a permission-verification block with
   **every requested permission for AICore and PCS**, while other packages keep only AICore
   `BIND_SERVICE`, `QUERY_ALL_PACKAGES` and the matrix columns; every GenAI feature row with
   `probeExecuted` and `featureAvailable` kept separate (including an explicit `NOT PROBED` row
   when no result exists); the four Prompt configs on the same basis; the retained summarization
   probe; the PCS surface and launch targets; every bind row with its provenance (`current run`,
   `earlier run at <time>`, or `not run`), the observed binding fact, `RPC authorization: not
   tested: no transaction is sent` and `provisioning: not tested`, plus bind flag, outcome,
   guarded descriptor, binder label, exception, elapsed time and unbind count (including
   `descriptorTimedOut`); the note when stored history was missing or unreadable; the launch
   results; the installed-manifest
   facts for AICore, PCS and ASI as the installed-manifest reader decoded them (declared package, signer
   SHA-256, declared and used permissions, `android:privateComputeCore` components, the
   component-access/association policy and its package names and certificate digests, and
   isolated/process attributes); the
   six-way classifier result with every evidence item's observed/inferred strength, what would
   refute it and, when INDETERMINATE, the candidates with the missing observation; the
   installed-code facts for AICore and PCS (outcome, versionCode, APK SHA-256, dex entry
   names, each allowlisted marker's presence and referencing-method count, the literal
   defaults, the `READ_DEVICE_CONFIG` check fact, `DeviceConfig` method names, asset entry
   names and sizes, and any dex failure class/message, with the elapsed time per package) and
   the ordered `provisioningDependencies` evaluation (every step's outcome, summary and
   evidence with its strength, the first step shown to fail, every earlier step not passed, and
   the earliest step the evidence cannot settle); the stored bind history per component (at
   most five, each with its time, app version and PCS versionCode) beside the current bind
   rows; the offline
   inference matrix if it has run; package versionCode and signer SHA-256 for AICore, PCS, ASI
   and GMS; the relevant `getSystemAvailableFeatures` names; the explicitly untested
   operations; and the diagnostic event log. `BIND_SERVICE` rows carry the holder's
   first-install and last-update times beside AICore's first-install time. If a bind or launch
   finishes after the report was generated, the report card says the report predates it and
   the export buttons stay disabled until you run Full Diagnostics again. The action performs
   no download, and it binds only when the include-bind-probe option was ticked.
8. **What to attach back.** The Provisioning Diagnostics JSON export (and the text export if
   convenient); the Diagnostic Logs JSON export if you want the event stream; the AICore and PCS
   versions from Overview; the permission matrix (already in the JSON export); the
   `provisioningDependencies` result and every step's outcome and evidence; the installed-code
   facts and the elapsed time per package; the classifier's `result`, `summary` and
   `candidates` fields; the per-feature and per-Prompt rows; the installed-manifest evidence;
   the current bind outcomes and the stored bind history; the launch outcomes; and the
   offline matrix with a note confirming you disabled connectivity yourself.
9. **Known platform limit.** On GrapheneOS the summarization probe reports
   `Feature 622 is not available` (`3-PREPARATION_ERROR` / `606-FEATURE_NOT_FOUND`): AICore has
   no provisioning entry for feature 622. Feature 622 is the AICore AIDL constant
   `SAPI_SUMMARIZATION_EN_2` (decompiled source, not a model); the model is unknown. No rootless
   app can insert that manifest into AICore — this app reports the state and does not fix it, and
   a Prompt `606` with a different feature id does not change that.

### How to read the failure classification

The **Decision** card's failure-class line is exactly one of: `missing feature configuration`,
`PCS initialization failure`, `PCS service authorization failure`,
`remote provisioning rejection`, `model registration failure`, `NPU access restrictions`, or
`INDETERMINATE`. The report's `provisioningClassifier` object carries the same result with
every evidence item marked `observed` or `inferred`, the observations that would refute it,
and for INDETERMINATE the still-open candidates each with its missing observation.

- **INDETERMINATE is the expected answer on this phone.** `606` is documented as
  configuration-not-downloaded or an unlocked bootloader, and the July 2025 public PCS dump
  that refuses `GetManifestConfig` with “feature disabled” was not read on this build. The
  app cannot send that RPC, so missing feature configuration and PCS service authorization
  failure stay candidates. The JSON export names both.
- **`READ_DEVICE_CONFIG` denied supports but does not prove that PCS gate**, and granted does
  not refute it: an unwritten DeviceConfig namespace leaves the same default.
- **This app's own bind outcomes are evidence, not selections.** PCS's policy lists AICore and
  not this app, so a `SecurityException` here is expected on a healthy device; a timeout looks
  like a slow cold start; a null binding, a died binding and the “no caller check” reading come
  from a source mirror rather than this PCS. Only a gRPC service the installed PCS declares but
  does **not effectively enable** selects PCS initialization failure; only a service declared
  but **not exported** selects PCS service authorization failure, and only when PCS and AICore
  are not established to share a UID. Everything else, including `BIND_REFUSED` and
  `BIND_ERROR`, stays a candidate.
- **A declared association policy is not proof of enforcement.** A mismatch selects only from a
  complete PCS manifest whose matched policy elements all returned readable package names and a
  visible installed AICore package.
- **ML Kit's wrapper is stripped before the message rules run.** Only the text after the
  wrapper is matched: an optional `[ErrorCode n]`, then
  `AICore failed with error type …-… and error code …-…:`. The exported evidence keeps the
  full message.
- **NPU access restrictions are never selected from a denied `ACCESS_NPU_MODEL_MANAGER_API`,
  `USE_ON_DEVICE_INTELLIGENCE` or `MANAGE_VIRTUAL_MACHINE` grant.** The same clause must name
  the NPU **and** an access denial, refusal or failed access. Bare “fail”, “cannot” and
  “unavailable” do not count, and an NPU name alone is evidence only.
- **A local “not eligible” message without a whole-word server, remote, backend or cloud source
  is a candidate**, because a local eligibility read looks the same. Source words match as whole
  words, so `RemoteException` and `system_server` do not count; a rejection with only `provision`
  or `download` in the clause is a candidate too.
- **A `401` or `403` counts only as an HTTP or status code.** `HTTP 403`, `HTTP/1.1 403`,
  `status 403` and `status code 403` satisfy the remote rejection rule; a bare number such as
  “cloud feature 403 is pending” does not.
- **Negated failure words do not count.** “not denied”, “not rejected” and “not missing” do not
  satisfy the NPU, remote, configuration or registration rule.
- **A generation that succeeded conflicts with any selected class** and makes the result
  `INDETERMINATE`; the success is listed as evidence.
- **The classifier uses only the gRPC bind row** (`AstreaGrpcService`/`PcsGrpcService`). A
  null binding of `GenAiInferenceService` is not evidence, the app's own bind never stands in
  for AICore's, and an empty or disabled PCS network log is not evidence for any class.
- **`BIND_SERVICE` denials for GMS and Gboard are not evidence for any class.** Their
  install times are shown only so the install-order artifact is visible; do not reinstall
  Play services to change a grant.
- **Next observation.** For a persistent `606`, update AICore, restart the phone, and probe
  again. If it persists, the cause is at the manifest/feature gate or a remote response the
  app cannot send. Re-run **Run Full Diagnostics** and attach the new JSON export.

## Sections

| Section | State |
| --- | --- |
| Overview | Implemented: AICore/PCS presence and version, real summarization probe, most recent failure, decision-tree recommendation, retained summarization test |
| System Components | Implemented: version, installer, signing SHA-256, SDK levels, requested permissions, observable grants, declared/exported components, metadata, probed intent actions |
| AICore Features | Implemented: independent Prompt probe over the four `ModelConfig` combinations (`STABLE/FAST`, `STABLE/FULL`, `PREVIEW/FAST`, `PREVIEW/FULL`) plus the full public GenAI matrix — Rewriting, Proofreading, Image Description, Embedding, Agent, Speech Recognition, Speech Translation and Text to Speech. Each row is an isolated probe with status, feature id, error class/code/message, base model name or signature only when returned, latency, and a download button only when ML Kit says DOWNLOADABLE. The retained summarization status is shown separately |
| Private Compute Services | Implemented: requested-vs-granted permission matrix, exported surface with type/permission/enabled/actions and visibility limits, and safe `bindService` outcomes for the services the installed PCS declares (the current `astrea` names first; the open-source names are aliases). Bind observations persist locally (last five per component) and each row is marked current run, earlier run with its time, or not run, with binding, RPC authorization and provisioning kept as three separate facts. PCS settings and the network usage log have explicit launch controls; the item-details activity is listed but not launched. PCS settings are opened by explicit component resolution, not an `ACTION_APPLICATION_PREFERENCES` intent |
| Model Manager | Implemented: per-feature observed status, explicit download only when DOWNLOADABLE, progress from the public callbacks, retry after failure, no catalog, no automatic download, no invented cancel |
| Provisioning Diagnostics | Implemented: the evidence blocks (matrix, feature statuses, Prompt, PCS surface/bind, installed-manifest evidence, installed-code facts, decision), the six-way provisioning failure classifier with INDETERMINATE and the missing observation, the ordered first-step-shown-to-fail evaluation over the traced provisioning chain, the exact feature-622 label, and one **Run Full Diagnostics** action producing an exportable JSON + text report. Its **Include PCS bind probe** option is unchecked by default, applies to that run only and is never remembered; ticked, the bind finishes before the report is built. The installed-code read happens inside that same action, off the main thread; one package's failure never fails the report |
| Offline Inference | Implemented: one short fixed-input inference per AVAILABLE feature with exact failures and latency; the user must disable connectivity externally and no offline success is claimed from this app alone |
| Privacy | Implemented |
| Diagnostic Logs | Implemented: bounded local event bus, search/filter, copy, JSON and text export through SAF, redaction |
| Settings | Implemented: theme, SAF export directory, log verbosity, reset probes, a clear control for the stored PCS bind history, provenance |

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
  descriptor read (on a worker thread) and its own timing, binder-alive state, exception,
  disconnects and elapsed time. It sends no binder transaction, builds no AIDL stub, calls no
  `asInterface` and uses no transaction code. Observations persist in app-private storage, at
  most five per component, with their wall-clock time, app version and PCS versionCode; each
  row is marked current run, earlier run with its time, or not run, and shows binding
  (observed), RPC authorization (`not tested: no transaction is sent`) and provisioning
  (`not tested`) separately. A stored row from a different PCS versionCode is labelled and is
  never classifier input; corrupt or missing storage yields no history and an explicit note.
  Job-bound services are static facts only. Launch controls for PCS settings and the network
  usage log are explicit taps; the item-details activity is not launched because its extras
  are unknown.
- **Feature matrix.** Eight independent clients over the public SDKs: Rewriting,
  Proofreading, Image Description, Embedding, Agent, Speech Recognition, Speech
  Translation and Text to Speech. Each probe records only what its own client returned;
  one failure never marks another feature. Speech uses file/fixed-buffer input only, so
  no `RECORD_AUDIO` is requested. Embedding reports the model signature and vector
  dimension the API returned. Agent runs over the public `Agent`/`AgentRunner` API with
  app functions disabled.
- **Failure classification.** A pure classifier over the observed evidence returns one of the
  six provisioning failure classes or `INDETERMINATE`. Each result lists the evidence used with
  its observed/inferred strength and what would refute it; `INDETERMINATE` names the candidates
  and the missing observation. It never selects NPU access restrictions from a denied
  permission, never treats a 606 alone as a separated cause, and never uses the app's own PCS
  bind as a stand-in for AICore's access. The same result is in the UI decision card and in the
  JSON and text exports.
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
  permission matrix, reads the installed AICore and PCS dex code off the main thread,
  evaluates the ordered provisioning chain, probes every GenAI feature status, the four
  Prompt configs and the retained summarization probe, records the PCS surface, includes
  stored bind rows with their provenance and the stored bind history, and builds JSON and
  text reports through the existing SAF export path. An unchecked-by-default **Include PCS bind probe** option applies to that one run
  only and is never remembered; when ticked, the same bind probe runs and finishes before
  the report is built. Unticked, the action performs no bind. Each probe is isolated and bounded by a 30-second timeout. The report separates
  "probe executed" from "feature available" for every feature and Prompt row, lists every
  explicitly untested operation, names the AICore/PCS/ASI/GMS versionCode and signer
  SHA-256, includes the relevant `getSystemAvailableFeatures` names, and keeps full
  permission enumeration to AICore and PCS. Bind rows carry the holder's first-install and
  last-update times beside AICore's first-install time. It performs no download.
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
  updated. Only the public ML Kit APIs are used; there is no privileged access,
  bootloader or verified-boot change, and no hidden or system API.
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
