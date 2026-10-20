# PRD: WhatsApp Call Recorder (personal, on-device)

## 1. Overview
- **Version**: 1.0  |  **Date**: 2026-10-18  |  **Status**: Approved (2026-10-18)  |  **Owner**: soakula
- **Source**: [Discovery doc (Approved 2026-10-18)](../plans/2026-10-17_whatsapp-call-recording_discovery.md). FR/NFR IDs below refer to it.

### Problem Statement
WhatsApp has no call recording. The owner loses details from important calls (instructions, agreements, family/medical/financial talks). Existing Android recorders are unreliable for VoIP since Android 10, often capture only one side, and many upload data to vendor clouds. There is no trustworthy, local-only option.

### Target Users
A single owner (developer, high technical level) on their own phones: **Galaxy A73 5G, Android 16** (primary) and **Galaxy F42 5G, Android 13** (minimum). Installed as a local APK via ADB; never distributed.

## 2. Goals & Non-Goals
### User Goals
- Allowlisted calls are recorded with zero user action — ≥ 95% of allowlisted calls produce a playable file (NFR-001).
- Both/all sides captured on earpiece, wired and Bluetooth, without speakerphone, wherever the device allows (FR-004, FR-034).
- Nothing ever leaves the phone (FR-018, NFR-003).
- The app looks polished and native next to Samsung One UI (NFR-012).
- Later: read and search calls as romanized Hindi/Telugu/English transcripts, fully offline (FR-029).

### Non-Goals (Out of Scope)
- Cloud backup, sync, or any sharing — _Rationale: recordings must never be shared._
- Google Play distribution — _Rationale: owner decision; Play also bans accessibility-based call recording._
- iOS — _Rationale: OS blocks third-party call capture; not requested._
- Cellular (SIM) call recording — _Rationale: not requested._
- Cloud/online speech-to-text or translation — _Rationale: would send audio off-device._
- Modifying/hooking WhatsApp — _Rationale: violates WhatsApp ToS, risks account ban._
- Root, custom ROM, or system/privileged install — _Rationale: owner decision 2026-10-17._
- Consent/notification features for other participants — _Rationale: owner in India, personal use; owner accepts risk (FR-025, FR-032 Won't)._

## 3. User Personas
- **Owner**: records own WhatsApp calls for personal reference; comfortable with ADB and Android settings; wants it to "just work" and look good. Speaks Hindi, Telugu and English, often mixed in one sentence.

| Role | Permissions | Key Actions |
|------|-------------|-------------|
| Owner | Full: settings, allowlist, recordings, transcripts | Set up once, manage allowlist, record (auto/manual), play, search, transcribe, delete |
| Other call party | None (not an app user) | — |

## 4. Requirements (MoSCoW)
| ID | Requirement | Priority | Problem it solves | Milestones | Est. AI |
|----|-------------|----------|-------------------|------------|---------|
| REQ-001 | Capture feasibility is proven on both target phones before building | Must | Unknown whether remote audio and call signals are readable without root/speakerphone | M-001.1, M-001.2 | 02:50 |
| REQ-002 | System must detect WhatsApp calls automatically | Must | Owner should never have to start the app per call | M-002.1, M-002.2 | 03:20 |
| REQ-003 | System must record call audio, both/all sides, on any audio route, without losing data | Must | Calls are not recorded today | M-003.1, M-003.2, M-003.3 | 04:55 |
| REQ-004 | Owner can auto-record allowlisted contacts and manually record any call | Must | Important contacts must be recorded without remembering to | M-004.1, M-004.2 | 02:15 |
| REQ-005 | Recordings stay private on the device | Must | Fear of recordings leaking via network, backup, or other apps | M-005.1, M-005.2 | 02:25 |
| REQ-006 | Owner can find and play recordings from a home dashboard and library | Must | Recordings are useless if hard to reach | M-006.1 | 01:40 |
| REQ-007 | Setup is guided and the app warns when it stops being able to record | Must | Sideloaded accessibility apps need many settings; silent failures lose calls | M-007.1, M-007.2 | 02:25 |
| REQ-008 | UI is aesthetic, smooth and accessible | Must | Owner wants a nice-looking app with good UX | M-008.1, M-008.2 | 03:00 |
| REQ-009 | Owner can record video calls as audio + video | Should | Some video calls need the visual record | M-009.1 | 01:50 |
| REQ-010 | Group calls and per-contact modes follow owner rules | Should | Group calls and video-preferred contacts need their own rules | M-010.1, M-010.2 | 01:15 |
| REQ-011 | Library is rich, comfortable and locked | Should | Many recordings → need grouping, search, better player, snoop protection | M-011.1, M-011.2, M-011.3 | 02:55 |
| REQ-012 | Owner can test what this phone can capture | Should | Capture varies per phone/route/update; owner needs to know | M-012.1 | 01:00 |
| REQ-013 | Recordings can be transcribed offline into one mixed-language romanized transcript | Should | Listening to long calls to find one detail is slow | M-013.1, M-013.2, M-013.3, M-013.4 | 06:45 |
| REQ-014 | Group-call transcripts name or distinguish remote speakers | Could | All remote voices arrive as one mixed stream | M-014.1, M-014.2 | 02:35 |
| REQ-015 | Owner can export one recording on purpose and auto-delete old ones | Could | Occasional need to keep a copy elsewhere; storage grows | M-015.1, M-015.2 | 01:20 |
| REQ-016 | Owner can correct and copy transcript text | Could | ASR mistakes on mixed speech | M-016.1 | 00:40 |
| REQ-017 | Speakerphone fallback when remote audio cannot be captured | Could | Only if M-001.1 shows remote side is silent | M-017.1 | 00:40 |
| REQ-018 | WhatsApp Desktop calls on Windows are recorded | Should | Phase 2; most reliable platform for both sides | M-018.1 | 02:45 |
| REQ-019 | WhatsApp Web calls are recorded from the browser | Could | Phase 3 | M-019.1 | 01:25 |
| **Total** | | | | | **46:00** |

> **About estimates**: "Est. AI" is active AI-agent time (analyse, code, test, iterate). It excludes elapsed time for real-call testing on two phones, owner review, and waiting — expect calendar time to be several times higher, especially for REQ-001, REQ-003 and REQ-013.

### Won't Have
- Consent notification to participants (FR-025) and legal notice (FR-032) — _Rationale: owner decision, India, personal use._
- Shared allowlist across Android/desktop/web (FR-028) — _Rationale: needs sync → conflicts with no-sharing._
- Transcript translation (FR-043) — _Rationale: owner decision; romanized transcript is enough._
- Live (during-call) transcription (FR-051) — _Rationale: deferred; F42 likely too slow, call-quality risk; revisit after REQ-013._

## 5. Requirement Breakdown
> Requirement → Milestone → Feature → User Story. Milestone Est. AI = features + exit-criteria test run.

### REQ-001: Capture feasibility proven (Must Have)
- **Outcome**: For each phone, source and audio route, we know which sides are capturable and which WhatsApp signals the app can read; the owner has chosen a capture strategy.
- **Source**: FR-003, FR-004, FR-034, FR-050, NFR-007; discovery §11 spike.
- **Est. AI**: 02:50

#### Milestone M-001.1: Audio capture matrix known
- **Problem solved**: Nobody knows whether the remote side is audible on the A73/F42 without root or speakerphone.
- **Est. AI**: 01:45

##### FEAT-001.1.1: Spike recorder app
Throwaway accessibility-service app that records one audio source at a time during a live WhatsApp call.
- **Est. AI**: 01:15

- **US-001** — **As the** owner **I want** a test app that records each audio source during a WhatsApp call **So that** I can learn which source captures both sides (`Est. AI: 00:45`)
  - [ ] Tries `VOICE_COMMUNICATION`, `VOICE_RECOGNITION`, `MIC`, `CAMCORDER`, `VOICE_CALL`, `VOICE_DOWNLINK`, 30 s each
  - [ ] Saves one file per attempt named `<device>_<source>_<route>.m4a` in app-private storage
  - [ ] A refused source (exception or silent buffer) is logged with the error, not crashed on
- **US-002** — **As the** owner **I want** a capability matrix generated from the test files **So that** I can decide the capture strategy from facts (`Est. AI: 00:30`)
  - [ ] Matrix rows = source; columns = route (earpiece, loudspeaker, wired, Bluetooth) × side (own / remote)
  - [ ] Each cell shows RMS level and ✓/✗ using a silence threshold (−50 dBFS)
  - [ ] Matrix shown in-app and saved as a local Markdown file

##### FEAT-001.1.2: Samsung Screen recorder baseline
- **Est. AI**: 00:10

- **US-003** — **As the** owner **I want** a checklist to test Samsung's built-in Screen recorder on a WhatsApp call **So that** I have a baseline to compare against (`Est. AI: 00:10`)
  - [ ] Checklist covers "Media sounds" and "Media sounds and mic" on both phones, all routes

**Exit Criteria** (test run `00:20`) — milestone is done only when all boxes are checked:
- [ ] **Unit tests**: RMS/silence-detection utility tests pass; ≥ 80% coverage on new code (FEAT-001.1.1)
- [ ] **Manual / UAT**: matrix filled for A73 and F42 across all 4 routes (US-001, US-002, US-003)
- [ ] **Manual / UAT**: owner records the decision in the discovery doc: both sides ✓ / own-voice-only / Windows-first / speakerphone opt-in (gates REQ-017)

#### Milestone M-001.2: WhatsApp call signals known
- **Problem solved**: Unknown which notifications/accessibility events reveal call start/end, call type, contact name and active speaker.
- **Est. AI**: 01:05

##### FEAT-001.2.1: Event and UI-tree logger
- **Est. AI**: 00:50

- **US-004** — **As the** owner **I want** WhatsApp notifications and accessibility events logged during voice, video and group calls (incoming and outgoing) **So that** detection rules are based on real signals (`Est. AI: 00:30`)
  - [ ] Log captures ringing, connected, ended, voice/video indicator, display name, group name
  - [ ] WhatsApp and One UI versions recorded with each log
  - [ ] Log stays in app-private storage
- **US-005** — **As the** owner **I want** the call-screen view tree dumped each second during a group call **So that** I know whether the active-speaker highlight is readable (`Est. AI: 00:20`)
  - [ ] Report states which node property (if any) changes when a participant speaks
  - [ ] If none, a screenshot sample at ≤ 3/s is taken and the highlight's visibility noted

**Exit Criteria** (test run `00:15`) — milestone is done only when all boxes are checked:
- [ ] **Unit tests**: log parser tests pass on captured fixtures (FEAT-001.2.1)
- [ ] **Manual / UAT**: signal catalogue documented for both phones: voice, video, group, incoming, outgoing, missed (US-004)
- [ ] **Manual / UAT**: active-speaker readability verdict recorded — tree / screenshot / not possible (US-005)

### REQ-002: Automatic call detection (Must Have)
- **Outcome**: Every WhatsApp call on the phone becomes a call session with start, end, type and contact, with no user action.
- **Source**: FR-001, FR-002, NFR-002.
- **Est. AI**: 03:20

#### Milestone M-002.1: Voice calls detected automatically
- **Problem solved**: The app cannot react to calls it does not know about.
- **Est. AI**: 02:05

##### FEAT-002.1.1: Call state detector
Accessibility service + notification listener turn WhatsApp signals into call sessions.
- **Est. AI**: 01:15

- **US-006** — **As the** owner **I want** incoming and outgoing WhatsApp voice calls detected automatically **So that** recording can start without me (`Est. AI: 00:45`)
  - [ ] Session start ≤ 1 s after call connects; end ≤ 2 s after hang-up
  - [ ] Missed or declined calls create no session
  - [ ] Works with WhatsApp in background and screen off
- **US-007** — **As the** owner **I want** the session to carry the contact's display name **So that** recordings are labelled correctly (`Est. AI: 00:30`)
  - [ ] Uses the name WhatsApp shows (saved contact name, or number if unsaved)
  - [ ] Falls back to "Unknown" when no name is readable

##### FEAT-002.1.2: Detector resilience
- **Est. AI**: 00:30

- **US-008** — **As the** owner **I want** call waiting, reconnects and back-to-back calls handled **So that** sessions are not split or merged wrongly (`Est. AI: 00:30`)
  - [ ] Reconnect within 30 s continues the same session
  - [ ] A second call while the first is on hold starts a separate session

**Exit Criteria** (test run `00:20`) — milestone is done only when all boxes are checked:
- [ ] **Unit tests**: detector state machine tests on M-001.2 fixtures pass; ≥ 80% coverage (FEAT-002.1.1, FEAT-002.1.2)
- [ ] **End-to-end**: 10 calls per phone (5 incoming, 5 outgoing) detected 10/10 with correct name (US-006, US-007)
- [ ] **Performance**: session start ≤ 1 s after connect in 10/10 calls (NFR-002)

#### Milestone M-002.2: Video and group calls distinguished
- **Problem solved**: Video and group calls need different handling than 1:1 voice calls.
- **Est. AI**: 01:15

##### FEAT-002.2.1: Call-type classifier
- **Est. AI**: 01:00

- **US-009** — **As the** owner **I want** voice and video calls told apart, including mid-call switches **So that** the right recording mode applies (`Est. AI: 00:30`)
  - [ ] Call type correct at start; voice→video switch logged with timestamp
- **US-010** — **As the** owner **I want** group calls detected with the group name or participant names **So that** they can be labelled and allowlisted (`Est. AI: 00:30`)
  - [ ] Group flag, group name (if any) and visible participant names stored on the session

**Exit Criteria** (test run `00:15`) — milestone is done only when all boxes are checked:
- [ ] **Unit tests**: classifier tests pass (FEAT-002.2.1)
- [ ] **End-to-end**: 3 video, 3 group, 2 voice→video switch calls classified correctly on both phones (US-009, US-010)

### REQ-003: Record call audio, all sides, any route (Must Have)
- **Outcome**: Each detected call that should be recorded produces a playable, compact file with the best capture the device allows, even across route changes, crashes or low storage.
- **Source**: FR-003, FR-004, FR-013, FR-014, FR-023, FR-024, FR-030, FR-034, NFR-002, NFR-005, NFR-006, NFR-008, NFR-009.
- **Est. AI**: 04:55

#### Milestone M-003.1: Call audio recorded to a file
- **Problem solved**: Calls are not captured at all today.
- **Est. AI**: 01:55

##### FEAT-003.1.1: Recording engine
Foreground service, started by the accessibility service, using the source chosen in M-001.1.
- **Est. AI**: 01:25

- **US-011** — **As the** owner **I want** my voice and the best available remote audio recorded when a session starts **So that** I have the call on file (`Est. AI: 00:45`)
  - [ ] Recording starts ≤ 2 s after connect (NFR-002)
  - [ ] Own voice and remote on separate stereo channels where possible, else mono mix
  - [ ] Audio ≤ 1 MB/min (Opus or AAC, ~64 kbps) (NFR-005)
- **US-012** — **As the** owner **I want** a persistent indicator while recording **So that** I always know it is on (`Est. AI: 00:20`)
  - [ ] Foreground notification with elapsed time and Stop action
  - [ ] Haptic tick on start and stop (NFR-015)
- **US-013** — **As the** owner **I want** each file saved with metadata **So that** I can find it later (`Est. AI: 00:20`)
  - [ ] Stores contact, direction, call type, start time, duration, capture source, routes used

**Exit Criteria** (test run `00:30`) — milestone is done only when all boxes are checked:
- [ ] **Unit tests**: encoder config and metadata repository tests pass; ≥ 80% coverage (FEAT-003.1.1)
- [ ] **Integration tests**: on-device instrumented test records 60 s and produces a playable file with metadata (US-011, US-013)
- [ ] **Performance**: ≤ 5% extra battery over a 1-hour call on F42; file ≤ 1 MB/min (NFR-005, NFR-006)
- [ ] **Manual / UAT**: no audible call degradation or drops over 5 calls per phone (NFR-008)

#### Milestone M-003.2: Any route and group calls covered
- **Problem solved**: Recording breaks or loses a side when the owner switches to Bluetooth/headset or joins a group call.
- **Est. AI**: 01:25

##### FEAT-003.2.1: Audio route handling
- **Est. AI**: 00:45

- **US-014** — **As the** owner **I want** recording to continue across earpiece, loudspeaker, wired and Bluetooth, including mid-call switches **So that** I never need to change how I take calls (`Est. AI: 00:45`)
  - [ ] No gap > 1 s on route change; each route change logged with timestamp
  - [ ] Bluetooth disconnect mid-call does not stop recording

##### FEAT-003.2.2: Group call capture
- **Est. AI**: 00:20

- **US-015** — **As the** owner **I want** a group call saved as one file with me and all remote participants **So that** conference calls are complete (`Est. AI: 00:20`)
  - [ ] Single file per group call; participants joining/leaving do not split it

**Exit Criteria** (test run `00:20`) — milestone is done only when all boxes are checked:
- [ ] **End-to-end**: per phone, one call switching earpiece→Bluetooth→wired→loudspeaker plays back with no gap > 1 s (US-014)
- [ ] **End-to-end**: one 3-party group call recorded as one file on each phone (US-015)

#### Milestone M-003.3: No recording lost
- **Problem solved**: Crashes, OS kills and full storage silently destroy recordings.
- **Est. AI**: 01:35

##### FEAT-003.3.1: Crash-safe chunked writes
- **Est. AI**: 00:45

- **US-016** — **As the** owner **I want** partial recordings kept if the app crashes or is killed **So that** I lose at most a few seconds (`Est. AI: 00:45`)
  - [ ] Data flushed every ≤ 5 s; a kill loses ≤ 5 s (NFR-009)
  - [ ] Unfinished files repaired on next start and marked "Recovered"

##### FEAT-003.3.2: Low-storage guard
- **Est. AI**: 00:30

- **US-017** — **As the** owner **I want** a warning before storage runs out and a clean stop if it does **So that** files are never corrupted (`Est. AI: 00:30`)
  - [ ] Warning at < 500 MB free; recording stops and finalizes at < 100 MB

**Exit Criteria** (test run `00:20`) — milestone is done only when all boxes are checked:
- [ ] **Unit tests**: chunk writer and repair logic tests pass; ≥ 80% coverage (FEAT-003.3.1, FEAT-003.3.2)
- [ ] **Integration tests**: `adb shell am kill` mid-recording → recovered file loses ≤ 5 s (US-016)
- [ ] **Integration tests**: simulated low storage triggers warning and clean stop (US-017)

### REQ-004: Allowlist auto-record and manual control (Must Have)
- **Outcome**: Allowlisted calls record themselves; any other call can be recorded with one tap.
- **Source**: FR-010, FR-011, FR-012, FR-033.
- **Est. AI**: 02:15

#### Milestone M-004.1: Allowlisted calls auto-record
- **Problem solved**: Owner forgets to start recording for important contacts.
- **Est. AI**: 01:10

##### FEAT-004.1.1: Allowlist management
- **Est. AI**: 00:30

- **US-018** — **As the** owner **I want** to add and remove contacts on an "Always record" list **So that** I choose who is auto-recorded (`Est. AI: 00:30`)
  - [ ] Pick from phone contacts; matched by display name
  - [ ] Warns when two contacts share the same name

##### FEAT-004.1.2: Auto-record rule
- **Est. AI**: 00:20

- **US-019** — **As the** owner **I want** recording to start automatically for allowlisted contacts **So that** I never miss them (`Est. AI: 00:20`)
  - [ ] Allowlisted → recording starts; non-allowlisted → never auto-records

**Exit Criteria** (test run `00:20`) — milestone is done only when all boxes are checked:
- [ ] **Unit tests**: name matching (case, emoji, duplicates) tests pass (FEAT-004.1.1, FEAT-004.1.2)
- [ ] **End-to-end**: 5 allowlisted calls recorded 5/5; 5 non-allowlisted calls not recorded (US-019)

#### Milestone M-004.2: Any call recordable on demand
- **Problem solved**: Owner sometimes needs to record a call with someone not on the list.
- **Est. AI**: 01:05

##### FEAT-004.2.1: Manual controls
- **Est. AI**: 00:50

- **US-020** — **As the** owner **I want** to start/stop recording from the notification or a Quick Settings tile **So that** I can control it mid-call (`Est. AI: 00:30`)
  - [ ] Start mid-call begins recording ≤ 2 s; Stop finalizes the file
- **US-021** — **As the** owner **I want** a "Record this call?" notification for non-allowlisted calls **So that** recording is one tap away (`Est. AI: 00:20`)
  - [ ] Dismissing it records nothing; it disappears when the call ends

**Exit Criteria** (test run `00:15`) — milestone is done only when all boxes are checked:
- [ ] **End-to-end**: start/stop via notification and tile on both phones (US-020)
- [ ] **End-to-end**: prompt appears for non-allowlisted calls and auto-clears at call end (US-021)

### REQ-005: Recordings stay private (Must Have)
- **Outcome**: Recordings and metadata cannot leave the phone and are unreadable outside the app.
- **Source**: FR-017, FR-018, NFR-003, NFR-004.
- **Est. AI**: 02:25

#### Milestone M-005.1: Nothing can leave the device
- **Problem solved**: Network, cloud backup or other apps could leak recordings.
- **Est. AI**: 00:50

##### FEAT-005.1.1: Manifest hardening
- **Est. AI**: 00:20

- **US-022** — **As the** owner **I want** the app to have no network permission and no backup **So that** leaks are impossible by design (`Est. AI: 00:20`)
  - [ ] No `INTERNET` permission in the merged manifest; build fails if one appears
  - [ ] `allowBackup=false` and data-extraction rules exclude all data

##### FEAT-005.1.2: App-private storage
- **Est. AI**: 00:15

- **US-023** — **As the** owner **I want** recordings kept in app-private storage **So that** gallery, file managers and other apps cannot see them (`Est. AI: 00:15`)
  - [ ] Files never appear in MediaStore or shared storage

**Exit Criteria** (test run `00:15`) — milestone is done only when all boxes are checked:
- [ ] **Security**: CI manifest check passes; `aapt dump permissions` shows no `INTERNET` (US-022)
- [ ] **Manual / UAT**: My Files and Gallery show no recordings on either phone (US-023)

#### Milestone M-005.2: Recordings unreadable if extracted
- **Problem solved**: Files copied off a rooted/forensic dump would be playable.
- **Est. AI**: 01:35

##### FEAT-005.2.1: Encryption at rest
- **Est. AI**: 01:15

- **US-024** — **As the** owner **I want** recordings encrypted as they are written **So that** a copied file is useless (`Est. AI: 00:45`)
  - [ ] AES-256-GCM per chunk; key in Android Keystore, non-exportable
  - [ ] Works with chunked writes from US-016
- **US-025** — **As the** owner **I want** playback to decrypt in memory **So that** no plaintext copy is ever written (`Est. AI: 00:30`)
  - [ ] No temp plaintext files created during play or seek

**Exit Criteria** (test run `00:20`) — milestone is done only when all boxes are checked:
- [ ] **Unit tests**: encrypt/decrypt round-trip and tamper-detection tests pass; ≥ 90% coverage (FEAT-005.2.1)
- [ ] **Security**: file pulled via `adb` is not playable; no high/critical findings in dependency scan

### REQ-006: Dashboard and library (Must Have)
- **Outcome**: Owner sees recorder status and can play any recording within 2 taps.
- **Source**: FR-015, FR-036, NFR-013.
- **Est. AI**: 01:40

#### Milestone M-006.1: Recordings findable and playable
- **Problem solved**: Recordings are useless if hard to reach.
- **Est. AI**: 01:40

##### FEAT-006.1.1: Home dashboard
- **Est. AI**: 00:30

- **US-026** — **As the** owner **I want** a home screen showing recorder status, recent recordings and storage used **So that** I know at a glance that everything works (`Est. AI: 00:30`)
  - [ ] Status: Ready / Recording / Needs attention (links to fix)
  - [ ] Last 5 recordings and total storage used

##### FEAT-006.1.2: Library and basic playback
- **Est. AI**: 00:50

- **US-027** — **As the** owner **I want** a newest-first list with play, pause and seek **So that** I can listen to any call (`Est. AI: 00:30`)
  - [ ] Any recording playable within 2 taps from launch (NFR-013)
- **US-028** — **As the** owner **I want** to rename and delete recordings **So that** I keep the library tidy (`Est. AI: 00:20`)
  - [ ] Delete offers 5 s Undo; deleted files are removed from disk

**Exit Criteria** (test run `00:20`) — milestone is done only when all boxes are checked:
- [ ] **Unit tests**: library view-model tests pass; ≥ 80% coverage (FEAT-006.1.1, FEAT-006.1.2)
- [ ] **UI tests**: Compose UI tests for list, play, rename, delete, undo pass (US-026..US-028)

### REQ-007: Guided setup and health monitoring (Must Have)
- **Outcome**: Owner completes setup in ≤ 3 min and is told immediately whenever recording would fail.
- **Source**: FR-035, FR-040, NFR-011, NFR-013.
- **Est. AI**: 02:25

#### Milestone M-007.1: Setup completable in ≤ 3 minutes
- **Problem solved**: Restricted settings, accessibility, battery and notification access are confusing to enable on a sideloaded app.
- **Est. AI**: 01:20

##### FEAT-007.1.1: Onboarding checklist
- **Est. AI**: 01:00

- **US-029** — **As the** owner **I want** a step-by-step checklist with a plain reason before each permission and a deep link to each setting **So that** setup is quick and clear (`Est. AI: 00:45`)
  - [ ] Covers: allow restricted settings, accessibility service, notification access, battery Unrestricted, notifications, contacts, microphone
  - [ ] Each step explains why before the OS prompt (NFR-011)
- **US-030** — **As the** owner **I want** the checklist to update itself when I return from Settings **So that** I see progress (`Est. AI: 00:15`)
  - [ ] Step ticks within 1 s of returning to the app

**Exit Criteria** (test run `00:20`) — milestone is done only when all boxes are checked:
- [ ] **UI tests**: checklist states render for each permission combination (US-029, US-030)
- [ ] **Manual / UAT**: fresh install → setup done in ≤ 3 min on both phones (NFR-013)

#### Milestone M-007.2: Silent failures prevented
- **Problem solved**: Updates, reboots or Samsung battery rules disable the service and calls go unrecorded.
- **Est. AI**: 01:05

##### FEAT-007.2.1: Health monitor
- **Est. AI**: 00:50

- **US-031** — **As the** owner **I want** an alert when a required setting is revoked **So that** I can fix it before the next call (`Est. AI: 00:30`)
  - [ ] Banner + notification within 1 min of detection, with one-tap fix
- **US-032** — **As the** owner **I want** the recorder ready again after a reboot or app update **So that** I never have to open the app (`Est. AI: 00:20`)
  - [ ] Ready within 60 s of boot/update without user action

**Exit Criteria** (test run `00:15`) — milestone is done only when all boxes are checked:
- [ ] **Integration tests**: disabling accessibility / setting battery to Optimized triggers alert (US-031)
- [ ] **Manual / UAT**: reboot both phones → next allowlisted call is recorded (US-032)

### REQ-008: Aesthetic, smooth, accessible UI (Must Have)
- **Outcome**: Approved designs implemented with Material 3, smooth on the F42, usable with TalkBack.
- **Source**: FR-039, NFR-010, NFR-012, NFR-014, NFR-015.
- **Est. AI**: 03:00

#### Milestone M-008.1: Designs approved before UI coding
- **Problem solved**: Building UI without an agreed look leads to rework and an unpolished app.
- **Est. AI**: 01:00

##### FEAT-008.1.1: High-fidelity mockups
- **Est. AI**: 00:45

- **US-033** — **As the** owner **I want** mockups of onboarding, dashboard, library, player, allowlist, settings and transcript screens in light and dark **So that** I approve the look first (`Est. AI: 00:45`)
  - [ ] Material 3 color roles, type scale, 8 dp grid; One UI-friendly (NFR-012)
  - [ ] Empty, loading and error states drawn for each screen (NFR-015)

**Exit Criteria** (test run `00:15`) — milestone is done only when all boxes are checked:
- [ ] **Manual / UAT**: owner signs off the mockups (US-033)

#### Milestone M-008.2: App looks and feels native
- **Problem solved**: Default widgets and janky screens make the app feel cheap.
- **Est. AI**: 02:00

##### FEAT-008.2.1: Theme system
- **Est. AI**: 00:30

- **US-034** — **As the** owner **I want** light, dark and follow-system themes with Material You colors **So that** the app matches my phone (`Est. AI: 00:30`)
  - [ ] Dynamic color on both phones; branded fallback palette

##### FEAT-008.2.2: Polish and accessibility
- **Est. AI**: 01:00

- **US-035** — **As the** owner **I want** meaningful empty/loading/error states and smooth motion **So that** every screen feels finished (`Est. AI: 00:30`)
  - [ ] Motion ≤ 300 ms; no unstyled widgets
- **US-036** — **As the** owner **I want** the app usable with TalkBack and large text **So that** it is accessible (`Est. AI: 00:30`)
  - [ ] Content descriptions on all controls; 48 dp targets; text contrast ≥ 4.5:1 (NFR-010)

**Exit Criteria** (test run `00:30`) — milestone is done only when all boxes are checked:
- [ ] **UI tests**: screenshot tests for every screen in light/dark pass (US-034, US-035)
- [ ] **Accessibility**: Android Accessibility Scanner reports zero issues on all screens (US-036)
- [ ] **Performance**: cold start ≤ 1 s and janky frames ≤ 5% on F42 (NFR-014)
- [ ] **Manual / UAT**: owner confirms the built app matches the approved mockups

### REQ-009: Video recording mode (Should Have)
- **Outcome**: In Audio + Video mode, video calls are recorded as screen + audio after one consent tap; audio-only remains the default.
- **Source**: FR-006, FR-008, FR-009. (FR-006 and FR-009 are Must in discovery but only apply once video exists.)
- **Est. AI**: 01:50

#### Milestone M-009.1: Video calls recordable with one tap
- **Problem solved**: Some video calls need a visual record.
- **Est. AI**: 01:50

##### FEAT-009.1.1: Recording mode setting
- **Est. AI**: 00:15

- **US-037** — **As the** owner **I want** a global mode: Audio only (default) or Audio + Video **So that** I control what is captured (`Est. AI: 00:15`)
  - [ ] Default is Audio only on fresh install

##### FEAT-009.1.2: Screen + audio capture
- **Est. AI**: 01:05

- **US-038** — **As the** owner **I want** video calls recorded as screen + call audio after one tap on the Android prompt **So that** I keep the visual record (`Est. AI: 00:45`)
  - [ ] Prompt shown automatically when a video call starts in Audio + Video mode
  - [ ] 720p, ≤ 15 MB/min (NFR-005)
- **US-039** — **As the** owner **I want** audio-only recording to continue if I dismiss or deny the prompt **So that** the call is never lost (`Est. AI: 00:20`)
  - [ ] Reason logged on the recording ("video consent dismissed")

**Exit Criteria** (test run `00:30`) — milestone is done only when all boxes are checked:
- [ ] **Unit tests**: mode resolution tests pass (FEAT-009.1.1)
- [ ] **End-to-end**: 3 video calls per phone recorded with video; 1 dismissed prompt falls back to audio (US-038, US-039)
- [ ] **Performance**: video file ≤ 15 MB/min; no call drops (NFR-005, NFR-008)

### REQ-010: Group and per-contact rules (Should Have)
- **Outcome**: Allowlisted groups auto-record and contacts can override the global mode.
- **Source**: FR-031, FR-007.
- **Est. AI**: 01:15

#### Milestone M-010.1: Group calls follow the allowlist
- **Problem solved**: Group calls are missed because only 1:1 contacts are allowlisted.
- **Est. AI**: 00:45

##### FEAT-010.1.1: Group allowlist
- **Est. AI**: 00:30

- **US-040** — **As the** owner **I want** to allowlist a WhatsApp group by name **So that** its calls are auto-recorded (`Est. AI: 00:30`)
  - [ ] Group call auto-records when group name matches (policy per open question in §10)

**Exit Criteria** (test run `00:15`) — milestone is done only when all boxes are checked:
- [ ] **End-to-end**: 2 allowlisted group calls auto-recorded; 1 non-allowlisted group call prompts only (US-040)

#### Milestone M-010.2: Per-contact recording mode
- **Problem solved**: Some contacts need video while the default stays audio-only.
- **Est. AI**: 00:30

##### FEAT-010.2.1: Mode override
- **Est. AI**: 00:20

- **US-041** — **As the** owner **I want** to set Audio only / Audio + Video per allowlisted contact **So that** each contact gets the right mode (`Est. AI: 00:20`)
  - [ ] Override beats global mode; cleared override falls back to global

**Exit Criteria** (test run `00:10`) — milestone is done only when all boxes are checked:
- [ ] **Unit tests**: override precedence tests pass (US-041)

### REQ-011: Rich, locked library (Should Have)
- **Outcome**: Many recordings stay easy to browse, comfortable to play and protected from others using the phone.
- **Source**: FR-016, FR-019, FR-037, FR-038.
- **Est. AI**: 02:55

#### Milestone M-011.1: Recordings browsable by contact
- **Problem solved**: A flat list becomes unusable with hundreds of recordings.
- **Est. AI**: 01:15

##### FEAT-011.1.1: Grouped and filtered library
- **Est. AI**: 01:00

- **US-042** — **As the** owner **I want** recordings grouped by contact with avatar/initials, type, direction, duration and date **So that** I scan quickly (`Est. AI: 00:30`)
  - [x] Group headers show contact and count (local native Compose tests, 2026-10-20)
- **US-043** — **As the** owner **I want** to filter by contact and date range and search by name **So that** I find a call fast (`Est. AI: 00:30`)
  - [ ] Results update ≤ 200 ms for 1,000 recordings

**Exit Criteria** (test run `00:15`) — milestone is done only when all boxes are checked:
- [x] **UI tests**: grouping and filter tests pass (US-042, US-043; local native Compose tests, 2026-10-20)
- [ ] **Performance**: filter ≤ 200 ms with 1,000 seeded recordings on F42

#### Milestone M-011.2: Comfortable playback
- **Problem solved**: Long calls are tedious to review with a basic player.
- **Est. AI**: 01:00

##### FEAT-011.2.1: Advanced player
- **Est. AI**: 00:45

- **US-044** — **As the** owner **I want** a waveform, 0.75×–2× speed and ±10 s skip **So that** I review calls quickly (`Est. AI: 00:45`)
  - [ ] Waveform renders ≤ 1 s for a 60-min file; speed change keeps pitch

**Exit Criteria** (test run `00:15`) — milestone is done only when all boxes are checked:
- [ ] **UI tests**: player controls tests pass (US-044)

#### Milestone M-011.3: Library protected from snoopers
- **Problem solved**: Anyone holding the unlocked phone could listen to recordings.
- **Est. AI**: 00:40

##### FEAT-011.3.1: Biometric lock
- **Est. AI**: 00:30

- **US-045** — **As the** owner **I want** fingerprint/PIN required to open the library **So that** others cannot browse recordings (`Est. AI: 00:30`)
  - [ ] Required on open and after 1 min in background; recording still works while locked

**Exit Criteria** (test run `00:10`) — milestone is done only when all boxes are checked:
- [ ] **Manual / UAT**: lock verified on both phones (US-045)

### REQ-012: Device capability test (Should Have)
- **Outcome**: Owner can check at any time which sides this phone captures on each route.
- **Source**: FR-022.
- **Est. AI**: 01:00

#### Milestone M-012.1: Owner knows what this phone captures
- **Problem solved**: Capture varies by phone, route and OS/WhatsApp update.
- **Est. AI**: 01:00

##### FEAT-012.1.1: Guided capability test
- **Est. AI**: 00:45

- **US-046** — **As the** owner **I want** a guided 30 s test call per route with a "You ✓/✗, Other ✓/✗" result **So that** I trust recordings (`Est. AI: 00:45`)
  - [ ] Reuses silence detection from FEAT-001.1.1; result saved with date and OS/WhatsApp versions

**Exit Criteria** (test run `00:15`) — milestone is done only when all boxes are checked:
- [ ] **Manual / UAT**: test results match the M-001.1 matrix on both phones (US-046)

### REQ-013: Offline mixed-language transcription (Should Have)
- **Outcome**: Any recording can be turned, offline and for free, into one romanized Hindi/Telugu/English transcript with native script beneath, speaker labels, time alignment and search.
- **Source**: FR-029, FR-041, FR-042, FR-044, FR-045, FR-046, FR-048, NFR-016, NFR-017, NFR-018.
- **Est. AI**: 06:45

#### Milestone M-013.1: Best free model chosen
- **Problem solved**: No public benchmark shows which free offline model handles Hindi+Telugu+English mixing.
- **Est. AI**: 01:20

##### FEAT-013.1.1: Model bake-off
- **Est. AI**: 01:00

- **US-047** — **As the** owner **I want** candidate models scored on 10 of my real calls **So that** we pick on evidence (`Est. AI: 00:45`)
  - [ ] Candidates: whisper.cpp (small, medium), AI4Bharat IndicWhisper, IndicConformer via sherpa-onnx
  - [ ] Reports WER, speed (× call duration) on F42 and A73, peak RAM, model size
- **US-048** — **As the** owner **I want** each candidate's licence checked **So that** it is free with no subscription (`Est. AI: 00:15`)
  - [ ] Licence (MIT/Apache-2.0/CC-BY etc.) and source recorded per model

**Exit Criteria** (test run `00:20`) — milestone is done only when all boxes are checked:
- [ ] **Performance**: chosen model WER ≤ 30% (NFR-016), ≤ 2× call duration on F42 (NFR-017), ≤ 1 GB, ≤ 1.5 GB RAM (NFR-018)
- [ ] **Manual / UAT**: owner approves the chosen model and licence (US-047, US-048)

#### Milestone M-013.2: Recordings transcribed in background
- **Problem solved**: No way to turn audio into text on the phone.
- **Est. AI**: 02:35

##### FEAT-013.2.1: Local model management
- **Est. AI**: 00:30

- **US-049** — **As the** owner **I want** to load the model from a local file or the APK **So that** no network is ever needed (`Est. AI: 00:30`)
  - [ ] Import via file picker; checksum verified; app still has no `INTERNET` permission (FR-048)

##### FEAT-013.2.2: Transcription jobs
- **Est. AI**: 01:35

- **US-050** — **As the** owner **I want** to transcribe a recording on demand **So that** I can read it (`Est. AI: 00:45`)
  - [ ] Runs in background via WorkManager; app usable meanwhile
- **US-051** — **As the** owner **I want** new recordings auto-transcribed while charging **So that** transcripts are ready without effort (`Est. AI: 00:20`)
  - [ ] Off by default; only runs on charger and idle
- **US-052** — **As the** owner **I want** progress shown and jobs resumed after interruption **So that** long calls are not restarted (`Est. AI: 00:30`)
  - [ ] Resume from last finished segment after kill/reboot

**Exit Criteria** (test run `00:30`) — milestone is done only when all boxes are checked:
- [ ] **Unit tests**: job state and resume tests pass; ≥ 80% coverage (FEAT-013.2.2)
- [ ] **Integration tests**: 10-min recording transcribed on F42 in ≤ 20 min; killed job resumes (US-050, US-052)
- [ ] **Security**: merged manifest still has no `INTERNET` (US-049)

#### Milestone M-013.3: Transcript readable as a conversation
- **Problem solved**: Raw ASR output is unreadable without speakers, order and links to audio.
- **Est. AI**: 02:05

##### FEAT-013.3.1: Transcript format
- **Est. AI**: 01:15

- **US-053** — **As the** owner **I want** each line in English letters with the original Devanagari/Telugu script beneath **So that** I can read mixed speech easily (`Est. AI: 00:45`)
  - [ ] One file per call, conversation order; native-script line omitted when unavailable
- **US-054** — **As the** owner **I want** lines labelled "You" and the contact's saved name (or number) **So that** I know who said what (`Est. AI: 00:30`)
  - [ ] Uses separate channels when available; otherwise "Speaker A/B", renameable

##### FEAT-013.3.2: Time-aligned view
- **Est. AI**: 00:30

- **US-055** — **As the** owner **I want** to tap a line to jump the audio there and see the current line highlighted **So that** I can verify what was said (`Est. AI: 00:30`)
  - [ ] Seek accuracy ± 1 s

**Exit Criteria** (test run `00:20`) — milestone is done only when all boxes are checked:
- [ ] **Unit tests**: transcript formatter and label mapping tests pass (FEAT-013.3.1)
- [ ] **UI tests**: tap-to-seek and highlight tests pass (US-055)
- [ ] **Manual / UAT**: owner reads 3 real transcripts and accepts the format (US-053, US-054)

#### Milestone M-013.4: Any past conversation findable
- **Problem solved**: Owner cannot find which call mentioned something.
- **Est. AI**: 00:45

##### FEAT-013.4.1: Transcript search
- **Est. AI**: 00:30

- **US-056** — **As the** owner **I want** to search all transcripts and jump to the matching moment **So that** I find details fast (`Est. AI: 00:30`)
  - [ ] Matches romanized and native-script text; results ≤ 500 ms for 200 transcripts

**Exit Criteria** (test run `00:15`) — milestone is done only when all boxes are checked:
- [ ] **Integration tests**: full-text search over 200 seeded transcripts ≤ 500 ms on F42 (US-056)

### REQ-014: Group-call speaker naming (Could Have)
- **Outcome**: Group-call transcripts separate remote speakers and, when the call screen was visible, name them.
- **Source**: FR-049, FR-050.
- **Est. AI**: 02:35

#### Milestone M-014.1: Group speakers distinguishable
- **Problem solved**: All remote voices arrive as one stream, so the transcript can't tell them apart.
- **Est. AI**: 01:00

##### FEAT-014.1.1: Participant labels
- **Est. AI**: 00:45

- **US-057** — **As the** owner **I want** remote voices clustered as "Participant 1/2/…" and renameable per transcript **So that** I can follow group conversations (`Est. AI: 00:45`)
  - [ ] Rename applies to all lines of that participant in the transcript

**Exit Criteria** (test run `00:15`) — milestone is done only when all boxes are checked:
- [ ] **Manual / UAT**: 3-party test call → speakers separated correctly in ≥ 80% of lines (US-057)

#### Milestone M-014.2: Group speakers named from WhatsApp highlight
- **Problem solved**: Owner must rename participants by hand.
- **Est. AI**: 01:35

##### FEAT-014.2.1: Active-speaker log
Only if M-001.2 found the highlight readable.
- **Est. AI**: 01:15

- **US-058** — **As the** owner **I want** the highlighted speaker's name logged with timestamps while the call screen is on **So that** the transcript can name them (`Est. AI: 00:45`)
  - [ ] Uses view tree if possible, else screenshots ≤ 3/s; stops when screen off or WhatsApp in background
- **US-059** — **As the** owner **I want** the log mapped onto transcript segments with clusters filling gaps **So that** most lines get real names (`Est. AI: 00:30`)
  - [ ] Lines without highlight data keep "Participant N"

**Exit Criteria** (test run `00:20`) — milestone is done only when all boxes are checked:
- [ ] **Unit tests**: log-to-segment mapping tests pass (US-059)
- [ ] **Manual / UAT**: screen-on group call → ≥ 80% of remote lines correctly named (US-058)
- [ ] **Performance**: ≤ 3% extra battery per hour from screenshot sampling

### REQ-015: Export and retention (Could Have)
- **Outcome**: Owner can deliberately export one recording, and optionally auto-delete old ones.
- **Source**: FR-020, FR-021.
- **Est. AI**: 01:20

#### Milestone M-015.1: One recording exportable on purpose
- **Problem solved**: Owner occasionally needs a copy outside the app.
- **Est. AI**: 00:40

##### FEAT-015.1.1: Manual export
- **Est. AI**: 00:30

- **US-060** — **As the** owner **I want** to export a single recording to a location I choose **So that** I keep a copy when needed (`Est. AI: 00:30`)
  - [ ] Confirmation dialog; decrypted copy via system file picker; never automatic

**Exit Criteria** (test run `00:10`) — milestone is done only when all boxes are checked:
- [ ] **Manual / UAT**: exported file plays in another player; no export without confirmation (US-060)

#### Milestone M-015.2: Old recordings removed automatically
- **Problem solved**: Storage fills with recordings no longer needed.
- **Est. AI**: 00:40

##### FEAT-015.2.1: Retention policy
- **Est. AI**: 00:30

- **US-061** — **As the** owner **I want** recordings older than N days auto-deleted, off by default **So that** storage stays under control (`Est. AI: 00:30`)
  - [ ] Shows what will be deleted before the first purge; starred recordings never purged

**Exit Criteria** (test run `00:10`) — milestone is done only when all boxes are checked:
- [ ] **Unit tests**: retention selection tests pass incl. starred exclusion (US-061)

### REQ-016: Transcript editing (Could Have)
- **Outcome**: Owner can fix ASR mistakes and copy text.
- **Source**: FR-047.
- **Est. AI**: 00:40

#### Milestone M-016.1: Transcript corrections saved
- **Problem solved**: Mixed-language ASR makes mistakes the owner can't fix.
- **Est. AI**: 00:40

##### FEAT-016.1.1: Edit and copy
- **Est. AI**: 00:30

- **US-062** — **As the** owner **I want** to edit a line and copy one line or the whole transcript **So that** the text is correct and reusable (`Est. AI: 00:30`)
  - [ ] Edits persist and are searchable; copy goes to clipboard only

**Exit Criteria** (test run `00:10`) — milestone is done only when all boxes are checked:
- [ ] **UI tests**: edit, save, copy tests pass (US-062)

### REQ-017: Speakerphone fallback (Could Have)
- **Outcome**: If M-001.1 shows the remote side is silent, the owner can opt in per call to capture it via loudspeaker.
- **Source**: FR-005. **Build only if** the M-001.1 decision requires it.
- **Est. AI**: 00:40

#### Milestone M-017.1: Remote side capturable on demand
- **Problem solved**: Remote audio is silent on earpiece/Bluetooth on this phone.
- **Est. AI**: 00:40

##### FEAT-017.1.1: Per-call speaker mode
- **Est. AI**: 00:30

- **US-063** — **As the** owner **I want** a "Capture other side (speaker)" action on the recording notification **So that** I can choose it only when needed (`Est. AI: 00:30`)
  - [ ] Switches route to loudspeaker and records via `MIC`; capture method saved on the recording

**Exit Criteria** (test run `00:10`) — milestone is done only when all boxes are checked:
- [ ] **Manual / UAT**: remote side intelligible in 3/3 test calls on both phones (US-063)

### REQ-018: Windows desktop recorder (Should Have — Phase 2)
- **Outcome**: WhatsApp Desktop calls on Windows are recorded, both sides, to a local folder with its own allowlist.
- **Source**: FR-026; FR-028 Won't (no shared allowlist).
- **Est. AI**: 02:45

#### Milestone M-018.1: WhatsApp Desktop calls recorded
- **Problem solved**: Calls taken on the PC are not recorded.
- **Est. AI**: 02:45

##### FEAT-018.1.1: Desktop call detection
- **Est. AI**: 00:45

- **US-064** — **As the** owner **I want** WhatsApp Desktop calls detected automatically **So that** recording needs no action (`Est. AI: 00:45`)
  - [ ] Start/end detected ≤ 2 s from WhatsApp's audio session and call window

##### FEAT-018.1.2: Process loopback + mic capture
- **Est. AI**: 00:45

- **US-065** — **As the** owner **I want** WhatsApp's audio and my mic recorded on separate channels **So that** both sides are clean (`Est. AI: 00:45`)
  - [ ] Per-process loopback captures only WhatsApp; ≤ 1 MB/min

##### FEAT-018.1.3: Desktop library and allowlist
- **Est. AI**: 00:45

- **US-066** — **As the** owner **I want** a local allowlist, list and player on Windows **So that** desktop matches the phone experience (`Est. AI: 00:45`)
  - [ ] Files in a user-chosen local folder; no network access by the app

**Exit Criteria** (test run `00:30`) — milestone is done only when all boxes are checked:
- [ ] **Unit tests**: detector and capture config tests pass (FEAT-018.1.1, FEAT-018.1.2)
- [ ] **End-to-end**: 5 desktop calls recorded with both sides audible (US-064, US-065)
- [ ] **Security**: firewall log shows 0 outbound connections from the app (US-066)

### REQ-019: WhatsApp Web recorder (Could Have — Phase 3)
- **Outcome**: WhatsApp Web calls in Chrome/Edge are recorded locally.
- **Source**: FR-027.
- **Est. AI**: 01:25

#### Milestone M-019.1: WhatsApp Web calls recorded
- **Problem solved**: Calls taken in the browser are not recorded.
- **Est. AI**: 01:25

##### FEAT-019.1.1: Browser extension recorder
- **Est. AI**: 01:05

- **US-067** — **As the** owner **I want** a browser extension that records the WhatsApp Web tab audio plus my mic **So that** browser calls are captured (`Est. AI: 00:45`)
  - [ ] Uses `tabCapture`; optional video; starts from toolbar or on call detection
- **US-068** — **As the** owner **I want** recordings saved to a local folder **So that** nothing is uploaded (`Est. AI: 00:20`)
  - [ ] Extension declares no host permissions beyond web.whatsapp.com; no network calls

**Exit Criteria** (test run `00:20`) — milestone is done only when all boxes are checked:
- [ ] **End-to-end**: 3 WhatsApp Web calls recorded with both sides audible (US-067, US-068)

## 6. User Experience
### Entry Points
- Automatic: WhatsApp call starts → app detects and records (allowlist) or prompts (others).
- Recording notification / Quick Settings tile during a call.
- App icon → home dashboard.

### Core Experience
| Step | Action | System Response | Success State |
|------|--------|-----------------|---------------|
| 1 | Install APK via ADB, open app | Onboarding checklist with reasons and deep links | All checks green in ≤ 3 min |
| 2 | Add contacts to "Always record" | Contact picker; duplicates warned | Contacts listed |
| 3 | Allowlisted contact calls; owner answers on any route | Recording starts ≤ 2 s; notification with timer | File saved at hang-up; "Recorded 12m with Mom" |
| 4 | Non-allowlisted call | "Record this call?" notification | One tap starts recording |
| 5 | Open app | Dashboard: status, recent, storage | Play any recording in ≤ 2 taps |
| 6 | Tap Transcribe (later) | Background job with progress | Romanized transcript with native script, speakers, tap-to-seek |

### Edge Cases
- Call waiting / second call; voice→video switch; reconnects; route changes; group joins/leaves.
- App killed or phone rebooted mid-call → recovered partial file.
- Accessibility service disabled by update or Samsung battery rules → alert with one-tap fix.
- Low storage → warn at 500 MB, stop cleanly at 100 MB.
- Video consent dismissed → audio-only, reason logged.
- WhatsApp UI/notification text changes → detection may fail; health monitor flags missed calls if detectable.

### Pages & Screens
Onboarding checklist · Home dashboard · Library (grouped by contact) · Player (+ transcript pane) · Allowlist · Settings (mode, theme, lock, retention, transcription) · Capability test · Transcript search.

## 7. Technical Considerations
- **Capture**: user-enabled accessibility service is the only non-root path to call audio on Android 13–16; exact source/route behaviour decided by M-001.1. `AudioPlaybackCapture` excludes call audio.
- **Detection**: WhatsApp notifications + accessibility events; only display names available (no numbers for saved contacts). Fragile against WhatsApp updates → keep rules data-driven and logged.
- **Platform**: minSdk 33 (F42, Android 13), targetSdk 36 (A73, Android 16). Restricted settings must be allowed for the sideloaded app. Samsung battery must be "Unrestricted". Install via ADB (developer verification exempt).
- **Privacy**: no `INTERNET`; `allowBackup=false`; app-private storage; AES-256-GCM with Keystore key; no plaintext temp files.
- **Video**: MediaProjection needs one consent tap per session on Android 14+.
- **Transcription**: free, open-licensed offline models only (whisper.cpp, IndicWhisper, IndicConformer/sherpa-onnx); models loaded from local file/APK; WorkManager background jobs.
- **Windows**: per-process WASAPI loopback (Windows 10 2004+) for WhatsApp Desktop; **Web**: Chrome `tabCapture`.
- **Challenges**: remote-side capture may be impossible on unrooted Samsung (biggest risk); WhatsApp UI changes; Samsung background killing; transcription accuracy for 3-way code-mixing.

## 8. Success Metrics
- Allowlisted call capture: ≥ 95% produce a playable file — _2-week trial log (NFR-001)._
- Remote side intelligible: ≥ 90% of recordings where the device supports it — _manual spot check of 20 calls._
- Network egress: 0 bytes — _manifest check + network monitor._
- Call quality: 0 drops/degradation attributable to app — _owner log over trial (NFR-008)._
- Setup time: ≤ 3 min — _timed fresh install (NFR-013)._
- Transcript WER: ≤ 30% — _10-call owner test set (NFR-016)._

## 9. Delivery Sequence
| Order | Milestone | Requirement | Priority | Release | Est. AI | Depends on | Exit criteria met |
|-------|-----------|-------------|----------|---------|---------|------------|-------------------|
| 1 | M-001.1 Audio capture matrix known | REQ-001 | Must | Spike | 01:45 | — | [ ] |
| 2 | M-001.2 WhatsApp call signals known | REQ-001 | Must | Spike | 01:05 | — | [ ] |
| — | **Decision gate**: choose capture strategy from M-001.1; if remote side silent → schedule M-017.1 or move REQ-018 earlier | | | | | M-001.1 | [ ] |
| 3 | M-008.1 Designs approved | REQ-008 | Must | v1.0 | 01:00 | — | [ ] |
| 4 | M-005.1 Nothing can leave the device | REQ-005 | Must | v1.0 | 00:50 | — | [ ] |
| 5 | M-007.1 Setup in ≤ 3 min | REQ-007 | Must | v1.0 | 01:20 | M-008.1 | [ ] |
| 6 | M-002.1 Voice calls detected | REQ-002 | Must | v1.0 | 02:05 | M-001.2, M-007.1 | [ ] |
| 7 | M-003.1 Call audio recorded | REQ-003 | Must | v1.0 | 01:55 | M-001.1, M-002.1 | [ ] |
| 8 | M-005.2 Encryption at rest | REQ-005 | Must | v1.0 | 01:35 | M-003.1 | [ ] |
| 9 | M-004.1 Allowlisted calls auto-record | REQ-004 | Must | v1.0 | 01:10 | M-003.1 | [ ] |
| 10 | M-004.2 Any call on demand | REQ-004 | Must | v1.0 | 01:05 | M-003.1 | [ ] |
| 11 | M-006.1 Dashboard and library | REQ-006 | Must | v1.0 | 01:40 | M-003.1, M-008.1 | [ ] |
| 12 | M-002.2 Video and group detected | REQ-002 | Must | v1.0 | 01:15 | M-002.1 | [ ] |
| 13 | M-003.2 Any route and group calls | REQ-003 | Must | v1.0 | 01:25 | M-002.2, M-003.1 | [ ] |
| 14 | M-003.3 No recording lost | REQ-003 | Must | v1.0 | 01:35 | M-005.2 | [ ] |
| 15 | M-007.2 Silent failures prevented | REQ-007 | Must | v1.0 | 01:05 | M-007.1 | [ ] |
| 16 | M-008.2 App looks and feels native | REQ-008 | Must | v1.0 | 02:00 | M-006.1, M-007.1 | [ ] |
| 17 | M-012.1 Capability test | REQ-012 | Should | v1.0 | 01:00 | M-003.2 | [ ] |
| 18 | M-011.1 Browsable by contact | REQ-011 | Should | v1.0 | 01:15 | M-006.1 | [ ] |
| 19 | M-011.2 Comfortable playback | REQ-011 | Should | v1.0 | 01:00 | M-006.1 | [ ] |
| 20 | M-011.3 Library lock | REQ-011 | Should | v1.0 | 00:40 | M-006.1 | [ ] |
| 21 | M-009.1 Video calls recordable | REQ-009 | Should | v1.1 | 01:50 | M-002.2, M-003.1 | [ ] |
| 22 | M-010.1 Group allowlist | REQ-010 | Should | v1.1 | 00:45 | M-004.1, M-002.2 | [ ] |
| 23 | M-010.2 Per-contact mode | REQ-010 | Should | v1.1 | 00:30 | M-009.1 | [ ] |
| 24 | M-013.1 Best free model chosen | REQ-013 | Should | v1.2 | 01:20 | M-003.1 | [ ] |
| 25 | M-013.2 Background transcription | REQ-013 | Should | v1.2 | 02:35 | M-013.1 | [ ] |
| 26 | M-013.3 Readable transcript | REQ-013 | Should | v1.2 | 02:05 | M-013.2, M-011.2 | [ ] |
| 27 | M-013.4 Transcript search | REQ-013 | Should | v1.2 | 00:45 | M-013.3 | [ ] |
| 28 | M-016.1 Transcript editing | REQ-016 | Could | v1.2 | 00:40 | M-013.3 | [ ] |
| 29 | M-014.1 Group speakers distinguishable | REQ-014 | Could | v1.3 | 01:00 | M-013.3 | [ ] |
| 30 | M-014.2 Names from WhatsApp highlight | REQ-014 | Could | v1.3 | 01:35 | M-014.1, M-001.2 | [ ] |
| 31 | M-015.1 Manual export | REQ-015 | Could | v1.3 | 00:40 | M-005.2 | [ ] |
| 32 | M-015.2 Retention | REQ-015 | Could | v1.3 | 00:40 | M-006.1 | [ ] |
| 33 | M-017.1 Speakerphone fallback | REQ-017 | Could | If gate requires | 00:40 | M-001.1, M-003.1 | [ ] |
| 34 | M-018.1 WhatsApp Desktop recorder | REQ-018 | Should | v2 | 02:45 | — | [ ] |
| 35 | M-019.1 WhatsApp Web recorder | REQ-019 | Could | v3 | 01:25 | — | [ ] |
| **Total** | | | | | **46:00** | | |

## 10. Open Questions
- [ ] **Capture strategy** — decided at the gate after M-001.1 (both sides / own-voice-only / Windows-first / speakerphone opt-in).
- [ ] **Export (FR-020)** — does "never shared" allow deliberate single-file export? PRD assumes yes, manual only, with confirmation (REQ-015, Could).
- [ ] **Group auto-record (FR-031)** — PRD assumes "auto-record if the group name is allowlisted; otherwise prompt". Confirm.
- [ ] **Uneven group volume** — acceptable that remote participants may be louder/quieter than the owner?
- [ ] **WhatsApp UI changes** — accept ongoing maintenance of detection rules after WhatsApp updates?
- [ ] **Live transcription (FR-051)** — revisit after REQ-013 ships (A73 only, 5–10 s lag).
