# 05 — Accounts & infrastructure that need your input

Nothing below has been created. Each item needs your credentials, payment or a decision.

| When | Item | Why | Cost (approx.) |
|---|---|---|---|
| Now | **Install Git** (+ GitHub CLI) | Version control. Not installed on this machine. | Free |
| Now | **Private GitHub repository** `vynq` | Source and CI. You create it, or authorise `gh` and I create it. | Free |
| ~~Now~~ | ~~Decision: libsignal AGPL-3.0 OK?~~ **Decided 2026-11-01: yes.** The apps are AGPL-3.0 (see architecture §5). | — | — |
| Phase 7 | **App Store + AGPL review** | Apple's terms conflict with AGPL for apps you don't hold copyright to. Needs a decision before iOS distribution. | Possibly legal advice |
| Phase 2 | **Supabase project** (region near you) | Auth, database, storage, functions. Provide the URL + anon key via `local.properties`, never committed. | Free tier → $25/mo Pro |
| Phase 2 | Email sender (Supabase SMTP or Resend/Postmark) + a domain | Reliable OTP mail. | Domain ~$10–15/yr |
| Phase 3 | **Firebase project** (FCM only, Analytics disabled) | Android push. `google-services.json` is kept out of git. | Free |
| Phase 4 | **LiveKit Cloud** project (dev) → later a **VPS** for self-hosted LiveKit + TURN (2 vCPU, near users) | Media SFU/TURN. | Free tier → ~$10–40/mo VPS |
| Phase 4 | Physical devices: Xiaomi 17 Pro, 17T, USB debugging enabled | Camera/telecom testing. Emulators can't validate call quality. | — |
| Phase 7 | **Apple Developer Program** | Signing, APNs/VoIP certificates, TestFlight. | $99/yr |
| Phase 7 | macOS build host (a Mac or GitHub `macos` runners) | Xcode builds. Windows cannot sign iOS apps. | Runners billed per minute on private repos |
| Phase 8 | Android release keystore (you hold the backup) + Play Console if publishing | Release signing. | $25 one-off (Play) |
| Before public use | **Name clearance**: "vynq" trademark search (EUIPO/USPTO/WIPO), domain (`vynq.app`, `vynq.com`), social handles | Not verified. Legal availability is **not** claimed. | — |
