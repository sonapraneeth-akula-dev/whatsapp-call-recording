# Discovery: WhatsApp Call Recorder (personal, on-device)
**Date**: 2026-10-17 | **Status**: Approved (2026-10-18) | **Owner**: soakula

## 1. Idea Summary
An app that records WhatsApp calls — audio by default (both sides, or all participants in a group/conference call), video optionally (user chooses audio-only or audio+video). Distributed as a local APK only (no Play Store). Android first, then WhatsApp Desktop on Windows / WhatsApp Web. Recordings stay on the device and are never shared with anyone. Some contacts can be marked "always record" so recording starts automatically.

## 2. Problem & Motivation
- WhatsApp has no built-in call recording on any platform.
- Important details from calls (instructions, agreements, family/medical/financial conversations) are lost or misremembered.
- Existing Android recorders are unreliable for VoIP after Android 10 and the 2022 Play Store policy; many upload data to vendor clouds.
- Cost of doing nothing: continued reliance on memory/manual notes; no trustworthy local-only option.

## 3. Users & Stakeholders
| Role | Needs | Technical level | Permissions |
|------|-------|-----------------|-------------|
| Owner (primary user) | Reliable auto/manual recording, private local storage, easy playback | High (developer, can sideload) | Full: settings, recordings, allowlist |
| Other call party | Privacy, legal protection (consent) | Any | None in app; may need to be notified per local law |

## 4. Goals & Success Criteria
- **Goal**: Calls with allowlisted contacts are recorded without user action — **Measured by**: ≥ 95% of allowlisted calls produce a recording file over a 2-week trial.
- **Goal**: Recording includes both sides where the device allows it — **Measured by**: per-device capability report; on supported devices, remote party intelligible in ≥ 90% of recordings (manual spot check).
- **Goal**: Zero data leaves the device — **Measured by**: app ships without `INTERNET` permission; network-traffic audit shows 0 bytes sent by the app.
- **Goal**: No call quality impact — **Measured by**: no user-perceived degradation / dropped calls attributable to the app over trial.

## 5. Scope
### In Scope
- Android app: detect WhatsApp voice/video calls, record audio (and optionally screen video), save locally.
- Per-call and global mode choice: **Audio only** (default) or **Audio + Video**.
- "Always record" contact allowlist; manual start/stop for everyone else.
- Local library: list, play, rename, tag, delete, search by contact/date.
- Device capability check: tell user which audio sides are capturable on their phone.
- Advanced: post-call on-device speech-to-text for mixed Hindi/Telugu/English calls (romanized transcript, no translation).
- Phase 2: Windows (WhatsApp Desktop) recorder. Phase 3: WhatsApp Web (browser extension).

### Out of Scope (Non-Goals)
- Cloud backup/sync or any sharing feature — _Rationale: explicit requirement that files are never shared._
- iOS — _Rationale: OS blocks third-party call/screen audio capture of other apps; not requested._
- Google Play Store distribution — _Rationale: owner decision (local APK only); also Play policy bans Accessibility-API call recording._
- Regular cellular (SIM) call recording — _Rationale: not requested; different APIs/legal posture._
- Cloud/online transcription or translation services (e.g. Google Cloud STT, Sarvam API) — _Rationale: would send audio off-device; violates no-sharing requirement._
- Modifying/injecting into the WhatsApp app (mods, Xposed hooks) — _Rationale: violates WhatsApp ToS, risks account ban._
- Rooting the phone or installing as a privileged/system app — _Rationale: owner decision (2026-10-17), not acceptable._

## 6. Key Scenarios / User Journeys
### Auto-record an allowlisted contact (audio)
"Mom" is on the allowlist. She calls on WhatsApp; user answers. Recording starts automatically, a persistent indicator shows it's active. Call ends → recording stops, file saved, notification "Recorded 12m with Mom" offers Play.

### Manual record a non-allowlisted call
App detects a WhatsApp call with a colleague (not allowlisted) and shows a notification "Record this call?". User taps Record; recording starts mid-call; stops automatically at call end or when user taps Stop.

### Any audio route, no speakerphone
User takes calls on the earpiece, wired headphones, or a Bluetooth headset, and may switch between them mid-call. Recording continues without the user changing anything or turning on speaker; both sides captured.

### Video call with audio+video mode
User's global mode is Audio + Video. A video call starts with an allowlisted contact. App auto-detects it and shows Android's screen-capture prompt; user taps Start once (owner-accepted); screen + audio recorded. If user dismisses the prompt, app falls back to audio-only and notes it.

### First-run setup & capability check
User installs APK, grants required permissions with plain-language explanations, runs a 30-second test call (earpiece, then Bluetooth if available); app reports "Your voice: ✓/✗, Other party: ✓/✗" per audio route.

### Review and clean up
User opens library, filters by contact, plays a recording, renames it, deletes old ones. Storage usage visible; optional auto-delete after N days.

### Windows (phase 2)
User runs the desktop companion; it detects a WhatsApp Desktop call and records WhatsApp's audio output + microphone into a local file, applying the same allowlist.

## 7. Functional Requirements
| ID | Requirement | Priority | Notes |
|----|-------------|----------|-------|
| FR-001 | The system shall automatically detect start and end of WhatsApp voice calls on Android (incoming and outgoing) without user action. | Must | Owner expects fully automatic operation |
| FR-002 | The system shall detect start and end of WhatsApp video calls on Android and distinguish them from voice calls. | Must | |
| FR-003 | The system shall record the local user's voice during a call without requiring speakerphone. | Must | Ordinary apps get silence during calls; needs accessibility-service capture (see §9) |
| FR-004 | The system shall attempt to capture all remote participants' audio (one party, or all parties in a group call) using the best method the device supports and record which method was used. | Must | Best-effort; OS limits — see §10/§12 |
| FR-005 | The system shall offer a speakerphone fallback mode when remote audio cannot be captured internally. | Could | Owner finds speakerphone awkward; revisit only after spike results |
| FR-034 | The system shall capture both/all sides regardless of audio route (earpiece, loudspeaker, wired headset, Bluetooth headset) and survive route changes mid-call. | Must | Owner requirement; feasibility per route verified in spike |
| FR-006 | The system shall let the user choose a recording mode: Audio only (default) or Audio + Video. | Must | Global setting |
| FR-007 | The system shall allow overriding the recording mode per allowlisted contact. | Should | |
| FR-008 | In Audio + Video mode, the system shall record the screen with audio during video calls. | Should | Requires per-session OS consent prompt |
| FR-009 | The system shall fall back to audio-only and log the reason if video capture consent is denied or unavailable. | Must | |
| FR-010 | The system shall let the user maintain an "Always record" list of contacts. | Must | |
| FR-011 | The system shall automatically start recording when a call with an allowlisted contact begins. | Must | |
| FR-012 | The system shall let the user manually start and stop recording during any WhatsApp call. | Must | Notification action / quick tile |
| FR-033 | When a non-allowlisted call is detected, the system shall show a notification offering one-tap Record. | Should | Auto-detect everything; auto-record only allowlist |
| FR-013 | The system shall show a persistent, visible indicator while recording is active. | Must | Also OS-required (foreground service) |
| FR-014 | The system shall save each recording locally with metadata: contact, direction, call type, start time, duration, capture method. | Must | |
| FR-015 | The system shall provide a library to list, play, rename, and delete recordings. | Must | |
| FR-016 | The system shall filter/search recordings by contact and date. | Should | |
| FR-017 | The system shall store recordings in app-private storage not readable by other apps or visible in gallery/media scanners. | Must | |
| FR-018 | The system shall never transmit recordings or metadata off-device. | Must | No `INTERNET` permission |
| FR-019 | The system shall require device authentication (biometric/PIN) to open the library. | Should | |
| FR-020 | The system shall allow user-initiated export of a single recording to a user-chosen local location. | Could | Conflicts with "never shared"? — open question |
| FR-021 | The system shall auto-delete recordings older than a user-configured retention period. | Could | Off by default |
| FR-022 | The system shall run a guided capability test and report which audio sides are capturable on this device. | Should | |
| FR-023 | The system shall preserve partial recordings if the app crashes or call drops mid-recording. | Must | Data-loss protection |
| FR-024 | The system shall warn and stop gracefully when free storage falls below a threshold. | Must | |
| FR-025 | The system shall offer a one-tap way to notify participants that the call is being recorded. | Won't | Owner decision: no consent/notification flow (India, personal use) |
| FR-026 | The system shall record WhatsApp Desktop calls on Windows (app audio + microphone). | Should | Phase 2 |
| FR-027 | The system shall record WhatsApp Web calls from the browser tab (tab audio + microphone, optional video). | Could | Phase 3 |
| FR-028 | The system shall apply the same allowlist on desktop/web as on Android. | Won't (v1) | Would need sync → conflicts with no-sharing; keep per-device lists |
| FR-029 | The system shall transcribe a recording to text fully on-device after the call (not live), handling speech that mixes Hindi, Telugu, and English within the same call and sentence. | Should | Languages v1: hi, te, en only. Offline only (NFR-003) |
| FR-030 | The system shall record group/conference calls as a single file containing the local user and all remote participants. | Must | Same capture limits as FR-004 |
| FR-031 | The system shall auto-record a group call when the group (or any identifiable participant) is on the allowlist. | Should | WhatsApp may expose only group name, not participants |
| FR-032 | The system shall show a one-time legal notice at setup that the user is responsible for obtaining consent where required. | Won't | Single owner, single device; owner has accepted responsibility |
| FR-035 | The system shall provide a guided onboarding that walks the user step-by-step through every required setting (permissions, Restricted settings, accessibility service, Unrestricted battery, notification access) with a visual checklist and deep links to each Settings screen. | Must | Setup is the hardest UX moment |
| FR-036 | The system shall show a home dashboard with recorder status (ready / recording / needs attention), recent recordings, and storage used. | Must | |
| FR-037 | The system shall provide a player with waveform, seek, 0.75×–2× speed, and skip ±10 s. | Should | |
| FR-038 | The system shall group recordings by contact with avatar/initials and show call type, direction, duration, and date at a glance. | Should | |
| FR-039 | The system shall support light, dark, and follow-system themes, using Android dynamic color (Material You) where available. | Must | |
| FR-040 | The system shall alert the user (in-app banner + notification) when a required setting is revoked (e.g. accessibility service disabled after update) and offer a one-tap fix. | Must | Prevents silent recording failures |
| FR-041 | The system shall let the user start transcription per recording, and optionally auto-transcribe new recordings (e.g. only while charging). | Should | |
| FR-042 | The system shall produce one transcript per call, in conversation order, written in English letters (romanized Hindi/Telugu, e.g. "nenu repu vastanu"). Where feasible, each line also shows the original script (Devanagari/Telugu) beneath it in the same file. | Should | Owner decision 2026-10-18; native-script line is best-effort |
| FR-043 | The system shall optionally translate the transcript into a single chosen language (default English), on-device. | Won't | Owner decision 2026-10-18: romanized transcript is enough |
| FR-044 | The system shall show the transcript time-aligned with the audio: tapping a line seeks the player; current line highlights during playback. | Should | |
| FR-045 | The system shall label each transcript line by speaker: "You" for the owner; for the other party, the contact name as saved on the phone, or the phone number if not saved. | Should | Requires sides on separate channels. WhatsApp exposes the name it displays (saved name, or number for unsaved contacts) |
| FR-049 | In group calls, the system shall label remote speakers as "Participant 1/2/…" (or by group name) and let the user rename them per transcript. | Could | Fallback when FR-050 has no data. All remote voices arrive as one mixed stream |
| FR-050 | During group calls, while the WhatsApp call screen is visible, the system shall log which participant tile WhatsApp highlights as the active speaker (participant name + timestamp) and use that log to name speakers in the post-call transcript. | Could | Owner idea 2026-10-18. Best-effort: no data when screen off (phone at ear → proximity sensor), WhatsApp in background, or audio-only tiles not highlighted. Read via accessibility tree, else periodic accessibility screenshot (Android 11+, rate-limited ~3/s). Combine with voice clustering to fill gaps. Breaks if WhatsApp changes UI. Verify in spike |
| FR-051 | The system shall optionally show a live transcript during the call, using on-device models only. | Won't (v1) | Owner asked for effort estimate 2026-10-18. Post-call (FR-029) first; revisit after bake-off. See §11 effort note |
| FR-046 | The system shall let the user search across all transcripts and jump to the matching moment in the audio. | Should | |
| FR-047 | The system shall let the user edit and copy transcript text. | Could | Copy stays local (clipboard) |
| FR-048 | The system shall load speech/translation models from on-device storage (bundled in APK or imported from a local file) without network access. | Must (if FR-029) | Keeps no-`INTERNET` guarantee; models ~100 MB–1 GB |

## 8. Non-Functional Requirements
| ID | Category | Target | Notes |
|----|----------|--------|-------|
| NFR-001 | Reliability | ≥ 95% of allowlisted calls produce a playable file | 2-week trial |
| NFR-002 | Latency | Auto-recording starts ≤ 2 s after call connects | First seconds may be lost |
| NFR-003 | Privacy | 0 bytes network egress; app declares no `INTERNET` permission | Verifiable in manifest |
| NFR-004 | Security | Recordings encrypted at rest (AES-256, key in Android Keystore) | |
| NFR-005 | Storage | Audio ≤ 1 MB/min (e.g. ~64 kbps AAC/Opus mono); video ≤ 15 MB/min at 720p | |
| NFR-006 | Battery | ≤ 5% additional battery per hour of recording; ~0% idle drain | |
| NFR-007 | Compatibility | Unrooted Samsung, Android 13–16. **Minimum**: Galaxy F42 5G, Android 13 (One UI 5) → minSdk 33. **Primary**: Galaxy A73 5G, Android 16 (One UI 8) → targetSdk 36. All Must FRs pass on both. | Confirmed by owner 2026-10-17 |
| NFR-008 | Call quality | No audible degradation or call drops caused by the app | |
| NFR-009 | Resilience | Crash/kill mid-call loses ≤ 5 s of audio | Chunked writes |
| NFR-010 | Accessibility | Library UI meets WCAG 2.2 AA equivalents (TalkBack labels, 48dp targets, contrast) | |
| NFR-011 | Transparency | Every requested permission shown with plain-language reason before OS prompt | |
| NFR-012 | Visual design | Follows [Material Design 3](https://m3.material.io/) (typography scale, color roles, shape, motion); feels native next to Samsung One UI. Consistent 8 dp spacing grid; no default/unstyled widgets. | Owner: "nice and aesthetic" |
| NFR-013 | UX efficiency | Any recording playable within 2 taps from app launch; onboarding completable in ≤ 3 min | |
| NFR-014 | Smoothness | 60 fps scrolling/animations (no janky frames > 5% in Android GPU profiler); cold start ≤ 1 s on F42 | F42 is the slower device |
| NFR-015 | Polish | Meaningful empty, loading, and error states on every screen; motion ≤ 300 ms; haptic feedback on record start/stop | |
| NFR-016 | Transcription accuracy | Word error rate ≤ 30% on an owner-made test set of 10 real mixed-language calls (manually transcribed) | Validate in spike; adjust after baseline |
| NFR-017 | Transcription speed | Runs later in background (not live); ≤ 2× call duration on Galaxy F42 (10-min call → ≤ 20 min), with progress and resume after interruption | F42 is the slower device |
| NFR-018 | Transcription footprint | Model storage ≤ 1 GB; peak RAM ≤ 1.5 GB; no thermal throttling warnings during a 30-min transcription | F42 has 6–8 GB RAM |

## 9. Constraints & Assumptions
- **Constraint**: Android 10+ `AudioPlaybackCapture` cannot capture `USAGE_VOICE_COMMUNICATION` audio (what WhatsApp calls use) → remote audio capture is device/OEM-dependent, not guaranteed.
- **Constraint**: `VOICE_CALL`/`CAPTURE_AUDIO_OUTPUT` are system-only; unavailable to sideloaded apps without root.
- **Constraint**: During an active call, Android delivers **silence** to ordinary apps recording the mic; only accessibility services and privileged system apps receive real audio ([concurrent capture](https://source.android.com/docs/core/audio/concurrent), [sharing audio input](https://developer.android.com/media/platform/sharing-audio-input)) → the app must run as a user-enabled accessibility service. Allowed because it is a local APK (Play ban not applicable).
- **Constraint**: Owner does not want speakerphone-based capture; recording must work on earpiece, wired and Bluetooth headsets.
- **Constraint**: Google Play (May 2022) bans using Accessibility API for call recording → distribution via sideloaded APK.
- **Constraint**: Android 14+ requires user consent for each `MediaProjection` (screen capture) session → video recording needs one tap per video call on the A73 (owner accepted, 2026-10-17). On Android 13 (F42) the consent may be reusable within a session; design for the one-tap-per-call case on both.
- **Constraint**: Must not modify WhatsApp or violate its ToS.
- **Constraint**: Solo developer, personal project, no budget for paid services.
- **Constraint**: All speech/translation models and libraries must be free to download and use, with no subscription, API key, or usage fees, under an open license permitting personal use (e.g. MIT, Apache-2.0, CC-BY-4.0). License to be verified per model before adoption.
- **Constraint**: Local APK distribution only (owner decision, 2026-10-17).
- **Constraint**: No root, no system/privileged install (owner decision, 2026-10-17) → capture limited to what a user-installed accessibility-service app can get.
- **Constraint**: Android 13+ "Restricted settings" — a sideloaded app's accessibility service cannot be enabled until the user allows restricted settings in App info (one-time manual step).
- **Constraint**: [Android developer verification](https://developer.android.com/developer-verification) — unverified sideloaded APKs are being restricted (first regions Sept 2026; India expected 2027). Install via ADB/Android Studio stays allowed; otherwise use a free limited-distribution (hobbyist) account. Plan install via ADB.
- **Constraint**: Samsung battery management (Sleeping/Deep sleeping apps) kills background services → app must be set to "Unrestricted" battery.
- **Constraint (consent)**: Consent is required from the *other participants*, not from the app. The user, as a participant, is one party. Whether that alone suffices depends on where **each** participant is:
  - *One-party consent* (participant may record): India (no specific statute; DPDP Act 2023 §3(c)(ii) exempts personal/domestic use, but courts have treated covert recording as a privacy violation in some cases), UK (personal use), Canada, most US states, Australia (varies by state).
  - *All-party consent*: US states incl. California, Florida, Washington, Illinois, Pennsylvania, Maryland, Massachusetts, Montana, New Hampshire; Germany (§201 StGB, criminal) and many EU countries.
  - Cross-border/group calls → strictest participant's rule technically applies. Not legal advice.
  - **Owner decision (2026-10-17)**: owner is in India; no consent will be sought from any participant; app has no consent/notification feature. Owner accepts residual risk for calls with participants in all-party-consent regions.
- **Constraint**: Single user, single device (owner's phone). No distribution of the APK to anyone.
- **Assumption**: Personal use on the owner's own device(s) — _Validated by: owner (local APK only)._
- **Assumption**: Owner is in a one-party-consent jurisdiction (India) — _Validated by: owner, 2026-10-17._
- **Assumption**: Contacts can be identified from WhatsApp call notifications/UI by display name (WhatsApp does not expose caller phone number to other apps) — _Validated by: prototype spike on target phone._
- **Assumption**: Audio-only (both/all sides) is the default mode — _Validated by: owner, 2026-10-17._
- **Assumption**: Desktop/web lists are per-device (no sync) — _Validated by: owner confirmation._

## 10. Prior Art & Research
- [Cube ACR](https://cubeacr.app/) — records VoIP incl. WhatsApp via Accessibility + helper APK; remote side often silent on modern devices; cloud features. Shows demand and the fragility.
- [Android AudioPlaybackCapture](https://developer.android.com/media/platform/av-capture) — official capture API; explicitly excludes voice-communication usage → cannot be the reliable path.
- [Google Play Accessibility policy (2022)](https://www.androidpolice.com/google-ends-call-recording-apps-accessibility-services/) — bans call recording via Accessibility for Play apps.
- [Android MediaProjection](https://developer.android.com/media/grow/media-projection) — screen capture; per-session consent on Android 14+.
- Samsung built-in Screen recorder ("Media sounds" / "Media sounds and mic") — user reports are mixed: sometimes captures the other side of WhatsApp calls, often only one side after updates. It is a system app, so its abilities don't transfer to our app, but it's a free baseline to test on both phones first.
- [Windows loopback recording](https://learn.microsoft.com/en-us/windows/win32/coreaudio/loopback-recording) (Windows 10 2004+ also supports per-process loopback) — can isolate WhatsApp Desktop audio cleanly; desktop is the **most reliable** platform.
- [NAudio WasapiLoopbackCapture](https://github.com/naudio/NAudio/blob/main/Docs/WasapiLoopbackCapture.md) — OSS building block for Windows.
- [Chrome `tabCapture`](https://developer.chrome.com/docs/extensions/reference/api/tabCapture) / `getDisplayMedia` — tab audio/video capture for WhatsApp Web.
- OBS Studio / Audacity — already record desktop calls today; good for validating phase 2 need.
- **Transcription (on-device, offline)**:
  - [whisper.cpp](https://github.com/ggml-org/whisper.cpp) — OpenAI Whisper on CPU; supports Hindi, Telugu, English; handles mixed speech moderately; small/medium models needed for usable Telugu → slower on F42.
  - [AI4Bharat IndicWhisper](https://ai4bharat.iitm.ac.in/areas/model/ASR/IndicWhisper/) — Whisper fine-tuned on Indian languages; stronger on Hindi-English mixing.
  - [AI4Bharat IndicConformer](https://huggingface.co/ai4bharat/indic-conformer-600m-multilingual) — 22 Indian languages; runnable on Android via [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx).
  - Android on-device `SpeechRecognizer` — offline language packs exist but one language per session → poor for mixed speech.
  - [Sarvam](https://www.sarvam.ai/) / Google Cloud STT — good Indian-language mixing but cloud-only → excluded.
  - Translation: [ML Kit on-device translation](https://developers.google.com/ml-kit/language/translation) supports Hindi/Telugu ↔ English, but downloads models over the network → would need bundling or a separate model-import path; [AI4Bharat IndicTrans2](https://github.com/AI4Bharat/IndicTrans2) is an offline alternative.
  - **Verdict**: feasible offline, but accuracy on 3-way Hindi/Telugu/English mixing is unproven in public benchmarks → test on owner's real calls before committing.
- **Regulatory**: [GDPR Art. 2(2)(c)](https://gdpr-info.eu/art-2-gdpr/) household exemption likely covers purely personal recordings; US two-party-consent states (e.g. [California Penal Code §632](https://leginfo.legislature.ca.gov/faces/codes_displaySection.xhtml?lawCode=PEN&sectionNum=632)) require all-party consent; India generally permits participant recording, DPDP Act 2023 applies to processing of others' personal data. [WhatsApp Terms](https://www.whatsapp.com/legal/terms-of-service) prohibit modifying the client.

**Build-vs-buy verdict**: Nothing reliable + local-only exists for Android WhatsApp. But the technical ceiling is real: on many stock phones only the user's own voice is capturable without speakerphone. Do a 1–2 day feasibility spike on the owner's actual phone before committing. For Windows, existing tools (OBS) already work; build only if auto-record/allowlist matters there.

## 11. Suggestions & Opportunities
- **Feasibility spike first** (no speakerphone, no root): on Galaxy A73 5G (Android 16) and Galaxy F42 5G (Android 13), with an accessibility-service prototype, test each audio source (`VOICE_COMMUNICATION`, `VOICE_RECOGNITION`, `MIC`, `CAMCORDER`, `VOICE_CALL`/`VOICE_DOWNLINK` in case Samsung allows) × each route (earpiece, wired, Bluetooth). Record which sides are audible. Also dump the accessibility tree during a group call to check whether WhatsApp exposes the active-speaker highlight (FR-050). Also test Samsung Screen recorder as baseline. **Realistic expectation**: own voice ✓; other side likely silent on earpiece/Bluetooth. If so, options: (a) accept own-voice-only on Android, (b) make Windows primary (both sides cleanly), (c) speakerphone mode as opt-in per call (FR-005). Decide after results.
- **No `INTERNET` permission**: strongest, verifiable guarantee of "never shared".
- **Exclude from device backups** (`allowBackup=false`) — otherwise Google backup could upload recordings, violating the no-sharing requirement.
- **Identify contacts by WhatsApp display name**, not phone number; allowlist entries should be picked from contacts and matched by name — handle duplicate names and renamed contacts.
- **Group calls**: decide behaviour (record if any allowlisted participant? never auto?). Default suggestion: manual only.
- **Edge cases**: call waiting/second call; switching voice→video mid-call; Bluetooth headset (audio route changes); call drops/reconnects (one file vs. many); phone reboots mid-call; Do-Not-Disturb; low storage; OEM battery killers (Samsung Sleeping apps) stopping the background service; WhatsApp or One UI updates breaking call detection.
- **Device-theft/snooping scenario**: someone with access to the unlocked phone could browse recordings. Mitigate: library lock (FR-019), encryption at rest (NFR-004). Indicator (FR-013) remains because Android requires a foreground-service notification while recording.
- **Bookmarks during a call** ("mark this moment") — cheap, high value for later review.
- **Design first**: produce high-fidelity mockups (onboarding, dashboard, library, player, settings, allowlist) and approve them before coding UI; the onboarding/permissions flow decides whether the app feels polished or broken.
- **Sequencing**: v1 = Android audio-only, allowlist + manual, local library. v1.1 = video mode. v1.2 = transcription (after a model bake-off on 10 real calls). v1.3 = active-speaker labels; live transcription (A73) if still wanted. v2 = Windows desktop. v3 = WhatsApp Web extension. Cut translation, export, retention from v1.
- **Transcription quality depends on recording quality**: if only the user's own voice is captured (see §12 biggest risk), only that side can be transcribed.
- **Recording channels**: capturing own voice and other side as separate stereo channels (where possible) makes speaker labels (FR-045) nearly free.
- **Active-speaker labels (FR-050)**: in group calls WhatsApp highlights the speaking tile. Spike: dump the accessibility tree during a group call to see if the highlight is exposed (cheap, robust); else sample screenshots (~3/s cap) and detect the highlight ring per tile (fragile, more battery). Only works while the call screen is on; with the phone at the ear the screen is off → fall back to voice clustering + "Participant N". Effort ≈ 1–2 weeks on top of FR-049.
- **Live transcription effort (FR-051)**: no true streaming model handles Hindi+Telugu+English code-mixing offline today; practical approach is "near-live" — voice-activity detection cuts 3–8 s chunks → Whisper/IndicConformer per chunk → text appears 5–10 s behind speech. Phone fit: A73 (Snapdragon 778G) can likely keep pace with a small/quantized model; F42 (Dimensity 700) likely cannot without dropping to tiny models (poor hi/te accuracy) → post-call only on F42. Costs: CPU contention with the live call (risk of call-audio glitches), heat, battery ~10–20%/hr extra, lower accuracy than post-call (less context). Effort ≈ 3–5 weeks on top of post-call transcription, mostly tuning and device testing. Recommend: post-call first; add live later on A73 only if wanted.

## 12. Risks & Open Questions
- [ ] **Biggest risk**: on unrooted Samsung, the other side is likely silent without speakerphone. Owner: build the spike, decide from results (own-voice-only / Windows-first / opt-in speakerphone).
- [x] Target phones — Galaxy A73 5G, Android 16 (primary); Galaxy F42 5G, Android 13 (minimum supported).
- [x] Distribution — local APK only, no Play Store.
- [x] Default mode — audio only, both/all sides.
- [x] Jurisdiction — India; no consent flow (FR-025, FR-032 → Won't).
- [x] Distribution — none; single personal install.
- [ ] Does "never shared" forbid even user-initiated export (FR-020)? Suggested: allow manual export only, never automatic.
- [x] Video: one tap to approve per video call — accepted.
- [x] Call detection — fully automatic for all WhatsApp voice/video calls (FR-001/002); auto-record allowlist, prompt others (FR-033).
- [ ] Group call auto-record policy (FR-031) — confirm "auto if group is allowlisted".
- [ ] Group calls: participants on speaker/mic fallback will have uneven volume; acceptable?
- [x] Root — not acceptable.
- [ ] WhatsApp updates may change notification/UI text used for call detection → maintenance burden.
- [x] Transcript format — one file, conversation order, English letters; native script beneath each line where feasible. Languages: Hindi, Telugu, English.
- [x] Translation (FR-043) — not needed; romanized transcript is enough (→ Won't).
- [x] Transcription timing — after the call, in background; not live.
- [ ] Live transcription (FR-051) — deferred; revisit after post-call bake-off. Likely A73-only, 5–10 s lag, lower accuracy than post-call.
- [ ] Active-speaker labels (FR-050) — verify in spike whether WhatsApp exposes the "speaking" highlight in the accessibility tree, or only visually (needs screenshots).
- [x] Models — free, open-licensed, no subscription.
- [x] Speaker labels — "You" + saved contact name (or number if unsaved).

## 13. Glossary
| Term | Meaning |
|------|---------|
| Allowlist | Contacts whose WhatsApp calls are recorded automatically |
| Remote audio | The other party's voice |
| VoIP | Voice over IP — WhatsApp calls run over the internet, not the cellular voice channel |
| AudioPlaybackCapture | Android 10+ API for capturing other apps' audio; excludes call audio |
| MediaProjection | Android screen-capture API; needs user consent per session |
| Process loopback | Windows feature to capture one app's audio output |
| Sideload | Install an APK outside Google Play |
| One-/two-party consent | Whether one participant or all participants must consent to recording |
| Transcription (ASR) | Converting speech audio into text in the language(s) spoken |
| Translation | Converting text from one language into another |
| Code-mixing | Switching between languages within a conversation or sentence (e.g. Hindi + English) |
| WER | Word error rate — % of words wrong in a transcript; lower is better |
