# vynq development log

## 2026-11-01: Live Supabase project

- Created `vynq` (ap-southeast-1, nearest to Malaysia). Your signup's default Ireland project is untouched.
- Migration applied, `delete-account` deployed, auth config pushed (6-digit code, 10 min expiry; remote defaults were 8 digits / 1 hour).
- **Live smoke test 10/10** (`backend/e2e/smoke.mjs`): sign-in via code, wrong code → `otp_expired`, own-row RLS, username claim + taken/look-alike/reserved errors in the exact shape the app maps, no direct username writes, lookup without email, device isolation + takeover block, refresh rotation + sign-out revocation, deletion cascade. 0 users left afterwards.
- App: publishable key + URL in git-ignored `local.properties`; `Authorization` now sent only with a user token (new-style keys belong in `apikey` only).
- ~~Blocker: sign-in emails.~~ **Resolved:** custom SMTP via a dedicated Gmail account (no domain yet); "Your vynq code" template live. A real code sent through the app's endpoint arrived in the owner's inbox (confirmed by the owner). This created one real account (owner's email, no username yet).
- **Remaining for Phase 2:** run the app on two physical phones with two accounts (sign-in, username, device registration, sign-out, delete).

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
