# WhatsApp Call Recorder: blocker review

**Updated**: 2026-10-21. Scope: the 75 v1.0 tasks in the Azure task list, not all 149 spike/application/later-release tasks.

**Snapshot**: 66 locally complete, 6 blocked, 2 pending (Settings lock toggle and owner design review), 1 owner-deferred CI task. Local completion is not phone acceptance or release sign-off. The original spike remains available alongside the actual app.

Reference documents: `2026-10-19_whatsapp-call-recorder_azure-task-list.md` and `2026-10-18_whatsapp-call-recorder_technical-design.md`, both in this directory.

## Blocked tasks

| Blocker | Task IDs | Missing input |
|---------|----------|---------------|
| Positive group detection and captured golden test | `2.2.1.2.1`, `2.2.1.2.2` | Redacted real group-call structural evidence |
| Group participant volume verification | `3.2.2.1.2` | Controlled physical three-party audibility/level measurements |
| Baseline Profile integration | `8.2.2.1.2` | Actual generated profiles, subsequent packaging checks and F42 frame results |
| Device performance and accessibility execution | `8.2.2.2.2` | Actual F42 startup/frame results and A73 automated/manual accessibility results |
| Library filter latency | `11.1.1.2.2` | Actual F42 benchmark report |

These five underlying blockers account for six task rows. Harness preparation does not close a task that explicitly requires real device evidence. No such result is fabricated from host tests.

## Detailed review, including resolved decisions

### 1. App lock: stop playback, not call capture

**Owner preference recorded on 2026-10-21**: when listening to a saved recording and the screen locks, stop playback rather than continue listening. This concerns playback of existing audio, not stopping an active WhatsApp call recording.

The original design separately specifies optional app authentication, disabled by default, with a **60-second background relock**. Home, Library, Player, Allowlist and Settings are protected; onboarding stays open. Strong biometrics or device credentials unlock the app. `FLAG_SECURE` and recording independent of UI authentication remain required.

**Resolved**: stop-versus-continue playback. A locked app must not leave saved-recording playback usable through its service, notification or media controls.

**Timing resolved on 2026-10-21**: owner confirmed immediate stopping on phone lock. With optional app lock enabled, screen off immediately revokes authentication and stops playback; ordinary backgrounding retains the 60-second grace period. Active call capture is not stopped.

**Engineering status**: task `11.3.1.1.1` is locally implemented and verified: native strong-biometric/device-credential gate, fail-closed preference/error states, protected navigation and real playback service/session shutdown. Authentication is process-local and stale callbacks cannot unlock after relock. The default-off persistent preference exists; the Settings toggle (`11.3.1.1.2`) follows as the next separate task/commit. Real authentication/lockscreen behavior still requires both phones; no phone pass is claimed.

### 2. Captured group-call detection

**Already implemented**: individual-call detection, explicit confidence handling, encrypted session/group fields, and shared group-tagged capture plumbing. The retained positive native profile covers outgoing voice on the captured A73/WhatsApp build; it does not establish group selectors. Synthetic group persistence tests are not a captured group golden.

**Owner status**: group tests have not yet been performed, confirmed on 2026-10-21.

**Owner action**: use consenting test participants for a real three-party WhatsApp call. In the retained spike, enable **Dump call-screen view tree each second**, visit the call and participant screens, and retain the device/WhatsApp build information. Ideally collect on both phones; positive evidence from a supported profile is needed before implementing its selectors.

For the structural export, leave **Hide names & numbers** enabled and **Include audio files** / **Include screenshots** disabled. Use **Save logs** or **Share logs** and review the archive before sharing: do not assume redaction catches every unfamiliar participant label or private tree value. Share only the needed redacted structural evidence, not call content.

**Next engineering action**: derive group/name/participant selectors from the captured structure, implement extraction without guessing or promoting unknown confidence, then add the positive captured golden and encrypted persistence assertions in a separate task.

### 3. Three-party volume and audibility

**Already implemented**: one shared encrypted recording pipeline, aggregate PCM level measurement, and guided per-route capability tests with separate You/Other speaking windows.

**Missing**: actual three-party measurements and listening observations. The current input is mixed mono; aggregate RMS cannot identify individual participants or prove that every voice is audible.

**Owner action**: with consenting participants and one voice at a time, run two guided 30-second tests on the same route/source: You versus remote A, then You versus remote B, with the third participant silent. Retain the run times, device/route/source, displayed measurements, private listening observations and redacted diagnostics. Repeat on the other supported phone. Do not run the spike recorder and actual app recorder simultaneously.

**Next engineering action**: interpret only the cued windows supported by the measurements, document any imbalance and capture limitations, and adjust the selected route profile only when the evidence supports it. No separate participant channels or automatic gain correction are claimed.

### 4. Real Baseline Profile

**Already implemented**: isolated benchmark target, real encrypted synthetic Library fixture, startup/navigation journeys and a profile generator. Native status/list motion is already implemented.

**Owner action**: with only the intended unlocked API 33+ phone connected, run from the repository root:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :benchmark:connectedBenchmarkAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.soakula.wacr.benchmark.BaselineProfileGenerator'
```

Return that run's two generated `*-baseline-prof.txt` files and `*-startup-prof.txt`, together with device/build and APK identification. Keep the complete output directory so startup and navigation results cannot be confused with older runs.

**Next engineering action**: merge/de-duplicate the actual baseline files into `app\src\main\baseline-prof.txt`, copy the actual startup file to `app\src\main\startup-prof.txt`, rebuild and inspect merged ART inputs, app-rule inclusion and compiled APK profile assets. Then measure F42 frames and profile benefit separately. No generated profile or packaging result currently exists.

### 5. Actual startup, frame and accessibility checks

**Already implemented**: local native rendering/accessibility checks, a 42-state light/dark/font-scale matrix, physical-device benchmark tests and a fail-closed raw-result checker. Compilation and host results do not establish phone performance.

**Owner action**: with only the intended phone connected, run the matching command:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
# F42: ten cold launches and ten navigation/scroll runs.
.\gradlew.bat :benchmark:connectedBenchmarkAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.soakula.wacr.benchmark.PerformanceBenchmark'

# A73, API 34+: synthetic-screen Compose accessibility checks only.
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.soakula.wacr.ui.AccessibilityChecksTest'
```

The performance target uses isolated `com.soakula.wacr.benchmark` storage with synthetic metadata, not normal app/spike recordings. Do not enable its call-detection/capture permissions. The accessibility command targets the normal debug app with synthetic test screens; it is not a full phone acceptance run.

Retain unedited BenchmarkData JSON and Perfetto traces from `benchmark\build\outputs\connected_android_test_additional_output\benchmark`, plus actual model/build and APK identification. Check the selected F42 report:

```powershell
python -B .\tools\check_performance.py '<path-to-BenchmarkData.json>' --expected-model '<actual F42 Build.MODEL>'
```

Acceptance: every fully-drawn cold start `<=1,000 ms`; each navigation/scroll run has `<=5%` positive frame-deadline overruns. The checker requires complete ten-iteration samples and raw frame arrays; percentiles alone cannot pass. Exit 0 passes, 1 means measured failure, 2 means missing/invalid evidence.

Also retain the A73 accessibility test result and perform Accessibility Scanner/TalkBack, actual system 200% font-scale, scrolling and keyboard checks on both phones. The automatic Compose checks require API 34+, so they do not replace manual F42 checks on Android 13.

**Next engineering action**: investigate measured failures, make focused corrections and rerun the affected check. Only actual results can close this task; owner design approval remains separate.

### 6. F42 Library filter latency

**Already implemented**: a debug-only benchmark screen with 1,000 synthetic metadata recordings across 100 contacts, six filter cases and five repetitions per case. It does not seed real recording storage or create audio.

**Owner action**: install the current debug app on the selected F42, keep its normal font scale, open the benchmark and leave it foreground:

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb -s <F42-serial> install -r .\dist\app-debug.apk
& $adb -s <F42-serial> shell am start -n com.soakula.wacr.debug/com.soakula.wacr.ui.preview.LibraryBenchmarkActivity
```

Select **Run benchmark**. Return the displayed model/SDK, all 30 samples and median/maximum summary; repeat to distinguish cold/warm behavior. Maximum must be `<=200 ms`. The timer covers filter application through the corresponding content draw; it excludes input dispatch and GPU presentation. It is not the separate release-derived startup/frame benchmark.

**Next engineering action**: investigate any failure against the exact filter case and rerun. Host fixture correctness and timing-parser tests are not an F42 pass.

## Pending, deferred and resolved items

| Item | Task | State and action |
|------|------|------------------|
| App-lock Settings toggle | `11.3.1.1.2` | **Pending implementation, no owner decision needed.** The locally verified authentication/playback gate is ready; wire the persistent control next. |
| Owner design sign-off | `8.1.1.1.3` | **Pending, not an implementation blocker.** Review the generated local proposal gallery at `dist\mockups\light\index.html` and real-state gallery at `dist\ui-states\index.html`; record acceptance or requested revisions. Local rendering does not mean owner approval. |
| GitHub Actions / CI | `5.1.1.1.4` | **Deferred by owner.** Local builds and unit tests are the current workflow; no CI work or owner action needed now. |
| Dependency-security gate | `5.2.1.2.2` | **Locally complete; no longer a blocker.** Strict verification and the latest recorded scan cover ten exact selected graphs with zero high/critical findings. Two moderate build-tool versions remain documented below. |

The remaining moderate tooling findings concern `commons-compress 1.21` and `kotlin-gradle-plugin 2.2.20`. They do not invalidate the specified no-high/critical gate; this is not a claim of zero advisories or an exploitability assessment. Reproduction and scan details are in the technical design. Dependency verification is not pending on the owner.

Other real-phone acceptance, such as actual call audibility, route switching, low-storage native decoding and OEM background behavior, still applies even to locally complete implementation tasks. Milestone/UAT checkboxes must remain unchecked until their stated evidence exists. Later-release work is outside this v1.0 snapshot.
