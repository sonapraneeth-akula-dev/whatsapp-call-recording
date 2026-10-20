# WACR Spike: capture feasibility test app (REQ-001)

This is the retained capture/detection test app, separate from the production `:app` module. It answers one question on **your** phones (A73 on Android 16, F42 on Android 13): *can a non-root, sideloaded app record both sides of a WhatsApp call, and can it detect the call automatically?*

It records a few seconds with every Android audio source, logs every WhatsApp call signal it can see, and lets you share the logs back without USB or developer mode.

> Nothing leaves the phone unless you press **Share logs**. The app has no INTERNET permission.

## 1. Install (once per phone)

1. Copy `dist\spike-debug.apk` to the phone (Quick Share, Phone Link, Google Drive, or a WhatsApp "message yourself" chat).
2. Open it in **My Files** and install. If asked, allow installing from that app.
3. **"App blocked to protect your device"**: Play Protect in India blocks sideloaded apps that request Accessibility or Notification access, and gives no override. Use one of these:
   - **Turn Play Protect off for the install:** Play Store › profile picture › **Play Protect** › ⚙ › turn off **Scan apps with Play Protect**. Install, then turn scanning back on. If it later flags the app, choose Keep.
   - **Install over Wi-Fi with adb** (Play Protect doesn't block this):
     1. On the phone, enable Developer options (tap **Build number** 7 times), then **Wireless debugging** › **Pair device with pairing code**.
     2. On the PC, run `adb pair <ip:port> <code>`, then `adb connect <ip:port>`, then `adb install -r dist\spike-debug.apk`.
4. Open **WACR Spike** and make every row in **1. Setup** green:
   - **Microphone** and **Notifications**: tap *Allow*. **Contacts** is optional; it only helps hide names in shared logs.
   - **Allow restricted settings**: tap *App info*, then the **⋮** menu (top right), then **Allow restricted settings**. *Do this before the next two steps*, otherwise Android greys them out for sideloaded apps.
   - **Accessibility**: *Open*, then **Installed apps › WACR Spike call detector › On**.
   - **Notification access**: *Open*, then **WACR Spike › Allow**.
   - **Battery**: *Allow*, which sets the app to Unrestricted.

You do **not** need to install anything on the other person's phone. Install on both of your phones only because both are test devices.

## 2. One test call

1. In **2. Label**, pick the call type and direction, e.g. `voice` + `outgoing`, then tap **Start new run**.
2. Leave **Auto-sweep** on. Keep the recorder start mode as **Start service on call** for the first calls.
3. Make or receive the WhatsApp call. Tell the other person in advance that you will both take turns talking for about 3 minutes.
4. About 3 s after the call connects, the phone starts buzzing cues. Each audio source repeats this pattern:

   | Buzz | What to do |
   |---|---|
   | **1 buzz** | **You** talk (count, read anything) for the side length (10 s by default). The other person stays quiet. |
   | **2 buzzes** | Say "your turn" and go silent. **The other person** talks until the next cue. |
   | **3 long buzzes** | Sweep done. End the call whenever you like. |

   - There are 8 sources × about 20 s each, so about 3 minutes in total. Sources the phone blocks fail instantly and are skipped.
   - The notification shows which source is currently being tested.
5. **Ask the other person** whether they ever stopped hearing you, and at roughly which minute. The app cannot see that from your phone; note it in your message to Copilot.
6. When the call ends, open the app. **4. Results** shows the matrix (✓ = that side was audible in that recording).

## 3. What to cover

Record one run per row, on **both phones**. Switch the audio route during the call **before** the sweep starts, or switch mid-call: the app re-sweeps automatically when the route changes.

| # | Call | Route | Recorder start mode |
|---|---|---|---|
| 1 | voice outgoing | earpiece | Start service on call |
| 2 | voice incoming (app in background, screen off before it rings) | speaker | Start service on call |
| 3 | voice | Bluetooth headset/buds | Start service on call |
| 4 | voice | wired earphones (if you have them) | Start service on call |
| 5 | video | speaker | Start service on call |
| 6 | group voice (3+ people) | any | Start service on call |
| 7 | missed call (do not answer) | – | – (checks detection only) |
| 8 | repeat #2 if it showed `fgs/start_failed` | speaker | **Keep a Ready service running** (tap *Start Ready service* first) |
| 9 | repeat #2 if #8 also failed | speaker | **No service — record inside accessibility** |

Tips:
- No call partner handy? **Sweep now** during any WhatsApp call does the same thing manually.
- Use 5 s per side if 10 s feels long. Screenshots are off by default; turn them on only if Copilot asks.
- Also fill in [`SCREEN_RECORDER_CHECKLIST.md`](SCREEN_RECORDER_CHECKLIST.md), a 5-minute baseline using Samsung's own recorder.

## 4. Send the logs back (no USB)

1. In **5. Logs**, keep **Hide names & numbers** on.
   - Leave **Include audio files** off unless Copilot asks. Audio is the actual call content.
2. Tap **Share logs** and send the zip to your PC (Quick Share, Phone Link, Drive, email to yourself).
   - Or use **Save logs**, which writes to `Download/WACR-logs/` on the phone.
3. On the PC, drop the zip into `spike-logs\` in this repo (git-ignored) and run:

   ```powershell
   python tools\summarize_spike.py spike-logs\WACR-logs_<model>_<time>.zip
   ```

4. Tell Copilot: "*analyse spike-logs\…zip*", plus anything the other person noticed (step 2.5).

After both phones are done, use **Delete old runs** to free space.

## What the app logs

| Area | Logged | Not logged |
|---|---|---|
| WhatsApp notifications | Category, channel, call type, actions. Caller name only for **call** notifications. | Message text or sender of chat notifications |
| Accessibility | Activity/window class names. Call-screen text and view tree **only while a call screen is shown**. | Chats, any other app |
| Audio | Mode, route, devices, who is recording, own silenced state, 1 s level ticks, WAV per source | Nothing is uploaded |
| Device | Model, Android/One UI version, WhatsApp version, permission state | IMEI, account, location |

Shared zips replace saved contact names and `+` phone numbers with stable hashes such as `name#3fa2c1` when **Hide names & numbers** is on.

## Where it lives

- Production shell/backend: `app\src\main\kotlin\com\soakula\wacr\`; capture, encryption, metadata, recovery and a private microphone foreground service are connected through a single-recording controller. The service awaits recovery, posts its timer/Stop indicator and preserves interrupted ciphertext. No detector, permission flow, manual entry point or product screens are connected yet; the launcher still cannot start recording. Both apps reuse JSON/redaction helpers from `:logging` and unchanged RMS utilities from `:audio`; spike capture behavior, settings and saved runs are preserved.
- Separate install identities: **WACR Spike** = `com.soakula.wacr.spike`, **WACR Debug** = `com.soakula.wacr.debug`, release **WACR** = `com.soakula.wacr`. Installing the app does not replace the spike.
- Review-only UI proposals: open `app\src\debug\kotlin\com\soakula\wacr\ui\preview\MockupGallery.kt` in Android Studio's Compose preview with the debug variant. Sample data only; not connected to the launcher or recorder. Owner approval is still required before product UI implementation.
- Rendered review gallery: open `dist\mockups\light\index.html`, then switch to dark, 200% text or landscape using its links. Seven screens, top/end snapshots; not full-page captures or a functional recorder. Regenerate with `:app:testDebugUnitTest --tests '*MockupRenderingTest'`; source output is `app\build\mockups\`.
- Local dependency integrity: `gradle\verification-metadata.xml` enables strict SHA-256 checks; the wrapper distribution hash is pinned separately. Run the normal local builds/tests, or pass `--dependency-verification strict` explicitly. After adding dependencies, review the upstream artifacts and generated checksum changes before committing; never disable verification to work around a mismatch. This initial metadata covers the current Windows build, not every future platform/variant.
- Dependency scan status (2026-10-20): OSV-Scanner 2.7.0 found no advisory matches in the resolved app runtime, but high/critical build/test-tool findings remain. Raw public-coordinate results and scope inventory: `dist\dependency-scan.json`, `dist\dependency-inventory.json`. This is not a clean security gate; see technical design §11. Re-run a coordinate-only scan with `osv-scanner scan source --lockfile gradle\verification-metadata.xml --no-resolve --format json --output-file <report.json>`; do not substitute a recursive source scan.
- Source code: `spike/src/main/kotlin/com/soakula/wacr/spike/`
- Logs on the phone: app-private `files/runs/<time>_<label>/` containing `events.jsonl`, `device.json`, `matrix.md`, `audio/*.wav`, `tree/*.json`, `shots/*.jpg` and `crash.txt`.
- Build:

  ```powershell
  $env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'; .\gradlew :spike:assembleDebug :spike:testDebugUnitTest
  ```

- Build both apps and check their packaged identities/privacy defaults:

  ```powershell
  $env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
  .\gradlew :app:verifyNoInternet :app:assembleDebug :app:assembleRelease :app:lintDebug :app:testDebugUnitTest :spike:assembleDebug :spike:testDebugUnitTest
  .\tools\test-privacy-guard.ps1
  .\tools\test-app-shell.ps1 -SdkPath "$env:LOCALAPPDATA\Android\Sdk"
  ```

  Production debug APK: `app\build\outputs\apk\debug\app-debug.apk` (delivery copy: `dist\app-debug.apk`). Release APK is unsigned until personal signing is configured; do not install it as-is. Microphone, notification and microphone-foreground-service permissions are declared. The private backend can record only through its in-process controller after permissions are granted; the placeholder launcher has no start/permission controls. Continue using the separate spike for current phone capture tests.

  Every production APK build runs the merged-manifest network guard automatically. The scripts also check backup/transfer exclusions in the packaged resources; CI is intentionally deferred.

### Deviations from the task list

- WAV only, no m4a.
- Also tests `VOICE_UPLINK` and `UNPROCESSED`.
- One APK covers both M-001.1 (audio sources) and M-001.2 (call signals).
- The adb pull script was dropped because the in-app Share replaces it.
