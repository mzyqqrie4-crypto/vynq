# vynq development log

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

**Not verified / known gaps**
- New-device warning on real phones (unit-tested only so far).
- Battery cost of the background connection: not measured.
- After the phone kills vynq (e.g. swiped away on HyperOS), messages wait until the periodic job or next app start; calls will need a decision on FCM / vendor push (architecture §7, open decision).
- The server sees who messages whom and when (no sealed sender yet), and the OS sees notification text if previews are on.
- New-device approval from an existing device (threat model T4) isn't built; contacts are warned instead.
- Unit tests now need Java 21 (CI installs it; locally a portable Temurin 21 is configured in the user's Gradle settings).


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
