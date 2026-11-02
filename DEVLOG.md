# vynq development log

## 2026-11-02: Phase 6 — Fusion Mode experiment (branch `phase6/fusion-experiment`)

**Implemented**
- `experiments/fusion` (Fusion Probe): a separate Android app, never shipped with vynq. Keeps mobile data up next to Wi-Fi (`requestNetwork`), binds one UDP socket to each network and probes both with STUN Binding requests every 100 ms on a shared clock to Cloudflare's public STUN server (owner decision: no relay server of ours). Logs round trip / lost / down per probe and network events. 3 unit tests (STUN encoding).
- `tools/fusion_report.py`: per-path loss, RTT p50/p95/p99, late replies, outages, plus estimates of best-path selection and duplication from the simultaneous probes.

**Tested (executed)** on the Xiaomi 17 Pro (the only phone with mobile data), home Wi-Fi + LTE; full tables and the analysis in docs/06-fusion-mode-feasibility.md, raw data in `docs/measurements/phase6/`:
- 10 min, untouched: Wi-Fi 1.58 % loss, 5 outages (≤0.5 s), p99 109 ms; LTE 0.20 %, 1 outage (1.1 s), p99 56 ms. Duplicated on both (est.): **0 % loss, no outages, p99 49 ms**. Best path per second (est.): 1.05 %.
- 5 min with Wi-Fi off for 65 s: LTE, already up, went silent for 8.8 s and stayed congested while it carried everything (8.53 % loss, p95 242 ms). Duplication (est.) 7.20 %, best path 8.46 %: neither hides a full Wi-Fi drop.

**Phase 6 acceptance (docs/04-roadmap.md)**
| Criterion | Result |
|---|---|
| Feasibility report with measurements for every scenario in doc 06 | ⚠️ Wi-Fi only / LTE only / both / Wi-Fi drop measured (both paths probed together, so each path alone is in the same runs); induced loss/jitter, changing quality, freeze count in a call, data and battery cost not measured (no relay server; vynq failover call not run; battery stats not resettable over adb) |
| Ship/no-ship decision recorded | ✅ **No ship now**; possible follow-up: audio-only duplication through a relay (doc 06) |

**Not verified / known gaps**
- Real duplicated or bonded media was never sent: duplication numbers are estimates from probe timing without a relay's extra hop.
- The vynq Wi-Fi→mobile failover call (also Phase 4's handover check) wasn't run.
- iOS (no app-level access to mobile data while on Wi-Fi) and the realme (no mobile data) not tested.
- Installing a new app over wireless debugging is blocked on the Xiaomi (HyperOS "Install via USB" off); the probe was installed from the phone's Downloads folder.

**Blockers:** none.

**Next action:** you review and merge the Phase 6 PR. Then Phase 7 (iOS) per the roadmap, or the open measurements from Phases 4–6.

## 2026-11-02: Phase 5 — video quality, partly measured (branch `phase5/video-quality`)

Written up at the owner's request with most measurements still missing; Phase 5 is **not done** by the roadmap's definition.

**Implemented**
- Already on `main` from Phase 4 work: codec per direction (AV1 → H.265 → H.264, hardware encoders only), one stream per direction (no simulcast: 1:1 calls), quality profiles in Settings → Call quality (Data saver 360p/0.5 Mbps, Automatic 1080p/3.5, High 1080p/5, Maximum 1080p/8 or 1440p/12 when both phones allow it), resolution kept for the whole call (owner decision after adaptive downscaling started calls at ~540p).
- **Heat step-down** (owner decision): quality stays as chosen unless Android reports thermal status **severe** or worse. Then the camera goes down one level (Maximum 1440p → Maximum 1080p → High → Automatic → Data saver), the call screen says "Your phone is very hot, so video was lowered to …", and it steps again every 2 min while the phone stays that hot. Restarting the camera costs a 3–6 s video pause.
- **Measurements** (debug builds only): `CallMetrics` writes one CSV row per second per call — resolution, fps, bitrate, freeze count/duration, frames dropped, loss, jitter and round trip both ways, codecs, Android thermal status, battery temperature and level. Numbers only. `tools/call_report.py` turns the files into the table below. The files from today are in `docs/measurements/phase5/`.
- Fixes found while measuring: after a camera restart the recorder and the Details panel read the stopped stream (stale resolution, a negative bitrate); they now follow the stream that is still sending. The realme's front camera kept its 2560×1440 capture on a new track, so the step-down now sets the capture size explicitly. Debug builds can trigger the step-down by long-pressing the call timer.

**Tested (executed)**
- Android: 77/77 JVM tests (2 new: heat levels ordered by bitrate, every quality setting has a level).
- Calls on the two phones, same Wi-Fi (rows are what each phone **received** and **sent**; averages leave out the first 10 s):

| Call | Phone | Quality | Min | Recv res | Recv fps (avg / p10) | Recv kbps | Freezes (n / s) | Loss % | RTT ms | Send res | Send fps | Send kbps | Codec | Max thermal | Battery °C |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| A | realme | Maximum | 3.7 | 1080×1920 | 27 / 22 | 1546 | 37 / 27.9 | 1.8 | 103 | 1440×2560 | 27 | 345 | H.265 | 3 (severe) | 32.7→36.5 |
| A | Xiaomi | Maximum | 3.7 | 1440×2560 | 27 / 21 | 350 | 23 / 23.3 | 1.3 | 74 | 1080×1920 | 27 | 1576 | H.265 | 0 | 39.4→40.8 |
| Step-down | realme | Maximum | 0.6 | 1080×1920 | 31 / 28 | 2114 | 5 / 2.6 | 1.3 | 107 | 1440→1080 | 17 | 1131 | H.265 | 0 | 32.8→33.5 |
| Step-down | Xiaomi | Maximum | 0.6 | 1440→1080 | 17 / 15 | 1121 | 3 / 1.6 | 0.5 | 76 | 1080×1920 | 30 | 2280 | H.265 | 0 | 36.3 |
| C | realme | Automatic | 0.5 | 1080×1920 | 28 / 24 | 1384 | 5 / 1.8 | 3.8 | 108 | 1080×1920 | 17 | 1550 | H.265 | 0 | 33.5 |
| C | Xiaomi | Automatic | 0.5 | 1080×1920 | 16 / 15 | 1545 | 7 / 2.5 | 4.6 | 101 | 1080×1920 | 30 | 1412 | H.265 | 0 | 35.4 |

- What the numbers say:
  - **Freezes come mostly from this network.** In test A both phones stalled at the same moments (round trip jumping above 400 ms, sending falling to 1–2 fps), and loss stayed at 1–5 %. The realme's upload started at 200–400 kbps for 1440p and took ~90 s to climb, so 1440p from the realme was mostly starved.
  - **The realme hits Android's "severe" thermal status within ~2.5 min at 1440p** (battery only 36.5 °C). The step-down fired; in that build it didn't actually lower the camera (fixed and then verified: 1440×2560 → 1080×1920 on the receiving Xiaomi).
  - **The realme's camera delivers ~17 fps at 1080p** in this room (encoder input, not the network), likely low light; the Xiaomi sends a steady 30.
  - A voice call (1.7 min) was also recorded: round trip 68–86 ms, no video.
- Camera and microphone release after hang-up (from Phase 4) holds for every call above.

**Phase 5 acceptance (docs/04-roadmap.md)**
| Criterion | Result |
|---|---|
| Measured table: received resolution/fps/bitrate/freeze count per network condition | ⚠️ Wi-Fi only (table above); mobile data not measured |
| … vs. baseline app | ⬜ skipped (owner decision: WhatsApp isn't on the Xiaomi) |
| No thermal shutdown in a 30-min call | ⬜ not run (longest call 3.7 min); the heat step-down exists and is verified |
| Profiles change measurable bitrate | ⬜ only Automatic and Maximum measured, on short calls with different network moments; Data saver and High not run |

**Not verified / known gaps**
- No mobile-data, weak-signal or handover measurements; no long call; no battery drain figure (the Xiaomi was likely charging; the realme's calls were too short).
- Freeze counts are WebRTC's (`freezeCount`), not a viewer's judgement.
- Codec evaluation is limited to what these two phones can encode in hardware (H.265, H.264); AV1 is wired up but no test phone has a hardware AV1 encoder.
- Thermal adaptation steps down only on Android's "severe" signal (owner decision); there's no battery-based adaptation.

**Blockers:** none.

**Next action:** you review and merge the Phase 5 PR; the missing measurements (Data saver/High, mobile data, 30-min call, optional WhatsApp baseline) can be run on this branch's tooling at any time and added to `docs/measurements/`.

## 2026-11-02: Phase 4 — 1:1 calls (branch `phase4/calls`)

**Implemented**
- Backend migration `20261104000000_calls.sql`: `calls` table (caller, callee, video, state, timestamps), `create_call` / `update_call` RPCs for the two participants, `call_member` for the service role only. Edge Function `livekit-token` (livekit-server-sdk 2.19.1): checks the JWT, that the device belongs to the caller and that the caller is in the call, then issues a 10-minute token for room = call id, identity `user:device`. LiveKit Cloud (free tier, `vynq-rij14n09.livekit.cloud`); URL, key and secret are Supabase secrets the owner set in the dashboard, never in the repo.
- Signalling over the Signal channel: `call_invite`, `call_answer`, `call_decline`, `call_end` as end-to-end encrypted control messages, also copied to the account's other phones ("answered/declined on another phone"). The invite carries a fresh random 32-byte media key; both phones hand it to LiveKit's frame encryption (AES-GCM), so the LiveKit server forwards frames it can't read. The key never reaches the database or LiveKit.
- Android (LiveKit Android SDK 2.29.1): `CallManager` (one call at a time, busy reply, 45 s ring timeout, missed/declined/duration notes in the chat), `CallService` foreground service (`phoneCall|camera|microphone`), incoming-call notification with Answer/Decline, call screen over the lock screen, vynq's own looping ringtone and vibration (follows silent/vibrate mode), Calls tab built from call notes.
- Call screen: labelled controls (Mute, Speaker/Earpiece, Camera, Flip, Details, End), speaker button in voice calls, "turned their camera off" placeholder, security badge, **Details** panel with live WebRTC numbers (resolution, fps, bitrate both ways, round trip, packet loss, codec both ways, route).
- Honest label: "End-to-end encrypted" only while every encrypted track (ours and theirs) reports an active key and none has failed; otherwise "Encrypted in transit".
- Settings → Notifications → Calls (owner request after the Xiaomi didn't ring): full-screen call permission status, Xiaomi lock-screen/pop-up shortcut, ringtone test.
- **Video quality** (owner requests):
  - Codec per direction: each phone sends the formats it can decode in the invite/answer and sends the best it can **encode in hardware** that the other can play: AV1, then H.265, then H.264 (universal). Software encoders are never chosen. Older builds without the list get H.264. The caller previews its camera and publishes only after the answer.
  - Settings → Call quality decides the resolution and vynq never lowers it during a call (owner decision after trying adaptive downscaling, which started calls at ~540p): Data saver 360p/0.5 Mbps, Automatic 1080p/3.5 Mbps, High 1080p/5 Mbps, Maximum 1080p/8 Mbps, or 1440p/12 Mbps when the other phone's screen is wider than 1080 px and this phone's front camera and hardware encoder do 1440p30 (vendor performance points or measured rates; no data → no).
  - One stream per direction (no simulcast, no adaptive stream): it's a 1:1 call.

**Tested (executed)**
- Backend: 60/60 policy tests (5 for calls). Live: `backend/e2e/calls-smoke.mjs` 5/5 on the real project (token only for the call's two members and their own devices, room-scoped, expires).
- Android: 75/75 JVM tests, including a call invite between two simulated phones on real libsignal (media key delivered end to end) and the codec/1440p choice rules.
- Real phones (Xiaomi 17 Pro, Snapdragon 8 Elite Gen 5, HyperOS CN ↔ realme 14x 5G, Dimensity 6300, ColorOS; Wi-Fi):
  - Voice and video calls both directions; "End-to-end encrypted" confirmed in the logs for audio and video on both phones (`e2ee … OK` for local and remote tracks).
  - Bug found and fixed: the callee stayed on "Connecting" when the caller was already in the room.
  - Xiaomi didn't ring: HyperOS refused the full-screen call notification. Fixed with vynq's own ringtone, opening the call screen directly when vynq is in front, and the Calls checklist; rang and showed the call screen after the owner enabled the permissions (owner confirmed).
  - Speaker in voice calls, camera-off placeholder, labelled icons (owner confirmed).
  - Codec negotiation: both phones chose **H.265** both ways (neither has a hardware AV1 encoder; checked in each phone's codec list). Video stayed end-to-end encrypted with H.265 through LiveKit Cloud.
  - 1080p30 both ways; 1440p from the realme to the Xiaomi on Maximum (the realme's 720 px screen gets 1080p). The realme's camera delivers only 16–25 fps at 1440p; the owner prefers to keep it user-controlled (pick High on such phones).
  - **Camera and microphone released after hang-up:** `dumpsys media.camera` on both phones shows no active camera clients and a DISCONNECT for every vynq CONNECT; `dumpsys audio` shows audio focus abandoned after each call.

**Phase 4 acceptance (docs/04-roadmap.md)**
| Criterion | Result |
|---|---|
| 1:1 video call on Wi-Fi and on mobile data, 30 min without drop | ⬜ not run (calls so far ≤ 3 min, Wi-Fi only) |
| Incoming call rings from killed app state | ⬜ not run. Rings with vynq in the background and the screen locked (background connection alive); with the process killed it depends on the 15-min job (see gaps) |
| Wi-Fi→LTE handover reconnects < 5 s | ⬜ not run |
| Camera/mic released after hang-up (`dumpsys media.camera`) | ✅ both phones |
| Diagnostics panel shows live LiveKit stats | ✅ Details panel, numbers from WebRTC every second |

The test pair is Xiaomi↔realme, not Xiaomi↔Xiaomi as the criterion says.

**Deviations**
- No `ConnectionService` / `core-telecom` yet: calls use a foreground service plus a call-style notification. Consequence: no system call log entry, and Bluetooth car kits / the OS "busy on another call" handling don't know about vynq calls.
- No push provider (owner decision: background connection). A call reaches a phone while vynq's background connection is alive; once the OS kills the process the call is missed and shows as a missed call later.

**Not verified / known gaps**
- Long calls, mobile data and network handover (the three open acceptance rows).
- On a lossy network (3 % loss in the tests) WebRTC raises the bitrate slowly; a full drop in bandwidth estimate makes High/Maximum stutter (resolution is kept by owner choice).
- The LiveKit server sees who is in a call, when, for how long, IP addresses and bitrates (threat model T3, T9).
- Battery and heat during calls: not measured (Phase 5).
- The caller's own "joined" log line was missing in one early test; later tests show it on both phones.

**Blockers:** none.

**Next action:** you review and merge the Phase 4 PR; the three open acceptance checks can be run before or after merging.

## 2026-11-01: Phase 3 — end-to-end encrypted messaging (branch `phase3/messaging`)

**Implemented**
- Backend migration `20261102000000_messaging.sql`: contacts (request by exact username, accept, remove), per-device Signal keys (identity key immutable; signed, Kyber-1024 last-resort and one-time pre-keys; one-time keys popped atomically), ciphertext-only mailbox written/read only through RPCs that check device ownership and accepted-contact state, 30-day expiry, delete on acknowledgement, Realtime publication, private `attachments` bucket readable only by the sender's contacts. `delete-account` now also purges the user's attachments.
- Android: libsignal 0.105.0 (from Signal's own Maven repo, scoped to `org.signal`): PQXDH + Double Ratchet, each device addressed separately with its identity key pinned on first use; plaintext padded to 160-byte blocks; 60-digit safety numbers. Attachments: per-file AES-256-GCM key + SHA-256 digest inside the Signal message; photos re-encoded first so EXIF/location never leaves the phone. One SQLCipher database per device, key wrapped by the Android Keystore, wiped on sign-out.
- Screens: Contacts (add/accept/decline/cancel), Chats (real conversations, unread counts), chat (text, photos, retry, encrypted/verified status), safety number screen (mark verified; a new device for a contact clears it and adds a notice).
- **Notifications without Google push** (owner request, for phones with China ROMs): local notifications after on-device decryption, lock screen shows only "New message", preview setting; a foreground service keeps the Realtime connection (on by default, can be hidden or turned off), a 15-minute WorkManager safety net, restart after reboot/update; Settings → Notifications checklist with links to Xiaomi/OPPO/realme/OnePlus/vivo/Huawei/Honor autostart and battery screens. See docs/01-architecture.md §7.
- APK size: libsignal's desktop natives and test library excluded, native symbols stripped (NDK 29), arm64 only: 639 MB → 47 MB debug.

**Tested (executed)**
- Backend: 48/48 policy tests (23 new). Live: `backend/e2e/messaging-smoke.mjs` 7/7 on the real project (contacts, bundle popping, exact mailbox bytes, storage: contact can read / stranger and anon can't / can't write into another user's folder, Realtime notification, deletion purges attachments and contacts).
- Android: 55/55 JVM tests, including 15 crypto tests and 11 two-phone scenarios that run the real libsignal (desktop build, Java 21): offline queue, replay and tamper rejection, forged pre-key refused, swapped identity key refused, new-device warning, sync to own devices, duplicate delivery, photo integrity, key top-up.
- Real phones (Xiaomi 17 Pro @mzyqqrie ↔ realme 14x 5G @zyqqrie2, Wi-Fi debugging):
  - Contact request and accept; text both ways; photo delivered; safety numbers matched on both phones and were marked verified (owner confirmed).
  - **Offline then online:** vynq force-stopped on the realme, message sent from the Xiaomi. The server held one 266-byte type-2 envelope; none of the message's words appeared in the bytes (**ciphertext only, verified by query**). Opening the realme fetched, decrypted and cleared it within seconds. (A first attempt didn't count: the realme was reopened 40 s after being closed.)
  - **Notifications with vynq in the background and the screen off:** Xiaomi (HyperOS CN) and realme (ColorOS) both showed the message notification. On the Xiaomi this needed notification permission, Autostart and battery "No restrictions"; before Autostart was allowed, HyperOS blocked the post-update restart.
  - Both phones have Google Play services, so "no Google services at all" wasn't tested on a device. vynq doesn't use them either way.

- **New device:** the realme signed out and back in (same account, new device). The Xiaomi showed "has a new device, so your safety number with them changed" and the Verified badge went away (owner confirmed).
- **Bug found and fixed in that test:** sign-out wiped the device's keys but left it active on the server, so the Xiaomi kept encrypting to the dead device (4 messages lost) and every sign-out used up a device slot. Sign-out now calls `revoke_device` first (also drops its keys and undelivered mail). The dead device from the test was retired by hand, as the fix would have done.

- **Sign-out keeps history** (owner request): each account on a phone has its own device id, remembered across sign-out. Tested on the realme: signed out (normal), the Xiaomi sent a message meanwhile, signed back in with the same email: old history still there, the new message arrived (owner confirmed); server shows the same device (no new device, no new keys) and an empty mailbox. "Sign out and erase" retires the device and deletes local data (unit-tested); deleting the account does too. This replaces the sign-out-revokes-device fix above for normal sign-out.

- **History across phones** (owner decision: recovery key). Every text/photo message is also stored on the server encrypted with a per-account history key only the account's phones hold (AES-256-GCM, bound to the record id). A new phone gets the key by approval on an existing phone (sent over Signal) or with the 40-character recovery key, which wraps it (HKDF + GCM; the server stores only the wrapped key). Deleted with the account. Backend 55/55 (7 new), Android 65/65 (3 crypto + 5 two-phone history tests: recovery key, approval, no hand-over without approval, wrong key refused, messages received while locked backed up later).
  - On the phones: the Xiaomi set up @mzyqqrie's history and backed up all 46 existing messages. The realme signed out of @zyqqrie2 (history kept) and signed in as @mzyqqrie, asked for history, the Xiaomi approved, and the full conversation appeared (owner confirmed). Server: @mzyqqrie has 2 phones, 46 records; the realme keeps both accounts' databases separately.
  - Not done on a phone: unlocking with the recovery key (unit-tested only).

**Phase 3 acceptance (docs/04-roadmap.md)**
| Criterion | Result |
|---|---|
| Android↔Android delivered while recipient offline then online | ✅ on the two phones |
| Server DB contains only ciphertext (verified by query) | ✅ envelope bytes checked for the message's words |
| Safety-number verification works | ✅ same digits on both phones, marked verified |
| Key change triggers warning | ✅ new device → notice + verification cleared |
| Attachment round-trip with integrity check | ✅ photo on the phones; tampering rejected in tests |
| Encryption unit tests pass | ✅ 15 crypto + 11 messaging scenarios on real libsignal |

**Not verified / known gaps**
- Battery cost of the background connection: not measured.
- After the phone kills vynq (e.g. swiped away on HyperOS), messages wait until the periodic job or next app start; calls will need a decision on FCM / vendor push (architecture §7, open decision).
- The server sees who messages whom and when (no sealed sender yet), and the OS sees notification text if previews are on.
- New-device approval from an existing device (threat model T4) isn't built; contacts are warned instead.
- Unit tests now need Java 21 (CI installs it; locally a portable Temurin 21 is configured in the user's Gradle settings).
- A phone signed out for more than 30 days loses mail sent in that time (mailbox expiry). Signed-out devices stay active on the server by design; a stale-device cleanup belongs in Phase 8.
- Encrypted history: the server sees how many messages an account has, their sizes and when they were stored, not their content. Anyone with the recovery key *and* the account's email can read the history. Someone who takes over the email can register a phone but still needs approval on an existing phone or the recovery key. Losing every phone and the recovery key loses the history, by design.
- History on a signed-out phone stays readable to anyone who can unlock the phone *and* break into app storage (it's encrypted with a Keystore key, not with the account). Use "Sign out and erase" before handing a phone over.

**Blockers:** none.

**Next action:** you review and merge PR #2. Then Phase 4 (calls), which starts with the push decision in architecture §7.


## 2026-11-01: Phase 2 complete — accounts

**Last check: account deletion from the app.** The owner deleted @test from Settings on the realme. The phone's session file and device id were wiped and it returned to Welcome. Server afterwards: 1 user, 1 profile, 1 device. @test's profile and device were removed by the cascade; @mzyqqrie and the Xiaomi untouched.

**Implemented:** email-code sign-in (6 digits, real email through Gmail SMTP), encrypted session storage, single-flight token refresh, server-side sign-out, usernames with look-alike and reserved-name blocking, device registration (max 5, ids never move between accounts, a new device after sign-out), username change, account deletion. RLS on every table; all writes through RPCs.

**What works on real hardware:** Xiaomi 17 Pro (Android 17) and realme 14x 5G (Android 16) over Wi-Fi debugging. Sign-in, username claim, device naming, session kept across app updates, sign-out, two accounts on two phones, deletion. No crashes seen.

**Tested:** 25 backend policy tests (PGlite), 10/10 live smoke tests, 29 Android JVM tests (incl. 9 screenshots), debug and release builds, CI on both.

**Incomplete / known:**
- "Connected devices" list in Settings still says "Coming soon" (the data is there; the screen isn't).
- Email goes out through a personal-style Gmail account: fine for testing, not for public use (move to a domain + Resend/Postmark).
- USB debugging never worked on the Xiaomi (cable); Wi-Fi only.
- An unexplained moment where the session file didn't show on the first `ls` after re-sign-in (present on the second); not seen again.
- iPhone not tested (iOS app is Phase 7).

**Blockers:** none.

**Next action:** you review and merge PR #1. Then Phase 3: end-to-end encrypted messaging with libsignal.

## 2026-11-01: Two accounts on two physical phones (roadmap acceptance)

- Second phone: realme 14x 5G (RMX3943, Android 16), paired over Wi-Fi debugging. Latest build (sign-out device fix) installed on both phones; the Xiaomi update kept its session.
- Owner signed in on the realme with a second email and claimed @test.
- Server check (admin API, emails masked in output): 2 users, 2 profiles, 2 devices. @mzyqqrie → "Xiaomi 17 Pro"; @test → "realme 14x 5G". Each device belongs to its own account; no shared device ids. No crashes in the realme's crash log.
- Cross-account visibility isn't re-checked from the phones (that would need each user's token); it's covered by the live smoke test (own-row RLS, device isolation).
- **Remaining for Phase 2:** account deletion from the app.

## 2026-11-01: Live Supabase project

- Created `vynq` (ap-southeast-1, nearest to Malaysia). Your signup's default Ireland project is untouched.
- Migration applied, `delete-account` deployed, auth config pushed (6-digit code, 10 min expiry; remote defaults were 8 digits / 1 hour).
- **Live smoke test 10/10** (`backend/e2e/smoke.mjs`): sign-in via code, wrong code → `otp_expired`, own-row RLS, username claim + taken/look-alike/reserved errors in the exact shape the app maps, no direct username writes, lookup without email, device isolation + takeover block, refresh rotation + sign-out revocation, deletion cascade. 0 users left afterwards.
- App: publishable key + URL in git-ignored `local.properties`; `Authorization` now sent only with a user token (new-style keys belong in `apikey` only).
- ~~Blocker: sign-in emails.~~ **Resolved:** custom SMTP via a dedicated Gmail account (no domain yet); "Your vynq code" template live. A real code sent through the app's endpoint arrived in the owner's inbox (confirmed by the owner). This created one real account (owner's email, no username yet).

## 2026-11-01: First real device — Xiaomi 17 Pro (Android 17, HyperOS 4.0), over Wi-Fi debugging

- USB failed (charge-only cable / "Device Descriptor Request Failed"); wireless debugging worked.
- Cold start 300 ms (debug build). No crashes in any run. Edge-to-edge correct.
- Owner signed in with a real emailed code, claimed @mzyqqrie; profile + device verified server-side.
- Fixed: device name came from `Build.MODEL` ("25098PN5AC"); now uses the system device name ("Xiaomi 17 Pro"). Device record refreshes on each signed-in start.
- App update kept the session (no re-sign-in). Sign-out → Welcome; reopen stayed signed out; sign-in again skipped the username step; still one device record (no duplicate).
- Session file `no_backup/session.v1` (1,099 B) checked on the phone: no plaintext token, email or username strings.
- One check couldn't be done the way I wanted: a direct database query for revoked refresh tokens was blocked (it turned TLS verification off). Revocation on sign-out is covered by the live smoke test instead.
- **Remaining for Phase 2:** a second account on a second phone, and account deletion from the app.

## 2026-11-01: Phase 2 in progress — accounts (code complete, not yet run against Supabase)

**Implemented** (branch `phase2/auth-profiles`)
- Repo on GitHub (private). Git + GitHub CLI installed.
- Backend (`backend/`): migration for profiles, usernames, devices, rate limits. RLS on everything, column-level grants, every write through SECURITY DEFINER RPCs in a schema API roles can't reach. Usernames are ASCII-only; a "skeleton" (separators dropped; 0/o, 1/i/l, 5/s, rn/m, vv/w, cl/d folded) blocks look-alikes and reserved names. Max 5 devices; a device id can't move between accounts; revocation is final. `delete-account` Edge Function.
- Android: `SupabaseAuthRepository` (thin OkHttp client; no SDK): email OTP, verify, single-flight token refresh, server-side sign-out, profile, username claim, device registration, account deletion. Session encrypted with an Android Keystore AES-GCM key in no-backup storage. Endpoint comes from git-ignored `local.properties`; without it the app keeps saying honestly that accounts aren't connected.
- Screens: email → 6-digit code (auto-submit, 60 s resend timer, change email) → choose username → main. Settings shows @username/email, change username, sign out, delete account (confirmations). Splash holds until the stored session is read; signing out or an expired session returns to Welcome.
- R8 9.5.28 pinned → Kotlin 2.4 metadata warning gone. CI actions on current majors; new Backend CI job.

**Tested (executed)**
- Backend: 25/25 policy tests on PGlite (real Postgres in WASM) + a Supabase `auth` shim: own-row-only reads, no direct writes, anon blocked, look-alike/reserved names, rate limits, device takeover/limit/revocation, private schema unreachable, cascade on delete.
- Android: 28/28 JVM tests: 11 client tests against a mock server (OTP, wrong code, concurrent refresh = 1 request, dead refresh token wipes session, offline keeps session, RPC error mapping, sign-out, deletion, 401), username rules, Phase 1 tests, 9 screenshots.
- `assembleDebug` ✅, `assembleRelease` ✅ (2.5 MB).

**Not verified**
- Nothing has run against a real Supabase project (none exists yet): OTP email delivery, the real `auth.uid()`, PostgREST error bodies, the Edge Function (Deno not installed here).
- Keystore session storage only runs on a device (Robolectric can't emulate AndroidKeyStore).
- Two accounts on two physical devices (roadmap acceptance) not done.

**Notes**
- OkHttp held at 5.4.0: 5.5 requires compileSdk 37.
- Newer R8 prints a harmless "async parsing" notice with AGP 8.13.

**Next action:** you create the Supabase project; I apply the migration, deploy the function and run the end-to-end checks.

## 2026-11-01: Decision — libsignal (AGPL-3.0)

The owner approved libsignal for messaging E2EE. The client apps will be AGPL-3.0-or-later. Still to do: add a `LICENSE` file when the repository is created, add a source-code link in About, check every new dependency's licence, and review App Store compatibility before Phase 7. Details are in `docs/01-architecture.md` §5. vodozemac stays documented as the fallback.

**Next action:** you install Git. Then Phase 2 starts.

## 2026-10-31: Phase 0 complete, Phase 1 shell delivered

**Implemented**
- Environment inspection → `docs/00-environment.md`. Git, Docker, emulator and Android Studio are missing.
- Architecture + decisions (LiveKit, Supabase, libsignal pending a licence decision, native clients) → `docs/01`. Threat model `02`, data model `03`, roadmap with acceptance criteria `04`, accounts list `05`, Fusion feasibility notes `06`.
- Brand: parametric symbol + custom monoline wordmark generated from `brand/tools/generate.py`. SVG masters, lockups, dark/light/mono app icons, splash, iOS 1024 PNGs, Play icon. Android adaptive (incl. themed monochrome), notification and splash vectors. Tokens in `brand/tokens.json`.
- Android: Gradle multi-module (`app`, `core:designsystem`), pinned versions. Design system: colour/type/spacing/radius/motion tokens, light + dark themes, reduced-motion support, custom 24-grid icon set, glass surface, buttons, text field, list rows/groups, segmented control, empty state, alert, brand mark.
- Screens: system splash → welcome → email entry (validates input; honestly reports that the account service isn't connected) → main shell with glass tab bar (Chats / Calls / Contacts / Settings) → Appearance, Call quality, Network diagnostics, Privacy & security, About.

**What actually works**
- Theme switch (System/Light/Dark) persists via DataStore and recolours the system bars.
- Call-quality preference persists (it isn't applied until Phase 4/5, and the UI says so).
- Network diagnostics show live OS data for every connected network: transport, default, validated, metered, link estimates, interface.
- Notifications row opens the real system notification settings.
- Cloud backup and device transfer are disabled (threat model T10).
- No sample data anywhere. Every list shows a real empty state.

**Tested (executed)**
- `assembleDebug`: ✅. `assembleRelease` (R8, shrink): ✅, 2.5 MB unsigned.
- `testDebugUnitTest`: ✅ 6 logic tests (email validation, preference fallback and round-trip, formatting) plus 8 Roborazzi screenshot renders → `docs/phase1-screens.png`.
- **Not tested on a device**: no phone or emulator was attached. App launch, splash, edge-to-edge and the launcher icon on HyperOS are unverified.

**Known issues / blockers**
- R8 in AGP 8.13 warns that it can't parse Kotlin 2.4 metadata. The build is unaffected (no Kotlin reflection). Fix in Phase 2 by moving to AGP 9.x or pinning a newer R8.
- Glass uses a translucent fill and a fine edge, not backdrop blur. Blur is deferred until it can be measured against call performance.
- Git isn't installed, so no repository has been initialised yet.

**Next action:** you install Git and confirm the libsignal licence. Then start Phase 2: create the Supabase project, add email-OTP auth, username claim and device registration.
