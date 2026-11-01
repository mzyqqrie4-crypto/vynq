# vynq development log

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
