# 04 — Roadmap & acceptance criteria

A phase is **done** only when every criterion has been demonstrated, either on a device or by an executed test, and recorded in `DEVLOG.md`.

| Phase | Scope | Acceptance criteria |
|---|---|---|
| **0 Environment & architecture** | Inspection, architecture, threat model, data model, roadmap | ✅ Docs 00–06 exist; open decisions listed. |
| **1 Brand & shell** | Logo system, icons, tokens, Compose design system, splash, welcome, auth UI, main shell, settings | `assembleDebug` succeeds; app launches on a device; light/dark/system theme switch persists across restarts; every visible control either works or is visibly disabled with a reason; no fake conversation data; unit tests for tokens/preferences pass; reduced-motion respected. |
| **2 Auth & profiles** | Supabase project, email OTP, username claim, profile, device registration, sessions | Two accounts registered on two devices; username uniqueness + confusable check enforced server-side; RLS policy tests pass in CI; sign-out revokes refresh token; account deletion purges rows + storage. |
| **3 Messaging** | Contacts/invites, libsignal sessions, mailbox, SQLCipher store, attachments, verification, notifications without Google push | Android↔Android messages delivered while recipient offline then online; server DB contains only ciphertext (verified by query); safety-number verification works; key change triggers warning; attachment round-trip with integrity check; encryption unit tests pass. |
| **4 Calls** | LiveKit integration, signalling over E2EE channel, ConnectionService/full-screen intent, foreground service, audio routing | 1:1 video call Xiaomi↔Xiaomi on Wi-Fi and on mobile data, 30 min without drop; incoming call rings from killed app state; Wi-Fi→LTE handover reconnects < 5 s; camera/mic released after hang-up (verified via `dumpsys media.camera`); diagnostics panel shows live LiveKit stats. |
| **5 Video quality** | Capture config, simulcast layers, codec choice (VP8/H.264/VP9/AV1 eval), bitrate policy, thermal/battery adaptation, quality profiles | Measured table: received resolution/fps/bitrate/freeze count per network condition vs. baseline app; no thermal shutdown in 30-min call; profiles change measurable bitrate. |
| **6 Fusion experiment** | Standalone multi-path experiment | Feasibility report with measurements for every scenario in doc 06; ship/no-ship decision recorded. |
| **7 iOS** | SwiftUI app, CallKit/PushKit, NSE, Keychain, same protocol | All 6 cross-device call directions pass; Android↔iOS messaging; TestFlight build installed on a real iPhone. |
| **8 Hardening & release** | Security review, perf review, release signing, CI, store/TestFlight prep | Threat model re-reviewed; no secrets in repo (scanner in CI); release APK/AAB signed via CI secrets; perf targets met (below). |

## Performance targets (measured from Phase 1 onwards)
- Cold start to first frame ≤ 500 ms on Xiaomi 17-class hardware (Macrobenchmark).
- Steady scrolling of 500-conversation list: 0 janky frames > 2 % (JankStats).
- Idle in background: no wake locks, no polling; network only on push.
- Call: ≤ 25 % CPU on big cores at 720p30 send/receive after Phase 5 tuning; camera open ≤ 400 ms.
